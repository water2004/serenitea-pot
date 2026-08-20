package org.edtp.sereniteapot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SereniteaPotCommands {
    private static final String ROOT_COMMAND = "sereniteapot";
    private static final String OWNER_ARGUMENT = "owner";
    private static final String PLAYER_ARGUMENT = "player";
    private static final String RADIUS_ARGUMENT = "radius";
    private static final String REQUEST_ID_ARGUMENT = "request-id";

    private SereniteaPotCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT_COMMAND)
                .executes(SereniteaPotCommands::statusSelf);
        addPlayerCommands(root);
        root.then(SereniteaPotAdminCommands.build());
        dispatcher.register(root);
    }

    private static void addPlayerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(createCommand());
        root.then(enterCommand());
        root.then(Commands.literal("leave").executes(SereniteaPotCommands::leave));
        root.then(Commands.literal("unfreeze").executes(SereniteaPotCommands::unfreezeSelf));
        addInvitationCommands(root);
        root.then(deleteCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("create")
                .then(Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0))
                        .executes(SereniteaPotCommands::create));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> enterCommand() {
        return Commands.literal("enter")
                .executes(context -> enter(context, context.getSource().getPlayerOrException().getUUID()))
                .then(Commands.argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotCommands::enterTarget));
    }

    private static void addInvitationCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("request")
                .then(Commands.argument(OWNER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotCommands::request)));
        root.then(Commands.literal("requests").executes(SereniteaPotCommands::requests));
        root.then(Commands.literal("approve")
                .then(Commands.argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotCommands::approve)
                        .then(Commands.argument(REQUEST_ID_ARGUMENT, UuidArgument.uuid())
                                .executes(SereniteaPotCommands::approveFromButton))));
        root.then(Commands.literal("deny")
                .then(Commands.argument(PLAYER_ARGUMENT, GameProfileArgument.gameProfile())
                        .executes(SereniteaPotCommands::deny)
                        .then(Commands.argument(REQUEST_ID_ARGUMENT, UuidArgument.uuid())
                                .executes(SereniteaPotCommands::denyFromButton))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> deleteCommand() {
        return Commands.literal("delete")
                .then(Commands.literal("confirm").executes(SereniteaPotCommands::deleteSelf));
    }

    private static int unfreezeSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
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

    private static int create(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SereniteaPotCreationService.RequestResult result = SereniteaPotCreationService.request(
                player, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT));
        if (result instanceof SereniteaPotCreationService.Accepted accepted) {
            return success(context,
                    "开始按区块批量提取 " + accepted.chunkCount() + " 个区块的完整高度（代际 "
                            + accepted.generation() + "），执行 /sereniteapot 可查询进度");
        }
        return failure(context, ((SereniteaPotCreationService.Rejected) result).reason());
    }

    private static int enterTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return enter(context, profile(context, OWNER_ARGUMENT));
    }

    private static int enter(CommandContext<CommandSourceStack> context, UUID owner) throws CommandSyntaxException {
        SereniteaPotTravelService.Result result = SereniteaPotTravelService.enter(
                context.getSource().getPlayerOrException(), owner);
        return result == SereniteaPotTravelService.Success.INSTANCE
                ? success(context, "已进入尘歌壶")
                : failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SereniteaPotTravelService.Result result = SereniteaPotTravelService.leave(
                context.getSource().getPlayerOrException());
        return result == SereniteaPotTravelService.Success.INSTANCE
                ? success(context, "已离开尘歌壶")
                : failure(context, ((SereniteaPotTravelService.Rejected) result).reason());
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

    private static int deleteSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return delete(context, context.getSource().getPlayerOrException().getUUID());
    }

    static int delete(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotDeletionService.Result result = SereniteaPotDeletionService.deleteAndReset(
                context.getSource().getServer(), owner);
        return result == SereniteaPotDeletionService.Success.INSTANCE
                ? success(context, "尘歌壶已卸载并永久删除")
                : failure(context, ((SereniteaPotDeletionService.Rejected) result).reason());
    }

    private static int statusSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return status(context, context.getSource().getPlayerOrException().getUUID());
    }

    static int status(CommandContext<CommandSourceStack> context, UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return failure(context, "没有该玩家的尘歌壶配置");
        Double progress = SereniteaPotCreationService.progress(owner);
        String creation = progress == null ? "" : ", 创建进度=" + format("%.1f", progress * 100.0) + "%";
        return success(context,
                "owner=" + owner + ", 存在=" + record.exists()
                        + ", 已加载=" + (SereniteaPotManager.loaded(owner) != null)
                        + ", enabled=" + record.isEnabled() + ", frozen=" + record.isFrozen()
                        + ", maxRadiusChunks=" + record.getMaxRadiusChunks()
                        + ", budget=" + record.getBudgetMillisPerSecond() + "ms/s" + creation);
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }

    static UUID profile(CommandContext<CommandSourceStack> context, String argument)
            throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, argument);
        if (profiles.size() != 1) {
            throw GameProfileArgument.ERROR_UNKNOWN_PLAYER.create();
        }
        return profiles.iterator().next().id();
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
