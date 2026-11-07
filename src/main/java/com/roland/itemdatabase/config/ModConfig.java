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
 * Manages client-side configuration persistence for the BlossomCraft Item Database.
 * Handles:
 * - Persisting GUI positions (drag offsets) and custom card scale settings.
 * - Storing and sanitizing the Flask REST API endpoint URL.
 * - Managing admin authentication tokens for authenticated API actions.
 */
public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Path to the client configuration file: .minecraft/config/itemdatabase.json */
    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("itemdatabase.json")
            .toFile();

    // ==========================================
    // Persisted UI Transform State
    // ==========================================

    /** Horizontal pixel offset of the floating item viewer card relative to screen center. */
    public int cardOffsetX = 0;

    /** Vertical pixel offset of the floating item viewer card relative to screen center. */
    public int cardOffsetY = 0;

    /** Zoom/scale multiplier applied to the database viewer card (clamped between 0.6x and 1.5x). */
    public float uiScale = 1.0F;

    // ==========================================
    // Admin Bridge REST API Settings
    // ==========================================

    /** Base URL of the Flask REST API backend server (defaults to local development host). */
    public String apiUrl = "http://127.0.0.1:5000";

    /** Secret admin bearer token used to authenticate privileged POST/PATCH/DELETE endpoints. */
    public String apiToken = "";

    /** Active singleton instance in memory. */
    private static ModConfig INSTANCE;

    /**
     * Retrieves the active singleton configuration instance.
     * Loads the config file from disk if it hasn't been initialized yet.
     */
    public static synchronized ModConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /**
     * Reads and parses the config file from disk.
     * Falls back to default values if the file is missing, empty, or corrupted.
     */
    public static ModConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config != null) {
                    // Sanitize nulls if loaded from an older or partial config file version
                    if (config.apiUrl == null || config.apiUrl.isBlank()) {
                        config.apiUrl = "http://127.0.0.1:5000";
                    }
                    if (config.apiToken == null) {
                        config.apiToken = "";
                    }
                    return config;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load itemdatabase.json config, falling back to defaults", e);
            }
        }

        // Initialize fresh defaults and write to disk
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    /**
     * Checks if a valid admin API authentication token is configured.
     *
     * @return true if the token is non-null and not blank.
     */
    public boolean hasAdminToken() {
        return apiToken != null && !apiToken.trim().isEmpty();
    }

    /**
     * Sanitizes and returns the base API URL, guaranteeing no trailing slash.
     * Prevents endpoint concatenation errors (e.g. "http://localhost:5000//api/items").
     *
     * @return clean base URL string without trailing slashes.
     */
    public String getCleanApiUrl() {
        if (apiUrl == null || apiUrl.isBlank()) {
            return "http://127.0.0.1:5000";
        }
        String trimmed = apiUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Serializes the current configuration state to pretty-printed JSON and saves it to disk.
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