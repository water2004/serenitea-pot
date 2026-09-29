package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class DeletionCompletionGameTest {
    @GameTest(maxTicks = 300)
    public void deletionFinishesWithoutCallerCallback(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var owner = UUID.randomUUID();
        var future = new AtomicReference<CompletableFuture<SereniteaPotDeletionService.Result>>();
        server.execute(() -> {
            SereniteaPotManager.getOrCreateRecord(owner);
            future.set(SereniteaPotDeletionService.deleteAndReset(server, owner));
        });
        helper.onEachTick(() -> {
            var result = future.get();
            if (result == null || !result.isDone()) return;
            if (result.join() != SereniteaPotDeletionService.Success.INSTANCE
                    || SereniteaPotLifecycleService.isUnavailable(owner)) {
                helper.fail("Deletion did not finish/unlock without a caller completion callback");
                return;
            }
            server.execute(() -> SereniteaPotManager.catalog().getPlayers().remove(owner));
            helper.succeed();
        });
    }
}
