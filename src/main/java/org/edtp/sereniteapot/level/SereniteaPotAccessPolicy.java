package org.edtp.sereniteapot.level;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.player.HumanPlayerDetector;

import java.util.UUID;

public final class SereniteaPotAccessPolicy {
    private SereniteaPotAccessPolicy() {
    }

    public static Component denialReason(ServerPlayer player, ServerLevel destination) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(destination.dimension());
        if (identity == null) {
            return null;
        }
        SereniteaPotRecord record = SereniteaPotManager.record(identity.owner());
        if (record == null) {
            return Component.literal("目标尘歌壶不存在");
        }
        if (identity.generation() != record.getActiveGeneration()) {
            return Component.literal("不能进入非活动的尘歌壶代际");
        }
        if (SereniteaPotLifecycleService.isUnavailable(identity.owner())) {
            return Component.literal("目标尘歌壶正在关闭或维护");
        }
        if (!record.isEnabled()) {
            return Component.literal("目标尘歌壶已被管理员禁用");
        }
        SereniteaPotLevelKeys.Identity current = SereniteaPotLevelKeys.identify(player.level().dimension());
        if (current != null && current.owner().equals(identity.owner())) {
            return null;
        }
        if (player.getUUID().equals(identity.owner())) {
            return HumanPlayerDetector.isHuman(player) ? null : Component.literal("假玩家不能加载或维持尘歌壶");
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
            return SereniteaPotInvitationService.consumeEntryGrant(identity.owner(), player.getUUID())
                ? null
                : Component.literal("需要提交申请，并由主人批准本次进入");
        }
        return isRealOwnerInside(player.level().getServer(), identity.owner())
            ? null
            : Component.literal("只有主人本人在尘歌壶内时，管理员才能进入");
    }

    public static boolean isRealOwnerInside(MinecraftServer server, UUID owner) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || !HumanPlayerDetector.isHuman(player)) {
            return false;
        }
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(player.level().dimension());
        return identity != null && identity.owner().equals(owner);
    }
}
