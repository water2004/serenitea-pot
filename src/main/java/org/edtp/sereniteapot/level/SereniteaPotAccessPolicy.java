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
        if (SereniteaPotManager.hasFailedClose(identity.owner())) {
            return component(player, message(MessageKey.LIFECYCLE_CLOSE_FAILED));
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
        if (canLoad(player.getUUID(), identity.owner())) {
            return HumanPlayerDetector.isHuman(player)
                ? null
                : component(player, message(MessageKey.ACCESS_FAKE_PLAYER));
        }
        if (!hasResidentInside(player.level().getServer(), identity.owner())) {
            return component(player, message(MessageKey.ACCESS_RESIDENT_REQUIRED));
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
            // 临时许可只消费一次，批准申请不会变成永久访客名单。
            return SereniteaPotInvitationService.consumeEntryGrant(identity.owner(), player.getUUID())
                ? null
                : component(player, message(MessageKey.ACCESS_REQUEST_REQUIRED));
        }
        return null;
    }

    /** Loading rights belong to the owner and explicitly invited residents, never to OP alone. */
    public static boolean canLoad(UUID player, UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        return player.equals(owner) || record != null && record.getResidents().contains(player);
    }

    public static boolean hasResidentInside(MinecraftServer server, UUID owner) {
        return server.getPlayerList().getPlayers().stream().anyMatch(player -> {
            if (!canLoad(player.getUUID(), owner) || !HumanPlayerDetector.isHuman(player)) return false;
            var identity = SereniteaPotLevelKeys.identify(player.level().dimension());
            var record = SereniteaPotManager.record(owner);
            return identity != null && identity.owner().equals(owner) && record != null
                && identity.generation() == record.getActiveGeneration();
        });
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
