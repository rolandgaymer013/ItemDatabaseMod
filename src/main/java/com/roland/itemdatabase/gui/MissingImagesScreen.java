package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Administrative GUI screen displaying crate and misc items with missing icons.
 * Mirrors the website's `/admin/missingimages` dashboard, styled with the
 * BlossomCraft dark violet and slate aesthetic.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Scans in-memory database entries for empty, null, or failed texture URLs.</li>
 *   <li>Presents dual scrollable columns for missing Crate icons and Misc icons.</li>
 *   <li>Provides quick navigation directly to {@link AdminUploadScreen} on row click.</li>
 *   <li>Triggers asynchronous database resyncing via the Refresh action button.</li>
 * </ul>
 */
public class MissingImagesScreen extends Screen {

    /** Screen to return to upon closing or cancellation. */
    private final Screen parentScreen;

    /** Cached collection of crate items lacking custom web images. */
    private final List<ItemEntry> missingCrateIcons = new ArrayList<>();

    /** Cached collection of miscellaneous items lacking custom web images. */
    private final List<ItemEntry> missingMiscIcons = new ArrayList<>();

    /** Vertical scroll offset for the left column (Crate Icons). */
    private int crateScrollOffset = 0;

    /** Vertical scroll offset for the right column (Misc Icons). */
    private int miscScrollOffset = 0;

    /**
     * Constructs the Missing Images administrative overview screen.
     *
     * @param parentScreen Parent screen to display upon escaping or canceling.
     */
    public MissingImagesScreen(Screen parentScreen) {
        super(Component.literal("Missing Images"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        refreshMissingLists();
    }

    /**
     * Scans all loaded crate and misc items across DatabaseManager,
     * populating the missing item collections and rebuilding UI widgets.
     */
    private void refreshMissingLists() {
        missingCrateIcons.clear();
        missingMiscIcons.clear();

        com.roland.itemdatabase.ItemDatabase.LOGGER.info("[MissingImages] Scanning {} crate items and {} misc items...",
                DatabaseManager.CRATE_ITEMS.size(), DatabaseManager.MISC_ITEMS.size());

        for (ItemEntry item : DatabaseManager.CRATE_ITEMS.values()) {
            if (isMissingImage(item)) {
                missingCrateIcons.add(item);
            }
        }

        for (ItemEntry item : DatabaseManager.MISC_ITEMS.values()) {
            if (isMissingImage(item)) {
                missingMiscIcons.add(item);
            }
        }

        com.roland.itemdatabase.ItemDatabase.LOGGER.info("[MissingImages] Found {} missing crate icons and {} missing misc icons.",
                missingCrateIcons.size(), missingMiscIcons.size());

        rebuildLayout();
    }

    /**
     * Inspects an item's ImageType, icon URL, and TextureDownloader download history
     * to determine if an icon file is missing or failed to resolve.
     *
     * @param item Item record to validate.
     * @return True if the image is considered missing or broken.
     */
    private boolean isMissingImage(ItemEntry item) {
        String type = item.imageType();
        String url = item.iconUrl();

        // 1. Direct null/empty/None in database record
        if (type == null || type.isBlank() || type.equalsIgnoreCase("none") || type.equalsIgnoreCase("null")) {
            return true;
        }

        if (url == null || url.isBlank()
                || url.endsWith("/null")
                || url.endsWith("/None")
                || url.endsWith("/None.png")
                || url.endsWith("/null.png")
                || url.endsWith("/")) {
            return true;
        }

        // 2. Check TextureDownloader status for failed download flags
        String cacheKey = (item.isCrateItem() ? "crate_" : "misc_") + item.id();
        if (TextureDownloader.hasFailed(cacheKey)) {
            return true;
        }

        return false;
    }

    /**
     * Clears and constructs all interactive widgets, including the database resync button.
     */
    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        // Refresh / Rescan button
        Button refreshBtn = Button.builder(Component.literal("🔄 Refresh"), b -> {
                    b.active = false;
                    b.setMessage(Component.literal("Syncing..."));

                    // Clear failed download history so fixed images can be re-evaluated
                    TextureDownloader.clearFailedCache();

                    DatabaseManager.preloadDatabase().thenRun(() -> {
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                refreshMissingLists();
                                b.active = true;
                                b.setMessage(Component.literal("🔄 Refresh"));
                            });
                        }
                    });
                })
                .bounds(panelLeft + panelW - 100, panelTop + 28, 88, 18)
                .build();
        this.addRenderableWidget(refreshBtn);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int colW = (panelW - 24) / 2;
        int col1Left = panelLeft + 8;
        int col2Left = panelLeft + 16 + colW;
        int listTop = panelTop + 52 + 24;
        int listBottom = panelTop + panelH - 8;

        if (mouseY >= listTop && mouseY <= listBottom) {
            // Scroll Column 1 (Crate Icons)
            if (mouseX >= col1Left && mouseX <= col1Left + colW) {
                int maxScroll = Math.max(0, (missingCrateIcons.size() * 18) - (listBottom - listTop));
                crateScrollOffset = Mth.clamp(crateScrollOffset - (int) (verticalAmount * 18), 0, maxScroll);
                return true;
            }
            // Scroll Column 2 (Misc Icons)
            if (mouseX >= col2Left && mouseX <= col2Left + colW) {
                int maxScroll = Math.max(0, (missingMiscIcons.size() * 18) - (listBottom - listTop));
                miscScrollOffset = Mth.clamp(miscScrollOffset - (int) (verticalAmount * 18), 0, maxScroll);
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        double mouseX = event.x();
        double mouseY = event.y();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        // Admin Tab Bar Navigation
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && mouseY >= panelTop + 6 && mouseY <= panelTop + 22) {

            String[] adminTabs = {"Add Item", "Item List", "Manage Item Order", "Manage Crates", "Set Management", "Missing Images"};
            int tabX = panelLeft + 12;
            for (int i = 0; i < adminTabs.length; i++) {
                int tabW = this.font.width(adminTabs[i]);
                if (mouseX >= tabX && mouseX <= tabX + tabW) {
                    if (this.minecraft != null) {
                        if (i == 0) this.minecraft.setScreen(new AdminUploadScreen(this.parentScreen));
                        else if (i == 1) this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
                        else if (i == 2) this.minecraft.setScreen(new ManageItemOrderScreen(this.parentScreen));
                        else if (i == 3) this.minecraft.setScreen(new ManageCratesScreen(this.parentScreen));
                        else if (i == 4) this.minecraft.setScreen(new SetManagementScreen(this.parentScreen));
                    }
                    return true;
                }
                tabX += tabW + 14;
            }
        }

        // Click item row to inspect / upload image
        int colW = (panelW - 24) / 2;
        int col1Left = panelLeft + 8;
        int col2Left = panelLeft + 16 + colW;
        int listTop = panelTop + 52 + 24;
        int listBottom = panelTop + panelH - 8;

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && mouseY >= listTop && mouseY <= listBottom) {
            // Column 1: Crate items
            if (mouseX >= col1Left && mouseX <= col1Left + colW) {
                int index = (int) ((mouseY - listTop + crateScrollOffset) / 18);
                if (index >= 0 && index < missingCrateIcons.size()) {
                    ItemEntry clicked = missingCrateIcons.get(index);
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new AdminUploadScreen(clicked, this));
                    }
                    return true;
                }
            }
            // Column 2: Misc items
            if (mouseX >= col2Left && mouseX <= col2Left + colW) {
                int index = (int) ((mouseY - listTop + miscScrollOffset) / 18);
                if (index >= 0 && index < missingMiscIcons.size()) {
                    ItemEntry clicked = missingMiscIcons.get(index);
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new AdminUploadScreen(clicked, this));
                    }
                    return true;
                }
            }
        }

        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        // Container frame
        graphics.fill(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH, 0xFF14151B);
        graphics.outline(panelLeft, panelTop, panelW, panelH, 0xFF5C1656);

        // Header tabs
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelW - 1, panelTop + 24, 0xFF2A2D36);
        String[] adminTabs = {"Add Item", "Item List", "Manage Item Order", "Manage Crates", "Set Management", "Missing Images"};
        int tabX = panelLeft + 12;
        for (int i = 0; i < adminTabs.length; i++) {
            int col = (i == 5) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 5 ("Missing Images") cyan active highlight
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        int subHeaderW = panelW - 120;
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + 8 + subHeaderW, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, subHeaderW, 18, 0xFF5C1656);
        graphics.text(this.font, Component.literal("§6Items missing custom web textures are listed below. Click any row to upload."), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        // Column layout
        int colW = (panelW - 24) / 2;
        int col1Left = panelLeft + 8;
        int col2Left = panelLeft + 16 + colW;
        int colTop = panelTop + 52;
        int colH = panelH - 60;

        renderMissingColumn(graphics, "Missing Crate Icons", "§b", missingCrateIcons, col1Left, colTop, colW, colH, crateScrollOffset, mouseX, mouseY);
        renderMissingColumn(graphics, "Missing Misc Icons", "§d", missingMiscIcons, col2Left, colTop, colW, colH, miscScrollOffset, mouseX, mouseY);

        // Widgets (Refresh button)
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /**
     * Renders an individual column card containing a scrollable scissor-clipped list
     * of items missing image assets.
     */
    private void renderMissingColumn(GuiGraphicsExtractor graphics, String title, String accentColor, List<ItemEntry> items,
                                     int x, int y, int w, int h, int scrollOffset, int mouseX, int mouseY) {
        // Outer dark violet card box
        graphics.fill(x, y, x + w, y + h, 0xFF14071D);
        graphics.outline(x, y, w, h, 0xFF4A1A5E);

        // Column header banner with category count
        graphics.fill(x + 1, y + 1, x + w - 1, y + 23, 0xFF220D30);
        graphics.fill(x + 1, y + 23, x + w - 1, y + 24, 0xFF4A1A5E);

        Component headerComp = Component.literal(accentColor + "§l" + title + " §7(" + items.size() + ")");
        graphics.text(this.font, headerComp, x + 8, y + 7, 0xFFFFFFFF);

        int listTop = y + 26;
        int listBottom = y + h - 4;
        int listHeight = listBottom - listTop;

        if (items.isEmpty()) {
            graphics.text(this.font, Component.literal("§a✔ All icons exist! No missing images."), x + 12, listTop + 14, 0xFF55FF55);
            return;
        }

        graphics.enableScissor(x + 2, listTop, x + w - 2, listBottom);

        int rowH = 18;
        for (int i = 0; i < items.size(); i++) {
            ItemEntry item = items.get(i);
            int rowY = listTop - scrollOffset + (i * rowH);

            if (rowY + rowH >= listTop && rowY <= listBottom) {
                boolean hovered = mouseX >= x + 4 && mouseX <= x + w - 4 && mouseY >= rowY && mouseY < rowY + 16;
                if (hovered) {
                    graphics.fill(x + 4, rowY, x + w - 4, rowY + 16, 0x25FFFFFF);
                }

                // Row format: "▶ #123 - Item Name"
                Component rowComp = Component.literal("§8▶ " + accentColor + "#" + item.id() + " §7- §f" + (item.itemName() != null ? item.itemName() : "Unknown"));

                int maxTextWidth = w - 16;
                if (this.font.width(rowComp) > maxTextWidth) {
                    rowComp = Component.literal(this.font.substrByWidth(rowComp, maxTextWidth - 8).getString() + "..");
                }

                graphics.text(this.font, rowComp, x + 8, rowY + 4, 0xFFFFFFFF);
            }
        }

        graphics.disableScissor();

        // Styled Scrollbar
        int totalHeight = items.size() * rowH;
        int maxScroll = Math.max(0, totalHeight - listHeight);
        if (maxScroll > 0) {
            int barX = x + w - 5;
            int barHeight = Math.max(14, (int) ((float) listHeight / totalHeight * listHeight));
            int barY = listTop + (int) ((float) scrollOffset / maxScroll * (listHeight - barHeight));

            graphics.fill(barX, listTop, barX + 2, listBottom, 0xFF220D30);
            graphics.fill(barX, barY, barX + 2, barY + barHeight, 0xFF8E24AA);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
        }
    }
}