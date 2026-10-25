package com.roland.itemdatabase.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Manages client configuration and UI persistence for the Item Database viewer.
 * Stores layout offsets and custom scaling across game sessions.
 */
public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("itemdatabase.json")
            .toFile();

    // Persisted UI transform properties
    public int cardOffsetX = 0;
    public int cardOffsetY = 0;
    public float uiScale = 1.0F;

    private static ModConfig INSTANCE;

    /**
     * Gets the active singleton configuration instance.
     * Loads from disk if not previously initialized.
     */
    public static ModConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /**
     * Loads the config file from disk, returning default values if missing or invalid.
     */
    public static ModConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load itemdatabase.json config, falling back to defaults", e);
            }
        }

        // Initialize fresh defaults and save
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    /**
     * Serializes and writes the current configuration state to disk.
     */
    public void save() {
        try {
            File parentDir = CONFIG_FILE.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save itemdatabase.json config", e);
        }
    }
}