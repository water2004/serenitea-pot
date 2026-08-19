package org.edtp.universe.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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
    private final long volume;

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
        this.volume = Math.multiplyExact(Math.multiplyExact((long) sizeX, (long) sizeY), (long) sizeZ);
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
    public long getVolume() { return volume; }

    public BoundingBox getBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public BlockPos.MutableBlockPos position(long index, BlockPos.MutableBlockPos mutable) {
        if (index < 0 || index >= volume) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        int x = (int) (index % sizeX);
        long yz = index / sizeX;
        int z = (int) (yz % sizeZ);
        int y = (int) (yz / sizeZ);
        return mutable.set(minX + x, minY + y, minZ + z);
    }

    public static BlockRegion centered(BlockPos center, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Radius must not be negative");
        }
        return new BlockRegion(
                Math.subtractExact(center.getX(), radius),
                Math.subtractExact(center.getY(), radius),
                Math.subtractExact(center.getZ(), radius),
                Math.addExact(center.getX(), radius),
                Math.addExact(center.getY(), radius),
                Math.addExact(center.getZ(), radius)
        );
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
