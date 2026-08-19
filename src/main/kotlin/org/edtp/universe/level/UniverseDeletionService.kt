package org.edtp.universe.level

import net.casual.arcade.dimensions.utils.getDimensionPath
import net.minecraft.server.MinecraftServer
import org.apache.commons.io.file.PathUtils
import org.edtp.universe.UniverseMod
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.performance.UniverseScheduler
import org.edtp.universe.region.UniverseCreationService
import java.nio.file.Files
import java.util.UUID

object UniverseDeletionService {
    fun archiveAndReset(server: MinecraftServer, owner: UUID): Result {
        check(server.isSameThread)
        val record = UniverseManager.record(owner)
            ?: return Result.Rejected("该玩家没有小宇宙配置")
        UniverseCreationService.cancel(owner)
        UniverseManager.unload(owner)

        if (record.exists) {
            val sample = server.getDimensionPath(
                UniverseLevelKeys.key(owner, record.activeGeneration, UniverseDimension.OVERWORLD),
            )
            val ownerDirectory = sample.parent?.parent
            if (ownerDirectory != null && Files.isDirectory(ownerDirectory)) {
                val expectedRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("dimensions")
                    .resolve(UniverseMod.MOD_ID)
                    .resolve("u")
                    .toAbsolutePath()
                    .normalize()
                val resolved = ownerDirectory.toAbsolutePath().normalize()
                if (resolved.parent != expectedRoot || resolved.fileName.toString() != owner.toString()) {
                    return Result.Rejected("拒绝删除异常的小宇宙路径：$resolved")
                }
                try {
                    PathUtils.deleteDirectory(resolved)
                } catch (error: Exception) {
                    return Result.Rejected("无法删除小宇宙目录：${error.message}")
                }
            }
        }

        record.activeGeneration = 0
        record.stateId = UUID.randomUUID()
        record.slots.clear()
        record.frozen = false
        record.stopped = false
        record.quarantined = false
        UniverseScheduler.reset(owner)
        UniverseManager.saveCatalog()
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result
        data class Rejected(val reason: String) : Result
    }
}
