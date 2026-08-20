package org.edtp.sereniteapot.command;

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
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotPerformanceSnapshot;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SereniteaPotCommands {
    private SereniteaPotCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sereniteapot")
            .executes(SereniteaPotCommands::statusSelf)
            .then(Commands.literal("create")
                .then(Commands.argument("radius", IntegerArgumentType.integer(0)).executes(SereniteaPotCommands::create)))
            .then(Commands.literal("enter")
                .executes(context -> enter(context, context.getSource().getPlayerOrException().getUUID()))
                .then(Commands.argument("owner", GameProfileArgument.gameProfile())
                    .executes(SereniteaPotCommands::enterTarget)))
            .then(Commands.literal("leave").executes(SereniteaPotCommands::leave))
            .then(Commands.literal("unfreeze").executes(SereniteaPotCommands::unfreezeSelf))
            .then(Commands.literal("request")
                .then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(SereniteaPotCommands::request)))
            .then(Commands.literal("requests").executes(SereniteaPotCommands::requests))
            .then(Commands.literal("approve")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(SereniteaPotCommands::approve)
                    .then(Commands.argument("request-id", UuidArgument.uuid())
                        .executes(SereniteaPotCommands::approveFromButton))))
            .then(Commands.literal("deny")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(SereniteaPotCommands::deny)
                    .then(Commands.argument("request-id", UuidArgument.uuid())
                        .executes(SereniteaPotCommands::denyFromButton))))
            .then(Commands.literal("delete")
                .then(Commands.literal("confirm").executes(SereniteaPotCommands::deleteSelf)))
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
                            0, SereniteaPotRecord.MAX_RADIUS_CHUNKS))
                        .executes(SereniteaPotCommands::adminMaxRadius))))
            .then(Commands.literal("budget")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .then(Commands.argument("ms-per-second", DoubleArgumentType.doubleArg(0.0, 1000.0))
                        .executes(SereniteaPotCommands::adminBudget))))
            .then(Commands.literal("global-budget")
                .then(Commands.argument("ms-per-second", DoubleArgumentType.doubleArg(0.0, 5000.0))
                    .executes(SereniteaPotCommands::adminGlobalBudget)))
            .then(Commands.literal("status")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(SereniteaPotCommands::adminStatus)))
            .then(Commands.literal("perf")
                .executes(SereniteaPotCommands::adminPerfList)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(SereniteaPotCommands::adminPerf)))
            .then(Commands.literal("delete")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .then(Commands.literal("confirm").executes(SereniteaPotCommands::adminDelete))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminToggle(String name, boolean enabled) {
        return Commands.literal(name).then(
            Commands.argument("player", GameProfileArgument.gameProfile()).executes(context -> {
                UUID owner = profile(context, "player");
                SereniteaPotRecord record = SereniteaPotManager.getOrCreateRecord(owner);
                boolean wasEnabled = record.isEnabled();
                record.setEnabled(enabled);
                if (enabled) {
                    if (!wasEnabled) {
                        SereniteaPotLifecycleService.cancelPendingClose(owner);
                        SereniteaPotScheduler.reset(owner);
                    }
                } else {
                    SereniteaPotLifecycleService.requestClose(owner);
                }
                SereniteaPotManager.saveCatalog();
                String suffix = enabled && record.isFrozen()
                    ? "；tick 仍处于自动冻结，主人可执行 /sereniteapot unfreeze"
                    : "";
                return success(context,
                    "已" + (enabled ? "启用" : "禁用") + " " + owner + " 的尘歌壶功能" + suffix
                );
            })
        );
    }

    private static int unfreezeSelf(CommandContext<CommandSourceStack> context)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = context.getSource().getPlayerOrException().getUUID();
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null || !record.exists()) return failure(context, "你还没有尘歌壶");
        if (!record.isFrozen()) {
            return success(context, "你的尘歌壶 tick 当前没有冻结");
        }
        record.setFrozen(false);
        SereniteaPotManager.saveCatalog();
        return success(context, "已解除尘歌壶的自动冻结；预算债务仍会继续限制 tick 频率");
    }

    private static int create(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SereniteaPotCreationService.RequestResult result = SereniteaPotCreationService.request(
            player, IntegerArgumentType.getInteger(context, "radius")
        );
        if (result instanceof SereniteaPotCreationService.Accepted accepted) {
            return success(context,
                "开始按区块批量提取 " + accepted.chunkCount() + " 个区块的完整高度（代际 "
                    + accepted.generation() + "），执行 /sereniteapot 可查询进度"
            );
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }

    private static int enterTarget(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return enter(context, profile(context, "owner"));
    }

    private static int enter(CommandContext<CommandSourceStack> context, UUID owner) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        SereniteaPotTravelService.Result result = SereniteaPotTravelService.enter(
            context.getSource().getPlayerOrException(), owner
        );
        return result == SereniteaPotTravelService.Success.INSTANCE
            ? success(context, "已进入尘歌壶")
            : failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        SereniteaPotTravelService.Result result = SereniteaPotTravelService.leave(context.getSource().getPlayerOrException());
        return result == SereniteaPotTravelService.Success.INSTANCE
            ? success(context, "已离开尘歌壶")
            : failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
    }

    private static int request(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.request(
            context.getSource().getPlayerOrException(), profile(context, "owner")
        );
        return result == SereniteaPotInvitationService.Accepted.INSTANCE
            ? success(context, "申请已发送，60 秒后过期")
            : failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }

    private static int requests(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Set<UUID> requests = SereniteaPotInvitationService.pending(player.getUUID());
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
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.approve(
            context.getSource().getPlayerOrException(), profile(context, "player"), requestId
        );
        if (result instanceof SereniteaPotInvitationService.Approved) {
            return success(context, "已批准申请并将玩家送入尘歌壶");
        }
        return failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
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
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.deny(
            context.getSource().getPlayerOrException(), profile(context, "player"), requestId
        );
        return result == SereniteaPotInvitationService.Accepted.INSTANCE
            ? success(context, "已拒绝申请")
            : failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }

    private static int deleteSelf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return delete(context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int adminDelete(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return delete(context, profile(context, "player"));
    }

    private static int delete(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotDeletionService.Result result = SereniteaPotDeletionService.deleteAndReset(context.getSource().getServer(), owner);
        return result == SereniteaPotDeletionService.Success.INSTANCE
            ? success(context, "尘歌壶已卸载并永久删除")
            : failure(context, ((SereniteaPotDeletionService.Rejected) result).reason());
    }

    private static int adminMaxRadius(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = profile(context, "player");
        int radius = IntegerArgumentType.getInteger(context, "radius");
        ServerPlayer requester = context.getSource().getPlayer();
        SereniteaPotCreationService.MaximumChangeResult result = SereniteaPotCreationService.changeMaximum(
            context.getSource().getServer(),
            owner,
            radius,
            requester == null ? null : requester.getUUID()
        );
        if (result == SereniteaPotCreationService.MaximumUpdated.INSTANCE) {
            return success(context, owner + " 的最大区块半径已设为 " + radius
                + "（每边 " + ((long) radius * 2L + 1L) + " 个区块）");
        }
        if (result instanceof SereniteaPotCreationService.MaximumTrimStarted started) {
            return success(context,
                "已开始将 " + owner + " 的 " + started.dimensionCount() + " 个维度裁剪到最大区块半径 "
                    + radius + "（保留 " + started.retainedChunks() + " 个区块，代际 "
                    + started.generation() + "）"
            );
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }

    private static int adminBudget(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = profile(context, "player");
        double budget = DoubleArgumentType.getDouble(context, "ms-per-second");
        SereniteaPotManager.getOrCreateRecord(owner).setBudgetMillisPerSecond(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, owner + " 的三维度共享预算已设为 " + budget + " ms/s");
    }

    private static int adminGlobalBudget(CommandContext<CommandSourceStack> context) {
        double budget = DoubleArgumentType.getDouble(context, "ms-per-second");
        SereniteaPotManager.catalog().setGlobalBudgetMillisPerSecond(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, "全部尘歌壶的全局预算已设为 " + budget + " ms/s");
    }

    private static int statusSelf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return status(context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int adminStatus(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return status(context, profile(context, "player"));
    }

    private static int status(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return failure(context, "没有该玩家的尘歌壶配置");
        Double progress = SereniteaPotCreationService.progress(owner);
        String creation = progress == null ? "" : ", 创建进度=" + format("%.1f", progress * 100.0) + "%";
        return success(context,
            "owner=" + owner + ", 存在=" + record.exists() + ", 已加载=" + (SereniteaPotManager.loaded(owner) != null)
                + ", enabled=" + record.isEnabled() + ", frozen=" + record.isFrozen()
                + ", maxRadiusChunks=" + record.getMaxRadiusChunks() + ", budget=" + record.getBudgetMillisPerSecond()
                + "ms/s" + creation
        );
    }

    private static int adminPerf(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID owner = profile(context, "player");
        SereniteaPotPerformanceSnapshot snapshot = SereniteaPotScheduler.snapshot(owner);
        return snapshot == null
            ? failure(context, "没有该玩家的尘歌壶配置")
            : success(context, formatPerformance(snapshot));
    }

    private static int adminPerfList(CommandContext<CommandSourceStack> context) {
        List<SereniteaPotPerformanceSnapshot> all = SereniteaPotScheduler.allSnapshots();
        List<SereniteaPotPerformanceSnapshot> snapshots = all.subList(0, Math.min(10, all.size()));
        if (snapshots.isEmpty()) return success(context, "暂无尘歌壶性能数据");
        context.getSource().sendSuccess(() -> Component.literal("尘歌壶性能排行（最近完整 1 秒窗口）："), false);
        for (SereniteaPotPerformanceSnapshot snapshot : snapshots) {
            context.getSource().sendSuccess(() -> Component.literal(formatPerformance(snapshot)), false);
        }
        return snapshots.size();
    }

    private static String formatPerformance(SereniteaPotPerformanceSnapshot snapshot) {
        SereniteaPotRecord record = SereniteaPotManager.record(snapshot.owner());
        String state;
        if (record != null && !record.isEnabled()) state = "DISABLED";
        else if (record != null && record.isFrozen()) state = "FROZEN";
        else if (SereniteaPotCreationService.isBusy(snapshot.owner())) state = "COPYING";
        else if (snapshot.skippedTicks() > 0) state = "THROTTLED";
        else if (SereniteaPotManager.loaded(snapshot.owner()) != null) state = "RUNNING";
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
