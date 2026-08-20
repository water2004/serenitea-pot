package org.edtp.sereniteapot.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.edtp.sereniteapot.SereniteaPotMod;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerStateStore {
    private static final int SCHEMA_VERSION = 1;

    private final Path root;
    private final Map<UUID, CompoundTag> cache = new HashMap<>();

    public PlayerStateStore(Path root) {
        this.root = root;
    }

    public CompoundTag get(UUID player, String stateKey) {
        return loadPlayer(player).getCompound(stateKey).map(CompoundTag::copy).orElse(null);
    }

    public void put(UUID player, String stateKey, CompoundTag snapshot) {
        CompoundTag states = loadPlayer(player);
        states.put(stateKey, snapshot.copy());
        savePlayer(player, states);
    }

    public void clear() {
        cache.clear();
    }

    private CompoundTag loadPlayer(UUID player) {
        return cache.computeIfAbsent(player, ignored -> {
            Path file = file(player);
            if (!Files.isRegularFile(file)) {
                CompoundTag created = new CompoundTag();
                created.putInt("schemaVersion", SCHEMA_VERSION);
                return created;
            }
            try {
                return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            } catch (IOException error) {
                SereniteaPotMod.LOGGER.error("Failed to read isolated player state {}", file, error);
                throw new IllegalStateException("Failed to read isolated player state " + file, error);
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
}
