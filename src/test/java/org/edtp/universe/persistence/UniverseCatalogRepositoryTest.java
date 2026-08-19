package org.edtp.universe.persistence;

import org.edtp.universe.model.UniverseCatalog;
import org.edtp.universe.model.UniverseDimension;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.model.UniverseSlotRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UniverseCatalogRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void catalogRoundTripsAdministrativeAndSlotMetadata() throws Exception {
        UUID owner = UUID.randomUUID();
        UniverseCatalog catalog = new UniverseCatalog();
        catalog.setGlobalBudgetMillisPerSecond(73.5);
        UniverseRecord record = catalog.getOrCreate(owner);
        record.setActiveGeneration(4);
        record.setMaxRadius(91);
        record.setBudgetMillisPerSecond(12.25);
        record.setEnabled(false);
        record.getSlots().put(UniverseDimension.NETHER,
                new UniverseSlotRecord("minecraft:the_nether", 12, 70, -8, 17));

        UniverseCatalogRepository repository = new UniverseCatalogRepository(directory);
        repository.save(catalog);
        UniverseCatalog loaded = repository.load();
        var loadedRecord = loaded.getPlayers().get(owner);

        assertEquals(73.5, loaded.getGlobalBudgetMillisPerSecond());
        assertEquals(4, loadedRecord.getActiveGeneration());
        assertEquals(91, loadedRecord.getMaxRadius());
        assertEquals(12.25, loadedRecord.getBudgetMillisPerSecond());
        assertFalse(loadedRecord.isEnabled());
        assertEquals(record.getStateId(), loadedRecord.getStateId());
        assertEquals(record.getSlots().get(UniverseDimension.NETHER),
                loadedRecord.getSlots().get(UniverseDimension.NETHER));
    }
}
