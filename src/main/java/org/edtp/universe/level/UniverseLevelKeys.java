package org.edtp.universe.level;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.edtp.universe.UniverseMod;
import org.edtp.universe.model.UniverseDimension;

import java.util.UUID;

public final class UniverseLevelKeys {
    private static final String PREFIX = "u";

    private UniverseLevelKeys() {
    }

    public static ResourceKey<Level> key(UUID owner, long generation, UniverseDimension dimension) {
        return ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(
                UniverseMod.MOD_ID,
                PREFIX + "/" + owner + "/g" + generation + "/" + dimension.id()
            )
        );
    }

    public static Identity identify(ResourceKey<Level> key) {
        Identifier identifier = key.identifier();
        if (!identifier.getNamespace().equals(UniverseMod.MOD_ID)) {
            return null;
        }
        String[] parts = identifier.getPath().split("/");
        if (parts.length != 4 || !parts[0].equals(PREFIX) || !parts[2].startsWith("g")) {
            return null;
        }
        try {
            UniverseDimension dimension = UniverseDimension.fromId(parts[3]);
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

    public record Identity(UUID owner, long generation, UniverseDimension dimension) {
    }
}
