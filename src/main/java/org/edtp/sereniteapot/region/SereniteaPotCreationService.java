package org.edtp.sereniteapot.region;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.i18n.SereniteaPotTranslations.Message;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.fallback;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/**
 * 以可分片任务创建或裁剪尘歌壶，而不是在命令处理期间阻塞复制整个区域。
 *
 * <p>任务先构造一个不可进入的暂存代际，在每个服务器 tick 内按性能预算逐段复制；
 * 所有维度成功后才原子切换活动代际，随后删除被替换的旧代际。失败只丢弃暂存代际，
 * 不会把半成品设为活动世界。</p>
 */
public final class SereniteaPotCreationService {
    private static final long COPY_BUDGET_NANOS_PER_TICK = 4_000_000L;
    private static final Map<UUID, CreationJob> jobs = new LinkedHashMap<>();
    private static int roundRobinOffset;

    private SereniteaPotCreationService() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SereniteaPotCreationService::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(SereniteaPotCreationService::stop);
    }

    public static RequestResult request(ServerPlayer player, int radiusChunks) {
        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Creation must run on the server thread");
        UUID owner = player.getUUID();
        ServerLevel source = player.level();
        SereniteaPotDimension dimension = SereniteaPotDimension.fromVanillaLevel(source.dimension());
        if (dimension == null) return new Rejected(message("creation.public_dimension_only"));

        SereniteaPotRecord record = SereniteaPotManager.getOrCreateRecord(owner);
        if (!record.isEnabled()) return new Rejected(message("creation.disabled"));
        if (jobs.containsKey(owner)) return new Rejected(message("creation.job_exists"));
        if (radiusChunks < 0 || radiusChunks > record.getMaxRadiusChunks()) {
            return new Rejected(message("creation.radius_range", record.getMaxRadiusChunks()));
        }

        BlockRegion region;
        try {
            region = BlockRegion.chunkColumns(
                player.blockPosition(), radiusChunks, source.getMinY(), source.getMaxY()
            );
        } catch (ArithmeticException | IllegalArgumentException error) {
            return new Rejected(message("creation.coordinates_out_of_range"));
        }

        SereniteaPotLifecycleService.Result maintenance = SereniteaPotLifecycleService.beginMaintenance(server, owner);
        if (maintenance instanceof SereniteaPotLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        SereniteaPotBundle previous = null;
        if (record.exists()) {
            previous = SereniteaPotManager.loaded(owner);
            if (previous == null) {
                try {
                    previous = SereniteaPotManager.load(owner);
                } catch (RuntimeException error) {
                    abortMaintenance(owner);
                    return new Rejected(message("creation.previous_load_failed", error.getMessage()));
                }
            }
        }
        long generation = Math.max(record.getActiveGeneration() + 1, System.currentTimeMillis());
        SereniteaPotBundle staging;
        try {
            staging = SereniteaPotManager.createStaging(owner, generation, source.getSeed());
        } catch (RuntimeException error) {
            abortMaintenance(owner);
            return new Rejected(message("creation.staging_failed", error.getMessage()));
        }

        ArrayDeque<RegionCopyTask> tasks = new ArrayDeque<>();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots = copySlots(record.getSlots());
        for (SereniteaPotDimension slotDimension : SereniteaPotDimension.values()) {
            ServerLevel destination = staging.get(slotDimension);
            if (slotDimension == dimension) {
                tasks.add(new RegionCopyTask(
                    source,
                    destination,
                    region,
                    localRegion(destination, radiusChunks)
                ));
            } else {
                SereniteaPotSlotRecord oldSlot = record.getSlots().get(slotDimension);
                if (previous != null && oldSlot != null) {
                    tasks.add(new RegionCopyTask(
                        previous.get(slotDimension),
                        destination,
                        localRegion(previous.get(slotDimension), oldSlot.radiusChunks()),
                        localRegion(destination, oldSlot.radiusChunks())
                    ));
                }
            }
        }
        replacementSlots.put(dimension, new SereniteaPotSlotRecord(
            source.dimension().identifier().toString(),
            player.getBlockX(), player.getBlockY(), player.getBlockZ(), radiusChunks
        ));
        jobs.put(owner, new CreationJob(
            owner,
            staging,
            tasks,
            replacementSlots,
            player.getUUID(),
            record.getMaxRadiusChunks(),
            JobKind.EXTRACTION,
            true
        ));
        long diameterChunks = Math.addExact(Math.multiplyExact((long) radiusChunks, 2L), 1L);
        return new Accepted(Math.multiplyExact(diameterChunks, diameterChunks), generation);
    }

    /**
     * Changes an owner's configured maximum. If persisted dimensions exceed it,
     * they are rebuilt through the same staging/commit transaction used by extraction.
     */
    public static MaximumChangeResult changeMaximum(
        MinecraftServer server,
        UUID owner,
        int maximumRadiusChunks,
        UUID requester
    ) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Maximum change must run on the server thread");
        }
        if (maximumRadiusChunks < 0 || maximumRadiusChunks > SereniteaPotRecord.MAX_RADIUS_CHUNKS) {
            return new Rejected(message("creation.radius_range", SereniteaPotRecord.MAX_RADIUS_CHUNKS));
        }
        if (jobs.containsKey(owner)) {
            return new Rejected(message("creation.target_job_exists"));
        }

        SereniteaPotRecord record = SereniteaPotManager.getOrCreateRecord(owner);
        boolean requiresTrim = record.exists() && record.getSlots().values().stream()
            .anyMatch(slot -> slot.radiusChunks() > maximumRadiusChunks);
        if (!requiresTrim) {
            record.setMaxRadiusChunks(maximumRadiusChunks);
            SereniteaPotManager.saveCatalog();
            return MaximumUpdated.INSTANCE;
        }

        SereniteaPotLifecycleService.Result maintenance = SereniteaPotLifecycleService.beginMaintenance(server, owner);
        if (maintenance instanceof SereniteaPotLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        SereniteaPotBundle previous = SereniteaPotManager.loaded(owner);
        if (previous == null) {
            try {
                previous = SereniteaPotManager.load(owner);
            } catch (RuntimeException error) {
                abortMaintenance(owner);
                return new Rejected(message("creation.previous_load_failed", errorMessage(error)));
            }
        }

        long generation = Math.max(record.getActiveGeneration() + 1, System.currentTimeMillis());
        SereniteaPotBundle staging;
        try {
            staging = SereniteaPotManager.createStaging(
                owner,
                generation,
                previous.get(SereniteaPotDimension.OVERWORLD).getSeed()
            );
        } catch (RuntimeException error) {
            abortMaintenance(owner);
            return new Rejected(message("creation.trim_staging_failed", errorMessage(error)));
        }

        ArrayDeque<RegionCopyTask> tasks = new ArrayDeque<>();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots = new EnumMap<>(SereniteaPotDimension.class);
        long retainedChunks = 0L;
        try {
            for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
                SereniteaPotSlotRecord slot = record.getSlots().get(dimension);
                if (slot == null) continue;
                int retainedRadius = Math.min(slot.radiusChunks(), maximumRadiusChunks);
                SereniteaPotSlotRecord replacement = new SereniteaPotSlotRecord(
                    slot.sourceDimension(),
                    slot.entryX(),
                    slot.entryY(),
                    slot.entryZ(),
                    retainedRadius
                );
                replacementSlots.put(dimension, replacement);
                tasks.add(new RegionCopyTask(
                    previous.get(dimension),
                    staging.get(dimension),
                    localRegion(previous.get(dimension), retainedRadius),
                    localRegion(staging.get(dimension), retainedRadius)
                ));
                long diameter = retainedRadius * 2L + 1L;
                retainedChunks = Math.addExact(retainedChunks, Math.multiplyExact(diameter, diameter));
            }
        } catch (RuntimeException error) {
            SereniteaPotLifecycleService.deleteEvacuated(staging);
            abortMaintenance(owner);
            return new Rejected(message("creation.trim_prepare_failed", errorMessage(error)));
        }

        jobs.put(owner, new CreationJob(
            owner,
            staging,
            tasks,
            replacementSlots,
            requester,
            maximumRadiusChunks,
            JobKind.MAXIMUM_TRIM,
            record.isEnabled()
        ));
        return new MaximumTrimStarted(generation, replacementSlots.size(), retainedChunks);
    }

    public static boolean isBusy(UUID owner) {
        return jobs.containsKey(owner);
    }

    public static Double progress(UUID owner) {
        CreationJob job = jobs.get(owner);
        return job == null ? null : job.progress();
    }

    public static boolean cancel(UUID owner) {
        CreationJob job = jobs.remove(owner);
        if (job == null) return false;
        SereniteaPotLifecycleService.deleteEvacuated(job.staging);
        abortMaintenance(owner);
        return true;
    }

    private static void tick(MinecraftServer server) {
        if (jobs.isEmpty()) return;
        long globalDeadline = System.nanoTime() + COPY_BUDGET_NANOS_PER_TICK;
        List<UUID> owners = new ArrayList<>(jobs.keySet());
        // 每 tick 轮换起始玩家，避免任务列表前面的玩家长期占满全局复制预算。
        int start = Math.floorMod(roundRobinOffset, owners.size());
        for (int visited = 0; visited < owners.size(); visited++) {
            long now = System.nanoTime();
            if (now >= globalDeadline) break;
            UUID owner = owners.get(Math.floorMod(start + visited, owners.size()));
            CreationJob job = jobs.get(owner);
            if (job == null) continue;
            SereniteaPotRecord record = SereniteaPotManager.record(owner);
            if (!job.canContinue(record)) {
                fail(server, job, message("creation.stopped_by_admin_change"));
                jobs.remove(owner);
                continue;
            }
            long fairShare = (globalDeadline - now) / (owners.size() - visited);
            SereniteaPotScheduler.CreationReservation reservation = SereniteaPotScheduler.reserveCreationSlice(owner, fairShare);
            if (reservation == null) continue;
            long started = System.nanoTime();
            try {
                job.step(started + (long) reservation.reservedNanos());
            } catch (RuntimeException error) {
                SereniteaPotScheduler.completeCreationSlice(reservation, System.nanoTime() - started);
                SereniteaPotMod.LOGGER.error("Serenitea Pot creation failed for {}", job.owner, error);
                fail(server, job, message("creation.internal_error", errorMessage(error)));
                jobs.remove(owner);
                continue;
            }
            SereniteaPotScheduler.completeCreationSlice(reservation, System.nanoTime() - started);
            SereniteaPotRecord updated = SereniteaPotManager.record(owner);
            if (!job.canContinue(updated)) {
                fail(server, job, message("creation.disabled_during_job"));
                jobs.remove(owner);
                continue;
            }
            if (job.complete()) {
                // 只有全部复制任务成功后才发布新代际；commit 之前旧元数据仍是权威状态。
                SereniteaPotBundle replaced;
                try {
                    replaced = SereniteaPotManager.commitGeneration(
                        job.staging,
                        job.replacementSlots,
                        job.committedMaximumRadiusChunks
                    );
                } catch (RuntimeException error) {
                    SereniteaPotMod.LOGGER.error("Serenitea Pot creation commit failed for {}", job.owner, error);
                    fail(server, job, message("creation.internal_error", errorMessage(error)));
                    jobs.remove(owner);
                    continue;
                }
                jobs.remove(owner);
                if (replaced != null) SereniteaPotLifecycleService.deleteEvacuated(replaced);
                finishCommitted(server, job);
            }
        }
        roundRobinOffset = Math.floorMod(start + 1, Math.max(jobs.size(), 1));
    }

    private static void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Creation stop must run on the server thread");
        for (CreationJob job : jobs.values()) {
            try {
                SereniteaPotLifecycleService.deleteEvacuated(job.staging);
            } catch (RuntimeException error) {
                SereniteaPotMod.LOGGER.error(
                    "Failed to discard staging Serenitea Pot for {} during shutdown", job.owner, error
                );
            }
            SereniteaPotLifecycleService.endMaintenance(job.owner);
        }
        jobs.clear();
        roundRobinOffset = 0;
    }

    private static void finishCommitted(MinecraftServer server, CreationJob job) {
        SereniteaPotLifecycleService.endMaintenance(job.owner);
        try {
            SereniteaPotLifecycleService.Result close = SereniteaPotLifecycleService.closeNow(server, job.owner);
            if (close instanceof SereniteaPotLifecycleService.Rejected rejected) {
                SereniteaPotMod.LOGGER.warn(
                    "Committed Serenitea Pot {} but its post-creation unload was deferred: {}",
                    job.owner, fallback(rejected.reason())
                );
            }
        } catch (RuntimeException error) {
            SereniteaPotMod.LOGGER.error("Committed Serenitea Pot {} but failed to close it", job.owner, error);
            SereniteaPotLifecycleService.requestClose(job.owner);
        }
        ServerPlayer player = job.requester == null
            ? null
            : server.getPlayerList().getPlayer(job.requester);
        if (player != null) {
            Message completion = job.kind == JobKind.MAXIMUM_TRIM
                ? message("creation.trim.complete", job.committedMaximumRadiusChunks)
                : message("creation.complete", job.staging.generation());
            player.sendSystemMessage(component(player, completion));
        }
    }

    private static void abortMaintenance(UUID owner) {
        SereniteaPotLifecycleService.endMaintenance(owner);
        SereniteaPotLifecycleService.requestClose(owner);
    }

    private static void fail(MinecraftServer server, CreationJob job, Message reason) {
        try {
            SereniteaPotLifecycleService.deleteEvacuated(job.staging);
        } catch (RuntimeException ignored) {
        }
        abortMaintenance(job.owner);
        ServerPlayer player = job.requester == null
            ? null
            : server.getPlayerList().getPlayer(job.requester);
        if (player != null) {
            String key = job.kind == JobKind.MAXIMUM_TRIM
                ? "creation.trim.failed"
                : "creation.failed";
            player.sendSystemMessage(component(player, message(key, reason)));
        }
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> copySlots(
        Map<SereniteaPotDimension, SereniteaPotSlotRecord> source
    ) {
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> copy = new EnumMap<>(SereniteaPotDimension.class);
        copy.putAll(source);
        return copy;
    }

    private static BlockRegion localRegion(ServerLevel level, int radiusChunks) {
        return BlockRegion.chunkColumns(
            0,
            0,
            radiusChunks,
            level.getMinY(),
            level.getMaxY()
        );
    }

    private static final class CreationJob {
        private final UUID owner;
        private final SereniteaPotBundle staging;
        private final ArrayDeque<RegionCopyTask> tasks;
        private final EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots;
        private final UUID requester;
        private final int committedMaximumRadiusChunks;
        private final JobKind kind;
        private final boolean expectedEnabled;
        private final int totalTasks;
        private int completedTasks;

        private CreationJob(
            UUID owner,
            SereniteaPotBundle staging,
            ArrayDeque<RegionCopyTask> tasks,
            EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots,
            UUID requester,
            int committedMaximumRadiusChunks,
            JobKind kind,
            boolean expectedEnabled
        ) {
            this.owner = owner;
            this.staging = staging;
            this.tasks = tasks;
            this.replacementSlots = replacementSlots;
            this.requester = requester;
            this.committedMaximumRadiusChunks = committedMaximumRadiusChunks;
            this.kind = kind;
            this.expectedEnabled = expectedEnabled;
            this.totalTasks = tasks.size();
        }

        private boolean canContinue(SereniteaPotRecord record) {
            if (record == null) return false;
            if (kind == JobKind.MAXIMUM_TRIM) {
                return record.isEnabled() == expectedEnabled;
            }
            return record.isEnabled();
        }

        private void step(long deadline) {
            RegionCopyTask task = tasks.peekFirst();
            if (task == null) return;
            task.step(deadline);
            if (task.getComplete()) {
                tasks.removeFirst();
                completedTasks++;
            }
        }

        private boolean complete() {
            return tasks.isEmpty();
        }

        private double progress() {
            if (totalTasks == 0) return 1.0;
            RegionCopyTask current = tasks.peekFirst();
            return (completedTasks + (current == null ? 0.0 : current.getProgress())) / totalTasks;
        }
    }

    private enum JobKind {
        EXTRACTION,
        MAXIMUM_TRIM
    }

    public sealed interface RequestResult permits Accepted, Rejected {
    }

    public record Accepted(long chunkCount, long generation) implements RequestResult {
    }

    public record Rejected(Message reason) implements RequestResult, MaximumChangeResult {
    }

    public sealed interface MaximumChangeResult permits MaximumUpdated, MaximumTrimStarted, Rejected {
    }

    public enum MaximumUpdated implements MaximumChangeResult {
        INSTANCE
    }

    public record MaximumTrimStarted(
        long generation,
        int dimensionCount,
        long retainedChunks
    ) implements MaximumChangeResult {
    }
}
