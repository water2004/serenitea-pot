package org.edtp.universe.region

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockRegionTest {
    @Test
    fun `radius is a half length and includes both endpoints`() {
        val region = BlockRegion.centered(BlockPos(10, 64, -10), 2)

        assertEquals(8, region.minX)
        assertEquals(12, region.maxX)
        assertEquals(62, region.minY)
        assertEquals(66, region.maxY)
        assertEquals(-12, region.minZ)
        assertEquals(-8, region.maxZ)
        assertEquals(125L, region.volume)
    }

    @Test
    fun `linear cursor visits first and last block`() {
        val region = BlockRegion.centered(BlockPos.ZERO, 1)
        val cursor = BlockPos.MutableBlockPos()

        assertEquals(BlockPos(-1, -1, -1), region.position(0, cursor).immutable())
        assertEquals(BlockPos(1, 1, 1), region.position(region.volume - 1, cursor).immutable())
    }
}
