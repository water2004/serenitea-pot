package org.edtp.sereniteapot.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Mutable top-level catalog persisted to serenitea_pots.json. */
public class SereniteaPotCatalog {
    public static final double DEFAULT_GLOBAL_BUDGET_MILLIS_PER_TICK = 20.0;

    private int defaultMaxRadiusChunks;
    private double defaultBudgetMillisPerTick;
    private double globalBudgetMillisPerTick;
    private final Map<UUID, SereniteaPotRecord> players;

    public SereniteaPotCatalog() {
        this(
            SereniteaPotRecord.DEFAULT_MAX_RADIUS_CHUNKS,
            SereniteaPotRecord.DEFAULT_BUDGET_MILLIS_PER_TICK,
            DEFAULT_GLOBAL_BUDGET_MILLIS_PER_TICK
        );
    }

    public SereniteaPotCatalog(int defaultMaxRadiusChunks, double defaultBudgetMillisPerTick,
                           double globalBudgetMillisPerTick) {
        this.defaultMaxRadiusChunks = SereniteaPotRecord.requireValidRadiusChunks(defaultMaxRadiusChunks);
        this.defaultBudgetMillisPerTick = SereniteaPotRecord.requireValidBudgetMillisPerTick(defaultBudgetMillisPerTick);
        this.globalBudgetMillisPerTick = SereniteaPotRecord.requireValidBudgetMillisPerTick(globalBudgetMillisPerTick);
        this.players = new LinkedHashMap<>();
    }

    public int getDefaultMaxRadiusChunks() { return defaultMaxRadiusChunks; }
    public void setDefaultMaxRadiusChunks(int value) {
        defaultMaxRadiusChunks = SereniteaPotRecord.requireValidRadiusChunks(value);
    }
    public double getDefaultBudgetMillisPerTick() { return defaultBudgetMillisPerTick; }
    public void setDefaultBudgetMillisPerTick(double value) {
        defaultBudgetMillisPerTick = SereniteaPotRecord.requireValidBudgetMillisPerTick(value);
    }
    public double getGlobalBudgetMillisPerTick() { return globalBudgetMillisPerTick; }
    public void setGlobalBudgetMillisPerTick(double value) {
        globalBudgetMillisPerTick = SereniteaPotRecord.requireValidBudgetMillisPerTick(value);
    }
    public Map<UUID, SereniteaPotRecord> getPlayers() { return players; }

    public SereniteaPotRecord getOrCreate(UUID owner) {
        return players.computeIfAbsent(owner, ignored -> new SereniteaPotRecord(UUID.randomUUID(), 0,
                defaultMaxRadiusChunks, defaultBudgetMillisPerTick, true, false));
    }

}
