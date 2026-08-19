package org.edtp.universe.level;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.player.HumanPlayerDetector;

import java.util.UUID;

public final class UniverseAccessPolicy {
    private UniverseAccessPolicy() {
    }

    public static Component denialReason(ServerPlayer player, ServerLevel destination) {
        UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(destination.dimension());
        if (identity == null) {
            return null;
        }
        UniverseRecord record = UniverseManager.record(identity.owner());
        if (record == null) {
            return Component.literal("目标小宇宙不存在");
        }
        if (identity.generation() != record.getActiveGeneration()) {
            return Component.literal("不能进入非活动的小宇宙代际");
        }
        if (UniverseLifecycleService.isUnavailable(identity.owner())) {
            return Component.literal("目标小宇宙正在关闭或维护");
        }
        if (!record.isEnabled()) {
            return Component.literal("目标小宇宙已被管理员禁用");
        }
        if (record.isStopped()) {
            return Component.literal("目标小宇宙已被管理员停止");
        }
        if (record.isFrozen()) {
            return Component.literal("目标小宇宙已被管理员冻结");
        }
        if (record.isQuarantined()) {
            return Component.literal("目标小宇宙因性能异常被隔离");
        }

        UniverseLevelKeys.Identity current = UniverseLevelKeys.identify(player.level().dimension());
        if (current != null && current.owner().equals(identity.owner())) {
            return null;
        }
        if (player.getUUID().equals(identity.owner())) {
            return HumanPlayerDetector.isHuman(player) ? null : Component.literal("假玩家不能加载或维持小宇宙");
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
            return UniverseInvitationService.consumeEntryGrant(identity.owner(), player.getUUID())
                ? null
                : Component.literal("需要提交申请，并由主人批准本次进入");
        }
        return isRealOwnerInside(player.level().getServer(), identity.owner())
            ? null
            : Component.literal("只有主人本人在小宇宙内时，管理员才能进入");
    }

    public static boolean isRealOwnerInside(MinecraftServer server, UUID owner) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || !HumanPlayerDetector.isHuman(player)) {
            return false;
        }
        UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(player.level().dimension());
        return identity != null && identity.owner().equals(owner);
    }
}
