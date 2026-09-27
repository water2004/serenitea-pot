package org.edtp.sereniteapot.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.edtp.sereniteapot.SereniteaPotMod;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStateStore {
    // Version of the file container, independent of the catalog and each pot payload.
    private static final int SCHEMA_VERSION = 1;

    private final Path root;
    private final Map<UUID, CompoundTag> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();

    public PlayerStateStore(Path root) {
        this.root = root;
    }

    public CompoundTag get(UUID player, String stateKey) {
        synchronized (lock(player)) {
            CompoundTag states = loadPlayer(player);
            if (!states.contains(stateKey)) {
                return null;
            }
            return states.getCompound(stateKey).map(CompoundTag::copy).orElseThrow(() ->
                    new InvalidPlayerStateException("Invalid isolated player state " + stateKey));
        }
    }

    public void put(UUID player, String stateKey, CompoundTag state) {
        synchronized (lock(player)) {
            CompoundTag states = loadPlayer(player);
            states.put(stateKey, state.copy());
            savePlayer(player, states);
        }
    }

    public void clear() {
        cache.clear();
        playerLocks.clear();
    }

    private Object lock(UUID player) {
        return playerLocks.computeIfAbsent(player, ignored -> new Object());
    }

    private CompoundTag loadPlayer(UUID player) {
        return cache.computeIfAbsent(player, ignored -> {
            Path file = file(player);
            if (Files.notExists(file)) {
                CompoundTag created = new CompoundTag();
                created.putInt("schemaVersion", SCHEMA_VERSION);
                return created;
            }
            if (!Files.isRegularFile(file)) {
                throw new InvalidPlayerStateException("Isolated player state is not a file " + file);
            }
            try {
                CompoundTag states = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                if (states == null || !(states.get("schemaVersion") instanceof IntTag)
                        || states.getIntOr("schemaVersion", -1) != SCHEMA_VERSION) {
                    throw new InvalidPlayerStateException("Unsupported isolated player state schema in " + file);
                }
                return states;
            } catch (IOException error) {
                SereniteaPotMod.LOGGER.error("Failed to read isolated player state {}", file, error);
                throw new InvalidPlayerStateException("Failed to read isolated player state " + file, error);
            }
        });
    }

    private void savePlayer(UUID player, CompoundTag states) {
        try {
            Files.createDirectories(root);
            Path target = file(player);
            Path temporary = root.resolve(player + ".dat.tmp");
            NbtIo.writeCompressed(states, temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to save isolated player state for " + player, error);
        }
    }

    private Path file(UUID player) {
        return root.resolve(player + ".dat");
    }

    public static final class InvalidPlayerStateException extends IllegalStateException {
        public InvalidPlayerStateException(String message) {
            super(message);
        }

        public InvalidPlayerStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
