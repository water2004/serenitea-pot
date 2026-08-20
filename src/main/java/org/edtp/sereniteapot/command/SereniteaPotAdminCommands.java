package org.edtp.sereniteapot.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.sereniteapot.i18n.SereniteaPotTranslations.Message;
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
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;
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
        if (!enabled) return success(context, "command.admin.disable.success", owner);
        return success(context,
                record.isFrozen() ? "command.admin.enable.frozen" : "command.admin.enable.success",
                owner);
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
            return success(context, "command.admin.max_radius.success",
                    owner, radius, (long) radius * 2L + 1L);
        }
        if (result instanceof SereniteaPotCreationService.MaximumTrimStarted started) {
            return success(context, "command.admin.max_radius.trim_started",
                    owner, started.dimensionCount(), radius, started.retainedChunks(), started.generation());
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }

    private static int setPlayerBudget(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.getOrCreateRecord(owner).setBudgetMillisPerSecond(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, "command.admin.budget.success", owner, budget);
    }

    private static int setGlobalBudget(CommandContext<CommandSourceStack> context) {
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.catalog().setGlobalBudgetMillisPerSecond(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, "command.admin.global_budget.success", budget);
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return status(context, profile(context, PLAYER_ARGUMENT));
    }

    private static int showPerformance(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        SereniteaPotPerformanceSnapshot snapshot = SereniteaPotScheduler.snapshot(owner);
        return snapshot == null
                ? failure(context, "error.target.no_config")
                : sendPerformance(context, snapshot);
    }

    private static int showPerformanceList(CommandContext<CommandSourceStack> context) {
        List<SereniteaPotPerformanceSnapshot> all = SereniteaPotScheduler.allSnapshots();
        List<SereniteaPotPerformanceSnapshot> snapshots = all.subList(0, Math.min(10, all.size()));
        if (snapshots.isEmpty()) return success(context, "command.admin.perf.empty");
        Message heading = message("command.admin.perf.heading");
        context.getSource().sendSuccess(() -> component(context.getSource(), heading), false);
        for (SereniteaPotPerformanceSnapshot snapshot : snapshots) {
            Message row = performance(snapshot);
            context.getSource().sendSuccess(() -> component(context.getSource(), row), false);
        }
        return snapshots.size();
    }

    private static int sendPerformance(
            CommandContext<CommandSourceStack> context,
            SereniteaPotPerformanceSnapshot snapshot) {
        Message row = performance(snapshot);
        context.getSource().sendSuccess(() -> component(context.getSource(), row), false);
        return 1;
    }

    private static Message performance(SereniteaPotPerformanceSnapshot snapshot) {
        SereniteaPotRecord record = SereniteaPotManager.record(snapshot.owner());
        Message state;
        if (record != null && !record.isEnabled()) state = message("performance.state.disabled");
        else if (record != null && record.isFrozen()) state = message("performance.state.frozen");
        else if (SereniteaPotCreationService.isBusy(snapshot.owner())) state = message("performance.state.copying");
        else if (snapshot.skippedTicks() > 0) state = message("performance.state.throttled");
        else if (SereniteaPotManager.loaded(snapshot.owner()) != null) state = message("performance.state.running");
        else state = message("performance.state.unloaded");
        return message("command.admin.perf.row",
                snapshot.owner(),
                state,
                format("%.2f", snapshot.consumedMillisLastSecond()),
                format("%.2f", snapshot.budgetMillisPerSecond()),
                format("%.2f", snapshot.creationMillisLastSecond()),
                format("%.3f", snapshot.averageTickMillis()),
                format("%.3f", snapshot.maximumTickMillis()),
                format("%.2f", snapshot.effectiveTps()),
                snapshot.executedTicks(),
                snapshot.skippedTicks());
    }

    private static int deleteTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return deletePot(context, profile(context, PLAYER_ARGUMENT));
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }
}
