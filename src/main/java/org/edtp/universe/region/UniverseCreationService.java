package org.edtp.universe.region;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.universe.UniverseMod;
import org.edtp.universe.level.UniverseBundle;
import org.edtp.universe.level.UniverseLifecycleService;
import org.edtp.universe.level.UniverseManager;
import org.edtp.universe.model.UniverseDimension;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.model.UniverseSlotRecord;
import org.edtp.universe.performance.UniverseScheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class UniverseCreationService {
    private static final long COPY_BUDGET_NANOS_PER_TICK = 4_000_000L;
    private static final Map<UUID, CreationJob> jobs = new LinkedHashMap<>();
    private static int roundRobinOffset;

    private UniverseCreationService() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(UniverseCreationService::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(UniverseCreationService::stop);
    }

    public static RequestResult request(ServerPlayer player, int radiusChunks) {
        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Creation must run on the server thread");
        UUID owner = player.getUUID();
        ServerLevel source = player.level();
        UniverseDimension dimension = UniverseDimension.fromVanillaLevel(source.dimension());
        if (dimension == null) return new Rejected("只能从公共主世界、下界或末地提取区域");

        UniverseRecord record = UniverseManager.getOrCreateRecord(owner);
        if (!record.isEnabled()) return new Rejected("你的小宇宙功能已被管理员禁用");
        if (jobs.containsKey(owner)) return new Rejected("已有一个小宇宙创建任务正在运行");
        if (radiusChunks < 0 || radiusChunks > record.getMaxRadiusChunks()) {
            return new Rejected("区块半径必须在 0 到 " + record.getMaxRadiusChunks() + " 之间");
        }

        BlockRegion region;
        try {
            region = BlockRegion.chunkColumns(
                player.blockPosition(), radiusChunks, source.getMinY(), source.getMaxY()
            );
        } catch (ArithmeticException | IllegalArgumentException error) {
            return new Rejected("区域坐标超出可用范围");
        }

        UniverseLifecycleService.Result maintenance = UniverseLifecycleService.beginMaintenance(server, owner);
        if (maintenance instanceof UniverseLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        UniverseBundle previous = null;
        if (record.exists()) {
            previous = UniverseManager.loaded(owner);
            if (previous == null) {
                try {
                    previous = UniverseManager.load(owner);
                } catch (Throwable error) {
                    abortMaintenance(owner);
                    return new Rejected("原小宇宙无法加载：" + error.getMessage());
                }
            }
        }
        long generation = Math.max(record.getActiveGeneration() + 1, System.currentTimeMillis());
        UniverseBundle staging;
        try {
            staging = UniverseManager.createStaging(owner, generation, source.getSeed());
        } catch (Throwable error) {
            abortMaintenance(owner);
            return new Rejected("无法创建暂存维度：" + error.getMessage());
        }

        ArrayDeque<RegionCopyTask> tasks = new ArrayDeque<>();
        EnumMap<UniverseDimension, UniverseSlotRecord> replacementSlots = copySlots(record.getSlots());
        for (UniverseDimension slotDimension : UniverseDimension.values()) {
            ServerLevel destination = staging.get(slotDimension);
            if (slotDimension == dimension) {
                tasks.add(new RegionCopyTask(source, destination, region));
            } else {
                UniverseSlotRecord oldSlot = record.getSlots().get(slotDimension);
                if (previous != null && oldSlot != null) {
                    tasks.add(new RegionCopyTask(
                        previous.get(slotDimension),
                        destination,
                        regionForSlot(previous.get(slotDimension), oldSlot)
                    ));
                }
            }
        }
        replacementSlots.put(dimension, new UniverseSlotRecord(
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
            null
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
        if (maximumRadiusChunks < 0 || maximumRadiusChunks > UniverseRecord.MAX_RADIUS_CHUNKS) {
            return new Rejected("区块半径必须在 0 到 " + UniverseRecord.MAX_RADIUS_CHUNKS + " 之间");
        }
        if (jobs.containsKey(owner)) {
            return new Rejected("该玩家已有一个小宇宙维护任务正在运行");
        }

        UniverseRecord record = UniverseManager.getOrCreateRecord(owner);
        boolean requiresTrim = record.exists() && record.getSlots().values().stream()
            .anyMatch(slot -> slot.radiusChunks() > maximumRadiusChunks);
        if (!requiresTrim) {
            record.setMaxRadiusChunks(maximumRadiusChunks);
            UniverseManager.saveCatalog();
            return MaximumUpdated.INSTANCE;
        }

        UniverseLifecycleService.Result maintenance = UniverseLifecycleService.beginMaintenance(server, owner);
        if (maintenance instanceof UniverseLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        UniverseBundle previous = UniverseManager.loaded(owner);
        if (previous == null) {
            try {
                previous = UniverseManager.load(owner);
            } catch (Throwable error) {
                abortMaintenance(owner);
                return new Rejected("原小宇宙无法加载：" + errorMessage(error));
            }
        }

        long generation = Math.max(record.getActiveGeneration() + 1, System.currentTimeMillis());
        UniverseBundle staging;
        try {
            staging = UniverseManager.createStaging(
                owner,
                generation,
                previous.get(UniverseDimension.OVERWORLD).getSeed()
            );
        } catch (Throwable error) {
            abortMaintenance(owner);
            return new Rejected("无法创建裁剪暂存维度：" + errorMessage(error));
        }

        ArrayDeque<RegionCopyTask> tasks = new ArrayDeque<>();
        EnumMap<UniverseDimension, UniverseSlotRecord> replacementSlots = new EnumMap<>(UniverseDimension.class);
        long retainedChunks = 0L;
        try {
            for (UniverseDimension dimension : UniverseDimension.values()) {
                UniverseSlotRecord slot = record.getSlots().get(dimension);
                if (slot == null) continue;
                int retainedRadius = Math.min(slot.radiusChunks(), maximumRadiusChunks);
                UniverseSlotRecord replacement = new UniverseSlotRecord(
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
                    regionForSlot(previous.get(dimension), replacement)
                ));
                long diameter = retainedRadius * 2L + 1L;
                retainedChunks = Math.addExact(retainedChunks, Math.multiplyExact(diameter, diameter));
            }
        } catch (Throwable error) {
            UniverseLifecycleService.deleteEvacuated(staging);
            abortMaintenance(owner);
            return new Rejected("无法准备边缘裁剪：" + errorMessage(error));
        }

        jobs.put(owner, new CreationJob(
            owner,
            staging,
            tasks,
            replacementSlots,
            requester,
            maximumRadiusChunks,
            JobKind.MAXIMUM_TRIM,
            AdministrativeState.capture(record)
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
        UniverseLifecycleService.deleteEvacuated(job.staging);
        abortMaintenance(owner);
        return true;
    }

    private static void tick(MinecraftServer server) {
        if (jobs.isEmpty()) return;
        long globalDeadline = System.nanoTime() + COPY_BUDGET_NANOS_PER_TICK;
        List<UUID> owners = new ArrayList<>(jobs.keySet());
        int start = Math.floorMod(roundRobinOffset, owners.size());
        for (int visited = 0; visited < owners.size(); visited++) {
            long now = System.nanoTime();
            if (now >= globalDeadline) break;
            UUID owner = owners.get(Math.floorMod(start + visited, owners.size()));
            CreationJob job = jobs.get(owner);
            if (job == null) continue;
            UniverseRecord record = UniverseManager.record(owner);
            if (!job.canContinue(record)) {
                fail(server, job, "创建任务已因管理状态变化而停止");
                jobs.remove(owner);
                continue;
            }
            long fairShare = (globalDeadline - now) / (owners.size() - visited);
            UniverseScheduler.CreationReservation reservation = UniverseScheduler.reserveCreationSlice(owner, fairShare);
            if (reservation == null) continue;
            long started = System.nanoTime();
            try {
                job.step(started + (long) reservation.reservedNanos());
            } catch (Throwable error) {
                UniverseScheduler.completeCreationSlice(reservation, System.nanoTime() - started);
                UniverseMod.LOGGER.error("Universe creation failed for {}", job.owner, error);
                fail(server, job, errorMessage(error));
                jobs.remove(owner);
                continue;
            }
            UniverseScheduler.completeCreationSlice(reservation, System.nanoTime() - started);
            UniverseRecord updated = UniverseManager.record(owner);
            if (!job.canContinue(updated)) {
                fail(server, job, "创建任务期间小宇宙被禁用或删除");
                jobs.remove(owner);
                continue;
            }
            if (job.complete()) {
                UniverseBundle replaced;
                try {
                    replaced = UniverseManager.commitGeneration(
                        job.staging,
                        job.replacementSlots,
                        job.committedMaximumRadiusChunks
                    );
                } catch (Throwable error) {
                    UniverseMod.LOGGER.error("Universe creation commit failed for {}", job.owner, error);
                    fail(server, job, errorMessage(error));
                    jobs.remove(owner);
                    continue;
                }
                jobs.remove(owner);
                if (replaced != null) UniverseLifecycleService.deleteEvacuated(replaced);
                finishCommitted(server, job);
            }
        }
        roundRobinOffset = Math.floorMod(start + 1, Math.max(jobs.size(), 1));
    }

    private static void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Creation stop must run on the server thread");
        for (CreationJob job : jobs.values()) {
            try {
                UniverseLifecycleService.deleteEvacuated(job.staging);
            } catch (Throwable error) {
                UniverseMod.LOGGER.error(
                    "Failed to discard staging universe for {} during shutdown", job.owner, error
                );
            }
            UniverseLifecycleService.endMaintenance(job.owner);
        }
        jobs.clear();
        roundRobinOffset = 0;
    }

    private static void finishCommitted(MinecraftServer server, CreationJob job) {
        UniverseLifecycleService.endMaintenance(job.owner);
        try {
            UniverseLifecycleService.Result close = UniverseLifecycleService.closeNow(server, job.owner);
            if (close instanceof UniverseLifecycleService.Rejected rejected) {
                UniverseMod.LOGGER.warn(
                    "Committed universe {} but its post-creation unload was deferred: {}",
                    job.owner, rejected.reason()
                );
            }
        } catch (Throwable error) {
            UniverseMod.LOGGER.error("Committed universe {} but failed to close it", job.owner, error);
            UniverseLifecycleService.requestClose(job.owner);
        }
        ServerPlayer player = job.requester == null
            ? null
            : server.getPlayerList().getPlayer(job.requester);
        if (player != null) {
            String message = job.kind == JobKind.MAXIMUM_TRIM
                ? "小宇宙最大区块半径已设为 " + job.committedMaximumRadiusChunks + "，超出边缘已删除"
                : "小宇宙已创建完成（代际 " + job.staging.generation() + "）";
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private static void abortMaintenance(UUID owner) {
        UniverseLifecycleService.endMaintenance(owner);
        UniverseLifecycleService.requestClose(owner);
    }

    private static void fail(MinecraftServer server, CreationJob job, String reason) {
        try {
            UniverseLifecycleService.deleteEvacuated(job.staging);
        } catch (Throwable ignored) {
        }
        abortMaintenance(job.owner);
        ServerPlayer player = job.requester == null
            ? null
            : server.getPlayerList().getPlayer(job.requester);
        if (player != null) {
            String operation = job.kind == JobKind.MAXIMUM_TRIM ? "裁剪" : "创建";
            player.sendSystemMessage(Component.literal("小宇宙" + operation + "失败：" + reason));
        }
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static EnumMap<UniverseDimension, UniverseSlotRecord> copySlots(
        Map<UniverseDimension, UniverseSlotRecord> source
    ) {
        EnumMap<UniverseDimension, UniverseSlotRecord> copy = new EnumMap<>(UniverseDimension.class);
        copy.putAll(source);
        return copy;
    }

    private static BlockRegion regionForSlot(ServerLevel level, UniverseSlotRecord slot) {
        return BlockRegion.chunkColumns(
            new BlockPos(slot.entryX(), slot.entryY(), slot.entryZ()),
            slot.radiusChunks(),
            level.getMinY(),
            level.getMaxY()
        );
    }

    private static final class CreationJob {
        private final UUID owner;
        private final UniverseBundle staging;
        private final ArrayDeque<RegionCopyTask> tasks;
        private final EnumMap<UniverseDimension, UniverseSlotRecord> replacementSlots;
        private final UUID requester;
        private final int committedMaximumRadiusChunks;
        private final JobKind kind;
        private final AdministrativeState administrativeState;
        private final int totalTasks;
        private int completedTasks;

        private CreationJob(
            UUID owner,
            UniverseBundle staging,
            ArrayDeque<RegionCopyTask> tasks,
            EnumMap<UniverseDimension, UniverseSlotRecord> replacementSlots,
            UUID requester,
            int committedMaximumRadiusChunks,
            JobKind kind,
            AdministrativeState administrativeState
        ) {
            this.owner = owner;
            this.staging = staging;
            this.tasks = tasks;
            this.replacementSlots = replacementSlots;
            this.requester = requester;
            this.committedMaximumRadiusChunks = committedMaximumRadiusChunks;
            this.kind = kind;
            this.administrativeState = administrativeState;
            this.totalTasks = tasks.size();
        }

        private boolean canContinue(UniverseRecord record) {
            if (record == null) return false;
            if (kind == JobKind.MAXIMUM_TRIM) {
                return administrativeState.equals(AdministrativeState.capture(record));
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

    private record AdministrativeState(boolean enabled) {
        private static AdministrativeState capture(UniverseRecord record) {
            return new AdministrativeState(record.isEnabled());
        }
    }

    public sealed interface RequestResult permits Accepted, Rejected {
    }

    public record Accepted(long chunkCount, long generation) implements RequestResult {
    }

    public record Rejected(String reason) implements RequestResult, MaximumChangeResult {
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
