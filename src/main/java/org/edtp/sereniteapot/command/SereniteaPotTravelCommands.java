package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;

import java.util.UUID;

import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.profile;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotTravelCommands {
    private static final String OWNER_ARGUMENT = "owner";

    private SereniteaPotTravelCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("enter")
                .executes(context -> enter(context, context.getSource().getPlayerOrException().getUUID()))
                .then(Commands.argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotTravelCommands::enterTarget)));
        root.then(Commands.literal("leave").executes(SereniteaPotTravelCommands::leave));
    }

    private static int enterTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return enter(context, profile(context, OWNER_ARGUMENT));
    }

    private static int enter(CommandContext<CommandSourceStack> context, UUID owner) throws CommandSyntaxException {
        SereniteaPotTravelService.Result result = SereniteaPotTravelService.enter(
                context.getSource().getPlayerOrException(), owner);
        return result == SereniteaPotTravelService.Success.INSTANCE
                ? success(context, "已进入尘歌壶")
                : failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SereniteaPotTravelService.Result result = SereniteaPotTravelService.leave(
                context.getSource().getPlayerOrException());
        return result == SereniteaPotTravelService.Success.INSTANCE
                ? success(context, "已离开尘歌壶")
                : failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
    }
}
