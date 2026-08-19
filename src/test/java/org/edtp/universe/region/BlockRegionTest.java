package org.edtp.universe.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockRegionTest {
    @Test
    void radiusIsAHalfLengthAndIncludesBothEndpoints() {
        var region = BlockRegion.centered(new BlockPos(10, 64, -10), 2);

        assertEquals(8, region.getMinX());
        assertEquals(12, region.getMaxX());
        assertEquals(62, region.getMinY());
        assertEquals(66, region.getMaxY());
        assertEquals(-12, region.getMinZ());
        assertEquals(-8, region.getMaxZ());
        assertEquals(125L, region.getVolume());
    }

    @Test
    void linearCursorVisitsFirstAndLastBlock() {
        var region = BlockRegion.centered(BlockPos.ZERO, 1);
        var cursor = new BlockPos.MutableBlockPos();

        assertEquals(new BlockPos(-1, -1, -1), region.position(0, cursor).immutable());
        assertEquals(new BlockPos(1, 1, 1), region.position(region.getVolume() - 1, cursor).immutable());
    }
}
