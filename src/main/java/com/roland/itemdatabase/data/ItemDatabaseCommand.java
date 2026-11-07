package com.roland.itemdatabase.data;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.roland.itemdatabase.ItemDatabaseClient;
import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.gui.AdminUploadScreen;
import com.roland.itemdatabase.gui.ItemDatabaseScreen;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Registers and handles client-side Brigadier commands under the {@code /idb} namespace.
 * Provides user search tools, GUI invocation, token authentication, single-item uploading,
 * and batch-upload routines for open containers.
 */
public class ItemDatabaseCommand {

    /**
     * Registers client-side command definitions with Fabric's command event pipeline.
     */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(ItemDatabaseCommand::init);
    }

    /**
     * Constructs the Brigadier command dispatch tree.
     */
    private static void init(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(
                literal("idb")
                        // Bare "/idb" opens the database
                        .executes(c -> openDatabase(null))

                        // Admin sub-command tree
                        .then(literal("admin")
                                // /idb admin verify
                                .then(literal("verify")
                                        .executes(ItemDatabaseCommand::handleAdminVerify)
                                )
                                // /idb admin gui
                                .then(literal("gui")
                                        .executes(ctx -> {
                                            Minecraft mc = Minecraft.getInstance();
                                            if (mc.player == null) return 0;

                                            ItemStack held = mc.player.getMainHandItem();
                                            AdminPayloadBuilder.DraftItem draft = null;

                                            if (!held.isEmpty()) {
                                                draft = AdminPayloadBuilder.buildDraftFromHeldItem(held, 1);
                                            }

                                            final AdminPayloadBuilder.DraftItem finalDraft = draft;
                                            mc.execute(() -> mc.setScreen(new AdminUploadScreen(finalDraft, null)));
                                            return 1;
                                        })
                                )
                                // /idb admin token <token>
                                .then(literal("token")
                                        .then(argument("token", StringArgumentType.word())
                                                .executes(ItemDatabaseCommand::handleSetToken)
                                        )
                                )
                                // /idb admin upload [crateId]
                                .then(literal("upload")
                                        .executes(c -> handleAdminUpload(c, 1))
                                        .then(argument("crateId", IntegerArgumentType.integer(1))
                                                .executes(c -> handleAdminUpload(c, IntegerArgumentType.getInteger(c, "crateId")))
                                        )
                                )
                                // /idb admin batch <crateName>
                                .then(literal("batch")
                                        .then(argument("crateName", StringArgumentType.greedyString())
                                                .suggests(ItemDatabaseCommand::getCrateSuggestions)
                                                .executes(ItemDatabaseCommand::handleAdminBatchUpload)
                                        )
                                )
                        )

                        // Item search autocomplete: /idb <item name>
                        .then(argument("item", StringArgumentType.greedyString())
                                .suggests(ItemDatabaseCommand::getItemSuggestions)
                                .executes(c -> {
                                    String query = StringArgumentType.getString(c, "item");
                                    return openDatabase(query);
                                })
                        )
        );
    }

    /**
     * Updates the admin API bearer token in the mod's configuration file.
     */
    private static int handleSetToken(CommandContext<FabricClientCommandSource> context) {
        String token = StringArgumentType.getString(context, "token");
        ModConfig config = ModConfig.get();
        config.apiToken = token.trim();
        config.save();

        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§a[ItemDatabase] Admin API token updated and saved.")
            );
        }
        return 1;
    }

    /**
     * Validates the active API token against the server's verification endpoint.
     */
    private static int handleAdminVerify(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        client.player.sendSystemMessage(Component.literal("§7[ItemDatabase] Contacting backend to verify token..."));

        AdminApiClient.verifyToken().thenAccept(result -> {
            client.execute(() -> {
                if (client.player == null) return;
                if (result.success()) {
                    client.player.sendSystemMessage(
                            Component.literal("§a[ItemDatabase] Authenticated as §e" + result.username() +
                                    " §a(Permission Level: §e" + result.permissions() + "§a)")
                    );
                } else {
                    client.player.sendSystemMessage(
                            Component.literal("§c[ItemDatabase] Verification failed: §f" + result.error())
                    );
                }
            });
        });

        return 1;
    }

    /**
     * Creates a draft from the player's held item and launches the admin upload GUI.
     */
    private static int handleAdminUpload(CommandContext<FabricClientCommandSource> context, int crateId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        ItemStack heldItem = client.player.getMainHandItem();
        if (heldItem.isEmpty()) {
            client.player.sendSystemMessage(Component.literal("§c[ItemDatabase] You must hold an item in your main hand to upload!"));
            return 0;
        }

        AdminPayloadBuilder.DraftItem draft = AdminPayloadBuilder.buildDraftFromHeldItem(heldItem, crateId);
        if (draft == null) {
            client.player.sendSystemMessage(Component.literal("§c[ItemDatabase] Could not serialize the held item."));
            return 0;
        }

        client.execute(() -> client.setScreen(new AdminUploadScreen(draft, null)));
        return 1;
    }

    /**
     * Generates tab-completion suggestions for item names from cached records.
     */
    private static CompletableFuture<Suggestions> getItemSuggestions(
            CommandContext<FabricClientCommandSource> context,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase().trim();
        List<ItemEntry> items = DatabaseManager.getAllItems();

        if (items.isEmpty()) {
            return builder.buildFuture();
        }

        int count = 0;
        for (ItemEntry item : items) {
            String cleanName = item.getCleanName();
            if (cleanName.isBlank()) continue;

            if (remaining.isEmpty() || cleanName.toLowerCase().contains(remaining)) {
                if (cleanName.contains(" ")) {
                    builder.suggest("\"" + cleanName + "\"");
                } else {
                    builder.suggest(cleanName);
                }

                count++;
                if (count >= 50) break;
            }
        }

        return builder.buildFuture();
    }

    /**
     * Opens the item database screen, optionally focusing on a specific matching query.
     */
    private static int openDatabase(String itemQuery) {
        Minecraft client = Minecraft.getInstance();

        client.execute(() -> {
            ItemEntry targetItem = null;

            if (itemQuery != null && !itemQuery.trim().isEmpty()) {
                String cleanQuery = itemQuery.replace("\"", "").trim();

                // Standard lookup
                targetItem = DatabaseManager.findItemByName(cleanQuery);

                // Fallback: Normalized character lookup
                if (targetItem == null) {
                    String normalizedQuery = cleanQuery.replaceAll("(?i)[&§][0-9a-fk-or]|[^a-zA-Z0-9]", "").toLowerCase().trim();
                    for (ItemEntry item : DatabaseManager.getAllItems()) {
                        String candidate = item.getCleanName() != null ? item.getCleanName() : item.itemName();
                        String normalizedCandidate = candidate.replaceAll("(?i)[&§][0-9a-fk-or]|[^a-zA-Z0-9]", "").toLowerCase().trim();
                        if (normalizedCandidate.equals(normalizedQuery) || normalizedCandidate.contains(normalizedQuery)) {
                            targetItem = item;
                            break;
                        }
                    }
                }

                if (targetItem == null) {
                    if (client.player != null) {
                        client.player.sendSystemMessage(
                                Component.literal("§c[ItemDatabase] No item found matching: §e" + itemQuery)
                        );
                    }
                    return;
                }
            }

            client.setScreen(new ItemDatabaseScreen(targetItem));
        });

        return 1;
    }

    /**
     * Represents an item found inside an open GUI slot with its on-screen pixel coordinates.
     */
    public record ScannedItem(ItemStack stack, int screenX, int screenY) {}

    /**
     * Inspects the currently active container menu and extracts all non-empty items
     * along with their absolute viewport coordinates.
     */
    public static List<ScannedItem> extractItemsFromTarget(Minecraft client) {
        List<ScannedItem> items = new ArrayList<>();
        if (client.player == null) return items;

        if (client.player.containerMenu != null) {
            AbstractContainerMenu menu = client.player.containerMenu;
            int totalSlots = menu.slots.size();
            int containerSlotCount = Math.max(0, totalSlots - 36);

            int leftPos = 0;
            int topPos = 0;
            if (client.screen instanceof AbstractContainerScreen<?> containerScreen) {
                try {
                    Field leftField = AbstractContainerScreen.class.getDeclaredField("leftPos");
                    Field topField = AbstractContainerScreen.class.getDeclaredField("topPos");
                    leftField.setAccessible(true);
                    topField.setAccessible(true);
                    leftPos = leftField.getInt(containerScreen);
                    topPos = topField.getInt(containerScreen);
                } catch (Exception ignored) {
                    leftPos = (containerScreen.width - 176) / 2;
                    topPos = (containerScreen.height - 166) / 2;
                }
            }

            for (int i = 0; i < containerSlotCount; i++) {
                Slot slot = menu.getSlot(i);
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    int sx = leftPos + slot.x;
                    int sy = topPos + slot.y;
                    items.add(new ScannedItem(stack.copy(), sx, sy));
                }
            }

            client.player.sendSystemMessage(Component.literal(
                    "§e[Debug] Found non-empty items: " + items.size()
            ));
        }

        return items;
    }

    /**
     * Scans an open container, serializes unique item payloads with captured slot icons,
     * and uploads them to the target crate.
     */
    public static void runBatchUpload(Minecraft client, int crateId, String crateName) {
        if (client.player == null) return;

        ItemDatabaseClient.activeCrateId = crateId;
        ItemDatabaseClient.activeCrateName = crateName;

        List<ScannedItem> rawItems = extractItemsFromTarget(client);
        if (rawItems.isEmpty()) {
            client.player.sendSystemMessage(Component.literal(
                    "§c[ItemDatabase] No items found in the open container!"
            ));
            return;
        }

        List<JsonObject> payloads = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();

        for (ScannedItem scanned : rawItems) {
            AdminPayloadBuilder.DraftItem draft = AdminPayloadBuilder.buildDraftFromHeldItem(scanned.stack(), crateId);
            if (draft != null && draft.itemName != null) {
                if (seenSignatures.add(draft.itemName.trim())) {
                    JsonObject payload = AdminPayloadBuilder.serializeToApiJson(draft);

                    // Capture the slot's rendered image from the screen
                    String iconB64 = SlotIconCapturer.captureSlotIconBase64(scanned.screenX(), scanned.screenY(), 64);
                    if (iconB64 != null && !iconB64.isBlank()) {
                        payload.addProperty("image_base64", iconB64);
                        payload.addProperty("ImageType", "png");
                    }

                    payloads.add(payload);
                }
            }
        }

        client.player.sendSystemMessage(Component.literal(
                "§7[ItemDatabase] Found §e" + rawItems.size() + "§7 items (§b" + payloads.size() +
                        " unique§7). Uploading to §6" + crateName + "§7..."
        ));

        AdminApiClient.uploadBatch(payloads).thenAccept(results -> {
            client.execute(() -> {
                if (client.player == null) return;
                int successCount = 0;
                int failCount = 0;

                for (AdminApiClient.MutationResult res : results) {
                    if (res.success()) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }

                client.player.sendSystemMessage(Component.literal(
                        "§a[ItemDatabase] Batch complete! Uploaded: §e" + successCount +
                                (failCount > 0 ? " §c(Failed: " + failCount + ")" : "")
                ));
            });
        });
    }

    /**
     * Handles /idb admin batch <crateName>, preparing target crate state or triggering an upload.
     */
    private static int handleAdminBatchUpload(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return 0;

        String query = StringArgumentType.getString(context, "crateName").replace("\"", "").trim();
        CrateEntry targetCrate = findCrateByName(query);

        if (targetCrate == null) {
            client.player.sendSystemMessage(Component.literal(
                    "§c[ItemDatabase] Crate not found matching: §e" + query
            ));
            return 0;
        }

        // Update active crate target
        ItemDatabaseClient.activeCrateId = targetCrate.id();
        ItemDatabaseClient.activeCrateName = targetCrate.name();

        List<ScannedItem> rawItems = extractItemsFromTarget(client);

        // If no container is open, record crate target for subsequent container hotkey usage
        if (rawItems.isEmpty()) {
            client.player.sendSystemMessage(Component.literal(
                    "§a[ItemDatabase] Target crate set to: §6" + targetCrate.name() +
                            "§7. Open a chest and press §eB§7 to upload."
            ));
            return 1;
        }

        // Run batch upload directly if container is already open
        runBatchUpload(client, targetCrate.id(), targetCrate.name());
        return 1;
    }

    /**
     * Finds a cached crate entry by flexible name comparison, ignoring color codes and punctuation.
     */
    public static CrateEntry findCrateByName(String name) {
        String query = name.replaceAll("(?i)[&§][0-9a-fk-or]|[^a-zA-Z0-9]", "").toLowerCase().trim();
        for (CrateEntry crate : DatabaseManager.CRATES.values()) {
            String clean = crate.name().replaceAll("(?i)[&§][0-9a-fk-or]|[^a-zA-Z0-9]", "").toLowerCase().trim();
            if (clean.equalsIgnoreCase(query) || clean.contains(query)) {
                return crate;
            }
        }
        return null;
    }

    /**
     * Generates tab completion suggestions for registered crate names.
     */
    private static CompletableFuture<Suggestions> getCrateSuggestions(
            CommandContext<FabricClientCommandSource> context,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase().trim();
        for (CrateEntry crate : DatabaseManager.CRATES.values()) {
            String name = crate.name();
            if (name == null || name.isBlank()) continue;

            if (remaining.isEmpty() || name.toLowerCase().contains(remaining)) {
                if (name.contains(" ")) {
                    builder.suggest("\"" + name + "\"");
                } else {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }
}