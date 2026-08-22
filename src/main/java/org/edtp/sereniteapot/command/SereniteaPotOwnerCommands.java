package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.Difficulty;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotRecord;

import java.util.UUID;

import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotOwnerCommands {
    private SereniteaPotOwnerCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.executes(SereniteaPotOwnerCommands::statusSelf);
        route(root, literal("unfreeze").executes(SereniteaPotOwnerCommands::unfreeze));
        LiteralArgumentBuilder<CommandSourceStack> difficulty = literal("difficulty");
        for (Difficulty value : Difficulty.values()) {
            route(difficulty, literal(value.getSerializedName())
                    .executes(context -> setDifficulty(context, value)));
        }
        route(root, difficulty);
        route(root,
                literal("delete"),
                literal("confirm").executes(SereniteaPotOwnerCommands::delete));
    }

    private static int setDifficulty(
            CommandContext<CommandSourceStack> context,
            Difficulty difficulty) throws CommandSyntaxException {
        return SereniteaPotTargetCommands.setDifficulty(
                context,
                context.getSource().getPlayerOrException().getUUID(),
                difficulty,
                MessageKey.ERROR_SELF_NO_POT);
    }

    private static int unfreeze(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID owner = context.getSource().getPlayerOrException().getUUID();
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null || !record.exists()) return failure(context, MessageKey.ERROR_SELF_NO_POT);
        if (!record.isFrozen()) {
            return success(context, MessageKey.COMMAND_UNFREEZE_NOT_FROZEN);
        }
        record.setFrozen(false);
        SereniteaPotManager.saveCatalog();
        return success(context, MessageKey.COMMAND_UNFREEZE_SUCCESS);
    }

    private static int delete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return SereniteaPotTargetCommands.delete(
                context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int statusSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return SereniteaPotTargetCommands.status(
                context, context.getSource().getPlayerOrException().getUUID());
    }
}
