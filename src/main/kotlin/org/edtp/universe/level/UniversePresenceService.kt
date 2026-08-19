package org.edtp.universe.level

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import org.edtp.universe.UniverseMod
import java.util.UUID

object UniversePresenceService {
    private val pendingUnloads = LinkedHashSet<UUID>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::endServerTick)
    }

    @JvmStatic
    fun ownerLeft(owner: UUID) {
        pendingUnloads.add(owner)
    }

    private fun endServerTick(server: MinecraftServer) {
        val iterator = pendingUnloads.iterator()
        while (iterator.hasNext()) {
            val owner = iterator.next()
            val stillInside = UniverseAccessPolicy.isRealOwnerInside(server, owner)
            if (stillInside) {
                iterator.remove()
                continue
            }
            runCatching { UniverseManager.unload(owner) }
                .onFailure { error -> UniverseMod.logger.error("Failed to unload departed owner's universe {}", owner, error) }
            iterator.remove()
        }
    }
}
