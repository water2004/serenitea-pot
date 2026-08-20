package org.edtp.sereniteapot.level;

import net.casual.arcade.dimensions.level.CustomLevel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.i18n.SereniteaPotTranslations.Message;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.fallback;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/**
 * The only gateway for taking an active Serenitea Pot out of service.
 *
 * <p>Every close path first prevents admission, then evacuates players,
 * verifies that no player still references a custom level, and only then asks
 * {@link SereniteaPotManager} to remove the levels.</p>
 */
public final class SereniteaPotLifecycleService {
    private static final long DELETE_RETRY_MILLIS = 1_000L;

    private static final Set<UUID> pendingCloses = new LinkedHashSet<>();
    private static final Set<UUID> maintenance = new LinkedHashSet<>();
    private static final Set<UUID> closing = new LinkedHashSet<>();
    private static final Map<UUID, Map<Long, PendingDelete>> pendingDeletes = new LinkedHashMap<>();

    private SereniteaPotLifecycleService() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SereniteaPotLifecycleService::endServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(SereniteaPotLifecycleService::stop);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearAll());
    }

    public static void requestClose(UUID owner) {
        pendingCloses.add(owner);
    }

    /** Cancels a close that has only been queued and has not started yet. */
    public static void cancelPendingClose(UUID owner) {
        pendingCloses.remove(owner);
    }

    public static boolean isUnavailable(UUID owner) {
        return pendingCloses.contains(owner) || maintenance.contains(owner) || closing.contains(owner);
    }

    /** Locks admission and evacuates occupants while old levels remain available as copy sources. */
    public static Result beginMaintenance(MinecraftServer server, UUID owner) {
        requireServerThread(server);
        if (!maintenance.add(owner)) {
            return new Rejected(message(MessageKey.LIFECYCLE_MAINTENANCE_EXISTS));
        }
        pendingCloses.remove(owner);
        List<ServerPlayer> remaining = evacuate(server, owner);
        if (!remaining.isEmpty()) {
            maintenance.remove(owner);
            pendingCloses.add(owner);
            return new Rejected(message(MessageKey.LIFECYCLE_EVACUATION_FAILED, remaining.size()));
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
            return new Rejected(message(MessageKey.LIFECYCLE_CLOSING));
        }
        try {
            List<ServerPlayer> remaining = evacuate(server, owner);
            if (!remaining.isEmpty()) {
                for (ServerPlayer player : remaining) {
                    player.connection.disconnect(component(
                        player,
                        message(MessageKey.LIFECYCLE_DISCONNECT_FOR_UNLOAD)
                    ));
                }
                return new Rejected(message(MessageKey.LIFECYCLE_DISCONNECT_RETRY, remaining.size()));
            }
            if (!SereniteaPotManager.unloadEvacuated(owner)) {
                return new Rejected(message(MessageKey.LIFECYCLE_UNLOAD_RETRY));
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
        pendingDeletes.remove(owner);
    }

    public static void deleteEvacuated(SereniteaPotBundle bundle) {
        boolean occupied = bundle.levels().values().stream().anyMatch(level -> !level.players().isEmpty());
        if (occupied) {
            throw new IllegalArgumentException(
                "Cannot delete occupied replacement generation " + bundle.generation()
            );
        }
        pendingDeletes.computeIfAbsent(bundle.owner(), ignored -> new LinkedHashMap<>()).put(
            bundle.generation(), new PendingDelete(new ArrayList<>(bundle.levels().values()))
        );
        processPendingDeletes(false);
    }

    private static List<ServerPlayer> evacuate(MinecraftServer server, UUID owner) {
        List<ServerPlayer> occupants = occupants(server, owner);
        for (ServerPlayer player : occupants) {
            SereniteaPotTravelService.Result result = SereniteaPotTravelService.evict(player, owner);
            if (result instanceof SereniteaPotTravelService.Rejected rejected) {
                SereniteaPotMod.LOGGER.warn(
                    "Failed to evacuate {} from Serenitea Pot {}: {}",
                    player.getUUID(), owner, rejected.reason()
                );
            }
        }
        return occupants(server, owner);
    }

    private static List<ServerPlayer> occupants(MinecraftServer server, UUID owner) {
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(player.level().dimension());
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
                SereniteaPotMod.LOGGER.warn(
                    "Serenitea Pot {} close was deferred: {}", owner, fallback(rejected.reason())
                );
            }
        }
        processPendingDeletes(false);
    }

    private static void processPendingDeletes(boolean force) {
        long now = System.currentTimeMillis();
        Iterator<Map<Long, PendingDelete>> owners = pendingDeletes.values().iterator();
        while (owners.hasNext()) {
            Map<Long, PendingDelete> ownerDeletes = owners.next();
            Iterator<PendingDelete> deletes = ownerDeletes.values().iterator();
            while (deletes.hasNext()) {
                PendingDelete pending = deletes.next();
                if (!force && now < pending.nextAttemptMillis) continue;
                pending.levels.removeIf(SereniteaPotManager::deleteEvacuatedLevel);
                if (pending.levels.isEmpty()) {
                    deletes.remove();
                } else {
                    pending.nextAttemptMillis = now + DELETE_RETRY_MILLIS;
                }
            }
            if (ownerDeletes.isEmpty()) owners.remove();
        }
    }

    private static void stop(MinecraftServer server) {
        requireServerThread(server);
        for (UUID owner : SereniteaPotManager.loadedOwners()) {
            Result result = closeNow(server, owner);
            if (result instanceof Rejected rejected) {
                SereniteaPotMod.LOGGER.error(
                    "Could not fully close Serenitea Pot {} during shutdown: {}", owner, fallback(rejected.reason())
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
            throw new IllegalStateException("Serenitea Pot lifecycle mutation must run on the server thread");
        }
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

    public record Rejected(Message reason) implements Result {
    }
}
