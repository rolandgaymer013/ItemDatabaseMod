package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.CollectionManager;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.MiscGroupEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Global Collection Progress Dashboard showing deduplicated item completion,
 * completed armor sets, wishlist counts, and per-crate / per-group progress bars.
 */
public class CollectionDashboardScreen extends Screen {

    /** Category filter tabs displayed across the top of the progress list. */
    public enum DashboardTab {
        ALL_CRATES("All Crates"),
        SEASONAL("Seasonal"),
        SIDE("Side"),
        MISC_CRATES("Misc Crates"),
        MISC_GROUPS("Misc Groups");

        private final String label;
        DashboardTab(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Represents a single crate or misc-group completion row in the scrollable dashboard list. */
    private record ProgressRow(
            String title,
            String subtitle,
            int owned,
            int total,
            ItemEntry firstItem,
            ItemEntry firstMissingItem
    ) {
        int percent() {
            return total > 0 ? Math.round((owned * 100.0f) / total) : 0;
        }
    }

    /** Bounds and click handler for custom-rendered interactive regions (tabs and progress rows). */
    private record ClickTarget(int x, int y, int w, int h, Runnable action) {}

    private final Screen parent;
    private final Consumer<ItemEntry> onJumpToItem;

    private DashboardTab currentTab = DashboardTab.ALL_CRATES;
    private final List<ProgressRow> rows = new ArrayList<>();
    private final List<ClickTarget> clickTargets = new ArrayList<>();

    private int scrollOffset = 0;
    private int maxScroll = 0;

    // Top summary metrics (cached on init / tab refresh)
    private int uniqueOwnedCount = 0;
    private int uniqueTotalCount = 0;
    private int completedSetsCount = 0;
    private int totalSetsCount = 0;
    private int uniqueWishlistCount = 0;

    /**
     * Constructs the Collection Progress Dashboard.
     *
     * @param parent       Parent screen to return to when closed.
     * @param onJumpToItem Callback invoked when the user clicks a crate/group row or wishlist shortcut.
     */
    public CollectionDashboardScreen(Screen parent, Consumer<ItemEntry> onJumpToItem) {
        super(Component.literal("Collection Progress Dashboard"));
        this.parent = parent;
        this.onJumpToItem = onJumpToItem;
    }

    /** Computes the horizontal origin of the dashboard panel, clamped within screen bounds. */
    private int getPanelX(int panelW) {
        int base = (this.width - panelW) / 2 + ModConfig.get().cardOffsetX;
        return Mth.clamp(base, 8, Math.max(8, this.width - panelW - 8));
    }

    /** Computes the vertical origin of the dashboard panel, clamped within screen bounds. */
    private int getPanelY(int panelH) {
        int base = (this.height - panelH) / 2 - 10 + ModConfig.get().cardOffsetY;
        return Mth.clamp(base, 8, Math.max(8, this.height - panelH - 34));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        computeGlobalStats();
        rebuildRows();

        int panelW = 450;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        int btnY = panelY + panelH + 6;
        int gap = 6;
        int wSets = 118;
        int wWish = 108;
        int wSync = 86;
        int wBack = 54;
        int totalW = wSets + wWish + wSync + wBack + (gap * 3);
        int startX = panelX + (panelW - totalW) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("§6✦ Armor Sets (" + completedSetsCount + "/" + totalSetsCount + ")"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new SetCatalogScreen(this));
            }
        }).bounds(startX, btnY, wSets, 20).build());

        Button wishBtn = Button.builder(Component.literal("§e★ Jump to Wishlist"), b -> {
            ItemEntry firstWish = findFirstWishlistedItem();
            if (firstWish != null && this.onJumpToItem != null) {
                this.onJumpToItem.accept(firstWish);
                this.onClose();
            }
        }).bounds(startX + wSets + gap, btnY, wWish, 20).build();
        wishBtn.active = (this.uniqueWishlistCount > 0);
        this.addRenderableWidget(wishBtn);

        this.addRenderableWidget(Button.builder(Component.literal("§b⇄ Web Sync"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new WebSyncScreen(this));
            }
        }).bounds(startX + wSets + wWish + (gap * 2), btnY, wSync, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(startX + wSets + wWish + wSync + (gap * 3), btnY, wBack, 20)
                .build());
    }

    /**
     * Computes deduplicated unique collectible totals, completed armor set counts,
     * and active wishlist totals across all cached database items.
     */
    private void computeGlobalStats() {
        Set<String> allUniqueNames = new HashSet<>();
        Set<String> ownedUniqueNames = new HashSet<>();
        Set<String> wishUniqueNames = new HashSet<>();

        for (ItemEntry it : DatabaseManager.getAllItems()) {
            String key = it.getCleanName().trim().toLowerCase();
            if (key.isEmpty()) key = (it.isCrateItem() ? "crate_" : "misc_") + it.id();

            allUniqueNames.add(key);
            if (CollectionManager.isOwned(it)) {
                ownedUniqueNames.add(key);
            }
            if (CollectionManager.isWishlisted(it)) {
                wishUniqueNames.add(key);
            }
        }

        this.uniqueTotalCount = allUniqueNames.size();
        this.uniqueOwnedCount = ownedUniqueNames.size();
        this.uniqueWishlistCount = wishUniqueNames.size();

        this.totalSetsCount = DatabaseManager.ARMOR_SETS.size();
        int fullSets = 0;
        for (AdminApiClient.SetRow set : DatabaseManager.ARMOR_SETS) {
            if (set.itemIds().isEmpty()) continue;
            int ownedPieces = 0;
            for (int sid : set.itemIds()) {
                ItemEntry piece = DatabaseManager.findItemById(sid);
                if (piece != null && CollectionManager.isOwned(piece)) {
                    ownedPieces++;
                }
            }
            if (ownedPieces >= set.itemIds().size()) {
                fullSets++;
            }
        }
        this.completedSetsCount = fullSets;
    }

    /** Returns the first wishlisted item found in the database, or {@code null} if none exist. */
    private ItemEntry findFirstWishlistedItem() {
        for (ItemEntry it : DatabaseManager.getAllItems()) {
            if (CollectionManager.isWishlisted(it)) {
                return it;
            }
        }
        return null;
    }

    /** Rebuilds the scrollable {@link ProgressRow} list for the currently selected {@link DashboardTab}. */
    private void rebuildRows() {
        this.rows.clear();
        this.scrollOffset = 0;

        if (this.currentTab == DashboardTab.MISC_GROUPS) {
            List<MiscGroupEntry> groups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
            groups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder).thenComparing(MiscGroupEntry::name));

            for (MiscGroupEntry g : groups) {
                if (g.items().isEmpty()) continue;
                int owned = 0;
                ItemEntry first = g.items().get(0);
                ItemEntry firstMissing = null;
                for (ItemEntry it : g.items()) {
                    if (CollectionManager.isOwned(it)) {
                        owned++;
                    } else if (firstMissing == null) {
                        firstMissing = it;
                    }
                }
                String sub = (g.groupType() == null || g.groupType().isBlank()) ? "Misc" : g.groupType();
                this.rows.add(new ProgressRow(g.name(), sub, owned, g.items().size(), first, firstMissing));
            }
        } else {
            List<CrateEntry> crates = new ArrayList<>(DatabaseManager.CRATES.values());
            crates.sort(Comparator.comparingInt(CrateEntry::id));

            for (CrateEntry c : crates) {
                if (c.items().isEmpty()) continue;
                if (this.currentTab == DashboardTab.SEASONAL && !c.crateType().equalsIgnoreCase("Seasonal")) continue;
                if (this.currentTab == DashboardTab.SIDE && !c.crateType().equalsIgnoreCase("Side")) continue;
                if (this.currentTab == DashboardTab.MISC_CRATES && !c.crateType().equalsIgnoreCase("Misc")) continue;

                int owned = 0;
                ItemEntry first = c.items().get(0);
                ItemEntry firstMissing = null;
                for (ItemEntry it : c.items()) {
                    if (CollectionManager.isOwned(it)) {
                        owned++;
                    } else if (firstMissing == null) {
                        firstMissing = it;
                    }
                }
                this.rows.add(new ProgressRow(c.name(), c.crateType(), owned, c.items().size(), first, firstMissing));
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.maxScroll > 0) {
            this.scrollOffset = Mth.clamp(this.scrollOffset - (int) (verticalAmount * 18), 0, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (ClickTarget target : this.clickTargets) {
                if (event.x() >= target.x() && event.x() <= target.x() + target.w()
                        && event.y() >= target.y() && event.y() <= target.y() + target.h()) {
                    target.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.clickTargets.clear();

        int panelW = 450;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, Component.literal("§6§lCollection Progress & Crate Completion"), panelX + (panelW / 2), panelY + 9, 0xFFFFFFFF);

        // ============================================================
        // 1. TOP SUMMARY CARDS (Unique Items | Full Sets | Wishlist)
        // ============================================================
        int cardGap = 6;
        int cardW = (panelW - 16 - (cardGap * 2)) / 3;
        int cardH = 32;
        int cardY = panelY + 23;
        int c1X = panelX + 8;
        int c2X = c1X + cardW + cardGap;
        int c3X = c2X + cardW + cardGap;

        int overallPct = uniqueTotalCount > 0 ? Math.round((uniqueOwnedCount * 100.0f) / uniqueTotalCount) : 0;
        drawStatCard(graphics, c1X, cardY, cardW, cardH, "§bUnique Collectibles",
                "§a" + uniqueOwnedCount + " §7/ §f" + uniqueTotalCount + " §e(" + overallPct + "%)", 0xFF3B82F6);

        int setsPct = totalSetsCount > 0 ? Math.round((completedSetsCount * 100.0f) / totalSetsCount) : 0;
        drawStatCard(graphics, c2X, cardY, cardW, cardH, "§6Completed Armor Sets",
                "§a" + completedSetsCount + " §7/ §f" + totalSetsCount + " §e(" + setsPct + "%)", 0xFFFFAA00);

        drawStatCard(graphics, c3X, cardY, cardW, cardH, "§dActive Wishlist",
                "§6★ " + uniqueWishlistCount + " §7tracked items", 0xFFBA68C8);

        // ============================================================
        // 2. CATEGORY TABS
        // ============================================================
        int tabY = cardY + cardH + 6;
        DashboardTab[] tabs = DashboardTab.values();
        int tabW = (panelW - 16) / tabs.length;

        for (int i = 0; i < tabs.length; i++) {
            DashboardTab t = tabs[i];
            int tx = panelX + 8 + (i * tabW);
            boolean active = (this.currentTab == t);

            graphics.fill(tx, tabY, tx + tabW - 2, tabY + 14, active ? 0xFF692A82 : 0xFF1E0B2A);
            graphics.outline(tx, tabY, tabW - 2, 14, active ? 0xFFBA68C8 : 0xFF3D1650);
            graphics.centeredText(this.font, Component.literal((active ? "§f§l" : "§7") + t.label()), tx + (tabW / 2), tabY + 3, 0xFFFFFFFF);

            this.clickTargets.add(new ClickTarget(tx, tabY, tabW, 14, () -> {
                this.currentTab = t;
                rebuildRows();
            }));
        }

        // ============================================================
        // 3. SCROLLABLE CRATE / GROUP PROGRESS LIST
        // ============================================================
        int listX = panelX + 8;
        int listY = tabY + 18;
        int listW = panelW - 16;
        int listBottom = panelY + panelH - 8;
        int listH = listBottom - listY;

        graphics.fill(listX, listY, listX + listW, listBottom, 0xFF0D0414);
        graphics.outline(listX, listY, listW, listH, 0xFF351242);

        int rowH = 22;
        int totalH = this.rows.size() * rowH;
        this.maxScroll = Math.max(0, totalH - (listH - 4));
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0, this.maxScroll);

        graphics.enableScissor(listX + 2, listY + 2, listX + listW - 2, listBottom - 2);
        int ry = listY + 3 - this.scrollOffset;

        for (ProgressRow row : this.rows) {
            if (ry + rowH >= listY && ry <= listBottom) {
                boolean hovered = mouseX >= listX + 4 && mouseX <= listX + listW - 8
                        && mouseY >= Math.max(listY, ry) && mouseY <= Math.min(listBottom, ry + rowH - 2);

                graphics.fill(listX + 4, ry, listX + listW - 8, ry + rowH - 2, hovered ? 0x33692A82 : 0xFF160820);
                graphics.outline(listX + 4, ry, listW - 12, rowH - 2, hovered ? 0xFFBA68C8 : 0xFF2B1038);

                int pct = row.percent();
                boolean complete = row.total() > 0 && row.owned() >= row.total();

                // Left Title & Completion Checkmark
                String titleStr = (complete ? "§a✔ " : "§f") + row.title();
                int maxTitleW = 195;
                if (this.font.width(titleStr) > maxTitleW) {
                    titleStr = this.font.substrByWidth(Component.literal(titleStr), maxTitleW - 6).getString() + "..";
                }
                graphics.text(this.font, Component.literal(titleStr), listX + 8, ry + 6, 0xFFFFFFFF);

                // Progress Bar on Right
                int barW = 140;
                int barH = 10;
                int barX = listX + listW - barW - 76;
                int barY = ry + 5;

                graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF0A0310);
                graphics.outline(barX, barY, barW, barH, 0xFF3D1650);

                int fillW = row.total() > 0 ? Math.round(((barW - 2) * row.owned()) / (float) row.total()) : 0;
                if (fillW > 0) {
                    int fillColor = complete ? 0xCC2E7D32 : 0xCC8E24AA;
                    graphics.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + barH - 1, fillColor);
                }

                String countLabel = (complete ? "§a" : (row.owned() > 0 ? "§e" : "§7"))
                        + row.owned() + "/" + row.total() + " §8(" + pct + "%)";
                graphics.text(this.font, Component.literal(countLabel), barX + barW + 6, ry + 6, 0xFFFFFFFF);

                int hitTop = Math.max(listY, ry);
                int hitH = Math.min(listBottom, ry + rowH - 2) - hitTop;
                this.clickTargets.add(new ClickTarget(listX + 4, hitTop, listW - 12, hitH, () -> {
                    ItemEntry target = row.firstMissingItem() != null ? row.firstMissingItem() : row.firstItem();
                    if (target != null && this.onJumpToItem != null) {
                        this.onJumpToItem.accept(target);
                        this.onClose();
                    }
                }));
            }
            ry += rowH;
        }
        graphics.disableScissor();

        if (this.maxScroll > 0) {
            int barX = listX + listW - 5;
            int viewH = listH - 4;
            int thumbH = Math.max(14, (viewH * viewH) / totalH);
            int thumbY = listY + 2 + ((viewH - thumbH) * this.scrollOffset) / this.maxScroll;
            graphics.fill(barX, listY + 2, barX + 2, listBottom - 2, 0xFF220D30);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFFFAA00);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /** Renders an individual top-level metric card with a colored accent border. */
    private void drawStatCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String label, String value, int accent) {
        graphics.fill(x, y, x + w, y + h, 0xFF0D0414);
        graphics.outline(x, y, w, h, accent);
        graphics.centeredText(this.font, Component.literal(label), x + (w / 2), y + 5, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal(value), x + (w / 2), y + 17, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}