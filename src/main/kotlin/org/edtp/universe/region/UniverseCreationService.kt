package org.edtp.universe.region

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.edtp.universe.UniverseMod
import org.edtp.universe.level.UniverseBundle
import org.edtp.universe.level.UniverseManager
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.model.UniverseSlotRecord
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.UUID

object UniverseCreationService {
    private const val COPY_BUDGET_NANOS_PER_TICK = 4_000_000L
    private val jobs = LinkedHashMap<UUID, CreationJob>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
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

        val previous = if (record.exists) {
            UniverseManager.loaded(owner) ?: runCatching { UniverseManager.load(owner) }
                .getOrElse { return RequestResult.Rejected("原小宇宙无法加载：${it.message}") }
        } else {
            null
        }
        val generation = record.activeGeneration + 1
        val staging = runCatching { UniverseManager.createStaging(owner, generation, source.seed) }
            .getOrElse { return RequestResult.Rejected("无法创建暂存维度：${it.message}") }

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
        return true
    }

    private fun tick(server: MinecraftServer) {
        if (jobs.isEmpty()) {
            return
        }
        val deadline = System.nanoTime() + COPY_BUDGET_NANOS_PER_TICK
        val iterator = jobs.values.iterator()
        while (iterator.hasNext() && System.nanoTime() < deadline) {
            val job = iterator.next()
            try {
                job.step(deadline)
                if (job.complete()) {
                    finish(server, job)
                    iterator.remove()
                }
            } catch (error: Throwable) {
                UniverseMod.logger.error("Universe creation failed for {}", job.owner, error)
                runCatching { UniverseManager.discard(job.staging) }
                server.playerList.getPlayer(job.requester)?.sendSystemMessage(
                    Component.literal("小宇宙创建失败：${error.message}"),
                )
                iterator.remove()
            }
        }
    }

    private fun finish(server: MinecraftServer, job: CreationJob) {
        val record = UniverseManager.getOrCreateRecord(job.owner)
        record.slots.clear()
        record.slots.putAll(job.replacementSlots)
        UniverseManager.activate(job.staging)
        server.playerList.getPlayer(job.requester)?.sendSystemMessage(
            Component.literal("小宇宙已创建完成（代际 ${job.staging.generation}）"),
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
            while (tasks.isNotEmpty() && System.nanoTime() < deadline) {
                val task = tasks.first()
                task.step(deadline)
                if (!task.complete) {
                    return
                }
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
