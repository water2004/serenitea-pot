package org.edtp.sereniteapot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.playerName;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotTravelCommands {
    private static final String OWNER_ARGUMENT = "owner";

    private SereniteaPotTravelCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        route(root,
                literal("enter")
                        .executes(context -> enter(
                                context, context.getSource().getPlayerOrException().getUUID())),
                argument(OWNER_ARGUMENT, StringArgumentType.word())
                        .suggests(SereniteaPotCommandSupport::suggestEnterOwners)
                        .executes(SereniteaPotTravelCommands::enterTarget));
        route(root, literal("leave").executes(SereniteaPotTravelCommands::leave));
    }

    private static int enterTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, OWNER_ARGUMENT);
        // Existing pots are identified locally: offline owners need no profile lookup,
        // and their UUID remains usable if Vanilla's name cache no longer contains them.
        UUID owner = SereniteaPotManager.catalog().getPlayers().keySet().stream()
            .filter(id -> id.toString().equalsIgnoreCase(name)
                || playerName(context.getSource().getServer(), id).equalsIgnoreCase(name))
            .findFirst().orElse(null);
        return owner == null ? failure(context, MessageKey.TRAVEL_TARGET_NO_POT) : enter(context, owner);
    }

    private static int enter(CommandContext<CommandSourceStack> context, UUID owner) throws CommandSyntaxException {
        PlayerStateManager.whenReady(context.getSource().getPlayerOrException(), player -> {
            SereniteaPotTravelService.Result result = SereniteaPotTravelService.enter(player, owner);
            if (result == SereniteaPotTravelService.Success.INSTANCE) success(context, MessageKey.COMMAND_ENTER_SUCCESS);
            else failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
        });
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PlayerStateManager.whenReadyToLeave(context.getSource().getPlayerOrException(), player -> {
            SereniteaPotTravelService.Result result = SereniteaPotTravelService.leave(player);
            if (result == SereniteaPotTravelService.Success.INSTANCE) success(context, MessageKey.COMMAND_LEAVE_SUCCESS);
            else failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
        });
        return 1;
    }
}
