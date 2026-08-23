package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.mixin.accessor.PlayerListAccessor;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies that Vanilla playerdata remains authoritative outside Serenitea Pots. */
public final class PublicPlayerDataGameTest {
    @GameTest(maxTicks = 300)
    @SuppressWarnings("removal")
    public void publicCreativeModeSurvivesRealmRestoreAndReconnect(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerPlayer initialPlayer = helper.makeMockServerPlayerInLevel();
        UUID owner = initialPlayer.getUUID();
        AtomicInteger phase = new AtomicInteger();
        AtomicBoolean serverTaskQueued = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        server.execute(() -> {
            try {
                initialPlayer.setGameMode(GameType.SURVIVAL);
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
                helper.fail("Public playerdata authority failed: " + thrown);
                return;
            }

            if (phase.compareAndSet(1, 2)) {
                SereniteaPotTravelService.enter(currentPlayer(server, owner), owner);
                return;
            }

            if (phase.get() == 2 && serverTaskQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        ServerPlayer player = currentPlayer(server, owner);
                        if (SereniteaPotLevelKeys.identify(player.level().dimension()) == null) {
                            serverTaskQueued.set(false);
                            return;
                        }
                        var result = SereniteaPotTravelService.leave(player);
                        if (result != SereniteaPotTravelService.Success.INSTANCE) {
                            throw new AssertionError("Could not leave the test pot: " + result);
                        }
                        SereniteaPotLifecycleService.cancelPendingClose(owner);
                        phase.set(3);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        serverTaskQueued.set(false);
                    }
                });
                return;
            }

            if (phase.compareAndSet(3, 4)) {
                try {
                    ServerPlayer player = currentPlayer(server, owner);
                    if (SereniteaPotLevelKeys.identify(player.level().dimension()) != null) {
                        throw new AssertionError("Player did not return to a public world");
                    }

                    // Reproduce the regression: the public mode changes after returning
                    // from a pot, so any earlier mod-owned public copy is now stale.
                    player.setGameMode(GameType.CREATIVE);
                    var playerDataStorage = ((PlayerListAccessor) server.getPlayerList())
                        .sereniteapot$getPlayerDataStorage();
                    playerDataStorage.save(player);

                    player.setGameMode(GameType.SURVIVAL);
                    PlayerStateManager.afterTeleport(
                        player,
                        new PlayerStateManager.StateSwitchPlan(owner, null)
                    );
                    if (!player.gameMode().isCreative()) {
                        throw new AssertionError("Realm restore ignored Vanilla's current public game mode");
                    }

                    // Mojang's reconnect path loads this same file into a fresh player.
                    var savedData = playerDataStorage
                        .load(player.nameAndId())
                        .orElseThrow();
                    ServerPlayer reconnected = new ServerPlayer(
                        server,
                        server.overworld(),
                        player.getGameProfile(),
                        ClientInformation.createDefault()
                    );
                    reconnected.load(TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        reconnected.registryAccess(),
                        savedData
                    ));
                    if (!reconnected.gameMode().isCreative()) {
                        throw new AssertionError("Vanilla reconnect lost the public creative mode");
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
                return;
            }

            if (phase.get() == 4 && serverTaskQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        var deletion = SereniteaPotDeletionService.deleteAndReset(server, owner);
                        if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                            throw new AssertionError("Could not clean up public-playerdata test pot: " + deletion);
                        }
                        server.getPlayerList().remove(currentPlayer(server, owner));
                        SereniteaPotManager.catalog().getPlayers().remove(owner);
                        SereniteaPotManager.saveCatalog();
                        phase.set(5);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        serverTaskQueued.set(false);
                    }
                });
            } else if (phase.get() == 5) {
                helper.succeed();
            }
        });
    }

    private static ServerPlayer currentPlayer(net.minecraft.server.MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) throw new AssertionError("The live test player is not registered");
        return player;
    }
}
