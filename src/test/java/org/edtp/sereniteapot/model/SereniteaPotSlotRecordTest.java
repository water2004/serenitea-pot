package org.edtp.sereniteapot.model;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.border.WorldBorder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SereniteaPotSlotRecordTest {
    @Test
    void entryUsesTheSavedBorderCenterWithoutChangingSourceMetadata() {
        var slot = new SereniteaPotSlotRecord("minecraft:overworld", -231, 72, -146, 4);
        var border = new WorldBorder();
        border.setCenter(8, 8);
        assertEquals(new BlockPos(9, 72, 14), slot.entryPosition(border));
        border.setCenter(-232, -152);
        assertEquals(new BlockPos(-231, 72, -146), slot.entryPosition(border));
        assertEquals(-231, slot.entryX());
        assertEquals(-146, slot.entryZ());
    }
}
