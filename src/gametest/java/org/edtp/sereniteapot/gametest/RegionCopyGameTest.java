package org.edtp.sereniteapot.gametest;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.edtp.sereniteapot.region.BlockRegion;
import org.edtp.sereniteapot.region.RegionCopyTask;

public final class RegionCopyGameTest {
    private static final AttachmentType<MutableAttachment> TEST_ATTACHMENT =
            AttachmentRegistry.createPersistent(
                    Identifier.fromNamespaceAndPath("serenitea_pot_tests", "chunk_copy"),
                    Codec.INT.xmap(MutableAttachment::new, value -> value.number));

    @GameTest(maxTicks = 400, skyAccess = true)
    public void copiedChunkOwnsItsSectionsAndAttachments(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sourceMarker = helper.absolutePos(new BlockPos(1, 1, 1));
        int sourceChunkX = sourceMarker.getX() >> 4;
        int sourceChunkZ = sourceMarker.getZ() >> 4;
        int targetChunkX = sourceChunkX + 4;
        int targetChunkZ = sourceChunkZ;
        int blockOffsetX = (targetChunkX - sourceChunkX) << 4;
        BlockPos targetMarker = sourceMarker.offset(blockOffsetX, 0, 0);

        level.setBlockAndUpdate(sourceMarker, Blocks.DIAMOND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(targetMarker, Blocks.AIR.defaultBlockState());
        LevelChunk sourceChunk = level.getChunk(sourceChunkX, sourceChunkZ);
        LevelChunk targetChunk = level.getChunk(targetChunkX, targetChunkZ);
        MutableAttachment sourceAttachment = new MutableAttachment(42);
        sourceChunk.setAttached(TEST_ATTACHMENT, sourceAttachment);

        RegionCopyTask task = new RegionCopyTask(
                level,
                level,
                BlockRegion.chunkColumns(
                        sourceChunkX, sourceChunkZ, 0, level.getMinY(), level.getMaxY()),
                BlockRegion.chunkColumns(
                        targetChunkX, targetChunkZ, 0, level.getMinY(), level.getMaxY()));

        helper.onEachTick(() -> {
            if (task.getComplete()) {
                MutableAttachment targetAttachment = targetChunk.getAttached(TEST_ATTACHMENT);
                helper.assertTrue(targetAttachment != null, "Persistent chunk attachment was not copied");
                helper.assertTrue(targetAttachment != sourceAttachment,
                        "Source and target chunks share the same attachment object");
                helper.assertValueEqual(42, targetAttachment.number,
                        "Persistent chunk attachment value differs");

                sourceAttachment.number = 99;
                level.setBlockAndUpdate(sourceMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                helper.assertValueEqual(Blocks.DIAMOND_BLOCK, level.getBlockState(targetMarker).getBlock(),
                        "Source and target chunks share section state");
                helper.assertValueEqual(42, targetAttachment.number,
                        "Mutating the source attachment changed the target attachment");
                helper.succeed();
                return;
            }

            try {
                task.step(System.nanoTime() + 20_000_000L, 64);
            } catch (RuntimeException exception) {
                helper.fail("Region copy failed: " + exception);
            }
        });
    }

    private static final class MutableAttachment {
        private int number;

        private MutableAttachment(int number) {
            this.number = number;
        }
    }
}
