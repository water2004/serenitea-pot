package org.edtp.sereniteapot.level;

import net.casual.arcade.dimensions.level.CustomLevel;
import net.casual.arcade.dimensions.level.LevelPersistence;
import net.casual.arcade.dimensions.level.LevelProperties;
import net.casual.arcade.dimensions.level.builder.CustomLevelBuilder;
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevels;
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevelsBuilder;
import net.casual.arcade.dimensions.utils.DimensionUtilsKt;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.model.SereniteaPotCatalog;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.persistence.SereniteaPotCatalogRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 尘歌壶元数据和 Arcade 自定义维度的运行时所有者。
 *
 * <p>持久化目录可以存在但维度默认不加载；只有主人进入时才调用 {@link #load(UUID)}。
 * 所有增删维度操作都要求位于服务器线程，并由生命周期服务保证其中已经没有玩家。</p>
 */
public final class SereniteaPotManager {
    private static MinecraftServer server;
    private static SereniteaPotCatalogRepository repository;
    private static SereniteaPotCatalog catalog = new SereniteaPotCatalog();
    private static final Map<UUID, SereniteaPotBundle> loaded = new LinkedHashMap<>();

    private SereniteaPotManager() {
    }

    public static void start(MinecraftServer server) {
        if (SereniteaPotManager.server != null) {
            throw new IllegalStateException("SereniteaPotManager is already attached");
        }
        SereniteaPotCatalogRepository candidateRepository = new SereniteaPotCatalogRepository(
            server.getWorldPath(LevelResource.ROOT).resolve(SereniteaPotMod.MOD_ID)
        );
        SereniteaPotCatalog loadedCatalog;
        try {
            loadedCatalog = candidateRepository.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load Serenitea Pot catalog", error);
        }
        repository = candidateRepository;
        catalog = loadedCatalog;
        SereniteaPotManager.server = server;
        SereniteaPotMod.LOGGER.info(
            "Loaded metadata for {} personal Serenitea Pots; dimensions remain unloaded",
            catalog.getPlayers().size()
        );
    }

    public static void stop(MinecraftServer server) {
        if (SereniteaPotManager.server != server) return;
        SereniteaPotDeletionService.awaitPending(server);
        saveCatalog();
        awaitCatalogWrites();
        loaded.clear();
        repository = null;
        SereniteaPotManager.server = null;
    }

    /** Shutdown only: the queued work contains no server-thread continuations. */
    public static void awaitCatalogWrites() {
        if (repository != null) {
            try {
                repository.pendingWrites().join();
            } catch (CompletionException error) {
                SereniteaPotMod.LOGGER.error("Failed to flush Serenitea Pot catalog during shutdown", error);
            }
        }
    }

    public static SereniteaPotCatalog catalog() {
        return catalog;
    }

    public static SereniteaPotRecord record(UUID owner) {
        return catalog.getPlayers().get(owner);
    }

    public static SereniteaPotRecord getOrCreateRecord(UUID owner) {
        return catalog.getOrCreate(owner);
    }

    public static SereniteaPotBundle loaded(UUID owner) {
        return loaded.get(owner);
    }

    static Set<UUID> loadedOwners() {
        return Set.copyOf(loaded.keySet());
    }

    public static SereniteaPotBundle createStaging(UUID owner, long generation, long seed) {
        MinecraftServer server = requireServerThread();
        if (generation <= 0) throw new IllegalArgumentException("Generation must be positive");
        for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
            if (server.getLevel(SereniteaPotLevelKeys.key(owner, generation, dimension)) != null) {
                throw new IllegalArgumentException(
                    "Serenitea Pot " + owner + " generation " + generation + " is already loaded"
                );
            }
        }

        // 三个维度必须由同一个 VanillaLikeLevelsBuilder 创建，Arcade 才会为它们建立
        // 主世界/下界/末地的成组传送门映射。
        VanillaLikeLevelsBuilder builder = new VanillaLikeLevelsBuilder();
        Difficulty difficulty = catalog.getOrCreate(owner).getDifficulty();
        for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
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
                    .dimensionKey(SereniteaPotLevelKeys.key(owner, generation, dimension))
                    .dimensionType(template.dimensionTypeRegistration())
                    .chunkGenerator(voidGenerator(biome))
                    .difficulty(difficultyProperties(difficulty))
                    .persistence(LevelPersistence.Permanent)
                    .seed(seed)
            );
        }
        VanillaLikeLevels built = builder.build(server);
        EnumMap<SereniteaPotDimension, CustomLevel> levels = new EnumMap<>(SereniteaPotDimension.class);
        try {
            for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
                CustomLevel level = built.getOrThrow(dimension.vanilla());
                // Initialize new dimensions once. Existing dimensions restore their own
                // Vanilla world_border SavedData; loading must never recenter them.
                level.getWorldBorder().setCenter(ChunkPos.ZERO.getMiddleBlockX(), ChunkPos.ZERO.getMiddleBlockZ());
                level.getWorldBorder().setSize((catalog.getOrCreate(owner).getMaxRadiusChunks() * 2.0 + 1.0)
                    * SectionPos.SECTION_SIZE);
                DimensionUtilsKt.addCustomLevel(server, level);
                levels.put(dimension, level);
            }
        } catch (RuntimeException error) {
            var added = new ArrayList<>(levels.values());
            Collections.reverse(added);
            for (CustomLevel level : added) {
                try {
                    DimensionUtilsKt.removeCustomLevel(server, level);
                } catch (RuntimeException ignored) {
                }
            }
            throw error;
        }
        return new SereniteaPotBundle(owner, generation, levels);
    }

    /** Starts persistence for a new active generation without waiting on the I/O pool. */
    public static GenerationCommit beginCommitGeneration(
        SereniteaPotBundle bundle,
        Map<SereniteaPotDimension, SereniteaPotSlotRecord> replacementSlots,
        int maximumRadiusChunks
    ) {
        requireServerThread();
        SereniteaPotBundle previous = loaded.get(bundle.owner());
        if (previous != null && previous != bundle && previous.generation() == bundle.generation()) {
            throw new IllegalArgumentException(
                "Serenitea Pot " + bundle.owner() + " generation " + bundle.generation() + " is already active"
            );
        }
        if (previous != null && previous.levels().values().stream().anyMatch(level -> !level.players().isEmpty())) {
            throw new IllegalArgumentException(
                "Cannot replace Serenitea Pot " + bundle.owner() + " while players still occupy the previous generation"
            );
        }

        SereniteaPotRecord record = catalog.getOrCreate(bundle.owner());
        applyDifficulty(bundle, record.getDifficulty());
        for (CustomLevel level : bundle.levels().values()) {
            level.save(null, true, false);
        }
        long oldGeneration = record.getActiveGeneration();
        int oldMaximumRadiusChunks = record.getMaxRadiusChunks();
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots = copySlots(record.getSlots());
        record.setActiveGeneration(bundle.generation());
        record.setMaxRadiusChunks(maximumRadiusChunks);
        record.getSlots().clear();
        record.getSlots().putAll(copySlots(replacementSlots));
        try {
            return new GenerationCommit(
                bundle, previous == bundle ? null : previous, record, oldGeneration,
                oldMaximumRadiusChunks, oldSlots, saveCatalog()
            );
        } catch (RuntimeException error) {
            record.setActiveGeneration(oldGeneration);
            record.setMaxRadiusChunks(oldMaximumRadiusChunks);
            record.getSlots().clear();
            record.getSlots().putAll(oldSlots);
            throw error;
        }
    }

    /** Poll and publish a prepared generation after its IO-only persistence future completes. */
    public static SereniteaPotBundle finishCommitGeneration(GenerationCommit commit) {
        requireServerThread();
        if (!commit.isDone()) throw new IllegalStateException("Generation commit is still pending");
        if (commit.finished) throw new IllegalStateException("Generation commit was already finished");
        commit.finished = true;
        try {
            commit.persisted.join();
        } catch (RuntimeException error) {
            commit.record.setActiveGeneration(commit.oldGeneration);
            commit.record.setMaxRadiusChunks(commit.oldMaximumRadiusChunks);
            commit.record.getSlots().clear();
            commit.record.getSlots().putAll(commit.oldSlots);
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
            throw new IllegalStateException("Failed to persist Serenitea Pot generation", cause);
        }
        loaded.put(commit.bundle.owner(), commit.bundle);
        return commit.previous;
    }

    public static final class GenerationCommit {
        private final SereniteaPotBundle bundle;
        private final SereniteaPotBundle previous;
        private final SereniteaPotRecord record;
        private final long oldGeneration;
        private final int oldMaximumRadiusChunks;
        private final EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots;
        private final CompletableFuture<Void> persisted;
        private boolean finished;

        private GenerationCommit(
            SereniteaPotBundle bundle, SereniteaPotBundle previous, SereniteaPotRecord record,
            long oldGeneration, int oldMaximumRadiusChunks,
            EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> oldSlots,
            CompletableFuture<Void> persisted
        ) {
            this.bundle = bundle;
            this.previous = previous;
            this.record = record;
            this.oldGeneration = oldGeneration;
            this.oldMaximumRadiusChunks = oldMaximumRadiusChunks;
            this.oldSlots = oldSlots;
            this.persisted = persisted;
        }

        public boolean isDone() { return persisted.isDone(); }
        public CompletableFuture<Void> persistedFuture() { return persisted; }
    }

    public static SereniteaPotBundle load(UUID owner) {
        MinecraftServer server = requireServerThread();
        SereniteaPotBundle existing = loaded.get(owner);
        if (existing != null) return existing;
        SereniteaPotRecord record = catalog.getPlayers().get(owner);
        if (record == null) throw new IllegalArgumentException("No Serenitea Pot metadata for " + owner);
        if (!record.exists()) throw new IllegalArgumentException("Serenitea Pot " + owner + " does not exist");

        EnumMap<SereniteaPotDimension, CustomLevel> levels = new EnumMap<>(SereniteaPotDimension.class);
        try {
            for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
                var key = SereniteaPotLevelKeys.key(owner, record.getActiveGeneration(), dimension);
                CustomLevel level = DimensionUtilsKt.loadCustomLevel(server, key);
                if (level == null) {
                    throw new IllegalStateException("Missing persisted Serenitea Pot level " + key.identifier());
                }
                levels.put(dimension, level);
            }
        } catch (RuntimeException error) {
            var added = new ArrayList<>(levels.values());
            Collections.reverse(added);
            for (CustomLevel level : added) {
                try {
                    DimensionUtilsKt.removeCustomLevel(server, level);
                } catch (RuntimeException ignored) {
                }
            }
            throw error;
        }
        SereniteaPotBundle bundle = new SereniteaPotBundle(owner, record.getActiveGeneration(), levels);
        applyDifficulty(bundle, record.getDifficulty());
        loaded.put(owner, bundle);
        return bundle;
    }

    static boolean unloadEvacuated(UUID owner) {
        requireServerThread();
        SereniteaPotBundle bundle = loaded.get(owner);
        if (bundle == null) return true;
        if (bundle.levels().values().stream().anyMatch(level -> !level.players().isEmpty())) {
            throw new IllegalArgumentException("Cannot unload Serenitea Pot " + owner + " while players still occupy it");
        }
        if (!unloadBundle(bundle)) return false;
        loaded.remove(owner);
        return true;
    }

    public static CompletableFuture<Void> saveCatalog() {
        CompletableFuture<Void> saved = repository == null
            ? CompletableFuture.completedFuture(null) : repository.saveAsync(catalog);
        saved.whenComplete((ignored, error) -> {
            if (error != null) SereniteaPotMod.LOGGER.error("Failed to save Serenitea Pot catalog", error);
        });
        return saved;
    }

    /** Changes the shared difficulty of all three dimensions without touching public worlds. */
    public static CompletableFuture<Void> setDifficulty(UUID owner, Difficulty difficulty) {
        requireServerThread();
        SereniteaPotRecord record = catalog.getPlayers().get(owner);
        if (record == null || !record.exists()) {
            throw new IllegalArgumentException("Serenitea Pot " + owner + " does not exist");
        }
        record.setDifficulty(difficulty);
        SereniteaPotBundle bundle = loaded.get(owner);
        if (bundle != null) {
            applyDifficulty(bundle, difficulty);
        }
        return saveCatalog();
    }

    static boolean deleteEvacuatedLevel(CustomLevel level) {
        MinecraftServer server = requireServerThread();
        if (!level.players().isEmpty()) {
            throw new IllegalArgumentException("Cannot delete occupied level " + level.dimension().identifier());
        }
        try {
            DimensionUtilsKt.deleteCustomLevel(server, level);
        } catch (RuntimeException error) {
            SereniteaPotMod.LOGGER.warn("Failed to delete Serenitea Pot level {}", level.dimension().identifier(), error);
        }
        boolean detached = server.getLevel(level.dimension()) != level;
        boolean directoryRemoved = !Files.exists(DimensionUtilsKt.getDimensionPath(server, level.dimension()));
        return detached && directoryRemoved;
    }

    private static boolean unloadBundle(SereniteaPotBundle bundle) {
        MinecraftServer server = requireServerThread();
        boolean complete = true;
        var levels = new ArrayList<>(bundle.levels().values());
        Collections.reverse(levels);
        for (CustomLevel level : levels) {
            if (server.getLevel(level.dimension()) != level) continue;
            try {
                DimensionUtilsKt.removeCustomLevel(server, level);
            } catch (RuntimeException error) {
                SereniteaPotMod.LOGGER.warn("Failed to unload Serenitea Pot level {}", level.dimension().identifier(), error);
            }
            if (server.getLevel(level.dimension()) == level) complete = false;
        }
        return complete;
    }

    private static void applyDifficulty(SereniteaPotBundle bundle, Difficulty difficulty) {
        var packet = new ClientboundChangeDifficultyPacket(difficulty, false);
        for (CustomLevel level : bundle.levels().values()) {
            level.getProperties().setDifficulty(Optional.of(difficultyProperties(difficulty)));
            level.players().forEach(player -> player.connection.send(packet));
        }
    }

    private static LevelProperties.DifficultyProperties difficultyProperties(Difficulty difficulty) {
        return new LevelProperties.DifficultyProperties(difficulty, false);
    }

    private static EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> copySlots(
        Map<SereniteaPotDimension, SereniteaPotSlotRecord> source
    ) {
        EnumMap<SereniteaPotDimension, SereniteaPotSlotRecord> copy = new EnumMap<>(SereniteaPotDimension.class);
        copy.putAll(source);
        return copy;
    }

    private static MinecraftServer requireServerThread() {
        MinecraftServer current = server;
        if (current == null) throw new IllegalStateException("SereniteaPotManager is not attached to a server");
        if (!current.isSameThread()) {
            throw new IllegalStateException("Serenitea Pot world mutation must run on the server thread");
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
