package org.edtp.sereniteapot.level;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.model.SereniteaPotDimension;

import java.util.UUID;

public final class SereniteaPotLevelKeys {
    private static final String PREFIX = "pot";

    private SereniteaPotLevelKeys() {
    }

    public static ResourceKey<Level> key(UUID owner, long generation, SereniteaPotDimension dimension) {
        return ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(
                SereniteaPotMod.MOD_ID,
                PREFIX + "/" + owner + "/g" + generation + "/" + dimension.id()
            )
        );
    }

    public static Identity identify(ResourceKey<Level> key) {
        Identifier identifier = key.identifier();
        if (!identifier.getNamespace().equals(SereniteaPotMod.MOD_ID)) {
            return null;
        }
        String[] parts = identifier.getPath().split("/");
        if (parts.length != 4 || !parts[0].equals(PREFIX) || !parts[2].startsWith("g")) {
            return null;
        }
        try {
            SereniteaPotDimension dimension = SereniteaPotDimension.fromId(parts[3]);
            if (dimension == null) {
                return null;
            }
            return new Identity(
                UUID.fromString(parts[1]),
                Long.parseLong(parts[2].substring(1)),
                dimension
            );
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public record Identity(UUID owner, long generation, SereniteaPotDimension dimension) {
    }
}
