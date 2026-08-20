package org.edtp.sereniteapot.performance;

import org.edtp.sereniteapot.model.SereniteaPotDimension;

import java.util.Map;
import java.util.UUID;

/** A completed one-second performance window for one Serenitea Pot. */
public record SereniteaPotPerformanceSnapshot(
    UUID owner,
    double budgetMillisPerSecond,
    double consumedMillisLastSecond,
    double creationMillisLastSecond,
    double averageTickMillis,
    double maximumTickMillis,
    double effectiveTps,
    long executedTicks,
    long skippedTicks,
    Map<SereniteaPotDimension, DimensionPerformanceSnapshot> dimensions
) {
}
