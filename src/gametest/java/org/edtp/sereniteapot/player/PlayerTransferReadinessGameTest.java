package org.edtp.sereniteapot.player;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled I/O completion exercises the same continuation used by travel commands. */
public final class PlayerTransferReadinessGameTest {
    @GameTest(maxTicks = 300)
    @SuppressWarnings("removal")
    public void resumesOnceAndCancelsStaleRequests(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        var owner = player.getUUID();
        var gate = new CompletableFuture<Void>();
        var permissionGate = new CompletableFuture<Void>();
        var disconnectGate = new CompletableFuture<Void>();
        var phase = new int[]{0};
        var calls = new int[]{0};
        var queued = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        helper.onEachTick(() -> {
            if (failure.get() != null) { helper.fail("Deferred transfer failed: " + failure.get()); return; }
            if (phase[0] == 5) { helper.succeed(); return; }
            if (!queued.compareAndSet(false, true)) return;
            server.execute(() -> {
                try {
                    switch (phase[0]) {
                        case 0 -> {
                            if (!PlayerStateManager.prepare(player).isDone()) return;
                            var bundle = SereniteaPotManager.createStaging(owner, 1, 1);
                            var commit = SereniteaPotManager.beginCommitGeneration(bundle,
                                Map.of(SereniteaPotDimension.OVERWORLD,
                                    new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 0)), 0);
                            // Fixture persistence only; the behavior under test below never waits.
                            commit.persistedFuture().join();
                            SereniteaPotManager.finishCommitGeneration(commit);
                            PlayerStateManager.whenReady(player, gate, current -> {
                                calls[0]++;
                                if (SereniteaPotTravelService.enter(current, owner) != SereniteaPotTravelService.Success.INSTANCE) {
                                    failure.set(new AssertionError("Deferred entry was rejected"));
                                }
                            });
                            if (calls[0] != 0) throw new AssertionError("Transfer ran before data was ready");
                            phase[0] = 1;
                        }
                        case 1 -> {
                            if (calls[0] != 0 || SereniteaPotLevelKeys.identify(player.level().dimension()) != null) {
                                throw new AssertionError("Waiting changed the player's realm");
                            }
                            gate.complete(null); // no second command/request
                            phase[0] = 2;
                        }
                        case 2 -> {
                            var current = server.getPlayerList().getPlayer(owner);
                            if (calls[0] == 0) return;
                            if (calls[0] != 1 || SereniteaPotLevelKeys.identify(current.level().dimension()) == null) {
                                throw new AssertionError("Transfer did not resume exactly once");
                            }
                            phase[0] = 6;
                            PlayerStateManager.whenReady(current, live -> {
                                if (SereniteaPotTravelService.leave(live) != SereniteaPotTravelService.Success.INSTANCE) {
                                    failure.set(new AssertionError("Deferred exit was rejected"));
                                }
                                phase[0] = 3;
                            });
                        }
                        case 3 -> {
                            var current = server.getPlayerList().getPlayer(owner);
                            PlayerStateManager.whenReady(current, permissionGate, live -> {
                                calls[0]++;
                                if (!(SereniteaPotTravelService.enter(live, owner) instanceof SereniteaPotTravelService.Rejected)) {
                                    failure.set(new AssertionError("Waiting request bypassed newly disabled pot"));
                                }
                            });
                            SereniteaPotManager.record(owner).setEnabled(false);
                            permissionGate.complete(null);
                            phase[0] = 7;
                        }
                        case 7 -> {
                            if (calls[0] < 2) return;
                            var current = server.getPlayerList().getPlayer(owner);
                            if (SereniteaPotLevelKeys.identify(current.level().dimension()) != null) {
                                throw new AssertionError("Disabled pot accepted deferred entry");
                            }
                            SereniteaPotManager.record(owner).setEnabled(true);
                            PlayerStateManager.whenReady(current, disconnectGate, ignored -> calls[0]++);
                            server.getPlayerList().remove(current);
                            disconnectGate.complete(null);
                            phase[0] = 4;
                        }
                        case 4 -> {
                            if (calls[0] != 2) throw new AssertionError("Disconnected player request resumed");
                            var deletion = SereniteaPotDeletionService.deleteAndReset(server, owner);
                            if (deletion instanceof SereniteaPotDeletionService.Pending pending) {
                                pending.future().join(); // test fixture cleanup only
                                deletion = pending.finish();
                            }
                            if (deletion != SereniteaPotDeletionService.Success.INSTANCE) throw new AssertionError("Cleanup failed");
                            SereniteaPotManager.catalog().getPlayers().remove(owner);
                            phase[0] = 5;
                        }
                        case 6 -> { } // asynchronous exit continuation
                        default -> throw new AssertionError("Unexpected phase");
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    queued.set(false);
                }
            });
        });
    }
}
