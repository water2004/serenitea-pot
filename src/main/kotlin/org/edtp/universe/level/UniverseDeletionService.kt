package org.edtp.universe.level

import net.minecraft.server.MinecraftServer
import org.apache.commons.io.file.PathUtils
import org.edtp.universe.UniverseMod
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.model.UniverseSlotRecord
import org.edtp.universe.performance.UniverseScheduler
import org.edtp.universe.region.UniverseCreationService
import java.nio.file.Files
import java.util.EnumMap
import java.util.UUID

object UniverseDeletionService {
    fun archiveAndReset(server: MinecraftServer, owner: UUID): Result {
        check(server.isSameThread)
        val record = UniverseManager.record(owner)
            ?: return Result.Rejected("该玩家没有小宇宙配置")
        UniverseCreationService.cancel(owner)
        when (val close = UniverseLifecycleService.closeNow(server, owner)) {
            UniverseLifecycleService.Result.Success -> Unit
            is UniverseLifecycleService.Result.Rejected -> return Result.Rejected(close.reason)
        }

        val oldStateId = record.stateId
        val oldGeneration = record.activeGeneration
        val oldSlots = EnumMap<UniverseDimension, UniverseSlotRecord>(
            UniverseDimension::class.java,
        )
        oldSlots.putAll(record.slots.mapValues { (_, slot) -> slot.copy() })
        val oldFrozen = record.frozen
        val oldStopped = record.stopped
        val oldQuarantined = record.quarantined
        record.activeGeneration = 0
        record.stateId = UUID.randomUUID()
        record.slots.clear()
        record.frozen = false
        record.stopped = false
        record.quarantined = false
        try {
            UniverseManager.saveCatalog()
        } catch (error: Throwable) {
            record.stateId = oldStateId
            record.activeGeneration = oldGeneration
            record.slots.putAll(oldSlots)
            record.frozen = oldFrozen
            record.stopped = oldStopped
            record.quarantined = oldQuarantined
            return Result.Rejected("无法提交删除事务：${error.message}")
        }
        UniverseScheduler.reset(owner)
        UniverseLifecycleService.forget(owner)

        val expectedRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("dimensions")
            .resolve(UniverseMod.MOD_ID)
            .resolve("u")
            .toAbsolutePath()
            .normalize()
        val resolved = expectedRoot.resolve(owner.toString()).normalize()
        if (resolved.parent != expectedRoot || resolved.fileName.toString() != owner.toString()) {
            return Result.Rejected("拒绝删除异常的小宇宙路径：$resolved")
        }
        if (Files.isDirectory(resolved)) {
            try {
                PathUtils.deleteDirectory(resolved)
            } catch (error: Exception) {
                return Result.Rejected("小宇宙已注销，但磁盘清理失败；下次启动会重试：${error.message}")
            }
        }

        return Result.Success
    }

    sealed interface Result {
        data object Success : Result
        data class Rejected(val reason: String) : Result
    }
}
