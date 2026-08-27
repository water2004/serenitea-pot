package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.i18n.SereniteaPotTranslations.Message;
import org.edtp.sereniteapot.level.SereniteaPotAccessPolicy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/** 命令实现之间共享的少量 Brigadier 组装与反馈工具。 */
final class SereniteaPotCommandSupport {
    private SereniteaPotCommandSupport() {
    }

    /**
     * 将参数按从左到右的顺序组装成一条命令路径。
     *
     * <p>例如 {@code route(root, a, b, c)} 等价于先执行
     * {@code b.then(c)}、再执行 {@code a.then(b)}、最后执行
     * {@code root.then(a)}。这里只隐藏 Brigadier 重复的树节点连接，不负责解析、
     * 权限判断或执行命令。</p>
     */
    static void route(
            ArgumentBuilder<CommandSourceStack, ?> parent,
            ArgumentBuilder<CommandSourceStack, ?> child) {
        parent.then(child);
    }

    static void route(
            ArgumentBuilder<CommandSourceStack, ?> parent,
            ArgumentBuilder<CommandSourceStack, ?> child,
            ArgumentBuilder<CommandSourceStack, ?> grandchild) {
        child.then(grandchild);
        parent.then(child);
    }

    static void route(
            ArgumentBuilder<CommandSourceStack, ?> parent,
            ArgumentBuilder<CommandSourceStack, ?> child,
            ArgumentBuilder<CommandSourceStack, ?> grandchild,
            ArgumentBuilder<CommandSourceStack, ?> greatGrandchild) {
        grandchild.then(greatGrandchild);
        child.then(grandchild);
        parent.then(child);
    }

    static UUID profile(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, argument);
        if (profiles.size() != 1) {
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        }
        return profiles.iterator().next().id();
    }

    /**
     * 提示当前确实能接待访客的壶主。申请和进入共用这一定义，执行时仍会重新鉴权。
     */
    static CompletableFuture<Suggestions> suggestAvailableOwners(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        var source = context.getSource();
        var server = source.getServer();
        ServerPlayer requester = source.getPlayer();
        return SharedSuggestionProvider.suggest(
                server.getPlayerList().getPlayers().stream()
                        .filter(player -> requester == null || !player.getUUID().equals(requester.getUUID()))
                        .filter(player -> SereniteaPotAccessPolicy.isRealOwnerInside(server, player.getUUID()))
                        .map(ServerPlayer::getScoreboardName),
                builder);
    }

    static int success(CommandContext<CommandSourceStack> context, MessageKey key, Object... arguments) {
        Message message = message(key, arguments);
        context.getSource().sendSuccess(() -> component(context.getSource(), message), false);
        return 1;
    }

    static int failure(CommandContext<CommandSourceStack> context, MessageKey key, Object... arguments) {
        return failure(context, message(key, arguments));
    }

    static int failure(CommandContext<CommandSourceStack> context, Message message) {
        context.getSource().sendFailure(component(context.getSource(), message));
        return 0;
    }
}
