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

    public static RequestResult request(ServerPlayer player, int radius) {
        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Creation must run on the server thread");
        UUID owner = player.getUUID();
        ServerLevel source = player.level();
        UniverseDimension dimension = UniverseDimension.fromVanillaLevel(source.dimension());
        if (dimension == null) return new Rejected("只能从公共主世界、下界或末地提取区域");

        UniverseRecord record = UniverseManager.getOrCreateRecord(owner);
        if (!record.isEnabled()) return new Rejected("你的小宇宙功能已被管理员禁用");
        if (record.isQuarantined()) return new Rejected("你的小宇宙已被隔离，请联系管理员");
        if (record.isFrozen() || record.isStopped()) return new Rejected("你的小宇宙已被管理员冻结或停止");
        if (jobs.containsKey(owner)) return new Rejected("已有一个小宇宙创建任务正在运行");
        if (radius < 1 || radius > record.getMaxRadius()) {
            return new Rejected("半径必须在 1 到 " + record.getMaxRadius() + " 之间");
        }

        BlockRegion region;
        try {
            region = BlockRegion.centered(player.blockPosition(), radius);
        } catch (ArithmeticException | IllegalArgumentException error) {
            return new Rejected("区域坐标超出可用范围");
        }
        if (region.getMinY() < source.getMinY() || region.getMaxY() > source.getMaxY()) {
            return new Rejected(
                "立方体超出当前维度高度范围 " + source.getMinY() + ".." + source.getMaxY()
            );
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
                        BlockRegion.centered(
                            new BlockPos(oldSlot.centerX(), oldSlot.centerY(), oldSlot.centerZ()),
                            oldSlot.radius()
                        )
                    ));
                }
            }
        }
        replacementSlots.put(dimension, new UniverseSlotRecord(
            source.dimension().identifier().toString(),
            player.getBlockX(), player.getBlockY(), player.getBlockZ(), radius
        ));
        jobs.put(owner, new CreationJob(owner, staging, tasks, replacementSlots, player.getUUID()));
        return new Accepted(region.getVolume(), generation);
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
            if (record == null || !record.isEnabled() || record.isFrozen()
                || record.isStopped() || record.isQuarantined()) {
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
                UniverseScheduler.completeCreationSlice(server, reservation, System.nanoTime() - started);
                UniverseMod.LOGGER.error("Universe creation failed for {}", job.owner, error);
                fail(server, job, errorMessage(error));
                jobs.remove(owner);
                continue;
            }
            UniverseScheduler.completeCreationSlice(server, reservation, System.nanoTime() - started);
            UniverseRecord updated = UniverseManager.record(owner);
            if (updated != null && updated.isQuarantined()) {
                fail(server, job, "创建复制触发性能隔离");
                jobs.remove(owner);
                continue;
            }
            if (job.complete()) {
                UniverseBundle replaced;
                try {
                    replaced = UniverseManager.activate(job.staging, job.replacementSlots);
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
        ServerPlayer player = server.getPlayerList().getPlayer(job.requester);
        if (player != null) {
            player.sendSystemMessage(Component.literal(
                "小宇宙已创建完成（代际 " + job.staging.generation() + "）"
            ));
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
        ServerPlayer player = server.getPlayerList().getPlayer(job.requester);
        if (player != null) player.sendSystemMessage(Component.literal("小宇宙创建失败：" + reason));
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

    private static final class CreationJob {
        private final UUID owner;
        private final UniverseBundle staging;
        private final ArrayDeque<RegionCopyTask> tasks;
        private final EnumMap<UniverseDimension, UniverseSlotRecord> replacementSlots;
        private final UUID requester;
        private final int totalTasks;
        private int completedTasks;

        private CreationJob(
            UUID owner,
            UniverseBundle staging,
            ArrayDeque<RegionCopyTask> tasks,
            EnumMap<UniverseDimension, UniverseSlotRecord> replacementSlots,
            UUID requester
        ) {
            this.owner = owner;
            this.staging = staging;
            this.tasks = tasks;
            this.replacementSlots = replacementSlots;
            this.requester = requester;
            this.totalTasks = tasks.size();
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

    public sealed interface RequestResult permits Accepted, Rejected {
    }

    public record Accepted(long volume, long generation) implements RequestResult {
    }

    public record Rejected(String reason) implements RequestResult {
    }
}
