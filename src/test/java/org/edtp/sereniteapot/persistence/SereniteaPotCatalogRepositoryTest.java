package org.edtp.sereniteapot.persistence;

import net.minecraft.world.Difficulty;
import org.edtp.sereniteapot.model.SereniteaPotCatalog;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SereniteaPotCatalogRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void v4CatalogRoundTripsAdministrativeAndSlotMetadata() throws Exception {
        UUID owner = UUID.randomUUID();
        SereniteaPotCatalog catalog = new SereniteaPotCatalog();
        catalog.setDefaultMaxRadiusChunks(7);
        catalog.setGlobalBudgetMillisPerTick(37.5);
        SereniteaPotRecord record = catalog.getOrCreate(owner);
        record.setActiveGeneration(4);
        record.setMaxRadiusChunks(9);
        record.setBudgetMillisPerTick(12.25);
        record.setDifficulty(Difficulty.HARD);
        record.setEnabled(false);
        record.getSlots().put(SereniteaPotDimension.NETHER,
                new SereniteaPotSlotRecord("minecraft:the_nether", 12, 70, -8, 17));

        SereniteaPotCatalogRepository repository = new SereniteaPotCatalogRepository(directory);
        repository.saveAsync(catalog).join();
        SereniteaPotCatalog loaded = repository.load();
        var loadedRecord = loaded.getPlayers().get(owner);
        String json = Files.readString(directory.resolve("serenitea_pots.json"));

        assertEquals(7, loaded.getDefaultMaxRadiusChunks());
        assertEquals(37.5, loaded.getGlobalBudgetMillisPerTick());
        assertEquals(4, loadedRecord.getActiveGeneration());
        assertEquals(9, loadedRecord.getMaxRadiusChunks());
        assertEquals(12.25, loadedRecord.getBudgetMillisPerTick());
        assertEquals(Difficulty.HARD, loadedRecord.getDifficulty());
        assertFalse(loadedRecord.isEnabled());
        assertEquals(record.getStateId(), loadedRecord.getStateId());
        assertEquals(record.getSlots().get(SereniteaPotDimension.NETHER),
                loadedRecord.getSlots().get(SereniteaPotDimension.NETHER));
        assertTrue(json.contains("\"version\": 4"));
        assertTrue(json.contains("\"globalBudgetMillisPerTick\": 37.5"));
        assertTrue(json.contains("\"budgetMillisPerTick\": 12.25"));
        assertTrue(json.contains("\"difficulty\": \"hard\""));
        assertFalse(json.contains("MillisPerSecond"));
        assertTrue(json.contains("\"defaultMaxRadiusChunks\": 7"));
        assertTrue(json.contains("\"maxRadiusChunks\": 9"));
        assertTrue(json.contains("\"entryX\": 12"));
        assertTrue(json.contains("\"radiusChunks\": 17"));
        assertFalse(json.contains("\"defaultMaxRadius\":"));
        assertFalse(json.contains("\"maxRadius\":"));
    }

    @Test
    void rejectsUnsupportedCatalogVersion() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("serenitea_pots.json"), "{\"version\":1}");

        SereniteaPotCatalogRepository repository = new SereniteaPotCatalogRepository(directory);

        assertThrows(IllegalStateException.class, repository::load);
    }

    @Test
    void rejectsRadiusAboveTheModelLimit() {
        SereniteaPotCatalog catalog = new SereniteaPotCatalog();

        assertThrows(IllegalArgumentException.class,
                () -> catalog.setDefaultMaxRadiusChunks(SereniteaPotRecord.MAX_RADIUS_CHUNKS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.getOrCreate(UUID.randomUUID())
                        .setMaxRadiusChunks(SereniteaPotRecord.MAX_RADIUS_CHUNKS + 1));
    }

    @Test
    void rejectsCorruptMetadataWithoutChangingCatalog() throws Exception {
        String valid = """
                {"version":4,"players":{"00000000-0000-0000-0000-000000000001":{
                "stateId":"00000000-0000-0000-0000-000000000002","activeGeneration":1,
                "difficulty":"normal","enabled":true,"frozen":false,
                "slots":{"overworld":{"sourceDimension":"minecraft:overworld",
                "entryX":1,"entryY":64,"entryZ":2,"radiusChunks":1}}}}}
                """;
        String[] corrupt = {
                valid.replace("\"players\":", "\"missingPlayers\":"),
                valid.replace("\"stateId\":\"00000000-0000-0000-0000-000000000002\",", ""),
                valid.replace("\"activeGeneration\":1,", ""),
                valid.replace("\"activeGeneration\":1", "\"activeGeneration\":1.5"),
                valid.replace("\"activeGeneration\":1", "\"activeGeneration\":-1"),
                valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
                valid.replace("\"enabled\":true,", ""),
                valid.replace("\"frozen\":false,", ""),
                valid.replace("\"slots\":{\"overworld\":", "\"missingSlots\":{\"overworld\":"),
                valid.replace("\"sourceDimension\":\"minecraft:overworld\",", ""),
                valid.replace("\"entryX\":1,", ""),
                valid.replace("\"entryY\":64", "\"entryY\":2147483648"),
                valid.replace("\"radiusChunks\":1", "\"radiusChunks\":257"),
                valid.replace("\"overworld\":", "\"unknown\":"),
                valid.substring(0, valid.indexOf("\"slots\":")) + "\"slots\":{}}}}"
        };
        Path file = directory.resolve("serenitea_pots.json");
        SereniteaPotCatalogRepository repository = new SereniteaPotCatalogRepository(directory);
        for (String text : corrupt) {
            Files.writeString(file, text);
            byte[] original = Files.readAllBytes(file);
            assertThrows(IllegalStateException.class, repository::load);
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test
    void acceptsUncreatedAdministrativeRecord() throws Exception {
        SereniteaPotCatalog catalog = new SereniteaPotCatalog();
        UUID owner = UUID.randomUUID();
        catalog.getOrCreate(owner);
        SereniteaPotCatalogRepository repository = new SereniteaPotCatalogRepository(directory);
        repository.saveAsync(catalog).join();
        assertEquals(0, repository.load().getPlayers().get(owner).getActiveGeneration());
        assertTrue(repository.load().getPlayers().get(owner).getSlots().isEmpty());
    }

    @Test
    void asyncSavesUseCallerSnapshotsAndCommitInOrder() throws Exception {
        SereniteaPotCatalogRepository repository = new SereniteaPotCatalogRepository(directory);
        SereniteaPotCatalog catalog = new SereniteaPotCatalog();
        var first = repository.saveAsync(catalog);
        catalog.setGlobalBudgetMillisPerTick(31.0);
        var second = repository.saveAsync(catalog);
        catalog.setGlobalBudgetMillisPerTick(42.0);
        var third = repository.saveAsync(catalog);
        catalog.setGlobalBudgetMillisPerTick(99.0);

        repository.pendingWrites().join();
        first.join();
        second.join();
        third.join();
        assertEquals(42.0, repository.load().getGlobalBudgetMillisPerTick());
    }

    @Test
    void asyncWriteFailureIsObservableAndLaterWritesDoNotOvertakeIt() throws Exception {
        Path blockedRoot = directory.resolve("blocked-root");
        Files.writeString(blockedRoot, "existing file");
        SereniteaPotCatalogRepository repository = new SereniteaPotCatalogRepository(blockedRoot);
        SereniteaPotCatalog catalog = new SereniteaPotCatalog();
        var first = repository.saveAsync(catalog);
        catalog.setGlobalBudgetMillisPerTick(7.0);
        var second = repository.saveAsync(catalog);

        assertThrows(CompletionException.class, first::join);
        assertThrows(CompletionException.class, second::join);
        assertThrows(CompletionException.class, () -> repository.pendingWrites().join());
        // Once the failure is known, no more snapshots or impossible write jobs are queued.
        assertSame(repository.pendingWrites(), repository.saveAsync(catalog));
        assertEquals("existing file", Files.readString(blockedRoot));
    }
}
