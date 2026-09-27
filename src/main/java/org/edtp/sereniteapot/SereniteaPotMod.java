package org.edtp.sereniteapot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.edtp.sereniteapot.region.RegionCopyTask;
import org.edtp.sereniteapot.command.SereniteaPotCommands;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandPolicy;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.permission.SereniteaPotToolPermissions;
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
        RegionCopyTask.registerTicketType();
        SereniteaPotCommands.register();
        SereniteaPotCreationService.register();
        SereniteaPotScheduler.register();
        SereniteaPotToolPermissions.register();
        SereniteaPotCommandPolicy.register();
        SereniteaPotLifecycleService.register();
        SereniteaPotInvitationService.register();
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerStateManager::start);
        ServerLifecycleEvents.SERVER_STARTED.register(SereniteaPotManager::start);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var playerId = handler.player.getUUID();
            PlayerStateManager.prepare(handler.player).whenComplete((ignored, error) -> {
                if (error != null) LOGGER.error("Failed to preload private player data for {}", playerId, error);
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(SereniteaPotManager::stop);
        // Vanilla still saves/removes players after SERVER_STOPPING. Keep realm
        // routing alive through that final save, then drain private I/O at STOPPED.
        ServerLifecycleEvents.SERVER_STOPPED.register(PlayerStateManager::stop);
    }
}
