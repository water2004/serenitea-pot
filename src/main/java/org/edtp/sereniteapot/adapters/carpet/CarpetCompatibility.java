package org.edtp.sereniteapot.adapters.carpet;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.level.SereniteaPotAccessPolicy;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.function.BiConsumer;

/** Carpet births are not realm transfers; prepare them before registering a live player. */
public final class CarpetCompatibility {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("carpet");

    private CarpetCompatibility() {}

    public static boolean isFake(ServerPlayer player) {
        return LOADED && player instanceof EntityPlayerMPFake;
    }

    public static void removeFake(ServerPlayer player) {
        // Carpet's kill override schedules its normal logout; disconnect(Component)
        // deliberately ignores ordinary kick reasons on fake connections.
        ((EntityPlayerMPFake) player).kill(player.level());
    }

    public static void spawnWhenReady(MinecraftServer server, ServerLevel level, GameProfile profile,
                                      BiConsumer<GameProfile, Throwable> spawn) {
        var identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (!canSpawn(server, level) || server.getPlayerList().getPlayer(profile.id()) != null) {
            rejectSpawn(server, profile, spawn, new IllegalStateException("Pot is no longer available for a fake-player birth"));
            return;
        }
        PlayerStateManager.preparePotSpawn(server, profile.id(), identity.owner()).whenCompleteAsync((state, error) -> {
            if (error != null || !canSpawn(server, level) || server.getPlayerList().getPlayer(profile.id()) != null) {
                rejectSpawn(server, profile, spawn, error != null ? error
                    : new IllegalStateException("Pot closed while preparing a fake-player birth"));
                return;
            }
            spawn.accept(profile, null);
            ServerPlayer player = server.getPlayerList().getPlayer(profile.id());
            if (player != null && player.level() == level && isFake(player)) {
                // Apply after Carpet's final health/mode/flight overrides. The spawn
                // command still owns position and rotation, not the saved location.
                PlayerStateManager.initializePotSpawn(player, state);
            } else {
                PlayerStateManager.discardPreparedSpawn(server, profile.id());
            }
        }, server).exceptionally(error -> {
            SereniteaPotMod.LOGGER.error("Failed to complete Carpet birth for {}", profile.id(), error);
            return null;
        });
    }

    private static boolean canSpawn(MinecraftServer server, ServerLevel level) {
        var identity = SereniteaPotLevelKeys.identify(level.dimension());
        if (identity == null || server.getLevel(level.dimension()) != level) return false;
        var record = SereniteaPotManager.record(identity.owner());
        return record != null && record.isEnabled()
            && record.getActiveGeneration() == identity.generation()
            && !SereniteaPotLifecycleService.isUnavailable(identity.owner())
            && SereniteaPotAccessPolicy.isRealOwnerInside(server, identity.owner());
    }

    private static void rejectSpawn(MinecraftServer server, GameProfile profile,
                                    BiConsumer<GameProfile, Throwable> spawn, Throwable error) {
        // Carpet's completion callback removes its pending name before inspecting
        // errors. Reuse that path rather than maintaining a second spawning registry.
        spawn.accept(profile, error);
        PlayerStateManager.discardPreparedSpawn(server, profile.id());
        SereniteaPotMod.LOGGER.warn("Cancelled Carpet birth for {}: {}", profile.id(), error.toString());
    }
}
