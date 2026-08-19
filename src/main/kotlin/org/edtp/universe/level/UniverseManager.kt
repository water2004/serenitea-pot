package org.edtp.universe.level

import net.casual.arcade.dimensions.level.CustomLevel
import net.casual.arcade.dimensions.level.LevelPersistence
import net.casual.arcade.dimensions.level.builder.CustomLevelBuilder
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevelsBuilder
import net.casual.arcade.dimensions.utils.addCustomLevel
import net.casual.arcade.dimensions.utils.deleteCustomLevel
import net.casual.arcade.dimensions.utils.loadCustomLevel
import net.casual.arcade.dimensions.utils.removeCustomLevel
import net.casual.arcade.dimensions.utils.getDimensionPath
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings
import net.minecraft.world.level.storage.LevelResource
import org.edtp.universe.UniverseMod
import org.edtp.universe.model.UniverseCatalog
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.model.UniverseRecord
import org.edtp.universe.model.UniverseSlotRecord
import org.edtp.universe.persistence.UniverseCatalogRepository
import org.apache.commons.io.file.PathUtils
import java.nio.file.Files
import java.util.EnumMap
import java.util.Optional
import java.util.UUID

object UniverseManager {
    private var server: MinecraftServer? = null
    private var repository: UniverseCatalogRepository? = null
    private var catalog: UniverseCatalog = UniverseCatalog()
    private val loaded = LinkedHashMap<UUID, UniverseBundle>()

    fun start(server: MinecraftServer) {
        check(this.server == null) { "UniverseManager is already attached" }
        this.server = server
        this.repository = UniverseCatalogRepository(
            server.getWorldPath(LevelResource.ROOT).resolve(UniverseMod.MOD_ID),
        )
        this.catalog = requireNotNull(repository).load()
        cleanupInactiveGenerations(server)

        UniverseMod.logger.info("Loaded metadata for {} personal universes; dimensions remain unloaded", catalog.players.size)
    }

    fun stop(server: MinecraftServer) {
        if (this.server !== server) {
            return
        }
        saveCatalog()
        loaded.clear()
        repository = null
        this.server = null
    }

    fun catalog(): UniverseCatalog = catalog

    fun record(owner: UUID): UniverseRecord? = catalog.players[owner]

    fun getOrCreateRecord(owner: UUID): UniverseRecord = catalog.getOrCreate(owner)

    fun removeRecord(owner: UUID): UniverseRecord? = catalog.players.remove(owner)

    fun loaded(owner: UUID): UniverseBundle? = loaded[owner]

    internal fun loadedOwners(): Set<UUID> = loaded.keys.toSet()

    fun ownerOf(level: net.minecraft.server.level.ServerLevel): UUID? =
        UniverseLevelKeys.identify(level.dimension())?.owner

    fun createStaging(owner: UUID, generation: Long, seed: Long): UniverseBundle {
        val server = requireServerThread()
        require(generation > 0) { "Generation must be positive" }
        require(UniverseDimension.entries.none { server.getLevel(UniverseLevelKeys.key(owner, generation, it)) != null }) {
            "Universe $owner generation $generation is already loaded"
        }

        val built = VanillaLikeLevelsBuilder.build(server) {
            for (dimension in UniverseDimension.entries) {
                val template = requireNotNull(server.getLevel(dimension.vanillaLevelKey)) {
                    "Missing vanilla template dimension ${dimension.vanillaLevelKey.identifier()}"
                }
                val biome = template.getBiome(template.respawnData.pos())
                set(
                    dimension.vanilla,
                    CustomLevelBuilder()
                        .dimensionKey(UniverseLevelKeys.key(owner, generation, dimension))
                        .dimensionType(template.dimensionTypeRegistration())
                        .chunkGenerator(voidGenerator(biome))
                        .persistence(LevelPersistence.Permanent)
                        .seed(seed),
                )
            }
        }
        val levels = EnumMap<UniverseDimension, CustomLevel>(UniverseDimension::class.java)
        try {
            for (dimension in UniverseDimension.entries) {
                val level = built.getOrThrow(dimension.vanilla)
                server.addCustomLevel(level)
                levels[dimension] = level
            }
        } catch (error: Throwable) {
            for (level in levels.values.toList().asReversed()) {
                runCatching { server.removeCustomLevel(level) }
            }
            throw error
        }
        return UniverseBundle(owner, generation, levels)
    }

    fun activate(
        bundle: UniverseBundle,
        replacementSlots: Map<UniverseDimension, UniverseSlotRecord>,
    ): UniverseBundle? {
        requireServerThread()
        val previous = loaded[bundle.owner]
        require(previous == null || previous === bundle || previous.generation != bundle.generation) {
            "Universe ${bundle.owner} generation ${bundle.generation} is already active"
        }
        require(previous == null || previous.levels.values.all { it.players().isEmpty() }) {
            "Cannot replace universe ${bundle.owner} while players still occupy the previous generation"
        }

        for (level in bundle.levels.values) {
            level.save(null, true, false)
        }
        val record = catalog.getOrCreate(bundle.owner)
        val oldGeneration = record.activeGeneration
        val oldSlots = EnumMap<UniverseDimension, UniverseSlotRecord>(UniverseDimension::class.java)
        oldSlots.putAll(record.slots.mapValues { (_, slot) -> slot.copy() })
        val oldStopped = record.stopped
        val oldQuarantined = record.quarantined
        record.activeGeneration = bundle.generation
        record.slots.clear()
        record.slots.putAll(replacementSlots.mapValues { (_, slot) -> slot.copy() })
        record.stopped = false
        record.quarantined = false
        try {
            applyBorders(bundle, record)
            saveCatalog()
        } catch (error: Throwable) {
            record.activeGeneration = oldGeneration
            record.slots.clear()
            record.slots.putAll(oldSlots)
            record.stopped = oldStopped
            record.quarantined = oldQuarantined
            throw error
        }

        loaded[bundle.owner] = bundle
        return previous?.takeUnless { it === bundle }
    }

    fun load(owner: UUID): UniverseBundle {
        val server = requireServerThread()
        loaded[owner]?.let { return it }
        val record = requireNotNull(catalog.players[owner]) { "No universe metadata for $owner" }
        require(record.exists) { "Universe $owner does not exist" }

        val levels = EnumMap<UniverseDimension, CustomLevel>(UniverseDimension::class.java)
        try {
            for (dimension in UniverseDimension.entries) {
                val key = UniverseLevelKeys.key(owner, record.activeGeneration, dimension)
                val level = requireNotNull(server.loadCustomLevel(key)) {
                    "Missing persisted universe level ${key.identifier()}"
                }
                levels[dimension] = level
            }
        } catch (error: Throwable) {
            for (level in levels.values.toList().asReversed()) {
                runCatching { server.removeCustomLevel(level) }
            }
            throw error
        }
        return UniverseBundle(owner, record.activeGeneration, levels).also {
            loaded[owner] = it
            applyBorders(it, record)
        }
    }

    internal fun unloadEvacuated(owner: UUID): Boolean {
        requireServerThread()
        val bundle = loaded[owner] ?: return true
        require(bundle.levels.values.all { it.players().isEmpty() }) {
            "Cannot unload universe $owner while players still occupy it"
        }
        if (!unloadBundle(bundle)) {
            return false
        }
        loaded.remove(owner)
        return true
    }

    fun discard(bundle: UniverseBundle) {
        val server = requireServerThread()
        if (loaded[bundle.owner] === bundle) {
            loaded.remove(bundle.owner)
        }
        for (level in bundle.levels.values.toList().asReversed()) {
            if (!server.deleteCustomLevel(level)) {
                UniverseMod.logger.warn("Failed to delete staging level {}", level.dimension().identifier())
            }
        }
    }

    fun saveCatalog() {
        repository?.save(catalog)
    }

    internal fun deleteEvacuatedLevel(level: CustomLevel): Boolean {
        val server = requireServerThread()
        require(level.players().isEmpty()) { "Cannot delete occupied level ${level.dimension().identifier()}" }
        runCatching { server.deleteCustomLevel(level) }
            .onFailure { error ->
                UniverseMod.logger.warn("Failed to delete universe level {}", level.dimension().identifier(), error)
            }
        val detached = server.getLevel(level.dimension()) !== level
        val directoryRemoved = !Files.exists(server.getDimensionPath(level.dimension()))
        return detached && directoryRemoved
    }

    private fun unloadBundle(bundle: UniverseBundle): Boolean {
        val server = requireServerThread()
        var complete = true
        for (level in bundle.levels.values.toList().asReversed()) {
            if (server.getLevel(level.dimension()) !== level) {
                continue
            }
            runCatching { server.removeCustomLevel(level) }
                .onFailure { error ->
                    UniverseMod.logger.warn("Failed to unload universe level {}", level.dimension().identifier(), error)
                }
            if (server.getLevel(level.dimension()) === level) {
                complete = false
            }
        }
        return complete
    }

    private fun applyBorders(bundle: UniverseBundle, record: UniverseRecord) {
        for (dimension in UniverseDimension.entries) {
            val slot = record.slots[dimension]
            val border = bundle[dimension].worldBorder
            if (slot == null) {
                border.setCenter(0.0, 0.0)
                border.setSize(record.maxRadius * 2.0 + 1.0)
            } else {
                border.setCenter(slot.centerX + 0.5, slot.centerZ + 0.5)
                border.setSize(slot.radius * 2.0 + 1.0)
            }
        }
    }

    /**
     * Completes an interrupted generation transaction. The catalog's active
     * pointer is the commit marker; any sibling generation is staging or an
     * already-replaced generation and is not a backup.
     */
    private fun cleanupInactiveGenerations(server: MinecraftServer) {
        val universeRoot = server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions")
            .resolve(UniverseMod.MOD_ID)
            .resolve("u")
            .toAbsolutePath()
            .normalize()
        for ((owner, record) in catalog.players) {
            val ownerRoot = universeRoot.resolve(owner.toString()).normalize()
            if (ownerRoot.parent != universeRoot || !Files.isDirectory(ownerRoot)) {
                continue
            }
            runCatching {
                Files.list(ownerRoot).use { children ->
                    children.filter(Files::isDirectory).forEach { candidate ->
                        val resolved = candidate.toAbsolutePath().normalize()
                        val name = resolved.fileName.toString()
                        val generation = name.takeIf { it.startsWith('g') }
                            ?.substring(1)
                            ?.toLongOrNull()
                            ?: return@forEach
                        if (resolved.parent != ownerRoot || generation == record.activeGeneration) {
                            return@forEach
                        }
                        runCatching { PathUtils.deleteDirectory(resolved) }
                            .onSuccess {
                                UniverseMod.logger.info(
                                    "Deleted inactive universe generation {} for {}",
                                    generation,
                                    owner,
                                )
                            }
                            .onFailure { error ->
                                UniverseMod.logger.warn(
                                    "Failed to delete inactive universe generation {} for {}",
                                    generation,
                                    owner,
                                    error,
                                )
                            }
                    }
                }
            }.onFailure { error ->
                UniverseMod.logger.warn("Failed to inspect universe generation directory for {}", owner, error)
            }
        }
    }

    private fun requireServerThread(): MinecraftServer {
        val server = checkNotNull(server) { "UniverseManager is not attached to a server" }
        check(server.isSameThread) { "Universe world mutation must run on the server thread" }
        return server
    }

    private fun voidGenerator(biome: net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>): FlatLevelSource {
        val settings = FlatLevelGeneratorSettings(Optional.empty(), biome, listOf())
        settings.layersInfo.add(FlatLayerInfo(1, Blocks.AIR))
        settings.updateLayers()
        return FlatLevelSource(settings)
    }
}
