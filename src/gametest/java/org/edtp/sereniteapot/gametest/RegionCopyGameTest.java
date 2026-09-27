package org.edtp.sereniteapot.gametest;

import com.mojang.serialization.Codec;
import net.casual.arcade.dimensions.utils.DimensionUtilsKt;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
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

    @GameTest(maxTicks = 600)
    public void copiesEntitiesAndScheduledTicksFromUnloadedNativeChunks(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID sourceOwner = UUID.randomUUID();
        UUID targetOwner = UUID.randomUUID();
        SereniteaPotBundle[] source = {null};
        SereniteaPotBundle[] target = {null};
        RegionCopyTask[] task = {null};
        java.util.concurrent.CompletableFuture<?>[] verification = {null};
        AtomicBoolean queued = new AtomicBoolean();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (failure.get() != null) {
                helper.fail("Native chunk reload copy failed: " + failure.get());
                return;
            }
            if (complete.get()) { helper.succeed(); return; }
            if (!queued.compareAndSet(false, true)) return;
            server.execute(() -> {
                try {
                    if (task[0] == null) {
                        source[0] = SereniteaPotManager.createStaging(sourceOwner, 1, 1);
                        target[0] = SereniteaPotManager.createStaging(targetOwner, 1, 1);
                        var from = source[0].get(SereniteaPotDimension.OVERWORLD);
                        for (int x : new int[]{1, 17}) {
                            BlockPos pos = new BlockPos(x, 70, 1);
                            from.setBlockAndUpdate(pos, Blocks.OAK_SAPLING.defaultBlockState());
                            from.scheduleTick(pos, Blocks.OAK_SAPLING, 6000);
                            if (!from.addFreshEntity(new ItemEntity(from, x + .5, 71, 1.5,
                                    new ItemStack(Items.DIAMOND)))) {
                                throw new AssertionError("Could not populate source entities");
                            }
                        }
                        // Force the test through Minecraft's region/entity files,
                        // not just an already-populated in-memory entity section.
                        var key = from.dimension();
                        DimensionUtilsKt.removeCustomLevel(server, from);
                        var reloaded = DimensionUtilsKt.loadCustomLevel(server, key);
                        if (reloaded == null) throw new AssertionError("Native source reload failed");
                        source[0].levels().put(SereniteaPotDimension.OVERWORLD, reloaded);
                        task[0] = new RegionCopyTask(reloaded,
                            target[0].get(SereniteaPotDimension.OVERWORLD),
                            new BlockRegion(0, reloaded.getMinY(), 0, 31, reloaded.getMaxY() - 1, 15));
                    }
                    // Deliberately span multiple ticks, including entity I/O completion.
                    task[0].step(System.nanoTime() + 20_000_000L, 1);
                    if (!task[0].getComplete()) return;
                    var copied = target[0].get(SereniteaPotDimension.OVERWORLD);
                    // The copy releases completed chunks. Re-open both through
                    // Vanilla before querying their tracked entity sections.
                    if (verification[0] == null) {
                        verification[0] = java.util.concurrent.CompletableFuture.allOf(
                            copied.getChunkSource().addTicketAndLoadWithRadius(net.minecraft.server.level.TicketType.FORCED,
                                new net.minecraft.world.level.ChunkPos(0, 0), 0),
                            copied.getChunkSource().addTicketAndLoadWithRadius(net.minecraft.server.level.TicketType.FORCED,
                                new net.minecraft.world.level.ChunkPos(1, 0), 0));
                        return;
                    }
                    if (!verification[0].isDone()) return;
                    ((org.edtp.sereniteapot.mixin.accessor.ServerLevelEntityManagerAccessor) (Object) copied)
                        .sereniteapot$getEntityManager().processPendingLoads();
                    if (!copied.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(0, 0))
                        || !copied.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(1, 0))) return;
                    var items = copied.getEntitiesOfClass(ItemEntity.class,
                        new AABB(0, 60, 0, 32, 90, 16));
                    helper.assertValueEqual(items.size(), 2, "Disk-loaded entities were lost or duplicated");
                    for (int x : new int[]{1, 17}) {
                        helper.assertTrue(copied.getBlockTicks().hasScheduledTick(new BlockPos(x, 70, 1), Blocks.OAK_SAPLING),
                            "Disk-loaded scheduled tick was lost");
                    }
                    task[0].close();
                    SereniteaPotLifecycleService.deleteEvacuated(source[0]);
                    SereniteaPotLifecycleService.deleteEvacuated(target[0]);
                    SereniteaPotManager.catalog().getPlayers().remove(sourceOwner);
                    SereniteaPotManager.catalog().getPlayers().remove(targetOwner);
                    SereniteaPotManager.saveCatalog();
                    complete.set(true);
                } catch (Throwable error) {
                    if (task[0] != null) task[0].close();
                    failure.set(error);
                } finally {
                    queued.set(false);
                }
            });
        });
    }

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
                    task[0].close();
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
