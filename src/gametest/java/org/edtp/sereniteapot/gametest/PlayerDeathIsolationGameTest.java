package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelData;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies that death cannot move pot inventory or public inventory across realms. */
public final class PlayerDeathIsolationGameTest {
    @GameTest(maxTicks = 300)
    @SuppressWarnings("removal")
    public void deathStaysInsidePotAndPreservesPublicInventory(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var initialPlayer = helper.makeMockServerPlayerInLevel();
        UUID owner = initialPlayer.getUUID();
        AtomicInteger phase = new AtomicInteger();
        AtomicBoolean serverTaskQueued = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        server.execute(() -> {
            try {
                initialPlayer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
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
                helper.fail("Player death isolation failed: " + thrown);
                return;
            }

            if (phase.compareAndSet(1, 2)) {
                SereniteaPotTravelService.enter(currentPlayer(server, owner), owner);
                return;
            }

            if (phase.get() == 2) {
                try {
                    var player = currentPlayer(server, owner);
                    SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(
                        player.level().dimension()
                    );
                    if (identity == null) return;
                    if (player.getInventory().countItem(Items.DIAMOND) != 0) {
                        throw new AssertionError("Public inventory remained live inside the pot");
                    }

                    player.getInventory().setItem(0, new ItemStack(Items.DIRT, 2));
                    player.setRespawnPosition(
                        new ServerPlayer.RespawnConfig(
                            LevelData.RespawnData.of(
                                player.level().dimension(),
                                player.blockPosition(),
                                player.getYRot(),
                                player.getXRot()
                            ),
                            true
                        ),
                        false
                    );
                    player.setHealth(0.0F);
                    player.die(player.level().damageSources().genericKill());

                    boolean leakedPublicItem = player.level()
                        .getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8.0))
                        .stream()
                        .anyMatch(item -> item.getItem().is(Items.DIAMOND));
                    if (leakedPublicItem) {
                        throw new AssertionError("Public inventory dropped inside the pot");
                    }

                    // Exercise the real network handler: WorldThreader requires player
                    // replacement to originate on the owning dimension-family thread.
                    player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                        ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                    ));
                    var respawned = currentPlayer(server, owner);
                    SereniteaPotLevelKeys.Identity respawnIdentity = SereniteaPotLevelKeys.identify(
                        respawned.level().dimension()
                    );
                    if (respawnIdentity == null || !respawnIdentity.owner().equals(owner)) {
                        throw new AssertionError("Pot death respawn escaped to a public world");
                    }
                    phase.set(3);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
                return;
            }

            if (phase.get() == 3) {
                try {
                    var result = SereniteaPotTravelService.leave(currentPlayer(server, owner));
                    if (result != SereniteaPotTravelService.Success.INSTANCE) {
                        throw new AssertionError("Could not leave after respawning in the pot: " + result);
                    }
                    phase.set(4);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
                return;
            }

            if (phase.get() == 4) {
                try {
                    var player = currentPlayer(server, owner);
                    if (SereniteaPotLevelKeys.identify(player.level().dimension()) != null) return;
                    if (player.getInventory().countItem(Items.DIAMOND) != 3) {
                        throw new AssertionError("Public inventory changed after a pot death");
                    }
                    if (player.getInventory().countItem(Items.DIRT) != 0) {
                        throw new AssertionError("Pot inventory leaked into the public realm");
                    }
                    ServerPlayer.RespawnConfig publicRespawn = player.getRespawnConfig();
                    if (publicRespawn != null && SereniteaPotLevelKeys.identify(
                        publicRespawn.respawnData().dimension()
                    ) != null) {
                        throw new AssertionError("The pot respawn point leaked into public playerdata");
                    }

                    player.setHealth(0.0F);
                    player.die(player.level().damageSources().genericKill());
                    player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                        ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
                    ));
                    if (SereniteaPotLevelKeys.identify(currentPlayer(server, owner).level().dimension()) != null) {
                        throw new AssertionError("Public-world death respawned inside a pot");
                    }
                    phase.set(5);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
                return;
            }

            if (phase.get() == 5 && serverTaskQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    try {
                        var player = currentPlayer(server, owner);
                        var deletion = SereniteaPotDeletionService.deleteAndReset(server, owner);
                        if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                            throw new AssertionError("Could not clean up death-isolation test pot: " + deletion);
                        }
                        server.getPlayerList().remove(player);
                        SereniteaPotManager.catalog().getPlayers().remove(owner);
                        SereniteaPotManager.saveCatalog();
                        phase.set(6);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        serverTaskQueued.set(false);
                    }
                });
            } else if (phase.get() == 6) {
                helper.succeed();
            }
        });
    }

    private static ServerPlayer currentPlayer(MinecraftServer server, UUID playerId) {
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null) throw new AssertionError("The live test player is not registered");
        return player;
    }
}
