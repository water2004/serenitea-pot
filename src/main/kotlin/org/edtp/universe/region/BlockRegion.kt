package org.edtp.universe.region

import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox

data class BlockRegion(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    init {
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Invalid block region" }
    }

    val sizeX: Int = Math.addExact(Math.subtractExact(maxX, minX), 1)
    val sizeY: Int = Math.addExact(Math.subtractExact(maxY, minY), 1)
    val sizeZ: Int = Math.addExact(Math.subtractExact(maxZ, minZ), 1)
    val volume: Long = Math.multiplyExact(Math.multiplyExact(sizeX.toLong(), sizeY.toLong()), sizeZ.toLong())

    val boundingBox: BoundingBox
        get() = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)

    fun position(index: Long, mutable: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos {
        require(index in 0 until volume)
        val x = (index % sizeX).toInt()
        val yz = index / sizeX
        val z = (yz % sizeZ).toInt()
        val y = (yz / sizeZ).toInt()
        return mutable.set(minX + x, minY + y, minZ + z)
    }

    companion object {
        fun centered(center: BlockPos, radius: Int): BlockRegion {
            require(radius >= 0) { "Radius must not be negative" }
            return BlockRegion(
                Math.subtractExact(center.x, radius),
                Math.subtractExact(center.y, radius),
                Math.subtractExact(center.z, radius),
                Math.addExact(center.x, radius),
                Math.addExact(center.y, radius),
                Math.addExact(center.z, radius),
            )
        }
    }
}
