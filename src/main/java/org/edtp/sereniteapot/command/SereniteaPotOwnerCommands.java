package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
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
        if (record == null || !record.exists()) return failure(context, "你还没有尘歌壶");
        if (!record.isFrozen()) {
            return success(context, "你的尘歌壶 tick 当前没有冻结");
        }
        record.setFrozen(false);
        SereniteaPotManager.saveCatalog();
        return success(context, "已解除尘歌壶的自动冻结；预算债务仍会继续限制 tick 频率");
    }

    private static int delete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return deletePot(context, context.getSource().getPlayerOrException().getUUID());
    }

    private static int statusSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return status(context, context.getSource().getPlayerOrException().getUUID());
    }

    static int status(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return failure(context, "没有该玩家的尘歌壶配置");
        Double progress = SereniteaPotCreationService.progress(owner);
        String creation = progress == null
                ? ""
                : ", 创建进度=" + String.format(Locale.ROOT, "%.1f", progress * 100.0) + "%";
        return success(context,
                "owner=" + owner + ", 存在=" + record.exists()
                        + ", 已加载=" + (SereniteaPotManager.loaded(owner) != null)
                        + ", enabled=" + record.isEnabled() + ", frozen=" + record.isFrozen()
                        + ", maxRadiusChunks=" + record.getMaxRadiusChunks()
                        + ", budget=" + record.getBudgetMillisPerSecond() + "ms/s" + creation);
    }
}
