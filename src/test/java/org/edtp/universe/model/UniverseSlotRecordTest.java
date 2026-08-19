package org.edtp.universe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniverseSlotRecordTest {
    @Test
    void sourceEntryIsProjectedIntoLocalCenterChunk() {
        var slot = new UniverseSlotRecord("minecraft:overworld", -231, 72, -146, 4);

        assertEquals(9, slot.localEntryX());
        assertEquals(72, slot.localEntryY());
        assertEquals(14, slot.localEntryZ());
    }
}
