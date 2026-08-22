package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies that Arcade exposes one pot-local difficulty to all three dimensions. */
public final class PotDifficultyGameTest {
    @GameTest(maxTicks = 100)
    public void appliesDifficultyWithoutChangingThePublicWorld(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        Difficulty publicDifficulty = helper.getLevel().getDifficulty();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean complete = new AtomicBoolean();

        server.execute(() -> {
            UUID owner = UUID.randomUUID();
            try {
                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(owner, 1L, 1L);
                SereniteaPotManager.commitGeneration(
                    bundle,
                    Map.of(
                        SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord("minecraft:overworld", 0, 64, 0, 0)
                    ),
                    0
                );

                SereniteaPotManager.setDifficulty(owner, Difficulty.PEACEFUL);
                for (var level : bundle.levels().values()) {
                    if (level.getDifficulty() != Difficulty.PEACEFUL) {
                        throw new AssertionError(level.dimension().identifier() + " did not become peaceful");
                    }
                    if (level.getCurrentDifficultyAt(level.getRespawnData().pos()).getDifficulty()
                            != Difficulty.PEACEFUL) {
                        throw new AssertionError(
                            level.dimension().identifier() + " still computes non-peaceful local difficulty"
                        );
                    }
                }
                if (helper.getLevel().getDifficulty() != publicDifficulty) {
                    throw new AssertionError("Changing pot difficulty modified the public world");
                }

                var deletion = SereniteaPotDeletionService.deleteAndReset(server, owner);
                if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                    throw new IllegalStateException("Could not clean up difficulty test pot: " + deletion);
                }
                SereniteaPotManager.catalog().getPlayers().remove(owner);
                SereniteaPotManager.saveCatalog();
                complete.set(true);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        helper.onEachTick(() -> {
            Throwable throwable = failure.get();
            if (throwable != null) {
                helper.fail("Pot difficulty test failed: " + throwable);
            } else if (complete.get()) {
                helper.succeed();
            }
        });
    }
}
