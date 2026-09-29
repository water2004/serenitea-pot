package org.edtp.sereniteapot.gametest;

import net.casual.arcade.dimensions.level.CustomLevel;
import net.casual.arcade.dimensions.utils.DimensionUtilsKt;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.model.*;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Inject failure after Arcade has registered a level, before addCustomLevel returns. */
public final class LevelRollbackGameTest {
    @GameTest(maxTicks = 300)
    public void stagingRollsBackEveryAcquiredLevel(GameTestHelper helper) { run(helper, false, false); }

    @GameTest(maxTicks = 300)
    public void reloadPreservesDataAndCanBeRetried(GameTestHelper helper) { run(helper, true, false); }

    @GameTest(maxTicks = 300)
    public void failedCleanupIsRetainedAndCannotBeReopened(GameTestHelper helper) { run(helper, true, true); }

    private static void run(GameTestHelper helper, boolean reload, boolean failCleanup) {
        var server = helper.getLevel().getServer();
        var done = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        server.execute(() -> {
            UUID owner = UUID.randomUUID();
            var armed = new AtomicBoolean();
            var original = new IllegalStateException("Injected registration failure");
            var cleanupError = new IllegalStateException("Injected unload event failure after native close");
            var closed = new java.util.ArrayList<CustomLevel>();
            var loadCalls = new int[]{0};
            ServerLevelEvents.LOAD.register((s, level) -> {
                var identity = SereniteaPotLevelKeys.identify(level.dimension());
                if (armed.get() && identity != null && identity.owner().equals(owner)) {
                    loadCalls[0]++;
                    if (identity.dimension() == SereniteaPotDimension.NETHER) throw original;
                }
            });
            ServerLevelEvents.UNLOAD.register((s, level) -> {
                var identity = SereniteaPotLevelKeys.identify(level.dimension());
                if (armed.get() && identity != null && identity.owner().equals(owner)) {
                    closed.add((CustomLevel) level);
                    if (failCleanup && identity.dimension() == SereniteaPotDimension.NETHER) throw cleanupError;
                }
            });
            try {
                if (reload) {
                    var bundle = SereniteaPotManager.createStaging(owner, 1, 1);
                    GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 0)), 0);
                    check(SereniteaPotLifecycleService.closeNow(server, owner) == SereniteaPotLifecycleService.Success.INSTANCE,
                        "Fixture unload failed");
                }
                armed.set(true);
                Throwable caught = null;
                try {
                    if (reload) SereniteaPotManager.load(owner);
                    else SereniteaPotManager.createStaging(owner, 1, 1);
                } catch (RuntimeException error) { caught = error; }
                check(caught == original, "Original construction failure was lost");
                check(closed.size() == 2, "Not all acquired levels were closed: " + closed.size());
                for (var dimension : SereniteaPotDimension.values()) {
                    var key = SereniteaPotLevelKeys.key(owner, 1, dimension);
                    check(server.getLevel(key) == null, "Failed registration leaked a live level");
                    if (!reload) check(!Files.exists(DimensionUtilsKt.getDimensionPath(server, key)), "Staging directory leaked");
                }
                if (failCleanup) {
                    check(java.util.Arrays.asList(original.getSuppressed()).contains(cleanupError), "Cleanup failure was swallowed");
                    check(SereniteaPotLifecycleService.isUnavailable(owner), "Uncertain closure was exposed for reuse");
                    int previousCalls = loadCalls[0];
                    try { SereniteaPotManager.load(owner); throw new AssertionError("Reopened uncertain storage"); }
                    catch (IllegalStateException expected) { check(expected.getCause() == cleanupError, "Lost closure cause"); }
                    check(loadCalls[0] == previousCalls, "Retry entered Arcade with uncertain resources");
                    check(SereniteaPotDeletionService.deleteAndReset(server, owner).join()
                        instanceof SereniteaPotDeletionService.Rejected, "Deleted files whose closure is uncertain");
                    check(SereniteaPotManager.record(owner).exists(), "Failed deletion changed catalog");
                } else {
                    armed.set(false);
                    var retried = reload ? SereniteaPotManager.load(owner) : SereniteaPotManager.createStaging(owner, 1, 1);
                    check(retried.levels().size() == 3, "Clean rollback could not be retried");
                    if (!reload) SereniteaPotLifecycleService.deleteEvacuated(retried);
                }
            } catch (Throwable error) { failure.set(error); }
            finally {
                armed.set(false);
                try {
                    // The injected error is after native close, so this fixture can
                    // safely release the deliberately conservative runtime guard.
                    var field = SereniteaPotManager.class.getDeclaredField("failedCloses");
                    field.setAccessible(true);
                    ((Map<?, ?>) field.get(null)).keySet().removeAll(closed);
                    GameTestStorage.deleteAndReset(server, owner);
                    SereniteaPotManager.catalog().getPlayers().remove(owner);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                done.set(true);
            }
        });
        helper.onEachTick(() -> {
            if (!done.get()) return;
            if (failure.get() != null) helper.fail("Level rollback: " + failure.get());
            else helper.succeed();
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
