package org.edtp.universe.performance

import org.edtp.universe.model.UniverseDimension
import java.util.UUID

data class UniversePerformanceSnapshot(
    val owner: UUID,
    val budgetMillisPerSecond: Double,
    val consumedMillisLastSecond: Double,
    val averageTickMillis: Double,
    val maximumTickMillis: Double,
    val effectiveTps: Double,
    val executedTicks: Long,
    val skippedTicks: Long,
    val dimensions: Map<UniverseDimension, DimensionPerformanceSnapshot>,
)

data class DimensionPerformanceSnapshot(
    val averageTickMillis: Double,
    val maximumTickMillis: Double,
    val executedTicks: Long,
    val skippedTicks: Long,
)
