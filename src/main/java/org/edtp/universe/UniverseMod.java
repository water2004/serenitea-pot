package org.edtp.universe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.edtp.universe.command.UniverseCommands;
import org.edtp.universe.level.UniverseInvitationService;
import org.edtp.universe.level.UniverseLifecycleService;
import org.edtp.universe.level.UniverseManager;
import org.edtp.universe.performance.UniverseScheduler;
import org.edtp.universe.player.PlayerStateManager;
import org.edtp.universe.region.UniverseCreationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UniverseMod implements ModInitializer {
    public static final String MOD_ID = "universe_647";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Universe 647 initializing");
        UniverseCommands.register();
        UniverseCreationService.register();
        UniverseScheduler.register();
        UniverseLifecycleService.register();
        UniverseInvitationService.register();
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerStateManager::start);
        ServerLifecycleEvents.SERVER_STARTED.register(UniverseManager::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(UniverseManager::stop);
        ServerLifecycleEvents.SERVER_STOPPING.register(PlayerStateManager::stop);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            PlayerStateManager.onJoin(handler.getPlayer())
        );
    }
}
