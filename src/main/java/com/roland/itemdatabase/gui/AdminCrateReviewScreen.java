package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.CrateContainerScanner;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.MiscGroupEntry;
import com.roland.itemdatabase.data.StagedCrateItem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AdminCrateReviewScreen extends Screen {

    public interface StagedUploadConsumer {
        void accept(boolean isCrate, int existingId, String newName, String newTag, String newType, List<StagedCrateItem> items);
    }

    private final List<StagedCrateItem> stagedItems;
    private final Screen parentScreen;
    private final StagedUploadConsumer onConfirmUpload;
    private final CrateContainerScanner.DetectedContainerMeta detectedMeta;

    // Mode state: Crate vs Misc
    private boolean isCrateMode = true;
    private boolean isCreatingNew = false;
    private boolean isDropdownOpen = false;

    // Available targets
    private List<CrateEntry> availableCrates = new ArrayList<>();
    private int selectedCrateIndex = 0;

    private List<MiscGroupEntry> availableGroups = new ArrayList<>();
    private int selectedGroupIndex = 0;

    // New Container Creation Fields
    private EditBox newNameBox;
    private EditBox newTagBox;
    private Button typeCycleButton;
    private static final String[] CRATE_TYPES = {"Seasonal", "Side", "Misc"};
    private static final String[] GROUP_TYPES = {"Furniture", "Voucher", "Event", "Unsorted"};
    private int typeIndex = 0;

    // Destination choice buttons
    private Button actionNewBtn;
    private Button actionSelectExistingBtn;
    private Button cyclePrevBtn;
    private Button cycleNextBtn;

    // Bottom action buttons
    private Button uploadButton;
    private Button toggleAllButton;

    // Scroller state
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private final int rowHeight = 24;

    // Dropdown overlay state
    private record DropdownRow(int id, String name, String subtitle) {}
    private final List<DropdownRow> dropdownRows = new ArrayList<>();
    private int dropdownX, dropdownY, dropdownW, dropdownH;
    private int dropdownScroll = 0;

    // Staged item clickable tracking
    private record RowClickable(int x, int y, int width, int height, StagedCrateItem item) {}
    private final List<RowClickable> rowClickables = new ArrayList<>();

    public AdminCrateReviewScreen(CrateEntry defaultCrate, List<StagedCrateItem> stagedItems, Screen parentScreen, StagedUploadConsumer onConfirmUpload) {
        this(defaultCrate, stagedItems, parentScreen, null, onConfirmUpload);
    }

    public AdminCrateReviewScreen(CrateEntry defaultCrate, List<StagedCrateItem> stagedItems, Screen parentScreen, CrateContainerScanner.DetectedContainerMeta detectedMeta, StagedUploadConsumer onConfirmUpload) {
        super(Component.literal("Review Scanned Items"));
        this.stagedItems = stagedItems != null ? stagedItems : new ArrayList<>();
        this.parentScreen = parentScreen;
        this.detectedMeta = detectedMeta;
        this.onConfirmUpload = onConfirmUpload;

        if (detectedMeta != null) {
            this.isCrateMode = detectedMeta.isCrate();
        }
    }

    @Override
    protected void init() {
        super.init();

        this.availableCrates = new ArrayList<>(DatabaseManager.CRATES.values());
        this.availableCrates.sort(Comparator.comparingInt(CrateEntry::id));

        this.availableGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
        this.availableGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));

        rebuildLayout();
    }

    private void rebuildLayout() {
        this.clearWidgets();
        this.isDropdownOpen = false;

        int panelW = Math.min(this.width - 20, 580);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int curY = panelTop + 36;
        int btnHalfW = (panelW - 28) / 2;

        // 1. Mode tabs: [ Crate Items | Misc Items ]
        this.addRenderableWidget(Button.builder(
                Component.literal(isCrateMode ? "§b§lCrate Items" : "§7Crate Items"),
                b -> {
                    if (!isCrateMode) {
                        isCrateMode = true;
                        typeIndex = 0;
                        rebuildLayout();
                    }
                }
        ).bounds(panelLeft + 12, curY, btnHalfW, 16).build());

        this.addRenderableWidget(Button.builder(
                Component.literal(!isCrateMode ? "§d§lMisc Items" : "§7Misc Items"),
                b -> {
                    if (isCrateMode) {
                        isCrateMode = false;
                        typeIndex = 0;
                        rebuildLayout();
                    }
                }
        ).bounds(panelLeft + 16 + btnHalfW, curY, btnHalfW, 16).build());

        curY += 20;

        // 2. Destination Choice: [ + Create New ] AND [ ◀ ] [ Existing: Name ▾ ] [ ▶ ]
        String newBtnText = isCreatingNew
                ? (isCrateMode ? "§a§l+ Create New Crate" : "§a§l+ Create New Group")
                : (isCrateMode ? "§a+ Create New Crate" : "§a+ Create New Group");

        this.actionNewBtn = Button.builder(Component.literal(newBtnText), btn -> {
            isCreatingNew = true;
            isDropdownOpen = false;
            rebuildLayout();
        }).bounds(panelLeft + 12, curY, btnHalfW, 16).build();
        this.addRenderableWidget(this.actionNewBtn);

        int rightSideX = panelLeft + 16 + btnHalfW;
        int navBtnW = 16;
        int centerChoiceW = btnHalfW - (navBtnW * 2) - 4;

        this.cyclePrevBtn = Button.builder(Component.literal("◀"), btn -> {
            isCreatingNew = false;
            if (isCrateMode && !availableCrates.isEmpty()) {
                selectedCrateIndex = (selectedCrateIndex - 1 + availableCrates.size()) % availableCrates.size();
            } else if (!isCrateMode && !availableGroups.isEmpty()) {
                selectedGroupIndex = (selectedGroupIndex - 1 + availableGroups.size()) % availableGroups.size();
            }
            rebuildLayout();
        }).bounds(rightSideX, curY, navBtnW, 16).build();
        this.addRenderableWidget(this.cyclePrevBtn);

        String existingText = getSelectedTargetLabel() + " §8▾";
        this.actionSelectExistingBtn = Button.builder(Component.literal(existingText), btn -> {
            isCreatingNew = false;
            isDropdownOpen = !isDropdownOpen;
            if (isDropdownOpen && newNameBox != null) {
                newNameBox.setFocused(false);
            }
        }).bounds(rightSideX + navBtnW + 2, curY, centerChoiceW, 16).build();
        this.addRenderableWidget(this.actionSelectExistingBtn);

        this.cycleNextBtn = Button.builder(Component.literal("▶"), btn -> {
            isCreatingNew = false;
            if (isCrateMode && !availableCrates.isEmpty()) {
                selectedCrateIndex = (selectedCrateIndex + 1) % availableCrates.size();
            } else if (!isCrateMode && !availableGroups.isEmpty()) {
                selectedGroupIndex = (selectedGroupIndex + 1) % availableGroups.size();
            }
            rebuildLayout();
        }).bounds(rightSideX + navBtnW + 2 + centerChoiceW + 2, curY, navBtnW, 16).build();
        this.addRenderableWidget(this.cycleNextBtn);

        curY += 20;

        // 3. New Container Form Row (Name, Tag, Type)
        if (isCreatingNew) {
            int inputW = (panelW - 36) / 3;

            this.newNameBox = new EditBox(this.font, panelLeft + 12, curY, inputW, 16, Component.literal("Name"));
            this.newNameBox.setHint(Component.literal("§8" + (isCrateMode ? "Crate Name..." : "Group Name...")));

            if (detectedMeta != null && detectedMeta.suggestedName() != null && !detectedMeta.suggestedName().isBlank()) {
                this.newNameBox.setValue(detectedMeta.suggestedName());
            }

            this.newTagBox = new EditBox(this.font, panelLeft + 16 + inputW, curY, inputW, 16, Component.literal("URL Tag"));
            this.newTagBox.setHint(Component.literal("§8URL Tag"));

            if (detectedMeta != null && detectedMeta.suggestedTag() != null && !detectedMeta.suggestedTag().isBlank()) {
                this.newTagBox.setValue(detectedMeta.suggestedTag());
            }

            this.newNameBox.setResponder(s -> {
                if (newTagBox != null) {
                    newTagBox.setValue(slugify(s));
                }
                updateUploadButtonState();
            });
            this.addRenderableWidget(this.newNameBox);

            this.newTagBox.setResponder(s -> updateUploadButtonState());
            this.addRenderableWidget(this.newTagBox);

            String[] currentTypes = isCrateMode ? CRATE_TYPES : GROUP_TYPES;
            typeIndex = Math.floorMod(typeIndex, currentTypes.length);

            this.typeCycleButton = Button.builder(Component.literal("Type: §e" + currentTypes[typeIndex]), b -> {
                typeIndex = (typeIndex + 1) % currentTypes.length;
                b.setMessage(Component.literal("Type: §e" + currentTypes[typeIndex]));
            }).bounds(panelLeft + 20 + (inputW * 2), curY, inputW, 16).build();
            this.addRenderableWidget(this.typeCycleButton);

            curY += 20;
        } else {
            this.newNameBox = null;
            this.newTagBox = null;
            this.typeCycleButton = null;
        }

        // 4. Bottom Action Bar
        int bottomY = panelTop + panelH - 28;

        this.toggleAllButton = Button.builder(Component.literal("Toggle All"), btn -> {
            boolean anySelected = stagedItems.stream().anyMatch(StagedCrateItem::isSelected);
            stagedItems.forEach(i -> i.setSelected(!anySelected));
            updateUploadButtonState();
        }).bounds(panelLeft + 12, bottomY, 85, 20).build();
        this.addRenderableWidget(this.toggleAllButton);

        this.uploadButton = Button.builder(Component.literal("Upload"), btn -> executeConfirm())
                .bounds(panelLeft + panelW / 2 - 60, bottomY, 120, 20).build();
        this.addRenderableWidget(this.uploadButton);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn -> onClose())
                .bounds(panelLeft + panelW - 82, bottomY, 70, 20).build());

        updateUploadButtonState();
    }

    private String getSelectedTargetLabel() {
        if (isCrateMode) {
            if (availableCrates.isEmpty()) return "§cNo Crates Available";
            if (selectedCrateIndex >= availableCrates.size()) selectedCrateIndex = 0;
            return "Add to Crate: " + availableCrates.get(selectedCrateIndex).name();
        } else {
            if (availableGroups.isEmpty()) return "§cNo Groups Available";
            if (selectedGroupIndex >= availableGroups.size()) selectedGroupIndex = 0;
            return "Add to Group: " + availableGroups.get(selectedGroupIndex).name();
        }
    }

    private static String slugify(String input) {
        String clean = input.replaceAll("(?i)[&§][0-9a-fk-or]", "");
        return clean.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private void executeConfirm() {
        List<StagedCrateItem> selected = stagedItems.stream().filter(StagedCrateItem::isSelected).toList();
        if (selected.isEmpty() || onConfirmUpload == null) return;

        int targetId = 0;
        String newName = "";
        String newTag = "";
        String newType = "";

        if (isCreatingNew) {
            newName = newNameBox != null ? newNameBox.getValue().trim() : "";
            newTag = newTagBox != null ? newTagBox.getValue().trim() : "";
            if (newName.isEmpty() || newTag.isEmpty()) return;

            String[] currentTypes = isCrateMode ? CRATE_TYPES : GROUP_TYPES;
            newType = currentTypes[Math.floorMod(typeIndex, currentTypes.length)];
        } else {
            if (isCrateMode && !availableCrates.isEmpty()) {
                targetId = availableCrates.get(selectedCrateIndex).id();
            } else if (!isCrateMode && !availableGroups.isEmpty()) {
                targetId = availableGroups.get(selectedGroupIndex).id();
            }
        }

        onConfirmUpload.accept(isCrateMode, targetId, newName, newTag, newType, selected);
    }

    public void updateUploadButtonState() {
        long count = stagedItems.stream().filter(StagedCrateItem::isSelected).count();
        boolean validName = !isCreatingNew || (newNameBox != null && !newNameBox.getValue().trim().isEmpty() && newTagBox != null && !newTagBox.getValue().trim().isEmpty());

        if (this.uploadButton != null) {
            this.uploadButton.active = count > 0 && validName;
            this.uploadButton.setMessage(Component.literal("Upload (" + count + "/" + stagedItems.size() + ")"));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isDropdownOpen && mouseX >= dropdownX && mouseX <= dropdownX + dropdownW
                && mouseY >= dropdownY && mouseY <= dropdownY + dropdownH) {
            int totalH = dropdownRows.size() * 18;
            dropdownScroll = Mth.clamp(dropdownScroll - (int) (verticalAmount * 18), 0, Math.max(0, totalH - dropdownH));
            return true;
        }

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

        if (isDropdownOpen) {
            if (mx >= dropdownX && mx <= dropdownX + dropdownW && my >= dropdownY && my <= dropdownY + dropdownH) {
                int relY = (int) my - dropdownY + dropdownScroll;
                int clickedIdx = relY / 18;
                if (clickedIdx >= 0 && clickedIdx < dropdownRows.size()) {
                    if (isCrateMode) {
                        selectedCrateIndex = clickedIdx;
                    } else {
                        selectedGroupIndex = clickedIdx;
                    }
                    isDropdownOpen = false;
                    actionSelectExistingBtn.setMessage(Component.literal(getSelectedTargetLabel() + " §8▾"));
                    return true;
                }
            } else {
                isDropdownOpen = false;
                return true;
            }
        }

        for (RowClickable r : rowClickables) {
            if (mx >= r.x() && mx <= r.x() + r.width() && my >= r.y() && my <= r.y() + r.height()) {
                r.item().toggleSelected();
                updateUploadButtonState();
                return true;
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isDropdownOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            isDropdownOpen = false;
            return true;
        }
        if (newNameBox != null && newNameBox.isFocused() && newNameBox.keyPressed(event)) return true;
        if (newTagBox != null && newTagBox.isFocused() && newTagBox.keyPressed(event)) return true;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);

        int panelW = Math.min(this.width - 20, 580);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        graphics.fill(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH, 0xFF14071D);
        graphics.outline(panelLeft, panelTop, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, Component.literal("§d§lReview Scanned Container Items"), panelLeft + panelW / 2, panelTop + 10, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("§7Choose target crate/group, review auto-parsed items, and batch upload."), panelLeft + panelW / 2, panelTop + 22, 0xFFAAAAAA);

        int viewTop = panelTop + (isCreatingNew ? 98 : 78);
        int viewBottom = panelTop + panelH - 36;
        int viewHeight = viewBottom - viewTop;
        int totalContentHeight = stagedItems.size() * rowHeight;
        maxScroll = Math.max(0, totalContentHeight - viewHeight);

        rowClickables.clear();
        graphics.enableScissor(panelLeft + 8, viewTop, panelLeft + panelW - 8, viewBottom);

        int drawY = viewTop - scrollOffset;

        for (int i = 0; i < stagedItems.size(); i++) {
            StagedCrateItem item = stagedItems.get(i);
            int itemTop = drawY;

            if (itemTop + rowHeight >= viewTop && itemTop <= viewBottom) {
                int rowLeft = panelLeft + 12;
                int rowWidth = panelW - 24;

                boolean hovered = mouseX >= rowLeft && mouseX <= rowLeft + rowWidth
                        && mouseY >= itemTop && mouseY < itemTop + rowHeight - 2;

                int bg = item.isSelected()
                        ? (hovered ? 0x44BA68C8 : 0x22692A82)
                        : (hovered ? 0x33444444 : 0x1A222222);
                graphics.fill(rowLeft, itemTop, rowLeft + rowWidth, itemTop + rowHeight - 2, bg);
                graphics.outline(rowLeft, itemTop, rowWidth, rowHeight - 2, item.isSelected() ? 0xFF5C1656 : 0xFF333333);

                int iconX = rowLeft + 4;
                int iconY = itemTop + 2;
                graphics.fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, 0xFF08040C);
                graphics.outline(iconX - 1, iconY - 1, 18, 18, 0xFF3D1E4E);
                if (item.getStack() != null && !item.getStack().isEmpty()) {
                    graphics.item(item.getStack(), iconX, iconY);
                }

                int textColor = item.isSelected() ? 0xFFFFFFFF : 0xFFAAAAAA;
                String countSuffix = item.getCount() > 1 ? " §8(x" + item.getCount() + ")" : "";
                String titleText = (item.getDisplayName() != null && !item.getDisplayName().isBlank())
                        ? item.getDisplayName()
                        : (item.getStack() != null ? item.getStack().getHoverName().getString() : "Unknown Item");

                graphics.text(this.font, titleText + countSuffix, rowLeft + 26, itemTop + 3, textColor, true);

                String matText = (item.getMaterialName() != null) ? "§8" + item.getMaterialName() : "";
                graphics.text(this.font, matText, rowLeft + 26, itemTop + 13, 0xFF888888, false);

                int badgeW = 68;
                int badgeX = rowLeft + rowWidth - badgeW - 4;
                int badgeY = itemTop + 2;
                int badgeBg = item.isSelected() ? 0xAA2E7D32 : 0xAA552222;
                int badgeBorder = item.isSelected() ? 0xFFA5D6A7 : 0xFFFF5555;
                graphics.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 16, badgeBg);
                graphics.outline(badgeX, badgeY, badgeW, 16, badgeBorder);

                String badgeText = item.isSelected() ? "§a✔ Include" : "§c✖ Skip";
                graphics.centeredText(this.font, Component.literal(badgeText), badgeX + (badgeW / 2), badgeY + 4, 0xFFFFFFFF);

                rowClickables.add(new RowClickable(rowLeft, itemTop, rowWidth, rowHeight - 2, item));
            }

            drawY += rowHeight;
        }

        graphics.disableScissor();

        if (maxScroll > 0) {
            int barX = panelLeft + panelW - 7;
            int barHeight = Math.max(14, (int) ((float) viewHeight / totalContentHeight * viewHeight));
            int barY = viewTop + (int) ((float) scrollOffset / maxScroll * (viewHeight - barHeight));

            graphics.fill(barX, viewTop, barX + 2, viewBottom, 0xFF220D30);
            graphics.fill(barX, barY, barX + 2, barY + barHeight, 0xFF8E24AA);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);

        // Render Dropdown overlay if active
        if (isDropdownOpen) {
            renderDropdownOverlay(graphics, mouseX, mouseY);
        }
    }

    private void renderDropdownOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (actionSelectExistingBtn == null) return;

        dropdownX = actionSelectExistingBtn.getX();
        dropdownY = actionSelectExistingBtn.getY() + actionSelectExistingBtn.getHeight() + 2;
        dropdownW = actionSelectExistingBtn.getWidth();

        dropdownRows.clear();
        if (isCrateMode) {
            for (CrateEntry c : availableCrates) {
                dropdownRows.add(new DropdownRow(c.id(), c.name(), " (" + c.items().size() + " items) - " + c.crateType()));
            }
        } else {
            for (MiscGroupEntry g : availableGroups) {
                dropdownRows.add(new DropdownRow(g.id(), g.name(), " (" + g.items().size() + " items) - " + g.groupType()));
            }
        }

        int maxVisible = 8;
        dropdownH = Math.min(dropdownRows.size(), maxVisible) * 18;
        if (dropdownH == 0) dropdownH = 18;

        graphics.fill(dropdownX, dropdownY, dropdownX + dropdownW, dropdownY + dropdownH, 0xFA14071D);
        graphics.outline(dropdownX, dropdownY, dropdownW, dropdownH, 0xFFBA68C8);

        graphics.enableScissor(dropdownX, dropdownY, dropdownX + dropdownW, dropdownY + dropdownH);

        for (int i = 0; i < dropdownRows.size(); i++) {
            DropdownRow row = dropdownRows.get(i);
            int itemY = dropdownY + (i * 18) - dropdownScroll;
            if (itemY + 18 < dropdownY || itemY > dropdownY + dropdownH) continue;

            boolean hovered = mouseX >= dropdownX && mouseX <= dropdownX + dropdownW
                    && mouseY >= itemY && mouseY < itemY + 18;

            int activeIdx = isCrateMode ? selectedCrateIndex : selectedGroupIndex;
            boolean isCurrent = (i == activeIdx);

            if (hovered || isCurrent) {
                graphics.fill(dropdownX + 1, itemY, dropdownX + dropdownW - 1, itemY + 18, isCurrent ? 0x55BA68C8 : 0x22FFFFFF);
            }

            String prefix = isCurrent ? "§d▶ " : "  §7";
            graphics.text(this.font, Component.literal(prefix + row.name() + "§8" + row.subtitle()), dropdownX + 4, itemY + 5, 0xFFFFFFFF);
        }

        graphics.disableScissor();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parentScreen);
        }
    }
}