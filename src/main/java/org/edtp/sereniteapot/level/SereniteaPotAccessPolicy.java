package org.edtp.sereniteapot.level;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.player.HumanPlayerDetector;

import java.util.UUID;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/**
 * 所有进入尘歌壶维度的最终访问检查。
 *
 * <p>命令只负责发起传送；传送门、管理员命令和其他模组触发的玩家跨维度传送也会
 * 经过这里，因此不能只在命令层检查权限。</p>
 */
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
            return component(player, message(MessageKey.ACCESS_TARGET_MISSING));
        }
        if (identity.generation() != record.getActiveGeneration()) {
            return component(player, message(MessageKey.ACCESS_INACTIVE_GENERATION));
        }
        if (SereniteaPotLifecycleService.isUnavailable(identity.owner())) {
            return component(player, message(MessageKey.ACCESS_UNAVAILABLE));
        }
        if (!record.isEnabled()) {
            return component(player, message(MessageKey.ACCESS_DISABLED));
        }
        SereniteaPotLevelKeys.Identity current = SereniteaPotLevelKeys.identify(player.level().dimension());
        if (current != null && current.owner().equals(identity.owner())) {
            return null;
        }
        if (player.getUUID().equals(identity.owner())) {
            return HumanPlayerDetector.isHuman(player)
                ? null
                : component(player, message(MessageKey.ACCESS_FAKE_PLAYER));
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
            // 临时许可只消费一次，批准申请不会变成永久访客名单。
            return SereniteaPotInvitationService.consumeEntryGrant(identity.owner(), player.getUUID())
                ? null
                : component(player, message(MessageKey.ACCESS_REQUEST_REQUIRED));
        }
        return isRealOwnerInside(player.level().getServer(), identity.owner())
            ? null
            : component(player, message(MessageKey.ACCESS_OWNER_REQUIRED_FOR_ADMIN));
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
