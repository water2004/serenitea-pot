package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hold a real return-data read across ticks; the original operation must resume itself. */
public final class MaintenanceReadinessGameTest {
    @GameTest(maxTicks = 300)
    public void deletionWaitsForEvacuation(GameTestHelper helper) { run(helper, false); }

    @GameTest(maxTicks = 300)
    public void trimWaitsForEvacuation(GameTestHelper helper) { run(helper, true); }

    private static void run(GameTestHelper helper, boolean trim) {
        var server = helper.getLevel().getServer();
        var queued = new AtomicBoolean();
        helper.onEachTick(new Runnable() {
            ConnectedTestPlayer connection;
            CompletableFuture<CompoundTag> gate;
            CompoundTag publicData;
            CompletableFuture<?> operation;
            int ticks;
            boolean finished;

            @Override
            public void run() {
                if (finished || !queued.compareAndSet(false, true)) return;
                server.execute(() -> {
                    try {
                        if (connection == null) {
                            connection = new ConnectedTestPlayer(server);
                            var player = connection.player();
                            PlayerStateManager.prepare(player).join(); // fixture only
                            var bundle = SereniteaPotManager.createStaging(connection.id, 1, 1);
                            GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                                new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 1)), 1);
                            check(SereniteaPotTravelService.enter(player, connection.id) == SereniteaPotTravelService.Success.INSTANCE,
                                "Fixture entry failed");
                            PlayerStateManager.prepareReturn(connection.player()).join();
                            var reads = pendingReads();
                            publicData = reads.get(connection.id).join();
                            gate = new CompletableFuture<>();
                            reads.put(connection.id, gate);
                            operation = trim
                                ? SereniteaPotCreationService.changeMaximum(server, connection.id, 0, null)
                                : SereniteaPotDeletionService.deleteAndReset(server, connection.id);
                        } else if (++ticks < 5) {
                            check(!operation.isDone(), "Pending I/O was treated as completion/failure");
                            check(SereniteaPotLifecycleService.isUnavailable(connection.id), "Admission lock was released");
                            check(SereniteaPotManager.loaded(connection.id) != null, "Source levels unloaded before evacuation");
                            check(SereniteaPotLevelKeys.identify(connection.player().level().dimension()) != null,
                                "Player moved before data became available");
                        } else if (!gate.isDone()) {
                            gate.complete(publicData);
                        } else if (operation.isDone()) {
                            Object result = operation.join();
                            check(trim ? result instanceof SereniteaPotCreationService.MaximumTrimStarted
                                : result == SereniteaPotDeletionService.Success.INSTANCE, "Original operation failed: " + result);
                            check(SereniteaPotLevelKeys.identify(connection.player().level().dimension()) == null,
                                "Player was not evacuated");
                            if (trim) {
                                check(SereniteaPotManager.loaded(connection.id) != null, "Copy source closed during maintenance");
                                check(SereniteaPotCreationService.cancel(connection.id), "Cannot cancel fixture copy");
                            }
                            cleanup();
                            finished = true;
                            helper.succeed();
                        }
                    } catch (Throwable failure) {
                        if (gate != null && !gate.isDone()) gate.complete(publicData);
                        cleanup();
                        finished = true;
                        helper.fail("Maintenance continuation: " + failure);
                    } finally {
                        queued.set(false);
                    }
                });
            }

            private void cleanup() {
                if (connection == null) return;
                connection.close();
                SereniteaPotCreationService.cancel(connection.id);
                SereniteaPotLifecycleService.endMaintenance(connection.id);
                GameTestStorage.deleteAndReset(server, connection.id);
                SereniteaPotManager.catalog().getPlayers().remove(connection.id);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, CompletableFuture<CompoundTag>> pendingReads() throws ReflectiveOperationException {
        var field = PlayerStateManager.class.getDeclaredField("pendingPublicReturns");
        field.setAccessible(true);
        return (Map<UUID, CompletableFuture<CompoundTag>>) field.get(null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
