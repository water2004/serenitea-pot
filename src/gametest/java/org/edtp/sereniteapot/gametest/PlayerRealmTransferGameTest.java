package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerRealmTransferGameTest {
    @GameTest(maxTicks = 300)
    @SuppressWarnings("removal")
    public void preservesFlightWhenEnteringAgain(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer initialPlayer = helper.makeMockServerPlayerInLevel();
        UUID owner = initialPlayer.getUUID();
        AtomicInteger phase = new AtomicInteger();
        AtomicBoolean serverTaskQueued = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Dynamic levels are created on the main server thread. The two entries
        // below start on the public world's tick thread, so Worldthreader exercises
        // its real cross-thread ServerPlayer replacement path when installed.
        server.execute(() -> {
            try {
                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(owner, 1L, 1L);
                SereniteaPotManager.commitGeneration(
                    bundle,
                    Map.of(
                        SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord(
                            helper.getLevel().dimension().identifier().toString(),
                            initialPlayer.getBlockX(),
                            initialPlayer.getBlockY(),
                            initialPlayer.getBlockZ(),
                            0
                        )
                    ),
                    0
                );
                phase.set(1);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        helper.onEachTick(() -> {
            Throwable thrown = failure.get();
            if (thrown != null) {
                helper.fail("Player realm transfer failed: " + thrown);
                return;
            }

            if (phase.compareAndSet(1, 2)) {
                SereniteaPotTravelService.enter(currentPlayer(server, owner), owner);
                return;
            }

            if (phase.get() == 2 && serverTaskQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        ServerPlayer current = currentPlayer(server, owner);
                        if (SereniteaPotLevelKeys.identify(current.level().dimension()) == null) {
                            serverTaskQueued.set(false);
                            return;
                        }

                        current.getAbilities().flying = true;
                        current.onUpdateAbilities();
                        SereniteaPotTravelService.Result result = SereniteaPotTravelService.leave(current);
                        if (result != SereniteaPotTravelService.Success.INSTANCE) {
                            throw new AssertionError("Could not leave the test pot: " + result);
                        }
                        // Keep the bundle loaded so the test isolates player-state transfer.
                        SereniteaPotLifecycleService.cancelPendingClose(owner);
                        serverTaskQueued.set(false);
                        phase.set(3);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                return;
            }

            if (phase.compareAndSet(3, 4)) {
                SereniteaPotTravelService.enter(currentPlayer(server, owner), owner);
                return;
            }

            if (phase.get() == 4 && serverTaskQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        ServerPlayer current = currentPlayer(server, owner);
                        if (SereniteaPotLevelKeys.identify(current.level().dimension()) == null) {
                            serverTaskQueued.set(false);
                            return;
                        }
                        if (!current.gameMode().isCreative()
                            || !current.getAbilities().mayfly
                            || !current.getAbilities().invulnerable
                            || !current.getAbilities().flying) {
                            throw new AssertionError("The authoritative in-pot player lost creative flight state");
                        }

                        SereniteaPotDeletionService.Result deletion =
                            SereniteaPotDeletionService.deleteAndReset(server, owner);
                        if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                            throw new AssertionError("Could not clean up transfer test pot: " + deletion);
                        }
                        server.getPlayerList().remove(currentPlayer(server, owner));
                        SereniteaPotManager.catalog().getPlayers().remove(owner);
                        SereniteaPotManager.saveCatalog();
                        phase.set(5);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
            } else if (phase.get() == 5) {
                helper.succeed();
            }
        });
    }

    private static ServerPlayer currentPlayer(net.minecraft.server.MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            throw new AssertionError("The live test player is not registered");
        }
        return player;
    }
}
