package org.edtp.universe

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object UniverseMod : ModInitializer {
    const val MOD_ID = "universe_647"
    val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        logger.info("Universe 647 initializing")
    }
}

