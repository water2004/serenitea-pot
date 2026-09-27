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
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.region.BlockRegion;
import org.edtp.sereniteapot.region.RegionCopyTask;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        var server = level.getServer();
        UUID owner = UUID.randomUUID();
        SereniteaPotBundle[] bundle = {null};
        RegionCopyTask[] task = {null};
        MutableAttachment sourceAttachment = new MutableAttachment(42);
        AtomicBoolean queued = new AtomicBoolean();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        helper.onEachTick(() -> {
            if (failure.get() != null) {
                helper.fail("Region copy failed: " + failure.get());
                return;
            }
            if (complete.get()) {
                helper.succeed();
                return;
            }
            if (!queued.compareAndSet(false, true)) return;
            server.execute(() -> {
                try {
                    if (task[0] == null) {
                        bundle[0] = SereniteaPotManager.createStaging(owner, 1, level.getSeed());
                        level.setBlockAndUpdate(sourceMarker, Blocks.DIAMOND_BLOCK.defaultBlockState());
                        level.getChunk(sourceChunkX, sourceChunkZ).setAttached(TEST_ATTACHMENT, sourceAttachment);
                        task[0] = new RegionCopyTask(level, bundle[0].get(SereniteaPotDimension.OVERWORLD),
                            BlockRegion.chunkColumns(sourceChunkX, sourceChunkZ, 0, level.getMinY(), level.getMaxY()));
                    }
                    task[0].step(System.nanoTime() + 20_000_000L, 64);
                    if (!task[0].getComplete()) return;
                    ServerLevel target = bundle[0].get(SereniteaPotDimension.OVERWORLD);
                    LevelChunk targetChunk = target.getChunk(sourceChunkX, sourceChunkZ);
                    MutableAttachment targetAttachment = targetChunk.getAttached(TEST_ATTACHMENT);
                    helper.assertTrue(targetAttachment != null, "Persistent chunk attachment was not copied");
                    helper.assertTrue(targetAttachment != sourceAttachment,
                            "Source and target chunks share the same attachment object");
                    helper.assertValueEqual(42, targetAttachment.number,
                            "Persistent chunk attachment value differs");

                    sourceAttachment.number = 99;
                    level.setBlockAndUpdate(sourceMarker, Blocks.EMERALD_BLOCK.defaultBlockState());
                    helper.assertValueEqual(Blocks.DIAMOND_BLOCK, target.getBlockState(sourceMarker).getBlock(),
                            "Source and target chunks share section state or coordinates changed");
                    helper.assertValueEqual(42, targetAttachment.number,
                            "Mutating the source attachment changed the target attachment");
                    SereniteaPotLifecycleService.deleteEvacuated(bundle[0]);
                    SereniteaPotManager.catalog().getPlayers().remove(owner);
                    SereniteaPotManager.saveCatalog();
                    complete.set(true);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    queued.set(false);
                }
            });
        });
    }

    private static final class MutableAttachment {
        private int number;

        private MutableAttachment(int number) {
            this.number = number;
        }
    }
}
