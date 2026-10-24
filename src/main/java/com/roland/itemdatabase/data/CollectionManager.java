package com.roland.itemdatabase.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

public class CollectionManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(
            FabricLoader.getInstance().getConfigDir().toFile(),
            "itemdatabase_collection.json"
    );

    private static final Set<String> OWNED_ITEM_IDS = new HashSet<>();

    static {
        load();
    }

    public static synchronized void load() {
        if (!CONFIG_FILE.exists()) return;
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Type setType = new TypeToken<HashSet<String>>() {}.getType();
            Set<String> loaded = GSON.fromJson(reader, setType);
            if (loaded != null) {
                OWNED_ITEM_IDS.clear();
                OWNED_ITEM_IDS.addAll(loaded);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized void save() {
        try {
            if (!CONFIG_FILE.getParentFile().exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(OWNED_ITEM_IDS, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean isOwned(String itemId) {
        if (itemId == null) return false;
        return OWNED_ITEM_IDS.contains(itemId.trim());
    }

    public static void setOwned(String itemId, boolean owned) {
        if (itemId == null || itemId.isBlank()) return;
        String cleanId = itemId.trim();
        if (owned) {
            OWNED_ITEM_IDS.add(cleanId);
        } else {
            OWNED_ITEM_IDS.remove(cleanId);
        }
        save();
    }

    public static boolean toggleOwned(String itemId) {
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

    public static int getOwnedCount() {
        return OWNED_ITEM_IDS.size();
    }

    public static Set<String> getOwnedIds() {
        return Collections.unmodifiableSet(OWNED_ITEM_IDS);
    }

    /**
     * Import a list of item IDs (e.g. from clipboard or web export).
     * Accepts comma-separated, space-separated, or JSON arrays.
     */
    public static int importIds(String input) {
        if (input == null || input.isBlank()) return 0;
        int imported = 0;
        String cleaned = input.replaceAll("[\\[\\]\"']", " ");
        String[] tokens = cleaned.split("[,\\s\n\r]+");
        for (String token : tokens) {
            String id = token.trim();
            if (!id.isEmpty()) {
                OWNED_ITEM_IDS.add(id);
                imported++;
            }
        }
        if (imported > 0) {
            save();
        }
        return imported;
    }
}