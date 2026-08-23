package org.edtp.sereniteapot.gametest;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.mixin.accessor.PlayerListAccessor;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerDataIsolationGameTest {
    @GameTest(maxTicks = 200)
    public void vanillaSaveRemainsPublicWhilePlayerRemainsInPot(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean complete = new AtomicBoolean();

        // Custom level lifecycle changes belong to the server thread, including
        // when the surrounding GameTest level is running on Worldthreader.
        server.execute(() -> {
            UUID owner = UUID.randomUUID();
            ServerPlayer player = null;
            try {
                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(owner, 1L, 1L);
                SereniteaPotManager.commitGeneration(
                    bundle,
                    Map.of(
                        SereniteaPotDimension.OVERWORLD,
                        new SereniteaPotSlotRecord("minecraft:overworld", 120, 80, -40, 0)
                    ),
                    0
                );

                player = new ServerPlayer(
                    server,
                    server.overworld(),
                    new GameProfile(owner, "save-isolation-test"),
                    ClientInformation.createDefault()
                );
                player.snapTo(120.5, 80.0, -39.5, 30.0F, -5.0F);
                int publicGameType = player.gameMode().getId();
                var playerDataStorage = ((PlayerListAccessor) server.getPlayerList())
                    .sereniteapot$getPlayerDataStorage();
                playerDataStorage.save(player);

                var potLevel = bundle.get(SereniteaPotDimension.OVERWORLD);
                player.setServerLevel(potLevel);
                player.snapTo(0.5, 70.0, 0.5, 90.0F, 10.0F);
                GameType.CREATIVE.updatePlayerAbilities(player.getAbilities());

                // The mixin cancels this ordinary playerdata save and updates only
                // the existing isolated pot snapshot.
                playerDataStorage.save(player);
                CompoundTag savedData = playerDataStorage
                    .load(new NameAndId(player.getGameProfile()))
                    .orElseThrow();
                Vec3 savedPosition = savedData.read("Pos", Vec3.CODEC).orElseThrow();

                if (!"minecraft:overworld".equals(savedData.getStringOr("Dimension", ""))) {
                    throw new AssertionError("Vanilla playerdata retained the pot dimension");
                }
                if (!savedPosition.equals(new Vec3(120.5, 80.0, -39.5))) {
                    throw new AssertionError("Vanilla playerdata retained the pot-local position: " + savedPosition);
                }
                if (savedData.getIntOr("playerGameType", -1) != publicGameType) {
                    throw new AssertionError("Vanilla playerdata retained the pot game mode");
                }
                if (player.level() != potLevel || !player.position().equals(new Vec3(0.5, 70.0, 0.5))) {
                    throw new AssertionError("Cancelling playerdata save mutated the live in-pot player");
                }
                var isolatedLocation = PlayerStateManager.savedPotLocation(player, owner);
                if (isolatedLocation == null
                    || isolatedLocation.x() != 0.5
                    || isolatedLocation.y() != 70.0
                    || isolatedLocation.z() != 0.5) {
                    throw new AssertionError("The cancelled save did not update isolated pot state");
                }

                player.setServerLevel(server.overworld());
                var deletion = SereniteaPotDeletionService.deleteAndReset(server, owner);
                if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                    throw new IllegalStateException("Could not clean up playerdata test pot: " + deletion);
                }
                SereniteaPotManager.catalog().getPlayers().remove(owner);
                SereniteaPotManager.saveCatalog();
                complete.set(true);
            } catch (Throwable throwable) {
                if (player != null) {
                    player.setServerLevel(server.overworld());
                }
                failure.set(throwable);
            }
        });

        helper.onEachTick(() -> {
            Throwable throwable = failure.get();
            if (throwable != null) {
                helper.fail("Playerdata isolation failed: " + throwable);
            } else if (complete.get()) {
                helper.succeed();
            }
        });
    }
}
