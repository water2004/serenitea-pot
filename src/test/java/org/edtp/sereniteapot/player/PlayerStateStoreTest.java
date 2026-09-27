package org.edtp.sereniteapot.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerStateStoreTest {
    @Test
    void serializesConcurrentRealmUpdatesPerPlayer(@TempDir Path directory) throws Exception {
        PlayerStateStore store = new PlayerStateStore(directory);
        UUID player = UUID.randomUUID();

        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 16; index++) {
                int value = index;
                tasks.add(executor.submit(() -> {
                    CompoundTag snapshot = new CompoundTag();
                    snapshot.putInt("value", value);
                    store.put(player, "realm_" + value, snapshot);
                }));
            }
            for (var task : tasks) {
                task.get();
            }
        }

        for (int index = 0; index < 16; index++) {
            assertEquals(index, store.get(player, "realm_" + index).getIntOr("value", -1));
        }
    }

    @Test
    void rejectsUnsupportedOrMissingSchemaBeforeCacheAndPreservesFile(@TempDir Path directory) throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".dat");
        for (Integer version : new Integer[]{null, 2}) {
            CompoundTag root = new CompoundTag();
            if (version != null) root.putInt("schemaVersion", version);
            NbtIo.writeCompressed(root, file);
            byte[] original = Files.readAllBytes(file);
            PlayerStateStore store = new PlayerStateStore(directory);
            assertThrows(PlayerStateStore.InvalidPlayerStateException.class,
                    () -> store.get(player, "realm"));
            assertThrows(PlayerStateStore.InvalidPlayerStateException.class,
                    () -> store.put(player, "realm", new CompoundTag()));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
        CompoundTag wrongType = new CompoundTag();
        wrongType.putDouble("schemaVersion", 1.0);
        NbtIo.writeCompressed(wrongType, file);
        byte[] original = Files.readAllBytes(file);
        assertThrows(PlayerStateStore.InvalidPlayerStateException.class,
                () -> new PlayerStateStore(directory).get(player, "realm"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void rejectsPresentNonCompoundState(@TempDir Path directory) throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".dat");
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", 1);
        root.putInt("realm", 3);
        NbtIo.writeCompressed(root, file);
        byte[] original = Files.readAllBytes(file);
        assertThrows(PlayerStateStore.InvalidPlayerStateException.class,
                () -> new PlayerStateStore(directory).get(player, "realm"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void currentSchemaRoundTripsAcrossStoreInstances(@TempDir Path directory) {
        UUID player = UUID.randomUUID();
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("snapshotVersion", 3);
        snapshot.putInt("value", 42);
        new PlayerStateStore(directory).put(player, "realm", snapshot);
        assertEquals(42, new PlayerStateStore(directory).get(player, "realm").getIntOr("value", -1));
    }
}
