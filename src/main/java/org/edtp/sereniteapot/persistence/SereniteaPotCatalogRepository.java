package org.edtp.sereniteapot.persistence;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Difficulty;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.model.SereniteaPotCatalog;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

public class SereniteaPotCatalogRepository {
    // v4 is the supported baseline; future format changes must explicitly migrate it.
    private static final int FORMAT_VERSION = 4;

    private final Path root;
    private final com.google.gson.Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public SereniteaPotCatalogRepository(Path root) {
        this.root = root;
        this.file = root.resolve("serenitea_pots.json");
    }

    public SereniteaPotCatalog load() throws IOException {
        if (Files.notExists(file)) {
            return new SereniteaPotCatalog();
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Serenitea Pot catalog is not a file: " + file);
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return decode(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException error) {
            SereniteaPotMod.LOGGER.error("Failed to read Serenitea Pot catalog at {}", file, error);
            throw error;
        } catch (RuntimeException error) {
            SereniteaPotMod.LOGGER.error("Failed to read Serenitea Pot catalog at {}", file, error);
            throw new IllegalStateException("Serenitea Pot catalog is unreadable: " + file, error);
        }
    }

    public void save(SereniteaPotCatalog catalog) throws IOException {
        Files.createDirectories(root);
        Path temporary = root.resolve("serenitea_pots.json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            gson.toJson(encode(catalog), writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private JsonObject encode(SereniteaPotCatalog catalog) {
        JsonObject result = new JsonObject();
        result.addProperty("version", FORMAT_VERSION);
        result.addProperty("defaultMaxRadiusChunks", catalog.getDefaultMaxRadiusChunks());
        result.addProperty("defaultBudgetMillisPerTick", catalog.getDefaultBudgetMillisPerTick());
        result.addProperty("globalBudgetMillisPerTick", catalog.getGlobalBudgetMillisPerTick());

        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, SereniteaPotRecord> entry : catalog.getPlayers().entrySet()) {
            players.add(entry.getKey().toString(), encodeRecord(entry.getValue()));
        }
        result.add("players", players);
        return result;
    }

    private JsonObject encodeRecord(SereniteaPotRecord record) {
        JsonObject result = new JsonObject();
        result.addProperty("stateId", record.getStateId().toString());
        result.addProperty("activeGeneration", record.getActiveGeneration());
        result.addProperty("maxRadiusChunks", record.getMaxRadiusChunks());
        result.addProperty("budgetMillisPerTick", record.getBudgetMillisPerTick());
        result.addProperty("difficulty", record.getDifficulty().getSerializedName());
        result.addProperty("enabled", record.isEnabled());
        result.addProperty("frozen", record.isFrozen());

        JsonObject slots = new JsonObject();
        for (Map.Entry<SereniteaPotDimension, SereniteaPotSlotRecord> entry : record.getSlots().entrySet()) {
            SereniteaPotDimension dimension = entry.getKey();
            SereniteaPotSlotRecord slot = entry.getValue();
            JsonObject slotObject = new JsonObject();
            slotObject.addProperty("sourceDimension", slot.sourceDimension());
            slotObject.addProperty("entryX", slot.entryX());
            slotObject.addProperty("entryY", slot.entryY());
            slotObject.addProperty("entryZ", slot.entryZ());
            slotObject.addProperty("radiusChunks", slot.radiusChunks());
            slots.add(dimension.id(), slotObject);
        }
        result.add("slots", slots);
        return result;
    }

    private SereniteaPotCatalog decode(JsonObject root) {
        int version = intValue(root, "version", 0);
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Serenitea Pot catalog version " + version + " (expected " + FORMAT_VERSION + ")");
        }

        SereniteaPotCatalog catalog = new SereniteaPotCatalog(
                intValue(root, "defaultMaxRadiusChunks", SereniteaPotRecord.DEFAULT_MAX_RADIUS_CHUNKS),
                doubleValue(root, "defaultBudgetMillisPerTick", SereniteaPotRecord.DEFAULT_BUDGET_MILLIS_PER_TICK),
                doubleValue(root, "globalBudgetMillisPerTick", SereniteaPotCatalog.DEFAULT_GLOBAL_BUDGET_MILLIS_PER_TICK));
        JsonObject players = requiredObject(root, "players");
        for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
            JsonObject recordObject = requiredObject(players, entry.getKey());
            long activeGeneration = longValue(recordObject, "activeGeneration");
            if (activeGeneration < 0) {
                throw new IllegalArgumentException("Negative activeGeneration for " + entry.getKey());
            }
            SereniteaPotRecord record = new SereniteaPotRecord(
                    UUID.fromString(requiredString(recordObject, "stateId")),
                    activeGeneration,
                    intValue(recordObject, "maxRadiusChunks", catalog.getDefaultMaxRadiusChunks()),
                    doubleValue(recordObject, "budgetMillisPerTick", catalog.getDefaultBudgetMillisPerTick()),
                    difficultyValue(recordObject, "difficulty"),
                    booleanValue(recordObject, "enabled"),
                    booleanValue(recordObject, "frozen"));

            JsonObject slots = requiredObject(recordObject, "slots");
            for (Map.Entry<String, JsonElement> slotEntry : slots.entrySet()) {
                SereniteaPotDimension dimension = SereniteaPotDimension.fromId(slotEntry.getKey());
                if (dimension == null) {
                    throw new IllegalArgumentException("Unknown Serenitea Pot slot " + slotEntry.getKey());
                }
                JsonObject slot = requiredObject(slots, slotEntry.getKey());
                String sourceDimension = requiredString(slot, "sourceDimension");
                if (Identifier.tryParse(sourceDimension) == null) {
                    throw new IllegalArgumentException("Invalid sourceDimension " + sourceDimension);
                }
                record.getSlots().put(dimension, new SereniteaPotSlotRecord(
                        sourceDimension,
                        requiredInt(slot, "entryX"),
                        requiredInt(slot, "entryY"),
                        requiredInt(slot, "entryZ"),
                        requiredInt(slot, "radiusChunks")));
            }
            if (record.exists() && record.getSlots().isEmpty()) {
                throw new IllegalArgumentException("Active Serenitea Pot has no slots for " + entry.getKey());
            }
            catalog.getPlayers().put(UUID.fromString(entry.getKey()), record);
        }
        return catalog;
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = nonNull(object, name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Missing or invalid object " + name);
        }
        return value.getAsJsonObject();
    }

    private static int intValue(JsonObject object, String name, int fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : exactInt(value, name);
    }

    private static int requiredInt(JsonObject object, String name) {
        return exactInt(required(object, name), name);
    }

    private static long longValue(JsonObject object, String name) {
        JsonElement value = required(object, name);
        try {
            return number(value, name).longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Invalid integral value for " + name, error);
        }
    }

    private static int exactInt(JsonElement value, String name) {
        try {
            return number(value, name).intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Invalid integral value for " + name, error);
        }
    }

    private static BigDecimal number(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid number for " + name);
        }
        return new BigDecimal(value.getAsString());
    }

    private static double doubleValue(JsonObject object, String name, double fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : number(value, name).doubleValue();
    }

    private static boolean booleanValue(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid boolean for " + name);
        }
        return value.getAsBoolean();
    }

    private static Difficulty difficultyValue(JsonObject object, String name) {
        Difficulty difficulty = Difficulty.byName(requiredString(object, name));
        if (difficulty == null) {
            throw new IllegalArgumentException("Unknown difficulty in Serenitea Pot catalog");
        }
        return difficulty;
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid string for " + name);
        }
        return value.getAsString();
    }

    private static JsonElement required(JsonObject object, String name) {
        JsonElement value = nonNull(object, name);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static JsonElement nonNull(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value;
    }
}
