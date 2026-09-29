package org.edtp.sereniteapot.gametest;

import net.minecraft.server.MinecraftServer;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.UUID;

/** Synchronous setup/cleanup adapters for server-thread GameTest fixtures only. */
final class GameTestStorage {
    private GameTestStorage() {
    }

    static SereniteaPotBundle commitGeneration(
        SereniteaPotBundle bundle,
        Map<SereniteaPotDimension, SereniteaPotSlotRecord> slots,
        int maximumRadiusChunks
    ) {
        SereniteaPotManager.GenerationCommit commit = SereniteaPotManager.beginCommitGeneration(
            bundle, slots, maximumRadiusChunks);
        commit.persistedFuture().join();
        return SereniteaPotManager.finishCommitGeneration(commit);
    }

    static SereniteaPotDeletionService.Result deleteAndReset(MinecraftServer server, UUID owner) {
        // Fixture teardown may run immediately after entry. Only drain detached I/O;
        // production evacuation waits across ticks instead of blocking here.
        for (var player : server.getPlayerList().getPlayers()) {
            var identity = SereniteaPotLevelKeys.identify(player.level().dimension());
            if (identity != null && identity.owner().equals(owner)) {
                PlayerStateManager.prepareReturn(player).join();
            }
        }
        var result = SereniteaPotDeletionService.deleteAndReset(server, owner);
        SereniteaPotDeletionService.awaitPending(server);
        return result.join();
    }
}
