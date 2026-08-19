package org.edtp.universe.level

import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object UniverseInvitationService {
    private const val REQUEST_TTL_MILLIS = 60_000L
    private const val ENTRY_GRANT_TTL_MILLIS = 5_000L
    private val pending = LinkedHashMap<UUID, LinkedHashMap<UUID, Long>>()
    private val entryGrants = LinkedHashMap<Pair<UUID, UUID>, Long>()

    fun request(requester: ServerPlayer, owner: UUID): Result {
        if (requester.uuid == owner) {
            return Result.Rejected("不能申请加入自己的小宇宙")
        }
        val record = UniverseManager.record(owner)
            ?: return Result.Rejected("该玩家还没有小宇宙")
        if (!record.exists || !record.enabled || record.stopped || record.quarantined) {
            return Result.Rejected("该小宇宙当前不可申请")
        }
        if (!UniverseAccessPolicy.isRealOwnerInside(requester.level().server, owner)) {
            return Result.Rejected("只有主人本人正在小宇宙内时才能提交申请")
        }
        val now = System.currentTimeMillis()
        val requests = pending.getOrPut(owner) { LinkedHashMap() }
        val existing = requests[requester.uuid]
        if (existing != null && existing > now) {
            return Result.Rejected("你的申请已经在等待处理")
        }
        requests[requester.uuid] = now + REQUEST_TTL_MILLIS
        requester.level().server.playerList.getPlayer(owner)?.sendSystemMessage(
            Component.literal(
                "${requester.scoreboardName} 申请进入你的小宇宙。使用 /universe approve ${requester.scoreboardName} 批准",
            ),
        )
        return Result.Accepted
    }

    fun approve(owner: ServerPlayer, visitor: UUID): Result {
        val record = UniverseManager.record(owner.uuid)
            ?: return Result.Rejected("你还没有小宇宙")
        val requests = pending[owner.uuid]
        val expiresAt = requests?.remove(visitor)
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            return Result.Rejected("没有找到该玩家的待处理申请")
        }
        entryGrants[owner.uuid to visitor] = System.currentTimeMillis() + ENTRY_GRANT_TTL_MILLIS
        if (requests.isEmpty()) {
            pending.remove(owner.uuid)
        }
        return Result.Approved(visitor)
    }

    fun deny(owner: ServerPlayer, visitor: UUID): Result {
        val requests = pending[owner.uuid]
        val expiresAt = requests?.remove(visitor)
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            return Result.Rejected("没有找到该玩家的待处理申请")
        }
        if (requests.isEmpty()) {
            pending.remove(owner.uuid)
        }
        owner.level().server.playerList.getPlayer(visitor)?.sendSystemMessage(
            Component.literal("${owner.scoreboardName} 拒绝了你的小宇宙访问申请"),
        )
        return Result.Accepted
    }

    fun pending(owner: UUID): Set<UUID> {
        val now = System.currentTimeMillis()
        val requests = pending[owner] ?: return emptySet()
        requests.entries.removeIf { it.value <= now }
        if (requests.isEmpty()) {
            pending.remove(owner)
            return emptySet()
        }
        return requests.keys.toSet()
    }

    fun consumeEntryGrant(owner: UUID, visitor: UUID): Boolean {
        val key = owner to visitor
        val expiresAt = entryGrants.remove(key) ?: return false
        return expiresAt > System.currentTimeMillis()
    }

    sealed interface Result {
        data object Accepted : Result
        data class Approved(val visitor: UUID) : Result
        data class Rejected(val reason: String) : Result
    }
}
