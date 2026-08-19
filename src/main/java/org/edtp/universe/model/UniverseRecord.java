package org.edtp.universe.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable administrative metadata for one owner's personal universe. */
public class UniverseRecord {
    public static final int DEFAULT_MAX_RADIUS = 64;
    public static final double DEFAULT_BUDGET_MILLIS_PER_SECOND = 25.0;

    private final UUID owner;
    private UUID stateId;
    private long activeGeneration;
    private int maxRadius;
    private double budgetMillisPerSecond;
    private boolean enabled;
    private boolean frozen;
    private boolean stopped;
    private boolean quarantined;
    private final Map<UniverseDimension, UniverseSlotRecord> slots;

    public UniverseRecord(UUID owner) {
        this(owner, UUID.randomUUID(), 0, DEFAULT_MAX_RADIUS, DEFAULT_BUDGET_MILLIS_PER_SECOND,
                true, false, false, false, new EnumMap<>(UniverseDimension.class));
    }

    public UniverseRecord(UUID owner, UUID stateId, long activeGeneration, int maxRadius,
                          double budgetMillisPerSecond, boolean enabled, boolean frozen,
                          boolean stopped, boolean quarantined) {
        this(owner, stateId, activeGeneration, maxRadius, budgetMillisPerSecond, enabled, frozen,
                stopped, quarantined, new EnumMap<>(UniverseDimension.class));
    }

    public UniverseRecord(UUID owner, UUID stateId, long activeGeneration, int maxRadius,
                          double budgetMillisPerSecond, boolean enabled, boolean frozen,
                          boolean stopped, boolean quarantined,
                          Map<UniverseDimension, UniverseSlotRecord> slots) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.stateId = Objects.requireNonNull(stateId, "stateId");
        this.activeGeneration = activeGeneration;
        this.maxRadius = maxRadius;
        this.budgetMillisPerSecond = budgetMillisPerSecond;
        this.enabled = enabled;
        this.frozen = frozen;
        this.stopped = stopped;
        this.quarantined = quarantined;
        this.slots = Objects.requireNonNull(slots, "slots");
    }

    public UUID getOwner() {
        return owner;
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

    public int getMaxRadius() {
        return maxRadius;
    }

    public void setMaxRadius(int maxRadius) {
        this.maxRadius = maxRadius;
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

    public boolean isStopped() {
        return stopped;
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    public boolean isQuarantined() {
        return quarantined;
    }

    public void setQuarantined(boolean quarantined) {
        this.quarantined = quarantined;
    }

    public boolean exists() {
        return activeGeneration > 0;
    }

    public Map<UniverseDimension, UniverseSlotRecord> getSlots() {
        return slots;
    }

}
