package org.edtp.sereniteapot.performance;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/**
 * 在服务器主线程上为尘歌壶 tick 和创建复制共享时间预算。
 *
 * <p>调度器使用玩家级与全局两层令牌桶：执行前按历史耗时预扣，执行后按真实耗时
 * 修正。它可以降低后续 tick 频率，但无法中断一个已经进入模组代码的慢 tick；单次
 * 维度 tick 超过安全阈值时会自动冻结该尘歌壶。</p>
 */
public final class SereniteaPotScheduler {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final long MINIMUM_RESERVATION_NANOS = 50_000L;
    private static final long AUTO_FREEZE_LEVEL_TICK_NANOS = 200_000_000L;

    private static final LinkedHashMap<UUID, OwnerBudget> owners = new LinkedHashMap<>();
    private static double globalTokensNanos;
    private static long serverTicks;

    private SereniteaPotScheduler() {}

    public static void register() {
        ServerTickEvents.START_SERVER_TICK.register(SereniteaPotScheduler::startServerTick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> resetAll());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> resetAll());
    }

    public static boolean beforeLevelTick(ServerLevel level) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (identity == null) {
            return true;
        }
        UUID owner = identity.owner();
        var record = SereniteaPotManager.record(owner);
        if (record == null) {
            return false;
        }
        if (identity.generation() != record.getActiveGeneration()) {
            return false;
        }

        OwnerBudget budget = owners.computeIfAbsent(owner,
            ignored -> new OwnerBudget(record.getBudgetMillisPerSecond()));
        budget.calls++;
        SereniteaPotDimension dimension = identity.dimension();
        if (!record.isEnabled() || record.isFrozen()
            || SereniteaPotCreationService.isBusy(owner)) {
            budget.recordSkip(dimension);
            return false;
        }

        budget.updateLimit(record.getBudgetMillisPerSecond());
        // 先按指数移动平均值预扣；真实耗时可能更高，差额会在 afterLevelTick 形成预算债务。
        double reservation = Math.max(MINIMUM_RESERVATION_NANOS, budget.estimatedNanos);
        if (budget.tokensNanos < reservation || globalTokensNanos < reservation) {
            budget.recordSkip(dimension);
            return false;
        }
        budget.tokensNanos -= reservation;
        globalTokensNanos -= reservation;
        budget.reservations.put(dimension, reservation);
        return true;
    }

    public static void afterLevelTick(ServerLevel level, long elapsedNanos) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (identity == null) {
            return;
        }
        UUID owner = identity.owner();
        OwnerBudget budget = owners.get(owner);
        if (budget == null) {
            return;
        }
        SereniteaPotDimension dimension = identity.dimension();
        double reservation = budget.reservations.getOrDefault(dimension, 0.0);
        budget.reservations.remove(dimension);
        double correction = elapsedNanos - reservation;
        budget.tokensNanos -= correction;
        globalTokensNanos -= correction;
        budget.recordRun(dimension, elapsedNanos);

        // 自动冻结只停止后续 tick，不禁用或删除尘歌壶，主人仍可进入并手动修复。
        if (elapsedNanos >= AUTO_FREEZE_LEVEL_TICK_NANOS) {
            var record = SereniteaPotManager.record(owner);
            if (record == null) {
                return;
            }
            if (!record.isFrozen()) {
                record.setFrozen(true);
                SereniteaPotManager.saveCatalog();
                notifyAutomaticFreeze(level.getServer(), owner, elapsedNanos);
            }
        }
    }

    public static SereniteaPotPerformanceSnapshot snapshot(UUID owner) {
        var record = SereniteaPotManager.record(owner);
        if (record == null) {
            return null;
        }
        OwnerBudget budget = owners.get(owner);
        if (budget == null) {
            return new SereniteaPotPerformanceSnapshot(
                owner,
                record.getBudgetMillisPerSecond(),
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0L,
                0L,
                Map.of()
            );
        }
        return budget.snapshot(owner, record.getBudgetMillisPerSecond());
    }

    public static List<SereniteaPotPerformanceSnapshot> allSnapshots() {
        List<SereniteaPotPerformanceSnapshot> snapshots = new ArrayList<>();
        for (UUID owner : SereniteaPotManager.catalog().getPlayers().keySet()) {
            SereniteaPotPerformanceSnapshot snapshot = snapshot(owner);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        snapshots.sort((left, right) -> Double.compare(
            right.consumedMillisLastSecond(), left.consumedMillisLastSecond()));
        return snapshots;
    }

    public static void reset(UUID owner) {
        owners.remove(owner);
    }

    private static void resetAll() {
        owners.clear();
        globalTokensNanos = 0.0;
        serverTicks = 0L;
    }

    /**
     * Reserves time for a region-copy slice from the same owner/global token
     * buckets used by custom level ticks. The actual elapsed time is corrected
     * by {@link #completeCreationSlice(CreationReservation, long)},
     * so a slow chunk load creates budget debt.
     */
    public static CreationReservation reserveCreationSlice(UUID owner, long maximumNanos) {
        var record = SereniteaPotManager.record(owner);
        if (record == null) {
            return null;
        }
        OwnerBudget budget = owners.computeIfAbsent(owner,
            ignored -> new OwnerBudget(record.getBudgetMillisPerSecond()));
        budget.updateLimit(record.getBudgetMillisPerSecond());
        double reserved = Math.min((double) maximumNanos, Math.min(budget.tokensNanos, globalTokensNanos));
        if (reserved < MINIMUM_RESERVATION_NANOS) {
            return null;
        }
        budget.tokensNanos -= reserved;
        globalTokensNanos -= reserved;
        return new CreationReservation(owner, reserved);
    }

    public static void completeCreationSlice(CreationReservation reservation, long elapsedNanos) {
        if (reservation.completed) {
            SereniteaPotMod.LOGGER.warn(
                "Ignored duplicate completion for Serenitea Pot {} creation reservation", reservation.owner);
            return;
        }
        reservation.completed = true;
        OwnerBudget budget = owners.get(reservation.owner);
        if (budget == null) {
            return;
        }
        double correction = elapsedNanos - reservation.reservedNanos;
        budget.tokensNanos -= correction;
        globalTokensNanos -= correction;
        budget.recordCreation(elapsedNanos);
    }

    private static void startServerTick(MinecraftServer server) {
        serverTicks++;
        double globalLimit = SereniteaPotManager.catalog().getGlobalBudgetMillisPerSecond() * 1_000_000.0;
        globalTokensNanos = Math.min(globalLimit, globalTokensNanos + globalLimit / TICKS_PER_SECOND);
        for (var entry : owners.entrySet()) {
            UUID owner = entry.getKey();
            OwnerBudget budget = entry.getValue();
            var record = SereniteaPotManager.record(owner);
            double limit = record == null ? 0.0 : record.getBudgetMillisPerSecond();
            budget.updateLimit(limit);
            budget.refill();
            if (serverTicks % 20L == 0L) {
                budget.rollWindow();
            }
        }
    }

    private static void notifyAutomaticFreeze(MinecraftServer server, UUID owner, long elapsedNanos) {
        double millis = elapsedNanos / 1_000_000.0;
        String formattedMillis = "%.2f".formatted(millis);
        SereniteaPotMod.LOGGER.error(
            "Serenitea Pot {} produced a single {} ms level tick and was automatically frozen",
            owner,
            formattedMillis
        );
        for (var player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(owner)
                || player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
                player.sendSystemMessage(component(
                    player,
                    message(MessageKey.PERFORMANCE_AUTO_FREEZE, owner, formattedMillis)
                ));
            }
        }
    }

    private static final class OwnerBudget {
        private double limitNanos;
        private double tokensNanos;
        private double estimatedNanos = MINIMUM_RESERVATION_NANOS;
        private final EnumMap<SereniteaPotDimension, Double> reservations =
            new EnumMap<>(SereniteaPotDimension.class);
        private final EnumMap<SereniteaPotDimension, DimensionBudget> dimensions =
            new EnumMap<>(SereniteaPotDimension.class);
        private long calls;
        private long runs;
        private long skips;
        private long consumed;
        private long maximum;
        private long creationConsumed;
        private long lastCalls;
        private long lastRuns;
        private long lastSkips;
        private long lastConsumed;
        private long lastMaximum;
        private long lastCreationConsumed;

        private OwnerBudget(double limitMillisPerSecond) {
            limitNanos = limitMillisPerSecond * 1_000_000.0;
            tokensNanos = limitNanos / TICKS_PER_SECOND;
        }

        private void updateLimit(double millisPerSecond) {
            limitNanos = Math.max(0.0, millisPerSecond * 1_000_000.0);
            tokensNanos = Math.min(tokensNanos, limitNanos);
        }

        private void refill() {
            tokensNanos = Math.min(limitNanos, tokensNanos + limitNanos / TICKS_PER_SECOND);
        }

        private DimensionBudget dimension(SereniteaPotDimension dimension) {
            return dimensions.computeIfAbsent(dimension, ignored -> new DimensionBudget());
        }

        private void recordSkip(SereniteaPotDimension dimension) {
            skips++;
            dimension(dimension).skips++;
        }

        private void recordRun(SereniteaPotDimension dimension, long elapsedNanos) {
            runs++;
            consumed += elapsedNanos;
            maximum = Math.max(maximum, elapsedNanos);
            estimatedNanos = estimatedNanos * 0.8 + elapsedNanos * 0.2;
            dimension(dimension).recordRun(elapsedNanos);
        }

        private void recordCreation(long elapsedNanos) {
            consumed += elapsedNanos;
            creationConsumed += elapsedNanos;
            maximum = Math.max(maximum, elapsedNanos);
        }

        private void rollWindow() {
            lastCalls = calls;
            lastRuns = runs;
            lastSkips = skips;
            lastConsumed = consumed;
            lastMaximum = maximum;
            lastCreationConsumed = creationConsumed;
            calls = 0L;
            runs = 0L;
            skips = 0L;
            consumed = 0L;
            maximum = 0L;
            creationConsumed = 0L;
            for (DimensionBudget dimension : dimensions.values()) {
                dimension.rollWindow();
            }
        }

        private SereniteaPotPerformanceSnapshot snapshot(UUID owner, double limitMillis) {
            long levelConsumed = lastConsumed - lastCreationConsumed;
            double average = lastRuns == 0L ? 0.0 : levelConsumed / (double) lastRuns / 1_000_000.0;
            double effectiveTps = lastCalls == 0L ? 0.0 : (double) lastRuns / lastCalls * 20.0;
            EnumMap<SereniteaPotDimension, DimensionPerformanceSnapshot> dimensionSnapshots =
                new EnumMap<>(SereniteaPotDimension.class);
            for (var entry : dimensions.entrySet()) {
                dimensionSnapshots.put(entry.getKey(), entry.getValue().snapshot());
            }
            return new SereniteaPotPerformanceSnapshot(
                owner,
                limitMillis,
                lastConsumed / 1_000_000.0,
                lastCreationConsumed / 1_000_000.0,
                average,
                lastMaximum / 1_000_000.0,
                effectiveTps,
                lastRuns,
                lastSkips,
                dimensionSnapshots
            );
        }
    }

    public static final class CreationReservation {
        private final UUID owner;
        private final double reservedNanos;
        private boolean completed;

        public CreationReservation(UUID owner, double reservedNanos) {
            this.owner = owner;
            this.reservedNanos = reservedNanos;
        }

        public double reservedNanos() {
            return reservedNanos;
        }

    }

    private static final class DimensionBudget {
        private long runs;
        private long skips;
        private long consumed;
        private long maximum;
        private long lastRuns;
        private long lastSkips;
        private long lastConsumed;
        private long lastMaximum;

        private void recordRun(long elapsedNanos) {
            runs++;
            consumed += elapsedNanos;
            maximum = Math.max(maximum, elapsedNanos);
        }

        private void rollWindow() {
            lastRuns = runs;
            lastSkips = skips;
            lastConsumed = consumed;
            lastMaximum = maximum;
            runs = 0L;
            skips = 0L;
            consumed = 0L;
            maximum = 0L;
        }

        private DimensionPerformanceSnapshot snapshot() {
            return new DimensionPerformanceSnapshot(
                lastRuns == 0L ? 0.0 : lastConsumed / (double) lastRuns / 1_000_000.0,
                lastMaximum / 1_000_000.0,
                lastRuns,
                lastSkips
            );
        }
    }
}
