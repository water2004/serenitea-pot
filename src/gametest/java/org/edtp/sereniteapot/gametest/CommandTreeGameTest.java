package org.edtp.sereniteapot.gametest;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Set;
import java.util.stream.Collectors;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.translate;

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

    @GameTest
    public void translatesOnTheServerAndFallsBackToEnglish(GameTestHelper helper) {
        var message = message("invitation.teleport_failed", message("travel.denied"));
        helper.assertValueEqual(
                "批准后传送失败，申请仍有效：传送被访问策略拒绝",
                translate("zh_cn", message),
                "Chinese server translation differs");
        helper.assertValueEqual(
                "Teleport failed after approval; the request remains valid: The access policy rejected the teleport",
                translate("en_us", message),
                "English server translation differs");
        helper.assertValueEqual(
                translate("en_us", message),
                translate("unsupported_language", message),
                "Unknown client languages must fall back to English");
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
