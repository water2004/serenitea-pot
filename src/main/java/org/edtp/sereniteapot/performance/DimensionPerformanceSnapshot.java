package org.edtp.sereniteapot.performance;

/** A completed one-second performance window for one Serenitea Pot dimension. */
public record DimensionPerformanceSnapshot(
    double averageTickMillis,
    double maximumTickMillis,
    long executedTicks,
    long skippedTicks
) {
}
