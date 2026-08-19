package org.edtp.universe.performance;

import org.edtp.universe.model.UniverseDimension;

import java.util.Map;
import java.util.UUID;

/** A completed one-second performance window for one universe. */
public record UniversePerformanceSnapshot(
    UUID owner,
    double budgetMillisPerSecond,
    double consumedMillisLastSecond,
    double creationMillisLastSecond,
    double averageTickMillis,
    double maximumTickMillis,
    double effectiveTps,
    long executedTicks,
    long skippedTicks,
    Map<UniverseDimension, DimensionPerformanceSnapshot> dimensions
) {
}
