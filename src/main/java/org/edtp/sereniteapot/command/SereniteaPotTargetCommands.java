package org.edtp.sereniteapot.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.Locale;
import java.util.UUID;

import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

/** 主人和管理员都可复用的、作用于一个指定尘歌壶的命令。 */
final class SereniteaPotTargetCommands {
    private SereniteaPotTargetCommands() {
    }

    static int delete(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotDeletionService.Result result = SereniteaPotDeletionService.deleteAndReset(
                context.getSource().getServer(), owner);
        return result == SereniteaPotDeletionService.Success.INSTANCE
                ? success(context, MessageKey.COMMAND_DELETE_SUCCESS)
                : failure(context, ((SereniteaPotDeletionService.Rejected) result).reason());
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
                record.getBudgetMillisPerTick(),
                record.getDifficulty().getSerializedName(),
                progressValue);
    }
}
