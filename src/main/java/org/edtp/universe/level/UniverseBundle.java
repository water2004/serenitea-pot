package org.edtp.universe.level;

import net.casual.arcade.dimensions.level.CustomLevel;
import org.edtp.universe.model.UniverseDimension;

import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

public record UniverseBundle(
    UUID owner,
    long generation,
    EnumMap<UniverseDimension, CustomLevel> levels
) {
    public UniverseBundle {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(levels, "levels");
    }

    public CustomLevel get(UniverseDimension dimension) {
        return Objects.requireNonNull(
            levels.get(dimension),
            () -> "Missing " + dimension + " in universe " + owner + " generation " + generation
        );
    }
}
