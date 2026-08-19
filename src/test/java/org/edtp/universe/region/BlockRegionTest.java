package org.edtp.universe.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockRegionTest {
    @Test
    void linearCursorVisitsFirstAndLastBlock() {
        var region = new BlockRegion(-1, -1, -1, 1, 1, 1);
        var cursor = new BlockPos.MutableBlockPos();

        assertEquals(new BlockPos(-1, -1, -1), region.position(0, cursor).immutable());
        assertEquals(new BlockPos(1, 1, 1), region.position(region.getVolume() - 1, cursor).immutable());
    }

    @Test
    void zeroChunkRadiusCoversOneAlignedChunkAndFullHeight() {
        var region = BlockRegion.chunkColumns(new BlockPos(15, 0, -1), 0, -64, 320);

        assertEquals(0, region.getMinX());
        assertEquals(15, region.getMaxX());
        assertEquals(-16, region.getMinZ());
        assertEquals(-1, region.getMaxZ());
        assertEquals(-64, region.getMinY());
        assertEquals(319, region.getMaxY());
        assertEquals(16, region.getSizeX());
        assertEquals(384, region.getSizeY());
        assertEquals(16, region.getSizeZ());
    }

    @Test
    void oneChunkRadiusUsesNegativeChunkCoordinates() {
        var region = BlockRegion.chunkColumns(1, -2, 1, 0, 1);

        assertEquals(0, region.getMinX());
        assertEquals(47, region.getMaxX());
        assertEquals(-48, region.getMinZ());
        assertEquals(-1, region.getMaxZ());
        assertEquals(0, region.getMinY());
        assertEquals(0, region.getMaxY());
    }

    @Test
    void rejectsInvalidHeightAndOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockRegion.chunkColumns(0, 0, 0, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> BlockRegion.chunkColumns(0, 0, -1, 0, 1));
        assertThrows(ArithmeticException.class,
                () -> BlockRegion.chunkColumns(Integer.MAX_VALUE, 0, 0, 0, 1));
        assertThrows(ArithmeticException.class,
                () -> BlockRegion.chunkColumns(Integer.MAX_VALUE, 0, 1, 0, 1));
    }
}
