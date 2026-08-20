package org.edtp.sereniteapot.level;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.io.file.PathUtils;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public final class SereniteaPotDeletionService {
    private SereniteaPotDeletionService() {
    }

    public static Result archiveAndReset(MinecraftServer server, UUID owner) {
        if (!server.isSameThread()) throw new IllegalStateException("Deletion must run on the server thread");
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        if (record == null) return new Rejected("该玩家没有尘歌壶配置");

        SereniteaPotCreationService.cancel(owner);
        SereniteaPotLifecycleService.Result close = SereniteaPotLifecycleService.closeNow(server, owner);
        if (close instanceof SereniteaPotLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        UUID oldStateId = record.getStateId();
        long oldGeneration = record.getActiveGeneration();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots = new EnumMap<>(SereniteaPotDimension.class);
        for (Map.Entry<SereniteaPotDimension, SereniteaPotSlotRecord> entry : record.getSlots().entrySet()) {
            oldSlots.put(entry.getKey(), entry.getValue());
        }
        boolean oldFrozen = record.isFrozen();

        record.setActiveGeneration(0);
        record.setStateId(UUID.randomUUID());
        record.getSlots().clear();
        record.setFrozen(false);
        try {
            SereniteaPotManager.saveCatalog();
        } catch (Throwable error) {
            record.setStateId(oldStateId);
            record.setActiveGeneration(oldGeneration);
            record.getSlots().putAll(oldSlots);
            record.setFrozen(oldFrozen);
            return new Rejected("无法提交删除事务：" + error.getMessage());
        }
        SereniteaPotScheduler.reset(owner);
        SereniteaPotLifecycleService.forget(owner);

        Path expectedRoot = server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions").resolve(SereniteaPotMod.MOD_ID).resolve("pot").toAbsolutePath().normalize();
        Path resolved = expectedRoot.resolve(owner.toString()).normalize();
        if (!expectedRoot.equals(resolved.getParent()) || !owner.toString().equals(resolved.getFileName().toString())) {
            return new Rejected("拒绝删除异常的尘歌壶路径：" + resolved);
        }
        if (Files.isDirectory(resolved)) {
            try {
                PathUtils.deleteDirectory(resolved);
            } catch (Exception error) {
                return new Rejected("尘歌壶已注销，但磁盘清理失败；下次启动会重试：" + error.getMessage());
            }
        }
        return Success.INSTANCE;
    }

    public sealed interface Result permits Success, Rejected {
    }

    public enum Success implements Result {
        INSTANCE
    }

    public record Rejected(String reason) implements Result {
    }
}
