package org.edtp.universe.model;

/** Immutable metadata for one full-height, chunk-aligned universe dimension. */
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
}
