package org.edtp.sereniteapot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.edtp.sereniteapot.command.SereniteaPotCommands;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SereniteaPotMod implements ModInitializer {
    public static final String MOD_ID = "serenitea_pot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Serenitea Pot initializing");
        SereniteaPotCommands.register();
        SereniteaPotCreationService.register();
        SereniteaPotScheduler.register();
        SereniteaPotLifecycleService.register();
        SereniteaPotInvitationService.register();
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerStateManager::start);
        ServerLifecycleEvents.SERVER_STARTED.register(SereniteaPotManager::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(SereniteaPotManager::stop);
        ServerLifecycleEvents.SERVER_STOPPING.register(PlayerStateManager::stop);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            PlayerStateManager.onJoin(handler.getPlayer())
        );
    }
}
