package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import no2.worldthreader.common.mixin_support.interfaces.MinecraftServerExtended;
import no2.worldthreader.common.thread.WorldThreadingManager;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs in the actual WT world workers, not three executor threads imitating its protocol. */
public final class WorldThreaderBarrierGameTest {
    @GameTest(maxTicks = 400)
    public void endPortalItemReturnsWhileOtherFamiliesWait(GameTestHelper helper) {
        run(helper, true);
    }

    @GameTest(maxTicks = 400)
    public void ownerBarrierReleasesHeldExclusiveAccess(GameTestHelper helper) {
        run(helper, false);
    }

    private static void run(GameTestHelper helper, boolean portal) {
        if (!FabricLoader.getInstance().isModLoaded("worldthreader")) {
            helper.succeed();
            return;
        }
        // Keep WT-only linkage behind the installed-mod check.
        Assertions.run(helper, portal);
    }

    private static final class Assertions {
        static void run(GameTestHelper helper, boolean portal) {
            var server = helper.getLevel().getServer();
            var state = new AtomicReference<Fixture>();
            var failure = new AtomicReference<Throwable>();
            var complete = new AtomicBoolean();

            ServerTickEvents.END_LEVEL_TICK.register(level -> {
                Fixture fixture = state.get();
                if (fixture == null) return;
                if (level == fixture.bundle.get(SereniteaPotDimension.OVERWORLD)
                    || level == fixture.bundle.get(SereniteaPotDimension.NETHER)) {
                    fixture.otherFamiliesFinished.countDown();
                }
                // Also exercise a requester retaining exclusive access until its
                // owner-completion barrier; it must release it before parking.
                if (!portal && level == fixture.bundle.get(SereniteaPotDimension.END)) {
                    exercise(fixture, failure, false);
                }
            });
            ServerTickEvents.START_LEVEL_TICK.register(level -> {
                Fixture fixture = state.get();
                if (portal && fixture != null && level == fixture.bundle.get(SereniteaPotDimension.END)) {
                    exercise(fixture, failure, true);
                }
            });
            ServerTickEvents.END_SERVER_TICK.register(tickedServer -> {
                Fixture fixture = state.get();
                if (fixture == null || !fixture.triggered.get()) return;
                // Reaching END_SERVER_TICK proves all native worker phases finished;
                // the watchdog must not count the later destination-chunk visibility wait.
                fixture.watchdog.shutdownNow();
                // The destination's entity section may only become visible on its
                // next chunk-source tick (that family tick preceded the arrival).
                if (portal && failure.get() == null && fixture.requestReturned.get()
                    && fixture.bundle.get(SereniteaPotDimension.OVERWORLD).getEntity(fixture.item.getUUID()) == null
                    && ++fixture.completedServerTicks < 20) return;
                try {
                    if (!fixture.requestReturned.get()) throw new AssertionError("Cross-world request did not return");
                    if (portal && !(fixture.bundle.get(SereniteaPotDimension.OVERWORLD)
                        .getEntity(fixture.item.getUUID()) instanceof ItemEntity)) {
                        throw new AssertionError("WT did not deliver the End portal item to its paired Overworld: removed="
                            + fixture.item.getRemovalReason() + ", portal=" + fixture.item.portalProcess
                            + ", level=" + fixture.item.level().dimension()
                            + ", endEntity=" + fixture.bundle.get(SereniteaPotDimension.END).getEntity(fixture.item.getUUID()));
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    state.set(null);
                    try (ConnectedTestPlayer player = fixture.player) {
                        if (GameTestStorage.deleteAndReset(server, player.id)
                            != SereniteaPotDeletionService.Success.INSTANCE) {
                            throw new AssertionError("Could not delete barrier fixture");
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                    complete.set(true);
                }
            });

            server.execute(() -> {
                ConnectedTestPlayer player = null;
                try {
                    player = new ConnectedTestPlayer(server);
                    PlayerStateManager.prepare(player.player()).join();
                    var bundle = SereniteaPotManager.createStaging(player.id, 1, 1);
                    GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord("minecraft:overworld", 8, 80, 8, 0)), 0);
                    SereniteaPotManager.record(player.id).setBudgetMillisPerTick(1000);
                    if (SereniteaPotTravelService.enter(player.player(), player.id)
                        != SereniteaPotTravelService.Success.INSTANCE) {
                        throw new AssertionError("Could not enter barrier fixture");
                    }
                    var end = bundle.get(SereniteaPotDimension.END);
                    var item = new ItemEntity(end, 8.5, 80, 8.5, new ItemStack(Items.STONE));
                    end.addFreshEntity(item);
                    state.set(new Fixture(player, bundle, item));
                } catch (Throwable error) {
                    failure.set(error);
                    if (player != null) {
                        GameTestStorage.deleteAndReset(server, player.id);
                        player.close();
                    }
                    complete.set(true);
                }
            });
            helper.onEachTick(() -> {
                if (!complete.get()) return;
                if (failure.get() != null) helper.fail("WT cooperative owner barrier: " + failure.get());
                else helper.succeed();
            });
        }

        private static void exercise(Fixture fixture, AtomicReference<Throwable> failure, boolean portal) {
            if (!fixture.triggered.compareAndSet(false, true)) return;
            // Only a test watchdog: the old implementation deadlocks the entire
            // server and cannot reach GameTest's tick timeout. Abort its pot barrier
            // to release the cycle and report a failure instead of hanging CI.
            fixture.watchdog.schedule(() -> {
                failure.compareAndSet(null, new AssertionError("World access/owner barrier deadlocked"));
                SereniteaPotScheduler.abortThreadedTick();
            }, 5, TimeUnit.SECONDS);
            try {
                if (!fixture.otherFamiliesFinished.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("Other WT family workers did not finish the fixture");
                }
                ServerLevel end = fixture.bundle.get(SereniteaPotDimension.END);
                var manager = ((MinecraftServerExtended) end.getServer()).worldthreader$getThreadingManager();
                if (manager == null || !manager.isWorldThreadOf(end) || !manager.isMultiThreadedPhase()) {
                    throw new AssertionError("Regression probe did not run on a real WT End worker");
                }
                if (portal) {
                    // Use Vanilla's contact API and ItemEntity.tick -> handlePortal,
                    // the exact dropped-item path captured in mc-deadlock.tar.gz.
                    fixture.item.setAsInsidePortal((EndPortalBlock) Blocks.END_PORTAL, fixture.item.blockPosition());
                    fixture.item.tick();
                } else {
                    manager.waitForExclusiveWorldAccess(false);
                    if (!WorldThreadingManager.hasExclusiveScoreboardAccess(end.getServer())) {
                        throw new AssertionError("Requester did not acquire exclusive access");
                    }
                }
                fixture.requestReturned.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                SereniteaPotScheduler.abortThreadedTick();
            }
        }
    }

    private static final class Fixture {
        final ConnectedTestPlayer player;
        final SereniteaPotBundle bundle;
        final ItemEntity item;
        final CountDownLatch otherFamiliesFinished = new CountDownLatch(2);
        final AtomicBoolean triggered = new AtomicBoolean();
        final AtomicBoolean requestReturned = new AtomicBoolean();
        int completedServerTicks;
        final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("pot-barrier-test-watchdog").factory());

        Fixture(ConnectedTestPlayer player, SereniteaPotBundle bundle, ItemEntity item) {
            this.player = player;
            this.bundle = bundle;
            this.item = item;
        }
    }
}
