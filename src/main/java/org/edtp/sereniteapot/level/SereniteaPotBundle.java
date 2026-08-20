package org.edtp.sereniteapot.level;

import net.casual.arcade.dimensions.level.CustomLevel;
import org.edtp.sereniteapot.model.SereniteaPotDimension;

import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

public record SereniteaPotBundle(
    UUID owner,
    long generation,
    EnumMap<SereniteaPotDimension, CustomLevel> levels
) {
    public SereniteaPotBundle {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(levels, "levels");
    }

    public CustomLevel get(SereniteaPotDimension dimension) {
        return Objects.requireNonNull(
            levels.get(dimension),
            () -> "Missing " + dimension + " in Serenitea Pot " + owner + " generation " + generation
        );
    }
}
