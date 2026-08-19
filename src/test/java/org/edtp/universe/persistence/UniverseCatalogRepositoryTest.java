package org.edtp.universe.persistence;

import org.edtp.universe.model.UniverseCatalog;
import org.edtp.universe.model.UniverseDimension;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.model.UniverseSlotRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniverseCatalogRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void v2CatalogRoundTripsAdministrativeAndSlotMetadata() throws Exception {
        UUID owner = UUID.randomUUID();
        UniverseCatalog catalog = new UniverseCatalog();
        catalog.setDefaultMaxRadiusChunks(7);
        catalog.setGlobalBudgetMillisPerSecond(73.5);
        UniverseRecord record = catalog.getOrCreate(owner);
        record.setActiveGeneration(4);
        record.setMaxRadiusChunks(9);
        record.setBudgetMillisPerSecond(12.25);
        record.setEnabled(false);
        record.getSlots().put(UniverseDimension.NETHER,
                new UniverseSlotRecord("minecraft:the_nether", 12, 70, -8, 17));

        UniverseCatalogRepository repository = new UniverseCatalogRepository(directory);
        repository.save(catalog);
        UniverseCatalog loaded = repository.load();
        var loadedRecord = loaded.getPlayers().get(owner);
        String json = Files.readString(directory.resolve("universes.json"));

        assertEquals(7, loaded.getDefaultMaxRadiusChunks());
        assertEquals(73.5, loaded.getGlobalBudgetMillisPerSecond());
        assertEquals(4, loadedRecord.getActiveGeneration());
        assertEquals(9, loadedRecord.getMaxRadiusChunks());
        assertEquals(12.25, loadedRecord.getBudgetMillisPerSecond());
        assertFalse(loadedRecord.isEnabled());
        assertEquals(record.getStateId(), loadedRecord.getStateId());
        assertEquals(record.getSlots().get(UniverseDimension.NETHER),
                loadedRecord.getSlots().get(UniverseDimension.NETHER));
        assertTrue(json.contains("\"version\": 2"));
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
        Files.writeString(directory.resolve("universes.json"), "{\"version\":1}");

        UniverseCatalogRepository repository = new UniverseCatalogRepository(directory);

        assertThrows(IllegalStateException.class, repository::load);
    }

    @Test
    void rejectsRadiusAboveTheModelLimit() {
        UniverseCatalog catalog = new UniverseCatalog();

        assertThrows(IllegalArgumentException.class,
                () -> catalog.setDefaultMaxRadiusChunks(UniverseRecord.MAX_RADIUS_CHUNKS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.getOrCreate(UUID.randomUUID())
                        .setMaxRadiusChunks(UniverseRecord.MAX_RADIUS_CHUNKS + 1));
    }
}
