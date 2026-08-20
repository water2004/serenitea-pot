package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.Locale;
import java.util.UUID;

import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.deletePot;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotOwnerCommands {
    private SereniteaPotOwnerCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.executes(SereniteaPotOwnerCommands::statusSelf);
        route(root, literal("unfreeze").executes(SereniteaPotOwnerCommands::unfreeze));
        route(root,
                literal("delete"),
                literal("confirm").executes(SereniteaPotOwnerCommands::delete));
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
        return deletePot(context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int statusSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return status(context, context.getSource().getPlayerOrException().getUUID());
    }

    static int status(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return failure(context, MessageKey.ERROR_TARGET_NO_CONFIG);
        Double progress = SereniteaPotCreationService.progress(owner);
        String progressValue = progress == null
                ? "-"
                : String.format(Locale.ROOT, "%.1f%%", progress * 100.0);
        return success(context, MessageKey.COMMAND_STATUS,
                owner,
                record.exists(),
                SereniteaPotManager.loaded(owner) != null,
                record.isEnabled(),
                record.isFrozen(),
                record.getMaxRadiusChunks(),
                record.getBudgetMillisPerSecond(),
                progressValue);
    }
}
