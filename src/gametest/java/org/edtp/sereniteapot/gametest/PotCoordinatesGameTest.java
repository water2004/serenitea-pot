package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.VegetationFeatures;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real extraction/trim jobs, including unload and reload, not just coordinate math. */
public final class PotCoordinatesGameTest {
    @GameTest(maxTicks = 1600)
    @SuppressWarnings("removal")
    public void preservesExistingDimensionsAndExtractsAtSourceCoordinates(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var player = helper.makeMockServerPlayerInLevel();
        var owner = player.getUUID();
        var source = helper.getLevel();
        var sourceEntry = new BlockPos(-231, 80, -146);
        var marker = sourceEntry.below();
        var outerMarker = marker.offset(20, 0, 0);
        var netherMarker = new BlockPos(-39, 70, 86);
        var endMarker = new BlockPos(5, 70, 5);
        int[] phase = {0};
        AtomicBoolean queued = new AtomicBoolean();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        helper.onEachTick(() -> {
            if (failure.get() != null) {
                helper.fail("Coordinate lifecycle failed: " + failure.get());
                return;
            }
            if (complete.get()) {
                helper.succeed();
                return;
            }
            if (!queued.compareAndSet(false, true)) return;
            server.execute(() -> {
                try {
                    switch (phase[0]) {
                        case 0 -> {
                            SereniteaPotManager.getOrCreateRecord(owner).setMaxRadiusChunks(1);
                            SereniteaPotBundle old = SereniteaPotManager.createStaging(owner, 1, source.getSeed());
                            ServerLevel nether = old.get(SereniteaPotDimension.NETHER);
                            nether.getWorldBorder().setCenter(-40, 88);
                            nether.getWorldBorder().setWarningBlocks(3);
                            nether.setBlockAndUpdate(netherMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                            old.get(SereniteaPotDimension.END).setBlockAndUpdate(endMarker, Blocks.GOLD_BLOCK.defaultBlockState());
                            var oldSlot = new SereniteaPotSlotRecord("minecraft:overworld", -231, 80, -146, 1);
                            SereniteaPotManager.commitGeneration(old, Map.of(
                                SereniteaPotDimension.OVERWORLD, oldSlot,
                                SereniteaPotDimension.NETHER,
                                new SereniteaPotSlotRecord("minecraft:the_nether", 1001, 70, 998, 1)
                            ), 1);
                            check(SereniteaPotLifecycleService.closeNow(server, owner)
                                == SereniteaPotLifecycleService.Success.INSTANCE, "Legacy unload failed");
                            var reloaded = SereniteaPotManager.load(owner);
                            check(reloaded.get(SereniteaPotDimension.OVERWORLD).getWorldBorder().getCenterX() == 8,
                                "Loading moved the legacy center");
                            check(oldSlot.entryPosition(reloaded.get(SereniteaPotDimension.OVERWORLD).getWorldBorder())
                                .equals(new BlockPos(9, 80, 14)), "Legacy entry was relocated");
                            check(reloaded.get(SereniteaPotDimension.NETHER).getWorldBorder().getCenterZ() == 88,
                                "Loading reset the saved per-dimension border");
                            check(reloaded.get(SereniteaPotDimension.NETHER).getWorldBorder().getWarningBlocks() == 3,
                                "Loading reset border settings");

                            source.setBlockAndUpdate(marker, Blocks.DIAMOND_BLOCK.defaultBlockState());
                            source.setBlockAndUpdate(outerMarker, Blocks.DIAMOND_BLOCK.defaultBlockState());
                            var forest = source.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.FLOWER_FOREST);
                            var chunk = source.getChunkAt(marker);
                            for (var section : chunk.getSections()) {
                                section.fillBiomesFromNoise((x, y, z, sampler) -> forest,
                                    source.getChunkSource().randomState().sampler(), 0, 0, 0);
                            }
                            player.snapTo(sourceEntry.getX() + .5, sourceEntry.getY(), sourceEntry.getZ() + .5);
                            check(SereniteaPotCreationService.request(player, 1) instanceof SereniteaPotCreationService.Accepted,
                                "Extraction was rejected");
                            phase[0] = 1;
                        }
                        case 1 -> {
                            if (SereniteaPotCreationService.isBusy(owner)) return;
                            check(SereniteaPotManager.record(owner).getActiveGeneration() != 1, "Extraction did not commit");
                            var bundle = SereniteaPotManager.load(owner);
                            ServerLevel pot = bundle.get(SereniteaPotDimension.OVERWORLD);
                            check(pot.getWorldBorder().getCenterX() == -232 && pot.getWorldBorder().getCenterZ() == -152,
                                "New extraction has the wrong center");
                            check(pot.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "Copied block was relocated");
                            check(pot.getBlockState(outerMarker).is(Blocks.DIAMOND_BLOCK), "Outer chunk was not copied");
                            check(pot.getNoiseBiome(marker.getX() >> 2, marker.getY() >> 2, marker.getZ() >> 2).is(Biomes.FLOWER_FOREST),
                                "Flower forest biome palette changed");
                            assertFlowers(source, pot, marker);
                            assertRetained(bundle, netherMarker, endMarker);
                            check(SereniteaPotTravelService.enter(server.getPlayerList().getPlayer(owner), owner)
                                == SereniteaPotTravelService.Success.INSTANCE, "Could not enter copied world");
                            var inside = server.getPlayerList().getPlayer(owner);
                            check(inside.blockPosition().equals(sourceEntry), "First entry did not use source coordinates");
                            check(SereniteaPotTravelService.leave(inside) == SereniteaPotTravelService.Success.INSTANCE,
                                "Could not leave copied world");
                            check(SereniteaPotCreationService.changeMaximum(server, owner, 0, null)
                                instanceof SereniteaPotCreationService.MaximumTrimStarted, "Trim did not start");
                            phase[0] = 2;
                        }
                        case 2 -> {
                            if (SereniteaPotCreationService.isBusy(owner)) return;
                            check(SereniteaPotManager.record(owner).getMaxRadiusChunks() == 0, "Trim failed to commit");
                            var bundle = SereniteaPotManager.load(owner);
                            ServerLevel pot = bundle.get(SereniteaPotDimension.OVERWORLD);
                            check(pot.getWorldBorder().getCenterX() == -232 && pot.getWorldBorder().getSize() == 16,
                                "Trim moved the center or did not shrink the border");
                            check(pot.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "Trim lost center contents");
                            check(pot.getBlockState(outerMarker).isAir(), "Trim retained discarded edge contents");
                            assertRetained(bundle, netherMarker, endMarker);
                            check(SereniteaPotDeletionService.deleteAndReset(server, owner)
                                == SereniteaPotDeletionService.Success.INSTANCE, "Test pot cleanup failed");
                            server.getPlayerList().remove(server.getPlayerList().getPlayer(owner));
                            SereniteaPotManager.catalog().getPlayers().remove(owner);
                            SereniteaPotManager.saveCatalog();
                            complete.set(true);
                        }
                        default -> throw new AssertionError("Unexpected test phase");
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    queued.set(false);
                }
            });
        });
    }

    private static void assertRetained(SereniteaPotBundle bundle, BlockPos netherMarker, BlockPos endMarker) {
        ServerLevel nether = bundle.get(SereniteaPotDimension.NETHER);
        check(nether.getWorldBorder().getCenterX() == -40 && nether.getWorldBorder().getCenterZ() == 88,
            "Rebuild moved an untouched dimension's center");
        check(nether.getBlockState(netherMarker).is(Blocks.EMERALD_BLOCK), "Rebuild lost an untouched dimension's block");
        check(nether.getWorldBorder().getWarningBlocks() == 3, "Rebuild lost border settings");
        check(bundle.get(SereniteaPotDimension.END).getBlockState(endMarker).is(Blocks.GOLD_BLOCK),
            "Rebuild lost contents of an unextracted dimension");
    }

    private static void assertFlowers(ServerLevel source, ServerLevel pot, BlockPos marker) {
        var feature = source.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE)
            .getOrThrow(VegetationFeatures.FLOWER_FLOWER_FOREST).value();
        var provider = ((SimpleBlockConfiguration) feature.config()).toPlace();
        boolean localCoordinatesDiffer = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos absolute = new BlockPos((marker.getX() >> 4) * 16 + x, marker.getY(), (marker.getZ() >> 4) * 16 + z);
                var expected = provider.getState(source, RandomSource.create(1), absolute);
                check(expected.equals(provider.getState(pot, RandomSource.create(2), absolute)), "Flower noise changed");
                localCoordinatesDiffer |= !expected.equals(provider.getState(pot, RandomSource.create(1),
                    new BlockPos(x, marker.getY(), z)));
            }
        }
        check(localCoordinatesDiffer, "Flower regression fixture did not expose the old coordinate shift");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
