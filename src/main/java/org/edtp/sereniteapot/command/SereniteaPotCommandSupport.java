package org.edtp.sereniteapot.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;

import java.util.UUID;

final class SereniteaPotCommandSupport {
    private SereniteaPotCommandSupport() {
    }

    static UUID profile(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, argument);
        if (profiles.size() != 1) {
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        }
        return profiles.iterator().next().id();
    }

    static int deletePot(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotDeletionService.Result result = SereniteaPotDeletionService.deleteAndReset(
                context.getSource().getServer(), owner);
        return result == SereniteaPotDeletionService.Success.INSTANCE
                ? success(context, "尘歌壶已卸载并永久删除")
                : failure(context, ((SereniteaPotDeletionService.Rejected) result).reason());
    }

    static int success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    static int failure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
