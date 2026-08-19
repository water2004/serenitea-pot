package org.edtp.universe.performance;

/** A completed one-second performance window for one universe dimension. */
public record DimensionPerformanceSnapshot(
    double averageTickMillis,
    double maximumTickMillis,
    long executedTicks,
    long skippedTicks
) {
}
