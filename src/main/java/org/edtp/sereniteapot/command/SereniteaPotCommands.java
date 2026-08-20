package org.edtp.sereniteapot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 尘歌壶命令树的唯一入口。
 *
 * <p>各功能类只向同一个 {@code /sereniteapot} 根节点添加分支，最后由这里统一向
 * Brigadier 注册一次，避免多个类各自注册同名根命令并互相覆盖。</p>
 */
public final class SereniteaPotCommands {
    private static final String ROOT_COMMAND = "sereniteapot";

    private SereniteaPotCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_COMMAND);
        // register(...) 在这里的含义都是“向 root 添加子节点”，并不会再次注册根命令。
        SereniteaPotOwnerCommands.register(root);
        SereniteaPotCreateCommand.register(root);
        SereniteaPotTravelCommands.register(root);
        SereniteaPotInvitationCommands.register(root);
        SereniteaPotAdminCommands.register(root);
        dispatcher.register(root);
    }
}
