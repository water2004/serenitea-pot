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
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Phaser;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/**
 * 为完整尘歌壶 tick 和区域复制分配每 tick 时间预算。
 *
 * <p>每个服务器 tick 都重新随机排列已加载的壶。一个壶一旦开始执行，本 tick
 * 内的三个维度便作为一个整体完成；实际耗时扣除后才决定是否放行下一个壶。
 * 预算不会跨 tick 累积，也不依赖历史耗时预测。</p>
 */
public final class SereniteaPotScheduler {
    private static final int METRICS_WINDOW_TICKS = 20;
    private static final long MINIMUM_CREATION_SLICE_NANOS = 50_000L;
    private static final long AUTO_FREEZE_LEVEL_TICK_NANOS = 200_000_000L;

    private static final LinkedHashMap<UUID, OwnerMetrics> metrics = new LinkedHashMap<>();
    private static final LinkedHashMap<UUID, Long> pendingAutomaticFreezes = new LinkedHashMap<>();
    private static volatile TickPlan currentPlan = TickPlan.empty();
    private static long serverTicks;

    private SereniteaPotScheduler() {
    }

    public static void register() {
        ServerTickEvents.START_SERVER_TICK.register(SereniteaPotScheduler::startServerTick);
        ServerTickEvents.END_SERVER_TICK.register(SereniteaPotScheduler::endServerTick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> resetAll());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> resetAll());
    }

    /** Orders only the vanilla tick loop; WorldThreader uses the same plan through its compatibility mixins. */
    public static Iterable<ServerLevel> orderLevelsForVanillaTick(Iterable<ServerLevel> original) {
        List<ServerLevel> publicLevels = new ArrayList<>();
        Map<UUID, EnumMap<SereniteaPotDimension, ServerLevel>> activePots = new HashMap<>();
        List<ServerLevel> inactivePotLevels = new ArrayList<>();

        for (ServerLevel level : original) {
            SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
            if (identity == null) {
                publicLevels.add(level);
                continue;
            }
            SereniteaPotRecord record = SereniteaPotManager.record(identity.owner());
            if (record == null || identity.generation() != record.getActiveGeneration()) {
                inactivePotLevels.add(level);
                continue;
            }
            activePots.computeIfAbsent(identity.owner(), ignored -> new EnumMap<>(SereniteaPotDimension.class))
                .put(identity.dimension(), level);
        }

        List<ServerLevel> ordered = new ArrayList<>(publicLevels);
        for (UUID owner : currentPlan.owners()) {
            Map<SereniteaPotDimension, ServerLevel> levels = activePots.remove(owner);
            if (levels == null) continue;
            for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
                ServerLevel level = levels.get(dimension);
                if (level != null) ordered.add(level);
            }
        }
        // A level added after START_SERVER_TICK is never admitted without a plan,
        // but it remains in the iterable so vanilla bookkeeping stays complete.
        activePots.values().forEach(levels -> ordered.addAll(levels.values()));
        ordered.addAll(inactivePotLevels);
        return ordered;
    }

    /** Random owner order shared by the three WorldThreader dimension-family lanes. */
    public static List<UUID> tickOrder() {
        return currentPlan.owners();
    }

    /** Returns the active level for one owner and dimension in the current plan. */
    public static ServerLevel activeLevel(MinecraftServer server, UUID owner, SereniteaPotDimension dimension) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null || !record.exists()) return null;
        return server.getLevel(SereniteaPotLevelKeys.key(owner, record.getActiveGeneration(), dimension));
    }

    /** Admission hook for the ordinary single-threaded level loop. */
    public static boolean beforeLevelTick(ServerLevel level) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (identity == null) return true;
        boolean runnable = isRunnable(identity) && currentPlan.admitSerial(identity.owner());
        recordCall(identity.owner(), identity.dimension(), runnable);
        return runnable;
    }

    /** Completion hook for the ordinary single-threaded level loop. */
    public static void afterLevelTick(ServerLevel level, long elapsedNanos) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (identity == null) return;
        recordLevelRun(identity.owner(), identity.dimension(), elapsedNanos);
        currentPlan.recordSerialCost(identity.owner(), elapsedNanos);
    }

    /** Admission hook used by each WorldThreader dimension-family lane. */
    public static boolean beforeThreadedLevelTick(ServerLevel level) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (identity == null) return true;
        boolean runnable = isRunnable(identity) && currentPlan.admitThreaded(identity.owner());
        recordCall(identity.owner(), identity.dimension(), runnable);
        return runnable;
    }

    /** Records one lane, then waits until all three dimension families completed the same owner. */
    public static boolean finishThreadedOwner(
        UUID owner,
        SereniteaPotDimension dimension,
        long elapsedNanos,
        boolean ran
    ) {
        if (ran) recordLevelRun(owner, dimension, elapsedNanos);
        return currentPlan.finishThreadedOwner(owner, dimension, ran ? elapsedNanos : 0L, ran);
    }

    /** Wakes the other two family lanes if one lane aborts before reaching the owner barrier. */
    public static void abortThreadedTick() {
        currentPlan.abortThreadedTick();
    }

    /** Owners whose complete three-dimension world phase ran this tick. */
    public static List<UUID> threadedExecutedOwners() {
        return currentPlan.threadedExecutedOwners();
    }

    public static synchronized SereniteaPotPerformanceSnapshot snapshot(UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return null;
        OwnerMetrics ownerMetrics = metrics.get(owner);
        return ownerMetrics == null
            ? OwnerMetrics.emptySnapshot(owner, record.getBudgetMillisPerTick())
            : ownerMetrics.snapshot(owner, record.getBudgetMillisPerTick());
    }

    public static synchronized List<SereniteaPotPerformanceSnapshot> allSnapshots() {
        List<SereniteaPotPerformanceSnapshot> snapshots = new ArrayList<>();
        for (UUID owner : SereniteaPotManager.catalog().getPlayers().keySet()) {
            SereniteaPotPerformanceSnapshot snapshot = snapshot(owner);
            if (snapshot != null) snapshots.add(snapshot);
        }
        snapshots.sort((left, right) -> Double.compare(
            right.consumedMillisPerTick(), left.consumedMillisPerTick()
        ));
        return snapshots;
    }

    public static synchronized void reset(UUID owner) {
        metrics.remove(owner);
        pendingAutomaticFreezes.remove(owner);
    }

    /** Reserves a copy slice from the current tick's remaining owner and global budgets. */
    public static CreationReservation reserveCreationSlice(UUID owner, long maximumNanos) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return null;
        double reserved = currentPlan.reserveCreation(
            owner,
            maximumNanos,
            record.getBudgetMillisPerTick() * 1_000_000.0
        );
        if (reserved < MINIMUM_CREATION_SLICE_NANOS) return null;
        return new CreationReservation(currentPlan, owner, reserved);
    }

    public static void completeCreationSlice(CreationReservation reservation, long elapsedNanos) {
        synchronized (reservation) {
            if (reservation.completed) {
                SereniteaPotMod.LOGGER.warn(
                    "Ignored duplicate completion for Serenitea Pot {} creation reservation",
                    reservation.owner
                );
                return;
            }
            reservation.completed = true;
        }
        reservation.plan.correctCreation(reservation.owner, reservation.reservedNanos, elapsedNanos);
        synchronized (SereniteaPotScheduler.class) {
            metrics.computeIfAbsent(reservation.owner, ignored -> new OwnerMetrics())
                .recordCreation(elapsedNanos);
        }
    }

    private static synchronized void startServerTick(MinecraftServer server) {
        if (serverTicks > 0L && serverTicks % METRICS_WINDOW_TICKS == 0L) {
            metrics.values().forEach(OwnerMetrics::rollWindow);
        }
        serverTicks++;

        LinkedHashSet<UUID> loadedOwners = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
            if (identity == null) continue;
            SereniteaPotRecord record = SereniteaPotManager.record(identity.owner());
            if (record != null && identity.generation() == record.getActiveGeneration()) {
                loadedOwners.add(identity.owner());
            }
        }
        List<UUID> order = new ArrayList<>(loadedOwners);
        Collections.shuffle(order);
        currentPlan = new TickPlan(
            order,
            SereniteaPotManager.catalog().getGlobalBudgetMillisPerTick() * 1_000_000.0
        );
    }

    private static void endServerTick(MinecraftServer server) {
        Map<UUID, Long> freezes = drainAutomaticFreezes();
        List<Map.Entry<UUID, Long>> applied = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : freezes.entrySet()) {
            SereniteaPotRecord record = SereniteaPotManager.record(entry.getKey());
            if (record == null || record.isFrozen()) continue;
            record.setFrozen(true);
            applied.add(entry);
        }
        if (!applied.isEmpty()) SereniteaPotManager.saveCatalog();
        for (Map.Entry<UUID, Long> entry : applied) {
            notifyAutomaticFreeze(server, entry.getKey(), entry.getValue());
        }
    }

    private static boolean isRunnable(SereniteaPotLevelKeys.Identity identity) {
        SereniteaPotRecord record = SereniteaPotManager.record(identity.owner());
        return record != null
            && identity.generation() == record.getActiveGeneration()
            && record.isEnabled()
            && !record.isFrozen()
            && !SereniteaPotCreationService.isBusy(identity.owner());
    }

    private static synchronized void recordCall(
        UUID owner,
        SereniteaPotDimension dimension,
        boolean ran
    ) {
        OwnerMetrics ownerMetrics = metrics.computeIfAbsent(owner, ignored -> new OwnerMetrics());
        ownerMetrics.calls++;
        if (!ran) ownerMetrics.recordSkip(dimension);
    }

    private static synchronized void recordLevelRun(
        UUID owner,
        SereniteaPotDimension dimension,
        long elapsedNanos
    ) {
        OwnerMetrics ownerMetrics = metrics.computeIfAbsent(owner, ignored -> new OwnerMetrics());
        ownerMetrics.recordLevelRun(dimension, elapsedNanos);
        if (elapsedNanos >= AUTO_FREEZE_LEVEL_TICK_NANOS) {
            pendingAutomaticFreezes.merge(owner, elapsedNanos, Math::max);
        }
    }

    private static synchronized void recordWholePotCost(UUID owner, long elapsedNanos) {
        metrics.computeIfAbsent(owner, ignored -> new OwnerMetrics()).recordWholePotCost(elapsedNanos);
    }

    private static synchronized Map<UUID, Long> drainAutomaticFreezes() {
        if (pendingAutomaticFreezes.isEmpty()) return Map.of();
        Map<UUID, Long> freezes = Map.copyOf(pendingAutomaticFreezes);
        pendingAutomaticFreezes.clear();
        return freezes;
    }

    private static synchronized void resetAll() {
        metrics.clear();
        pendingAutomaticFreezes.clear();
        currentPlan = TickPlan.empty();
        serverTicks = 0L;
    }

    private static void notifyAutomaticFreeze(MinecraftServer server, UUID owner, long elapsedNanos) {
        String formattedMillis = "%.2f".formatted(elapsedNanos / 1_000_000.0);
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

    /** One immutable owner order plus the mutable accounting for exactly one server tick. */
    private static final class TickPlan {
        private static final TickPlan EMPTY = new TickPlan(List.of(), 0.0);

        private final List<UUID> owners;
        private final Map<UUID, Integer> indices;
        private final boolean[] serialStarted;
        private final boolean[] threadedAdmitted;
        private final boolean[] threadedExecuted;
        private final long[][] threadedElapsed;
        private final Map<UUID, Double> ownerRemainingNanos = new HashMap<>();
        private final Phaser threadedBarrier;
        private double globalRemainingNanos;

        private TickPlan(List<UUID> owners, double globalBudgetNanos) {
            this.owners = List.copyOf(owners);
            this.indices = new HashMap<>();
            this.serialStarted = new boolean[owners.size()];
            this.threadedAdmitted = new boolean[owners.size()];
            this.threadedExecuted = new boolean[owners.size()];
            this.threadedElapsed = new long[owners.size()][SereniteaPotDimension.values().length];
            this.globalRemainingNanos = Math.max(0.0, globalBudgetNanos);
            for (int index = 0; index < owners.size(); index++) {
                UUID owner = owners.get(index);
                indices.put(owner, index);
                SereniteaPotRecord record = SereniteaPotManager.record(owner);
                ownerRemainingNanos.put(
                    owner,
                    record == null ? 0.0 : record.getBudgetMillisPerTick() * 1_000_000.0
                );
            }
            if (!owners.isEmpty()) threadedAdmitted[0] = globalRemainingNanos > 0.0;
            this.threadedBarrier = new Phaser(3) {
                @Override
                protected boolean onAdvance(int phase, int registeredParties) {
                    completeThreadedPhase(phase);
                    return phase + 1 >= TickPlan.this.owners.size();
                }
            };
        }

        private static TickPlan empty() {
            return EMPTY;
        }

        private List<UUID> owners() {
            return owners;
        }

        private synchronized boolean admitSerial(UUID owner) {
            Integer index = indices.get(owner);
            if (index == null) return false;
            if (serialStarted[index]) return true;
            if (globalRemainingNanos <= 0.0) return false;
            serialStarted[index] = true;
            return true;
        }

        private synchronized void recordSerialCost(UUID owner, long elapsedNanos) {
            globalRemainingNanos -= elapsedNanos;
            ownerRemainingNanos.compute(owner, (ignored, remaining) ->
                (remaining == null ? 0.0 : remaining) - elapsedNanos
            );
            recordWholePotCost(owner, elapsedNanos);
        }

        private boolean admitThreaded(UUID owner) {
            Integer index = indices.get(owner);
            return index != null && threadedAdmitted[index];
        }

        private boolean finishThreadedOwner(
            UUID owner,
            SereniteaPotDimension dimension,
            long elapsedNanos,
            boolean ran
        ) {
            Integer index = indices.get(owner);
            if (index == null) return false;
            threadedElapsed[index][dimension.ordinal()] = elapsedNanos;
            if (ran) threadedExecuted[index] = true;
            return threadedBarrier.arriveAndAwaitAdvance() >= 0;
        }

        private synchronized void completeThreadedPhase(int index) {
            if (index >= owners.size()) return;
            long cost = 0L;
            for (long laneElapsed : threadedElapsed[index]) cost = Math.max(cost, laneElapsed);
            long actualCost = cost;
            UUID owner = owners.get(index);
            globalRemainingNanos -= actualCost;
            ownerRemainingNanos.compute(owner, (ignored, remaining) ->
                (remaining == null ? 0.0 : remaining) - actualCost
            );
            if (threadedExecuted[index]) recordWholePotCost(owner, actualCost);
            int next = index + 1;
            if (next < owners.size()) threadedAdmitted[next] = globalRemainingNanos > 0.0;
        }

        private void abortThreadedTick() {
            threadedBarrier.forceTermination();
        }

        private List<UUID> threadedExecutedOwners() {
            List<UUID> executed = new ArrayList<>();
            for (int index = 0; index < owners.size(); index++) {
                if (threadedExecuted[index]) executed.add(owners.get(index));
            }
            return List.copyOf(executed);
        }

        private synchronized double reserveCreation(UUID owner, long maximumNanos, double ownerLimitNanos) {
            double ownerRemaining = ownerRemainingNanos.computeIfAbsent(owner, ignored -> ownerLimitNanos);
            double reserved = Math.min((double) maximumNanos, Math.min(ownerRemaining, globalRemainingNanos));
            if (reserved < MINIMUM_CREATION_SLICE_NANOS) return 0.0;
            ownerRemainingNanos.put(owner, ownerRemaining - reserved);
            globalRemainingNanos -= reserved;
            return reserved;
        }

        private synchronized void correctCreation(UUID owner, double reservedNanos, long elapsedNanos) {
            double correction = elapsedNanos - reservedNanos;
            ownerRemainingNanos.compute(owner, (ignored, remaining) ->
                (remaining == null ? 0.0 : remaining) - correction
            );
            globalRemainingNanos -= correction;
        }
    }

    public static final class CreationReservation {
        private final TickPlan plan;
        private final UUID owner;
        private final double reservedNanos;
        private boolean completed;

        private CreationReservation(TickPlan plan, UUID owner, double reservedNanos) {
            this.plan = plan;
            this.owner = owner;
            this.reservedNanos = reservedNanos;
        }

        public double reservedNanos() {
            return reservedNanos;
        }
    }

    private static final class OwnerMetrics {
        private final EnumMap<SereniteaPotDimension, DimensionMetrics> dimensions =
            new EnumMap<>(SereniteaPotDimension.class);
        private long calls;
        private long runs;
        private long skips;
        private long wholePotConsumed;
        private long levelConsumed;
        private long maximum;
        private long creationConsumed;
        private long lastCalls;
        private long lastRuns;
        private long lastSkips;
        private long lastWholePotConsumed;
        private long lastLevelConsumed;
        private long lastMaximum;
        private long lastCreationConsumed;

        private static SereniteaPotPerformanceSnapshot emptySnapshot(UUID owner, double budgetMillis) {
            return new SereniteaPotPerformanceSnapshot(
                owner, budgetMillis, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, Map.of()
            );
        }

        private DimensionMetrics dimension(SereniteaPotDimension dimension) {
            return dimensions.computeIfAbsent(dimension, ignored -> new DimensionMetrics());
        }

        private void recordSkip(SereniteaPotDimension dimension) {
            skips++;
            dimension(dimension).skips++;
        }

        private void recordLevelRun(SereniteaPotDimension dimension, long elapsedNanos) {
            runs++;
            levelConsumed += elapsedNanos;
            maximum = Math.max(maximum, elapsedNanos);
            dimension(dimension).recordRun(elapsedNanos);
        }

        private void recordWholePotCost(long elapsedNanos) {
            wholePotConsumed += elapsedNanos;
        }

        private void recordCreation(long elapsedNanos) {
            creationConsumed += elapsedNanos;
        }

        private void rollWindow() {
            lastCalls = calls;
            lastRuns = runs;
            lastSkips = skips;
            lastWholePotConsumed = wholePotConsumed;
            lastLevelConsumed = levelConsumed;
            lastMaximum = maximum;
            lastCreationConsumed = creationConsumed;
            calls = 0L;
            runs = 0L;
            skips = 0L;
            wholePotConsumed = 0L;
            levelConsumed = 0L;
            maximum = 0L;
            creationConsumed = 0L;
            dimensions.values().forEach(DimensionMetrics::rollWindow);
        }

        private SereniteaPotPerformanceSnapshot snapshot(UUID owner, double budgetMillis) {
            double average = lastRuns == 0L ? 0.0 : lastLevelConsumed / (double) lastRuns / 1_000_000.0;
            double effectiveTps = lastCalls == 0L ? 0.0 : (double) lastRuns / lastCalls * 20.0;
            EnumMap<SereniteaPotDimension, DimensionPerformanceSnapshot> dimensionSnapshots =
                new EnumMap<>(SereniteaPotDimension.class);
            dimensions.forEach((dimension, value) -> dimensionSnapshots.put(dimension, value.snapshot()));
            return new SereniteaPotPerformanceSnapshot(
                owner,
                budgetMillis,
                (lastWholePotConsumed + lastCreationConsumed)
                    / (double) METRICS_WINDOW_TICKS / 1_000_000.0,
                lastCreationConsumed / (double) METRICS_WINDOW_TICKS / 1_000_000.0,
                average,
                lastMaximum / 1_000_000.0,
                effectiveTps,
                lastRuns,
                lastSkips,
                dimensionSnapshots
            );
        }
    }

    private static final class DimensionMetrics {
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
