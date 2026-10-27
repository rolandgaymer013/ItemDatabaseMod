package com.roland.itemdatabase.data;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.roland.itemdatabase.gui.ItemDatabaseScreen;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ItemDatabaseCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(ItemDatabaseCommand::init);
    }

    private static void init(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(
                literal("idb")
                        // Bare "/idb" opens the database to the default/first item
                        .executes(c -> openDatabase(null))
                        .then(argument("item", StringArgumentType.greedyString())
                                .suggests(ItemDatabaseCommand::getItemSuggestions)
                                .executes(c -> {
                                    String query = StringArgumentType.getString(c, "item");
                                    return openDatabase(query);
                                })
                        )
        );
    }

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
                // If the item name has spaces, suggest with quotes for clean Brigadier parsing
                if (cleanName.contains(" ")) {
                    builder.suggest("\"" + cleanName + "\"");
                } else {
                    builder.suggest(cleanName);
                }

                // Limit suggestions list so it stays responsive
                count++;
                if (count >= 50) break;
            }
        }

        return builder.buildFuture();
    }

    private static int openDatabase(String itemQuery) {
        Minecraft client = Minecraft.getInstance();

        client.execute(() -> {
            ItemEntry targetItem = null;

            if (itemQuery != null && !itemQuery.trim().isEmpty()) {
                String cleanQuery = itemQuery.replace("\"", "").trim();

                // 1. Try standard lookup first
                targetItem = DatabaseManager.findItemByName(cleanQuery);

                // 2. Fallback: Normalized lookup (ignoring symbols, flowers, formatting)
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

            // Open the database screen focused on the target item
            client.setScreen(new ItemDatabaseScreen(targetItem));
        });

        return 1;
    }
}
