package org.edtp.universe.level

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.player.HumanPlayerDetector
import java.util.UUID

object UniverseTravelService {
    fun enter(player: ServerPlayer, owner: UUID): Result {
        val record = UniverseManager.record(owner)
            ?: return Result.Rejected("目标玩家还没有小宇宙")
        if (!record.exists) {
            return Result.Rejected("目标玩家还没有创建小宇宙")
        }
        if (!record.enabled) {
            return Result.Rejected("目标小宇宙已被禁用")
        }
        if (record.stopped) {
            return Result.Rejected("目标小宇宙已被管理员停止")
        }
        if (record.frozen) {
            return Result.Rejected("目标小宇宙已被管理员冻结")
        }
        if (record.quarantined) {
            return Result.Rejected("目标小宇宙因性能异常被隔离")
        }
        if (UniverseLifecycleService.isUnavailable(owner)) {
            return Result.Rejected("目标小宇宙正在关闭或维护")
        }

        val bundle = if (player.uuid == owner) {
            if (!HumanPlayerDetector.isHuman(player)) {
                return Result.Rejected("假玩家不能加载小宇宙")
            }
            UniverseManager.loaded(owner) ?: runCatching { UniverseManager.load(owner) }
                .getOrElse { return Result.Rejected("小宇宙加载失败：${it.message}") }
        } else {
            if (!UniverseAccessPolicy.isRealOwnerInside(player.level().server, owner)) {
                return Result.Rejected("只有主人本人在小宇宙内时才能进入")
            }
            UniverseManager.loaded(owner)
                ?: return Result.Rejected("目标小宇宙尚未加载")
        }

        val current = UniverseDimension.fromVanillaLevel(player.level().dimension())
        val destinationDimension = current?.takeIf(record.slots::containsKey)
            ?: UniverseDimension.entries.firstOrNull(record.slots::containsKey)
            ?: return Result.Rejected("目标小宇宙还没有可进入的维度")
        val slot = requireNotNull(record.slots[destinationDimension])
        val success = player.teleportTo(
            bundle[destinationDimension],
            slot.centerX + 0.5,
            slot.centerY.toDouble(),
            slot.centerZ + 0.5,
            emptySet(),
            player.yRot,
            player.xRot,
            true,
        )
        return if (success) Result.Success else Result.Rejected("传送被访问策略拒绝")
    }

    fun leave(player: ServerPlayer): Result {
        val identity = UniverseLevelKeys.identify(player.level().dimension())
            ?: return Result.Rejected("你当前不在小宇宙内")
        return evict(player, identity.owner)
    }

    /** Used only by the lifecycle close transaction. */
    internal fun evict(player: ServerPlayer, owner: UUID): Result {
        val identity = UniverseLevelKeys.identify(player.level().dimension())
            ?: return Result.Success
        if (identity.owner != owner) {
            return Result.Rejected("玩家位于另一个小宇宙")
        }
        val server = player.level().server
        val slot = UniverseManager.record(owner)?.slots?.get(identity.dimension)
        val target = publicTarget(server.overworld(), slot?.sourceDimension?.let { sourceId ->
            runCatching {
                server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(sourceId)))
            }.getOrNull()
        })
        val position = if (slot != null && target.dimension().identifier().toString() == slot.sourceDimension) {
            Vec3(slot.centerX + 0.5, slot.centerY.toDouble(), slot.centerZ + 0.5)
        } else {
            Vec3.atBottomCenterOf(target.respawnData.pos())
        }
        val success = player.teleportTo(
            target,
            position.x,
            position.y,
            position.z,
            emptySet(),
            player.yRot,
            player.xRot,
            true,
        )
        return if (success) Result.Success else Result.Rejected("无法离开小宇宙")
    }

    private fun publicTarget(fallback: ServerLevel, candidate: ServerLevel?): ServerLevel =
        candidate?.takeIf { UniverseLevelKeys.identify(it.dimension()) == null } ?: fallback

    sealed interface Result {
        data object Success : Result
        data class Rejected(val reason: String) : Result
    }
}
