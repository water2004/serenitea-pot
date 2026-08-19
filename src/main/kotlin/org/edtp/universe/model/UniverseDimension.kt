package org.edtp.universe.model

import net.casual.arcade.dimensions.level.vanilla.VanillaDimension
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

enum class UniverseDimension(
    val id: String,
    val vanillaId: String,
    val vanilla: VanillaDimension,
) {
    OVERWORLD("overworld", "minecraft:overworld", VanillaDimension.Overworld),
    NETHER("nether", "minecraft:the_nether", VanillaDimension.Nether),
    END("end", "minecraft:the_end", VanillaDimension.End),
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
