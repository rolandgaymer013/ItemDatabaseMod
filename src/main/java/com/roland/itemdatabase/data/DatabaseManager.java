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
 * Central client-side data repository and network controller for BlossomCraft.
 * <p>
 * Manages:
 * <ul>
 *   <li>Asynchronous HTTP calls to the Flask REST backend with timeout and redirect handling.</li>
 *   <li>Thread-safe caching of Crate items, Miscellaneous items, Crates, and Misc Groups.</li>
 *   <li>Relational tree assembly mapping flat database item rows into container hierarchies.</li>
 *   <li>Fast in-memory index queries for UI autocompletion, search filtering, and set detection.</li>
 * </ul>
 */
public class DatabaseManager {

    /**
     * Resolves the configured base API URL without trailing slashes.
     *
     * @return Normalized base API URL string (e.g. "https://blossomcraft.org").
     */
    public static String getApiBase() {
        return com.roland.itemdatabase.config.ModConfig.get().getCleanApiUrl();
    }

    /** Shared HTTP client with automatic redirect following and a 10-second connection timeout. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Primary item cache mapping Crate Item ID -> ItemEntry. */
    public static final Map<String, ItemEntry> CRATE_ITEMS = new ConcurrentHashMap<>();

    /** Secondary item cache mapping Miscellaneous Item ID -> ItemEntry. */
    public static final Map<String, ItemEntry> MISC_ITEMS = new ConcurrentHashMap<>();

    /** Backwards-compatibility alias referencing CRATE_ITEMS. */
    public static final Map<String, ItemEntry> ITEM_CACHE = CRATE_ITEMS;

    /** Container cache mapping Crate ID -> CrateEntry. */
    public static final Map<Integer, CrateEntry> CRATES = new ConcurrentHashMap<>();

    /** Container cache mapping Group ID -> MiscGroupEntry. */
    public static final Map<Integer, MiscGroupEntry> MISC_GROUPS = new ConcurrentHashMap<>();

    /**
     * Initializes the client database by preloading all crates, misc items,
     * and container groups concurrently across background worker threads.
     *
     * @return CompletableFuture resolving to true if items were successfully ingested into memory.
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

    /**
     * Entrypoint invoked during mod initialization to kick off background preloading.
     */
    public static void initialize() {
        preloadDatabase();
    }

    /**
     * Requests and loads all crate items from GET /api/items.
     * Uses 'I-INCLUDED-INFO: *' header to ensure all metadata columns are returned.
     *
     * @return CompletableFuture resolving to true if parsing succeeded.
     */
    public static CompletableFuture<Boolean> fetchCrateItems() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getApiBase() + "/api/items"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .header("I-INCLUDED-INFO", "*")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        ItemDatabase.LOGGER.info("[ItemDatabase] Requesting Crate Items from {}/api/items", getApiBase());

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
     * Requests and loads all miscellaneous server items from GET /api/miscitems.
     *
     * @return CompletableFuture resolving to true if parsing succeeded.
     */
    public static CompletableFuture<Boolean> fetchMiscItems() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getApiBase() + "/api/miscitems"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .header("I-INCLUDED-INFO", "*")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        ItemDatabase.LOGGER.info("[ItemDatabase] Requesting Misc Items from {}/api/miscitems", getApiBase());

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

    /**
     * Deserializes the JSON payload from /api/items into the CRATE_ITEMS cache.
     * Supports raw arrays, standard envelope wrappers ("data"), or keyed dictionaries.
     */
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

    /**
     * Instantiates an ItemEntry record from an individual crate item JSON object.
     */
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
        String rawImageType = getStringSafe(obj, "ImageType", null);
        String imageType = (rawImageType == null || rawImageType.isBlank() || rawImageType.equalsIgnoreCase("none") || rawImageType.equalsIgnoreCase("null"))
                ? null
                : rawImageType.toLowerCase();
        String crateId = getStringSafe(obj, "CrateID", "");
        String notes = getStringSafe(obj, "Notes", "");

        // Determine numerical display order within the crate
        int itemOrder = 0;
        if (obj.has("ItemOrder") && !obj.get("ItemOrder").isJsonNull()) {
            try {
                itemOrder = obj.get("ItemOrder").getAsInt();
            } catch (Exception ignored) {}
        } else if (obj.has("order") && !obj.get("order").isJsonNull()) {
            try {
                itemOrder = obj.get("order").getAsInt();
            } catch (Exception ignored) {}
        }

        String iconUrl = (imageType != null)
                ? getApiBase() + "/static/images/BlossomCraft_Icons/" + id + "." + imageType
                : "";

        // Extract the 7 database classification tag columns
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
                imageType,
                winPercentage,
                rarityHuman,
                notes,
                "",        // groupId (empty for crate items)
                crateId,   // crateId
                iconUrl,
                true,      // isCrateItem
                tagsList,
                itemOrder
        );

        CRATE_ITEMS.put(id, entry);
    }

    /**
     * Deserializes the JSON payload from /api/miscitems into the MISC_ITEMS cache.
     */
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

    /**
     * Instantiates an ItemEntry record from an individual miscellaneous item JSON object.
     */
    private static void processMiscObject(JsonObject obj) {
        String id = getStringSafe(obj, "id", "");
        if (id.isEmpty()) {
            id = getStringSafe(obj, "ID", "");
        }
        if (id.isEmpty()) return;

        String itemName = getStringSafe(obj, "ItemName", "Item " + id);
        String itemHuman = getStringSafe(obj, "ItemHuman", "");
        String rawData = getStringSafe(obj, "RawData", "");
        String imageType = getStringSafe(obj, "ImageType", null);
        String group = getStringSafe(obj, "GroupID", "");
        String notes = getStringSafe(obj, "Notes", "");

        int itemOrder = 0;
        if (obj.has("ItemOrder") && !obj.get("ItemOrder").isJsonNull()) {
            try {
                itemOrder = obj.get("ItemOrder").getAsInt();
            } catch (Exception ignored) {}
        } else if (obj.has("order") && !obj.get("order").isJsonNull()) {
            try {
                itemOrder = obj.get("order").getAsInt();
            } catch (Exception ignored) {}
        }

        String iconUrl = (imageType != null && !imageType.isBlank())
                ? getApiBase() + "/static/images/BlossomCraft_Misc_Icons/" + id + "." + imageType.toLowerCase()
                : "";

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

        // Dynamically resolve subcategory tag from parent group (Furniture, Voucher, Event, Unsorted)
        String subCategory = null;
        if (!group.isBlank() && !group.equalsIgnoreCase("null") && !group.equalsIgnoreCase("none")) {
            try {
                int gid = Integer.parseInt(group.trim());
                MiscGroupEntry parentGroup = MISC_GROUPS.get(gid);
                if (parentGroup != null && parentGroup.groupType() != null && !parentGroup.groupType().isBlank()) {
                    subCategory = parentGroup.groupType().trim();
                }
            } catch (NumberFormatException ignored) {}

            // Fallback: match by URLTag or name if numeric parse failed
            if (subCategory == null) {
                for (MiscGroupEntry g : MISC_GROUPS.values()) {
                    if (g.urlTag().equalsIgnoreCase(group.trim()) || g.name().equalsIgnoreCase(group.trim())) {
                        if (g.groupType() != null && !g.groupType().isBlank()) {
                            subCategory = g.groupType().trim();
                        }
                        break;
                    }
                }
            }
        }

        if (subCategory != null && !subCategory.equalsIgnoreCase("null") && !subCategory.equalsIgnoreCase("none")) {
            tagsList.add(subCategory);
        }

        MISC_ITEMS.put(id, new ItemEntry(
                id,
                itemName,
                itemHuman,
                rawData,
                imageType,
                null,    // winPercentage (not applicable to misc items)
                "",      // rarityHuman (misc items do not possess a rarity tier)
                notes,
                group,   // groupId
                "",      // crateId
                iconUrl,
                false,   // isCrateItem
                tagsList,
                itemOrder
        ));
    }

    /**
     * Safely reads a string value from a JsonObject, falling back to a default if missing or null.
     */
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
     * Requests all crate container metadata from GET /api/crates and rebuilds crate mappings.
     *
     * @return CompletableFuture completing when crate headers are cached and items mapped.
     */
    public static CompletableFuture<Void> fetchCrates() {
        String base = getApiBase().endsWith("/") ? getApiBase().substring(0, getApiBase().length() - 1) : getApiBase();
        String endpoint = base + "/api/crates";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    ItemDatabase.LOGGER.info("[ItemDatabase] fetchCrates to {} returned status: {}", endpoint, response.statusCode());

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
                            ItemDatabase.LOGGER.info("[ItemDatabase] Registered Crate: id={}, name='{}'", id, name);
                        }
                        mapItemsToCrates();
                    } else {
                        ItemDatabase.LOGGER.warn("[ItemDatabase] Failed to fetch crates: HTTP {} - {}", response.statusCode(), response.body());
                    }
                })
                .exceptionally(ex -> {
                    ItemDatabase.LOGGER.error("[ItemDatabase] Failed fetching crates from " + endpoint, ex);
                    return null;
                });
    }

    /**
     * Maps all items currently in CRATE_ITEMS into their parent CrateEntry containers.
     * Resolves parentage using direct integer ID, slug URLTag, or crate display title.
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

            // 2. Disambiguate by URLTag or CrateName
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
     * Requests all miscellaneous group metadata from GET /api/miscgroups.
     *
     * @return CompletableFuture resolving to true if groups were fetched and cached.
     */
    public static CompletableFuture<Boolean> fetchMiscGroups() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getApiBase() + "/api/miscgroups"))
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

    /**
     * Parses the JSON payload from /api/miscgroups into the MISC_GROUPS cache.
     * Extracts persistent GroupOrder indices to preserve custom sort sequences.
     */
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

                // Parse the GroupOrder column from the API
                int groupOrder = 0;
                if (obj.has("GroupOrder") && !obj.get("GroupOrder").isJsonNull()) {
                    try {
                        groupOrder = obj.get("GroupOrder").getAsInt();
                    } catch (Exception ignored) {}
                } else if (obj.has("Order") && !obj.get("Order").isJsonNull()) {
                    try {
                        groupOrder = obj.get("Order").getAsInt();
                    } catch (Exception ignored) {}
                } else if (obj.has("order") && !obj.get("order").isJsonNull()) {
                    try {
                        groupOrder = obj.get("order").getAsInt();
                    } catch (Exception ignored) {}
                }

                MISC_GROUPS.put(id, new MiscGroupEntry(id, name, type, urlTag, release, groupOrder));
            }
            ItemDatabase.LOGGER.info("[ItemDatabase] Loaded {} misc groups", MISC_GROUPS.size());
            return true;
        } catch (Exception e) {
            ItemDatabase.LOGGER.error("[ItemDatabase] Error parsing misc groups", e);
            return false;
        }
    }

    /**
     * Maps all items currently in MISC_ITEMS into their parent MiscGroupEntry containers.
     * Synchronizes group type tags back to child items for sub-filter queries.
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
            MiscGroupEntry matchedGroup = null;

            try {
                int numId = Integer.parseInt(trimmed);
                if (MISC_GROUPS.containsKey(numId)) {
                    matchedGroup = MISC_GROUPS.get(numId);
                }
            } catch (NumberFormatException ignored) {}

            if (matchedGroup == null) {
                for (MiscGroupEntry group : MISC_GROUPS.values()) {
                    if (group.name().equalsIgnoreCase(trimmed) || group.urlTag().equalsIgnoreCase(trimmed)) {
                        matchedGroup = group;
                        break;
                    }
                }
            }

            if (matchedGroup != null) {
                matchedGroup.items().add(item);
                mapped++;

                // Ensure tags list is mutable before modifying
                try {
                    String gType = (matchedGroup.groupType() != null && !matchedGroup.groupType().isBlank())
                            ? matchedGroup.groupType().trim()
                            : "Unsorted";

                    List<String> mutableTags = new ArrayList<>(item.tags() != null ? item.tags() : List.of());
                    mutableTags.removeIf(t -> t.equals(trimmed) || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("none"));

                    if (!mutableTags.contains(gType)) {
                        mutableTags.add(0, gType);
                    }

                    if (item.tags() instanceof ArrayList<String>) {
                        item.tags().clear();
                        item.tags().addAll(mutableTags);
                    }
                } catch (Throwable ignored) {}
            }
        }
        ItemDatabase.LOGGER.info("[ItemDatabase] Mapped {} misc items across {} misc groups", mapped, MISC_GROUPS.size());
    }

    /**
     * Aggregates all cached items across both crates and miscellaneous categories.
     *
     * @return Immutable snapshot list of all cached ItemEntry instances.
     */
    public static List<ItemEntry> getAllItems() {
        List<ItemEntry> all = new ArrayList<>(CRATE_ITEMS.values());
        all.addAll(MISC_ITEMS.values());
        return all;
    }

    /**
     * Searches the local cache for an item by matching its ID, exact name, or partial query.
     * <p>
     * Search priority:
     * <ol>
     *   <li>Direct numerical ID equality.</li>
     *   <li>Exact case-insensitive match against clean item name.</li>
     *   <li>Item name begins with query substring.</li>
     *   <li>Item name contains query substring.</li>
     * </ol>
     *
     * @param query Search query string (numerical ID or text string).
     * @return Matching ItemEntry instance, or null if no match is found.
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

    /**
     * Finds an ItemEntry across all loaded crate items and misc items by integer ID.
     *
     * @param id Database primary key integer ID.
     * @return ItemEntry if found, or null.
     */
    public static ItemEntry findItemById(int id) {
        String strId = String.valueOf(id);
        ItemEntry item = CRATE_ITEMS.get(strId);
        if (item != null) return item;
        return MISC_ITEMS.get(strId);
    }

    /**
     * Finds the parent CrateEntry that contains a given item ID.
     *
     * @param itemId Unique database ID of the child item.
     * @return Associated CrateEntry instance, or null if unassigned or orphaned.
     */
    public static CrateEntry findCrateByItemId(int itemId) {
        ItemEntry item = findItemById(itemId);
        if (item == null || item.crateId() == null || item.crateId().isBlank()) {
            return null;
        }

        // 1. Direct numerical crate ID lookup
        try {
            int cId = Integer.parseInt(item.crateId().trim());
            if (CRATES.containsKey(cId)) {
                return CRATES.get(cId);
            }
        } catch (NumberFormatException ignored) {}

        // 2. Disambiguate by URLTag or Crate display name
        String tagOrName = item.crateId().trim();
        for (CrateEntry crate : CRATES.values()) {
            if (crate.urlTag().equalsIgnoreCase(tagOrName) || crate.name().equalsIgnoreCase(tagOrName)) {
                return crate;
            }
        }

        return null;
    }
}