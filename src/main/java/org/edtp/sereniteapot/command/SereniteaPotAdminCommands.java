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
import net.minecraft.world.Difficulty;
import org.edtp.sereniteapot.i18n.MessageKey;
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
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.profile;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotAdminCommands {
    private static final String PLAYER_ARGUMENT = "player";
    private static final String RADIUS_ARGUMENT = "radius";
    private static final String BUDGET_ARGUMENT = "ms-per-tick";

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
                literal("default-max-radius"),
                argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(
                        0, SereniteaPotRecord.MAX_RADIUS_CHUNKS))
                        .executes(SereniteaPotAdminCommands::setDefaultMaximumRadius));
        route(admin,
                literal("budget"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile()),
                argument(BUDGET_ARGUMENT, DoubleArgumentType.doubleArg(
                        0.0))
                        .executes(SereniteaPotAdminCommands::setPlayerBudget));
        route(admin,
                literal("default-budget"),
                argument(BUDGET_ARGUMENT, DoubleArgumentType.doubleArg(0.0))
                        .executes(SereniteaPotAdminCommands::setDefaultBudget));
        route(admin,
                literal("global-budget"),
                argument(BUDGET_ARGUMENT, DoubleArgumentType.doubleArg(0.0))
                        .executes(SereniteaPotAdminCommands::setGlobalBudget));
        var difficultyTarget = argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile());
        for (Difficulty value : Difficulty.values()) {
            route(difficultyTarget, literal(value.getSerializedName())
                    .executes(context -> setDifficulty(context, value)));
        }
        route(admin, literal("difficulty"), difficultyTarget);
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

    private static int setDefaultMaximumRadius(CommandContext<CommandSourceStack> context) {
        int radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT);
        SereniteaPotManager.catalog().setDefaultMaxRadiusChunks(radius);
        SereniteaPotManager.saveCatalog();
        return success(context, MessageKey.COMMAND_ADMIN_DEFAULT_MAX_RADIUS_SUCCESS,
                radius, (long) radius * 2L + 1L);
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
        if (!enabled) return success(context, MessageKey.COMMAND_ADMIN_DISABLE_SUCCESS, owner);
        return success(context,
                record.isFrozen()
                        ? MessageKey.COMMAND_ADMIN_ENABLE_FROZEN
                        : MessageKey.COMMAND_ADMIN_ENABLE_SUCCESS,
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
            return success(context, MessageKey.COMMAND_ADMIN_MAX_RADIUS_SUCCESS,
                    owner, radius, (long) radius * 2L + 1L);
        }
        if (result instanceof SereniteaPotCreationService.MaximumTrimStarted started) {
            return success(context, MessageKey.COMMAND_ADMIN_MAX_RADIUS_TRIM_STARTED,
                    owner, started.dimensionCount(), radius, started.retainedChunks(), started.generation());
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }

    private static int setPlayerBudget(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.getOrCreateRecord(owner).setBudgetMillisPerTick(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, MessageKey.COMMAND_ADMIN_BUDGET_SUCCESS, owner, budget);
    }

    private static int setDefaultBudget(CommandContext<CommandSourceStack> context) {
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.catalog().setDefaultBudgetMillisPerTick(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, MessageKey.COMMAND_ADMIN_DEFAULT_BUDGET_SUCCESS, budget);
    }

    private static int setGlobalBudget(CommandContext<CommandSourceStack> context) {
        double budget = DoubleArgumentType.getDouble(context, BUDGET_ARGUMENT);
        SereniteaPotManager.catalog().setGlobalBudgetMillisPerTick(budget);
        SereniteaPotManager.saveCatalog();
        return success(context, MessageKey.COMMAND_ADMIN_GLOBAL_BUDGET_SUCCESS, budget);
    }

    private static int setDifficulty(
            CommandContext<CommandSourceStack> context,
            Difficulty difficulty) throws CommandSyntaxException {
        return SereniteaPotTargetCommands.setDifficulty(
                context,
                profile(context, PLAYER_ARGUMENT),
                difficulty,
                MessageKey.ERROR_TARGET_NO_POT);
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return SereniteaPotTargetCommands.status(context, profile(context, PLAYER_ARGUMENT));
    }

    private static int showPerformance(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID owner = profile(context, PLAYER_ARGUMENT);
        SereniteaPotPerformanceSnapshot snapshot = SereniteaPotScheduler.snapshot(owner);
        if (snapshot == null) return failure(context, MessageKey.ERROR_TARGET_NO_CONFIG);
        sendPerformance(context, snapshot);
        return 1;
    }

    private static int showPerformanceList(CommandContext<CommandSourceStack> context) {
        List<SereniteaPotPerformanceSnapshot> all = SereniteaPotScheduler.allSnapshots();
        List<SereniteaPotPerformanceSnapshot> snapshots = all.subList(0, Math.min(10, all.size()));
        if (snapshots.isEmpty()) return success(context, MessageKey.COMMAND_ADMIN_PERF_EMPTY);
        Message heading = message(MessageKey.COMMAND_ADMIN_PERF_HEADING);
        context.getSource().sendSuccess(() -> component(context.getSource(), heading), false);
        for (SereniteaPotPerformanceSnapshot snapshot : snapshots) {
            sendPerformance(context, snapshot);
        }
        return snapshots.size();
    }

    private static void sendPerformance(
            CommandContext<CommandSourceStack> context,
            SereniteaPotPerformanceSnapshot snapshot) {
        Message row = performance(snapshot);
        context.getSource().sendSuccess(() -> component(context.getSource(), row), false);
    }

    private static Message performance(SereniteaPotPerformanceSnapshot snapshot) {
        SereniteaPotRecord record = SereniteaPotManager.record(snapshot.owner());
        Message state;
        if (record != null && !record.isEnabled()) state = message(MessageKey.PERFORMANCE_STATE_DISABLED);
        else if (record != null && record.isFrozen()) state = message(MessageKey.PERFORMANCE_STATE_FROZEN);
        else if (SereniteaPotCreationService.isBusy(snapshot.owner())) {
            state = message(MessageKey.PERFORMANCE_STATE_COPYING);
        } else if (snapshot.skippedTicks() > 0) state = message(MessageKey.PERFORMANCE_STATE_THROTTLED);
        else if (SereniteaPotManager.loaded(snapshot.owner()) != null) {
            state = message(MessageKey.PERFORMANCE_STATE_RUNNING);
        } else state = message(MessageKey.PERFORMANCE_STATE_UNLOADED);
        return message(MessageKey.COMMAND_ADMIN_PERF_ROW,
                snapshot.owner(),
                state,
                format("%.2f", snapshot.consumedMillisPerTick()),
                format("%.2f", snapshot.budgetMillisPerTick()),
                format("%.2f", snapshot.creationMillisPerTick()),
                format("%.3f", snapshot.averageTickMillis()),
                format("%.3f", snapshot.maximumTickMillis()),
                format("%.2f", snapshot.effectiveTps()),
                snapshot.executedTicks(),
                snapshot.skippedTicks());
    }

    private static int deleteTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return SereniteaPotTargetCommands.delete(context, profile(context, PLAYER_ARGUMENT));
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }
}
