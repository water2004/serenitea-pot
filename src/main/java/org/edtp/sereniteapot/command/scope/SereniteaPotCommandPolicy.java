package org.edtp.sereniteapot.command.scope;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import org.edtp.sereniteapot.mixin.accessor.CommandNodeAccessor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Defines which Vanilla commands have a meaningful pot-local interpretation.
 *
 * <p>Entity and dimension isolation is implemented centrally by the scope mixins. This class
 * only grants those scoped commands to the pot owner and hides known Vanilla server-global
 * commands from every player while they are inside a pot. Third-party commands are never
 * granted here, and custom selector types remain the owning mod's responsibility.</p>
 */
public final class SereniteaPotCommandPolicy {
    // Security boundary: adding a root here grants it to a non-OP pot owner. Every added
    // command must operate through the scoped source, Vanilla selectors, or dimensions.
    private static final Set<String> OWNER_SCOPED_COMMANDS = Set.of(
            "attribute", "clear", "clone", "damage", "data", "dialog", "difficulty",
            "effect", "enchant", "execute", "experience", "fill", "fillbiome",
            "forceload", "gamerule", "give", "item", "kill", "locate",
            "loot", "particle", "place", "playsound", "recipe", "ride", "rotate",
            "setblock", "setworldspawn", "spawnpoint", "spectate", "spreadplayers",
            "stopsound", "summon", "swing", "tag", "teleport", "tellraw", "time", "title", "tp",
            "waypoint", "weather", "xp"
    );

    // Real operators already have these commands, so they must be explicitly suppressed
    // while the operator is physically inside a pot.
    private static final Set<String> SERVER_GLOBAL_COMMANDS = Set.of(
            "advancement", "ban", "ban-ip", "banlist", "bossbar", "chase", "datapack",
            "debug", "debugconfig", "debugmobspawning", "debugpath", "defaultgamemode",
            "deop", "fetchprofile", "function", "jfr", "kick", "me", "op", "pardon",
            "pardon-ip", "perf", "publish", "raid", "random", "reload", "return",
            "save-all", "save-off", "save-on", "say", "schedule", "scoreboard",
            "serverpack", "setidletimeout", "spawn_armor_trims", "stop", "stopwatch",
            "team", "teammsg", "tm", "test", "tick", "transfer", "trigger", "unpublish",
            "warden_spawn_tracker", "whitelist"
    );

    private static final Set<String> EXECUTE_GLOBAL_BRANCHES =
            Set.of("bossbar", "function", "score", "storage");

    private SereniteaPotCommandPolicy() {
    }

    public static void register() {
        // SERVER_STARTING runs after Vanilla and mod command registration, so aliases and
        // optional integrations have their final command tree before policy is applied.
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                apply(server.getCommands().getDispatcher()));
    }

    static void apply(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot();
        for (String command : OWNER_SCOPED_COMMANDS) {
            CommandNode<CommandSourceStack> node = root.getChild(command);
            if (node != null) {
                extendOwnerRequirementRecursively(
                        node,
                        Collections.newSetFromMap(new IdentityHashMap<>())
                );
            }
        }
        for (String command : SERVER_GLOBAL_COMMANDS) {
            CommandNode<CommandSourceStack> node = root.getChild(command);
            if (node != null) {
                denyInsidePot(node);
            }
        }

        // The pot command itself remains available so /sereniteapot leave cannot be trapped.
        // Administrative operations are server-global and must be performed after leaving.
        CommandNode<CommandSourceStack> pot = root.getChild("sereniteapot");
        if (pot != null && pot.getChild("admin") != null) {
            denyInsidePot(pot.getChild("admin"));
        }

        // These branches bypass entity/dimension arguments and mutate global server storage.
        blockNamedDescendants(root.getChild("execute"), EXECUTE_GLOBAL_BRANCHES);
        blockNamedDescendants(root.getChild("data"), Set.of("storage"));
    }

    private static void extendOwnerRequirementRecursively(
            CommandNode<CommandSourceStack> node,
            Set<CommandNode<CommandSourceStack>> visited) {
        if (!visited.add(node)) {
            return;
        }
        replaceRequirement(node, original -> source ->
                original.test(source) || SereniteaPotCommandScope.isPhysicalOwner(source));
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            extendOwnerRequirementRecursively(child, visited);
        }
    }

    private static void blockNamedDescendants(
            CommandNode<CommandSourceStack> node,
            Set<String> blockedNames) {
        if (node == null) {
            return;
        }
        if (blockedNames.contains(node.getName())) {
            denyInsidePot(node);
            return;
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            blockNamedDescendants(child, blockedNames);
        }
    }

    private static void denyInsidePot(CommandNode<CommandSourceStack> node) {
        replaceRequirement(node, original -> source ->
                original.test(source)
                        && !SereniteaPotCommandScope.isPhysicalPlayerInsidePot(source));
    }

    @SuppressWarnings("unchecked")
    private static void replaceRequirement(
            CommandNode<CommandSourceStack> node,
            java.util.function.UnaryOperator<Predicate<CommandSourceStack>> replacement) {
        Predicate<CommandSourceStack> original = node.getRequirement();
        ((CommandNodeAccessor<CommandSourceStack>) (Object) node)
                .sereniteaPot$setRequirement(replacement.apply(original));
    }
}
