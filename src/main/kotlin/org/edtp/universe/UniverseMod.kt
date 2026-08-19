package org.edtp.universe

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.edtp.universe.command.UniverseCommands
import org.edtp.universe.level.UniverseManager
import org.edtp.universe.level.UniverseInvitationService
import org.edtp.universe.level.UniverseLifecycleService
import org.edtp.universe.player.PlayerStateManager
import org.edtp.universe.performance.UniverseScheduler
import org.edtp.universe.region.UniverseCreationService
import org.slf4j.LoggerFactory

object UniverseMod : ModInitializer {
    const val MOD_ID = "universe_647"
    val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        logger.info("Universe 647 initializing")
        UniverseCommands.register()
        UniverseCreationService.register()
        UniverseScheduler.register()
        UniverseLifecycleService.register()
        UniverseInvitationService.register()
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerStateManager::start)
        ServerLifecycleEvents.SERVER_STARTED.register(UniverseManager::start)
        ServerLifecycleEvents.SERVER_STOPPING.register(UniverseManager::stop)
        ServerLifecycleEvents.SERVER_STOPPING.register(PlayerStateManager::stop)
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            PlayerStateManager.onJoin(handler.player)
        }
    }
}
