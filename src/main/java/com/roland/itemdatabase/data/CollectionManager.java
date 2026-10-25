package com.roland.itemdatabase.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages player item collection states, persistence to disk, and ID imports.
 * Persists owned item IDs to itemdatabase_collection.json in the Minecraft config directory.
 */
public class CollectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase/Collection");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final File CONFIG_FILE = new File(
            FabricLoader.getInstance().getConfigDir().toFile(),
            "itemdatabase_collection.json"
    );

    private static final Set<String> OWNED_ITEM_IDS = new HashSet<>();

    static {
        load();
    }

    /**
     * Loads saved collection data from disk.
     */
    public static synchronized void load() {
        if (!CONFIG_FILE.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Type setType = new TypeToken<HashSet<String>>() {}.getType();
            Set<String> loaded = GSON.fromJson(reader, setType);
            if (loaded != null) {
                OWNED_ITEM_IDS.clear();
                OWNED_ITEM_IDS.addAll(loaded);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load collection database from {}", CONFIG_FILE.getName(), e);
        }
    }

    /**
     * Serializes the current collection state to disk.
     */
    public static synchronized void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(OWNED_ITEM_IDS, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save collection database to {}", CONFIG_FILE.getName(), e);
        }
    }

    /**
     * Checks if an item is marked as owned in the active collection.
     *
     * @param itemId Unique item identifier to inspect.
     * @return True if owned, false otherwise.
     */
    public static synchronized boolean isOwned(String itemId) {
        if (itemId == null) return false;
        return OWNED_ITEM_IDS.contains(itemId.trim());
    }

    /**
     * Explicitly sets the owned status of an item.
     *
     * @param itemId Unique item identifier.
     * @param owned  True to mark as owned, false to unmark.
     */
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

    /**
     * Toggles the owned status of an item.
     *
     * @param itemId Unique item identifier.
     * @return New owned status after toggling.
     */
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

    /**
     * @return The total count of unique owned items.
     */
    public static synchronized int getOwnedCount() {
        return OWNED_ITEM_IDS.size();
    }

    /**
     * @return An unmodifiable view of all owned item IDs.
     */
    public static synchronized Set<String> getOwnedIds() {
        return Collections.unmodifiableSet(new HashSet<>(OWNED_ITEM_IDS));
    }

    /**
     * Imports a list of item IDs (accepts comma-separated, space-separated, newlines, or JSON arrays).
     *
     * @param input Raw text containing identifiers.
     * @return Count of IDs successfully imported and registered.
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
            save();
        }
        return imported;
    }
}