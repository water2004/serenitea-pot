package org.edtp.universe.level

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.casual.arcade.dimensions.level.CustomLevel
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.edtp.universe.UniverseMod
import java.util.UUID

/**
 * The only gateway for taking an active universe out of service.
 *
 * Closing is deferred to the end of the server tick when requested from world
 * ticking code. Every path first prevents admission, then evacuates players,
 * verifies that no player still references a custom level, and only then asks
 * [UniverseManager] to remove the levels.
 */
object UniverseLifecycleService {
    private val pendingCloses = LinkedHashSet<UUID>()
    private val maintenance = LinkedHashSet<UUID>()
    private val closing = LinkedHashSet<UUID>()
    private val pendingDeletes = LinkedHashMap<Pair<UUID, Long>, PendingDelete>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::endServerTick)
        ServerLifecycleEvents.SERVER_STOPPING.register(::stop)
        ServerLifecycleEvents.SERVER_STOPPED.register { clearAll() }
    }

    @JvmStatic
    fun ownerLeft(owner: UUID) {
        requestClose(owner)
    }

    @JvmStatic
    fun forceUnload(owner: UUID) {
        requestClose(owner)
    }

    fun requestClose(owner: UUID) {
        pendingCloses.add(owner)
    }

    fun isUnavailable(owner: UUID): Boolean =
        owner in pendingCloses || owner in maintenance || owner in closing

    /**
     * Locks a universe against admission while a replacement generation is
     * prepared. Existing occupants are evacuated, but the old levels stay
     * loaded as read-only copy sources.
     */
    fun beginMaintenance(server: MinecraftServer, owner: UUID): Result {
        check(server.isSameThread)
        if (!maintenance.add(owner)) {
            return Result.Rejected("该小宇宙已有维护任务")
        }
        pendingCloses.remove(owner)
        val remaining = evacuate(server, owner)
        if (remaining.isNotEmpty()) {
            maintenance.remove(owner)
            pendingCloses.add(owner)
            return Result.Rejected("无法安全送出 ${remaining.size} 名小宇宙成员")
        }
        return Result.Success
    }

    fun endMaintenance(owner: UUID) {
        maintenance.remove(owner)
    }

    /**
     * Runs the close transaction immediately. Callers must already be on the
     * server thread and outside MinecraftServer's level iterator.
     */
    fun closeNow(server: MinecraftServer, owner: UUID): Result {
        check(server.isSameThread)
        pendingCloses.add(owner)
        if (!closing.add(owner)) {
            return Result.Rejected("该小宇宙正在关闭")
        }
        try {
            val remaining = evacuate(server, owner)
            if (remaining.isNotEmpty()) {
                for (player in remaining) {
                    player.connection.disconnect(Component.literal("小宇宙正在安全卸载，请重新连接"))
                }
                return Result.Rejected("仍有 ${remaining.size} 名玩家未完成离场，已断开连接并将在下一 tick 重试")
            }
            if (!UniverseManager.unloadEvacuated(owner)) {
                return Result.Rejected("至少一个维度尚未完成卸载，将在下一 tick 重试")
            }
            pendingCloses.remove(owner)
            return Result.Success
        } finally {
            closing.remove(owner)
        }
    }

    fun forget(owner: UUID) {
        pendingCloses.remove(owner)
        maintenance.remove(owner)
        closing.remove(owner)
        pendingDeletes.keys.removeIf { it.first == owner }
    }

    fun deleteReplaced(bundle: UniverseBundle) {
        require(bundle.levels.values.all { it.players().isEmpty() }) {
            "Cannot delete occupied replacement generation ${bundle.generation}"
        }
        pendingDeletes[bundle.owner to bundle.generation] = PendingDelete(bundle.levels.values.toMutableList())
        processPendingDeletes()
    }

    private fun clearAll() {
        pendingCloses.clear()
        maintenance.clear()
        closing.clear()
        pendingDeletes.clear()
    }

    private fun evacuate(server: MinecraftServer, owner: UUID): List<net.minecraft.server.level.ServerPlayer> {
        val occupants = server.playerList.players
            .filter { UniverseLevelKeys.identify(it.level().dimension())?.owner == owner }
            .toList()
        for (player in occupants) {
            when (val result = UniverseTravelService.evict(player, owner)) {
                UniverseTravelService.Result.Success -> Unit
                is UniverseTravelService.Result.Rejected -> UniverseMod.logger.warn(
                    "Failed to evacuate {} from universe {}: {}",
                    player.uuid,
                    owner,
                    result.reason,
                )
            }
        }
        return server.playerList.players
            .filter { UniverseLevelKeys.identify(it.level().dimension())?.owner == owner }
    }

    private fun endServerTick(server: MinecraftServer) {
        for (owner in pendingCloses.toList()) {
            when (val result = closeNow(server, owner)) {
                Result.Success -> Unit
                is Result.Rejected -> UniverseMod.logger.warn(
                    "Universe {} close was deferred: {}",
                    owner,
                    result.reason,
                )
            }
        }
        processPendingDeletes()
    }

    private fun processPendingDeletes(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val iterator = pendingDeletes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            if (!force && now < pending.nextAttemptMillis) {
                continue
            }
            pending.levels.removeIf(UniverseManager::deleteEvacuatedLevel)
            if (pending.levels.isEmpty()) {
                iterator.remove()
            } else {
                pending.nextAttemptMillis = now + DELETE_RETRY_MILLIS
            }
        }
    }

    private fun stop(server: MinecraftServer) {
        check(server.isSameThread)
        for (owner in UniverseManager.loadedOwners()) {
            when (val result = closeNow(server, owner)) {
                Result.Success -> Unit
                is Result.Rejected -> UniverseMod.logger.error(
                    "Could not fully close universe {} during shutdown: {}",
                    owner,
                    result.reason,
                )
            }
        }
        processPendingDeletes(force = true)
    }

    private data class PendingDelete(
        val levels: MutableList<CustomLevel>,
        var nextAttemptMillis: Long = 0,
    )

    sealed interface Result {
        data object Success : Result
        data class Rejected(val reason: String) : Result
    }

    private const val DELETE_RETRY_MILLIS = 1_000L
}
