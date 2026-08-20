package org.edtp.sereniteapot.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Mutable top-level catalog persisted to serenitea_pots.json. */
public class SereniteaPotCatalog {
    private int defaultMaxRadiusChunks;
    private double defaultBudgetMillisPerSecond;
    private double globalBudgetMillisPerSecond;
    private final Map<UUID, SereniteaPotRecord> players;

    public SereniteaPotCatalog() {
        this(SereniteaPotRecord.DEFAULT_MAX_RADIUS_CHUNKS, SereniteaPotRecord.DEFAULT_BUDGET_MILLIS_PER_SECOND,
                100.0, new LinkedHashMap<>());
    }

    public SereniteaPotCatalog(int defaultMaxRadiusChunks, double defaultBudgetMillisPerSecond,
                           double globalBudgetMillisPerSecond) {
        this(defaultMaxRadiusChunks, defaultBudgetMillisPerSecond, globalBudgetMillisPerSecond,
                new LinkedHashMap<>());
    }

    public SereniteaPotCatalog(int defaultMaxRadiusChunks, double defaultBudgetMillisPerSecond,
                           double globalBudgetMillisPerSecond, Map<UUID, SereniteaPotRecord> players) {
        this.defaultMaxRadiusChunks = SereniteaPotRecord.requireValidRadiusChunks(defaultMaxRadiusChunks);
        this.defaultBudgetMillisPerSecond = defaultBudgetMillisPerSecond;
        this.globalBudgetMillisPerSecond = globalBudgetMillisPerSecond;
        this.players = java.util.Objects.requireNonNull(players, "players");
    }

    public int getDefaultMaxRadiusChunks() { return defaultMaxRadiusChunks; }
    public void setDefaultMaxRadiusChunks(int value) {
        defaultMaxRadiusChunks = SereniteaPotRecord.requireValidRadiusChunks(value);
    }
    public double getDefaultBudgetMillisPerSecond() { return defaultBudgetMillisPerSecond; }
    public void setDefaultBudgetMillisPerSecond(double value) { defaultBudgetMillisPerSecond = value; }
    public double getGlobalBudgetMillisPerSecond() { return globalBudgetMillisPerSecond; }
    public void setGlobalBudgetMillisPerSecond(double value) { globalBudgetMillisPerSecond = value; }
    public Map<UUID, SereniteaPotRecord> getPlayers() { return players; }

    public SereniteaPotRecord getOrCreate(UUID owner) {
        return players.computeIfAbsent(owner, key -> new SereniteaPotRecord(key, UUID.randomUUID(), 0,
                defaultMaxRadiusChunks, defaultBudgetMillisPerSecond, true, false));
    }

}
