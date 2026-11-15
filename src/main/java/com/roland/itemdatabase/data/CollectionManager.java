package com.roland.itemdatabase.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages player item collection and wishlist states, JSON disk persistence, and ID imports.
 * <p>
 * Maintains scoped storage keys ({@code crate_<id>} / {@code misc_<id>}) alongside normalized
 * lowercase clean-name caches so duplicate "Repeat appearance" crate re-releases resolve in O(1) time.
 */
public class CollectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase/Collection");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final File CONFIG_FILE = new File(
            FabricLoader.getInstance().getConfigDir().toFile(),
            "itemdatabase_collection.json"
    );

    private static final Set<String> OWNED_ITEM_IDS = new HashSet<>();
    private static final Set<String> WISHLIST_ITEM_IDS = new HashSet<>();
    private static final Set<String> OWNED_CLEAN_NAMES = new HashSet<>();
    private static final Set<String> WISHLIST_CLEAN_NAMES = new HashSet<>();

    static {
        load();
    }

    /**
     * Rebuilds the O(1) normalized clean-name lookup sets from currently loaded database items.
     * Should be invoked after loading disk state or completing asynchronous database preloads.
     */
    public static synchronized void rebuildCleanNameCaches() {
        OWNED_CLEAN_NAMES.clear();
        WISHLIST_CLEAN_NAMES.clear();

        if (DatabaseManager.CRATE_ITEMS.isEmpty() && DatabaseManager.MISC_ITEMS.isEmpty()) {
            return;
        }

        for (ItemEntry item : DatabaseManager.CRATE_ITEMS.values()) {
            String key = getStorageKey(item);
            String rawId = item.id().trim();
            String cleanName = item.getCleanName().trim().toLowerCase();
            if (cleanName.isEmpty()) continue;

            if (OWNED_ITEM_IDS.contains(key) || OWNED_ITEM_IDS.contains(rawId)) {
                OWNED_CLEAN_NAMES.add(cleanName);
            }
            if (WISHLIST_ITEM_IDS.contains(key) || WISHLIST_ITEM_IDS.contains(rawId)) {
                WISHLIST_CLEAN_NAMES.add(cleanName);
            }
        }

        for (ItemEntry item : DatabaseManager.MISC_ITEMS.values()) {
            String key = getStorageKey(item);
            String rawId = item.id().trim();
            String cleanName = item.getCleanName().trim().toLowerCase();
            if (cleanName.isEmpty()) continue;

            if (OWNED_ITEM_IDS.contains(key) || OWNED_ITEM_IDS.contains(rawId)) {
                OWNED_CLEAN_NAMES.add(cleanName);
            }
            if (WISHLIST_ITEM_IDS.contains(key) || WISHLIST_ITEM_IDS.contains(rawId)) {
                WISHLIST_CLEAN_NAMES.add(cleanName);
            }
        }
    }

    /**
     * Loads saved collection and wishlist data from {@code itemdatabase_collection.json}.
     * Backwards-compatible with legacy flat JSON arrays of owned IDs.
     */
    public static synchronized void load() {
        if (!CONFIG_FILE.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            JsonElement root = JsonParser.parseReader(reader);
            OWNED_ITEM_IDS.clear();
            WISHLIST_ITEM_IDS.clear();

            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonPrimitive()) {
                        OWNED_ITEM_IDS.add(el.getAsString().trim());
                    }
                }
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();

                if (obj.has("owned") && obj.get("owned").isJsonArray()) {
                    for (JsonElement el : obj.getAsJsonArray("owned")) {
                        OWNED_ITEM_IDS.add(el.getAsString().trim());
                    }
                }

                if (obj.has("wishlist") && obj.get("wishlist").isJsonArray()) {
                    for (JsonElement el : obj.getAsJsonArray("wishlist")) {
                        WISHLIST_ITEM_IDS.add(el.getAsString().trim());
                    }
                }
            }
            rebuildCleanNameCaches();
        } catch (Exception e) {
            LOGGER.error("Failed to load collection database from {}", CONFIG_FILE.getName(), e);
        }
    }

    /**
     * Serializes both owned and wishlisted item sets to disk.
     */
    public static synchronized void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            JsonObject root = new JsonObject();

            JsonArray ownedArray = new JsonArray();
            for (String id : OWNED_ITEM_IDS) {
                ownedArray.add(id);
            }
            root.add("owned", ownedArray);

            JsonArray wishlistArray = new JsonArray();
            for (String id : WISHLIST_ITEM_IDS) {
                wishlistArray.add(id);
            }
            root.add("wishlist", wishlistArray);

            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save collection database to {}", CONFIG_FILE.getName(), e);
        }
    }

    // ==========================================
    // STRING ID COLLECTION METHODS
    // ==========================================

    /** Checks whether a raw or scoped item ID is marked as owned. */
    public static synchronized boolean isOwned(String itemId) {
        if (itemId == null) return false;
        return OWNED_ITEM_IDS.contains(itemId.trim());
    }

    /** Explicitly sets the owned status for a raw or scoped item ID and persists to disk. */
    public static synchronized void setOwned(String itemId, boolean owned) {
        if (itemId == null || itemId.isBlank()) return;
        String cleanId = itemId.trim();
        if (owned) {
            OWNED_ITEM_IDS.add(cleanId);
        } else {
            OWNED_ITEM_IDS.remove(cleanId);
        }
        rebuildCleanNameCaches();
        save();
    }

    /** Toggles the owned status for a raw or scoped item ID and returns the new state. */
    public static synchronized boolean toggleOwned(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String cleanId = itemId.trim();
        boolean newState = !OWNED_ITEM_IDS.contains(cleanId);
        if (newState) {
            OWNED_ITEM_IDS.add(cleanId);
        } else {
            OWNED_ITEM_IDS.remove(cleanId);
        }
        rebuildCleanNameCaches();
        save();
        return newState;
    }

    /** Returns the number of stored owned ID entries. */
    public static synchronized int getOwnedCount() {
        return OWNED_ITEM_IDS.size();
    }

    /** Returns an unmodifiable snapshot of all stored owned IDs. */
    public static synchronized Set<String> getOwnedIds() {
        return Collections.unmodifiableSet(new HashSet<>(OWNED_ITEM_IDS));
    }

    // ==========================================
    // STRING ID WISHLIST METHODS
    // ==========================================

    /** Checks whether a raw or scoped item ID is on the wishlist. */
    public static synchronized boolean isWishlisted(String itemId) {
        if (itemId == null) return false;
        return WISHLIST_ITEM_IDS.contains(itemId.trim());
    }

    /** Explicitly sets the wishlist status for a raw or scoped item ID and persists to disk. */
    public static synchronized void setWishlisted(String itemId, boolean wishlisted) {
        if (itemId == null || itemId.isBlank()) return;
        String cleanId = itemId.trim();
        if (wishlisted) {
            WISHLIST_ITEM_IDS.add(cleanId);
        } else {
            WISHLIST_ITEM_IDS.remove(cleanId);
        }
        rebuildCleanNameCaches();
        save();
    }

    /** Toggles the wishlist status for a raw or scoped item ID and returns the new state. */
    public static synchronized boolean toggleWishlist(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String cleanId = itemId.trim();
        boolean newState = !WISHLIST_ITEM_IDS.contains(cleanId);
        if (newState) {
            WISHLIST_ITEM_IDS.add(cleanId);
        } else {
            WISHLIST_ITEM_IDS.remove(cleanId);
        }
        rebuildCleanNameCaches();
        save();
        return newState;
    }

    /** Returns the number of stored wishlist ID entries. */
    public static synchronized int getWishlistCount() {
        return WISHLIST_ITEM_IDS.size();
    }

    /** Returns an unmodifiable snapshot of all stored wishlist IDs. */
    public static synchronized Set<String> getWishlistIds() {
        return Collections.unmodifiableSet(new HashSet<>(WISHLIST_ITEM_IDS));
    }

    /**
     * Checks in O(1) time whether a clean item name (stripped of formatting/icons) is on the wishlist.
     */
    public static synchronized boolean isNameOnWishlist(String cleanItemName) {
        if (cleanItemName == null || cleanItemName.isBlank()) return false;
        return WISHLIST_CLEAN_NAMES.contains(cleanItemName.trim().toLowerCase());
    }

    // ==========================================
    // IMPORT HELPER
    // ==========================================

    /**
     * Bulk-imports comma- or whitespace-separated item IDs into the owned collection.
     *
     * @param input Raw string containing item IDs.
     * @return Count of newly added unique IDs.
     */
    public static synchronized int importIds(String input) {
        if (input == null || input.isBlank()) return 0;
        int imported = 0;
        String cleaned = input.replaceAll("[\\[\\]\"']", " ");
        String[] tokens = cleaned.split("[,\\s\n\r]+");

        for (String token : tokens) {
            String id = token.trim();
            if (!id.isEmpty()) {
                if (OWNED_ITEM_IDS.add(id)) {
                    imported++;
                }
            }
        }

        if (imported > 0) {
            rebuildCleanNameCaches();
            save();
        }
        return imported;
    }

    // ==========================================
    // ITEM ENTRY COLLECTION & WISHLIST METHODS (O(1) CACHED)
    // ==========================================

    /**
     * Builds a collision-free storage key ({@code crate_<id>} or {@code misc_<id>}) for an {@link ItemEntry}.
     */
    public static String getStorageKey(ItemEntry item) {
        if (item == null) return "";
        return (item.isCrateItem() ? "crate_" : "misc_") + item.id().trim();
    }

    /**
     * Checks in O(1) time whether an {@link ItemEntry} or any "Repeat appearance" duplicate is owned.
     */
    public static synchronized boolean isOwned(ItemEntry item) {
        if (item == null) return false;
        if (OWNED_ITEM_IDS.contains(getStorageKey(item)) || OWNED_ITEM_IDS.contains(item.id().trim())) {
            return true;
        }
        String targetName = item.getCleanName().trim().toLowerCase();
        return !targetName.isEmpty() && OWNED_CLEAN_NAMES.contains(targetName);
    }

    /**
     * Toggles the owned status of an {@link ItemEntry}, clearing all "Repeat appearance"
     * duplicates when unmarking, and persists changes to disk.
     */
    public static synchronized boolean toggleOwned(ItemEntry item) {
        if (item == null) return false;
        boolean currentlyOwned = isOwned(item);
        boolean newState = !currentlyOwned;
        String targetName = item.getCleanName().trim().toLowerCase();

        if (newState) {
            OWNED_ITEM_IDS.remove(item.id().trim());
            OWNED_ITEM_IDS.add(getStorageKey(item));
            if (!targetName.isEmpty()) OWNED_CLEAN_NAMES.add(targetName);
        } else {
            OWNED_ITEM_IDS.remove(getStorageKey(item));
            OWNED_ITEM_IDS.remove(item.id().trim());
            if (!targetName.isEmpty()) {
                OWNED_CLEAN_NAMES.remove(targetName);
                for (ItemEntry other : DatabaseManager.CRATE_ITEMS.values()) {
                    if (other.getCleanName().trim().equalsIgnoreCase(targetName)) {
                        OWNED_ITEM_IDS.remove(getStorageKey(other));
                        OWNED_ITEM_IDS.remove(other.id().trim());
                    }
                }
                for (ItemEntry other : DatabaseManager.MISC_ITEMS.values()) {
                    if (other.getCleanName().trim().equalsIgnoreCase(targetName)) {
                        OWNED_ITEM_IDS.remove(getStorageKey(other));
                        OWNED_ITEM_IDS.remove(other.id().trim());
                    }
                }
            }
        }
        save();
        return newState;
    }

    /**
     * Checks in O(1) time whether an {@link ItemEntry} or any "Repeat appearance" duplicate is wishlisted.
     */
    public static synchronized boolean isWishlisted(ItemEntry item) {
        if (item == null) return false;
        if (WISHLIST_ITEM_IDS.contains(getStorageKey(item)) || WISHLIST_ITEM_IDS.contains(item.id().trim())) {
            return true;
        }
        String targetName = item.getCleanName().trim().toLowerCase();
        return !targetName.isEmpty() && WISHLIST_CLEAN_NAMES.contains(targetName);
    }

    /**
     * Toggles the wishlist status of an {@link ItemEntry}, clearing all "Repeat appearance"
     * duplicates when unmarking, and persists changes to disk.
     */
    public static synchronized boolean toggleWishlist(ItemEntry item) {
        if (item == null) return false;
        boolean currentlyWishlisted = isWishlisted(item);
        boolean newState = !currentlyWishlisted;
        String targetName = item.getCleanName().trim().toLowerCase();

        if (newState) {
            WISHLIST_ITEM_IDS.remove(item.id().trim());
            WISHLIST_ITEM_IDS.add(getStorageKey(item));
            if (!targetName.isEmpty()) WISHLIST_CLEAN_NAMES.add(targetName);
        } else {
            WISHLIST_ITEM_IDS.remove(getStorageKey(item));
            WISHLIST_ITEM_IDS.remove(item.id().trim());
            if (!targetName.isEmpty()) {
                WISHLIST_CLEAN_NAMES.remove(targetName);
                for (ItemEntry other : DatabaseManager.CRATE_ITEMS.values()) {
                    if (other.getCleanName().trim().equalsIgnoreCase(targetName)) {
                        WISHLIST_ITEM_IDS.remove(getStorageKey(other));
                        WISHLIST_ITEM_IDS.remove(other.id().trim());
                    }
                }
                for (ItemEntry other : DatabaseManager.MISC_ITEMS.values()) {
                    if (other.getCleanName().trim().equalsIgnoreCase(targetName)) {
                        WISHLIST_ITEM_IDS.remove(getStorageKey(other));
                        WISHLIST_ITEM_IDS.remove(other.id().trim());
                    }
                }
            }
        }
        save();
        return newState;
    }
}