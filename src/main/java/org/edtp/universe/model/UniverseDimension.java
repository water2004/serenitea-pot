package org.edtp.universe.model;

import net.casual.arcade.dimensions.level.vanilla.VanillaDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** The three vanilla dimensions used as templates by a personal universe. */
public enum UniverseDimension {
    OVERWORLD("overworld", "minecraft:overworld", VanillaDimension.Overworld),
    NETHER("nether", "minecraft:the_nether", VanillaDimension.Nether),
    END("end", "minecraft:the_end", VanillaDimension.End);

    private final String id;
    private final String vanillaId;
    private final VanillaDimension vanilla;

    UniverseDimension(String id, String vanillaId, VanillaDimension vanilla) {
        this.id = id;
        this.vanillaId = vanillaId;
        this.vanilla = vanilla;
    }

    public String id() {
        return id;
    }

    public String vanillaId() {
        return vanillaId;
    }

    public VanillaDimension vanilla() {
        return vanilla;
    }

    public ResourceKey<Level> vanillaLevelKey() {
        return vanilla.getDimensionKey();
    }

    public static UniverseDimension fromVanillaLevel(ResourceKey<Level> key) {
        for (UniverseDimension dimension : values()) {
            if (Objects.equals(dimension.vanillaLevelKey(), key)) {
                return dimension;
            }
        }
        return null;
    }

    public static UniverseDimension fromId(String id) {
        for (UniverseDimension dimension : values()) {
            if (dimension.id.equals(id)) {
                return dimension;
            }
        }
        return null;
    }
}
