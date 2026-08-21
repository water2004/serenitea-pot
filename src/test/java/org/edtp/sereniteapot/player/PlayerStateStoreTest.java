package org.edtp.sereniteapot.player;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
