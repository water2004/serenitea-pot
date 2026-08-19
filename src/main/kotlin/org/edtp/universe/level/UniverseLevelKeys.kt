package org.edtp.universe.level

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import org.edtp.universe.UniverseMod
import org.edtp.universe.model.UniverseDimension
import java.util.UUID

object UniverseLevelKeys {
    private const val PREFIX = "u"

    fun key(owner: UUID, generation: Long, dimension: UniverseDimension): ResourceKey<Level> =
        ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(
                UniverseMod.MOD_ID,
                "$PREFIX/$owner/g$generation/${dimension.id}",
            ),
        )

    fun identify(key: ResourceKey<Level>): Identity? {
        val identifier = key.identifier()
        if (identifier.namespace != UniverseMod.MOD_ID) {
            return null
        }
        val parts = identifier.path.split('/')
        if (parts.size != 4 || parts[0] != PREFIX || !parts[2].startsWith('g')) {
            return null
        }
        return runCatching {
            Identity(
                owner = UUID.fromString(parts[1]),
                generation = parts[2].substring(1).toLong(),
                dimension = UniverseDimension.fromId(parts[3]) ?: return null,
            )
        }.getOrNull()
    }

    data class Identity(
        val owner: UUID,
        val generation: Long,
        val dimension: UniverseDimension,
    )
}
