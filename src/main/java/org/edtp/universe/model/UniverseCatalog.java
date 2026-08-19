package org.edtp.universe.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Mutable top-level catalog persisted to universes.json. */
public class UniverseCatalog {
    private int defaultMaxRadius;
    private double defaultBudgetMillisPerSecond;
    private double globalBudgetMillisPerSecond;
    private final Map<UUID, UniverseRecord> players;

    public UniverseCatalog() {
        this(UniverseRecord.DEFAULT_MAX_RADIUS, UniverseRecord.DEFAULT_BUDGET_MILLIS_PER_SECOND,
                100.0, new LinkedHashMap<>());
    }

    public UniverseCatalog(int defaultMaxRadius, double defaultBudgetMillisPerSecond,
                           double globalBudgetMillisPerSecond) {
        this(defaultMaxRadius, defaultBudgetMillisPerSecond, globalBudgetMillisPerSecond,
                new LinkedHashMap<>());
    }

    public UniverseCatalog(int defaultMaxRadius, double defaultBudgetMillisPerSecond,
                           double globalBudgetMillisPerSecond, Map<UUID, UniverseRecord> players) {
        this.defaultMaxRadius = defaultMaxRadius;
        this.defaultBudgetMillisPerSecond = defaultBudgetMillisPerSecond;
        this.globalBudgetMillisPerSecond = globalBudgetMillisPerSecond;
        this.players = java.util.Objects.requireNonNull(players, "players");
    }

    public int getDefaultMaxRadius() { return defaultMaxRadius; }
    public void setDefaultMaxRadius(int value) { defaultMaxRadius = value; }
    public double getDefaultBudgetMillisPerSecond() { return defaultBudgetMillisPerSecond; }
    public void setDefaultBudgetMillisPerSecond(double value) { defaultBudgetMillisPerSecond = value; }
    public double getGlobalBudgetMillisPerSecond() { return globalBudgetMillisPerSecond; }
    public void setGlobalBudgetMillisPerSecond(double value) { globalBudgetMillisPerSecond = value; }
    public Map<UUID, UniverseRecord> getPlayers() { return players; }

    public UniverseRecord getOrCreate(UUID owner) {
        return players.computeIfAbsent(owner, key -> new UniverseRecord(key, UUID.randomUUID(), 0,
                defaultMaxRadius, defaultBudgetMillisPerSecond, true, false, false, false));
    }

}
