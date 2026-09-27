package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PotPortalGameTest {
    @GameTest(maxTicks = 400)
    @SuppressWarnings("removal")
    public void nativePortalsStayInThePairedDimensionsAndInsideTheirBorders(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var initialPlayer = helper.makeMockServerPlayerInLevel();
        var owner = initialPlayer.getUUID();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean complete = new AtomicBoolean();
        server.execute(() -> {
            try {
                SereniteaPotManager.getOrCreateRecord(owner).setMaxRadiusChunks(0);
                var bundle = SereniteaPotManager.createStaging(owner, 1, 1);
                var overworld = bundle.get(SereniteaPotDimension.OVERWORLD);
                var nether = bundle.get(SereniteaPotDimension.NETHER);
                var end = bundle.get(SereniteaPotDimension.END);
                overworld.getWorldBorder().setCenter(-232, -152);
                nether.getWorldBorder().setCenter(-24, -24);
                end.getWorldBorder().setCenter(8008, -7992);
                var overworldSlot = new SereniteaPotSlotRecord("minecraft:overworld", -231, 80, -146, 0);
                overworld.setBlockAndUpdate(new BlockPos(-231, 79, -146), Blocks.STONE.defaultBlockState());
                SereniteaPotManager.commitGeneration(bundle, Map.of(
                    SereniteaPotDimension.OVERWORLD, overworldSlot,
                    SereniteaPotDimension.END, new SereniteaPotSlotRecord("minecraft:the_end", 8001, 70, -7999, 0)
                ), 0);

                var pig = EntityTypes.PIG.create(overworld, EntitySpawnReason.COMMAND);
                check(pig != null, "Could not create portal test entity");
                pig.snapTo(-231.5, 80, -146.5);
                var endPortal = (EndPortalBlock) Blocks.END_PORTAL;
                var arrival = endPortal.getPortalDestination(overworld, pig, pig.blockPosition());
                assertDestination(arrival, end);
                BlockPos platform = BlockPos.containing(arrival.position()).below(2);
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        BlockPos block = platform.offset(x, 0, z);
                        check(end.getWorldBorder().isWithinBounds(block), "End platform crossed the border");
                        check(end.getBlockState(block).is(Blocks.OBSIDIAN), "Vanilla End platform was not generated");
                    }
                }
                check(end.getBlockState(ServerLevel.END_SPAWN_POINT.below(2)).isAir(),
                    "An extra platform was generated outside the pot border");

                // When the original spawn fits, do not relocate it to the extraction point.
                end.getWorldBorder().setCenter(104, 8);
                end.getWorldBorder().setSize(48);
                var nativeArrival = endPortal.getPortalDestination(overworld, pig, pig.blockPosition());
                check(nativeArrival.position().equals(Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT)),
                    "An in-bounds Vanilla End spawn was changed");
                end.getWorldBorder().setCenter(8008, -7992);
                end.getWorldBorder().setSize(16);

                var endPig = EntityTypes.PIG.create(end, EntitySpawnReason.COMMAND);
                check(endPig != null, "Could not create End return entity");
                endPig.snapTo(8003, 70, -7990);
                var returnTrip = endPortal.getPortalDestination(end, endPig, endPig.blockPosition());
                assertDestination(returnTrip, overworld);
                check(returnTrip.position().equals(Vec3.atBottomCenterOf(overworldSlot.entryPosition(overworld.getWorldBorder()))),
                    "Entity returned to public spawn coordinates instead of the pot entry");

                var netherPortal = (NetherPortalBlock) Blocks.NETHER_PORTAL;
                var toNether = netherPortal.getPortalDestination(overworld, pig, pig.blockPosition());
                assertDestination(toNether, nether);
                var netherPig = EntityTypes.PIG.create(nether, EntitySpawnReason.COMMAND);
                check(netherPig != null, "Could not create Nether return entity");
                netherPig.snapTo(toNether.position());
                assertDestination(netherPortal.getPortalDestination(nether, netherPig, netherPig.blockPosition()), overworld);

                check(SereniteaPotTravelService.enter(initialPlayer, owner) == SereniteaPotTravelService.Success.INSTANCE,
                    "Owner could not enter portal fixture");
                var player = server.getPlayerList().getPlayer(owner);
                check(player.teleportTo(end, 8003, 70, -7990, Set.of(), 0, 0, true), "Owner could not reach End");
                player = server.getPlayerList().getPlayer(owner);
                player.setRespawnPosition(null, false);
                var playerReturn = endPortal.getPortalDestination(end, player, player.blockPosition());
                assertDestination(playerReturn, overworld);
                check(playerReturn.position().equals(Vec3.atBottomCenterOf(overworldSlot.entryPosition(overworld.getWorldBorder()))),
                    "Player End return selected the End again instead of the pot entry");

                // Forced personal respawn is the same Vanilla path as a valid bed/anchor.
                var spawn = new BlockPos(-26, 70, -26);
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    LevelData.RespawnData.of(nether.dimension(), spawn, 45, 0), true), false);
                var personalReturn = endPortal.getPortalDestination(end, player, player.blockPosition());
                assertDestination(personalReturn, nether);
                check(BlockPos.containing(personalReturn.position()).equals(spawn), "Personal pot respawn was ignored");

                var publicPig = EntityTypes.PIG.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                check(publicPig != null, "Could not create public portal entity");
                var publicArrival = endPortal.getPortalDestination(helper.getLevel(), publicPig, BlockPos.ZERO);
                check(publicArrival != null && publicArrival.newLevel().dimension() == Level.END
                    && publicArrival.position().equals(Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT)),
                    "Public-world End portal behavior changed");

                check(SereniteaPotDeletionService.deleteAndReset(server, owner)
                    == SereniteaPotDeletionService.Success.INSTANCE, "Portal fixture cleanup failed");
                server.getPlayerList().remove(server.getPlayerList().getPlayer(owner));
                SereniteaPotManager.catalog().getPlayers().remove(owner);
                SereniteaPotManager.saveCatalog();
                complete.set(true);
            } catch (Throwable error) {
                org.edtp.sereniteapot.SereniteaPotMod.LOGGER.error("Portal regression", error);
                failure.set(error);
            }
        });
        helper.onEachTick(() -> {
            if (failure.get() != null) helper.fail("Portal regression: " + failure.get());
            else if (complete.get()) helper.succeed();
        });
    }

    private static void assertDestination(TeleportTransition transition, ServerLevel expected) {
        check(transition != null && transition.newLevel() == expected,
            "Expected " + expected.dimension().identifier() + ", got "
                + (transition == null ? "no destination" : transition.newLevel().dimension().identifier()));
        check(expected.getWorldBorder().isWithinBounds(transition.position().x, transition.position().z),
            "Portal destination is outside its border");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
