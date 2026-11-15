package com.roland.itemdatabase.data;

import com.roland.itemdatabase.gui.CollectionDashboardScreen;
import com.roland.itemdatabase.gui.ComparisonScreen;
import com.roland.itemdatabase.gui.FittingRoomScreen;
import com.roland.itemdatabase.gui.ItemCatalogPickerScreen;
import com.roland.itemdatabase.gui.ItemDatabaseScreen;
import com.roland.itemdatabase.gui.SetCatalogScreen;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;

/**
 * Registers a client-side {@link ItemTooltipCallback} that appends live Collection,
 * Wishlist, Crate/Group Origin, and Armor Set progress lines to hovered BlossomCraft items
 * inside player inventories, containers, and {@code /ah} GUIs.
 */
public class ItemTooltipHandler {

    /**
     * Hooks into Fabric's {@link ItemTooltipCallback#EVENT} to inject database metadata lines
     * while skipping custom {@code ItemDatabaseMod} screens that already render native badges.
     */
    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            if (stack == null || stack.isEmpty() || lines == null) return;
            if (DatabaseManager.CLEAN_NAME_TO_ITEM.isEmpty()) return;

            // Skip custom ItemDatabaseMod screens that already render their own badges
            Screen currentScreen = Minecraft.getInstance().screen;
            if (currentScreen instanceof ItemDatabaseScreen
                    || currentScreen instanceof SetCatalogScreen
                    || currentScreen instanceof FittingRoomScreen
                    || currentScreen instanceof ItemCatalogPickerScreen
                    || currentScreen instanceof ComparisonScreen
                    || currentScreen instanceof CollectionDashboardScreen) {
                return;
            }

            // Only inspect custom-named items (ignore plain vanilla stacks like Dirt or Cobblestone)
            if (!stack.has(DataComponents.CUSTOM_NAME)) return;

            String rawHover = stack.getHoverName().getString();
            String cleanHover = StringUtil.stripColor(rawHover)
                    .replaceAll("[^a-zA-Z0-9\\s'\\-]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (cleanHover.isEmpty()) return;

            ItemEntry matched = DatabaseManager.findItemByCleanName(cleanHover);
            if (matched == null) return;

            boolean owned = CollectionManager.isOwned(matched);
            boolean wishlisted = CollectionManager.isWishlisted(matched);
            AdminApiClient.SetRow parentSet = DatabaseManager.findSetForItem(matched);

            // Resolve parent Crate or Misc Group label
            String originLabel = "";
            if (matched.isCrateItem()) {
                try {
                    CrateEntry crate = DatabaseManager.findCrateByItemId(Integer.parseInt(matched.id().trim()));
                    if (crate != null) originLabel = crate.name();
                } catch (NumberFormatException ignored) {}
            } else if (matched.groupId() != null && !matched.groupId().isBlank()) {
                try {
                    MiscGroupEntry grp = DatabaseManager.MISC_GROUPS.get(Integer.parseInt(matched.groupId().trim()));
                    if (grp != null) originLabel = grp.name();
                } catch (NumberFormatException ignored) {}
            }

            // Build compact status line: [✔ Owned / ★ Wishlisted] • [Crate Origin]
            String statusBadge;
            if (owned && wishlisted) {
                statusBadge = "§a✔ Owned §6★";
            } else if (owned) {
                statusBadge = "§a✔ Owned";
            } else if (wishlisted) {
                statusBadge = "§6★ Wishlisted";
            } else {
                statusBadge = "§7○ Unowned";
            }
            String originSuffix = originLabel.isEmpty() ? "" : " §8• §b" + originLabel;

            lines.add(Component.literal("§8────────────────────"));
            lines.add(Component.literal(statusBadge + originSuffix));

            // If piece belongs to an Armor Set, display live X/Y completion
            if (parentSet != null && !parentSet.itemIds().isEmpty()) {
                int ownedInSet = 0;
                for (int sid : parentSet.itemIds()) {
                    ItemEntry piece = DatabaseManager.findItemById(sid);
                    if (piece != null && CollectionManager.isOwned(piece)) {
                        ownedInSet++;
                    }
                }
                int totalInSet = parentSet.itemIds().size();
                String countColor = (ownedInSet >= totalInSet) ? "§a" : (ownedInSet > 0 ? "§e" : "§7");
                lines.add(Component.literal("§6✦ " + parentSet.name() + " " + countColor + "(" + ownedInSet + "/" + totalInSet + ")"));
            }
        });
    }
}