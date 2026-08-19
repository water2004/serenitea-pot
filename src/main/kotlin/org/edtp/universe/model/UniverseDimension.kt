package org.edtp.universe.model

import net.casual.arcade.dimensions.level.vanilla.VanillaDimension
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

enum class UniverseDimension(
    val id: String,
    val vanilla: VanillaDimension,
) {
    OVERWORLD("overworld", VanillaDimension.Overworld),
    NETHER("nether", VanillaDimension.Nether),
    END("end", VanillaDimension.End),
    ;

    val vanillaLevelKey: ResourceKey<Level>
        get() = vanilla.getDimensionKey()

    companion object {
        fun fromVanillaLevel(key: ResourceKey<Level>): UniverseDimension? =
            entries.firstOrNull { it.vanillaLevelKey == key }

        fun fromId(id: String): UniverseDimension? =
            entries.firstOrNull { it.id == id }
    }
}
