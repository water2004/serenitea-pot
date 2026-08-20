package org.edtp.sereniteapot.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotPerformanceSnapshot;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.deletePot;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.profile;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;
import static org.edtp.sereniteapot.command.SereniteaPotOwnerCommands.status;

final class SereniteaPotAdminCommands {
    private static final String PLAYER_ARGUMENT = "player";
    private static final String RADIUS_ARGUMENT = "radius";
    private static final String BUDGET_ARGUMENT = "ms-per-second";
    private static final double MAX_PLAYER_BUDGET_MILLIS_PER_SECOND = 1000.0;
    private static final double MAX_GLOBAL_BUDGET_MILLIS_PER_SECOND = 5000.0;

    private SereniteaPotAdminCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> admin = literal("admin")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER));
        route(admin,
                literal("enable"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(context -> setEnabled(context, true)));
        route(admin,
                literal("disable"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(context -> setEnabled(context, false)));
        route(admin,
                literal("max-radius"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile()),
                argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(
                        0, SereniteaPotRecord.MAX_RADIUS_CHUNKS))
                        .executes(SereniteaPotAdminCommands::setMaximumRadius));
        route(admin,
                literal("budget"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile()),
                argument(BUDGET_ARGUMENT, DoubleArgumentType.doubleArg(
                        0.0, MAX_PLAYER_BUDGET_MILLIS_PER_SECOND))
                        .executes(SereniteaPotAdminCommands::setPlayerBudget));
        route(admin,
                literal("global-budget"),
                argument(BUDGET_ARGUMENT, DoubleArgumentType.doubleArg(
                        0.0, MAX_GLOBAL_BUDGET_MILLIS_PER_SECOND))
                        .executes(SereniteaPotAdminCommands::setGlobalBudget));
        route(admin,
                literal("status"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotAdminCommands::showStatus));
        route(admin,
                literal("perf")
                        .executes(SereniteaPotAdminCommands::showPerformanceList),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotAdminCommands::showPerformance));
        route(admin,
                literal("delete"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile()),
                literal("confirm").executes(SereniteaPotAdminCommands::deleteTarget));
        route(root, admin);
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        SereniteaPotRecord record = SereniteaPotManager.getOrCreateRecord(owner);
        boolean wasEnabled = record.isEnabled();
        record.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            SereniteaPotLifecycleService.cancelPendingClose(owner);
            SereniteaPotScheduler.reset(owner);
        } else if (!enabled) {
            SereniteaPotLifecycleService.requestClose(owner);
        }
        SereniteaPotManager.saveCatalog();
        String suffix = enabled && record.isFrozen()
                ? "；tick 仍处于自动冻结，主人可执行 /sereniteapot unfreeze"
                : "";
        return success(context, "已" + (enabled ? "启用" : "禁用") + " " + owner + " 的尘歌壶功能" + suffix);
    }

    private static int setMaximumRadius(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        int radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT);
        ServerPlayer requester = context.getSource().getPlayer();
        SereniteaPotCreationService.MaximumChangeResult result = SereniteaPotCreationService.changeMaximum(
                context.getSource().getServer(), owner, radius,
                requester == null ? null : requester.getUUID());
        if (result == SereniteaPotCreationService.MaximumUpdated.INSTANCE) {
            return success(context, owner + " 的最大区块半径已设为 " + radius
                    + "（每边 " + ((long) radius * 2L + 1L) + " 个区块）");
        }
        if (result instanceof SereniteaPotCreationService.MaximumTrimStarted started) {
            return success(context,
                    "已开始将 " + owner + " 的 " + started.dimensionCount() + " 个维度裁剪到最大区块半径 "
                            + radius + "（保留 " + started.retainedChunks() + " 个区块，代际 "
                            + started.generation() + "）");
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }

    private static int setPlayerBudget(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.getOrCreateRecord(owner).setBudgetMillisPerSecond(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, owner + " 的三维度共享预算已设为 " + budget + " ms/s");
    }

    private static int setGlobalBudget(CommandContext<CommandSourceStack> context) {
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.catalog().setGlobalBudgetMillisPerSecond(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, "全部尘歌壶的全局预算已设为 " + budget + " ms/s");
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return status(context, profile(context, PLAYER_ARGUMENT));
    }

    private static int showPerformance(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        SereniteaPotPerformanceSnapshot snapshot = SereniteaPotScheduler.snapshot(owner);
        return snapshot == null
                ? failure(context, "没有该玩家的尘歌壶配置")
                : success(context, formatPerformance(snapshot));
    }

    private static int showPerformanceList(CommandContext<CommandSourceStack> context) {
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

    private static int deleteTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return deletePot(context, profile(context, PLAYER_ARGUMENT));
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }
}
