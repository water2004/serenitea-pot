package org.edtp.sereniteapot.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Util;
import org.edtp.sereniteapot.SereniteaPotMod;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Cached private player data. Only detached NBT crosses onto Minecraft's I/O pool. */
public final class PlayerStateStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private final Path root;
    private final Executor io;
    private final Map<UUID, Entry> players = new HashMap<>();
    private boolean closed;

    public PlayerStateStore(Path root) { this(root, Util.ioPool()); }

    PlayerStateStore(Path root, Executor io) {
        this.root = root;
        this.io = io;
    }

    public CompletableFuture<Void> preload(UUID player) {
        return entry(player).loaded.thenApply(ignored -> null);
    }

    /** Never waits for disk: callers may retry after preload completes. */
    public CompoundTag get(UUID player, String key) {
        Entry entry = entry(player);
        synchronized (entry) {
            CompoundTag states = ready(entry.loaded);
            checkWriteFailure(entry);
            if (!states.contains(key)) return null;
            return states.getCompound(key).map(CompoundTag::copy).orElseThrow(() ->
                new InvalidPlayerStateException("Invalid isolated player state " + key));
        }
    }

    public CompletableFuture<Void> put(UUID player, String key, CompoundTag state) {
        Entry entry = entry(player);
        synchronized (entry) {
            CompoundTag states = ready(entry.loaded);
            checkWriteFailure(entry);
            states.put(key, state.copy());
            // Own a complete image at submission, never the cache or a live player.
            CompoundTag snapshot = states.copy();
            entry.written = entry.written.thenRunAsync(() -> write(player, snapshot), io);
            entry.written.whenComplete((ignored, error) -> {
                if (error != null) SereniteaPotMod.LOGGER.error("Failed to save private state for {}", player, error);
            });
            return entry.written;
        }
    }

    public synchronized CompletableFuture<Void> flush() {
        return CompletableFuture.allOf(players.values().stream().map(entry -> {
            synchronized (entry) { return entry.written; }
        }).toArray(CompletableFuture[]::new));
    }

    /** Shutdown only; no game-thread continuation is needed by these I/O tasks. */
    @Override
    public void close() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            closed = true;
            // Failed preloads were already reported to their caller. Still drain
            // them so no old-server task can overlap a reopened save directory.
            CompletableFuture<?> reads = CompletableFuture.allOf(players.values().stream()
                .map(entry -> entry.loaded.handle((value, error) -> null))
                .toArray(CompletableFuture[]::new));
            pending = CompletableFuture.allOf(reads, flush());
        }
        try {
            pending.join();
        } finally {
            synchronized (this) { players.clear(); }
        }
    }

    private synchronized Entry entry(UUID player) {
        if (closed) throw new IllegalStateException("Player state store is closed");
        return players.computeIfAbsent(player, ignored -> new Entry(
            CompletableFuture.supplyAsync(() -> read(player), io)));
    }

    private static CompoundTag ready(CompletableFuture<CompoundTag> future) {
        if (!future.isDone()) throw new LoadingPlayerStateException();
        try {
            return future.getNow(null);
        } catch (CompletionException error) {
            if (error.getCause() instanceof InvalidPlayerStateException invalid) throw invalid;
            throw new InvalidPlayerStateException("Failed to load isolated player state", error.getCause());
        }
    }

    private static void checkWriteFailure(Entry entry) {
        if (entry.written.isCompletedExceptionally()) {
            throw new InvalidPlayerStateException("A previous private player state save failed");
        }
    }

    private CompoundTag read(UUID player) {
        Path file = root.resolve(player + ".dat");
        if (Files.notExists(file)) {
            CompoundTag created = new CompoundTag();
            created.putInt("schemaVersion", SCHEMA_VERSION);
            return created;
        }
        if (!Files.isRegularFile(file)) throw new InvalidPlayerStateException("Not a player state file: " + file);
        try {
            CompoundTag states = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (!(states.get("schemaVersion") instanceof IntTag)
                || states.getIntOr("schemaVersion", -1) != SCHEMA_VERSION) {
                throw new InvalidPlayerStateException("Unsupported player state schema: " + file);
            }
            return states;
        } catch (IOException error) {
            throw new InvalidPlayerStateException("Failed to read player state: " + file, error);
        }
    }

    private void write(UUID player, CompoundTag state) {
        try {
            Files.createDirectories(root);
            Path target = root.resolve(player + ".dat");
            Path temporary = root.resolve(player + ".dat.tmp");
            NbtIo.writeCompressed(state, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new InvalidPlayerStateException("Failed to save player state for " + player, error);
        }
    }

    private static final class Entry {
        final CompletableFuture<CompoundTag> loaded;
        CompletableFuture<Void> written = CompletableFuture.completedFuture(null);
        Entry(CompletableFuture<CompoundTag> loaded) { this.loaded = loaded; }
    }

    public static final class LoadingPlayerStateException extends RuntimeException {
        public LoadingPlayerStateException() { super("Player state is still loading"); }
    }

    public static final class InvalidPlayerStateException extends IllegalStateException {
        public InvalidPlayerStateException(String message) { super(message); }
        public InvalidPlayerStateException(String message, Throwable cause) { super(message, cause); }
    }
}
