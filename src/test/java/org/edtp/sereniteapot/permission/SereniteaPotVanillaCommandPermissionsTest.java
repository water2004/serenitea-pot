package org.edtp.sereniteapot.permission;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SereniteaPotVanillaCommandPermissionsTest {
    @Test
    void requiresOwnerAndCommandSourceToShareTheSamePotLevel() {
        UUID owner = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        ResourceKey<Level> pot = SereniteaPotLevelKeys.key(owner, 4, SereniteaPotDimension.OVERWORLD);
        ResourceKey<Level> potNether = SereniteaPotLevelKeys.key(owner, 4, SereniteaPotDimension.NETHER);
        ResourceKey<Level> publicWorld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        assertTrue(SereniteaPotVanillaCommandPermissions.ownsCommandLevel(owner, pot, pot));
        assertFalse(SereniteaPotVanillaCommandPermissions.ownsCommandLevel(visitor, pot, pot));
        assertFalse(SereniteaPotVanillaCommandPermissions.ownsCommandLevel(owner, pot, potNether));
        assertFalse(SereniteaPotVanillaCommandPermissions.ownsCommandLevel(owner, pot, publicWorld));
        assertFalse(SereniteaPotVanillaCommandPermissions.ownsCommandLevel(owner, publicWorld, publicWorld));
    }
}
