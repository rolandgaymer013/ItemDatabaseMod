package com.roland.itemdatabase;

import com.mojang.blaze3d.platform.InputConstants;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemDatabaseCommand;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.UpdateChecker;
import com.roland.itemdatabase.data.WishlistChatListener;
import com.roland.itemdatabase.gui.ItemDatabaseScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint for the BlossomCraft Item Database Fabric mod.
 * Responsible for:
 * <ul>
 *   <li>Preloading all crate items, categories, and tags from the REST API asynchronously.</li>
 *   <li>Registering client commands (/idb, /idb admin) and chat listeners.</li>
 *   <li>Handling global keybindings (Database viewer hotkey, container batch uploader).</li>
 *   <li>Checking for newer mod version releases upon joining a server/world.</li>
 * </ul>
 */
public class ItemDatabaseClient implements ClientModInitializer {

    /** Primary hotkey to open the main /idb database screen (default: 'O'). */
    private static KeyMapping openDatabaseKey;

    /** Admin hotkey to initiate batch container uploads while inside an inventory (default: 'B'). */
    public static KeyMapping batchUploadKey;

    /** Active target crate configuration for batch uploads (default: 1 - Season 1: Legacy). */
    public static int activeCrateId = 1;
    public static String activeCrateName = "Season 1: Legacy";

    @Override
    public void onInitializeClient() {
        ItemDatabase.LOGGER.info("[{}] Initializing client environment and preloading database...", ItemDatabase.MOD_NAME);

        // 1. Asynchronously prefetch crate data, miscellaneous items, and group structures
        DatabaseManager.preloadDatabase();

        // 2. Register client-side commands and chat packet listeners
        ItemDatabaseCommand.register();
        WishlistChatListener.register();

        // 3. Check for GitHub/mod update releases upon world or server join
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            UpdateChecker.checkForUpdates();
        });

        // 4. Register client keybindings
        openDatabaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.itemdatabase.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyMapping.Category.MISC
        ));

        batchUploadKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.itemdatabase.batch_upload",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC
        ));

        // 5. Global tick listener: Open database GUI when hotkey is pressed outside of open screens
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }

            while (openDatabaseKey.consumeClick()) {
                if (client.screen == null) {
                    // Pick the first available cached item as the initial selection
                    ItemEntry initial = DatabaseManager.ITEM_CACHE.values().stream()
                            .findFirst()
                            .orElse(null);

                    client.setScreen(new ItemDatabaseScreen(initial));
                }
            }
        });

        // 6. Screen keyboard listener: Trigger container batch upload inside chests/shulkers/barrels
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, event) -> {
                    if (batchUploadKey.matches(event)) {
                        ItemDatabaseCommand.runBatchUpload(Minecraft.getInstance(), activeCrateId, activeCrateName);
                    }
                });
            }
        });
    }
}