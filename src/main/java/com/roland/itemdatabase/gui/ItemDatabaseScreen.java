package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.AnimatedTexture;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.TextureDownloader;
import com.roland.itemdatabase.data.CollectionManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ItemDatabaseScreen extends Screen {
    public enum ViewCategory {
        CRATES,
        MISC,
        TAGS,
        COLLECTION
    }

    private ViewCategory currentCategory = ViewCategory.CRATES;
    private final List<ItemEntry> items = new ArrayList<>();
    private int currentIndex = 0;

    // Persisted drag offsets and scaling from ModConfig
    private static int cardOffsetX = ModConfig.get().cardOffsetX;
    private static int cardOffsetY = ModConfig.get().cardOffsetY;
    private static float uiScale = ModConfig.get().uiScale;

    // Dragging interaction state
    private boolean isDragging = false;
    private boolean wasMouseDown = false;
    private double dragGrabX = 0;
    private double dragGrabY = 0;

    // Scrolling state
    private int scrollOffset = 0;
    private int maxScroll = 0;

    // Base card dimensions
    private final int panelWidth = 330;
    private final int panelHeight = 220;

    private EditBox searchBox;

    private String lastLoggedId = "";

    public enum CrateTab {
        SEASONAL("Seasonal"),
        SIDE("Side"),
        MISC("Misc");

        private final String label;
        CrateTab(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum MiscTab {
        UNSORTED("Unsorted"),
        VOUCHER("Voucher"),
        EVENT("Event"),
        FURNITURE("Furniture");

        private final String label;
        MiscTab(String label) { this.label = label; }
        public String label() { return label; }
    }

    private MiscTab selectedMiscTab = MiscTab.UNSORTED;
    private int expandedMiscGroupId = -1;

    public enum CollectionFilter {
        ALL("All"),
        OWNED("Owned"),
        MISSING("Missing");

        private final String label;
        CollectionFilter(String label) { this.label = label; }
        public String label() { return label; }
    }

    private CollectionFilter collectionFilter = CollectionFilter.ALL;

    // Drawer state
    private boolean isDrawerOpen = false;
    private CrateTab selectedTab = CrateTab.SEASONAL;
    private int expandedCrateId = -1;
    private int expandedCollectionCrateId = -1;
    private int drawerScrollOffset = 0;
    private int drawerMaxScroll = 0;
    private final int drawerWidth = 200;

    // Tag tab state
    private com.roland.itemdatabase.data.TagCategory selectedTagCategory = com.roland.itemdatabase.data.TagCategory.TOOLS;
    private String expandedTagName = null;

    // Click target tracking for drawer rows
    private record ClickableRow(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableRow> drawerClickables = new ArrayList<>();

    public ItemDatabaseScreen(ItemEntry initialItem) {
        super(Component.literal("Item Database Viewer"));

        if (initialItem != null && !initialItem.isCrateItem()) {
            this.currentCategory = ViewCategory.MISC;
        }

        updateItemList();

        if (initialItem != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(initialItem.id())) {
                    this.currentIndex = i;
                    break;
                }
            }
        }
    }

    private void updateItemList() {
        this.items.clear();
        if (currentCategory == ViewCategory.CRATES) {
            this.items.addAll(DatabaseManager.CRATE_ITEMS.values());
        } else if (currentCategory == ViewCategory.MISC) {
            this.items.addAll(DatabaseManager.MISC_ITEMS.values());
        } else if (currentCategory == ViewCategory.TAGS) {
            this.items.addAll(DatabaseManager.CRATE_ITEMS.values());
            this.items.addAll(DatabaseManager.MISC_ITEMS.values());
        } else if (currentCategory == ViewCategory.COLLECTION) {
            List<ItemEntry> combined = new ArrayList<>();
            combined.addAll(DatabaseManager.CRATE_ITEMS.values());
            combined.addAll(DatabaseManager.MISC_ITEMS.values());

            for (ItemEntry it : combined) {
                boolean owned = CollectionManager.isOwned(it.id());
                if (collectionFilter == CollectionFilter.ALL) {
                    this.items.add(it);
                } else if (collectionFilter == CollectionFilter.OWNED && owned) {
                    this.items.add(it);
                } else if (collectionFilter == CollectionFilter.MISSING && !owned) {
                    this.items.add(it);
                }
            }
        }

        if (!DatabaseManager.CRATES.isEmpty() && !DatabaseManager.CRATE_ITEMS.isEmpty()) {
            DatabaseManager.mapItemsToCrates();
        }

        if (!this.items.isEmpty() && this.currentIndex >= this.items.size()) {
            this.currentIndex = 0;
        }
        this.scrollOffset = 0;
    }

    public ItemEntry getCurrentItem() {
        if (items.isEmpty()) {
            updateItemList();
        }
        if (items.isEmpty()) return null;
        if (currentIndex >= items.size()) currentIndex = 0;
        return items.get(currentIndex);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        long windowHandle = this.minecraft.getWindow().handle();
        boolean isCtrlDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        // 1. Scale card with Ctrl + Mouse Wheel
        if (isCtrlDown) {
            if (verticalAmount > 0) {
                uiScale = Math.min(1.5F, uiScale + 0.05F);
            } else if (verticalAmount < 0) {
                uiScale = Math.max(0.6F, uiScale - 0.05F);
            }
            ModConfig.get().uiScale = uiScale;
            ModConfig.get().save();
            return true;
        }

        // Calculate transformed cursor position relative to the card's top-left
        float centerX = (this.width / 2.0F) + cardOffsetX;
        float centerY = (this.height / 2.0F) + cardOffsetY;
        float localMouseX = (float) ((mouseX - centerX) / uiScale + panelWidth / 2.0F);
        float localMouseY = (float) ((mouseY - centerY) / uiScale + panelHeight / 2.0F);

        // 2. Left Drawer scroll (when open and hovered over the left drawer)
        if (isDrawerOpen && localMouseX < 0 && localMouseX >= -drawerWidth - 6 && localMouseY >= 0 && localMouseY <= panelHeight) {
            if (drawerMaxScroll > 0) {
                drawerScrollOffset = Mth.clamp(drawerScrollOffset - (int) (verticalAmount * 14), 0, drawerMaxScroll);
                return true;
            }
        }

        // 3. Right Card lore scroll (when hovered over the card)
        if (localMouseX >= 0 && localMouseX <= panelWidth && localMouseY >= 0 && localMouseY <= panelHeight) {
            if (maxScroll > 0) {
                scrollOffset = Mth.clamp(scrollOffset - (int) (verticalAmount * 14), 0, maxScroll);
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (isDrawerOpen) {
            double mouseX = event.x();
            double mouseY = event.y();

            float centerX = (this.width / 2.0F) + cardOffsetX;
            float centerY = (this.height / 2.0F) + cardOffsetY;
            float localMouseX = (float) ((mouseX - centerX) / uiScale + panelWidth / 2.0F);
            float localMouseY = (float) ((mouseY - centerY) / uiScale + panelHeight / 2.0F);

            // Check search box focus
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.searchBox != null) {
                int drawerX = -drawerWidth - 4;
                int searchX = drawerX + 6;
                int searchY = 20;
                int searchW = drawerWidth - 12;
                int searchH = 14;

                if (localMouseX >= searchX && localMouseX <= searchX + searchW
                        && localMouseY >= searchY && localMouseY <= searchY + searchH) {
                    this.searchBox.setFocused(true);
                    return true;
                } else {
                    this.searchBox.setFocused(false);
                }
            }

            // Check drawer rows
            for (ClickableRow row : drawerClickables) {
                if (localMouseX >= row.x() && localMouseX <= row.x() + row.width()
                        && localMouseY >= row.y() && localMouseY <= row.y() + row.height()) {
                    row.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                this.searchBox.setFocused(false);
                return true;
            }

            // Give the EditBox a chance to process navigation/editing keys (Backspace, Delete, Arrow keys, etc.)
            this.searchBox.keyPressed(event);

            // Always consume the key event while typing so hotkeys (A, D, R, Tab, C) never trigger
            return true;
        }

        int keyCode = event.key();

        // Switch between Crates and Misc items
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            currentCategory = (currentCategory == ViewCategory.CRATES) ? ViewCategory.MISC : ViewCategory.CRATES;
            currentIndex = 0;
            scrollOffset = 0;
            updateItemList();
            return true;
        }

        // Item navigation: A / D or Left / Right Arrows
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
            if (!items.isEmpty()) {
                currentIndex = (currentIndex + 1) % items.size();
                scrollOffset = 0;
                return true;
            }
        } else if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
            if (!items.isEmpty()) {
                currentIndex = (currentIndex - 1 + items.size()) % items.size();
                scrollOffset = 0;
                return true;
            }
        }

        // Reset position and scale with R
        if (keyCode == GLFW.GLFW_KEY_R) {
            cardOffsetX = 0;
            cardOffsetY = 0;
            uiScale = 1.0F;
            ModConfig.get().cardOffsetX = 0;
            ModConfig.get().cardOffsetY = 0;
            ModConfig.get().uiScale = 1.0F;
            ModConfig.get().save();
            return true;
        }

        // Toggle Crate Explorer drawer with C
        if (keyCode == GLFW.GLFW_KEY_C) {
            this.isDrawerOpen = !this.isDrawerOpen;
            return true;
        }

        // Toggle Owned state on currently viewed item with Spacebar
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            ItemEntry current = getCurrentItem();
            if (current != null) {
                CollectionManager.toggleOwned(current.id());
                if (currentCategory == ViewCategory.COLLECTION && collectionFilter != CollectionFilter.ALL) {
                    updateItemList();
                }
                return true;
            }
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        // --- Mouse Drag Handling ---
        long windowHandle = this.minecraft.getWindow().handle();
        boolean isMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        // Card center in window space
        float centerX = (this.width / 2.0F) + cardOffsetX;
        float centerY = (this.height / 2.0F) + cardOffsetY;

        // Hit-test bounding box scaled around center
        float halfW = (panelWidth * uiScale) / 2.0F;
        float halfH = (panelHeight * uiScale) / 2.0F;
        float cardLeft = centerX - halfW;
        float cardRight = centerX + halfW;
        float cardTop = centerY - halfH;
        float cardBottom = centerY + halfH;

        if (isMouseDown) {
            if (!wasMouseDown) {
                if (mouseX >= cardLeft && mouseX <= cardRight && mouseY >= cardTop && mouseY <= cardBottom) {
                    isDragging = true;
                    dragGrabX = mouseX - cardOffsetX;
                    dragGrabY = mouseY - cardOffsetY;
                }
            } else if (isDragging) {
                cardOffsetX = (int) (mouseX - dragGrabX);
                cardOffsetY = (int) (mouseY - dragGrabY);

                int maxOffsetX = (this.width / 2) - 20;
                int maxOffsetY = (this.height / 2) - 20;
                cardOffsetX = Mth.clamp(cardOffsetX, -maxOffsetX, maxOffsetX);
                cardOffsetY = Mth.clamp(cardOffsetY, -maxOffsetY, maxOffsetY);
            }
        } else {
            if (isDragging) {
                ModConfig.get().cardOffsetX = cardOffsetX;
                ModConfig.get().cardOffsetY = cardOffsetY;
                ModConfig.get().save();
            }
            isDragging = false;
        }
        wasMouseDown = isMouseDown;

        ItemEntry item = getCurrentItem();

        // Calculate panel-relative mouse coordinates matching matrix translation and scaling
        float localMouseX = (float) ((mouseX - centerX) / uiScale + panelWidth / 2.0F);
        float localMouseY = (float) ((mouseY - centerY) / uiScale + panelHeight / 2.0F);

        // Push transformation matrix for centered scaling
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(uiScale, uiScale);
        graphics.pose().translate(-panelWidth / 2.0F, -panelHeight / 2.0F);

        // Render the slide-out crate drawer when open
        renderCrateDrawer(graphics, localMouseX, localMouseY);

        int startX = 0;
        int startY = 0;

        // Base card fill & outer border
        graphics.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0xFF180A22);
        graphics.outline(startX, startY, panelWidth, panelHeight, isDragging ? 0xFFBA68C8 : 0xFF692A82);

        // --- Left Panel: Artwork Frame ---
        int artBoxX = startX + 10;
        int artBoxY = startY + 10;
        int artBoxSize = 110;

        graphics.fill(artBoxX, artBoxY, artBoxX + artBoxSize, artBoxY + artBoxSize, 0xFF08040C);
        graphics.outline(artBoxX, artBoxY, artBoxSize, artBoxSize, 0xFF3D1E4E);

        if (item != null) {
            // --- Temporary Logger: prints only once per item ---
            if (!item.id().equals(lastLoggedId)) {
                lastLoggedId = item.id();
                System.out.println("=== RAW ITEM INSPECTION ===");
                System.out.println("RAW ITEM NAME: " + item.itemName());
                System.out.println("RAW DESC/LORE: " + item.itemHuman());
                System.out.println("===========================");
            }
            String cacheKey = (item.isCrateItem() ? "crate_" : "misc_") + item.id();
            Identifier spriteLoc = TextureDownloader.getOrLoadTexture(item.iconUrl(), cacheKey);
            AnimatedTexture anim = TextureDownloader.getTextureInfo(cacheKey);

            if (spriteLoc != null) {
                // Calculate scale preserving aspect ratio within max 96x96 area
                int maxDim = 96;
                int imgW = (anim != null && anim.width() > 0) ? anim.width() : maxDim;
                int imgH = (anim != null && anim.height() > 0) ? anim.height() : maxDim;

                float scale = Math.min((float) maxDim / imgW, (float) maxDim / imgH);
                int renderW = Math.max(1, Math.round(imgW * scale));
                int renderH = Math.max(1, Math.round(imgH * scale));

                // Center perfectly inside the 110x110 artwork frame
                int spriteX = artBoxX + (artBoxSize - renderW) / 2;
                int spriteY = artBoxY + (artBoxSize - renderH) / 2;

                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        spriteLoc,
                        spriteX,
                        spriteY,
                        0.0F,
                        0.0F,
                        renderW,
                        renderH,
                        renderW,
                        renderH
                );
            } else {
                graphics.centeredText(this.font, Component.literal("§7[ Loading... ]"), artBoxX + (artBoxSize / 2), artBoxY + 50, 0xFFFFFFFF);
            }

            // --- Dynamic Left Metadata ---
            int metaY = artBoxY + artBoxSize + 6;
            if (item.isCrateItem()) {
                graphics.text(this.font, Component.literal("§6Win Chance: §e" + item.winPercentage() + "%"), artBoxX, metaY, 0xFFFFFFFF);
            } else {
                String groupLabel = item.groupId().isEmpty() ? "None" : item.groupId();
                graphics.text(this.font, Component.literal("§dGroup: §f" + groupLabel), artBoxX, metaY, 0xFFFFFFFF);
            }
            metaY += 10;

            graphics.text(this.font, Component.literal("§8Type: " + item.imageType()), artBoxX, metaY, 0xFFFFFFFF);
            metaY += 10;

            graphics.text(this.font, Component.literal("§8ID: " + item.id()), artBoxX, metaY, 0xFFFFFFFF);
            metaY += 10;

            // Display base Minecraft item on left panel if present
            String baseItem = extractBaseMinecraftItem(item.itemHuman());
            if (!baseItem.isEmpty()) {
                graphics.text(this.font, Component.literal("§8Item: §7" + baseItem), artBoxX, metaY, 0xFFFFFFFF);
                metaY += 10;
            }

            // --- Interactive Owned Pill Badge ---
            boolean isOwned = CollectionManager.isOwned(item.id());
            int badgeBoxX = artBoxX;
            int badgeBoxY = metaY;
            int badgeBoxW = artBoxSize;
            int badgeBoxH = 13;

            int badgeBg = isOwned ? 0xDD2E7D32 : 0xB01C0826;
            int badgeBorder = isOwned ? 0xFFA5D6A7 : 0xFF5C2974;
            graphics.fill(badgeBoxX, badgeBoxY, badgeBoxX + badgeBoxW, badgeBoxY + badgeBoxH, badgeBg);
            graphics.outline(badgeBoxX, badgeBoxY, badgeBoxW, badgeBoxH, badgeBorder);

            String badgeText = isOwned ? "§a✔ OWNED §7[Space]" : "§7☐ Unowned §8[Space]";
            graphics.centeredText(this.font, Component.literal(badgeText), badgeBoxX + (badgeBoxW / 2), badgeBoxY + 3, 0xFFFFFFFF);
            metaY += 16;

            // --- Cooldown Pill Badge (Positioned safely above bottom border) ---
            if (item.hasCooldown()) {
                String cdText = "⏱ " + item.getCooldown();
                int cdWidth = this.font.width(cdText);
                int badgeX = artBoxX + 1;
                int badgeY = metaY;

                graphics.fill(badgeX - 2, badgeY - 1, badgeX + cdWidth + 4, badgeY + 11, 0xD012061A);
                graphics.outline(badgeX - 2, badgeY - 1, cdWidth + 6, 12, 0xFFAB47BC);
                graphics.text(this.font, Component.literal("§a" + cdText), badgeX + 1, badgeY + 1, 0xFFFFFFFF);
            }
        } else {
            graphics.centeredText(this.font, Component.literal("§eLoading Database..."), artBoxX + (artBoxSize / 2), artBoxY + 50, 0xFFFFFFFF);
        }

        // --- Right Panel: Info & Multi-line Stats ---
        int textX = startX + 130;
        int lineY = startY + 10;

        if (item != null) {
            // Parse authentic per-character hex gradient if rawData is available; fallback to formatted itemName
            Component titleComp = (item.rawData() != null && !item.rawData().isBlank())
                    ? com.roland.itemdatabase.data.RawDataParser.parseCustomName(item.rawData(), item.itemName())
                    : Component.literal(item.itemName().replace('&', '§').trim());

            int maxHeaderPixelWidth = 180;
            if (this.font.width(titleComp) > maxHeaderPixelWidth) {
                titleComp = Component.literal(this.font.substrByWidth(titleComp, maxHeaderPixelWidth - 10).getString() + "...");
            }

            graphics.text(this.font, titleComp, textX, lineY, 0xFFFFFFFF);
            lineY += 13;

            graphics.horizontalLine(textX, startX + panelWidth - 10, lineY, 0xFF555555);
            lineY += 6;

            // Rarity or Category
            if (item.isCrateItem()) {
                graphics.text(this.font, Component.literal("§7Rarity: §d" + item.rarityHuman()), textX, lineY, 0xFFFFFFFF);
            } else {
                graphics.text(this.font, Component.literal("§7Category: §b" + item.rarityHuman()), textX, lineY, 0xFFFFFFFF);
            }
            lineY += 12;

            // Scrollable viewport bounds in local coordinates
            int viewTop = lineY;
            int viewBottom = panelHeight - 32;
            int viewHeight = viewBottom - viewTop;

            int maxTextWidth = panelWidth - 146;
            List<net.minecraft.util.FormattedCharSequence> renderedLines = new ArrayList<>();

            // 1. Try parsing exact JSON component lore from RawData first
            List<Component> rawLoreComponents = (item.rawData() != null && !item.rawData().isBlank())
                    ? com.roland.itemdatabase.data.RawDataParser.parseLore(item.rawData())
                    : List.of();

            if (!rawLoreComponents.isEmpty()) {
                for (Component loreLine : rawLoreComponents) {
                    String plain = loreLine.getString().trim();
                    if (plain.isEmpty()) continue;
                    if (plain.matches("^[\u2550\u2500=-]+$")) continue;
                    if (plain.toLowerCase().startsWith("minecraft:") || plain.toLowerCase().startsWith("item: minecraft:")) continue;

                    renderedLines.addAll(this.font.split(loreLine, maxTextWidth));
                }
            } else {
                // Fallback to legacy string parsing if rawData is absent
                String rawDesc = item.itemHuman();
                if (rawDesc != null && !rawDesc.isBlank()) {
                    String cleanDesc = rawDesc.replaceAll("(?i)§[0-9a-fk-or]", "");

                    String normalized = cleanDesc
                            .replace("\\r\\n", "\n")
                            .replace("\\n", "\n")
                            .replace("\r", "")
                            .replaceAll("(?i)<br\\s*/?>", "\n")
                            .replaceAll("[\u2550\u2500=-]{2,}", "\n")
                            .replaceAll("\\s*>\\s*", "\n> ")
                            .replaceAll("(?i)(Season \\d+:)", "\n$1")
                            .replaceAll("(?i)(Rarity:)", "\n$1")
                            .replaceAll("(?i)(Unbreakable)", "\n$1")
                            .replaceAll("(?i)(Unmodifiable)", "\n$1")
                            .replaceAll("(?i)(minecraft:[a-z0-9_]+)", "\n$1")
                            .replaceAll("(?<=\\d)\\s+(?=[A-Z][a-z])", "\n")
                            .replaceAll("(?<=\\d)(?=[A-Z])", "\n");

                    String[] rawTokens = normalized.split("[\n\r]+|\\s*[\u2550\u2500\u25C6\u25C7\u2726\u2727\u2022\u25A0\u2748\u273F\u22EE\u25BA\u25C4]+\\s*");
                    String canonicalItemName = item.itemName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

                    for (String token : rawTokens) {
                        String clean = token.replaceAll("\\s+", " ").trim();
                        if (clean.isEmpty()) continue;

                        String tokenKey = clean.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                        if (tokenKey.equals(canonicalItemName)) continue;
                        if (clean.matches("^[\u2550\u2500=-]+$")) continue;
                        if (clean.toLowerCase().startsWith("rarity:")) continue;
                        if (clean.toLowerCase().startsWith("minecraft:") || clean.toLowerCase().startsWith("item: minecraft:")) continue;

                        Component lineComponent;
                        String lower = clean.toLowerCase();

                        if (clean.startsWith(">")) {
                            lineComponent = Component.literal("  §8> §7" + clean.substring(1).trim());
                        } else if (lower.contains("crate")) {
                            String stripped = clean.replaceAll("[▶◀►◄]", "").trim();
                            String crateStyled = stripped.replaceAll("(?i)blossom", "§dBlossom").replaceAll("(?i)crate", "§bCrate");
                            lineComponent = Component.literal("§7▶ " + crateStyled + " §7◀");
                        } else if (lower.startsWith("season")) {
                            lineComponent = Component.literal("§7" + clean.replaceFirst("(?i)season\\s*(\\d+):?", "Season $1: §6❀ §e") + " §6❀");
                        } else if (clean.equalsIgnoreCase("unmodifiable") || clean.equalsIgnoreCase("unbreakable")) {
                            lineComponent = Component.literal("§6✦ §e" + clean);
                        } else {
                            lineComponent = Component.literal("§b• §f" + clean);
                        }

                        renderedLines.addAll(this.font.split(lineComponent, maxTextWidth));
                    }
                }
            }

            if (!item.isCrateItem() && !item.notes().isBlank()) {
                renderedLines.addAll(this.font.split(Component.literal("§eNote: §7" + item.notes()), maxTextWidth));
            }

            int totalContentHeight = renderedLines.size() * 10;
            maxScroll = Math.max(0, totalContentHeight - viewHeight);

            graphics.enableScissor(textX, viewTop, panelWidth - 14, viewBottom);

            int drawY = viewTop - scrollOffset;
            for (net.minecraft.util.FormattedCharSequence line : renderedLines) {
                if (drawY + 10 >= viewTop && drawY <= viewBottom) {
                    graphics.text(this.font, line, textX, drawY, 0xFFFFFFFF);
                }
                drawY += 10;
            }

            graphics.disableScissor();

            // Scrollbar
            if (maxScroll > 0) {
                int barX = startX + panelWidth - 12;
                int barHeight = Math.max(12, (int) ((float) viewHeight / totalContentHeight * viewHeight));
                int barY = viewTop + (int) ((float) scrollOffset / maxScroll * (viewHeight - barHeight));

                graphics.fill(barX, viewTop, barX + 3, viewBottom, 0xFF2A153A);
                graphics.fill(barX, barY, barX + 3, barY + barHeight, 0xFFAB47BC);
            }

            // --- Footer Navigation Prompt ---
            int navY = panelHeight - 24;
            graphics.horizontalLine(10, panelWidth - 10, navY, 0xFF3D1E4E);

            String catLabel = (currentCategory == ViewCategory.CRATES) ? "§6[Tab] §7Misc" : "§6[Tab] §7Crates";
            String drawerLabel = isDrawerOpen ? "§b[C] §7Close Tree" : "§b[C] §7Open Tree";

            graphics.centeredText(
                    this.font,
                    Component.literal("§e[A/D] §7Item   " + catLabel + "   §e[Drag] §7Move"),
                    panelWidth / 2,
                    navY + 3,
                    0xFFFFFFFF
            );

            graphics.centeredText(
                    this.font,
                    Component.literal(drawerLabel + "   §e[Ctrl+Scroll] §7Scale   §e[Scroll] §7Lore   §e[R] §7Reset"),
                    panelWidth / 2,
                    navY + 12,
                    0xFF888888
            );
        }

        graphics.pose().popMatrix();
    }

    private String extractBaseMinecraftItem(String rawDesc) {
        if (rawDesc == null || rawDesc.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)minecraft:([a-z0-9_]+)").matcher(rawDesc);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private void renderCrateDrawer(GuiGraphicsExtractor graphics, float localMouseX, float localMouseY) {
        if (!isDrawerOpen) return;

        drawerClickables.clear();

        int drawerX = -drawerWidth - 4;
        int drawerY = 0;

        // Background panel & border
        graphics.fill(drawerX, drawerY, drawerX + drawerWidth, panelHeight, 0xFF14071D);
        graphics.outline(drawerX, drawerY, drawerWidth, panelHeight, 0xFF4A1A5E);

        // --- Live Search Input Field ---
        int searchX = drawerX + 6;
        int searchY = 20;
        int searchW = drawerWidth - 12;
        int searchH = 14;

        if (this.searchBox != null) {
            this.searchBox.setPosition(searchX, searchY);
            this.searchBox.setWidth(searchW);
            this.searchBox.extractRenderState(graphics, 0, 0, 0.0F);
        }

        String filterQuery = (this.searchBox != null) ? this.searchBox.getValue().trim().toLowerCase() : "";
        boolean isFiltering = !filterQuery.isEmpty();

        // --- Mode Selector: [ Crates | Misc | Tags ] ---
        int modeY = searchY + 18;
        ViewCategory[] modes = ViewCategory.values();
        int modeW = (drawerWidth - 8) / modes.length;

        for (int i = 0; i < modes.length; i++) {
            ViewCategory mode = modes[i];
            int mx = drawerX + 4 + (i * modeW);
            boolean isSelected = (this.currentCategory == mode);

            int bg = isSelected ? 0xFF692A82 : 0xFF220D30;
            graphics.fill(mx, modeY, mx + modeW - 1, modeY + 13, bg);
            graphics.outline(mx, modeY, modeW - 1, 13, isSelected ? 0xFFBA68C8 : 0xFF3D1650);

            String color = isSelected ? "§f§l" : "§7";
            String label = switch (mode) {
                case CRATES -> "Crates";
                case MISC -> "Misc";
                case TAGS -> "Tags";
                case COLLECTION -> "Collect";
            };
            graphics.centeredText(this.font, Component.literal(color + label), mx + (modeW / 2), modeY + 3, 0xFFFFFFFF);

            drawerClickables.add(new ClickableRow(mx, modeY, modeW, 13, () -> {
                this.currentCategory = mode;
                this.drawerScrollOffset = 0;
                updateItemList();
            }));
        }

        int tabY = modeY + 16;
        int viewTop = tabY + 20;
        int viewBottom = panelHeight - 6;
        int viewHeight = viewBottom - viewTop;

        int totalHeight = 0;
        int currentY = viewTop - drawerScrollOffset;

        if (currentCategory == ViewCategory.CRATES) {
            // ==========================================
            // CRATE TABS: [Seasonal | Side | Misc]
            // ==========================================
            CrateTab[] tabs = CrateTab.values();
            int tabW = (drawerWidth - 8) / tabs.length;

            for (int i = 0; i < tabs.length; i++) {
                CrateTab tab = tabs[i];
                int tx = drawerX + 4 + (i * tabW);
                boolean isSelected = (this.selectedTab == tab);

                int tabBg = isSelected ? 0xFF692A82 : 0xFF220D30;
                graphics.fill(tx, tabY, tx + tabW - 1, tabY + 14, tabBg);
                graphics.outline(tx, tabY, tabW - 1, 14, isSelected ? 0xFFBA68C8 : 0xFF3D1650);

                String color = isSelected ? "§f" : "§7";
                graphics.centeredText(this.font, Component.literal(color + tab.label()), tx + (tabW / 2), tabY + 3, 0xFFFFFFFF);

                drawerClickables.add(new ClickableRow(tx, tabY, tabW, 14, () -> {
                    this.selectedTab = tab;
                    this.drawerScrollOffset = 0;
                }));
            }

            graphics.horizontalLine(drawerX + 4, drawerX + drawerWidth - 4, tabY + 16, 0xFF3D1650);

            List<com.roland.itemdatabase.data.CrateEntry> filteredCrates = DatabaseManager.CRATES.values().stream()
                    .filter(c -> c.crateType().equalsIgnoreCase(selectedTab.label()))
                    .toList();

            for (com.roland.itemdatabase.data.CrateEntry crate : filteredCrates) {
                List<ItemEntry> visibleItems = isFiltering
                        ? crate.items().stream().filter(it -> it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery)).toList()
                        : crate.items();

                if (isFiltering && visibleItems.isEmpty() && !crate.name().toLowerCase().contains(filterQuery)) {
                    continue;
                }

                boolean isExpanded = isFiltering || (crate.id() == expandedCrateId);
                totalHeight += 12;
                if (isExpanded) {
                    totalHeight += (visibleItems.size() * 10);
                }
            }
            drawerMaxScroll = Math.max(0, totalHeight - viewHeight);

            graphics.enableScissor(drawerX + 4, viewTop, drawerX + drawerWidth - 4, viewBottom);

            for (com.roland.itemdatabase.data.CrateEntry crate : filteredCrates) {
                List<ItemEntry> visibleItems = isFiltering
                        ? crate.items().stream().filter(it -> it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery)).toList()
                        : crate.items();

                if (isFiltering && visibleItems.isEmpty() && !crate.name().toLowerCase().contains(filterQuery)) {
                    continue;
                }

                boolean isExpanded = isFiltering || (crate.id() == expandedCrateId);

                // Get the authentic gradient Component from the crate's items
                Component baseHeader = crate.getFormattedHeader(isExpanded);

                int count = visibleItems.size();
                Component countComp = Component.literal(" §8(" + count + ")");

                int maxHeaderWidth = drawerWidth - 20;
                net.minecraft.network.chat.MutableComponent crateHeaderComp = Component.empty();

                if (this.font.width(baseHeader.getString() + " (" + count + ")") > maxHeaderWidth) {
                    int available = maxHeaderWidth - this.font.width(countComp);
                    crateHeaderComp.append(truncateComponent(baseHeader, available)).append(countComp);
                } else {
                    crateHeaderComp.append(baseHeader).append(countComp);
                }

                if (currentY + 10 >= viewTop && currentY <= viewBottom) {
                    graphics.text(this.font, crateHeaderComp, drawerX + 6, currentY, 0xFFFFFFFF);
                }

                int rowYCapture = currentY;
                drawerClickables.add(new ClickableRow(drawerX + 4, rowYCapture, drawerWidth - 8, 12, () -> {
                    if (!isFiltering) {
                        expandedCrateId = (expandedCrateId == crate.id()) ? -1 : crate.id();
                    }
                }));
                currentY += 12;

                if (isExpanded) {
                    for (ItemEntry item : visibleItems) {
                        if (currentY + 10 >= viewTop && currentY <= viewBottom) {
                            boolean isCurrent = !this.items.isEmpty()
                                    && this.currentIndex < this.items.size()
                                    && this.items.get(this.currentIndex).id().equals(item.id());

                            boolean isHovered = localMouseX >= drawerX + 4 && localMouseX <= drawerX + drawerWidth - 4
                                    && localMouseY >= currentY - 1 && localMouseY < currentY + 10;

                            if (isCurrent) {
                                graphics.fill(drawerX + 6, currentY - 1, drawerX + drawerWidth - 6, currentY + 10, 0x55BA68C8);
                            } else if (isHovered) {
                                graphics.fill(drawerX + 6, currentY - 1, drawerX + drawerWidth - 6, currentY + 10, 0x35FFFFFF);
                            }

                            boolean owned = CollectionManager.isOwned(item.id());
                            String statusIcon = owned ? "§a✔ " : "§8• ";
                            String prefix = isCurrent ? "  §e▶ " : "  " + statusIcon;

                            // Use the full per-character gradient from RawData if available; otherwise fallback to rarity color
                            net.minecraft.network.chat.Component nameComp = (item.rawData() != null && !item.rawData().isBlank())
                                    ? com.roland.itemdatabase.data.RawDataParser.parseCustomName(item.rawData(), item.itemName())
                                    : net.minecraft.network.chat.Component.literal(getRarityColor(item.rarityHuman()) + item.itemName());

                            // If currently selected, highlight with bold white; otherwise keep its gradient/rarity color
                            net.minecraft.network.chat.Component finalName = isCurrent
                                    ? net.minecraft.network.chat.Component.literal("§f§l" + nameComp.getString())
                                    : nameComp;

                            // Combine the prefix and item component into a single formatted line
                            net.minecraft.network.chat.MutableComponent itemRowComp = net.minecraft.network.chat.Component.literal(prefix).append(finalName);

                            // Safely truncate to fit the drawer width while preserving formatting
                            int availableTextWidth = drawerWidth - 24;
                            if (this.font.width(itemRowComp) > availableTextWidth) {
                                String truncated = this.font.substrByWidth(itemRowComp, availableTextWidth - 8).getString();
                                graphics.text(this.font, net.minecraft.network.chat.Component.literal(truncated + ".."), drawerX + 8, currentY, 0xFFFFFFFF);
                            } else {
                                graphics.text(this.font, itemRowComp, drawerX + 8, currentY, 0xFFFFFFFF);
                            }
                        }

                        int itemYCapture = currentY;
                        drawerClickables.add(new ClickableRow(drawerX + 8, itemYCapture, drawerWidth - 12, 10, () -> {
                            this.currentCategory = ViewCategory.CRATES;
                            updateItemList();
                            for (int idx = 0; idx < items.size(); idx++) {
                                if (items.get(idx).id().equals(item.id())) {
                                    this.currentIndex = idx;
                                    this.scrollOffset = 0;
                                    break;
                                }
                            }
                        }));
                        currentY += 10;
                    }
                }
            }

            graphics.disableScissor();

        } else if (currentCategory == ViewCategory.MISC) {
            // ========================================================
            // MISC TABS: [Unsorted | Voucher | Event | Furniture]
            // ========================================================
            MiscTab[] tabs = MiscTab.values();
            int tabW = (drawerWidth - 8) / tabs.length;

            for (int i = 0; i < tabs.length; i++) {
                MiscTab tab = tabs[i];
                int tx = drawerX + 4 + (i * tabW);
                boolean isSelected = (this.selectedMiscTab == tab);

                int tabBg = isSelected ? 0xFF692A82 : 0xFF220D30;
                graphics.fill(tx, tabY, tx + tabW - 1, tabY + 14, tabBg);
                graphics.outline(tx, tabY, tabW - 1, 14, isSelected ? 0xFFBA68C8 : 0xFF3D1650);

                String color = isSelected ? "§f" : "§7";
                graphics.centeredText(this.font, Component.literal(color + tab.label()), tx + (tabW / 2), tabY + 3, 0xFFFFFFFF);

                drawerClickables.add(new ClickableRow(tx, tabY, tabW, 14, () -> {
                    this.selectedMiscTab = tab;
                    this.drawerScrollOffset = 0;
                }));
            }

            graphics.horizontalLine(drawerX + 4, drawerX + drawerWidth - 4, tabY + 16, 0xFF3D1650);

            List<com.roland.itemdatabase.data.MiscGroupEntry> filteredGroups = DatabaseManager.MISC_GROUPS.values().stream()
                    .filter(g -> {
                        if (selectedMiscTab == MiscTab.UNSORTED) {
                            return g.groupType() == null
                                    || g.groupType().isBlank()
                                    || g.groupType().equalsIgnoreCase("Unsorted")
                                    || g.groupType().equalsIgnoreCase("None")
                                    || g.groupType().equalsIgnoreCase("null");
                        }
                        return g.groupType().equalsIgnoreCase(selectedMiscTab.label());
                    })
                    .toList();

            for (com.roland.itemdatabase.data.MiscGroupEntry group : filteredGroups) {
                List<ItemEntry> visibleItems = isFiltering
                        ? group.items().stream().filter(it -> it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery)).toList()
                        : group.items();

                if (isFiltering && visibleItems.isEmpty() && !group.name().toLowerCase().contains(filterQuery)) {
                    continue;
                }

                boolean isExpanded = isFiltering || (group.id() == expandedMiscGroupId);
                totalHeight += 12;
                if (isExpanded) {
                    totalHeight += (visibleItems.size() * 10);
                }
            }
            drawerMaxScroll = Math.max(0, totalHeight - viewHeight);

            graphics.enableScissor(drawerX + 4, viewTop, drawerX + drawerWidth - 4, viewBottom);

            for (com.roland.itemdatabase.data.MiscGroupEntry group : filteredGroups) {
                List<ItemEntry> visibleItems = isFiltering
                        ? group.items().stream().filter(it -> it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery)).toList()
                        : group.items();

                if (isFiltering && visibleItems.isEmpty() && !group.name().toLowerCase().contains(filterQuery)) {
                    continue;
                }

                boolean isExpanded = isFiltering || (group.id() == expandedMiscGroupId);

                Component arrowComp = Component.literal(isExpanded ? "§d▼ " : "§8▶ ");
                Component baseHeader = group.getFormattedHeader();

                int count = visibleItems.size();
                Component countComp = Component.literal(" §8(" + count + ")");

                // Calculate available width specifically for the title text
                int maxTitleWidth = drawerWidth - 20 - this.font.width(arrowComp) - this.font.width(countComp);

                net.minecraft.network.chat.MutableComponent groupRowComp = Component.empty();
                groupRowComp.append(arrowComp);

                if (this.font.width(baseHeader) > maxTitleWidth) {
                    groupRowComp.append(truncateComponent(baseHeader, maxTitleWidth));
                } else {
                    groupRowComp.append(baseHeader);
                }

                groupRowComp.append(countComp);

                if (currentY + 10 >= viewTop && currentY <= viewBottom) {
                    graphics.text(this.font, groupRowComp, drawerX + 6, currentY, 0xFFFFFFFF);
                }

                int rowYCapture = currentY;
                drawerClickables.add(new ClickableRow(drawerX + 4, rowYCapture, drawerWidth - 8, 12, () -> {
                    if (!isFiltering) {
                        expandedMiscGroupId = (expandedMiscGroupId == group.id()) ? -1 : group.id();
                    }
                }));
                currentY += 12;

                if (isExpanded) {
                    for (ItemEntry item : visibleItems) {
                        if (currentY + 10 >= viewTop && currentY <= viewBottom) {
                            boolean isCurrent = !this.items.isEmpty()
                                    && this.currentIndex < this.items.size()
                                    && this.items.get(this.currentIndex).id().equals(item.id());

                            boolean isHovered = localMouseX >= drawerX + 4 && localMouseX <= drawerX + drawerWidth - 4
                                    && localMouseY >= currentY - 1 && localMouseY < currentY + 10;

                            if (isCurrent) {
                                graphics.fill(drawerX + 6, currentY - 1, drawerX + drawerWidth - 6, currentY + 10, 0x55BA68C8);
                            } else if (isHovered) {
                                graphics.fill(drawerX + 6, currentY - 1, drawerX + drawerWidth - 6, currentY + 10, 0x35FFFFFF);
                            }

                            boolean owned = CollectionManager.isOwned(item.id());
                            String statusIcon = owned ? "§a✔ " : "§8• ";
                            String prefix = isCurrent ? "  §e▶ " : "  " + statusIcon;

                            // Use gradient from RawData if available; fallback to rarity color
                            net.minecraft.network.chat.Component miscNameComp = (item.rawData() != null && !item.rawData().isBlank())
                                    ? com.roland.itemdatabase.data.RawDataParser.parseCustomName(item.rawData(), item.itemName())
                                    : net.minecraft.network.chat.Component.literal(getRarityColor(item.rarityHuman()) + item.itemName());

                            // Bold white when selected
                            net.minecraft.network.chat.Component finalMiscName = isCurrent
                                    ? net.minecraft.network.chat.Component.literal("§f§l" + miscNameComp.getString())
                                    : miscNameComp;

                            net.minecraft.network.chat.MutableComponent miscRowComp = net.minecraft.network.chat.Component.literal(prefix).append(finalMiscName);

                            int availableTextWidth = drawerWidth - 24;
                            if (this.font.width(miscRowComp) > availableTextWidth) {
                                String truncated = this.font.substrByWidth(miscRowComp, availableTextWidth - 8).getString();
                                graphics.text(this.font, net.minecraft.network.chat.Component.literal(truncated + ".."), drawerX + 8, currentY, 0xFFFFFFFF);
                            } else {
                                graphics.text(this.font, miscRowComp, drawerX + 8, currentY, 0xFFFFFFFF);
                            }
                        }

                        int itemYCapture = currentY;
                        drawerClickables.add(new ClickableRow(drawerX + 8, itemYCapture, drawerWidth - 12, 10, () -> {
                            this.currentCategory = ViewCategory.MISC;
                            updateItemList();
                            for (int idx = 0; idx < items.size(); idx++) {
                                if (items.get(idx).id().equals(item.id())) {
                                    this.currentIndex = idx;
                                    this.scrollOffset = 0;
                                    break;
                                }
                            }
                        }));
                        currentY += 10;
                    }
                }
            }

            graphics.disableScissor();

        } else if (currentCategory == ViewCategory.TAGS) {
            // ========================================================
            // TAG CATEGORY TABS: Carousel Category Header
            // ========================================================
            com.roland.itemdatabase.data.TagCategory[] tagCats = com.roland.itemdatabase.data.TagCategory.values();
            int catBtnW = drawerWidth - 8;

            graphics.fill(drawerX + 4, tabY, drawerX + 4 + catBtnW, tabY + 14, 0xFF692A82);
            graphics.outline(drawerX + 4, tabY, catBtnW, 14, 0xFFBA68C8);
            graphics.centeredText(this.font, Component.literal("§e◀ §f" + selectedTagCategory.label() + " §e▶"), drawerX + 4 + (catBtnW / 2), tabY + 3, 0xFFFFFFFF);

            drawerClickables.add(new ClickableRow(drawerX + 4, tabY, catBtnW, 14, () -> {
                int nextIdx = (selectedTagCategory.ordinal() + 1) % tagCats.length;
                this.selectedTagCategory = tagCats[nextIdx];
                this.expandedTagName = null;
                this.drawerScrollOffset = 0;
            }));

            graphics.horizontalLine(drawerX + 4, drawerX + drawerWidth - 4, tabY + 16, 0xFF3D1650);

            // Combine all items across Crate and Misc caches
            List<ItemEntry> allItems = new ArrayList<>();
            allItems.addAll(DatabaseManager.CRATE_ITEMS.values());
            allItems.addAll(DatabaseManager.MISC_ITEMS.values());

            // Calculate height for scrolling
            for (String tag : selectedTagCategory.tags()) {
                List<ItemEntry> matching = allItems.stream()
                        .filter(it -> it.hasTag(tag))
                        .filter(it -> !isFiltering || it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery))
                        .toList();

                if (isFiltering && matching.isEmpty()) continue;

                boolean isExpanded = isFiltering || tag.equalsIgnoreCase(expandedTagName);
                totalHeight += 12;
                if (isExpanded) {
                    totalHeight += (matching.size() * 10);
                }
            }
            drawerMaxScroll = Math.max(0, totalHeight - viewHeight);

            graphics.enableScissor(drawerX + 4, viewTop, drawerX + drawerWidth - 4, viewBottom);

            for (String tag : selectedTagCategory.tags()) {
                List<ItemEntry> matching = allItems.stream()
                        .filter(it -> it.hasTag(tag))
                        .filter(it -> !isFiltering || it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery))
                        .toList();

                if (isFiltering && matching.isEmpty()) continue;

                boolean isExpanded = isFiltering || tag.equalsIgnoreCase(expandedTagName);
                String arrow = isExpanded ? "§d▼ " : "§8▶ ";
                String rowText = arrow + "§b" + tag + " §8(" + matching.size() + ")";

                if (currentY + 10 >= viewTop && currentY <= viewBottom) {
                    graphics.text(this.font, Component.literal(rowText), drawerX + 6, currentY, 0xFFFFFFFF);
                }

                int tagYCapture = currentY;
                drawerClickables.add(new ClickableRow(drawerX + 4, tagYCapture, drawerWidth - 8, 12, () -> {
                    if (!isFiltering) {
                        expandedTagName = tag.equalsIgnoreCase(expandedTagName) ? null : tag;
                    }
                }));
                currentY += 12;

                if (isExpanded) {
                    for (ItemEntry item : matching) {
                        if (currentY + 10 >= viewTop && currentY <= viewBottom) {
                            boolean isCurrent = !this.items.isEmpty()
                                    && this.currentIndex < this.items.size()
                                    && this.items.get(this.currentIndex).id().equals(item.id());

                            boolean isHovered = localMouseX >= drawerX + 4 && localMouseX <= drawerX + drawerWidth - 4
                                    && localMouseY >= currentY - 1 && localMouseY < currentY + 10;

                            if (isCurrent) {
                                graphics.fill(drawerX + 6, currentY - 1, drawerX + drawerWidth - 6, currentY + 10, 0x55BA68C8);
                            } else if (isHovered) {
                                graphics.fill(drawerX + 6, currentY - 1, drawerX + drawerWidth - 6, currentY + 10, 0x35FFFFFF);
                            }

                            String cleanName = item.itemName().replaceAll("(?i)§[0-9a-fk-or]", "").trim();
                            String rarityColor = getRarityColor(item.rarityHuman());

                            boolean owned = CollectionManager.isOwned(item.id());
                            String statusIcon = owned ? "§a✔ " : "§8• ";
                            String prefix = isCurrent ? "  §e▶ " : "  " + statusIcon;
                            String textFormat = isCurrent ? "§f§l" : rarityColor;

                            int availableTextWidth = drawerWidth - 24;
                            if (this.font.width(prefix + textFormat + cleanName) > availableTextWidth) {
                                while (cleanName.length() > 3 && this.font.width(prefix + textFormat + cleanName + "..") > availableTextWidth) {
                                    cleanName = cleanName.substring(0, cleanName.length() - 1);
                                }
                                cleanName += "..";
                            }
                            graphics.text(this.font, Component.literal(prefix + textFormat + cleanName), drawerX + 8, currentY, 0xFFFFFFFF);
                        }

                        int itemYCapture = currentY;
                        drawerClickables.add(new ClickableRow(drawerX + 8, itemYCapture, drawerWidth - 12, 10, () -> {
                            this.items.clear();
                            this.items.addAll(allItems);
                            for (int idx = 0; idx < items.size(); idx++) {
                                if (items.get(idx).id().equals(item.id())) {
                                    this.currentIndex = idx;
                                    this.scrollOffset = 0;
                                    break;
                                }
                            }
                        }));
                        currentY += 10;
                    }
                }
            }

            graphics.disableScissor();

        } else if (currentCategory == ViewCategory.COLLECTION) {
            // ========================================================
            // COLLECTION MODE: Grouped by Crate with Completion Counts
            // ========================================================
            List<ItemEntry> totalAll = new ArrayList<>();
            totalAll.addAll(DatabaseManager.CRATE_ITEMS.values());
            totalAll.addAll(DatabaseManager.MISC_ITEMS.values());

            int totalCount = totalAll.size();
            int ownedCount = 0;
            for (ItemEntry it : totalAll) {
                if (CollectionManager.isOwned(it.id())) ownedCount++;
            }

            int percent = totalCount > 0 ? (int) ((ownedCount / (float) totalCount) * 100) : 0;

            // --- Progress Header ---
            graphics.fill(drawerX + 4, tabY, drawerX + drawerWidth - 4, tabY + 14, 0xFF220D30);
            graphics.outline(drawerX + 4, tabY, drawerWidth - 8, 14, 0xFF4A1A5E);

            int barWidth = (int) (((drawerWidth - 12) * ownedCount) / (float) Math.max(1, totalCount));
            if (barWidth > 0) {
                graphics.fill(drawerX + 6, tabY + 2, drawerX + 6 + barWidth, tabY + 12, 0xAA8E24AA);
            }
            graphics.centeredText(
                    this.font,
                    Component.literal("§6" + ownedCount + "§7/§f" + totalCount + " §e(" + percent + "%)"),
                    drawerX + (drawerWidth / 2),
                    tabY + 3,
                    0xFFFFFFFF
            );

            // --- Filter Buttons: [ All | Owned | Missing ] ---
            int filterY = tabY + 17;
            CollectionFilter[] filters = CollectionFilter.values();
            int filterW = (drawerWidth - 8) / filters.length;

            for (int i = 0; i < filters.length; i++) {
                CollectionFilter f = filters[i];
                int fx = drawerX + 4 + (i * filterW);
                boolean isSelected = (this.collectionFilter == f);

                int fBg = isSelected ? 0xFF692A82 : 0xFF220D30;
                graphics.fill(fx, filterY, fx + filterW - 1, filterY + 13, fBg);
                graphics.outline(fx, filterY, filterW - 1, 13, isSelected ? 0xFFBA68C8 : 0xFF3D1650);

                String color = isSelected ? "§f§l" : "§7";
                graphics.centeredText(this.font, Component.literal(color + f.label()), fx + (filterW / 2), filterY + 3, 0xFFFFFFFF);

                drawerClickables.add(new ClickableRow(fx, filterY, filterW, 13, () -> {
                    this.collectionFilter = f;
                    this.drawerScrollOffset = 0;
                    updateItemList();
                }));
            }

            int collViewTop = filterY + 16;
            int collViewHeight = viewBottom - collViewTop;

            // Group items by crate and calculate heights
            List<com.roland.itemdatabase.data.CrateEntry> allCrates = new ArrayList<>(DatabaseManager.CRATES.values());

            for (com.roland.itemdatabase.data.CrateEntry crate : allCrates) {
                List<ItemEntry> crateMatching = crate.items().stream()
                        .filter(it -> {
                            boolean owned = CollectionManager.isOwned(it.id());
                            if (collectionFilter == CollectionFilter.OWNED && !owned) return false;
                            if (collectionFilter == CollectionFilter.MISSING && owned) return false;
                            return !isFiltering || it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery);
                        })
                        .toList();

                if (crateMatching.isEmpty() && (isFiltering || collectionFilter != CollectionFilter.ALL)) {
                    continue;
                }

                boolean isExpanded = isFiltering || (crate.id() == expandedCollectionCrateId);
                totalHeight += 12;
                if (isExpanded) {
                    totalHeight += (crateMatching.size() * 10);
                }
            }

            drawerMaxScroll = Math.max(0, totalHeight - collViewHeight);
            graphics.enableScissor(drawerX + 4, collViewTop, drawerX + drawerWidth - 4, viewBottom);

            int currentDrawY = collViewTop - drawerScrollOffset;

            for (com.roland.itemdatabase.data.CrateEntry crate : allCrates) {
                // Calculate crate-level owned count
                int crateOwned = 0;
                for (ItemEntry it : crate.items()) {
                    if (CollectionManager.isOwned(it.id())) crateOwned++;
                }

                List<ItemEntry> crateMatching = crate.items().stream()
                        .filter(it -> {
                            boolean owned = CollectionManager.isOwned(it.id());
                            if (collectionFilter == CollectionFilter.OWNED && !owned) return false;
                            if (collectionFilter == CollectionFilter.MISSING && owned) return false;
                            return !isFiltering || it.itemName().toLowerCase().contains(filterQuery) || it.id().contains(filterQuery);
                        })
                        .toList();

                if (crateMatching.isEmpty() && (isFiltering || collectionFilter != CollectionFilter.ALL)) {
                    continue;
                }

                boolean isExpanded = isFiltering || (crate.id() == expandedCollectionCrateId);
                String arrow = isExpanded ? "§d▼ " : "§8▶ ";
                String compColor = (crateOwned == crate.items().size() && !crate.items().isEmpty()) ? "§a" : "§e";
                String crateHeader = arrow + "§f" + crate.name() + " " + compColor + "(" + crateOwned + "/" + crate.items().size() + ")";

                if (this.font.width(crateHeader) > drawerWidth - 20) {
                    while (crateHeader.length() > 5 && this.font.width(crateHeader + "...") > drawerWidth - 20) {
                        crateHeader = crateHeader.substring(0, crateHeader.length() - 1);
                    }
                    crateHeader += "...";
                }

                if (currentDrawY + 10 >= collViewTop && currentDrawY <= viewBottom) {
                    graphics.text(this.font, Component.literal(crateHeader), drawerX + 6, currentDrawY, 0xFFFFFFFF);
                }

                int rowYCap = currentDrawY;
                drawerClickables.add(new ClickableRow(drawerX + 4, rowYCap, drawerWidth - 8, 12, () -> {
                    if (!isFiltering) {
                        expandedCollectionCrateId = (expandedCollectionCrateId == crate.id()) ? -1 : crate.id();
                    }
                }));
                currentDrawY += 12;

                if (isExpanded) {
                    for (ItemEntry item : crateMatching) {
                        if (currentDrawY + 10 >= collViewTop && currentDrawY <= viewBottom) {
                            boolean isCurrent = !this.items.isEmpty()
                                    && this.currentIndex < this.items.size()
                                    && this.items.get(this.currentIndex).id().equals(item.id());

                            boolean isHovered = localMouseX >= drawerX + 4 && localMouseX <= drawerX + drawerWidth - 4
                                    && localMouseY >= currentDrawY - 1 && localMouseY < currentDrawY + 10;

                            if (isCurrent) {
                                graphics.fill(drawerX + 6, currentDrawY - 1, drawerX + drawerWidth - 6, currentDrawY + 10, 0x55BA68C8);
                            } else if (isHovered) {
                                graphics.fill(drawerX + 6, currentDrawY - 1, drawerX + drawerWidth - 6, currentDrawY + 10, 0x35FFFFFF);
                            }

                            boolean owned = CollectionManager.isOwned(item.id());
                            String statusIcon = owned ? "§a✔ " : "§8• ";
                            String prefix = isCurrent ? "  §e▶ " : "  " + statusIcon;
                            String textFormat = isCurrent ? "§f§l" : getRarityColor(item.rarityHuman());
                            String cleanName = item.itemName().replaceAll("(?i)§[0-9a-fk-or]", "").trim();

                            int availableTextWidth = drawerWidth - 24;
                            if (this.font.width(prefix + textFormat + cleanName) > availableTextWidth) {
                                while (cleanName.length() > 3 && this.font.width(prefix + textFormat + cleanName + "..") > availableTextWidth) {
                                    cleanName = cleanName.substring(0, cleanName.length() - 1);
                                }
                                cleanName += "..";
                            }

                            graphics.text(this.font, Component.literal(prefix + textFormat + cleanName), drawerX + 8, currentDrawY, 0xFFFFFFFF);
                        }

                        int itemYCap = currentDrawY;
                        drawerClickables.add(new ClickableRow(drawerX + 8, itemYCap, drawerWidth - 12, 10, () -> {
                            for (int idx = 0; idx < items.size(); idx++) {
                                if (items.get(idx).id().equals(item.id())) {
                                    this.currentIndex = idx;
                                    this.scrollOffset = 0;
                                    break;
                                }
                            }
                        }));
                        currentDrawY += 10;
                    }
                }
            }

            graphics.disableScissor();
        }

        // --- Drawer Scrollbar ---
        if (drawerMaxScroll > 0) {
            int barX = drawerX + drawerWidth - 3;
            int barHeight = Math.max(10, (int) ((float) viewHeight / totalHeight * viewHeight));
            int barY = viewTop + (int) ((float) drawerScrollOffset / drawerMaxScroll * (viewHeight - barHeight));

            graphics.fill(barX, viewTop, barX + 2, viewBottom, 0xFF220D30);
            graphics.fill(barX, barY, barX + 2, barY + barHeight, 0xFF8E24AA);
        }
    }

    @Override
    protected void init() {
        super.init();
        this.searchBox = new EditBox(this.font, 0, 0, drawerWidth - 12, 14, Component.literal("Search..."));
        this.searchBox.setHint(Component.literal("§8Search items..."));
        this.searchBox.setMaxLength(32);
        this.searchBox.setResponder(s -> {
            this.drawerScrollOffset = 0;
        });
        this.addWidget(this.searchBox);
    }

    private String getRarityColor(String rarity) {
        if (rarity == null) return "§7";
        return switch (rarity.trim().toUpperCase()) {
            case "EXOTIC" -> "§c";       // Bright Red
            case "LEGENDARY" -> "§6";    // Gold
            case "MYTHIC" -> "§d";       // Light Purple / Pink
            case "EPIC" -> "§5";         // Dark Purple
            case "RARE" -> "§b";         // Aqua
            case "UNCOMMON" -> "§a";     // Green
            case "COMMON" -> "§f";       // White
            default -> "§e";             // Yellow for custom/misc groups
        };
    }

    private static String formatColors(String text) {
        if (text == null) return "";
        return text.replace('&', '§');
    }

    /**
     * Safely truncates a Component to fit within maxWidth, preserving all styles and colors.
     */
    private net.minecraft.network.chat.Component truncateComponent(net.minecraft.network.chat.Component comp, int maxWidth) {
        if (this.font.width(comp) <= maxWidth) {
            return comp;
        }
        int targetWidth = maxWidth - this.font.width("..");
        if (targetWidth <= 0) return comp;

        net.minecraft.network.chat.MutableComponent truncated = net.minecraft.network.chat.Component.empty();
        comp.visit((style, text) -> {
            char[] chars = text.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                // If this is a formatting code, grab both characters together
                if (chars[i] == '§' && i + 1 < chars.length) {
                    truncated.append(net.minecraft.network.chat.Component.literal("§" + chars[i + 1]));
                    i++;
                    continue;
                }
                String ch = String.valueOf(chars[i]);
                if (this.font.width(truncated.getString() + ch) <= targetWidth) {
                    truncated.append(net.minecraft.network.chat.Component.literal(ch).setStyle(style));
                } else {
                    return java.util.Optional.of(true);
                }
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);

        return truncated.append(net.minecraft.network.chat.Component.literal(".."));
    }
}