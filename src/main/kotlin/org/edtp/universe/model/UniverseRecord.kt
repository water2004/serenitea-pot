package org.edtp.universe.model

import java.util.EnumMap
import java.util.UUID

data class UniverseSlotRecord(
    var sourceDimension: String,
    var centerX: Int,
    var centerY: Int,
    var centerZ: Int,
    var radius: Int,
)

data class UniverseRecord(
    val owner: UUID,
    var stateId: UUID = UUID.randomUUID(),
    var activeGeneration: Long = 0,
    var maxRadius: Int = DEFAULT_MAX_RADIUS,
    var budgetMillisPerSecond: Double = DEFAULT_BUDGET_MILLIS_PER_SECOND,
    var enabled: Boolean = true,
    var frozen: Boolean = false,
    var stopped: Boolean = false,
    var quarantined: Boolean = false,
    val slots: MutableMap<UniverseDimension, UniverseSlotRecord> =
        EnumMap(UniverseDimension::class.java),
) {
    val exists: Boolean
        get() = activeGeneration > 0

    companion object {
        const val DEFAULT_MAX_RADIUS = 64
        const val DEFAULT_BUDGET_MILLIS_PER_SECOND = 25.0
    }
}

data class UniverseCatalog(
    var defaultMaxRadius: Int = UniverseRecord.DEFAULT_MAX_RADIUS,
    var defaultBudgetMillisPerSecond: Double = UniverseRecord.DEFAULT_BUDGET_MILLIS_PER_SECOND,
    var globalBudgetMillisPerSecond: Double = 100.0,
    val players: MutableMap<UUID, UniverseRecord> = LinkedHashMap(),
) {
    fun getOrCreate(owner: UUID): UniverseRecord = players.getOrPut(owner) {
        UniverseRecord(
            owner = owner,
            maxRadius = defaultMaxRadius,
            budgetMillisPerSecond = defaultBudgetMillisPerSecond,
        )
    }
}
