package org.edtp.sereniteapot.command.scope;

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

class SereniteaPotCommandScopeTest {
    @Test
    void containsExactlyOnePotGenerationAcrossItsThreeDimensions() {
        UUID owner = UUID.randomUUID();
        SereniteaPotCommandScope scope = new SereniteaPotCommandScope(owner, 4L);

        for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
            assertTrue(scope.contains(SereniteaPotLevelKeys.key(owner, 4L, dimension)));
        }
        assertFalse(scope.contains(SereniteaPotLevelKeys.key(owner, 5L, SereniteaPotDimension.OVERWORLD)));
        assertFalse(scope.contains(SereniteaPotLevelKeys.key(
                UUID.randomUUID(), 4L, SereniteaPotDimension.OVERWORLD)));
        assertFalse(scope.contains(ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld")
        )));
    }
}
