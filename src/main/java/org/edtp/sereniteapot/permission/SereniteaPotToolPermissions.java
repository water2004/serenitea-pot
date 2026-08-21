package org.edtp.sereniteapot.permission;

import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;

import java.util.UUID;

/**
 * Grants building-tool permissions to a player only while they physically occupy
 * their own Serenitea Pot.
 *
 * <p>The handlers deliberately return {@code null}/{@link TriState#DEFAULT}
 * everywhere else. That distinction is important: it preserves the server's
 * existing OP, LuckPerms and mod configuration decisions outside the pot.</p>
 */
public final class SereniteaPotToolPermissions {
    private static final String WORLD_EDIT_NAMESPACE = "worldedit";
    private static final String LEGACY_WORLD_EDIT_PREFIX = "worldedit.";
    private static final String AXIOM_ALL = "axiom.all";

    private SereniteaPotToolPermissions() {
    }

    public static void register() {
        // WorldEdit 7.4.5 uses Fabric's typed v1 nodes (for example worldedit:region.set).
        PermissionEvents.ON_REQUEST.register(SereniteaPotToolPermissions::worldEditPermission);

        // WorldEdit 7.4.4 and Axiom 5.5 query the legacy string API. Axiom expands
        // axiom.all itself, while WorldEdit asks for each worldedit.* node individually.
        PermissionCheckEvent.EVENT.register(SereniteaPotToolPermissions::legacyPermission);
    }

    private static <T> T worldEditPermission(PermissionContext context, PermissionNode<T> permission) {
        if (!WORLD_EDIT_NAMESPACE.equals(permission.key().getNamespace()) || !ownsCurrentPot(context)) {
            return null;
        }
        try {
            return permission.cast(Boolean.TRUE);
        } catch (IllegalArgumentException ignored) {
            // WorldEdit currently exposes boolean nodes. Leave any future typed node to its owner.
            return null;
        }
    }

    private static TriState legacyPermission(
            net.minecraft.commands.SharedSuggestionProvider source,
            String permission) {
        if (!(AXIOM_ALL.equals(permission) || permission.startsWith(LEGACY_WORLD_EDIT_PREFIX))
                || !(source instanceof CommandSourceStack commandSource)) {
            return TriState.DEFAULT;
        }
        Entity entity = commandSource.getEntity();
        return entity instanceof ServerPlayer player
                && ownsPot(player.getUUID(), player.level().dimension())
                ? TriState.TRUE
                : TriState.DEFAULT;
    }

    private static boolean ownsCurrentPot(PermissionContext context) {
        Entity entity = context.get(PermissionContext.ENTITY);
        return entity instanceof ServerPlayer player
                && ownsPot(player.getUUID(), player.level().dimension());
    }

    static boolean ownsPot(UUID player, ResourceKey<Level> dimension) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(dimension);
        return identity != null && identity.owner().equals(player);
    }
}
