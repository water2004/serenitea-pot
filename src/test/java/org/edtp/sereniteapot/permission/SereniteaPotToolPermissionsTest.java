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

class SereniteaPotToolPermissionsTest {
    @Test
    void onlyOwnerInsideOwnPotReceivesScopedToolAccess() {
        UUID owner = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        var pot = SereniteaPotLevelKeys.key(owner, 3, SereniteaPotDimension.OVERWORLD);
        ResourceKey<Level> publicWorld = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));

        assertTrue(SereniteaPotToolPermissions.ownsPot(owner, pot));
        assertFalse(SereniteaPotToolPermissions.ownsPot(visitor, pot));
        assertFalse(SereniteaPotToolPermissions.ownsPot(owner, publicWorld));
    }
}
