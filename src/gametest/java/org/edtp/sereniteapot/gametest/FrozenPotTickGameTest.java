package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises both Vanilla's serial tick path and Worldthreader's parallel path. */
public final class FrozenPotTickGameTest {
    @GameTest(maxTicks = 200)
    public void frozenPotStillParticipatesInServerTicks(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        AtomicReference<State> state = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean verificationQueued = new AtomicBoolean();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicInteger observedPublicTicks = new AtomicInteger();

        // GameTest callbacks execute as part of a level tick under Worldthreader.
        // Dynamic level lifecycle mutations must wait for the server-thread barrier.
        server.execute(() -> {
            try {
                UUID owner = UUID.randomUUID();
                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(owner, 1L, 1L);
                SereniteaPotManager.commitGeneration(
                    bundle,
                    Map.of(
                        SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord("minecraft:overworld", 0, 64, 0, 0)
                    ),
                    0
                );
                var record = SereniteaPotManager.record(owner);
                record.setFrozen(true);
                SereniteaPotManager.saveCatalog();

                Entity marker = EntityTypes.ARMOR_STAND.create(
                    bundle.get(SereniteaPotDimension.OVERWORLD),
                    EntitySpawnReason.COMMAND
                );
                if (marker == null) {
                    throw new IllegalStateException("Could not create frozen-pot marker entity");
                }
                marker.setPos(0.5, 64.0, 0.5);
                bundle.get(SereniteaPotDimension.OVERWORLD).addFreshEntity(marker);
                state.set(new State(owner, marker));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        helper.onEachTick(() -> {
            Throwable throwable = failure.get();
            if (throwable != null) {
                helper.fail("Frozen-pot setup or verification failed: " + throwable);
                return;
            }
            State current = state.get();
            if (current == null) {
                return;
            }
            if (observedPublicTicks.incrementAndGet() >= 25
                && verificationQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        var performance = SereniteaPotScheduler.snapshot(current.owner);
                        if (performance == null || performance.skippedTicks() == 0) {
                            throw new AssertionError("Frozen pot tick hook did not report skipped levels");
                        }
                        if (current.marker.tickCount != 0) {
                            throw new AssertionError(
                                "Frozen pot entity ticked " + current.marker.tickCount + " times"
                            );
                        }
                        var deletion = SereniteaPotDeletionService.deleteAndReset(server, current.owner);
                        if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                            throw new IllegalStateException("Could not clean up frozen test pot: " + deletion);
                        }
                        SereniteaPotManager.catalog().getPlayers().remove(current.owner);
                        SereniteaPotManager.saveCatalog();
                        complete.set(true);
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
            }
            if (complete.get()) {
                helper.succeed();
            }
        });
    }

    private record State(UUID owner, Entity marker) {
    }
}
