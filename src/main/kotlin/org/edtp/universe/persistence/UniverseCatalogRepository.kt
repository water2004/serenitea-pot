package org.edtp.universe.persistence

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.edtp.universe.UniverseMod
import org.edtp.universe.model.UniverseCatalog
import org.edtp.universe.model.UniverseDimension
import org.edtp.universe.model.UniverseRecord
import org.edtp.universe.model.UniverseSlotRecord
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class UniverseCatalogRepository(private val root: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = root.resolve("universes.json")

    fun load(): UniverseCatalog {
        if (!Files.isRegularFile(file)) {
            return UniverseCatalog()
        }

        return runCatching {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                decode(JsonParser.parseReader(reader).asJsonObject)
            }
        }.getOrElse { error ->
            UniverseMod.logger.error("Failed to read universe catalog at {}", file, error)
            throw IllegalStateException("Universe catalog is unreadable: $file", error)
        }
    }

    fun save(catalog: UniverseCatalog) {
        Files.createDirectories(root)
        val temporary = root.resolve("universes.json.tmp")
        Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
            gson.toJson(encode(catalog), writer)
        }
        try {
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(catalog: UniverseCatalog): JsonObject = JsonObject().apply {
        addProperty("version", FORMAT_VERSION)
        addProperty("defaultMaxRadius", catalog.defaultMaxRadius)
        addProperty("defaultBudgetMillisPerSecond", catalog.defaultBudgetMillisPerSecond)
        addProperty("globalBudgetMillisPerSecond", catalog.globalBudgetMillisPerSecond)
        add("players", JsonObject().apply {
            for ((owner, record) in catalog.players) {
                add(owner.toString(), encodeRecord(record))
            }
        })
    }

    private fun encodeRecord(record: UniverseRecord): JsonObject = JsonObject().apply {
        addProperty("stateId", record.stateId.toString())
        addProperty("activeGeneration", record.activeGeneration)
        addProperty("maxRadius", record.maxRadius)
        addProperty("budgetMillisPerSecond", record.budgetMillisPerSecond)
        addProperty("enabled", record.enabled)
        addProperty("frozen", record.frozen)
        addProperty("stopped", record.stopped)
        addProperty("quarantined", record.quarantined)
        add("slots", JsonObject().apply {
            for ((dimension, slot) in record.slots) {
                add(dimension.id, JsonObject().apply {
                    addProperty("sourceDimension", slot.sourceDimension)
                    addProperty("centerX", slot.centerX)
                    addProperty("centerY", slot.centerY)
                    addProperty("centerZ", slot.centerZ)
                    addProperty("radius", slot.radius)
                })
            }
        })
    }

    private fun decode(root: JsonObject): UniverseCatalog {
        val version = root.int("version", 0)
        require(version == FORMAT_VERSION) {
            "Unsupported universe catalog version $version (expected $FORMAT_VERSION)"
        }

        val catalog = UniverseCatalog(
            defaultMaxRadius = root.int("defaultMaxRadius", UniverseRecord.DEFAULT_MAX_RADIUS),
            defaultBudgetMillisPerSecond = root.double(
                "defaultBudgetMillisPerSecond",
                UniverseRecord.DEFAULT_BUDGET_MILLIS_PER_SECOND,
            ),
            globalBudgetMillisPerSecond = root.double("globalBudgetMillisPerSecond", 100.0),
        )
        val players = root.getAsJsonObject("players") ?: JsonObject()
        for ((ownerText, element) in players.entrySet()) {
            val owner = UUID.fromString(ownerText)
            val recordObject = element.asJsonObject
            val record = UniverseRecord(
                owner = owner,
                stateId = recordObject.string("stateId", UUID.randomUUID().toString()).let(UUID::fromString),
                activeGeneration = recordObject.long("activeGeneration", 0),
                maxRadius = recordObject.int("maxRadius", catalog.defaultMaxRadius),
                budgetMillisPerSecond = recordObject.double(
                    "budgetMillisPerSecond",
                    catalog.defaultBudgetMillisPerSecond,
                ),
                enabled = recordObject.boolean("enabled", true),
                frozen = recordObject.boolean("frozen", false),
                stopped = recordObject.boolean("stopped", false),
                quarantined = recordObject.boolean("quarantined", false),
            )
            val slots = recordObject.getAsJsonObject("slots") ?: JsonObject()
            for ((slotId, slotElement) in slots.entrySet()) {
                val dimension = UniverseDimension.fromId(slotId) ?: continue
                val slot = slotElement.asJsonObject
                record.slots[dimension] = UniverseSlotRecord(
                    sourceDimension = slot.string("sourceDimension", dimension.vanillaId),
                    centerX = slot.int("centerX", 0),
                    centerY = slot.int("centerY", 0),
                    centerZ = slot.int("centerZ", 0),
                    radius = slot.int("radius", 0),
                )
            }
            catalog.players[owner] = record
        }
        return catalog
    }

    private fun JsonObject.int(name: String, fallback: Int): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: fallback

    private fun JsonObject.long(name: String, fallback: Long): Long =
        get(name)?.takeUnless { it.isJsonNull }?.asLong ?: fallback

    private fun JsonObject.double(name: String, fallback: Double): Double =
        get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: fallback

    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: fallback

    private fun JsonObject.string(name: String, fallback: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString ?: fallback

    companion object {
        private const val FORMAT_VERSION = 1
    }
}
