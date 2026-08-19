package org.edtp.universe

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.edtp.universe.level.UniverseManager
import org.slf4j.LoggerFactory

object UniverseMod : ModInitializer {
    const val MOD_ID = "universe_647"
    val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        logger.info("Universe 647 initializing")
        ServerLifecycleEvents.SERVER_STARTED.register(UniverseManager::start)
        ServerLifecycleEvents.SERVER_STOPPING.register(UniverseManager::stop)
    }
}
