package org.edtp.universe.model;

/** Immutable metadata describing one generated dimension slot. */
public record UniverseSlotRecord(
    String sourceDimension,
    int centerX,
    int centerY,
    int centerZ,
    int radius
) {
}
