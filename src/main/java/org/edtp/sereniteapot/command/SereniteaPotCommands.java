package org.edtp.sereniteapot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class SereniteaPotCommands {
    private static final String ROOT_COMMAND = "sereniteapot";

    private SereniteaPotCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_COMMAND);
        SereniteaPotOwnerCommands.register(root);
        SereniteaPotCreateCommand.register(root);
        SereniteaPotTravelCommands.register(root);
        SereniteaPotInvitationCommands.register(root);
        SereniteaPotAdminCommands.register(root);
        dispatcher.register(root);
    }
}
