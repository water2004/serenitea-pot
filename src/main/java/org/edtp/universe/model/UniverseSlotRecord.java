package org.edtp.universe.model;

import net.minecraft.core.SectionPos;

/**
 * Immutable metadata for one full-height, chunk-aligned universe dimension.
 *
 * <p>The entry coordinates identify the extraction point in the public source
 * dimension. Universe levels use their own local coordinates: the source
 * entry's chunk is mapped to chunk {@code (0, 0)}.</p>
 */
public record UniverseSlotRecord(
    String sourceDimension,
    int entryX,
    int entryY,
    int entryZ,
    int radiusChunks
) {
    public UniverseSlotRecord {
        java.util.Objects.requireNonNull(sourceDimension, "sourceDimension");
        UniverseRecord.requireValidRadiusChunks(radiusChunks);
    }

    public int localEntryX() {
        return SectionPos.sectionRelative(entryX);
    }

    public int localEntryY() {
        return entryY;
    }

    public int localEntryZ() {
        return SectionPos.sectionRelative(entryZ);
    }
}
