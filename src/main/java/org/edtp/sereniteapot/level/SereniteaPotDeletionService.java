package org.edtp.sereniteapot.level;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
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
import java.util.UUID;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

public final class SereniteaPotDeletionService {
    private SereniteaPotDeletionService() {
    }

    public static Result deleteAndReset(MinecraftServer server, UUID owner) {
        if (!server.isSameThread()) throw new IllegalStateException("Deletion must run on the server thread");
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return new Rejected(message(MessageKey.DELETION_NO_CONFIG));

        SereniteaPotCreationService.cancel(owner);
        SereniteaPotLifecycleService.Result close = SereniteaPotLifecycleService.closeNow(server, owner);
        if (close instanceof SereniteaPotLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        UUID oldStateId = record.getStateId();
        long oldGeneration = record.getActiveGeneration();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots = new EnumMap<>(record.getSlots());
        boolean oldFrozen = record.isFrozen();

        record.setActiveGeneration(0);
        record.setStateId(UUID.randomUUID());
        record.getSlots().clear();
        record.setFrozen(false);
        try {
            SereniteaPotManager.saveCatalog();
        } catch (RuntimeException error) {
            record.setStateId(oldStateId);
            record.setActiveGeneration(oldGeneration);
            record.getSlots().putAll(oldSlots);
            record.setFrozen(oldFrozen);
            return new Rejected(message(MessageKey.DELETION_COMMIT_FAILED, error.getMessage()));
        }
        SereniteaPotScheduler.reset(owner);
        SereniteaPotLifecycleService.forget(owner);

        Path expectedRoot = server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions").resolve(SereniteaPotMod.MOD_ID).resolve("pot").toAbsolutePath().normalize();
        Path resolved = expectedRoot.resolve(owner.toString()).normalize();
        if (!expectedRoot.equals(resolved.getParent()) || !owner.toString().equals(resolved.getFileName().toString())) {
            return new Rejected(message(MessageKey.DELETION_UNSAFE_PATH, resolved));
        }
        if (Files.isDirectory(resolved)) {
            try {
                PathUtils.deleteDirectory(resolved);
            } catch (Exception error) {
                return new Rejected(message(MessageKey.DELETION_DIRECTORY_FAILED, error.getMessage()));
            }
        }
        return Success.INSTANCE;
    }

    public sealed interface Result permits Success, Rejected {
    }

    public enum Success implements Result {
        INSTANCE
    }

    public record Rejected(Message reason) implements Result {
    }
}
