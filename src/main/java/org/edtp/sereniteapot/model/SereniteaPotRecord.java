package org.edtp.sereniteapot.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable administrative metadata for one owner's personal Serenitea Pot. */
public class SereniteaPotRecord {
    public static final int DEFAULT_MAX_RADIUS_CHUNKS = 4;
    public static final int MAX_RADIUS_CHUNKS = 256;
    public static final double DEFAULT_BUDGET_MILLIS_PER_SECOND = 25.0;

    private UUID stateId;
    private long activeGeneration;
    private int maxRadiusChunks;
    private double budgetMillisPerSecond;
    private boolean enabled;
    private boolean frozen;
    private final Map<SereniteaPotDimension, SereniteaPotSlotRecord> slots;

    public SereniteaPotRecord(UUID stateId, long activeGeneration, int maxRadiusChunks,
                          double budgetMillisPerSecond, boolean enabled, boolean frozen) {
        this.stateId = Objects.requireNonNull(stateId, "stateId");
        this.activeGeneration = activeGeneration;
        this.maxRadiusChunks = requireValidRadiusChunks(maxRadiusChunks);
        this.budgetMillisPerSecond = budgetMillisPerSecond;
        this.enabled = enabled;
        this.frozen = frozen;
        this.slots = new EnumMap<>(SereniteaPotDimension.class);
    }

    public UUID getStateId() {
        return stateId;
    }

    public void setStateId(UUID stateId) {
        this.stateId = Objects.requireNonNull(stateId, "stateId");
    }

    public long getActiveGeneration() {
        return activeGeneration;
    }

    public void setActiveGeneration(long activeGeneration) {
        this.activeGeneration = activeGeneration;
    }

    public int getMaxRadiusChunks() {
        return maxRadiusChunks;
    }

    public void setMaxRadiusChunks(int maxRadiusChunks) {
        this.maxRadiusChunks = requireValidRadiusChunks(maxRadiusChunks);
    }

    public double getBudgetMillisPerSecond() {
        return budgetMillisPerSecond;
    }

    public void setBudgetMillisPerSecond(double budgetMillisPerSecond) {
        this.budgetMillisPerSecond = budgetMillisPerSecond;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public boolean exists() {
        return activeGeneration > 0;
    }

    public Map<SereniteaPotDimension, SereniteaPotSlotRecord> getSlots() {
        return slots;
    }

    static int requireValidRadiusChunks(int radiusChunks) {
        if (radiusChunks < 0 || radiusChunks > MAX_RADIUS_CHUNKS) {
            throw new IllegalArgumentException(
                "Chunk radius must be between 0 and " + MAX_RADIUS_CHUNKS
            );
        }
        return radiusChunks;
    }

}
