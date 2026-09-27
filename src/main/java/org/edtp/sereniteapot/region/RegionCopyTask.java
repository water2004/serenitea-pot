package org.edtp.sereniteapot.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.fabricmc.fabric.impl.attachment.AttachmentTargetImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import org.edtp.sereniteapot.mixin.accessor.ServerLevelEntityManagerAccessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main-thread, deadline-driven copy of a full-height, chunk-aligned region
 * between distinct dimensions at identical coordinates.
 *
 * <p>Block states and biomes are cloned one palette-backed chunk section at a
 * time instead of being rewritten block by block. Native chunk loads are
 * requested without waiting; each slice prepares at most one new chunk and
 * the scheduler charges its real elapsed time.</p>
 */
public final class RegionCopyTask {
    private static final int ENTITY_ROOTS_PER_SLICE = 256;
    private static TicketType copyTicketType;
    private static final Map<ServerLevel, Map<Long, SharedChunkLoad>> sharedChunkLoads = new IdentityHashMap<>();

    private final ServerLevel source;
    private final ServerLevel target;
    private final BlockRegion region;
    private Phase phase = Phase.CHUNKS;

    private final int chunkMinX;
    private final int chunkMinZ;
    private final int chunkSizeX;
    private final long chunkCount;
    private long chunkCursor;
    private LevelChunk sourceChunk;
    private LevelChunk targetChunk;
    private ChunkPos pendingChunkPos;
    private SharedChunkLoad pendingSourceLoad;
    private SharedChunkLoad pendingTargetLoad;
    private boolean chunkReady;
    private boolean closed;
    private final ArrayDeque<CompletableFuture<?>> lightingBarriers = new ArrayDeque<>();

    private final ArrayDeque<Entity> entities = new ArrayDeque<>();
    private final HashSet<UUID> collectedEntityIds = new HashSet<>();

    public RegionCopyTask(
        ServerLevel source,
        ServerLevel target,
        BlockRegion region
    ) {
        this.source = source;
        this.target = target;
        if (source == target) throw new IllegalArgumentException("Copy requires distinct dimensions");
        this.region = region;
        requireCopyRegion(source, region, "source");
        requireCopyRegion(target, region, "target");
        this.chunkMinX = region.getMinX() >> 4;
        this.chunkMinZ = region.getMinZ() >> 4;
        this.chunkSizeX = (region.getMaxX() >> 4) - chunkMinX + 1;
        int chunkSizeZ = (region.getMaxZ() >> 4) - chunkMinZ + 1;
        this.chunkCount = Math.multiplyExact((long) chunkSizeX, chunkSizeZ);
    }

    public boolean getComplete() {
        return phase == Phase.DONE;
    }

    /** Register before Minecraft freezes built-in registries. */
    public static void registerTicketType() {
        if (copyTicketType == null) {
            copyTicketType = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath("serenitea_pot", "region_copy"),
                new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
        }
    }

    /** Cancel a pending load and release its native tickets. Calls are idempotent. */
    public void close() {
        if (closed) return;
        closed = true;
        releaseChunkTickets();
    }

    public double getProgress() {
        if (phase == Phase.DONE) return 1.0;
        double phaseProgress = switch (phase) {
            case CHUNKS -> 0.0;
            case TICKS -> 0.2;
            case ENTITY_SCAN -> 0.4;
            case ENTITIES -> 0.6;
            case LIGHTING -> 0.8;
            case DONE -> 1.0;
        };
        return Math.min(0.99, (chunkCursor + phaseProgress) / chunkCount * 0.99);
    }

    public void step(long deadlineNanos) {
        step(deadlineNanos, 256);
    }

    public void step(long deadlineNanos, int maximumOperations) {
        if (closed) return;
        try {
            stepOpen(deadlineNanos, maximumOperations);
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private void stepOpen(long deadlineNanos, int maximumOperations) {
        if (maximumOperations <= 0 || System.nanoTime() >= deadlineNanos) return;
        if (chunkCursor < chunkCount && !chunkReady && !prepareChunk()) return;
        int operations = 0;
        while (!getComplete() && operations < maximumOperations && System.nanoTime() < deadlineNanos) {
            long activeChunk = chunkCursor;
            switch (phase) {
                case CHUNKS -> copyPreparedChunk();
                case TICKS -> copyNextChunkTicks();
                case ENTITY_SCAN -> collectNextChunkEntities();
                case ENTITIES -> copyNextEntity();
                case LIGHTING -> finishLighting();
                case DONE -> { }
            }
            operations++;
            if (chunkCursor != activeChunk) return;
        }
    }

    private boolean prepareChunk() {
        int relativeChunkX = (int) (chunkCursor % chunkSizeX);
        int relativeChunkZ = (int) (chunkCursor / chunkSizeX);
        if (pendingChunkPos == null) {
            if (copyTicketType == null) throw new IllegalStateException("Region copy ticket type was not registered");
            pendingChunkPos = new ChunkPos(chunkMinX + relativeChunkX, chunkMinZ + relativeChunkZ);
            pendingSourceLoad = acquireChunkLoad(source, pendingChunkPos);
            try {
                pendingTargetLoad = acquireChunkLoad(target, pendingChunkPos);
            } catch (RuntimeException | Error error) {
                releaseChunkTickets();
                throw error;
            }
        }
        processPendingEntityLoads(source);
        processPendingEntityLoads(target);
        if (!pendingSourceLoad.future.isDone() || !pendingTargetLoad.future.isDone()) return false;
        pendingSourceLoad.future.getNow(null);
        pendingTargetLoad.future.getNow(null);
        if (sourceChunk == null) {
            sourceChunk = source.getChunkSource().getChunkNow(pendingChunkPos.x(), pendingChunkPos.z());
            targetChunk = target.getChunkSource().getChunkNow(pendingChunkPos.x(), pendingChunkPos.z());
        }
        if (sourceChunk == null || targetChunk == null) {
            throw new IllegalStateException("Completed native chunk request has no full chunk at " + pendingChunkPos);
        }
        chunkReady = source.areEntitiesLoaded(pendingChunkPos.pack())
                && target.areEntitiesLoaded(pendingChunkPos.pack());
        return chunkReady;
    }

    private static void processPendingEntityLoads(ServerLevel level) {
        ((ServerLevelEntityManagerAccessor) (Object) level)
                .sereniteapot$getEntityManager()
                .processPendingLoads();
    }

    private void releaseChunkTickets() {
        if (pendingChunkPos == null) return;
        ChunkPos pos = pendingChunkPos;
        SharedChunkLoad sourceLoad = pendingSourceLoad;
        SharedChunkLoad targetLoad = pendingTargetLoad;
        pendingChunkPos = null;
        pendingSourceLoad = null;
        pendingTargetLoad = null;
        chunkReady = false;
        sourceChunk = null;
        targetChunk = null;
        try {
            if (sourceLoad != null) releaseChunkLoad(source, pos, sourceLoad);
        } finally {
            if (targetLoad != null) releaseChunkLoad(target, pos, targetLoad);
        }
    }

    /**
     * A ticket is identified by its type and level, so native ticket storage
     * coalesces identical requests instead of counting their owners. Keep that
     * ownership count here and share the original load future between tasks.
     * All callers run on the server thread, like the native ticket APIs.
     */
    private static SharedChunkLoad acquireChunkLoad(ServerLevel level, ChunkPos pos) {
        long key = pos.pack();
        Map<Long, SharedChunkLoad> levelLoads = sharedChunkLoads.computeIfAbsent(level, ignored -> new HashMap<>());
        SharedChunkLoad existing = levelLoads.get(key);
        if (existing != null) {
            existing.references++;
            return existing;
        }

        CompletableFuture<?> future;
        try {
            future = level.getChunkSource().addTicketAndLoadWithRadius(copyTicketType, pos, 0);
        } catch (RuntimeException | Error error) {
            if (levelLoads.isEmpty()) sharedChunkLoads.remove(level);
            try {
                // addTicketAndLoadWithRadius installs the ticket before doing
                // its distance-manager work, which can itself fail.
                level.getChunkSource().removeTicketWithRadius(copyTicketType, pos, 0);
            } catch (RuntimeException | Error cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        SharedChunkLoad created = new SharedChunkLoad(future);
        levelLoads.put(key, created);
        return created;
    }

    private static void releaseChunkLoad(ServerLevel level, ChunkPos pos, SharedChunkLoad load) {
        if (--load.references > 0) return;
        Map<Long, SharedChunkLoad> levelLoads = sharedChunkLoads.get(level);
        if (levelLoads != null && levelLoads.get(pos.pack()) == load) {
            levelLoads.remove(pos.pack());
            if (levelLoads.isEmpty()) sharedChunkLoads.remove(level);
        }
        level.getChunkSource().removeTicketWithRadius(copyTicketType, pos, 0);
    }

    private static final class SharedChunkLoad {
        private final CompletableFuture<?> future;
        private int references = 1;

        private SharedChunkLoad(CompletableFuture<?> future) {
            this.future = future;
        }
    }

    private void copyPreparedChunk() {
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

        phase = Phase.TICKS;
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
            starts.put(structure, copy);
        });
        targetChunk.setAllStarts(starts);
        HashMap<Structure, LongSet> references = new HashMap<>();
        sourceChunk.getAllReferences().forEach((structure, positions) ->
            references.put(structure, new LongOpenHashSet(positions)));
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
            targetChunk.setBlockEntityNbt(tag.copy());
            targetChunk.getBlockEntity(blockPos, LevelChunk.EntityCreationType.IMMEDIATE);
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
                    return new PoiSnapshot(packed.pos(), packed.poiType(), occupiedTickets);
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
        var box = chunkBox(chunkCursor);
        // FULL chunks can still have packed ticks that LevelTicks.copyAreaFrom
        // cannot see. Vanilla's pack/unpack preserves those and rebases delays
        // onto the destination world's clock without mutating the source.
        long sourceTime = source.getGameTime();
        long targetTime = target.getGameTime();
        var savedTicks = sourceChunk.getTicksForSerialization(sourceTime);
        targetChunk.unpackTicks(targetTime);
        target.getBlockTicks().clearArea(box);
        target.getFluidTicks().clearArea(box);
        long order = 0;
        for (var tick : savedTicks.blocks()) {
            targetChunk.getBlockTicks().schedule(tick.unpack(targetTime, order++));
        }
        for (var tick : savedTicks.fluids()) {
            targetChunk.getFluidTicks().schedule(tick.unpack(targetTime, order++));
        }
        targetChunk.markUnsaved();
        phase = Phase.ENTITY_SCAN;
    }

    private void collectNextChunkEntities() {
        var area = AABB.of(chunkBox(chunkCursor));
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
        phase = entities.isEmpty() ? Phase.LIGHTING : Phase.ENTITIES;
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
                target.tryAddFreshEntityWithPassengers(copy);
            }
        }
        if (entities.isEmpty()) phase = Phase.LIGHTING;
    }

    private void finishLighting() {
        pumpLighting();
        drainCompletedLighting();
        if (lightingBarriers.isEmpty()) {
            releaseChunkTickets();
            chunkCursor++;
            phase = chunkCursor >= chunkCount ? Phase.DONE : Phase.CHUNKS;
        }
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

    private BoundingBox chunkBox(long index) {
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
