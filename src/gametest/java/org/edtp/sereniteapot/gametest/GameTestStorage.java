package org.edtp.sereniteapot.gametest;

import net.minecraft.server.MinecraftServer;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
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
        SereniteaPotDeletionService.Result result = SereniteaPotDeletionService.deleteAndReset(server, owner);
        if (result instanceof SereniteaPotDeletionService.Pending pending) {
            pending.future().join();
            return pending.finish();
        }
        return result;
    }
}
