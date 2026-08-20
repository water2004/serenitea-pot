package org.edtp.sereniteapot.level;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.model.SereniteaPotRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 保存仅存在于本次服务器进程中的临时访问申请。
 *
 * <p>批准时先签发一个短时、一次性的进入许可，再让正常传送路径消费它；因此访客和
 * OP 最终走的是同一套访问策略与状态隔离流程。</p>
 */
public final class SereniteaPotInvitationService {
    private static final long REQUEST_TTL_MILLIS = 60_000L;
    private static final long ENTRY_GRANT_TTL_MILLIS = 5_000L;

    private static final Map<UUID, LinkedHashMap<UUID, PendingRequest>> pending = new LinkedHashMap<>();
    private static final Map<EntryGrantKey, Long> entryGrants = new LinkedHashMap<>();

    private SereniteaPotInvitationService() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> cleanupExpired());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            pending.clear();
            entryGrants.clear();
        });
    }

    public static Result request(ServerPlayer requester, UUID owner) {
        if (requester.getUUID().equals(owner)) {
            return new Rejected("不能申请加入自己的尘歌壶");
        }
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return new Rejected("该玩家还没有尘歌壶");
        if (!record.exists() || !record.isEnabled()) {
            return new Rejected("该尘歌壶当前不可申请");
        }
        if (SereniteaPotLifecycleService.isUnavailable(owner)) {
            return new Rejected("该尘歌壶当前正在关闭或维护");
        }
        if (!SereniteaPotAccessPolicy.isRealOwnerInside(requester.level().getServer(), owner)) {
            return new Rejected("只有主人本人正在尘歌壶内时才能提交申请");
        }

        long now = System.currentTimeMillis();
        LinkedHashMap<UUID, PendingRequest> requests = pending.computeIfAbsent(
            owner, ignored -> new LinkedHashMap<>()
        );
        PendingRequest existing = requests.get(requester.getUUID());
        if (existing != null && existing.expiresAt() > now) {
            return new Rejected("你的申请已经在等待处理");
        }
        UUID requestId = UUID.randomUUID();
        requests.put(requester.getUUID(), new PendingRequest(now + REQUEST_TTL_MILLIS, requestId));
        ServerPlayer ownerPlayer = requester.level().getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null) {
            ownerPlayer.sendSystemMessage(requestMessage(requester.getScoreboardName(), requestId));
        }
        return Accepted.INSTANCE;
    }

    public static Result approve(ServerPlayer owner, UUID visitor) {
        return approve(owner, visitor, null);
    }

    public static Result approve(ServerPlayer owner, UUID visitor, UUID requestId) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner.getUUID());
        if (record == null) return new Rejected("你还没有尘歌壶");
        LinkedHashMap<UUID, PendingRequest> requests = pending.get(owner.getUUID());
        PendingRequest request = requests == null ? null : requests.get(visitor);
        if (request == null || request.expiresAt() <= System.currentTimeMillis()) {
            return new Rejected("没有找到该玩家的待处理申请");
        }
        if (requestId != null && !request.id().equals(requestId)) {
            return new Rejected("该按钮对应的申请已经失效");
        }
        ServerPlayer visitorPlayer = owner.level().getServer().getPlayerList().getPlayer(visitor);
        if (visitorPlayer == null) {
            return new Rejected("申请者已离线，申请会保留到过期");
        }

        // AccessPolicy 会在 teleport HEAD 消费许可；失败时撤销许可并保留原申请供重试。
        EntryGrantKey key = new EntryGrantKey(owner.getUUID(), visitor);
        entryGrants.put(key, System.currentTimeMillis() + ENTRY_GRANT_TTL_MILLIS);
        SereniteaPotTravelService.Result travel = SereniteaPotTravelService.enter(visitorPlayer, owner.getUUID());
        if (travel == SereniteaPotTravelService.Success.INSTANCE) {
            requests.remove(visitor);
            if (requests.isEmpty()) pending.remove(owner.getUUID());
            return new Approved(visitor);
        }
        entryGrants.remove(key);
        return new Rejected("批准后传送失败，申请仍有效：" + ((SereniteaPotTravelService.Rejected) travel).reason());
    }

    public static Result deny(ServerPlayer owner, UUID visitor) {
        return deny(owner, visitor, null);
    }

    public static Result deny(ServerPlayer owner, UUID visitor, UUID requestId) {
        LinkedHashMap<UUID, PendingRequest> requests = pending.get(owner.getUUID());
        PendingRequest request = requests == null ? null : requests.get(visitor);
        if (request == null || request.expiresAt() <= System.currentTimeMillis()) {
            return new Rejected("没有找到该玩家的待处理申请");
        }
        if (requestId != null && !request.id().equals(requestId)) {
            return new Rejected("该按钮对应的申请已经失效");
        }
        requests.remove(visitor);
        if (requests.isEmpty()) pending.remove(owner.getUUID());
        ServerPlayer visitorPlayer = owner.level().getServer().getPlayerList().getPlayer(visitor);
        if (visitorPlayer != null) {
            visitorPlayer.sendSystemMessage(Component.literal(
                owner.getScoreboardName() + " 拒绝了你的尘歌壶访问申请"
            ));
        }
        return Accepted.INSTANCE;
    }

    public static Set<UUID> pending(UUID owner) {
        long now = System.currentTimeMillis();
        LinkedHashMap<UUID, PendingRequest> requests = pending.get(owner);
        if (requests == null) return Set.of();
        requests.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        if (requests.isEmpty()) {
            pending.remove(owner);
            return Set.of();
        }
        return new LinkedHashSet<>(requests.keySet());
    }

    public static boolean consumeEntryGrant(UUID owner, UUID visitor) {
        Long expiresAt = entryGrants.remove(new EntryGrantKey(owner, visitor));
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(request -> request.getValue().expiresAt() <= now);
            return entry.getValue().isEmpty();
        });
        entryGrants.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private record EntryGrantKey(UUID owner, UUID visitor) {
    }

    private record PendingRequest(long expiresAt, UUID id) {
    }

    private static Component requestMessage(String requesterName, UUID requestId) {
        String approve = "sereniteapot approve " + requesterName + " " + requestId;
        String deny = "sereniteapot deny " + requesterName + " " + requestId;
        return Component.literal(requesterName + " 申请进入你的尘歌壶（60 秒内有效）")
            .append("\n")
            .append(Component.literal("[接受]")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(approve))))
            .append(" ")
            .append(Component.literal("[拒绝]")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(deny))));
    }

    public sealed interface Result permits Accepted, Approved, Rejected {
    }

    public enum Accepted implements Result {
        INSTANCE
    }

    public record Approved(UUID visitor) implements Result {
    }

    public record Rejected(String reason) implements Result {
    }
}
