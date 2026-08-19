package org.edtp.universe.persistence

import org.edtp.universe.model.UniverseCatalog
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.model.UniverseSlotRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class UniverseCatalogRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `catalog round trips administrative and slot metadata`() {
        val owner = UUID.randomUUID()
        val catalog = UniverseCatalog(globalBudgetMillisPerSecond = 73.5)
        val record = catalog.getOrCreate(owner)
        record.activeGeneration = 4
        record.maxRadius = 91
        record.budgetMillisPerSecond = 12.25
        record.enabled = false
        record.slots[UniverseDimension.NETHER] = UniverseSlotRecord(
            sourceDimension = "minecraft:the_nether",
            centerX = 12,
            centerY = 70,
            centerZ = -8,
            radius = 17,
        )

        val repository = UniverseCatalogRepository(directory)
        repository.save(catalog)
        val loaded = repository.load()
        val loadedRecord = requireNotNull(loaded.players[owner])

        assertEquals(73.5, loaded.globalBudgetMillisPerSecond)
        assertEquals(4, loadedRecord.activeGeneration)
        assertEquals(91, loadedRecord.maxRadius)
        assertEquals(12.25, loadedRecord.budgetMillisPerSecond)
        assertFalse(loadedRecord.enabled)
        assertEquals(record.stateId, loadedRecord.stateId)
        assertEquals(record.slots[UniverseDimension.NETHER], loadedRecord.slots[UniverseDimension.NETHER])
    }
}
