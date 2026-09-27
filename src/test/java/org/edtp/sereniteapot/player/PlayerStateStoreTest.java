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
    void putUpdatesCacheImmediatelyAndCoalescesDetachedSnapshots(@TempDir Path directory) throws Exception {
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
        assertEquals(1, io.size()); // both submissions are covered by the latest pending image
        assertSame(write1, write2);

        io.runNext();
        assertTrue(write1.isDone());
        assertTrue(write2.isDone());
        assertTrue(flush.isDone());
        flush.join();
        assertEquals(2, NbtIo.readCompressed(directory.resolve(player + ".dat"), NbtAccounter.unlimitedHeap()).getCompound("realm")
                .orElseThrow().getIntOr("value", -1));
        assertEquals(2, store.get(player, "realm").getIntOr("value", -1));
    }

    @Test
    void slowWriterKeepsOnlyLatestPendingImage(@TempDir Path directory) throws Exception {
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        UUID player = UUID.randomUUID();
        io.runNextAfter(store.preload(player));
        var first = store.put(player, "realm", state(0));
        for (int i = 1; i <= 1000; i++) assertSame(first, store.put(player, "realm", state(i)));
        assertEquals(1, io.size());
        io.runNext();
        first.join();
        assertEquals(1000, NbtIo.readCompressed(directory.resolve(player + ".dat"), NbtAccounter.unlimitedHeap())
                .getCompound("realm").orElseThrow().getIntOr("value", -1));
    }

    @Test
    void disconnectDrainsDirtyDataAndReconnectRetainsTheSameEntry(@TempDir Path directory) {
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        UUID player = UUID.randomUUID();
        io.runNextAfter(store.preload(player));
        store.put(player, "realm", state(4));
        store.release(player);
        assertTrue(store.preload(player).isDone()); // reconnect before the write completes
        io.runNext();
        assertEquals(4, store.get(player, "realm").getIntOr("value", -1));
        assertEquals(0, io.size());
        store.release(player);
        var reloaded = store.preload(player);
        assertFalse(reloaded.isDone()); // clean offline entry was actually evicted
        io.runNext();
        assertEquals(4, store.get(player, "realm").getIntOr("value", -1));
    }

    @Test
    void disconnectDuringPreloadDoesNotEvictAReconnectedSession(@TempDir Path directory) {
        ManualExecutor io = new ManualExecutor();
        PlayerStateStore store = new PlayerStateStore(directory, io);
        UUID player = UUID.randomUUID();
        store.preload(player);
        store.release(player);
        store.preload(player);
        assertEquals(1, io.size());
        io.runNext();
        assertTrue(store.preload(player).isDone());
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
