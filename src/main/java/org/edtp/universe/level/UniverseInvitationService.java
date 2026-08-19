package org.edtp.universe.level;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.universe.model.UniverseRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class UniverseInvitationService {
    private static final long REQUEST_TTL_MILLIS = 60_000L;
    private static final long ENTRY_GRANT_TTL_MILLIS = 5_000L;

    private static final Map<UUID, LinkedHashMap<UUID, Long>> pending = new LinkedHashMap<>();
    private static final Map<EntryGrantKey, Long> entryGrants = new LinkedHashMap<>();

    private UniverseInvitationService() {
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
            return new Rejected("不能申请加入自己的小宇宙");
        }
        UniverseRecord record = UniverseManager.record(owner);
        if (record == null) return new Rejected("该玩家还没有小宇宙");
        if (!record.exists() || !record.isEnabled() || record.isStopped() || record.isQuarantined()) {
            return new Rejected("该小宇宙当前不可申请");
        }
        if (record.isFrozen() || UniverseLifecycleService.isUnavailable(owner)) {
            return new Rejected("该小宇宙当前正在冻结、关闭或维护");
        }
        if (!UniverseAccessPolicy.isRealOwnerInside(requester.level().getServer(), owner)) {
            return new Rejected("只有主人本人正在小宇宙内时才能提交申请");
        }

        long now = System.currentTimeMillis();
        LinkedHashMap<UUID, Long> requests = pending.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        Long existing = requests.get(requester.getUUID());
        if (existing != null && existing > now) {
            return new Rejected("你的申请已经在等待处理");
        }
        requests.put(requester.getUUID(), now + REQUEST_TTL_MILLIS);
        ServerPlayer ownerPlayer = requester.level().getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null) {
            ownerPlayer.sendSystemMessage(Component.literal(
                requester.getScoreboardName() + " 申请进入你的小宇宙。使用 /universe approve "
                    + requester.getScoreboardName() + " 批准"
            ));
        }
        return Accepted.INSTANCE;
    }

    public static Result approve(ServerPlayer owner, UUID visitor) {
        UniverseRecord record = UniverseManager.record(owner.getUUID());
        if (record == null) return new Rejected("你还没有小宇宙");
        LinkedHashMap<UUID, Long> requests = pending.get(owner.getUUID());
        Long expiresAt = requests == null ? null : requests.get(visitor);
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            return new Rejected("没有找到该玩家的待处理申请");
        }
        ServerPlayer visitorPlayer = owner.level().getServer().getPlayerList().getPlayer(visitor);
        if (visitorPlayer == null) {
            return new Rejected("申请者已离线，申请会保留到过期");
        }

        EntryGrantKey key = new EntryGrantKey(owner.getUUID(), visitor);
        entryGrants.put(key, System.currentTimeMillis() + ENTRY_GRANT_TTL_MILLIS);
        UniverseTravelService.Result travel = UniverseTravelService.enter(visitorPlayer, owner.getUUID());
        if (travel == UniverseTravelService.Success.INSTANCE) {
            requests.remove(visitor);
            if (requests.isEmpty()) pending.remove(owner.getUUID());
            return new Approved(visitor);
        }
        entryGrants.remove(key);
        return new Rejected("批准后传送失败，申请仍有效：" + ((UniverseTravelService.Rejected) travel).reason());
    }

    public static Result deny(ServerPlayer owner, UUID visitor) {
        LinkedHashMap<UUID, Long> requests = pending.get(owner.getUUID());
        Long expiresAt = requests == null ? null : requests.remove(visitor);
        if (expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            return new Rejected("没有找到该玩家的待处理申请");
        }
        if (requests.isEmpty()) pending.remove(owner.getUUID());
        ServerPlayer visitorPlayer = owner.level().getServer().getPlayerList().getPlayer(visitor);
        if (visitorPlayer != null) {
            visitorPlayer.sendSystemMessage(Component.literal(
                owner.getScoreboardName() + " 拒绝了你的小宇宙访问申请"
            ));
        }
        return Accepted.INSTANCE;
    }

    public static Set<UUID> pending(UUID owner) {
        long now = System.currentTimeMillis();
        LinkedHashMap<UUID, Long> requests = pending.get(owner);
        if (requests == null) return Set.of();
        requests.entrySet().removeIf(entry -> entry.getValue() <= now);
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
            entry.getValue().entrySet().removeIf(request -> request.getValue() <= now);
            return entry.getValue().isEmpty();
        });
        entryGrants.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private record EntryGrantKey(UUID owner, UUID visitor) {
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
