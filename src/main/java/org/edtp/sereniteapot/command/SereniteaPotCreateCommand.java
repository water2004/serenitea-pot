package org.edtp.sereniteapot.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotCreateCommand {
    private static final String RADIUS_ARGUMENT = "radius";

    private SereniteaPotCreateCommand() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        route(root,
                literal("create"),
                argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0))
                        .executes(SereniteaPotCreateCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SereniteaPotCreationService.RequestResult result = SereniteaPotCreationService.request(
                player, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT));
        if (result instanceof SereniteaPotCreationService.Accepted accepted) {
            return success(context,
                    "command.create.accepted", accepted.chunkCount(), accepted.generation());
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }
}
