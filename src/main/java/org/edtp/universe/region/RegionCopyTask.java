package org.edtp.universe.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

/**
 * Main-thread, deadline-driven copy of one bounded region.
 *
 * Blocks are visited chunk-by-chunk so source and target chunks are prepared
 * once per slice. A call prepares at most one new chunk; an individual chunk
 * load or mod callback cannot be preempted, but it cannot cascade into many
 * synchronous chunk loads in the same server tick.
 */
public final class RegionCopyTask {
    private static final int ENTITY_ROOTS_PER_SLICE = 256;

    private final ServerLevel source;
    private final ServerLevel target;
    private final BlockRegion region;
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private Phase phase = Phase.BLOCKS;

    private final int chunkMinX;
    private final int chunkMinZ;
    private final int chunkSizeX;
    private final int chunkSizeZ;
    private final long chunkCount;
    private long chunkCursor;
    private LevelChunk sourceChunk;
    private LevelChunk targetChunk;
    private int blockX;
    private int blockY;
    private int blockZ;
    private int sliceMinX;
    private int sliceMaxX;
    private int sliceMinZ;
    private int sliceMaxZ;
    private long copiedBlocks;

    private final int quartMinX;
    private final int quartMinY;
    private final int quartMinZ;
    private final int quartSizeX;
    private final int quartSizeY;
    private final int quartSizeZ;
    private final long quartVolume;
    private long copiedQuarts;
    private boolean copyingChunkBiomes;
    private int quartX;
    private int quartY;
    private int quartZ;
    private int sliceQuartMinX;
    private int sliceQuartMaxX;
    private int sliceQuartMinZ;
    private int sliceQuartMaxZ;

    private final ArrayDeque<Entity> entities = new ArrayDeque<>();
    private final HashSet<UUID> collectedEntityIds = new HashSet<>();
    private long tickChunkCursor;
    private long entityScanChunkCursor;
    private int totalEntities;
    private int copiedEntities;

    public RegionCopyTask(ServerLevel source, ServerLevel target, BlockRegion region) {
        this.source = source;
        this.target = target;
        this.region = region;
        this.chunkMinX = region.getMinX() >> 4;
        this.chunkMinZ = region.getMinZ() >> 4;
        this.chunkSizeX = (region.getMaxX() >> 4) - chunkMinX + 1;
        this.chunkSizeZ = (region.getMaxZ() >> 4) - chunkMinZ + 1;
        this.chunkCount = Math.multiplyExact((long) chunkSizeX, (long) chunkSizeZ);
        this.quartMinX = QuartPos.fromBlock(region.getMinX());
        this.quartMinY = QuartPos.fromBlock(region.getMinY());
        this.quartMinZ = QuartPos.fromBlock(region.getMinZ());
        this.quartSizeX = QuartPos.fromBlock(region.getMaxX()) - quartMinX + 1;
        this.quartSizeY = QuartPos.fromBlock(region.getMaxY()) - quartMinY + 1;
        this.quartSizeZ = QuartPos.fromBlock(region.getMaxZ()) - quartMinZ + 1;
        this.quartVolume = (long) quartSizeX * quartSizeY * quartSizeZ;
    }

    public BlockRegion getRegion() {
        return region;
    }

    public boolean getComplete() {
        return phase == Phase.DONE;
    }

    public double getProgress() {
        return switch (phase) {
            case BLOCKS -> copiedBlocks / (double) region.getVolume() * 0.9
                    + copiedQuarts / (double) Math.max(quartVolume, 1L) * 0.08;
            case TICKS -> 0.98 + tickChunkCursor / (double) chunkCount * 0.005;
            case ENTITY_SCAN -> 0.985 + entityScanChunkCursor / (double) chunkCount * 0.005;
            case ENTITIES -> 0.99 + copiedEntities / (double) Math.max(totalEntities, 1) * 0.01;
            case DONE -> 1.0;
        };
    }

    public void step(long deadlineNanos) {
        step(deadlineNanos, 256);
    }

    public void step(long deadlineNanos, int maximumOperations) {
        int operations = 0;
        int preparedChunks = 0;
        while (!getComplete() && operations < maximumOperations && System.nanoTime() < deadlineNanos) {
            if (phase == Phase.BLOCKS && sourceChunk == null && chunkCursor < chunkCount) {
                if (preparedChunks >= 1) {
                    return;
                }
                prepareChunk();
                preparedChunks++;
                operations++;
                continue;
            }
            switch (phase) {
                case BLOCKS -> { if (copyingChunkBiomes) copyBiome(); else copyBlock(); }
                case TICKS -> copyNextChunkTicks();
                case ENTITY_SCAN -> collectNextChunkEntities();
                case ENTITIES -> copyNextEntity();
                case DONE -> { }
            }
            operations++;
        }
    }

    private void prepareChunk() {
        int chunkX = chunkMinX + (int) (chunkCursor % chunkSizeX);
        int chunkZ = chunkMinZ + (int) (chunkCursor / chunkSizeX);
        sourceChunk = source.getChunk(chunkX, chunkZ);
        targetChunk = target.getChunk(chunkX, chunkZ);
        sliceMinX = Math.max(region.getMinX(), chunkX << 4);
        sliceMaxX = Math.min(region.getMaxX(), (chunkX << 4) + 15);
        sliceMinZ = Math.max(region.getMinZ(), chunkZ << 4);
        sliceMaxZ = Math.min(region.getMaxZ(), (chunkZ << 4) + 15);
        sliceQuartMinX = Math.max(quartMinX, chunkX << 2);
        sliceQuartMaxX = Math.min(quartMinX + quartSizeX - 1, (chunkX << 2) + 3);
        sliceQuartMinZ = Math.max(quartMinZ, chunkZ << 2);
        sliceQuartMaxZ = Math.min(quartMinZ + quartSizeZ - 1, (chunkZ << 2) + 3);
        blockX = sliceMinX;
        blockY = region.getMinY();
        blockZ = sliceMinZ;
    }

    private void copyBlock() {
        if (chunkCursor >= chunkCount) {
            phase = Phase.TICKS;
            return;
        }
        if (sourceChunk == null || targetChunk == null) {
            throw new IllegalStateException("Chunk was not prepared");
        }
        position.set(blockX, blockY, blockZ);
        var state = sourceChunk.getBlockState(position);
        targetChunk.setBlockState(position, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        if (state.hasBlockEntity()) {
            var sourceEntity = sourceChunk.getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE);
            var targetEntity = targetChunk.getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE);
            if (sourceEntity != null && targetEntity != null) {
                var tag = sourceEntity.saveWithFullMetadata(source.registryAccess());
                targetEntity.loadWithComponents(
                        TagValueInput.create(ProblemReporter.DISCARDING, target.registryAccess(), tag));
                targetEntity.setChanged();
            }
        }
        copiedBlocks++;
        advanceBlockCursor();
    }

    private void advanceBlockCursor() {
        blockX++;
        if (blockX <= sliceMaxX) return;
        blockX = sliceMinX;
        blockZ++;
        if (blockZ <= sliceMaxZ) return;
        blockZ = sliceMinZ;
        blockY++;
        if (blockY <= region.getMaxY()) return;
        copyingChunkBiomes = true;
        quartX = sliceQuartMinX;
        quartY = quartMinY;
        quartZ = sliceQuartMinZ;
    }

    @SuppressWarnings("unchecked")
    private void copyBiome() {
        if (sourceChunk == null || targetChunk == null) {
            throw new IllegalStateException("Chunk was not prepared");
        }
        int biomeBlockY = QuartPos.toBlock(quartY);
        var section = targetChunk.getSection(targetChunk.getSectionIndex(biomeBlockY));
        var biomes = (PalettedContainer<Holder<Biome>>) (Object) section.getBiomes();
        biomes.set(quartX & 3, quartY & 3, quartZ & 3, sourceChunk.getNoiseBiome(quartX, quartY, quartZ));
        targetChunk.markUnsaved();
        copiedQuarts++;
        advanceBiomeCursor();
    }

    private void advanceBiomeCursor() {
        quartX++;
        if (quartX <= sliceQuartMaxX) return;
        quartX = sliceQuartMinX;
        quartZ++;
        if (quartZ <= sliceQuartMaxZ) return;
        quartZ = sliceQuartMinZ;
        quartY++;
        if (quartY < quartMinY + quartSizeY) return;
        copyingChunkBiomes = false;
        chunkCursor++;
        sourceChunk = null;
        targetChunk = null;
        if (chunkCursor >= chunkCount) phase = Phase.TICKS;
    }

    private void copyNextChunkTicks() {
        if (tickChunkCursor >= chunkCount) {
            phase = Phase.ENTITY_SCAN;
            return;
        }
        var box = chunkBox(tickChunkCursor++);
        target.getBlockTicks().clearArea(box);
        target.getBlockTicks().copyAreaFrom(source.getBlockTicks(), box, BlockPos.ZERO);
        target.getFluidTicks().clearArea(box);
        target.getFluidTicks().copyAreaFrom(source.getFluidTicks(), box, BlockPos.ZERO);
        if (tickChunkCursor >= chunkCount) phase = Phase.ENTITY_SCAN;
    }

    private void collectNextChunkEntities() {
        if (entityScanChunkCursor >= chunkCount) {
            totalEntities = entities.size();
            phase = Phase.ENTITIES;
            return;
        }
        var area = AABB.of(chunkBox(entityScanChunkCursor));
        var found = new ArrayList<Entity>(ENTITY_ROOTS_PER_SLICE);
        source.getEntities(EntityTypeTest.forClass(Entity.class), area,
                entity -> !(entity instanceof ServerPlayer)
                        && !entity.isPassenger()
                        && !collectedEntityIds.contains(entity.getUUID()),
                found, ENTITY_ROOTS_PER_SLICE);
        for (var entity : found) {
            if (collectedEntityIds.add(entity.getUUID())) entities.addLast(entity);
        }
        if (found.size() >= ENTITY_ROOTS_PER_SLICE) {
            return;
        }
        entityScanChunkCursor++;
        if (entityScanChunkCursor >= chunkCount) {
            totalEntities = entities.size();
            phase = Phase.ENTITIES;
        }
    }

    private void copyNextEntity() {
        var entity = entities.pollFirst();
        if (entity == null) {
            phase = Phase.DONE;
            return;
        }
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
        if (entity.save(output)) {
            var copy = EntityType.loadEntityRecursive(output.buildResult(), target,
                    new EntitySpawnRequest(EntitySpawnReason.LOAD, false), loaded -> loaded);
            if (copy != null) {
                copy.getSelfAndPassengers().forEach(loaded -> loaded.setUUID(UUID.randomUUID()));
                target.tryAddFreshEntityWithPassengers(copy);
            }
        }
        copiedEntities++;
        if (entities.isEmpty()) phase = Phase.DONE;
    }

    private BoundingBox chunkBox(long index) {
        int chunkX = chunkMinX + (int) (index % chunkSizeX);
        int chunkZ = chunkMinZ + (int) (index / chunkSizeX);
        return new BoundingBox(
                Math.max(region.getMinX(), chunkX << 4), region.getMinY(), Math.max(region.getMinZ(), chunkZ << 4),
                Math.min(region.getMaxX(), (chunkX << 4) + 15), region.getMaxY(), Math.min(region.getMaxZ(), (chunkZ << 4) + 15));
    }

    private enum Phase { BLOCKS, TICKS, ENTITY_SCAN, ENTITIES, DONE }
}
