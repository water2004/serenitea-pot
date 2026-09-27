package org.edtp.sereniteapot.level;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.util.Util;
import org.apache.commons.io.file.PathUtils;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.i18n.SereniteaPotTranslations.Message;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

public final class SereniteaPotDeletionService {
    private static final Map<UUID, Pending> pending = new LinkedHashMap<>();

    private SereniteaPotDeletionService() {
    }

    public static Result deleteAndReset(MinecraftServer server, UUID owner) {
        if (!server.isSameThread()) throw new IllegalStateException("Deletion must run on the server thread");
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return new Rejected(message(MessageKey.DELETION_NO_CONFIG));

        if (!SereniteaPotCreationService.cancel(owner)) {
            return new Rejected(message(MessageKey.LIFECYCLE_MAINTENANCE_EXISTS));
        }
        Path expectedRoot = server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions").resolve(SereniteaPotMod.MOD_ID).resolve("pot").toAbsolutePath().normalize();
        Path resolved = expectedRoot.resolve(owner.toString()).normalize();
        if (!expectedRoot.equals(resolved.getParent()) || !owner.toString().equals(resolved.getFileName().toString())) {
            return new Rejected(message(MessageKey.DELETION_UNSAFE_PATH, resolved));
        }
        SereniteaPotLifecycleService.Result maintenance = SereniteaPotLifecycleService.beginMaintenance(server, owner);
        if (maintenance instanceof SereniteaPotLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }
        SereniteaPotLifecycleService.Result close = SereniteaPotLifecycleService.closeNow(server, owner);
        if (close instanceof SereniteaPotLifecycleService.Rejected rejected) {
            SereniteaPotLifecycleService.endMaintenance(owner);
            return new Rejected(rejected.reason());
        }

        UUID oldStateId = record.getStateId();
        long oldGeneration = record.getActiveGeneration();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots = new EnumMap<>(record.getSlots());
        boolean oldFrozen = record.isFrozen();
        UUID newStateId = UUID.randomUUID();
        record.setActiveGeneration(0);
        record.setStateId(newStateId);
        record.getSlots().clear();
        record.setFrozen(false);
        CompletableFuture<Void> committed;
        CompletableFuture<Void> io;
        try {
            committed = SereniteaPotManager.saveCatalog();
            io = committed.thenRunAsync(() -> {
                try {
                    if (Files.isDirectory(resolved)) PathUtils.deleteDirectory(resolved);
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }, Util.ioPool());
        } catch (RuntimeException error) {
            record.setStateId(oldStateId);
            record.setActiveGeneration(oldGeneration);
            record.getSlots().putAll(oldSlots);
            record.setFrozen(oldFrozen);
            SereniteaPotLifecycleService.endMaintenance(owner);
            return new Rejected(message(MessageKey.DELETION_COMMIT_FAILED, error.getMessage()));
        }
        Pending operation = new Pending(
            server, owner, record, oldStateId, oldGeneration, oldSlots, oldFrozen, committed, io
        );
        pending.put(owner, operation);
        // The service owns completion and unlocking, even if its caller ignores the result.
        io.whenComplete((ignored, error) -> server.execute(operation::finish));
        return operation;
    }

    /** Drains pending file work at shutdown; this chain contains only I/O tasks. */
    public static void awaitPending(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Deletion drain must run on the server thread");
        for (Pending operation : java.util.List.copyOf(pending.values())) {
            try {
                operation.io.join();
            } catch (CompletionException ignored) {
                // finish records and rolls back the failure on the server thread.
            }
            operation.finish();
        }
    }

    public static final class Pending implements Result {
        private final MinecraftServer server;
        private final UUID owner;
        private final SereniteaPotRecord record;
        private final UUID oldStateId;
        private final long oldGeneration;
        private final EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots;
        private final boolean oldFrozen;
        private final CompletableFuture<Void> committed;
        private final CompletableFuture<Void> io;
        private final CompletableFuture<Result> completion = new CompletableFuture<>();

        private Pending(MinecraftServer server, UUID owner, SereniteaPotRecord record, UUID oldStateId,
                long oldGeneration, EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots,
                boolean oldFrozen, CompletableFuture<Void> committed, CompletableFuture<Void> io) {
            this.server = server;
            this.owner = owner;
            this.record = record;
            this.oldStateId = oldStateId;
            this.oldGeneration = oldGeneration;
            this.oldSlots = oldSlots;
            this.oldFrozen = oldFrozen;
            this.committed = committed;
            this.io = io;
        }

        public CompletableFuture<Result> future() { return completion; }

        private void finish() {
            if (!server.isSameThread()) throw new IllegalStateException("Deletion finish must run on the server thread");
            if (completion.isDone()) return;
            if (!io.isDone()) throw new IllegalStateException("Deletion is still pending");
            try {
                committed.join();
            } catch (RuntimeException error) {
                restoreRecord();
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
                release();
                completion.complete(new Rejected(message(MessageKey.DELETION_COMMIT_FAILED, cause.getMessage())));
                return;
            }
            Result result;
            try {
                io.join();
                result = Success.INSTANCE;
            } catch (RuntimeException error) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
                result = new Rejected(message(MessageKey.DELETION_DIRECTORY_FAILED, cause.getMessage()));
            } finally {
                SereniteaPotScheduler.forgetOwner(owner);
                SereniteaPotLifecycleService.forget(owner);
                release();
            }
            completion.complete(result);
        }

        private void release() {
            pending.remove(owner);
            SereniteaPotLifecycleService.endMaintenance(owner);
        }

        private void restoreRecord() {
            record.setStateId(oldStateId);
            record.setActiveGeneration(oldGeneration);
            record.getSlots().clear();
            record.getSlots().putAll(oldSlots);
            record.setFrozen(oldFrozen);
        }
    }

    public sealed interface Result permits Success, Rejected, Pending {
    }

    public enum Success implements Result {
        INSTANCE
    }

    public record Rejected(Message reason) implements Result {
    }
}
