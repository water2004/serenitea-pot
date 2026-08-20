package org.edtp.sereniteapot.persistence;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.model.SereniteaPotCatalog;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

public class SereniteaPotCatalogRepository {
    private static final int FORMAT_VERSION = 2;

    private final Path root;
    private final com.google.gson.Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public SereniteaPotCatalogRepository(Path root) {
        this.root = root;
        this.file = root.resolve("serenitea_pots.json");
    }

    public SereniteaPotCatalog load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new SereniteaPotCatalog();
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
        result.addProperty("defaultBudgetMillisPerSecond", catalog.getDefaultBudgetMillisPerSecond());
        result.addProperty("globalBudgetMillisPerSecond", catalog.getGlobalBudgetMillisPerSecond());

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
        result.addProperty("budgetMillisPerSecond", record.getBudgetMillisPerSecond());
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
                doubleValue(root, "defaultBudgetMillisPerSecond", SereniteaPotRecord.DEFAULT_BUDGET_MILLIS_PER_SECOND),
                doubleValue(root, "globalBudgetMillisPerSecond", 100.0));
        JsonObject players = objectValue(root, "players");
        for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
            UUID owner = UUID.fromString(entry.getKey());
            JsonObject recordObject = entry.getValue().getAsJsonObject();
            SereniteaPotRecord record = new SereniteaPotRecord(
                    owner,
                    UUID.fromString(stringValue(recordObject, "stateId", UUID.randomUUID().toString())),
                    longValue(recordObject, "activeGeneration", 0),
                    intValue(recordObject, "maxRadiusChunks", catalog.getDefaultMaxRadiusChunks()),
                    doubleValue(recordObject, "budgetMillisPerSecond", catalog.getDefaultBudgetMillisPerSecond()),
                    booleanValue(recordObject, "enabled", true),
                    booleanValue(recordObject, "frozen", false));

            JsonObject slots = objectValue(recordObject, "slots");
            for (Map.Entry<String, JsonElement> slotEntry : slots.entrySet()) {
                SereniteaPotDimension dimension = SereniteaPotDimension.fromId(slotEntry.getKey());
                if (dimension == null) {
                    continue;
                }
                JsonObject slot = slotEntry.getValue().getAsJsonObject();
                record.getSlots().put(dimension, new SereniteaPotSlotRecord(
                        stringValue(slot, "sourceDimension", dimension.vanillaId()),
                        intValue(slot, "entryX", 0),
                        intValue(slot, "entryY", 0),
                        intValue(slot, "entryZ", 0),
                        intValue(slot, "radiusChunks", 0)));
            }
            catalog.getPlayers().put(owner, record);
        }
        return catalog;
    }

    private static JsonObject objectValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? new JsonObject() : value.getAsJsonObject();
    }

    private static int intValue(JsonObject object, String name, int fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : value.getAsInt();
    }

    private static long longValue(JsonObject object, String name, long fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : value.getAsLong();
    }

    private static double doubleValue(JsonObject object, String name, double fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : value.getAsDouble();
    }

    private static boolean booleanValue(JsonObject object, String name, boolean fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : value.getAsBoolean();
    }

    private static String stringValue(JsonObject object, String name, String fallback) {
        JsonElement value = nonNull(object, name);
        return value == null ? fallback : value.getAsString();
    }

    private static JsonElement nonNull(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value;
    }
}
