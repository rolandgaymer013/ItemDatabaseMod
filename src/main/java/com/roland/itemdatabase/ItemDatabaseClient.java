package com.roland.itemdatabase;

import com.mojang.blaze3d.platform.InputConstants;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.gui.ItemDatabaseScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;

public class ItemDatabaseClient implements ClientModInitializer {
    private static boolean wasKeyPressed = false;

    @Override
    public void onInitializeClient() {
        ItemDatabase.LOGGER.info("[ItemDatabase] Pre-loading BlossomCraft item database...");
        DatabaseManager.preloadDatabase();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getWindow() == null) {
                wasKeyPressed = false;
                return;
            }

            boolean isKeyDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_O);

            if (isKeyDown && !wasKeyPressed) {
                if (client.screen == null) {
                    // Grab any item from cache (or the first available)
                    ItemEntry entry = DatabaseManager.ITEM_CACHE.values().stream()
                            .findFirst()
                            .orElse(null);

                    client.execute(() -> client.setScreen(new ItemDatabaseScreen(entry)));
                }
            }

            wasKeyPressed = isKeyDown;
        });
    }
}