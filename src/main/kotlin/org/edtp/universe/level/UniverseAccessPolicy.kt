package org.edtp.universe.level

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import org.edtp.universe.player.HumanPlayerDetector
import java.util.UUID

object UniverseAccessPolicy {
    fun denialReason(player: ServerPlayer, destination: ServerLevel): Component? {
        val identity = UniverseLevelKeys.identify(destination.dimension()) ?: return null
        val record = UniverseManager.record(identity.owner)
            ?: return Component.literal("目标小宇宙不存在")
        if (identity.generation != record.activeGeneration) {
            return Component.literal("不能进入非活动的小宇宙代际")
        }
        if (UniverseLifecycleService.isUnavailable(identity.owner)) {
            return Component.literal("目标小宇宙正在关闭或维护")
        }
        if (!record.enabled) {
            return Component.literal("目标小宇宙已被管理员禁用")
        }
        if (record.stopped) {
            return Component.literal("目标小宇宙已被管理员停止")
        }
        if (record.frozen) {
            return Component.literal("目标小宇宙已被管理员冻结")
        }
        if (record.quarantined) {
            return Component.literal("目标小宇宙因性能异常被隔离")
        }
        if (UniverseLevelKeys.identify(player.level().dimension())?.owner == identity.owner) {
            return null
        }

        if (player.uuid == identity.owner) {
            return if (HumanPlayerDetector.isHuman(player)) null
            else Component.literal("假玩家不能加载或维持小宇宙")
        }
        if (!player.permissions().hasPermission(Permissions.COMMANDS_OWNER)) {
            return if (UniverseInvitationService.consumeEntryGrant(identity.owner, player.uuid)) null
            else Component.literal("需要提交申请，并由主人批准本次进入")
        }
        return if (isRealOwnerInside(player.level().server, identity.owner)) null
        else Component.literal("只有主人本人在小宇宙内时，管理员才能进入")
    }

    fun isRealOwnerInside(server: net.minecraft.server.MinecraftServer, owner: UUID): Boolean {
        val player = server.playerList.getPlayer(owner) ?: return false
        if (!HumanPlayerDetector.isHuman(player)) {
            return false
        }
        return UniverseLevelKeys.identify(player.level().dimension())?.owner == owner
    }
}
