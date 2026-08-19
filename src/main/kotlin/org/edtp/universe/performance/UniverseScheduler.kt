package org.edtp.universe.performance

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.permissions.Permissions
import org.edtp.universe.UniverseMod
import org.edtp.universe.level.UniverseLevelKeys
import org.edtp.universe.level.UniverseManager
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.region.UniverseCreationService
import java.util.EnumMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object UniverseScheduler {
    private const val TICKS_PER_SECOND = 20.0
    private const val MINIMUM_RESERVATION_NANOS = 50_000L
    private const val QUARANTINE_SINGLE_TICK_NANOS = 200_000_000L
    private val owners = LinkedHashMap<UUID, OwnerBudget>()
    private var globalTokensNanos = 0.0
    private var serverTicks = 0L

    fun register() {
        ServerTickEvents.START_SERVER_TICK.register(::startServerTick)
    }

    @JvmStatic
    fun beforeLevelTick(level: ServerLevel): Boolean {
        val identity = UniverseLevelKeys.identify(level.dimension()) ?: return true
        val record = UniverseManager.record(identity.owner) ?: return false
        if (identity.generation != record.activeGeneration) {
            return false
        }

        val budget = owners.getOrPut(identity.owner) { OwnerBudget(record.budgetMillisPerSecond) }
        budget.calls++
        budget.dimension(identity.dimension).calls++
        if (!record.enabled || record.frozen || record.stopped || record.quarantined ||
            UniverseCreationService.isBusy(identity.owner)
        ) {
            budget.recordSkip(identity.dimension)
            return false
        }

        budget.updateLimit(record.budgetMillisPerSecond)
        val reservation = max(MINIMUM_RESERVATION_NANOS.toDouble(), budget.estimatedNanos)
        if (budget.tokensNanos < reservation || globalTokensNanos < reservation) {
            budget.recordSkip(identity.dimension)
            return false
        }
        budget.tokensNanos -= reservation
        globalTokensNanos -= reservation
        budget.reservations[identity.dimension] = reservation
        return true
    }

    @JvmStatic
    fun afterLevelTick(level: ServerLevel, elapsedNanos: Long) {
        val identity = UniverseLevelKeys.identify(level.dimension()) ?: return
        val budget = owners[identity.owner] ?: return
        val reservation = budget.reservations.remove(identity.dimension) ?: 0.0
        val correction = elapsedNanos - reservation
        budget.tokensNanos -= correction
        globalTokensNanos -= correction
        budget.recordRun(identity.dimension, elapsedNanos)

        if (elapsedNanos >= QUARANTINE_SINGLE_TICK_NANOS) {
            val record = UniverseManager.record(identity.owner) ?: return
            if (!record.quarantined) {
                record.quarantined = true
                UniverseManager.saveCatalog()
                notifyQuarantine(level.server, identity.owner, elapsedNanos)
            }
        }
    }

    fun snapshot(owner: UUID): UniversePerformanceSnapshot? {
        val record = UniverseManager.record(owner) ?: return null
        val budget = owners[owner] ?: return UniversePerformanceSnapshot(
            owner,
            record.budgetMillisPerSecond,
            0.0,
            0.0,
            0.0,
            0.0,
            0,
            0,
            emptyMap(),
        )
        return budget.snapshot(owner, record.budgetMillisPerSecond)
    }

    fun allSnapshots(): List<UniversePerformanceSnapshot> =
        UniverseManager.catalog().players.keys.mapNotNull(::snapshot)
            .sortedByDescending { it.consumedMillisLastSecond }

    fun reset(owner: UUID) {
        owners.remove(owner)
    }

    private fun startServerTick(server: MinecraftServer) {
        serverTicks++
        val globalLimit = UniverseManager.catalog().globalBudgetMillisPerSecond * 1_000_000.0
        globalTokensNanos = min(globalLimit, globalTokensNanos + globalLimit / TICKS_PER_SECOND)
        for ((owner, budget) in owners) {
            val limit = UniverseManager.record(owner)?.budgetMillisPerSecond ?: 0.0
            budget.updateLimit(limit)
            budget.refill()
            if (serverTicks % 20L == 0L) {
                budget.rollWindow()
            }
        }
    }

    private fun notifyQuarantine(server: MinecraftServer, owner: UUID, elapsedNanos: Long) {
        val millis = elapsedNanos / 1_000_000.0
        UniverseMod.logger.error(
            "Universe {} produced a single {} ms level tick and was quarantined",
            owner,
            "%.2f".format(millis),
        )
        val message = Component.literal(
            "[Universe 647] $owner 单次维度 tick 耗时 ${"%.2f".format(millis)} ms，已自动隔离",
        )
        for (player in server.playerList.players) {
            if (player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
                player.sendSystemMessage(message)
            }
        }
    }

    private class OwnerBudget(limitMillisPerSecond: Double) {
        var limitNanos = limitMillisPerSecond * 1_000_000.0
        var tokensNanos = limitNanos / TICKS_PER_SECOND
        var estimatedNanos = MINIMUM_RESERVATION_NANOS.toDouble()
        val reservations = EnumMap<UniverseDimension, Double>(UniverseDimension::class.java)
        private val dimensions = EnumMap<UniverseDimension, DimensionBudget>(UniverseDimension::class.java)
        var calls = 0L
        private var runs = 0L
        private var skips = 0L
        private var consumed = 0L
        private var maximum = 0L
        private var lastCalls = 0L
        private var lastRuns = 0L
        private var lastSkips = 0L
        private var lastConsumed = 0L
        private var lastMaximum = 0L

        fun updateLimit(millisPerSecond: Double) {
            limitNanos = max(0.0, millisPerSecond * 1_000_000.0)
            tokensNanos = min(tokensNanos, limitNanos)
        }

        fun refill() {
            tokensNanos = min(limitNanos, tokensNanos + limitNanos / TICKS_PER_SECOND)
        }

        fun dimension(dimension: UniverseDimension): DimensionBudget =
            dimensions.getOrPut(dimension) { DimensionBudget() }

        fun recordSkip(dimension: UniverseDimension) {
            skips++
            dimension(dimension).skips++
        }

        fun recordRun(dimension: UniverseDimension, elapsedNanos: Long) {
            runs++
            consumed += elapsedNanos
            maximum = max(maximum, elapsedNanos)
            estimatedNanos = estimatedNanos * 0.8 + elapsedNanos * 0.2
            dimension(dimension).recordRun(elapsedNanos)
        }

        fun rollWindow() {
            lastCalls = calls
            lastRuns = runs
            lastSkips = skips
            lastConsumed = consumed
            lastMaximum = maximum
            calls = 0
            runs = 0
            skips = 0
            consumed = 0
            maximum = 0
            for (dimension in dimensions.values) {
                dimension.rollWindow()
            }
        }

        fun snapshot(owner: UUID, limitMillis: Double): UniversePerformanceSnapshot {
            val average = if (lastRuns == 0L) 0.0 else lastConsumed / lastRuns / 1_000_000.0
            val effectiveTps = if (lastCalls == 0L) 0.0 else lastRuns.toDouble() / lastCalls * 20.0
            return UniversePerformanceSnapshot(
                owner = owner,
                budgetMillisPerSecond = limitMillis,
                consumedMillisLastSecond = lastConsumed / 1_000_000.0,
                averageTickMillis = average,
                maximumTickMillis = lastMaximum / 1_000_000.0,
                effectiveTps = effectiveTps,
                executedTicks = lastRuns,
                skippedTicks = lastSkips,
                dimensions = dimensions.mapValues { it.value.snapshot() },
            )
        }
    }

    private class DimensionBudget {
        var calls = 0L
        var runs = 0L
        var skips = 0L
        var consumed = 0L
        var maximum = 0L
        private var lastRuns = 0L
        private var lastSkips = 0L
        private var lastConsumed = 0L
        private var lastMaximum = 0L

        fun recordRun(elapsedNanos: Long) {
            runs++
            consumed += elapsedNanos
            maximum = max(maximum, elapsedNanos)
        }

        fun rollWindow() {
            lastRuns = runs
            lastSkips = skips
            lastConsumed = consumed
            lastMaximum = maximum
            calls = 0
            runs = 0
            skips = 0
            consumed = 0
            maximum = 0
        }

        fun snapshot(): DimensionPerformanceSnapshot = DimensionPerformanceSnapshot(
            averageTickMillis = if (lastRuns == 0L) 0.0 else lastConsumed / lastRuns / 1_000_000.0,
            maximumTickMillis = lastMaximum / 1_000_000.0,
            executedTicks = lastRuns,
            skippedTicks = lastSkips,
        )
    }
}
