package org.edtp.sereniteapot.permission;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.mixin.accessor.CommandNodeAccessor;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Extends selected world-local vanilla commands to the owner of the current pot.
 * The original command requirement remains authoritative everywhere else.
 */
public final class SereniteaPotVanillaCommandPermissions {
    // This set is a security boundary: every branch of each command must operate only on
    // CommandSourceStack#getLevel and must not target players, other dimensions, or server state.
    private static final Set<String> WORLD_LOCAL_COMMANDS = Set.of(
            "difficulty",
            "fill",
            "fillbiome",
            "place",
            "setblock",
            "summon"
    );

    private SereniteaPotVanillaCommandPermissions() {
    }

    public static void register() {
        // Run after every mod has finished registering commands, so a later callback cannot
        // accidentally replace the scoped requirement.
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                extendRequirements(server.getCommands().getDispatcher()));
    }

    private static void extendRequirements(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String command : WORLD_LOCAL_COMMANDS) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(command);
            if (node != null) {
                extendRequirement(node);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void extendRequirement(CommandNode<CommandSourceStack> node) {
        Predicate<CommandSourceStack> original = node.getRequirement();
        CommandNodeAccessor<CommandSourceStack> accessor =
                (CommandNodeAccessor<CommandSourceStack>) (Object) node;
        accessor.sereniteaPot$setRequirement(source -> original.test(source) || ownsCurrentPot(source));
    }

    private static boolean ownsCurrentPot(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null || source.getLevel() == null) {
            return false;
        }
        return ownsCommandLevel(
                player.getUUID(),
                player.level().dimension(),
                source.getLevel().dimension());
    }

    static boolean ownsCommandLevel(
            UUID player,
            ResourceKey<Level> playerDimension,
            ResourceKey<Level> commandDimension) {
        return playerDimension.equals(commandDimension)
                && SereniteaPotToolPermissions.ownsPot(player, commandDimension);
    }
}
