package org.edtp.sereniteapot.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.Objects;

/** An inclusive, axis-aligned block region. */
public final class BlockRegion {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    public BlockRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid block region");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.sizeX = Math.addExact(Math.subtractExact(maxX, minX), 1);
        this.sizeY = Math.addExact(Math.subtractExact(maxY, minY), 1);
        this.sizeZ = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
    }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    /**
     * Creates a region covering whole chunk columns around the block's chunk.
     * The vertical interval is half-open: {@code [minY, maxYExclusive)}.
     */
    public static BlockRegion chunkColumns(
            BlockPos centerBlockPos, int radiusChunks, int minY, int maxYExclusive) {
        Objects.requireNonNull(centerBlockPos, "centerBlockPos");
        return chunkColumns(
                SectionPos.blockToSectionCoord(centerBlockPos.getX()),
                SectionPos.blockToSectionCoord(centerBlockPos.getZ()),
                radiusChunks,
                minY,
                maxYExclusive
        );
    }

    /**
     * Creates a region covering whole chunk columns around a chunk coordinate.
     * The vertical interval is half-open: {@code [minY, maxYExclusive)}.
     */
    public static BlockRegion chunkColumns(
            int centerChunkX, int centerChunkZ, int radiusChunks, int minY, int maxYExclusive) {
        if (radiusChunks < 0) {
            throw new IllegalArgumentException("Chunk radius must not be negative");
        }
        if (minY >= maxYExclusive) {
            throw new IllegalArgumentException("Maximum Y must be greater than minimum Y");
        }

        int minChunkX = Math.subtractExact(centerChunkX, radiusChunks);
        int maxChunkX = Math.addExact(centerChunkX, radiusChunks);
        int minChunkZ = Math.subtractExact(centerChunkZ, radiusChunks);
        int maxChunkZ = Math.addExact(centerChunkZ, radiusChunks);
        int minX = Math.multiplyExact(minChunkX, SectionPos.SECTION_SIZE);
        int minZ = Math.multiplyExact(minChunkZ, SectionPos.SECTION_SIZE);
        int maxX = Math.addExact(
                Math.multiplyExact(maxChunkX, SectionPos.SECTION_SIZE),
                SectionPos.SECTION_MAX_INDEX
        );
        int maxZ = Math.addExact(
                Math.multiplyExact(maxChunkZ, SectionPos.SECTION_SIZE),
                SectionPos.SECTION_MAX_INDEX
        );
        int maxY = Math.subtractExact(maxYExclusive, 1);
        return new BlockRegion(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BlockRegion that)) return false;
        return minX == that.minX && minY == that.minY && minZ == that.minZ
                && maxX == that.maxX && maxY == that.maxY && maxZ == that.maxZ;
    }

    @Override
    public int hashCode() {
        int result = minX;
        result = 31 * result + minY;
        result = 31 * result + minZ;
        result = 31 * result + maxX;
        result = 31 * result + maxY;
        result = 31 * result + maxZ;
        return result;
    }

    @Override
    public String toString() {
        return "BlockRegion(minX=" + minX + ", minY=" + minY + ", minZ=" + minZ
                + ", maxX=" + maxX + ", maxY=" + maxY + ", maxZ=" + maxZ + ")";
    }
}
