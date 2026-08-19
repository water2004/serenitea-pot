package org.edtp.universe.level

import net.casual.arcade.dimensions.level.CustomLevel
import net.casual.arcade.dimensions.level.LevelPersistence
import net.casual.arcade.dimensions.level.builder.CustomLevelBuilder
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevelsBuilder
import net.casual.arcade.dimensions.utils.addCustomLevel
import net.casual.arcade.dimensions.utils.deleteCustomLevel
import net.casual.arcade.dimensions.utils.loadCustomLevel
import net.casual.arcade.dimensions.utils.removeCustomLevel
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
import org.edtp.universe.persistence.UniverseCatalogRepository
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

        for (record in catalog.players.values) {
            if (!record.exists || !record.enabled || record.stopped || record.quarantined) {
                continue
            }
            runCatching { load(record.owner) }.onFailure { error ->
                record.quarantined = true
                UniverseMod.logger.error(
                    "Failed to load universe {} generation {}; it has been quarantined",
                    record.owner,
                    record.activeGeneration,
                    error,
                )
            }
        }
        saveCatalog()
        UniverseMod.logger.info("Loaded {} personal universe bundles", loaded.size)
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

    fun loaded(owner: UUID): UniverseBundle? = loaded[owner]

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

    fun activate(bundle: UniverseBundle) {
        requireServerThread()
        val previous = loaded[bundle.owner]
        require(previous == null || previous === bundle || previous.generation != bundle.generation) {
            "Universe ${bundle.owner} generation ${bundle.generation} is already active"
        }

        for (level in bundle.levels.values) {
            level.save(null, true, false)
        }
        val record = catalog.getOrCreate(bundle.owner)
        record.activeGeneration = bundle.generation
        record.stopped = false
        record.quarantined = false
        loaded[bundle.owner] = bundle
        applyBorders(bundle, record)
        saveCatalog()

        if (previous != null && previous !== bundle) {
            unloadBundle(previous)
        }
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

    fun unload(owner: UUID): Boolean {
        requireServerThread()
        val bundle = loaded.remove(owner) ?: return false
        unloadBundle(bundle)
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

    private fun unloadBundle(bundle: UniverseBundle) {
        val server = requireServerThread()
        for (level in bundle.levels.values.toList().asReversed()) {
            if (!server.removeCustomLevel(level)) {
                UniverseMod.logger.warn("Universe level {} was not loaded during unload", level.dimension().identifier())
            }
        }
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
