package org.edtp.universe.level;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.io.file.PathUtils;
import org.edtp.universe.UniverseMod;
import org.edtp.universe.model.UniverseDimension;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.model.UniverseSlotRecord;
import org.edtp.universe.performance.UniverseScheduler;
import org.edtp.universe.region.UniverseCreationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public final class UniverseDeletionService {
    private UniverseDeletionService() {
    }

    public static Result archiveAndReset(MinecraftServer server, UUID owner) {
        if (!server.isSameThread()) throw new IllegalStateException("Deletion must run on the server thread");
        UniverseRecord record = UniverseManager.record(owner);
        if (record == null) return new Rejected("该玩家没有小宇宙配置");

        UniverseCreationService.cancel(owner);
        UniverseLifecycleService.Result close = UniverseLifecycleService.closeNow(server, owner);
        if (close instanceof UniverseLifecycleService.Rejected rejected) {
            return new Rejected(rejected.reason());
        }

        UUID oldStateId = record.getStateId();
        long oldGeneration = record.getActiveGeneration();
        EnumMap<UniverseDimension, UniverseSlotRecord> oldSlots = new EnumMap<>(UniverseDimension.class);
        for (Map.Entry<UniverseDimension, UniverseSlotRecord> entry : record.getSlots().entrySet()) {
            oldSlots.put(entry.getKey(), entry.getValue());
        }
        boolean oldFrozen = record.isFrozen();

        record.setActiveGeneration(0);
        record.setStateId(UUID.randomUUID());
        record.getSlots().clear();
        record.setFrozen(false);
        try {
            UniverseManager.saveCatalog();
        } catch (Throwable error) {
            record.setStateId(oldStateId);
            record.setActiveGeneration(oldGeneration);
            record.getSlots().putAll(oldSlots);
            record.setFrozen(oldFrozen);
            return new Rejected("无法提交删除事务：" + error.getMessage());
        }
        UniverseScheduler.reset(owner);
        UniverseLifecycleService.forget(owner);

        Path expectedRoot = server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions").resolve(UniverseMod.MOD_ID).resolve("u").toAbsolutePath().normalize();
        Path resolved = expectedRoot.resolve(owner.toString()).normalize();
        if (!expectedRoot.equals(resolved.getParent()) || !owner.toString().equals(resolved.getFileName().toString())) {
            return new Rejected("拒绝删除异常的小宇宙路径：" + resolved);
        }
        if (Files.isDirectory(resolved)) {
            try {
                PathUtils.deleteDirectory(resolved);
            } catch (Exception error) {
                return new Rejected("小宇宙已注销，但磁盘清理失败；下次启动会重试：" + error.getMessage());
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
