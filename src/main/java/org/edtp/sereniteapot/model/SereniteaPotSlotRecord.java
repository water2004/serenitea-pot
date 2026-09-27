package org.edtp.sereniteapot.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Immutable metadata for one full-height, chunk-aligned Serenitea Pot dimension.
 *
 * <p>The entry coordinates identify the extraction point in the public source
 * dimension. The dimension's saved world border owns its center, not this
 * extraction metadata.</p>
 */
public record SereniteaPotSlotRecord(
    String sourceDimension,
    int entryX,
    int entryY,
    int entryZ,
    int radiusChunks
) {
    public SereniteaPotSlotRecord {
        java.util.Objects.requireNonNull(sourceDimension, "sourceDimension");
        SereniteaPotRecord.requireValidRadiusChunks(radiusChunks);
    }

    /** Keeps the extraction point's offset within the dimension's center chunk. */
    public BlockPos entryPosition(WorldBorder border) {
        return new BlockPos(
            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(border.getCenterX()),
                SectionPos.sectionRelative(entryX)),
            entryY,
            SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(border.getCenterZ()),
                SectionPos.sectionRelative(entryZ))
        );
    }
}
