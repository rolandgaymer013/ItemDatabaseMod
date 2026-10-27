package com.roland.itemdatabase;

import com.mojang.blaze3d.platform.InputConstants;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemDatabaseCommand;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.WishlistChatListener;
import com.roland.itemdatabase.gui.ItemDatabaseScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import com.roland.itemdatabase.data.UpdateChecker;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Client entrypoint for the BlossomCraft Item Database mod.
 * Registers user keybindings, initiates background database preloading,
 * and handles UI invocation events.
 */
public class ItemDatabaseClient implements ClientModInitializer {
    private static KeyMapping openDatabaseKey;

    @Override
    public void onInitializeClient() {
        ItemDatabase.LOGGER.info("[{}] Initializing client environment and preloading database...", ItemDatabase.MOD_NAME);
        DatabaseManager.preloadDatabase();
        ItemDatabaseCommand.register();
        WishlistChatListener.register();

        // 1. Check for updates on server/world join
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            UpdateChecker.checkForUpdates();
        });

        // 2. Register configurable keybinding (defaults to 'O')
        openDatabaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.itemdatabase.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyMapping.Category.MISC
        ));

        // 3. Listen for key presses at the end of each client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }

            while (openDatabaseKey.consumeClick()) {
                if (client.screen == null) {
                    // Pass the first cached item if available, or null to allow the screen to pick
                    ItemEntry initial = DatabaseManager.ITEM_CACHE.values().stream()
                            .findFirst()
                            .orElse(null);

                    client.setScreen(new ItemDatabaseScreen(initial));
                }
            }
        });
    }
}