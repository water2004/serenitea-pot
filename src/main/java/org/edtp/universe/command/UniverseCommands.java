package org.edtp.universe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.universe.level.UniverseDeletionService;
import org.edtp.universe.level.UniverseInvitationService;
import org.edtp.universe.level.UniverseLifecycleService;
import org.edtp.universe.level.UniverseManager;
import org.edtp.universe.level.UniverseTravelService;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.performance.UniversePerformanceSnapshot;
import org.edtp.universe.performance.UniverseScheduler;
import org.edtp.universe.region.UniverseCreationService;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class UniverseCommands {
    private UniverseCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("universe")
            .executes(UniverseCommands::statusSelf)
            .then(Commands.literal("create")
                .then(Commands.argument("radius", IntegerArgumentType.integer(0)).executes(UniverseCommands::create)))
            .then(Commands.literal("enter")
                .executes(context -> enter(context, context.getSource().getPlayerOrException().getUUID()))
                .then(Commands.argument("owner", GameProfileArgument.gameProfile())
                    .executes(UniverseCommands::enterTarget)))
            .then(Commands.literal("leave").executes(UniverseCommands::leave))
            .then(Commands.literal("unfreeze").executes(UniverseCommands::unfreezeSelf))
            .then(Commands.literal("request")
                .then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(UniverseCommands::request)))
            .then(Commands.literal("requests").executes(UniverseCommands::requests))
            .then(Commands.literal("approve")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(UniverseCommands::approve)
                    .then(Commands.argument("request-id", UuidArgument.uuid())
                        .executes(UniverseCommands::approveFromButton))))
            .then(Commands.literal("deny")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(UniverseCommands::deny)
                    .then(Commands.argument("request-id", UuidArgument.uuid())
                        .executes(UniverseCommands::denyFromButton))))
            .then(Commands.literal("delete")
                .then(Commands.literal("confirm").executes(UniverseCommands::deleteSelf)))
            .then(adminCommands());
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminCommands() {
        return Commands.literal("admin")
            .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
            .then(adminToggle("enable", true))
            .then(adminToggle("disable", false))
            .then(Commands.literal("max-radius")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .then(Commands.argument("radius", IntegerArgumentType.integer(
                            0, UniverseRecord.MAX_RADIUS_CHUNKS))
                        .executes(UniverseCommands::adminMaxRadius))))
            .then(Commands.literal("budget")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .then(Commands.argument("ms-per-second", DoubleArgumentType.doubleArg(0.0, 1000.0))
                        .executes(UniverseCommands::adminBudget))))
            .then(Commands.literal("global-budget")
                .then(Commands.argument("ms-per-second", DoubleArgumentType.doubleArg(0.0, 5000.0))
                    .executes(UniverseCommands::adminGlobalBudget)))
            .then(Commands.literal("status")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(UniverseCommands::adminStatus)))
            .then(Commands.literal("perf")
                .executes(UniverseCommands::adminPerfList)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(UniverseCommands::adminPerf)))
            .then(Commands.literal("delete")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .then(Commands.literal("confirm").executes(UniverseCommands::adminDelete))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminToggle(String name, boolean enabled) {
        return Commands.literal(name).then(
            Commands.argument("player", GameProfileArgument.gameProfile()).executes(context -> {
                UUID owner = profile(context, "player");
                UniverseRecord record = UniverseManager.getOrCreateRecord(owner);
                boolean wasEnabled = record.isEnabled();
                record.setEnabled(enabled);
                if (enabled) {
                    if (!wasEnabled) {
                        UniverseLifecycleService.cancelPendingClose(owner);
                        UniverseScheduler.reset(owner);
                    }
                } else {
                    UniverseLifecycleService.requestClose(owner);
                }
                UniverseManager.saveCatalog();
                String suffix = enabled && record.isFrozen()
                    ? "；tick 仍处于自动冻结，主人可执行 /universe unfreeze"
                    : "";
                return success(context,
                    "已" + (enabled ? "启用" : "禁用") + " " + owner + " 的小宇宙功能" + suffix
                );
            })
        );
    }

    private static int unfreezeSelf(CommandContext<CommandSourceStack> context)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = context.getSource().getPlayerOrException().getUUID();
        UniverseRecord record = UniverseManager.record(owner);
        if (record == null || !record.exists()) return failure(context, "你还没有小宇宙");
        if (!record.isFrozen()) {
            return success(context, "你的小宇宙 tick 当前没有冻结");
        }
        record.setFrozen(false);
        UniverseManager.saveCatalog();
        return success(context, "已解除小宇宙的自动冻结；预算债务仍会继续限制 tick 频率");
    }

    private static int create(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UniverseCreationService.RequestResult result = UniverseCreationService.request(
            player, IntegerArgumentType.getInteger(context, "radius")
        );
        if (result instanceof UniverseCreationService.Accepted accepted) {
            return success(context,
                "开始提取 " + accepted.chunkCount() + " 个区块的完整高度（" + accepted.volume()
                    + " 个方块，代际 " + accepted.generation() + "），过程按 tick 分批执行"
            );
        }
        return failure(context, ((UniverseCreationService.Rejected) result).reason());
    }

    private static int enterTarget(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return enter(context, profile(context, "owner"));
    }

    private static int enter(CommandContext<CommandSourceStack> context, UUID owner) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UniverseTravelService.Result result = UniverseTravelService.enter(
            context.getSource().getPlayerOrException(), owner
        );
        return result == UniverseTravelService.Success.INSTANCE
            ? success(context, "已进入小宇宙")
            : failure(context, ((UniverseTravelService.Rejected) result).reason());
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UniverseTravelService.Result result = UniverseTravelService.leave(context.getSource().getPlayerOrException());
        return result == UniverseTravelService.Success.INSTANCE
            ? success(context, "已离开小宇宙")
            : failure(context, ((UniverseTravelService.Rejected) result).reason());
    }

    private static int request(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UniverseInvitationService.Result result = UniverseInvitationService.request(
            context.getSource().getPlayerOrException(), profile(context, "owner")
        );
        return result == UniverseInvitationService.Accepted.INSTANCE
            ? success(context, "申请已发送，60 秒后过期")
            : failure(context, ((UniverseInvitationService.Rejected) result).reason());
    }

    private static int requests(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Set<UUID> requests = UniverseInvitationService.pending(player.getUUID());
        return requests.isEmpty()
            ? success(context, "当前没有待处理申请")
            : success(context, "待处理申请：" + requests.stream().map(UUID::toString).collect(Collectors.joining(", ")));
    }

    private static int approve(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return approve(context, null);
    }

    private static int approveFromButton(CommandContext<CommandSourceStack> context)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return approve(context, UuidArgument.getUuid(context, "request-id"));
    }

    private static int approve(CommandContext<CommandSourceStack> context, UUID requestId)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UniverseInvitationService.Result result = UniverseInvitationService.approve(
            context.getSource().getPlayerOrException(), profile(context, "player"), requestId
        );
        if (result instanceof UniverseInvitationService.Approved) {
            return success(context, "已批准申请并将玩家送入小宇宙");
        }
        return failure(context, ((UniverseInvitationService.Rejected) result).reason());
    }

    private static int deny(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return deny(context, null);
    }

    private static int denyFromButton(CommandContext<CommandSourceStack> context)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return deny(context, UuidArgument.getUuid(context, "request-id"));
    }

    private static int deny(CommandContext<CommandSourceStack> context, UUID requestId)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UniverseInvitationService.Result result = UniverseInvitationService.deny(
            context.getSource().getPlayerOrException(), profile(context, "player"), requestId
        );
        return result == UniverseInvitationService.Accepted.INSTANCE
            ? success(context, "已拒绝申请")
            : failure(context, ((UniverseInvitationService.Rejected) result).reason());
    }

    private static int deleteSelf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return delete(context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int adminDelete(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return delete(context, profile(context, "player"));
    }

    private static int delete(CommandContext<CommandSourceStack> context, UUID owner) {
        UniverseDeletionService.Result result = UniverseDeletionService.archiveAndReset(context.getSource().getServer(), owner);
        return result == UniverseDeletionService.Success.INSTANCE
            ? success(context, "小宇宙已卸载并永久删除")
            : failure(context, ((UniverseDeletionService.Rejected) result).reason());
    }

    private static int adminMaxRadius(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = profile(context, "player");
        int radius = IntegerArgumentType.getInteger(context, "radius");
        ServerPlayer requester = context.getSource().getPlayer();
        UniverseCreationService.MaximumChangeResult result = UniverseCreationService.changeMaximum(
            context.getSource().getServer(),
            owner,
            radius,
            requester == null ? null : requester.getUUID()
        );
        if (result == UniverseCreationService.MaximumUpdated.INSTANCE) {
            return success(context, owner + " 的最大区块半径已设为 " + radius
                + "（每边 " + ((long) radius * 2L + 1L) + " 个区块）");
        }
        if (result instanceof UniverseCreationService.MaximumTrimStarted started) {
            return success(context,
                "已开始将 " + owner + " 的 " + started.dimensionCount() + " 个维度裁剪到最大区块半径 "
                    + radius + "（保留 " + started.retainedChunks() + " 个区块，代际 "
                    + started.generation() + "）"
            );
        }
        return failure(context, ((UniverseCreationService.Rejected) result).reason());
    }

    private static int adminBudget(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = profile(context, "player");
        double budget = DoubleArgumentType.getDouble(context, "ms-per-second");
        UniverseManager.getOrCreateRecord(owner).setBudgetMillisPerSecond(budget);
        UniverseManager.saveCatalog();
        return success(context, owner + " 的三维度共享预算已设为 " + budget + " ms/s");
    }

    private static int adminGlobalBudget(CommandContext<CommandSourceStack> context) {
        double budget = DoubleArgumentType.getDouble(context, "ms-per-second");
        UniverseManager.catalog().setGlobalBudgetMillisPerSecond(budget);
        UniverseManager.saveCatalog();
        return success(context, "全部小宇宙的全局预算已设为 " + budget + " ms/s");
    }

    private static int statusSelf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return status(context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int adminStatus(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return status(context, profile(context, "player"));
    }

    private static int status(CommandContext<CommandSourceStack> context, UUID owner) {
        UniverseRecord record = UniverseManager.record(owner);
        if (record == null) return failure(context, "没有该玩家的小宇宙配置");
        Double progress = UniverseCreationService.progress(owner);
        String creation = progress == null ? "" : ", 创建进度=" + format("%.1f", progress * 100.0) + "%";
        return success(context,
            "owner=" + owner + ", 存在=" + record.exists() + ", 已加载=" + (UniverseManager.loaded(owner) != null)
                + ", enabled=" + record.isEnabled() + ", frozen=" + record.isFrozen()
                + ", maxRadiusChunks=" + record.getMaxRadiusChunks() + ", budget=" + record.getBudgetMillisPerSecond()
                + "ms/s" + creation
        );
    }

    private static int adminPerf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = profile(context, "player");
        UniversePerformanceSnapshot snapshot = UniverseScheduler.snapshot(owner);
        return snapshot == null
            ? failure(context, "没有该玩家的小宇宙配置")
            : success(context, formatPerformance(snapshot));
    }

    private static int adminPerfList(CommandContext<CommandSourceStack> context) {
        List<UniversePerformanceSnapshot> all = UniverseScheduler.allSnapshots();
        List<UniversePerformanceSnapshot> snapshots = all.subList(0, Math.min(10, all.size()));
        if (snapshots.isEmpty()) return success(context, "暂无小宇宙性能数据");
        context.getSource().sendSuccess(() -> Component.literal("小宇宙性能排行（最近完整 1 秒窗口）："), false);
        for (UniversePerformanceSnapshot snapshot : snapshots) {
            context.getSource().sendSuccess(() -> Component.literal(formatPerformance(snapshot)), false);
        }
        return snapshots.size();
    }

    private static String formatPerformance(UniversePerformanceSnapshot snapshot) {
        UniverseRecord record = UniverseManager.record(snapshot.owner());
        String state;
        if (record != null && !record.isEnabled()) state = "DISABLED";
        else if (record != null && record.isFrozen()) state = "FROZEN";
        else if (UniverseCreationService.isBusy(snapshot.owner())) state = "COPYING";
        else if (snapshot.skippedTicks() > 0) state = "THROTTLED";
        else if (UniverseManager.loaded(snapshot.owner()) != null) state = "RUNNING";
        else state = "UNLOADED";
        return snapshot.owner() + ": state=" + state
            + ", cost=" + format("%.2f", snapshot.consumedMillisLastSecond()) + "/"
            + format("%.2f", snapshot.budgetMillisPerSecond()) + "ms/s"
            + ", copy=" + format("%.2f", snapshot.creationMillisLastSecond()) + "ms/s"
            + ", avg=" + format("%.3f", snapshot.averageTickMillis()) + "ms"
            + ", max=" + format("%.3f", snapshot.maximumTickMillis()) + "ms"
            + ", effectiveTPS=" + format("%.2f", snapshot.effectiveTps())
            + ", run=" + snapshot.executedTicks() + ", skip=" + snapshot.skippedTicks();
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }

    private static UUID profile(CommandContext<CommandSourceStack> context, String argument)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, argument);
        if (profiles.size() != 1) {
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        }
        return profiles.iterator().next().id();
    }

    private static int success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int failure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
