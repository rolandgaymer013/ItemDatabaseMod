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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles asynchronous communication with the BlossomCraft item database REST API.
 * Parses, caches, and cross-references crate items, miscellaneous items, categories, and tags.
 */
public class DatabaseManager {
    private static final String API_BASE = "https://items.blossomcraft.org";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Primary item caches: Item ID -> ItemEntry
    public static final Map<String, ItemEntry> CRATE_ITEMS = new ConcurrentHashMap<>();
    public static final Map<String, ItemEntry> MISC_ITEMS = new ConcurrentHashMap<>();

    // Backwards-compatibility alias for CRATE_ITEMS
    public static final Map<String, ItemEntry> ITEM_CACHE = CRATE_ITEMS;

    // Category / Group caches
    public static final Map<Integer, CrateEntry> CRATES = new ConcurrentHashMap<>();
    public static final Map<Integer, MiscGroupEntry> MISC_GROUPS = new ConcurrentHashMap<>();

    /**
     * Initializes the client database by preloading all crates, misc items, and grouping trees in parallel.
     */
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

    /**
     * Fetches all crate items from /api/items.
     */
    public static CompletableFuture<Boolean> fetchCrateItems() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/api/items"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
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

    /**
     * Fetches all miscellaneous server items from /api/miscitems.
     */
    public static CompletableFuture<Boolean> fetchMiscItems() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/api/miscitems"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .header("I-INCLUDED-INFO", "*")
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
        String rawData = getStringSafe(obj, "RawData", "");
        String rarityHuman = getStringSafe(obj, "RarityHuman", "Common");
        String winPercentage = getStringSafe(obj, "WinPercentage", "0");
        String imageType = getStringSafe(obj, "ImageType", "png");
        String crateId = getStringSafe(obj, "CrateID", "");
        String notes = getStringSafe(obj, "Notes", "");

        String iconUrl = API_BASE + "/static/images/Icons/" + id + "." + imageType.toLowerCase();

        // Extract the 7 database tag columns
        List<String> tagsList = new ArrayList<>();
        String[] tagKeys = {
                "TagPrimary", "TagSecondary", "TagTertiary",
                "TagQuaternary", "TagQuinary", "TagSenary", "TagSeptenary"
        };
        for (String key : tagKeys) {
            String val = getStringSafe(obj, key, "").trim();
            if (!val.isEmpty() && !val.equalsIgnoreCase("null") && !val.equalsIgnoreCase("none")) {
                tagsList.add(val);
            }
        }

        ItemEntry entry = new ItemEntry(
                id,
                itemName,
                itemHuman,
                rawData,
                imageType.toLowerCase(),
                winPercentage,
                rarityHuman,
                notes,
                "",        // groupId
                crateId,   // crateId
                iconUrl,
                true,      // isCrateItem
                tagsList
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
        String rawData = getStringSafe(obj, "RawData", "");
        String imageType = getStringSafe(obj, "ImageType", "png").toLowerCase();
        String group = getStringSafe(obj, "GroupID", "");
        String notes = getStringSafe(obj, "Notes", "");

        String iconUrl = API_BASE + "/static/images/Misc_Icons/" + id + "." + imageType;

        List<String> tagsList = new ArrayList<>();
        String[] tagKeys = {
                "TagPrimary", "TagSecondary", "TagTertiary",
                "TagQuaternary", "TagQuinary", "TagSenary", "TagSeptenary"
        };
        for (String key : tagKeys) {
            String val = getStringSafe(obj, key, "").trim();
            if (!val.isEmpty() && !val.equalsIgnoreCase("null") && !val.equalsIgnoreCase("none")) {
                tagsList.add(val);
            }
        }

        if (!group.isBlank() && !group.equalsIgnoreCase("null") && !group.equalsIgnoreCase("none")) {
            tagsList.add(group.trim());
        }

        MISC_ITEMS.put(id, new ItemEntry(
                id,
                itemName,
                itemHuman,
                rawData,
                imageType,
                null,                                              // winPercentage
                (!group.isBlank() ? group.toUpperCase() : "MISC"), // rarityHuman
                notes,                                             // notes
                group,                                             // groupId
                "",                                                // crateId
                iconUrl,
                false,                                             // isCrateItem
                tagsList
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

    /**
     * Fetches metadata for all active crates from /api/crates.
     */
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

    /**
     * Maps cached Crate items into their parent CrateEntry containers.
     */
    public static synchronized void mapItemsToCrates() {
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

    /**
     * Fetches metadata for all miscellaneous groups from /api/miscgroups.
     */
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

    /**
     * Maps cached Misc items into their parent MiscGroupEntry containers.
     */
    public static synchronized void mapItemsToMiscGroups() {
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

    /**
     * Collects all cached items across crates and miscellaneous categories.
     */
    public static List<ItemEntry> getAllItems() {
        List<ItemEntry> all = new ArrayList<>(CRATE_ITEMS.values());
        all.addAll(MISC_ITEMS.values());
        return all;
    }

    /**
     * Finds an item by matching its clean name or ID (case-insensitive).
     */
    public static ItemEntry findItemByName(String query) {
        if (query == null || query.isBlank()) return null;
        String lower = query.trim().toLowerCase();

        List<ItemEntry> all = getAllItems();

        // 1. Direct ID match
        for (ItemEntry item : all) {
            if (item.id().equalsIgnoreCase(lower)) return item;
        }

        // 2. Exact clean name match
        for (ItemEntry item : all) {
            if (item.getCleanName().equalsIgnoreCase(lower)) return item;
        }

        // 3. Name starts with query
        for (ItemEntry item : all) {
            if (item.getCleanName().toLowerCase().startsWith(lower)) return item;
        }

        // 4. Name contains query
        for (ItemEntry item : all) {
            if (item.getCleanName().toLowerCase().contains(lower)) return item;
        }

        return null;
    }
}