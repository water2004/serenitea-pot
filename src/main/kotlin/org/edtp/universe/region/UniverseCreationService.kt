package org.edtp.universe.region

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.edtp.universe.UniverseMod
import org.edtp.universe.level.UniverseBundle
import org.edtp.universe.level.UniverseLifecycleService
import org.edtp.universe.level.UniverseManager
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.model.UniverseSlotRecord
import org.edtp.universe.performance.UniverseScheduler
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.UUID

object UniverseCreationService {
    private const val COPY_BUDGET_NANOS_PER_TICK = 4_000_000L
    private val jobs = LinkedHashMap<UUID, CreationJob>()
    private var roundRobinOffset = 0

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerLifecycleEvents.SERVER_STOPPING.register(::stop)
    }

    fun request(player: ServerPlayer, radius: Int): RequestResult {
        val server = player.level().server
        check(server.isSameThread)
        val owner = player.uuid
        val source = player.level()
        val dimension = UniverseDimension.fromVanillaLevel(source.dimension())
            ?: return RequestResult.Rejected("只能从公共主世界、下界或末地提取区域")
        val record = UniverseManager.getOrCreateRecord(owner)
        if (!record.enabled) {
            return RequestResult.Rejected("你的小宇宙功能已被管理员禁用")
        }
        if (record.quarantined) {
            return RequestResult.Rejected("你的小宇宙已被隔离，请联系管理员")
        }
        if (record.frozen || record.stopped) {
            return RequestResult.Rejected("你的小宇宙已被管理员冻结或停止")
        }
        if (jobs.containsKey(owner)) {
            return RequestResult.Rejected("已有一个小宇宙创建任务正在运行")
        }
        if (radius !in 1..record.maxRadius) {
            return RequestResult.Rejected("半径必须在 1 到 ${record.maxRadius} 之间")
        }

        val region = runCatching { BlockRegion.centered(player.blockPosition(), radius) }
            .getOrElse { return RequestResult.Rejected("区域坐标超出可用范围") }
        if (region.minY < source.minY || region.maxY > source.maxY) {
            return RequestResult.Rejected("立方体超出当前维度高度范围 ${source.minY}..${source.maxY}")
        }

        when (val maintenance = UniverseLifecycleService.beginMaintenance(server, owner)) {
            UniverseLifecycleService.Result.Success -> Unit
            is UniverseLifecycleService.Result.Rejected -> return RequestResult.Rejected(maintenance.reason)
        }

        val previous = if (record.exists) {
            UniverseManager.loaded(owner) ?: runCatching { UniverseManager.load(owner) }
                .getOrElse {
                    abortMaintenance(owner)
                    return RequestResult.Rejected("原小宇宙无法加载：${it.message}")
                }
        } else {
            null
        }
        val generation = maxOf(record.activeGeneration + 1, System.currentTimeMillis())
        val staging = runCatching { UniverseManager.createStaging(owner, generation, source.seed) }
            .getOrElse {
                abortMaintenance(owner)
                return RequestResult.Rejected("无法创建暂存维度：${it.message}")
            }

        val tasks = ArrayDeque<RegionCopyTask>()
        val replacementSlots = EnumMap<UniverseDimension, UniverseSlotRecord>(UniverseDimension::class.java)
        replacementSlots.putAll(record.slots.mapValues { (_, slot) -> slot.copy() })
        for (slotDimension in UniverseDimension.entries) {
            val destination = staging[slotDimension]
            if (slotDimension == dimension) {
                tasks.add(RegionCopyTask(source, destination, region))
            } else {
                val oldSlot = record.slots[slotDimension]
                if (previous != null && oldSlot != null) {
                    tasks.add(
                        RegionCopyTask(
                            previous[slotDimension],
                            destination,
                            BlockRegion.centered(
                                net.minecraft.core.BlockPos(oldSlot.centerX, oldSlot.centerY, oldSlot.centerZ),
                                oldSlot.radius,
                            ),
                        ),
                    )
                }
            }
        }
        replacementSlots[dimension] = UniverseSlotRecord(
            sourceDimension = source.dimension().identifier().toString(),
            centerX = player.blockX,
            centerY = player.blockY,
            centerZ = player.blockZ,
            radius = radius,
        )
        jobs[owner] = CreationJob(
            owner,
            staging,
            tasks,
            replacementSlots,
            player.uuid,
        )
        return RequestResult.Accepted(region.volume, generation)
    }

    fun isBusy(owner: UUID): Boolean = jobs.containsKey(owner)

    fun progress(owner: UUID): Double? = jobs[owner]?.progress()

    fun cancel(owner: UUID): Boolean {
        val job = jobs.remove(owner) ?: return false
        UniverseManager.discard(job.staging)
        abortMaintenance(owner)
        return true
    }

    private fun tick(server: MinecraftServer) {
        if (jobs.isEmpty()) {
            return
        }
        val globalDeadline = System.nanoTime() + COPY_BUDGET_NANOS_PER_TICK
        val owners = jobs.keys.toList()
        val start = roundRobinOffset.mod(owners.size)
        for (visited in owners.indices) {
            val now = System.nanoTime()
            if (now >= globalDeadline) {
                break
            }
            val owner = owners[(start + visited).mod(owners.size)]
            val job = jobs[owner] ?: continue
            val record = UniverseManager.record(owner)
            if (record == null || !record.enabled || record.frozen || record.stopped || record.quarantined) {
                fail(server, job, "创建任务已因管理状态变化而停止")
                jobs.remove(owner)
                continue
            }
            val fairShare = (globalDeadline - now) / (owners.size - visited)
            val reservation = UniverseScheduler.reserveCreationSlice(owner, fairShare) ?: continue
            val started = System.nanoTime()
            try {
                job.step(started + reservation.reservedNanos.toLong())
            } catch (error: Throwable) {
                UniverseScheduler.completeCreationSlice(server, reservation, System.nanoTime() - started)
                UniverseMod.logger.error("Universe creation failed for {}", job.owner, error)
                fail(server, job, error.message ?: error.javaClass.simpleName)
                jobs.remove(owner)
                continue
            }
            UniverseScheduler.completeCreationSlice(server, reservation, System.nanoTime() - started)
            if (UniverseManager.record(owner)?.quarantined == true) {
                fail(server, job, "创建复制触发性能隔离")
                jobs.remove(owner)
                continue
            }
            if (job.complete()) {
                val previous: UniverseBundle?
                try {
                    previous = UniverseManager.activate(job.staging, job.replacementSlots)
                } catch (error: Throwable) {
                    UniverseMod.logger.error("Universe creation commit failed for {}", job.owner, error)
                    fail(server, job, error.message ?: error.javaClass.simpleName)
                    jobs.remove(owner)
                    continue
                }
                jobs.remove(owner)
                previous?.let(UniverseLifecycleService::deleteReplaced)
                finishCommitted(server, job)
            }
        }
        roundRobinOffset = (start + 1).mod(jobs.size.coerceAtLeast(1))
    }

    private fun stop(server: MinecraftServer) {
        check(server.isSameThread)
        for (job in jobs.values) {
            runCatching { UniverseManager.discard(job.staging) }
                .onFailure { error ->
                    UniverseMod.logger.error("Failed to discard staging universe for {} during shutdown", job.owner, error)
                }
            UniverseLifecycleService.endMaintenance(job.owner)
        }
        jobs.clear()
        roundRobinOffset = 0
    }

    private fun finishCommitted(server: MinecraftServer, job: CreationJob) {
        UniverseLifecycleService.endMaintenance(job.owner)
        runCatching { UniverseLifecycleService.closeNow(server, job.owner) }
            .onSuccess { close ->
                if (close is UniverseLifecycleService.Result.Rejected) {
                    UniverseMod.logger.warn(
                        "Committed universe {} but its post-creation unload was deferred: {}",
                        job.owner,
                        close.reason,
                    )
                }
            }
            .onFailure { error ->
                UniverseMod.logger.error("Committed universe {} but failed to close it", job.owner, error)
                UniverseLifecycleService.requestClose(job.owner)
            }
        runCatching {
            server.playerList.getPlayer(job.requester)?.sendSystemMessage(
                Component.literal("小宇宙已创建完成（代际 ${job.staging.generation}）"),
            )
        }
    }

    private fun abortMaintenance(owner: UUID) {
        UniverseLifecycleService.endMaintenance(owner)
        UniverseLifecycleService.requestClose(owner)
    }

    private fun fail(server: MinecraftServer, job: CreationJob, reason: String) {
        runCatching { UniverseManager.discard(job.staging) }
        abortMaintenance(job.owner)
        server.playerList.getPlayer(job.requester)?.sendSystemMessage(
            Component.literal("小宇宙创建失败：$reason"),
        )
    }

    private data class CreationJob(
        val owner: UUID,
        val staging: UniverseBundle,
        val tasks: ArrayDeque<RegionCopyTask>,
        val replacementSlots: EnumMap<UniverseDimension, UniverseSlotRecord>,
        val requester: UUID,
        var completedTasks: Int = 0,
    ) {
        private val totalTasks = tasks.size

        fun step(deadline: Long) {
            val task = tasks.firstOrNull() ?: return
            task.step(deadline)
            if (task.complete) {
                tasks.removeFirst()
                completedTasks++
            }
        }

        fun complete(): Boolean = tasks.isEmpty()

        fun progress(): Double {
            if (totalTasks == 0) {
                return 1.0
            }
            val current = tasks.firstOrNull()?.progress ?: 0.0
            return (completedTasks + current) / totalTasks
        }
    }

    sealed interface RequestResult {
        data class Accepted(val volume: Long, val generation: Long) : RequestResult
        data class Rejected(val reason: String) : RequestResult
    }
}
