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
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.AABB
import java.util.UUID

class RegionCopyTask(
    private val source: ServerLevel,
    private val target: ServerLevel,
    val region: BlockRegion,
) {
    private val position = BlockPos.MutableBlockPos()
    private var phase = Phase.BLOCKS
    private var cursor = 0L
    private val quartMinX = QuartPos.fromBlock(region.minX)
    private val quartMinY = QuartPos.fromBlock(region.minY)
    private val quartMinZ = QuartPos.fromBlock(region.minZ)
    private val quartSizeX = QuartPos.fromBlock(region.maxX) - quartMinX + 1
    private val quartSizeY = QuartPos.fromBlock(region.maxY) - quartMinY + 1
    private val quartSizeZ = QuartPos.fromBlock(region.maxZ) - quartMinZ + 1
    private val quartVolume = quartSizeX.toLong() * quartSizeY * quartSizeZ

    val complete: Boolean
        get() = phase == Phase.DONE

    val progress: Double
        get() = when (phase) {
            Phase.BLOCKS -> cursor.toDouble() / region.volume * 0.9
            Phase.BIOMES -> 0.9 + cursor.toDouble() / quartVolume.coerceAtLeast(1) * 0.09
            Phase.AUXILIARY -> 0.99
            Phase.DONE -> 1.0
        }

    fun step(deadlineNanos: Long, minimumBlocks: Int = 64) {
        var operations = 0
        while (!complete && (operations < minimumBlocks || System.nanoTime() < deadlineNanos)) {
            when (phase) {
                Phase.BLOCKS -> copyBlock()
                Phase.BIOMES -> copyBiome()
                Phase.AUXILIARY -> copyAuxiliaryData()
                Phase.DONE -> Unit
            }
            operations++
        }
    }

    private fun copyBlock() {
        if (cursor >= region.volume) {
            phase = Phase.BIOMES
            cursor = 0
            return
        }
        region.position(cursor++, position)
        val state = source.getBlockState(position)
        target.setBlock(
            position,
            state,
            Block.UPDATE_CLIENTS or Block.UPDATE_SKIP_ALL_SIDEEFFECTS,
        )
        if (!state.hasBlockEntity()) {
            return
        }
        val sourceEntity = source.getBlockEntity(position) ?: return
        val targetEntity = target.getBlockEntity(position) ?: return
        val tag = sourceEntity.saveWithFullMetadata(source.registryAccess())
        targetEntity.loadWithComponents(
            TagValueInput.create(ProblemReporter.DISCARDING, target.registryAccess(), tag),
        )
        targetEntity.setChanged()
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyBiome() {
        if (cursor >= quartVolume) {
            phase = Phase.AUXILIARY
            cursor = 0
            return
        }
        val qx = quartMinX + (cursor % quartSizeX).toInt()
        val yz = cursor / quartSizeX
        val qz = quartMinZ + (yz % quartSizeZ).toInt()
        val qy = quartMinY + (yz / quartSizeZ).toInt()
        cursor++

        val blockX = QuartPos.toBlock(qx)
        val blockY = QuartPos.toBlock(qy)
        val blockZ = QuartPos.toBlock(qz)
        val chunk = target.getChunk(blockX shr 4, blockZ shr 4)
        val section = chunk.getSection(chunk.getSectionIndex(blockY))
        val biomes = section.biomes as PalettedContainer<Holder<Biome>>
        biomes.set(qx and 3, qy and 3, qz and 3, source.getNoiseBiome(qx, qy, qz))
        chunk.markUnsaved()
    }

    private fun copyAuxiliaryData() {
        val box = region.boundingBox
        target.blockTicks.clearArea(box)
        target.blockTicks.copyAreaFrom(source.blockTicks, box, BlockPos.ZERO)
        target.fluidTicks.clearArea(box)
        target.fluidTicks.copyAreaFrom(source.fluidTicks, box, BlockPos.ZERO)
        copyEntities()
        phase = Phase.DONE
    }

    private fun copyEntities() {
        val area = AABB(
            region.minX.toDouble(),
            region.minY.toDouble(),
            region.minZ.toDouble(),
            region.maxX + 1.0,
            region.maxY + 1.0,
            region.maxZ + 1.0,
        )
        target.allEntities
            .filter { it !is ServerPlayer && area.contains(it.position()) }
            .toList()
            .forEach { it.remove(Entity.RemovalReason.DISCARDED) }

        for (entity in source.allEntities.toList()) {
            if (entity is ServerPlayer || entity.isPassenger || !area.contains(entity.position())) {
                continue
            }
            val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess())
            if (!entity.save(output)) {
                continue
            }
            val copy = EntityType.loadEntityRecursive(
                output.buildResult(),
                target,
                EntitySpawnRequest(EntitySpawnReason.LOAD, false),
            ) { loaded -> loaded } ?: continue
            copy.selfAndPassengers.forEach { loaded -> loaded.uuid = UUID.randomUUID() }
            target.tryAddFreshEntityWithPassengers(copy)
        }
    }

    private enum class Phase {
        BLOCKS,
        BIOMES,
        AUXILIARY,
        DONE,
    }
}
