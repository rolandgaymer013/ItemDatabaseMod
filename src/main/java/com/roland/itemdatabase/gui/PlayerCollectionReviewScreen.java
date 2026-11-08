package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.CollectionContainerScanner.ScannedCollectionItem;
import com.roland.itemdatabase.data.CollectionManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public class PlayerCollectionReviewScreen extends Screen {

    private final Screen parentScreen;
    private final List<ScannedCollectionItem> items;
    private final List<RowClickable> rowClickables = new ArrayList<>();

    private int scrollOffset = 0;
    private int maxScroll = 0;
    private final int rowHeight = 24;

    private Button addSelectedBtn;

    private record RowClickable(int x, int y, int width, int height, ScannedCollectionItem item) {}

    public PlayerCollectionReviewScreen(Screen parentScreen, List<ScannedCollectionItem> items) {
        super(Component.literal("Review Collection Items"));
        this.parentScreen = parentScreen;
        this.items = items != null ? items : new ArrayList<>();
    }

    @Override
    protected void init() {
        super.init();
        rebuildLayout();
    }

    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 520);
        int panelH = Math.min(this.height - 20, 360);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;
        int bottomY = panelTop + panelH - 28;

        // Top quick-selection toggles
        this.addRenderableWidget(Button.builder(Component.literal("Select New"), btn -> {
            for (var item : items) {
                if (!item.alreadyOwned && !item.isUnrecognized()) {
                    item.selected = true;
                }
            }
            updateButtonState();
        }).bounds(panelLeft + 12, panelTop + 36, 85, 16).build());

        this.addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> {
            for (var item : items) {
                item.selected = false;
            }
            updateButtonState();
        }).bounds(panelLeft + 101, panelTop + 36, 55, 16).build());

        // Bottom Action Bar
        this.addSelectedBtn = Button.builder(Component.literal("Add to Collection"), btn -> commitSelected())
                .bounds(panelLeft + panelW / 2 - 80, bottomY, 160, 20).build();
        this.addRenderableWidget(this.addSelectedBtn);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn -> onClose())
                .bounds(panelLeft + panelW - 82, bottomY, 70, 20).build());

        updateButtonState();
    }

    private void updateButtonState() {
        long selectedCount = items.stream().filter(i -> i.selected && !i.alreadyOwned && !i.isUnrecognized()).count();
        if (this.addSelectedBtn != null) {
            this.addSelectedBtn.active = selectedCount > 0;
            this.addSelectedBtn.setMessage(Component.literal("§aAdd Selected (" + selectedCount + ")"));
        }
    }

    private void commitSelected() {
        int added = 0;
        for (var item : items) {
            if (item.selected && item.matchedEntry != null && !item.alreadyOwned) {
                String key = CollectionManager.getStorageKey(item.matchedEntry);
                CollectionManager.setOwned(key, true);
                added++;
            }
        }

        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.literal("§a[ItemDatabase] Added §e" + added + " §anew items to your collection!"));
        }
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll > 0) {
            scrollOffset = Mth.clamp(scrollOffset - (int) (verticalAmount * rowHeight), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        double mx = event.x();
        double my = event.y();

        for (RowClickable r : rowClickables) {
            if (mx >= r.x() && mx <= r.x() + r.width() && my >= r.y() && my <= r.y() + r.height()) {
                if (!r.item().alreadyOwned && !r.item().isUnrecognized()) {
                    r.item().selected = !r.item().selected;
                    updateButtonState();
                    return true;
                }
            }
        }

        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);

        int panelW = Math.min(this.width - 20, 520);
        int panelH = Math.min(this.height - 20, 360);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        // Background Frame
        graphics.fill(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH, 0xFF14071D);
        graphics.outline(panelLeft, panelTop, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, Component.literal("§6§lReview Collection Items"), panelLeft + panelW / 2, panelTop + 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("§7Select which scanned items to record in your personal checklist."), panelLeft + panelW / 2, panelTop + 22, 0xFFAAAAAA);

        int viewTop = panelTop + 56;
        int viewBottom = panelTop + panelH - 36;
        int viewHeight = viewBottom - viewTop;
        int totalContentHeight = items.size() * rowHeight;
        maxScroll = Math.max(0, totalContentHeight - viewHeight);

        rowClickables.clear();
        graphics.enableScissor(panelLeft + 8, viewTop, panelLeft + panelW - 8, viewBottom);

        int drawY = viewTop - scrollOffset;

        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            int itemTop = drawY;

            if (itemTop + rowHeight >= viewTop && itemTop <= viewBottom) {
                int rowLeft = panelLeft + 12;
                int rowWidth = panelW - 24;

                boolean hovered = mouseX >= rowLeft && mouseX <= rowLeft + rowWidth
                        && mouseY >= itemTop && mouseY < itemTop + rowHeight - 2;

                int bg;
                int border;
                if (item.isUnrecognized()) {
                    bg = hovered ? 0x22333333 : 0x11222222;
                    border = 0xFF2B2B2B;
                } else if (item.alreadyOwned) {
                    bg = hovered ? 0x331B5E20 : 0x1A1B5E20;
                    border = 0xFF2E7D32;
                } else if (item.selected) {
                    bg = hovered ? 0x44BA68C8 : 0x22692A82;
                    border = 0xFF5C1656;
                } else {
                    bg = hovered ? 0x33444444 : 0x1A222222;
                    border = 0xFF333333;
                }

                graphics.fill(rowLeft, itemTop, rowLeft + rowWidth, itemTop + rowHeight - 2, bg);
                graphics.outline(rowLeft, itemTop, rowWidth, rowHeight - 2, border);

                // Slot Icon
                int iconX = rowLeft + 4;
                int iconY = itemTop + 3;
                graphics.fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, 0xFF08040C);
                graphics.outline(iconX - 1, iconY - 1, 18, 18, 0xFF3D1E4E);
                if (item.stack != null && !item.stack.isEmpty()) {
                    graphics.item(item.stack, iconX, iconY);
                }

                // Title & Subtitle
                int textColor = item.alreadyOwned ? 0xFFA5D6A7 : (item.selected ? 0xFFFFFFFF : 0xFFAAAAAA);
                String titleText = (item.matchedEntry != null) ? item.matchedEntry.itemName() : item.displayName;
                graphics.text(this.font, titleText, rowLeft + 26, itemTop + 4, textColor, true);

                String subText;
                if (item.isUnrecognized()) {
                    subText = "§8Not found in server catalog";
                } else if (item.alreadyOwned) {
                    subText = "§aAlready in your collection";
                } else if (item.matchedEntry != null) {
                    subText = item.matchedEntry.isCrateItem()
                            ? "§7Crate: " + item.matchedEntry.crateId()
                            : "§7Misc Group Item";
                } else {
                    subText = "";
                }
                graphics.text(this.font, subText, rowLeft + 26, itemTop + 13, 0xFF888888, false);

                // Right Status Badge
                int badgeW = 74;
                int badgeX = rowLeft + rowWidth - badgeW - 4;
                int badgeY = itemTop + 3;

                String badgeText;
                int badgeBg;
                int badgeBorder;

                if (item.isUnrecognized()) {
                    badgeText = "§8Ignored";
                    badgeBg = 0xAA222222;
                    badgeBorder = 0xFF444444;
                } else if (item.alreadyOwned) {
                    badgeText = "§a✔ Owned";
                    badgeBg = 0xAA1B5E20;
                    badgeBorder = 0xFF4CAF50;
                } else if (item.selected) {
                    badgeText = "§e✔ Add";
                    badgeBg = 0xAA827717;
                    badgeBorder = 0xFFFFEB3B;
                } else {
                    badgeText = "§7✖ Skip";
                    badgeBg = 0xAA37474F;
                    badgeBorder = 0xFF78909C;
                }

                graphics.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 16, badgeBg);
                graphics.outline(badgeX, badgeY, badgeW, 16, badgeBorder);
                graphics.centeredText(this.font, Component.literal(badgeText), badgeX + (badgeW / 2), badgeY + 4, 0xFFFFFFFF);

                rowClickables.add(new RowClickable(rowLeft, itemTop, rowWidth, rowHeight - 2, item));
            }

            drawY += rowHeight;
        }

        graphics.disableScissor();

        // Scrollbar
        if (maxScroll > 0) {
            int barX = panelLeft + panelW - 7;
            int barHeight = Math.max(14, (int) ((float) viewHeight / totalContentHeight * viewHeight));
            int barY = viewTop + (int) ((float) scrollOffset / maxScroll * (viewHeight - barHeight));

            graphics.fill(barX, viewTop, barX + 2, viewBottom, 0xFF220D30);
            graphics.fill(barX, barY, barX + 2, barY + barHeight, 0xFF8E24AA);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parentScreen);
        }
    }
}