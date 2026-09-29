package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import org.edtp.sereniteapot.compat.worldthreader.WorldThreaderPotTicking;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.model.*;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldThreaderFailureGameTest {
    @GameTest(maxTicks = 300)
    public void propagatesOriginalTickFailureAfterAbortingBarrier(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("worldthreader")) { helper.succeed(); return; }
        var done = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        helper.getLevel().getServer().execute(() -> {
            try { Assertions.run(helper); }
            catch (Throwable error) { failure.set(error); }
            finally { done.set(true); }
        });
        helper.onEachTick(() -> {
            if (!done.get()) return;
            if (failure.get() != null) helper.fail("WorldThreader failure handling: " + failure.get());
            else helper.succeed();
        });
    }

    private static final class Assertions {
        static void run(GameTestHelper helper) throws Exception {
            var server = helper.getLevel().getServer();
            var target = new AtomicReference<ServerLevel>();
            var expected = new IllegalStateException("Injected pot tick failure");
            ServerTickEvents.START_LEVEL_TICK.register(level -> {
                if (target.compareAndSet(level, null)) throw expected;
            });
            var planField = SereniteaPotScheduler.class.getDeclaredField("currentPlan");
            planField.setAccessible(true);
            Object previousPlan = planField.get(null);
            try (var player = new ConnectedTestPlayer(server)) {
                PlayerStateManager.prepare(player.player()).join();
                var bundle = SereniteaPotManager.createStaging(player.id, 1, 1);
                try {
                    GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord("minecraft:overworld", 8, 80, 8, 0)), 0);
                    SereniteaPotTravelService.enter(player.player(), player.id);
                    var start = SereniteaPotScheduler.class.getDeclaredMethod("startServerTick", net.minecraft.server.MinecraftServer.class);
                    start.setAccessible(true);
                    start.invoke(null, server);
                    // Other GameTests can own pots concurrently. Restrict this
                    // deliberately single-lane fault probe to its own owner;
                    // otherwise a healthy owner's three-lane barrier runs first.
                    var ledgerField = SereniteaPotScheduler.class.getDeclaredField("ownerBudgets");
                    ledgerField.setAccessible(true);
                    var constructor = planField.getType().getDeclaredConstructors()[0];
                    constructor.setAccessible(true);
                    planField.set(null, constructor.newInstance(java.util.List.of(player.id), 1_000_000_000.0, ledgerField.get(null)));
                    target.set(bundle.get(SereniteaPotDimension.OVERWORLD));
                    Throwable actual = null;
                    try { WorldThreaderPotTicking.tickWorldPhase(server, SereniteaPotDimension.OVERWORLD, () -> true); }
                    catch (Throwable error) { actual = error; }
                    if (target.get() != null) throw new AssertionError("Fault injection did not reach world tick");
                    if (actual != expected) throw new AssertionError("Original failure was swallowed/replaced: " + actual);
                    if (WorldThreaderPotTicking.isGroupedPotTick()) throw new AssertionError("Tick context leaked");
                } finally {
                    target.set(null);
                    planField.set(null, previousPlan);
                    GameTestStorage.deleteAndReset(server, player.id);
                }
            }
        }
    }
}
