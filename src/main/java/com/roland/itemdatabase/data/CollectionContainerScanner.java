package com.roland.itemdatabase.data;

import com.roland.itemdatabase.gui.PlayerCollectionReviewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CollectionContainerScanner {

    public static void scanAndOpenReview(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (DatabaseManager.CRATE_ITEMS.isEmpty() && DatabaseManager.MISC_ITEMS.isEmpty()) {
            DatabaseManager.preloadDatabase();
        }

        var menu = screen.getMenu();
        int containerSlotCount = menu.slots.size() - 36;
        if (containerSlotCount <= 0) {
            mc.player.sendSystemMessage(Component.literal("§c[ItemDatabase] No valid container slots found to scan."));
            return;
        }

        List<ScannedCollectionItem> scannedList = new ArrayList<>();

        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem()) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            Component nameComp = stack.get(DataComponents.CUSTOM_NAME);
            if (nameComp == null) {
                nameComp = stack.getHoverName();
            }

            StringBuilder rawLeaves = new StringBuilder();
            nameComp.visit((Style style, String text) -> {
                rawLeaves.append(text);
                return Optional.empty();
            }, Style.EMPTY);

            String rawExtracted = rawLeaves.toString().isEmpty() ? nameComp.getString() : rawLeaves.toString();
            String cleanDisplay = stripSectionSignsOnly(rawExtracted);

            ItemEntry matched = findMatchingItem(cleanDisplay);
            boolean alreadyOwned = matched != null && CollectionManager.isOwned(matched);

            scannedList.add(new ScannedCollectionItem(
                    stack.copy(),
                    cleanDisplay,
                    matched,
                    alreadyOwned,
                    !alreadyOwned && matched != null
            ));
        }

        if (scannedList.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("§e[ItemDatabase] No items found in this container."));
            return;
        }

        mc.setScreen(new PlayerCollectionReviewScreen(screen, scannedList));
    }

    public static String stripSectionSignsOnly(String input) {
        if (input == null) return "";
        String text = input.replaceAll("(?i)[§&]x(?:[§&][0-9a-f]){6}", "");
        text = text.replaceAll("[§&]", "");
        return text.replaceAll("\\s+", " ").trim();
    }

    public static ItemEntry findMatchingItem(String queryName) {
        if (queryName == null || queryName.isBlank()) return null;

        for (ItemEntry item : DatabaseManager.getAllItems()) {
            String dbRaw = stripSectionSignsOnly(item.itemName());
            if (dbRaw.equalsIgnoreCase(queryName)) {
                return item;
            }
            if (item.getCleanName().equalsIgnoreCase(queryName)) {
                return item;
            }
        }

        String normQuery = queryName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (normQuery.isBlank()) return null;

        for (ItemEntry item : DatabaseManager.getAllItems()) {
            String normDb = item.itemName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (normDb.equals(normQuery)) {
                return item;
            }
            String normClean = item.getCleanName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (normClean.equals(normQuery)) {
                return item;
            }
        }

        return null;
    }

    public static class ScannedCollectionItem {
        public final ItemStack stack;
        public final String displayName;
        public final ItemEntry matchedEntry;
        public final boolean alreadyOwned;
        public boolean selected;

        public ScannedCollectionItem(ItemStack stack, String displayName, ItemEntry matchedEntry, boolean alreadyOwned, boolean selected) {
            this.stack = stack;
            this.displayName = displayName;
            this.matchedEntry = matchedEntry;
            this.alreadyOwned = alreadyOwned;
            this.selected = selected;
        }

        public boolean isUnrecognized() {
            return matchedEntry == null;
        }
    }
}