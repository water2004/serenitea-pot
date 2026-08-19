package org.edtp.universe.level

import net.casual.arcade.dimensions.level.CustomLevel
import org.edtp.universe.model.UniverseDimension
import java.util.EnumMap
import java.util.UUID

data class UniverseBundle(
    val owner: UUID,
    val generation: Long,
    val levels: EnumMap<UniverseDimension, CustomLevel>,
) {
    operator fun get(dimension: UniverseDimension): CustomLevel =
        requireNotNull(levels[dimension]) { "Missing $dimension in universe $owner generation $generation" }
}
