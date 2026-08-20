package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.profile;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotInvitationCommands {
    private static final String OWNER_ARGUMENT = "owner";
    private static final String PLAYER_ARGUMENT = "player";
    private static final String REQUEST_ID_ARGUMENT = "request-id";

    private SereniteaPotInvitationCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        route(root,
                literal("request"),
                argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotInvitationCommands::request));
        route(root, literal("requests").executes(SereniteaPotInvitationCommands::requests));
        // request-id 只由聊天中的接受/拒绝按钮附带，用来阻止过期按钮处理后来的同名申请。
        // 不带 request-id 的较短路径仍保留给玩家手动输入。
        route(root,
                literal("approve"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotInvitationCommands::approve),
                argument(REQUEST_ID_ARGUMENT, UuidArgument.uuid())
                        .executes(SereniteaPotInvitationCommands::approveFromButton));
        route(root,
                literal("deny"),
                argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotInvitationCommands::deny),
                argument(REQUEST_ID_ARGUMENT, UuidArgument.uuid())
                        .executes(SereniteaPotInvitationCommands::denyFromButton));
    }

    private static int request(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.request(
                context.getSource().getPlayerOrException(), profile(context, OWNER_ARGUMENT));
        return result == SereniteaPotInvitationService.Accepted.INSTANCE
                ? success(context, "command.request.sent")
                : failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }

    private static int requests(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Set<UUID> requests = SereniteaPotInvitationService.pending(player.getUUID());
        return requests.isEmpty()
                ? success(context, "command.requests.empty")
                : success(context, "command.requests.list",
                        requests.stream().map(UUID::toString).collect(Collectors.joining(", ")));
    }

    private static int approve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return approve(context, null);
    }

    private static int approveFromButton(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return approve(context, UuidArgument.getUuid(context, REQUEST_ID_ARGUMENT));
    }

    private static int approve(CommandContext<CommandSourceStack> context, UUID requestId)
            throws CommandSyntaxException {
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.approve(
                context.getSource().getPlayerOrException(), profile(context, PLAYER_ARGUMENT), requestId);
        if (result instanceof SereniteaPotInvitationService.Approved) {
            return success(context, "command.approve.success");
        }
        return failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }

    private static int deny(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return deny(context, null);
    }

    private static int denyFromButton(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return deny(context, UuidArgument.getUuid(context, REQUEST_ID_ARGUMENT));
    }

    private static int deny(CommandContext<CommandSourceStack> context, UUID requestId)
            throws CommandSyntaxException {
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.deny(
                context.getSource().getPlayerOrException(), profile(context, PLAYER_ARGUMENT), requestId);
        return result == SereniteaPotInvitationService.Accepted.INSTANCE
                ? success(context, "command.deny.success")
                : failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }
}
