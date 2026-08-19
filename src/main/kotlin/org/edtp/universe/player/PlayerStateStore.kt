package org.edtp.universe.player

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.edtp.universe.UniverseMod
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class PlayerStateStore(private val root: Path) {
    private val cache = HashMap<UUID, CompoundTag>()

    fun get(player: UUID, stateKey: String): CompoundTag? =
        loadPlayer(player).getCompound(stateKey).orElse(null)?.copy()

    fun put(player: UUID, stateKey: String, snapshot: CompoundTag) {
        val states = loadPlayer(player)
        states.put(stateKey, snapshot.copy())
        savePlayer(player, states)
    }

    fun flushAndClear() {
        for ((player, states) in cache) {
            savePlayer(player, states)
        }
        cache.clear()
    }

    private fun loadPlayer(player: UUID): CompoundTag = cache.getOrPut(player) {
        val file = file(player)
        if (!Files.isRegularFile(file)) {
            CompoundTag().apply { putInt("schemaVersion", SCHEMA_VERSION) }
        } else {
            runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }
                .onFailure { error ->
                    UniverseMod.logger.error("Failed to read isolated player state {}", file, error)
                }
                .getOrThrow()
        }
    }

    private fun savePlayer(player: UUID, states: CompoundTag) {
        Files.createDirectories(root)
        val target = file(player)
        val temporary = root.resolve("$player.dat.tmp")
        NbtIo.writeCompressed(states, temporary)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun file(player: UUID): Path = root.resolve("$player.dat")

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
