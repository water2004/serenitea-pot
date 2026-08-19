package org.edtp.universe.level;

import net.casual.arcade.dimensions.level.CustomLevel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.universe.UniverseMod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only gateway for taking an active universe out of service.
 *
 * <p>Every close path first prevents admission, then evacuates players,
 * verifies that no player still references a custom level, and only then asks
 * {@link UniverseManager} to remove the levels.</p>
 */
public final class UniverseLifecycleService {
    private static final long DELETE_RETRY_MILLIS = 1_000L;

    private static final Set<UUID> pendingCloses = new LinkedHashSet<>();
    private static final Set<UUID> maintenance = new LinkedHashSet<>();
    private static final Set<UUID> closing = new LinkedHashSet<>();
    private static final Map<GenerationKey, PendingDelete> pendingDeletes = new LinkedHashMap<>();

    private UniverseLifecycleService() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(UniverseLifecycleService::endServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(UniverseLifecycleService::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearAll());
    }

    public static void ownerLeft(UUID owner) {
        requestClose(owner);
    }

    public static void forceUnload(UUID owner) {
        requestClose(owner);
    }

    public static void requestClose(UUID owner) {
        pendingCloses.add(owner);
    }

    public static boolean isUnavailable(UUID owner) {
        return pendingCloses.contains(owner) || maintenance.contains(owner) || closing.contains(owner);
    }

    /** Locks admission and evacuates occupants while old levels remain available as copy sources. */
    public static Result beginMaintenance(MinecraftServer server, UUID owner) {
        requireServerThread(server);
        if (!maintenance.add(owner)) {
            return new Rejected("该小宇宙已有维护任务");
        }
        pendingCloses.remove(owner);
        List<ServerPlayer> remaining = evacuate(server, owner);
        if (!remaining.isEmpty()) {
            maintenance.remove(owner);
            pendingCloses.add(owner);
            return new Rejected("无法安全送出 " + remaining.size() + " 名小宇宙成员");
        }
        return Success.INSTANCE;
    }

    public static void endMaintenance(UUID owner) {
        maintenance.remove(owner);
    }

    /** Must be called on the server thread and outside MinecraftServer's level iterator. */
    public static Result closeNow(MinecraftServer server, UUID owner) {
        requireServerThread(server);
        pendingCloses.add(owner);
        if (!closing.add(owner)) {
            return new Rejected("该小宇宙正在关闭");
        }
        try {
            List<ServerPlayer> remaining = evacuate(server, owner);
            if (!remaining.isEmpty()) {
                for (ServerPlayer player : remaining) {
                    player.connection.disconnect(Component.literal("小宇宙正在安全卸载，请重新连接"));
                }
                return new Rejected(
                    "仍有 " + remaining.size() + " 名玩家未完成离场，已断开连接并将在下一 tick 重试"
                );
            }
            if (!UniverseManager.unloadEvacuated(owner)) {
                return new Rejected("至少一个维度尚未完成卸载，将在下一 tick 重试");
            }
            pendingCloses.remove(owner);
            return Success.INSTANCE;
        } finally {
            closing.remove(owner);
        }
    }

    public static void forget(UUID owner) {
        pendingCloses.remove(owner);
        maintenance.remove(owner);
        closing.remove(owner);
        pendingDeletes.keySet().removeIf(key -> key.owner().equals(owner));
    }

    public static void deleteEvacuated(UniverseBundle bundle) {
        boolean occupied = bundle.levels().values().stream().anyMatch(level -> !level.players().isEmpty());
        if (occupied) {
            throw new IllegalArgumentException(
                "Cannot delete occupied replacement generation " + bundle.generation()
            );
        }
        pendingDeletes.put(
            new GenerationKey(bundle.owner(), bundle.generation()),
            new PendingDelete(new ArrayList<>(bundle.levels().values()))
        );
        processPendingDeletes(false);
    }

    private static List<ServerPlayer> evacuate(MinecraftServer server, UUID owner) {
        List<ServerPlayer> occupants = occupants(server, owner);
        for (ServerPlayer player : occupants) {
            UniverseTravelService.Result result = UniverseTravelService.evict(player, owner);
            if (result instanceof UniverseTravelService.Rejected rejected) {
                UniverseMod.LOGGER.warn(
                    "Failed to evacuate {} from universe {}: {}",
                    player.getUUID(), owner, rejected.reason()
                );
            }
        }
        return occupants(server, owner);
    }

    private static List<ServerPlayer> occupants(MinecraftServer server, UUID owner) {
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(player.level().dimension());
            if (identity != null && identity.owner().equals(owner)) {
                result.add(player);
            }
        }
        return result;
    }

    private static void endServerTick(MinecraftServer server) {
        for (UUID owner : List.copyOf(pendingCloses)) {
            Result result = closeNow(server, owner);
            if (result instanceof Rejected rejected) {
                UniverseMod.LOGGER.warn("Universe {} close was deferred: {}", owner, rejected.reason());
            }
        }
        processPendingDeletes(false);
    }

    private static void processPendingDeletes(boolean force) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<GenerationKey, PendingDelete>> iterator = pendingDeletes.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDelete pending = iterator.next().getValue();
            if (!force && now < pending.nextAttemptMillis) {
                continue;
            }
            pending.levels.removeIf(UniverseManager::deleteEvacuatedLevel);
            if (pending.levels.isEmpty()) {
                iterator.remove();
            } else {
                pending.nextAttemptMillis = now + DELETE_RETRY_MILLIS;
            }
        }
    }

    private static void stop(MinecraftServer server) {
        requireServerThread(server);
        for (UUID owner : UniverseManager.loadedOwners()) {
            Result result = closeNow(server, owner);
            if (result instanceof Rejected rejected) {
                UniverseMod.LOGGER.error(
                    "Could not fully close universe {} during shutdown: {}", owner, rejected.reason()
                );
            }
        }
        processPendingDeletes(true);
    }

    private static void clearAll() {
        pendingCloses.clear();
        maintenance.clear();
        closing.clear();
        pendingDeletes.clear();
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Universe lifecycle mutation must run on the server thread");
        }
    }

    private record GenerationKey(UUID owner, long generation) {
    }

    private static final class PendingDelete {
        private final List<CustomLevel> levels;
        private long nextAttemptMillis;

        private PendingDelete(List<CustomLevel> levels) {
            this.levels = levels;
        }
    }

    public sealed interface Result permits Success, Rejected {
    }

    public enum Success implements Result {
        INSTANCE
    }

    public record Rejected(String reason) implements Result {
    }
}
