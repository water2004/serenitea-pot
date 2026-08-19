package org.edtp.universe.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Mutable top-level catalog persisted to universes.json. */
public class UniverseCatalog {
    private int defaultMaxRadiusChunks;
    private double defaultBudgetMillisPerSecond;
    private double globalBudgetMillisPerSecond;
    private final Map<UUID, UniverseRecord> players;

    public UniverseCatalog() {
        this(UniverseRecord.DEFAULT_MAX_RADIUS_CHUNKS, UniverseRecord.DEFAULT_BUDGET_MILLIS_PER_SECOND,
                100.0, new LinkedHashMap<>());
    }

    public UniverseCatalog(int defaultMaxRadiusChunks, double defaultBudgetMillisPerSecond,
                           double globalBudgetMillisPerSecond) {
        this(defaultMaxRadiusChunks, defaultBudgetMillisPerSecond, globalBudgetMillisPerSecond,
                new LinkedHashMap<>());
    }

    public UniverseCatalog(int defaultMaxRadiusChunks, double defaultBudgetMillisPerSecond,
                           double globalBudgetMillisPerSecond, Map<UUID, UniverseRecord> players) {
        this.defaultMaxRadiusChunks = UniverseRecord.requireValidRadiusChunks(defaultMaxRadiusChunks);
        this.defaultBudgetMillisPerSecond = defaultBudgetMillisPerSecond;
        this.globalBudgetMillisPerSecond = globalBudgetMillisPerSecond;
        this.players = java.util.Objects.requireNonNull(players, "players");
    }

    public int getDefaultMaxRadiusChunks() { return defaultMaxRadiusChunks; }
    public void setDefaultMaxRadiusChunks(int value) {
        defaultMaxRadiusChunks = UniverseRecord.requireValidRadiusChunks(value);
    }
    public double getDefaultBudgetMillisPerSecond() { return defaultBudgetMillisPerSecond; }
    public void setDefaultBudgetMillisPerSecond(double value) { defaultBudgetMillisPerSecond = value; }
    public double getGlobalBudgetMillisPerSecond() { return globalBudgetMillisPerSecond; }
    public void setGlobalBudgetMillisPerSecond(double value) { globalBudgetMillisPerSecond = value; }
    public Map<UUID, UniverseRecord> getPlayers() { return players; }

    public UniverseRecord getOrCreate(UUID owner) {
        return players.computeIfAbsent(owner, key -> new UniverseRecord(key, UUID.randomUUID(), 0,
                defaultMaxRadiusChunks, defaultBudgetMillisPerSecond, true, false, false, false));
    }

}
