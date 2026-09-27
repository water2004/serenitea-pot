package org.edtp.sereniteapot.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStateStoreTest {
    @Test
    void preloadAndGetDoNotPerformIoOnCaller(@TempDir Path directory) {
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        UUID player = UUID.randomUUID();

        CompletableFuture<Void> preload = store.preload(player);
        assertFalse(preload.isDone());
        assertEquals(1, io.size());
        assertThrows(PlayerStateStore.LoadingPlayerStateException.class, () -> store.get(player, "realm"));
        assertFalse(Files.exists(directory.resolve(player + ".dat")));

        io.runNext();
        assertDoesNotThrow(preload::join);
        assertNull(store.get(player, "realm"));
    }

    @Test
    void putUpdatesCacheImmediatelyAndWritesDetachedSnapshotsInOrder(@TempDir Path directory) throws Exception {
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        UUID player = UUID.randomUUID();
        io.runNextAfter(store.preload(player));

        CompoundTag first = state(1);
        CompletableFuture<Void> write1 = store.put(player, "realm", first);
        first.putInt("value", 99);
        CompoundTag cached = store.get(player, "realm");
        assertEquals(1, cached.getIntOr("value", -1));
        cached.putInt("value", 77);
        assertEquals(1, store.get(player, "realm").getIntOr("value", -1));
        assertFalse(write1.isDone());
        assertFalse(Files.exists(directory.resolve(player + ".dat")));

        CompoundTag second = state(2);
        CompletableFuture<Void> write2 = store.put(player, "realm", second);
        second.putInt("value", 88);
        CompletableFuture<Void> flush = store.flush();
        assertFalse(flush.isDone());
        assertEquals(1, io.size()); // write two is chained behind write one

        io.runNext();
        assertTrue(write1.isDone());
        assertFalse(write2.isDone());
        assertFalse(flush.isDone());
        io.runNext();
        flush.join();
        assertEquals(2, NbtIo.readCompressed(directory.resolve(player + ".dat"), NbtAccounter.unlimitedHeap()).getCompound("realm")
                .orElseThrow().getIntOr("value", -1));
        assertEquals(2, store.get(player, "realm").getIntOr("value", -1));
    }

    @Test
    void malformedStateFailsPreloadAndPreservesFile(@TempDir Path directory) throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".dat");
        for (Integer version : new Integer[]{null, 2}) {
            CompoundTag root = new CompoundTag();
            if (version != null) root.putInt("schemaVersion", version);
            NbtIo.writeCompressed(root, file);
            byte[] original = Files.readAllBytes(file);
            ManualExecutor io = new ManualExecutor();
            PlayerStateStore store = new PlayerStateStore(directory, io);
            CompletableFuture<Void> preload = store.preload(player);
            io.runNext();
            assertThrows(java.util.concurrent.CompletionException.class, preload::join);
            assertThrows(PlayerStateStore.InvalidPlayerStateException.class, () -> store.get(player, "realm"));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test
    void writeFailureIsPropagatedAndFlushWaitsForPendingWrite(@TempDir Path directory) {
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        UUID player = UUID.randomUUID();
        io.runNextAfter(store.preload(player));
        io.rejectNext = true;

        CompletableFuture<Void> write = store.put(player, "realm", state(7));
        assertTrue(write.isCompletedExceptionally());
        assertThrows(java.util.concurrent.CompletionException.class, write::join);
        assertThrows(PlayerStateStore.InvalidPlayerStateException.class, () -> store.get(player, "realm"));
        assertThrows(java.util.concurrent.CompletionException.class, () -> store.flush().join());
    }

    @Test
    void invalidSchemaAndNonCompoundValuesAreRejectedWithoutOverwriting(@TempDir Path directory) throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".dat");
        CompoundTag wrongType = new CompoundTag();
        wrongType.putDouble("schemaVersion", 1.0);
        assertInvalidFileIsPreserved(directory, player, file, wrongType);
        CompoundTag nonCompound = new CompoundTag();
        nonCompound.putInt("schemaVersion", 1);
        nonCompound.putInt("realm", 3);
        assertInvalidFileIsPreserved(directory, player, file, nonCompound);
    }

    private static void assertInvalidFileIsPreserved(Path directory, UUID player, Path file, CompoundTag contents)
            throws Exception {
        NbtIo.writeCompressed(contents, file);
        byte[] original = Files.readAllBytes(file);
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        CompletableFuture<Void> preload = store.preload(player);
        io.runNext();
        if (contents.get("schemaVersion") instanceof net.minecraft.nbt.IntTag) {
            assertDoesNotThrow(preload::join);
        } else {
            assertThrows(java.util.concurrent.CompletionException.class, preload::join);
        }
        assertThrows(PlayerStateStore.InvalidPlayerStateException.class, () -> store.get(player, "realm"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    private static CompoundTag state(int value) {
        CompoundTag state = new CompoundTag();
        state.putInt("snapshotVersion", 3);
        state.putInt("value", value);
        return state;
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean rejectNext;

        @Override
        public void execute(Runnable command) {
            if (rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException("test rejection");
            }
            tasks.add(command);
        }

        int size() { return tasks.size(); }

        void runNext() { tasks.remove().run(); }

        void runNextAfter(CompletableFuture<?> future) {
            assertFalse(future.isDone());
            runNext();
            future.join();
        }
    }
}
