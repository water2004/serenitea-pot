package org.edtp.sereniteapot.region;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.i18n.MessageKey;
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
import java.util.concurrent.CompletableFuture;

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

    public static CompletableFuture<RequestResult> request(ServerPlayer player, int radiusChunks) {
        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Creation must run on the server thread");
        UUID owner = player.getUUID();
        ServerLevel source = player.level();
        SereniteaPotDimension dimension = SereniteaPotDimension.fromVanillaLevel(source.dimension());
        if (dimension == null) return CompletableFuture.completedFuture(new Rejected(message(MessageKey.CREATION_PUBLIC_DIMENSION_ONLY)));

        SereniteaPotRecord record = SereniteaPotManager.getOrCreateRecord(owner);
        if (!record.isEnabled()) return CompletableFuture.completedFuture(new Rejected(message(MessageKey.CREATION_DISABLED)));
        if (jobs.containsKey(owner)) return CompletableFuture.completedFuture(new Rejected(message(MessageKey.CREATION_JOB_EXISTS)));
        if (radiusChunks < 0 || radiusChunks > record.getMaxRadiusChunks()) {
            return CompletableFuture.completedFuture(new Rejected(message(MessageKey.CREATION_RADIUS_RANGE, record.getMaxRadiusChunks())));
        }

        BlockRegion region;
        try {
            region = BlockRegion.chunkColumns(
                player.blockPosition(), radiusChunks, source.getMinY(), source.getMaxY()
            );
        } catch (ArithmeticException | IllegalArgumentException error) {
            return CompletableFuture.completedFuture(new Rejected(message(MessageKey.CREATION_COORDINATES_OUT_OF_RANGE)));
        }
        BlockPos entry = player.blockPosition();
        return SereniteaPotLifecycleService.beginMaintenance(server, owner).thenApply(result -> {
            if (result instanceof SereniteaPotLifecycleService.Rejected rejected) return new Rejected(rejected.reason());
            if (!record.isEnabled()) {
                abortMaintenance(owner);
                return new Rejected(message(MessageKey.CREATION_DISABLED));
            }
            return startExtraction(owner, source, dimension, record, region, entry, radiusChunks);
        });
    }

    private static RequestResult startExtraction(UUID owner, ServerLevel source, SereniteaPotDimension dimension,
            SereniteaPotRecord record, BlockRegion region, BlockPos entry, int radiusChunks) {
        SereniteaPotBundle previous = null;
        if (record.exists()) {
            previous = SereniteaPotManager.loaded(owner);
            if (previous == null) {
                try {
                    previous = SereniteaPotManager.load(owner);
                } catch (RuntimeException error) {
                    abortMaintenance(owner);
                    return new Rejected(message(MessageKey.CREATION_PREVIOUS_LOAD_FAILED, error.getMessage()));
                }
            }
        }
        long generation = Math.max(record.getActiveGeneration() + 1, System.currentTimeMillis());
        SereniteaPotBundle staging;
        try {
            staging = SereniteaPotManager.createStaging(owner, generation, source.getSeed());
        } catch (RuntimeException error) {
            abortMaintenance(owner);
            return new Rejected(message(MessageKey.CREATION_STAGING_FAILED, error.getMessage()));
        }

        ArrayDeque<RegionCopyTask> tasks = new ArrayDeque<>();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots = copySlots(record.getSlots());
        try {
            for (SereniteaPotDimension slotDimension : SereniteaPotDimension.values()) {
                ServerLevel destination = staging.get(slotDimension);
                if (slotDimension == dimension) {
                    // Keep absolute XYZ: biome noise, block data and portal scaling all
                    // continue to see the same coordinates as the public source world.
                    destination.getWorldBorder().setCenter(
                        region.getMinX() + region.getSizeX() / 2.0,
                        region.getMinZ() + region.getSizeZ() / 2.0
                    );
                    destination.getWorldBorder().setSize(region.getSizeX());
                    tasks.add(new RegionCopyTask(source, destination, region));
                } else if (previous != null) {
                    // Even an unextracted dimension may contain portal platforms or builds.
                    tasks.add(retainDimension(previous.get(slotDimension), destination, record.getMaxRadiusChunks()));
                }
            }
        } catch (RuntimeException error) {
            SereniteaPotLifecycleService.deleteEvacuated(staging);
            abortMaintenance(owner);
            return new Rejected(message(MessageKey.CREATION_INTERNAL_ERROR, errorMessage(error)));
        }
        replacementSlots.put(dimension, new SereniteaPotSlotRecord(
            source.dimension().identifier().toString(),
            entry.getX(), entry.getY(), entry.getZ(), radiusChunks
        ));
        jobs.put(owner, new CreationJob(
            owner,
            staging,
            tasks,
            replacementSlots,
            owner,
            record.getMaxRadiusChunks(),
            JobKind.EXTRACTION,
            true
        ));
        long diameterChunks = Math.addExact(Math.multiplyExact(radiusChunks, 2L), 1L);
        return new Accepted(Math.multiplyExact(diameterChunks, diameterChunks), generation);
    }

    /**
     * Changes an owner's configured maximum. If persisted dimensions exceed it,
     * they are rebuilt through the same staging/commit transaction used by extraction.
     */
    public static CompletableFuture<MaximumChangeResult> changeMaximum(
        MinecraftServer server,
        UUID owner,
        int maximumRadiusChunks,
        UUID requester
    ) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Maximum change must run on the server thread");
        }
        if (maximumRadiusChunks < 0 || maximumRadiusChunks > SereniteaPotRecord.MAX_RADIUS_CHUNKS) {
            return CompletableFuture.completedFuture(new Rejected(message(
                MessageKey.CREATION_RADIUS_RANGE,
                SereniteaPotRecord.MAX_RADIUS_CHUNKS
            )));
        }
        if (jobs.containsKey(owner) || SereniteaPotLifecycleService.isMaintaining(owner)) {
            return CompletableFuture.completedFuture(new Rejected(message(MessageKey.CREATION_TARGET_JOB_EXISTS)));
        }

        SereniteaPotRecord record = SereniteaPotManager.getOrCreateRecord(owner);
        boolean requiresTrim = record.exists() && (maximumRadiusChunks < record.getMaxRadiusChunks()
            || record.getSlots().values().stream().anyMatch(slot -> slot.radiusChunks() > maximumRadiusChunks));
        if (!requiresTrim) {
            record.setMaxRadiusChunks(maximumRadiusChunks);
            return CompletableFuture.completedFuture(new MaximumUpdated(SereniteaPotManager.saveCatalog()));
        }

        boolean enabled = record.isEnabled();
        return SereniteaPotLifecycleService.beginMaintenance(server, owner).thenApply(result -> {
            if (result instanceof SereniteaPotLifecycleService.Rejected rejected) return new Rejected(rejected.reason());
            if (record.isEnabled() != enabled) {
                abortMaintenance(owner);
                return new Rejected(message(MessageKey.CREATION_STOPPED_BY_ADMIN_CHANGE));
            }
            return startTrim(owner, record, maximumRadiusChunks, requester);
        });
    }

    private static MaximumChangeResult startTrim(UUID owner, SereniteaPotRecord record,
            int maximumRadiusChunks, UUID requester) {
        SereniteaPotBundle previous = SereniteaPotManager.loaded(owner);
        if (previous == null) {
            try {
                previous = SereniteaPotManager.load(owner);
            } catch (RuntimeException error) {
                abortMaintenance(owner);
                return new Rejected(message(MessageKey.CREATION_PREVIOUS_LOAD_FAILED, errorMessage(error)));
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
            return new Rejected(message(MessageKey.CREATION_TRIM_STAGING_FAILED, errorMessage(error)));
        }

        ArrayDeque<RegionCopyTask> tasks = new ArrayDeque<>();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots = new EnumMap<>(SereniteaPotDimension.class);
        long retainedChunks = 0L;
        try {
            for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
                tasks.add(retainDimension(previous.get(dimension), staging.get(dimension), maximumRadiusChunks));
                BlockRegion retained = borderRegion(staging.get(dimension));
                retainedChunks += (long) (retained.getSizeX() / SectionPos.SECTION_SIZE)
                    * (retained.getSizeZ() / SectionPos.SECTION_SIZE);
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
            }
        } catch (RuntimeException error) {
            SereniteaPotLifecycleService.deleteEvacuated(staging);
            abortMaintenance(owner);
            return new Rejected(message(MessageKey.CREATION_TRIM_PREPARE_FAILED, errorMessage(error)));
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
        return new MaximumTrimStarted(generation, tasks.size(), retainedChunks);
    }

    public static boolean isBusy(UUID owner) {
        return jobs.containsKey(owner);
    }

    public static Double progress(UUID owner) {
        CreationJob job = jobs.get(owner);
        return job == null ? null : job.progress();
    }

    public static boolean cancel(UUID owner) {
        CreationJob job = jobs.get(owner);
        if (job == null) return true;
        if (job.commit != null) return false;
        jobs.remove(owner);
        job.closeTasks();
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
            if (job.commit != null) {
                if (!job.commit.isDone()) continue;
                SereniteaPotBundle replaced;
                try {
                    replaced = SereniteaPotManager.finishCommitGeneration(job.commit);
                } catch (RuntimeException error) {
                    SereniteaPotMod.LOGGER.error("Serenitea Pot creation commit failed for {}", job.owner, error);
                    fail(server, job, message(MessageKey.CREATION_INTERNAL_ERROR, errorMessage(error)));
                    jobs.remove(owner);
                    continue;
                }
                jobs.remove(owner);
                // Past the commit point, cleanup must never discard the newly
                // published generation merely because deleting the old one fails.
                if (replaced != null) SereniteaPotLifecycleService.deleteEvacuated(replaced);
                finishCommitted(server, job);
                continue;
            }
            SereniteaPotRecord record = SereniteaPotManager.record(owner);
            if (job.shouldStop(record)) {
                fail(server, job, message(MessageKey.CREATION_STOPPED_BY_ADMIN_CHANGE));
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
                fail(server, job, message(MessageKey.CREATION_INTERNAL_ERROR, errorMessage(error)));
                jobs.remove(owner);
                continue;
            }
            SereniteaPotScheduler.completeCreationSlice(reservation, System.nanoTime() - started);
            SereniteaPotRecord updated = SereniteaPotManager.record(owner);
            if (job.shouldStop(updated)) {
                fail(server, job, message(MessageKey.CREATION_DISABLED_DURING_JOB));
                jobs.remove(owner);
                continue;
            }
            if (job.complete()) {
                // Keep the previous generation until the catalog snapshot reaches disk.
                try {
                    job.commit = SereniteaPotManager.beginCommitGeneration(
                        job.staging,
                        job.replacementSlots,
                        job.committedMaximumRadiusChunks
                    );
                } catch (RuntimeException error) {
                    SereniteaPotMod.LOGGER.error("Serenitea Pot creation commit failed for {}", job.owner, error);
                    fail(server, job, message(MessageKey.CREATION_INTERNAL_ERROR, errorMessage(error)));
                    jobs.remove(owner);
                }
            }
        }
        roundRobinOffset = Math.floorMod(start + 1, Math.max(jobs.size(), 1));
    }

    private static void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Creation stop must run on the server thread");
        SereniteaPotManager.awaitCatalogWrites();
        for (CreationJob job : jobs.values()) {
            job.closeTasks();
            if (job.commit != null) {
                try {
                    SereniteaPotBundle replaced = SereniteaPotManager.finishCommitGeneration(job.commit);
                    if (replaced != null) SereniteaPotLifecycleService.deleteEvacuated(replaced);
                    SereniteaPotLifecycleService.endMaintenance(job.owner);
                    continue;
                } catch (RuntimeException error) {
                    SereniteaPotMod.LOGGER.error("Failed to finish Serenitea Pot commit during shutdown for {}", job.owner, error);
                }
            }
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
                ? message(MessageKey.CREATION_TRIM_COMPLETE, job.committedMaximumRadiusChunks)
                : message(MessageKey.CREATION_COMPLETE, job.staging.generation());
            player.sendSystemMessage(component(player, completion));
        }
    }

    private static void abortMaintenance(UUID owner) {
        SereniteaPotLifecycleService.endMaintenance(owner);
        SereniteaPotLifecycleService.requestClose(owner);
    }

    private static void fail(MinecraftServer server, CreationJob job, Message reason) {
        job.closeTasks();
        try {
            SereniteaPotLifecycleService.deleteEvacuated(job.staging);
        } catch (RuntimeException ignored) {
        }
        abortMaintenance(job.owner);
        ServerPlayer player = job.requester == null
            ? null
            : server.getPlayerList().getPlayer(job.requester);
        if (player != null) {
            MessageKey key = job.kind == JobKind.MAXIMUM_TRIM
                ? MessageKey.CREATION_TRIM_FAILED
                : MessageKey.CREATION_FAILED;
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

    private static RegionCopyTask retainDimension(ServerLevel source, ServerLevel destination, int maximumRadiusChunks) {
        var from = source.getWorldBorder();
        var to = destination.getWorldBorder();
        // Mutate the registered border in place, preserving Arcade's packet listeners.
        to.setCenter(from.getCenterX(), from.getCenterZ());
        to.setSize(Math.min(from.getSize(), (maximumRadiusChunks * 2.0 + 1.0) * SectionPos.SECTION_SIZE));
        to.setDamagePerBlock(from.getDamagePerBlock());
        to.setSafeZone(from.getSafeZone());
        to.setWarningBlocks(from.getWarningBlocks());
        to.setWarningTime(from.getWarningTime());
        BlockRegion retained = borderRegion(destination);
        return new RegionCopyTask(source, destination, retained);
    }

    private static BlockRegion borderRegion(ServerLevel level) {
        var border = level.getWorldBorder();
        // Borders created by extraction are chunk-aligned. Cover the intersecting
        // chunks as well if an administrator deliberately moved the Vanilla border.
        return new BlockRegion(
            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(border.getMinX())),
            level.getMinY(),
            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(border.getMinZ())),
            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(Mth.ceil(border.getMaxX()) - 1), 15),
            level.getMaxY() - 1,
            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(Mth.ceil(border.getMaxZ()) - 1), 15)
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
        private SereniteaPotManager.GenerationCommit commit;

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

        private boolean shouldStop(SereniteaPotRecord record) {
            if (record == null) return true;
            if (kind == JobKind.MAXIMUM_TRIM) {
                return record.isEnabled() != expectedEnabled;
            }
            return !record.isEnabled();
        }

        private void step(long deadline) {
            RegionCopyTask task = tasks.peekFirst();
            if (task == null) return;
            task.step(deadline);
            if (task.getComplete()) {
                task.close();
                tasks.removeFirst();
                completedTasks++;
            }
        }

        private boolean complete() {
            return tasks.isEmpty();
        }

        private void closeTasks() {
            for (RegionCopyTask task : tasks) task.close();
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

    public record MaximumUpdated(CompletableFuture<Void> persisted) implements MaximumChangeResult {
    }

    public record MaximumTrimStarted(
        long generation,
        int dimensionCount,
        long retainedChunks
    ) implements MaximumChangeResult {
    }
}
