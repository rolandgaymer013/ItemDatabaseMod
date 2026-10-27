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
 * Manages player item collection and wishlist states, persistence to disk, and ID imports.
 * Persists owned item IDs and wishlisted item IDs to itemdatabase_collection.json.
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

    static {
        load();
    }

    /**
     * Loads saved collection & wishlist data from disk.
     * Backwards-compatible with previous flat Set<String> format.
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
                // Legacy format: raw array of owned IDs
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
        } catch (Exception e) {
            LOGGER.error("Failed to load collection database from {}", CONFIG_FILE.getName(), e);
        }
    }

    /**
     * Serializes both collection and wishlist states to disk.
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
    // OWNED COLLECTION METHODS
    // ==========================================

    public static synchronized boolean isOwned(String itemId) {
        if (itemId == null) return false;
        return OWNED_ITEM_IDS.contains(itemId.trim());
    }

    public static synchronized void setOwned(String itemId, boolean owned) {
        if (itemId == null || itemId.isBlank()) return;
        String cleanId = itemId.trim();
        if (owned) {
            OWNED_ITEM_IDS.add(cleanId);
        } else {
            OWNED_ITEM_IDS.remove(cleanId);
        }
        save();
    }

    public static synchronized boolean toggleOwned(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String cleanId = itemId.trim();
        boolean newState = !OWNED_ITEM_IDS.contains(cleanId);
        if (newState) {
            OWNED_ITEM_IDS.add(cleanId);
        } else {
            OWNED_ITEM_IDS.remove(cleanId);
        }
        save();
        return newState;
    }

    public static synchronized int getOwnedCount() {
        return OWNED_ITEM_IDS.size();
    }

    public static synchronized Set<String> getOwnedIds() {
        return Collections.unmodifiableSet(new HashSet<>(OWNED_ITEM_IDS));
    }

    // ==========================================
    // WISHLIST METHODS
    // ==========================================

    public static synchronized boolean isWishlisted(String itemId) {
        if (itemId == null) return false;
        return WISHLIST_ITEM_IDS.contains(itemId.trim());
    }

    public static synchronized void setWishlisted(String itemId, boolean wishlisted) {
        if (itemId == null || itemId.isBlank()) return;
        String cleanId = itemId.trim();
        if (wishlisted) {
            WISHLIST_ITEM_IDS.add(cleanId);
        } else {
            WISHLIST_ITEM_IDS.remove(cleanId);
        }
        save();
    }

    public static synchronized boolean toggleWishlist(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String cleanId = itemId.trim();
        boolean newState = !WISHLIST_ITEM_IDS.contains(cleanId);
        if (newState) {
            WISHLIST_ITEM_IDS.add(cleanId);
        } else {
            WISHLIST_ITEM_IDS.remove(cleanId);
        }
        save();
        return newState;
    }

    public static synchronized int getWishlistCount() {
        return WISHLIST_ITEM_IDS.size();
    }

    public static synchronized Set<String> getWishlistIds() {
        return Collections.unmodifiableSet(new HashSet<>(WISHLIST_ITEM_IDS));
    }

    /**
     * Checks whether an item name (clean, stripped of formatting/icons) is on the wishlist.
     */
    public static synchronized boolean isNameOnWishlist(String cleanItemName) {
        if (cleanItemName == null || cleanItemName.isBlank()) return false;
        String target = cleanItemName.trim().toLowerCase();

        for (String id : WISHLIST_ITEM_IDS) {
            ItemEntry item = DatabaseManager.CRATE_ITEMS.get(id);
            if (item == null) {
                item = DatabaseManager.MISC_ITEMS.get(id);
            }
            if (item != null && item.getCleanName().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    // ==========================================
    // IMPORT HELPER
    // ==========================================

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
            save();
        }
        return imported;
    }

    public static String getStorageKey(ItemEntry item) {
        if (item == null) return "";
        return (item.isCrateItem() ? "crate_" : "misc_") + item.id().trim();
    }

    public static synchronized boolean isOwned(ItemEntry item) {
        if (item == null) return false;
        // Check new namespaced key first, fall back to legacy raw ID for backward compatibility
        return OWNED_ITEM_IDS.contains(getStorageKey(item)) || OWNED_ITEM_IDS.contains(item.id().trim());
    }

    public static synchronized boolean toggleOwned(ItemEntry item) {
        if (item == null) return false;
        String key = getStorageKey(item);
        // Clean up legacy un-namespaced ID if present
        OWNED_ITEM_IDS.remove(item.id().trim());

        boolean newState = !OWNED_ITEM_IDS.contains(key);
        if (newState) {
            OWNED_ITEM_IDS.add(key);
        } else {
            OWNED_ITEM_IDS.remove(key);
        }
        save();
        return newState;
    }

    public static synchronized boolean isWishlisted(ItemEntry item) {
        if (item == null) return false;
        return WISHLIST_ITEM_IDS.contains(getStorageKey(item)) || WISHLIST_ITEM_IDS.contains(item.id().trim());
    }

    public static synchronized boolean toggleWishlist(ItemEntry item) {
        if (item == null) return false;
        String key = getStorageKey(item);
        WISHLIST_ITEM_IDS.remove(item.id().trim());

        boolean newState = !WISHLIST_ITEM_IDS.contains(key);
        if (newState) {
            WISHLIST_ITEM_IDS.add(key);
        } else {
            WISHLIST_ITEM_IDS.remove(key);
        }
        save();
        return newState;
    }
}