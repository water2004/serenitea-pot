package org.edtp.sereniteapot.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SereniteaPotSlotRecordTest {
    @Test
    void sourceEntryIsProjectedIntoLocalCenterChunk() {
        var slot = new SereniteaPotSlotRecord("minecraft:overworld", -231, 72, -146, 4);

        assertEquals(9, slot.localEntryX());
        assertEquals(72, slot.localEntryY());
        assertEquals(14, slot.localEntryZ());
    }
}
