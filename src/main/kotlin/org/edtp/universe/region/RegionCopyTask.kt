package org.edtp.universe.region

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.AABB
import java.util.ArrayDeque
import java.util.HashSet
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Main-thread, deadline-driven copy of one bounded region.
 *
 * Blocks are visited chunk-by-chunk so source and target chunks are prepared
 * once per slice. A call prepares at most one new chunk; an individual chunk
 * load or mod callback cannot be preempted, but it cannot cascade into many
 * synchronous chunk loads in the same server tick.
 */
class RegionCopyTask(
    private val source: ServerLevel,
    private val target: ServerLevel,
    val region: BlockRegion,
) {
    private val position = BlockPos.MutableBlockPos()
    private var phase = Phase.BLOCKS

    private val chunkMinX = region.minX shr 4
    private val chunkMinZ = region.minZ shr 4
    private val chunkSizeX = (region.maxX shr 4) - chunkMinX + 1
    private val chunkSizeZ = (region.maxZ shr 4) - chunkMinZ + 1
    private val chunkCount = chunkSizeX * chunkSizeZ
    private var chunkCursor = 0
    private var sourceChunk: LevelChunk? = null
    private var targetChunk: LevelChunk? = null
    private var blockX = 0
    private var blockY = 0
    private var blockZ = 0
    private var sliceMinX = 0
    private var sliceMaxX = 0
    private var sliceMinZ = 0
    private var sliceMaxZ = 0
    private var copiedBlocks = 0L

    private val quartMinX = QuartPos.fromBlock(region.minX)
    private val quartMinY = QuartPos.fromBlock(region.minY)
    private val quartMinZ = QuartPos.fromBlock(region.minZ)
    private val quartSizeX = QuartPos.fromBlock(region.maxX) - quartMinX + 1
    private val quartSizeY = QuartPos.fromBlock(region.maxY) - quartMinY + 1
    private val quartSizeZ = QuartPos.fromBlock(region.maxZ) - quartMinZ + 1
    private val quartVolume = quartSizeX.toLong() * quartSizeY * quartSizeZ
    private var copiedQuarts = 0L
    private var copyingChunkBiomes = false
    private var quartX = 0
    private var quartY = 0
    private var quartZ = 0
    private var sliceQuartMinX = 0
    private var sliceQuartMaxX = 0
    private var sliceQuartMinZ = 0
    private var sliceQuartMaxZ = 0

    private var entities = ArrayDeque<Entity>()
    private val collectedEntityIds = HashSet<UUID>()
    private var tickChunkCursor = 0
    private var entityScanChunkCursor = 0
    private var totalEntities = 0
    private var copiedEntities = 0

    val complete: Boolean
        get() = phase == Phase.DONE

    val progress: Double
        get() = when (phase) {
            Phase.BLOCKS ->
                copiedBlocks.toDouble() / region.volume * 0.9 +
                    copiedQuarts.toDouble() / quartVolume.coerceAtLeast(1) * 0.08
            Phase.TICKS -> 0.98 + tickChunkCursor.toDouble() / chunkCount * 0.005
            Phase.ENTITY_SCAN -> 0.985 + entityScanChunkCursor.toDouble() / chunkCount * 0.005
            Phase.ENTITIES -> 0.99 + copiedEntities.toDouble() / totalEntities.coerceAtLeast(1) * 0.01
            Phase.DONE -> 1.0
        }

    fun step(deadlineNanos: Long, maximumOperations: Int = 256) {
        var operations = 0
        var preparedChunks = 0
        while (!complete && operations < maximumOperations && System.nanoTime() < deadlineNanos) {
            if (phase == Phase.BLOCKS && sourceChunk == null && chunkCursor < chunkCount) {
                if (preparedChunks >= 1) {
                    return
                }
                prepareChunk()
                preparedChunks++
                operations++
                continue
            }
            when (phase) {
                Phase.BLOCKS -> if (copyingChunkBiomes) copyBiome() else copyBlock()
                Phase.TICKS -> copyNextChunkTicks()
                Phase.ENTITY_SCAN -> collectNextChunkEntities()
                Phase.ENTITIES -> copyNextEntity()
                Phase.DONE -> Unit
            }
            operations++
        }
    }

    private fun prepareChunk() {
        val chunkX = chunkMinX + chunkCursor % chunkSizeX
        val chunkZ = chunkMinZ + chunkCursor / chunkSizeX
        sourceChunk = source.getChunk(chunkX, chunkZ)
        targetChunk = target.getChunk(chunkX, chunkZ)
        sliceMinX = max(region.minX, chunkX shl 4)
        sliceMaxX = min(region.maxX, (chunkX shl 4) + 15)
        sliceMinZ = max(region.minZ, chunkZ shl 4)
        sliceMaxZ = min(region.maxZ, (chunkZ shl 4) + 15)
        sliceQuartMinX = max(quartMinX, chunkX shl 2)
        sliceQuartMaxX = min(quartMinX + quartSizeX - 1, (chunkX shl 2) + 3)
        sliceQuartMinZ = max(quartMinZ, chunkZ shl 2)
        sliceQuartMaxZ = min(quartMinZ + quartSizeZ - 1, (chunkZ shl 2) + 3)
        blockX = sliceMinX
        blockY = region.minY
        blockZ = sliceMinZ
    }

    private fun copyBlock() {
        if (chunkCursor >= chunkCount) {
            phase = Phase.TICKS
            return
        }
        val sourceChunk = requireNotNull(sourceChunk)
        val targetChunk = requireNotNull(targetChunk)
        position.set(blockX, blockY, blockZ)
        val state = sourceChunk.getBlockState(position)
        targetChunk.setBlockState(position, state, Block.UPDATE_SKIP_ALL_SIDEEFFECTS)
        if (state.hasBlockEntity()) {
            val sourceEntity = sourceChunk.getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE)
            val targetEntity = targetChunk.getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE)
            if (sourceEntity != null && targetEntity != null) {
                val tag = sourceEntity.saveWithFullMetadata(source.registryAccess())
                targetEntity.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, target.registryAccess(), tag),
                )
                targetEntity.setChanged()
            }
        }
        copiedBlocks++
        advanceBlockCursor()
    }

    private fun advanceBlockCursor() {
        blockX++
        if (blockX <= sliceMaxX) {
            return
        }
        blockX = sliceMinX
        blockZ++
        if (blockZ <= sliceMaxZ) {
            return
        }
        blockZ = sliceMinZ
        blockY++
        if (blockY <= region.maxY) {
            return
        }
        copyingChunkBiomes = true
        quartX = sliceQuartMinX
        quartY = quartMinY
        quartZ = sliceQuartMinZ
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyBiome() {
        val sourceChunk = requireNotNull(sourceChunk)
        val targetChunk = requireNotNull(targetChunk)
        val blockY = QuartPos.toBlock(quartY)
        val section = targetChunk.getSection(targetChunk.getSectionIndex(blockY))
        val biomes = section.biomes as PalettedContainer<Holder<Biome>>
        biomes.set(quartX and 3, quartY and 3, quartZ and 3, sourceChunk.getNoiseBiome(quartX, quartY, quartZ))
        targetChunk.markUnsaved()
        copiedQuarts++
        advanceBiomeCursor()
    }

    private fun advanceBiomeCursor() {
        quartX++
        if (quartX <= sliceQuartMaxX) {
            return
        }
        quartX = sliceQuartMinX
        quartZ++
        if (quartZ <= sliceQuartMaxZ) {
            return
        }
        quartZ = sliceQuartMinZ
        quartY++
        if (quartY < quartMinY + quartSizeY) {
            return
        }
        copyingChunkBiomes = false
        chunkCursor++
        sourceChunk = null
        targetChunk = null
        if (chunkCursor >= chunkCount) {
            phase = Phase.TICKS
        }
    }

    private fun copyNextChunkTicks() {
        if (tickChunkCursor >= chunkCount) {
            phase = Phase.ENTITY_SCAN
            return
        }
        val box = chunkBox(tickChunkCursor++)
        target.blockTicks.clearArea(box)
        target.blockTicks.copyAreaFrom(source.blockTicks, box, BlockPos.ZERO)
        target.fluidTicks.clearArea(box)
        target.fluidTicks.copyAreaFrom(source.fluidTicks, box, BlockPos.ZERO)
        if (tickChunkCursor >= chunkCount) {
            phase = Phase.ENTITY_SCAN
        }
    }

    private fun collectNextChunkEntities() {
        if (entityScanChunkCursor >= chunkCount) {
            totalEntities = entities.size
            phase = Phase.ENTITIES
            return
        }
        val area = AABB.of(chunkBox(entityScanChunkCursor++))
        for (entity in source.getEntities(null as Entity?, area) { it !is ServerPlayer && !it.isPassenger }) {
            if (collectedEntityIds.add(entity.uuid)) {
                entities.addLast(entity)
            }
        }
        if (entityScanChunkCursor >= chunkCount) {
            totalEntities = entities.size
            phase = Phase.ENTITIES
        }
    }

    private fun copyNextEntity() {
        val entity = entities.pollFirst()
        if (entity == null) {
            phase = Phase.DONE
            return
        }
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess())
        if (entity.save(output)) {
            val copy = EntityType.loadEntityRecursive(
                output.buildResult(),
                target,
                EntitySpawnRequest(EntitySpawnReason.LOAD, false),
            ) { loaded -> loaded }
            if (copy != null) {
                copy.selfAndPassengers.forEach { loaded -> loaded.uuid = UUID.randomUUID() }
                target.tryAddFreshEntityWithPassengers(copy)
            }
        }
        copiedEntities++
        if (entities.isEmpty()) {
            phase = Phase.DONE
        }
    }

    private fun chunkBox(index: Int): net.minecraft.world.level.levelgen.structure.BoundingBox {
        val chunkX = chunkMinX + index % chunkSizeX
        val chunkZ = chunkMinZ + index / chunkSizeX
        return net.minecraft.world.level.levelgen.structure.BoundingBox(
            max(region.minX, chunkX shl 4),
            region.minY,
            max(region.minZ, chunkZ shl 4),
            min(region.maxX, (chunkX shl 4) + 15),
            region.maxY,
            min(region.maxZ, (chunkZ shl 4) + 15),
        )
    }

    private enum class Phase {
        BLOCKS,
        TICKS,
        ENTITY_SCAN,
        ENTITIES,
        DONE,
    }
}
