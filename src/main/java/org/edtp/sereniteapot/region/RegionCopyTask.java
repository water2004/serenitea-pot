package org.edtp.sereniteapot.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main-thread, deadline-driven copy of a full-height, chunk-aligned region.
 *
 * <p>Block states and biomes are cloned one palette-backed chunk section at a
 * time instead of being rewritten block by block. Chunk loading and mod hooks
 * still cannot be preempted, so each slice prepares at most one new chunk and
 * the scheduler charges its real elapsed time.</p>
 */
public final class RegionCopyTask {
    private static final int ENTITY_ROOTS_PER_SLICE = 256;
    private static final int MAX_PENDING_LIGHT_CHUNKS = 32;

    private final ServerLevel source;
    private final ServerLevel target;
    private final BlockRegion sourceRegion;
    private final BlockRegion targetRegion;
    private Phase phase = Phase.CHUNKS;

    private final int sourceChunkMinX;
    private final int sourceChunkMinZ;
    private final int targetChunkMinX;
    private final int targetChunkMinZ;
    private final int chunkSizeX;
    private final int blockOffsetX;
    private final int blockOffsetZ;
    private final int chunkOffsetX;
    private final int chunkOffsetZ;
    private final BlockPos blockOffset;
    private final long chunkCount;
    private long chunkCursor;
    private LevelChunk sourceChunk;
    private LevelChunk targetChunk;
    private final ArrayDeque<CompletableFuture<?>> lightingBarriers = new ArrayDeque<>();

    private final ArrayDeque<Entity> entities = new ArrayDeque<>();
    private final HashSet<UUID> collectedEntityIds = new HashSet<>();
    private long tickChunkCursor;
    private long entityScanChunkCursor;
    private int totalEntities;
    private int copiedEntities;

    public RegionCopyTask(
        ServerLevel source,
        ServerLevel target,
        BlockRegion sourceRegion,
        BlockRegion targetRegion
    ) {
        this.source = source;
        this.target = target;
        this.sourceRegion = sourceRegion;
        this.targetRegion = targetRegion;
        requireCopyRegion(source, sourceRegion, "source");
        requireCopyRegion(target, targetRegion, "target");
        if (sourceRegion.getSizeX() != targetRegion.getSizeX()
                || sourceRegion.getSizeY() != targetRegion.getSizeY()
                || sourceRegion.getSizeZ() != targetRegion.getSizeZ()
                || sourceRegion.getMinY() != targetRegion.getMinY()) {
            throw new IllegalArgumentException("Source and target copy regions must have the same size");
        }
        this.sourceChunkMinX = sourceRegion.getMinX() >> 4;
        this.sourceChunkMinZ = sourceRegion.getMinZ() >> 4;
        this.targetChunkMinX = targetRegion.getMinX() >> 4;
        this.targetChunkMinZ = targetRegion.getMinZ() >> 4;
        this.chunkSizeX = (sourceRegion.getMaxX() >> 4) - sourceChunkMinX + 1;
        int chunkSizeZ = (sourceRegion.getMaxZ() >> 4) - sourceChunkMinZ + 1;
        this.chunkCount = Math.multiplyExact((long) chunkSizeX, chunkSizeZ);
        this.blockOffsetX = Math.subtractExact(targetRegion.getMinX(), sourceRegion.getMinX());
        this.blockOffsetZ = Math.subtractExact(targetRegion.getMinZ(), sourceRegion.getMinZ());
        this.chunkOffsetX = Math.subtractExact(targetChunkMinX, sourceChunkMinX);
        this.chunkOffsetZ = Math.subtractExact(targetChunkMinZ, sourceChunkMinZ);
        this.blockOffset = new BlockPos(blockOffsetX, 0, blockOffsetZ);
    }

    public boolean getComplete() {
        return phase == Phase.DONE;
    }

    public double getProgress() {
        return switch (phase) {
            case CHUNKS -> chunkCursor / (double) chunkCount * 0.9;
            case TICKS -> 0.9 + tickChunkCursor / (double) chunkCount * 0.04;
            case ENTITY_SCAN -> 0.94 + entityScanChunkCursor / (double) chunkCount * 0.03;
            case ENTITIES -> 0.97 + copiedEntities / (double) Math.max(totalEntities, 1) * 0.02;
            case LIGHTING -> 0.99;
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
            if (phase == Phase.CHUNKS && sourceChunk == null && chunkCursor < chunkCount) {
                pumpLighting();
                drainCompletedLighting();
                if (lightingBarriers.size() >= MAX_PENDING_LIGHT_CHUNKS) {
                    operations++;
                    continue;
                }
                if (preparedChunks >= 1) return;
                prepareChunk();
                preparedChunks++;
                operations++;
                continue;
            }
            switch (phase) {
                case CHUNKS -> copyPreparedChunk();
                case TICKS -> copyNextChunkTicks();
                case ENTITY_SCAN -> collectNextChunkEntities();
                case ENTITIES -> copyNextEntity();
                case LIGHTING -> finishLighting();
                case DONE -> { }
            }
            operations++;
        }
    }

    private void prepareChunk() {
        int relativeChunkX = (int) (chunkCursor % chunkSizeX);
        int relativeChunkZ = (int) (chunkCursor / chunkSizeX);
        sourceChunk = source.getChunk(sourceChunkMinX + relativeChunkX, sourceChunkMinZ + relativeChunkZ);
        targetChunk = target.getChunk(targetChunkMinX + relativeChunkX, targetChunkMinZ + relativeChunkZ);
    }

    private void copyPreparedChunk() {
        if (chunkCursor >= chunkCount) {
            phase = Phase.TICKS;
            return;
        }
        if (sourceChunk == null || targetChunk == null) {
            throw new IllegalStateException("Chunk was not prepared");
        }

        var sourceSections = sourceChunk.getSections();
        var targetSections = targetChunk.getSections();
        if (sourceSections.length != targetSections.length
                || sourceChunk.getMinSectionY() != targetChunk.getMinSectionY()) {
            throw new IllegalStateException("Source and target chunk heights differ");
        }

        targetChunk.clearAllBlockEntities();
        // Mojang's ProtoChunk -> LevelChunk constructor can transfer section objects because
        // the ProtoChunk is discarded. Our public source chunk stays alive, so every section
        // must be cloned just like SerializableChunkData.copyOf does for persistence.
        for (int index = 0; index < sourceSections.length; index++) {
            targetSections[index] = sourceSections[index].copy();
        }
        sourceChunk.getHeightmaps().forEach(entry ->
                targetChunk.setHeightmap(entry.getKey(), entry.getValue().getRawData().clone()));
        targetChunk.setInhabitedTime(sourceChunk.getInhabitedTime());
        copyPostProcessing();
        copyStructures();
        copyAttachments();
        copyBlockEntities();
        targetChunk.initializeLightSources();
        refreshPoiAndLighting();
        targetChunk.markUnsaved();

        chunkCursor++;
        sourceChunk = null;
        targetChunk = null;
        if (chunkCursor >= chunkCount) phase = Phase.TICKS;
    }

    private void copyPostProcessing() {
        var sourceSections = sourceChunk.getPostProcessing();
        var targetSections = targetChunk.getPostProcessing();
        if (sourceSections.length != targetSections.length) {
            throw new IllegalStateException("Source and target post-processing heights differ");
        }
        for (int index = 0; index < sourceSections.length; index++) {
            targetSections[index] = sourceSections[index] == null
                    ? null
                    : new ShortArrayList(sourceSections[index]);
        }
    }

    private void copyStructures() {
        var sourceContext = StructurePieceSerializationContext.fromLevel(source);
        var targetContext = StructurePieceSerializationContext.fromLevel(target);
        HashMap<Structure, StructureStart> starts = new HashMap<>();
        sourceChunk.getAllStarts().forEach((structure, start) -> {
            var copy = StructureStart.loadStaticStart(
                    targetContext,
                    start.createTag(sourceContext, sourceChunk.getPos()),
                    target.getSeed()
            );
            if (copy == null) return;
            if (!copy.isValid()) {
                starts.put(structure, StructureStart.INVALID_START);
                return;
            }
            copy.getPieces().forEach(piece -> piece.move(blockOffsetX, 0, blockOffsetZ));
            starts.put(copy.getStructure(), new StructureStart(
                copy.getStructure(),
                targetChunk.getPos(),
                copy.getReferences(),
                new PiecesContainer(copy.getPieces())
            ));
        });
        targetChunk.setAllStarts(starts);
        HashMap<Structure, LongSet> references = new HashMap<>();
        sourceChunk.getAllReferences().forEach((structure, positions) -> {
            LongSet translated = new LongOpenHashSet(positions.size());
            positions.forEach(position -> translated.add(ChunkPos.pack(
                Math.addExact(ChunkPos.getX(position), chunkOffsetX),
                Math.addExact(ChunkPos.getZ(position), chunkOffsetZ)
            )));
            references.put(structure, translated);
        });
        targetChunk.setAllReferences(references);
    }

    private void copyAttachments() {
        // Fabric's in-memory transfer aliases attachment values and is only safe when the
        // source object is discarded. A codec round trip gives two live chunks independent
        // persistent values without serializing the rest of the chunk.
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
        ((AttachmentTargetImpl) sourceChunk).fabric_writeAttachmentsToNbt(output);
        ((AttachmentTargetImpl) targetChunk).fabric_readAttachmentsFromNbt(
                TagValueInput.create(ProblemReporter.DISCARDING, target.registryAccess(), output.buildResult()));
    }

    private void copyBlockEntities() {
        for (BlockPos blockPos : sourceChunk.getBlockEntitiesPos()) {
            var tag = sourceChunk.getBlockEntityNbtForSaving(blockPos, source.registryAccess());
            if (tag == null) continue;
            BlockPos targetPos = blockPos.offset(blockOffset);
            var translated = tag.copy();
            translated.putInt("x", targetPos.getX());
            translated.putInt("y", targetPos.getY());
            translated.putInt("z", targetPos.getZ());
            targetChunk.setBlockEntityNbt(translated);
            targetChunk.getBlockEntity(targetPos, LevelChunk.EntityCreationType.IMMEDIATE);
        }
    }

    private void refreshPoiAndLighting() {
        ChunkPos sourceChunkPos = sourceChunk.getPos();
        ChunkPos targetChunkPos = targetChunk.getPos();
        var sourceLight = source.getChunkSource().getLightEngine();
        var targetLight = target.getChunkSource().getLightEngine();
        boolean lightCorrect = sourceChunk.isLightCorrect();
        List<PoiSnapshot> poiSnapshots = source.getPoiManager()
                .getInChunk(type -> true, sourceChunkPos, PoiManager.Occupancy.ANY)
                .map(record -> {
                    var packed = record.pack();
                    int occupiedTickets = packed.poiType().value().maxTickets() - packed.freeTickets();
                    return new PoiSnapshot(packed.pos().offset(blockOffset), packed.poiType(), occupiedTickets);
                })
                .toList();

        targetLight.retainData(targetChunkPos, true);
        for (int sectionY = targetLight.getMinLightSection();
             sectionY < targetLight.getMaxLightSection(); sectionY++) {
            SectionPos sourceSectionPos = SectionPos.of(sourceChunkPos, sectionY);
            SectionPos targetSectionPos = SectionPos.of(targetChunkPos, sectionY);
            var blockLight = sourceLight.getLayerListener(LightLayer.BLOCK).getDataLayerData(sourceSectionPos);
            var skyLight = sourceLight.getLayerListener(LightLayer.SKY).getDataLayerData(sourceSectionPos);
            targetLight.queueSectionData(
                    LightLayer.BLOCK, targetSectionPos,
                    lightCorrect && blockLight != null ? blockLight.copy() : null);
            targetLight.queueSectionData(
                    LightLayer.SKY, targetSectionPos,
                    lightCorrect && skyLight != null ? skyLight.copy() : null);
        }
        for (int index = 0; index < targetChunk.getSections().length; index++) {
            int sectionY = targetChunk.getSectionYFromSectionIndex(index);
            SectionPos sectionPos = SectionPos.of(targetChunkPos, sectionY);
            var section = targetChunk.getSections()[index];
            target.getPoiManager().checkConsistencyWithBlocks(sectionPos, section);
            targetLight.updateSectionStatus(sectionPos, section.hasOnlyAir());
        }
        restorePoiOccupancy(poiSnapshots);
        targetChunk.setLightCorrect(lightCorrect);
        if (lightCorrect) {
            targetLight.setLightEnabled(targetChunkPos, true);
            targetLight.retainData(targetChunkPos, false);
            lightingBarriers.add(targetLight.waitForPendingTasks(targetChunkPos.x(), targetChunkPos.z()));
        } else {
            lightingBarriers.add(targetLight.initializeLight(targetChunk, false)
                    .thenCompose(chunk -> targetLight.lightChunk(chunk, false)));
        }
        targetLight.tryScheduleUpdate();
    }

    private void restorePoiOccupancy(List<PoiSnapshot> snapshots) {
        PoiManager poiManager = target.getPoiManager();
        for (PoiSnapshot snapshot : snapshots) {
            for (int ticket = 0; ticket < snapshot.occupiedTickets(); ticket++) {
                var acquired = poiManager.take(
                        type -> type.equals(snapshot.type()),
                        (type, pos) -> type.equals(snapshot.type()) && pos.equals(snapshot.pos()),
                        snapshot.pos(),
                        0
                );
                if (acquired.isEmpty()) {
                    throw new IllegalStateException("Could not restore POI occupancy at " + snapshot.pos());
                }
            }
        }
    }

    private void copyNextChunkTicks() {
        if (tickChunkCursor >= chunkCount) {
            phase = Phase.ENTITY_SCAN;
            return;
        }
        long index = tickChunkCursor++;
        var sourceBox = sourceChunkBox(index);
        var targetBox = targetChunkBox(index);
        target.getBlockTicks().clearArea(targetBox);
        target.getBlockTicks().copyAreaFrom(source.getBlockTicks(), sourceBox, blockOffset);
        target.getFluidTicks().clearArea(targetBox);
        target.getFluidTicks().copyAreaFrom(source.getFluidTicks(), sourceBox, blockOffset);
        if (tickChunkCursor >= chunkCount) phase = Phase.ENTITY_SCAN;
    }

    private void collectNextChunkEntities() {
        if (entityScanChunkCursor >= chunkCount) {
            totalEntities = entities.size();
            phase = Phase.ENTITIES;
            return;
        }
        var area = AABB.of(sourceChunkBox(entityScanChunkCursor));
        var found = new ArrayList<Entity>(ENTITY_ROOTS_PER_SLICE);
        source.getEntities(EntityTypeTest.forClass(Entity.class), area,
                entity -> !(entity instanceof ServerPlayer)
                        && !entity.isPassenger()
                        && !collectedEntityIds.contains(entity.getUUID()),
                found, ENTITY_ROOTS_PER_SLICE);
        for (var entity : found) {
            if (collectedEntityIds.add(entity.getUUID())) entities.addLast(entity);
        }
        if (found.size() >= ENTITY_ROOTS_PER_SLICE) return;
        entityScanChunkCursor++;
        if (entityScanChunkCursor >= chunkCount) {
            totalEntities = entities.size();
            phase = Phase.ENTITIES;
        }
    }

    private void copyNextEntity() {
        var entity = entities.pollFirst();
        if (entity == null) {
            phase = Phase.LIGHTING;
            return;
        }
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
        if (entity.save(output)) {
            var copy = EntityType.loadEntityRecursive(output.buildResult(), target,
                    new EntitySpawnRequest(EntitySpawnReason.LOAD, false), loaded -> loaded);
            if (copy != null) {
                copy.getSelfAndPassengers().forEach(loaded -> loaded.setUUID(UUID.randomUUID()));
                copy.teleportRelative(blockOffsetX, 0.0, blockOffsetZ);
                target.tryAddFreshEntityWithPassengers(copy);
            }
        }
        copiedEntities++;
        if (entities.isEmpty()) phase = Phase.LIGHTING;
    }

    private void finishLighting() {
        pumpLighting();
        drainCompletedLighting();
        if (lightingBarriers.isEmpty()) phase = Phase.DONE;
    }

    private void pumpLighting() {
        target.getChunkSource().pollTask();
        target.getChunkSource().getLightEngine().tryScheduleUpdate();
    }

    private void drainCompletedLighting() {
        Iterator<CompletableFuture<?>> iterator = lightingBarriers.iterator();
        while (iterator.hasNext()) {
            CompletableFuture<?> barrier = iterator.next();
            if (!barrier.isDone()) continue;
            barrier.join();
            iterator.remove();
        }
    }

    private BoundingBox sourceChunkBox(long index) {
        return chunkBox(sourceRegion, sourceChunkMinX, sourceChunkMinZ, index);
    }

    private BoundingBox targetChunkBox(long index) {
        return chunkBox(targetRegion, targetChunkMinX, targetChunkMinZ, index);
    }

    private BoundingBox chunkBox(BlockRegion region, int chunkMinX, int chunkMinZ, long index) {
        int chunkX = chunkMinX + (int) (index % chunkSizeX);
        int chunkZ = chunkMinZ + (int) (index / chunkSizeX);
        return new BoundingBox(
                Math.max(region.getMinX(), chunkX << 4), region.getMinY(), Math.max(region.getMinZ(), chunkZ << 4),
                Math.min(region.getMaxX(), (chunkX << 4) + 15), region.getMaxY(), Math.min(region.getMaxZ(), (chunkZ << 4) + 15));
    }

    private static void requireCopyRegion(ServerLevel level, BlockRegion region, String role) {
        if ((region.getMinX() & 15) != 0 || (region.getMinZ() & 15) != 0
                || (region.getMaxX() & 15) != 15 || (region.getMaxZ() & 15) != 15
                || region.getMinY() != level.getMinY() || region.getMaxY() != level.getMaxY() - 1) {
            throw new IllegalArgumentException(
                "Fast region copy requires full-height, chunk-aligned " + role + " bounds"
            );
        }
    }

    private record PoiSnapshot(BlockPos pos, Holder<PoiType> type, int occupiedTickets) {
    }

    private enum Phase { CHUNKS, TICKS, ENTITY_SCAN, ENTITIES, LIGHTING, DONE }
}
