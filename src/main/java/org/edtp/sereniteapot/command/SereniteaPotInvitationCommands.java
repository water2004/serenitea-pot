package org.edtp.sereniteapot.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.profile;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

final class SereniteaPotInvitationCommands {
    private static final String OWNER_ARGUMENT = "owner";
    private static final String PLAYER_ARGUMENT = "player";
    private static final String REQUEST_ID_ARGUMENT = "request-id";

    private SereniteaPotInvitationCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("request")
                .then(Commands.argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotInvitationCommands::request)));
        root.then(Commands.literal("requests").executes(SereniteaPotInvitationCommands::requests));
        root.then(Commands.literal("approve")
                .then(Commands.argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotInvitationCommands::approve)
                        .then(Commands.argument(REQUEST_ID_ARGUMENT, UuidArgument.uuid())
                                .executes(SereniteaPotInvitationCommands::approveFromButton))));
        root.then(Commands.literal("deny")
                .then(Commands.argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotInvitationCommands::deny)
                        .then(Commands.argument(REQUEST_ID_ARGUMENT, UuidArgument.uuid())
                                .executes(SereniteaPotInvitationCommands::denyFromButton))));
    }

    private static int request(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SereniteaPotInvitationService.Result result = SereniteaPotInvitationService.request(
                context.getSource().getPlayerOrException(), profile(context, OWNER_ARGUMENT));
        return result == SereniteaPotInvitationService.Accepted.INSTANCE
                ? success(context, "申请已发送，60 秒后过期")
                : failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }

    private static int requests(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Set<UUID> requests = SereniteaPotInvitationService.pending(player.getUUID());
        return requests.isEmpty()
                ? success(context, "当前没有待处理申请")
                : success(context,
                        "待处理申请：" + requests.stream().map(UUID::toString).collect(Collectors.joining(", ")));
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
            return success(context, "已批准申请并将玩家送入尘歌壶");
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
                ? success(context, "已拒绝申请")
                : failure(context, ((SereniteaPotInvitationService.Rejected) result).reason());
    }
}
