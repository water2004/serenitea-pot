package org.edtp.universe.level;

import net.casual.arcade.dimensions.level.CustomLevel;
import net.casual.arcade.dimensions.level.LevelPersistence;
import net.casual.arcade.dimensions.level.builder.CustomLevelBuilder;
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevels;
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevelsBuilder;
import net.casual.arcade.dimensions.utils.DimensionUtilsKt;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.io.file.PathUtils;
import org.edtp.universe.UniverseMod;
import org.edtp.universe.model.UniverseCatalog;
import org.edtp.universe.model.UniverseDimension;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.model.UniverseSlotRecord;
import org.edtp.universe.persistence.UniverseCatalogRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class UniverseManager {
    private static MinecraftServer server;
    private static UniverseCatalogRepository repository;
    private static UniverseCatalog catalog = new UniverseCatalog();
    private static final Map<UUID, UniverseBundle> loaded = new LinkedHashMap<>();

    private UniverseManager() {
    }

    public static void start(MinecraftServer server) {
        if (UniverseManager.server != null) {
            throw new IllegalStateException("UniverseManager is already attached");
        }
        UniverseManager.server = server;
        repository = new UniverseCatalogRepository(
            server.getWorldPath(LevelResource.ROOT).resolve(UniverseMod.MOD_ID)
        );
        try {
            catalog = repository.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load universe catalog", error);
        }
        cleanupInactiveGenerations(server);
        UniverseMod.LOGGER.info(
            "Loaded metadata for {} personal universes; dimensions remain unloaded",
            catalog.getPlayers().size()
        );
    }

    public static void stop(MinecraftServer server) {
        if (UniverseManager.server != server) return;
        saveCatalog();
        loaded.clear();
        repository = null;
        UniverseManager.server = null;
    }

    public static UniverseCatalog catalog() {
        return catalog;
    }

    public static UniverseRecord record(UUID owner) {
        return catalog.getPlayers().get(owner);
    }

    public static UniverseRecord getOrCreateRecord(UUID owner) {
        return catalog.getOrCreate(owner);
    }

    public static UniverseRecord removeRecord(UUID owner) {
        return catalog.getPlayers().remove(owner);
    }

    public static UniverseBundle loaded(UUID owner) {
        return loaded.get(owner);
    }

    static Set<UUID> loadedOwners() {
        return Set.copyOf(loaded.keySet());
    }

    public static UUID ownerOf(ServerLevel level) {
        UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(level.dimension());
        return identity == null ? null : identity.owner();
    }

    public static UniverseBundle createStaging(UUID owner, long generation, long seed) {
        MinecraftServer server = requireServerThread();
        if (generation <= 0) throw new IllegalArgumentException("Generation must be positive");
        for (UniverseDimension dimension : UniverseDimension.values()) {
            if (server.getLevel(UniverseLevelKeys.key(owner, generation, dimension)) != null) {
                throw new IllegalArgumentException(
                    "Universe " + owner + " generation " + generation + " is already loaded"
                );
            }
        }

        VanillaLikeLevelsBuilder builder = new VanillaLikeLevelsBuilder();
        for (UniverseDimension dimension : UniverseDimension.values()) {
            ServerLevel template = server.getLevel(dimension.vanillaLevelKey());
            if (template == null) {
                throw new IllegalStateException(
                    "Missing vanilla template dimension " + dimension.vanillaLevelKey().identifier()
                );
            }
            Holder<Biome> biome = template.getBiome(template.getRespawnData().pos());
            builder.set(
                dimension.vanilla(),
                new CustomLevelBuilder()
                    .dimensionKey(UniverseLevelKeys.key(owner, generation, dimension))
                    .dimensionType(template.dimensionTypeRegistration())
                    .chunkGenerator(voidGenerator(biome))
                    .persistence(LevelPersistence.Permanent)
                    .seed(seed)
            );
        }
        VanillaLikeLevels built = builder.build(server);
        EnumMap<UniverseDimension, CustomLevel> levels = new EnumMap<>(UniverseDimension.class);
        try {
            for (UniverseDimension dimension : UniverseDimension.values()) {
                CustomLevel level = built.getOrThrow(dimension.vanilla());
                DimensionUtilsKt.addCustomLevel(server, level);
                levels.put(dimension, level);
            }
        } catch (Throwable error) {
            var added = new ArrayList<>(levels.values());
            Collections.reverse(added);
            for (CustomLevel level : added) {
                try {
                    DimensionUtilsKt.removeCustomLevel(server, level);
                } catch (Throwable ignored) {
                }
            }
            throw error;
        }
        return new UniverseBundle(owner, generation, levels);
    }

    public static UniverseBundle activate(
        UniverseBundle bundle,
        Map<UniverseDimension, UniverseSlotRecord> replacementSlots
    ) {
        requireServerThread();
        UniverseBundle previous = loaded.get(bundle.owner());
        if (previous != null && previous != bundle && previous.generation() == bundle.generation()) {
            throw new IllegalArgumentException(
                "Universe " + bundle.owner() + " generation " + bundle.generation() + " is already active"
            );
        }
        if (previous != null && previous.levels().values().stream().anyMatch(level -> !level.players().isEmpty())) {
            throw new IllegalArgumentException(
                "Cannot replace universe " + bundle.owner() + " while players still occupy the previous generation"
            );
        }

        for (CustomLevel level : bundle.levels().values()) {
            level.save(null, true, false);
        }
        UniverseRecord record = catalog.getOrCreate(bundle.owner());
        long oldGeneration = record.getActiveGeneration();
        EnumMap<UniverseDimension, UniverseSlotRecord> oldSlots = copySlots(record.getSlots());
        boolean oldStopped = record.isStopped();
        boolean oldQuarantined = record.isQuarantined();
        record.setActiveGeneration(bundle.generation());
        record.getSlots().clear();
        record.getSlots().putAll(copySlots(replacementSlots));
        record.setStopped(false);
        record.setQuarantined(false);
        try {
            applyBorders(bundle, record);
            saveCatalog();
        } catch (Throwable error) {
            record.setActiveGeneration(oldGeneration);
            record.getSlots().clear();
            record.getSlots().putAll(oldSlots);
            record.setStopped(oldStopped);
            record.setQuarantined(oldQuarantined);
            throw error;
        }
        loaded.put(bundle.owner(), bundle);
        return previous == bundle ? null : previous;
    }

    public static UniverseBundle load(UUID owner) {
        MinecraftServer server = requireServerThread();
        UniverseBundle existing = loaded.get(owner);
        if (existing != null) return existing;
        UniverseRecord record = catalog.getPlayers().get(owner);
        if (record == null) throw new IllegalArgumentException("No universe metadata for " + owner);
        if (!record.exists()) throw new IllegalArgumentException("Universe " + owner + " does not exist");

        EnumMap<UniverseDimension, CustomLevel> levels = new EnumMap<>(UniverseDimension.class);
        try {
            for (UniverseDimension dimension : UniverseDimension.values()) {
                var key = UniverseLevelKeys.key(owner, record.getActiveGeneration(), dimension);
                CustomLevel level = DimensionUtilsKt.loadCustomLevel(server, key);
                if (level == null) {
                    throw new IllegalStateException("Missing persisted universe level " + key.identifier());
                }
                levels.put(dimension, level);
            }
        } catch (Throwable error) {
            var added = new ArrayList<>(levels.values());
            Collections.reverse(added);
            for (CustomLevel level : added) {
                try {
                    DimensionUtilsKt.removeCustomLevel(server, level);
                } catch (Throwable ignored) {
                }
            }
            throw error;
        }
        UniverseBundle bundle = new UniverseBundle(owner, record.getActiveGeneration(), levels);
        loaded.put(owner, bundle);
        applyBorders(bundle, record);
        return bundle;
    }

    static boolean unloadEvacuated(UUID owner) {
        requireServerThread();
        UniverseBundle bundle = loaded.get(owner);
        if (bundle == null) return true;
        if (bundle.levels().values().stream().anyMatch(level -> !level.players().isEmpty())) {
            throw new IllegalArgumentException("Cannot unload universe " + owner + " while players still occupy it");
        }
        if (!unloadBundle(bundle)) return false;
        loaded.remove(owner);
        return true;
    }

    public static void saveCatalog() {
        if (repository == null) return;
        try {
            repository.save(catalog);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to save universe catalog", error);
        }
    }

    static boolean deleteEvacuatedLevel(CustomLevel level) {
        MinecraftServer server = requireServerThread();
        if (!level.players().isEmpty()) {
            throw new IllegalArgumentException("Cannot delete occupied level " + level.dimension().identifier());
        }
        try {
            DimensionUtilsKt.deleteCustomLevel(server, level);
        } catch (Throwable error) {
            UniverseMod.LOGGER.warn("Failed to delete universe level {}", level.dimension().identifier(), error);
        }
        boolean detached = server.getLevel(level.dimension()) != level;
        boolean directoryRemoved = !Files.exists(DimensionUtilsKt.getDimensionPath(server, level.dimension()));
        return detached && directoryRemoved;
    }

    private static boolean unloadBundle(UniverseBundle bundle) {
        MinecraftServer server = requireServerThread();
        boolean complete = true;
        var levels = new ArrayList<>(bundle.levels().values());
        Collections.reverse(levels);
        for (CustomLevel level : levels) {
            if (server.getLevel(level.dimension()) != level) continue;
            try {
                DimensionUtilsKt.removeCustomLevel(server, level);
            } catch (Throwable error) {
                UniverseMod.LOGGER.warn("Failed to unload universe level {}", level.dimension().identifier(), error);
            }
            if (server.getLevel(level.dimension()) == level) complete = false;
        }
        return complete;
    }

    private static void applyBorders(UniverseBundle bundle, UniverseRecord record) {
        for (UniverseDimension dimension : UniverseDimension.values()) {
            UniverseSlotRecord slot = record.getSlots().get(dimension);
            var border = bundle.get(dimension).getWorldBorder();
            if (slot == null) {
                border.setCenter(0.0, 0.0);
                border.setSize(record.getMaxRadius() * 2.0 + 1.0);
            } else {
                border.setCenter(slot.centerX() + 0.5, slot.centerZ() + 0.5);
                border.setSize(slot.radius() * 2.0 + 1.0);
            }
        }
    }

    private static void cleanupInactiveGenerations(MinecraftServer server) {
        Path universeRoot = server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions").resolve(UniverseMod.MOD_ID).resolve("u").toAbsolutePath().normalize();
        for (Map.Entry<UUID, UniverseRecord> entry : catalog.getPlayers().entrySet()) {
            UUID owner = entry.getKey();
            UniverseRecord record = entry.getValue();
            Path ownerRoot = universeRoot.resolve(owner.toString()).normalize();
            if (!universeRoot.equals(ownerRoot.getParent()) || !Files.isDirectory(ownerRoot)) continue;
            try (Stream<Path> children = Files.list(ownerRoot)) {
                children.filter(Files::isDirectory).forEach(candidate -> {
                    Path resolved = candidate.toAbsolutePath().normalize();
                    String name = resolved.getFileName().toString();
                    Long generation = parseGeneration(name);
                    if (!ownerRoot.equals(resolved.getParent())
                        || generation == null
                        || generation == record.getActiveGeneration()) {
                        return;
                    }
                    try {
                        PathUtils.deleteDirectory(resolved);
                        UniverseMod.LOGGER.info("Deleted inactive universe generation {} for {}", generation, owner);
                    } catch (IOException error) {
                        UniverseMod.LOGGER.warn(
                            "Failed to delete inactive universe generation {} for {}", generation, owner, error
                        );
                    }
                });
            } catch (IOException error) {
                UniverseMod.LOGGER.warn("Failed to inspect universe generation directory for {}", owner, error);
            }
        }
    }

    private static Long parseGeneration(String name) {
        if (!name.startsWith("g")) return null;
        try {
            return Long.parseLong(name.substring(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static EnumMap<UniverseDimension, UniverseSlotRecord> copySlots(
        Map<UniverseDimension, UniverseSlotRecord> source
    ) {
        EnumMap<UniverseDimension, UniverseSlotRecord> copy = new EnumMap<>(UniverseDimension.class);
        copy.putAll(source);
        return copy;
    }

    private static MinecraftServer requireServerThread() {
        MinecraftServer current = server;
        if (current == null) throw new IllegalStateException("UniverseManager is not attached to a server");
        if (!current.isSameThread()) {
            throw new IllegalStateException("Universe world mutation must run on the server thread");
        }
        return current;
    }

    private static FlatLevelSource voidGenerator(Holder<Biome> biome) {
        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(Optional.empty(), biome, new ArrayList<>());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
        settings.updateLayers();
        return new FlatLevelSource(settings);
    }
}
