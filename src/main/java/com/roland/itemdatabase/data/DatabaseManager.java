package com.roland.itemdatabase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roland.itemdatabase.ItemDatabase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;

public class DatabaseManager {
    private static final String API_BASE = "https://items.blossomcraft.org";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Cache: Item ID -> ItemEntry
    public static final Map<String, ItemEntry> CRATE_ITEMS = new ConcurrentHashMap<>();
    public static final Map<String, ItemEntry> MISC_ITEMS = new ConcurrentHashMap<>();

    // ITEM_CACHE remains pointed at CRATE_ITEMS for backwards compatibility
    public static final Map<String, ItemEntry> ITEM_CACHE = CRATE_ITEMS;

    public static final Map<Integer, CrateEntry> CRATES = new LinkedHashMap<>();

    public static final Map<Integer, MiscGroupEntry> MISC_GROUPS = new ConcurrentHashMap<>();

    public static CompletableFuture<Boolean> preloadDatabase() {
        return CompletableFuture.allOf(
                fetchCrateItems(),
                fetchCrates(),
                fetchMiscItems(),
                fetchMiscGroups()
        ).thenApply(v -> {
            mapItemsToCrates();
            mapItemsToMiscGroups();
            return !CRATE_ITEMS.isEmpty() || !MISC_ITEMS.isEmpty();
        });
    }

    public static void initialize() {
        preloadDatabase();
    }

    public static CompletableFuture<Boolean> fetchCrateItems() {
        String requestedFields = "id;ItemName;ItemHuman;RarityHuman;WinPercentage;ImageType;Notes";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/api/items"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .header("I-INCLUDED-INFO", "*")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        ItemDatabase.LOGGER.info("[ItemDatabase] Requesting Crate Items from {}/api/items", API_BASE);

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ItemDatabase.LOGGER.info("[ItemDatabase] Crate response status: {}", response.statusCode());
                    if (response.statusCode() == 200) {
                        return parseCrateResponse(response.body());
                    } else {
                        ItemDatabase.LOGGER.error("[ItemDatabase] Crate request failed with status: {}", response.statusCode());
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    ItemDatabase.LOGGER.error("[ItemDatabase] Network exception fetching Crate items", ex);
                    return false;
                });
    }

    public static CompletableFuture<Boolean> fetchMiscItems() {
        String requestedFields = "id;GroupID;ItemName;Notes;ItemHuman;ImageType";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/api/miscitems"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .header("I-INCLUDED-INFO", requestedFields)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        ItemDatabase.LOGGER.info("[ItemDatabase] Requesting Misc Items from {}/api/miscitems", API_BASE);

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    ItemDatabase.LOGGER.info("[ItemDatabase] Misc response status: {}", response.statusCode());
                    if (response.statusCode() == 200) {
                        return parseMiscResponse(response.body());
                    } else {
                        ItemDatabase.LOGGER.error("[ItemDatabase] Misc request failed with status: {}", response.statusCode());
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    ItemDatabase.LOGGER.error("[ItemDatabase] Network exception fetching Misc items", ex);
                    return false;
                });
    }

    private static boolean parseCrateResponse(String jsonString) {
        try {
            JsonElement root = JsonParser.parseString(jsonString);
            CRATE_ITEMS.clear();

            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonObject()) processCrateObject("", el.getAsJsonObject());
                }
            } else if (root.isJsonObject()) {
                JsonObject rootObj = root.getAsJsonObject();
                if (rootObj.has("data") && rootObj.get("data").isJsonArray()) {
                    for (JsonElement el : rootObj.getAsJsonArray("data")) {
                        if (el.isJsonObject()) processCrateObject("", el.getAsJsonObject());
                    }
                } else if (rootObj.has("items") && rootObj.get("items").isJsonArray()) {
                    for (JsonElement el : rootObj.getAsJsonArray("items")) {
                        if (el.isJsonObject()) processCrateObject("", el.getAsJsonObject());
                    }
                } else {
                    for (Map.Entry<String, JsonElement> entry : rootObj.entrySet()) {
                        if (entry.getValue().isJsonObject()) {
                            processCrateObject(entry.getKey(), entry.getValue().getAsJsonObject());
                        }
                    }
                }
            }

            ItemDatabase.LOGGER.info("[ItemDatabase] Successfully loaded {} Crate items into cache.", CRATE_ITEMS.size());
            return !CRATE_ITEMS.isEmpty();
        } catch (Exception e) {
            ItemDatabase.LOGGER.error("[ItemDatabase] Failed to parse Crate JSON", e);
            return false;
        }
    }

    private static void processCrateObject(String fallbackKey, JsonObject obj) {
        String id = getStringSafe(obj, "id", "");
        if (id.isEmpty()) {
            id = getStringSafe(obj, "ID", fallbackKey);
        }
        if (id.isEmpty()) return;

        String itemName = getStringSafe(obj, "ItemName", "Item " + id);
        String itemHuman = getStringSafe(obj, "ItemHuman", "");
        String rarityHuman = getStringSafe(obj, "RarityHuman", "Common");
        String winPercentage = getStringSafe(obj, "WinPercentage", "0");
        String imageType = getStringSafe(obj, "ImageType", "png");
        String crateId = getStringSafe(obj, "CrateID", "");
        String notes = getStringSafe(obj, "Notes", "");

        String iconUrl = API_BASE + "/static/images/Icons/" + id + "." + imageType.toLowerCase();

        ItemEntry entry = new ItemEntry(
                id,
                itemName,
                itemHuman,
                imageType.toLowerCase(),
                winPercentage,
                rarityHuman,
                notes,      // notes
                "",        // groupId
                crateId,   // crateId
                iconUrl,
                true       // isCrateItem
        );

        CRATE_ITEMS.put(id, entry);
    }

    private static boolean parseMiscResponse(String jsonString) {
        try {
            JsonElement root = JsonParser.parseString(jsonString);
            MISC_ITEMS.clear();

            if (root.isJsonObject()) {
                JsonObject rootObj = root.getAsJsonObject();
                JsonArray array = (rootObj.has("data") && rootObj.get("data").isJsonArray())
                        ? rootObj.getAsJsonArray("data")
                        : new JsonArray();

                for (JsonElement el : array) {
                    if (el.isJsonObject()) {
                        processMiscObject(el.getAsJsonObject());
                    }
                }
            } else if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        processMiscObject(el.getAsJsonObject());
                    }
                }
            }

            ItemDatabase.LOGGER.info("[ItemDatabase] Successfully loaded {} Misc items into cache.", MISC_ITEMS.size());
            return !MISC_ITEMS.isEmpty();
        } catch (Exception e) {
            ItemDatabase.LOGGER.error("[ItemDatabase] Failed to parse Misc JSON", e);
            return false;
        }
    }

    private static void processMiscObject(JsonObject obj) {
        String id = getStringSafe(obj, "id", "");
        if (id.isEmpty()) {
            id = getStringSafe(obj, "ID", "");
        }
        if (id.isEmpty()) return;

        String itemName = getStringSafe(obj, "ItemName", "Item " + id);
        String itemHuman = getStringSafe(obj, "ItemHuman", "");
        String imageType = getStringSafe(obj, "ImageType", "png").toLowerCase();
        String group = getStringSafe(obj, "GroupID", "");
        String notes = getStringSafe(obj, "Notes", "");

        // Confirmed live folder: /static/images/Misc_Icons/
        String iconUrl = API_BASE + "/static/images/Misc_Icons/" + id + "." + imageType;

        MISC_ITEMS.put(id, new ItemEntry(
                id,
                itemName,
                itemHuman,
                imageType,
                null,                                        // winPercentage
                (!group.isBlank() ? group.toUpperCase() : "MISC"), // rarityHuman
                notes,                                       // notes
                group,                                       // groupId
                "",                                          // crateId (empty for misc)
                iconUrl,
                false                                        // isCrateItem
        ));
    }

    private static String getStringSafe(JsonObject obj, String memberName, String fallback) {
        if (obj == null || !obj.has(memberName)) {
            return fallback;
        }
        JsonElement element = obj.get(memberName);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsString();
    }

    public static CompletableFuture<Void> fetchCrates() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/api/crates"))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                        CRATES.clear();
                        for (JsonElement el : array) {
                            JsonObject obj = el.getAsJsonObject();
                            int id = obj.get("id").getAsInt();
                            String name = getStringSafe(obj, "CrateName", "Unknown");
                            String date = getStringSafe(obj, "ReleaseDate", "");
                            String tag = getStringSafe(obj, "URLTag", "");
                            String type = getStringSafe(obj, "CrateType", "Misc");

                            CRATES.put(id, new CrateEntry(id, name, date, tag, type));
                        }
                        mapItemsToCrates();
                    }
                })
                .exceptionally(ex -> {
                    ItemDatabase.LOGGER.error("[ItemDatabase] Failed fetching crates", ex);
                    return null;
                });
    }

    public static void mapItemsToCrates() {
        for (CrateEntry crate : CRATES.values()) {
            crate.items().clear();
        }

        int mappedCount = 0;
        int unmappedCount = 0;

        for (ItemEntry item : CRATE_ITEMS.values()) {
            String rawCrateId = item.crateId();
            if (rawCrateId == null || rawCrateId.isBlank()) {
                unmappedCount++;
                continue;
            }

            String trimmed = rawCrateId.trim();
            boolean matched = false;

            // 1. Direct Integer ID match
            try {
                int numericId = Integer.parseInt(trimmed);
                if (CRATES.containsKey(numericId)) {
                    CRATES.get(numericId).items().add(item);
                    mappedCount++;
                    continue;
                }
            } catch (NumberFormatException ignored) {}

            // 2. Match by URLTag or CrateName
            for (CrateEntry crate : CRATES.values()) {
                if (crate.urlTag().equalsIgnoreCase(trimmed)
                        || crate.name().equalsIgnoreCase(trimmed)
                        || crate.name().toLowerCase().contains(trimmed.toLowerCase())) {
                    crate.items().add(item);
                    matched = true;
                    mappedCount++;
                    break;
                }
            }

            if (!matched) {
                unmappedCount++;
            }
        }

        ItemDatabase.LOGGER.info("[ItemDatabase] Crate mapping finished: {} items mapped, {} unmapped across {} crates.",
                mappedCount, unmappedCount, CRATES.size());
    }

    public static CompletableFuture<Boolean> fetchMiscGroups() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/api/miscgroups"))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return parseMiscGroupsResponse(response.body());
                    }
                    return false;
                })
                .exceptionally(ex -> {
                    ItemDatabase.LOGGER.error("[ItemDatabase] Failed to fetch misc groups", ex);
                    return false;
                });
    }

    private static boolean parseMiscGroupsResponse(String body) {
        try {
            JsonArray array = JsonParser.parseString(body).getAsJsonArray();
            MISC_GROUPS.clear();
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                int id = obj.get("id").getAsInt();
                String name = getStringSafe(obj, "GroupName", "Unnamed Group");

                String rawType = getStringSafe(obj, "GroupType", "Unsorted").trim();
                String type = (rawType.isEmpty() || rawType.equalsIgnoreCase("null") || rawType.equalsIgnoreCase("none"))
                        ? "Unsorted"
                        : rawType;

                String urlTag = getStringSafe(obj, "URLTag", "");
                String release = getStringSafe(obj, "ReleaseDate", "");

                MISC_GROUPS.put(id, new MiscGroupEntry(id, name, type, urlTag, release));
            }
            ItemDatabase.LOGGER.info("[ItemDatabase] Loaded {} misc groups", MISC_GROUPS.size());
            return true;
        } catch (Exception e) {
            ItemDatabase.LOGGER.error("[ItemDatabase] Error parsing misc groups", e);
            return false;
        }
    }

    public static void mapItemsToMiscGroups() {
        for (MiscGroupEntry group : MISC_GROUPS.values()) {
            group.items().clear();
        }

        int mapped = 0;
        for (ItemEntry item : MISC_ITEMS.values()) {
            String rawGroupId = item.groupId();
            if (rawGroupId == null || rawGroupId.isBlank()) continue;

            String trimmed = rawGroupId.trim();
            boolean matched = false;

            try {
                int numId = Integer.parseInt(trimmed);
                if (MISC_GROUPS.containsKey(numId)) {
                    MISC_GROUPS.get(numId).items().add(item);
                    matched = true;
                    mapped++;
                }
            } catch (NumberFormatException ignored) {}

            if (!matched) {
                for (MiscGroupEntry group : MISC_GROUPS.values()) {
                    if (group.name().equalsIgnoreCase(trimmed) || group.urlTag().equalsIgnoreCase(trimmed)) {
                        group.items().add(item);
                        mapped++;
                        break;
                    }
                }
            }
        }
        ItemDatabase.LOGGER.info("[ItemDatabase] Mapped {} misc items across {} misc groups", mapped, MISC_GROUPS.size());
    }
}