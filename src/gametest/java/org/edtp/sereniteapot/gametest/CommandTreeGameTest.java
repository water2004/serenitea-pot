package org.edtp.sereniteapot.gametest;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Set;
import java.util.stream.Collectors;

public final class CommandTreeGameTest {
    @GameTest
    public void registersPublicCommandTree(GameTestHelper helper) {
        CommandNode<CommandSourceStack> root = helper.getLevel().getServer().getCommands()
                .getDispatcher().getRoot().getChild("sereniteapot");
        helper.assertTrue(root != null, "Missing /sereniteapot command");
        assertChildren(helper, root,
                "create", "enter", "leave", "unfreeze", "request", "requests",
                "approve", "deny", "delete", "admin");
        assertChildren(helper, child(root, "admin"),
                "enable", "disable", "max-radius", "budget", "global-budget",
                "status", "perf", "delete");
        assertChildren(helper, child(root, "approve", "player"), "request-id");
        assertChildren(helper, child(root, "delete"), "confirm");
        assertChildren(helper, child(root, "admin", "max-radius", "player"), "radius");
        assertChildren(helper, child(root, "admin", "delete", "player"), "confirm");
        helper.succeed();
    }

    private static CommandNode<CommandSourceStack> child(
            CommandNode<CommandSourceStack> parent, String... path) {
        CommandNode<CommandSourceStack> current = parent;
        for (String name : path) {
            current = current.getChild(name);
            if (current == null) {
                throw new IllegalStateException("Missing command path node: " + name);
            }
        }
        return current;
    }

    private static void assertChildren(
            GameTestHelper helper, CommandNode<CommandSourceStack> parent, String... expected) {
        Set<String> actual = parent.getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toSet());
        helper.assertValueEqual(Set.of(expected), actual, "Unexpected command children under " + parent.getName());
    }
}
