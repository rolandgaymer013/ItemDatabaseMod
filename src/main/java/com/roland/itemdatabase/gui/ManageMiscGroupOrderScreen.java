package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.MiscGroupEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Administrative GUI screen allowing staff to inspect and adjust the display order
 * of Crates or Miscellaneous Groups within their selected sub-category.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Unified reordering interface for both Crates and Misc Groups via {@code ContainerRow}.</li>
 *   <li>Sub-category filtering support ("Seasonal", "Side", "Unsorted", "Furniture", etc.).</li>
 *   <li>Shift-to-bounds row shifting (▲/▼) and direct numerical position inputs.</li>
 *   <li>Intelligent partial merge when reordering filtered subsets to preserve overall container order.</li>
 *   <li>Persistence to backend REST endpoints with immediate database cache synchronization.</li>
 * </ul>
 */
public class ManageMiscGroupOrderScreen extends Screen {

    /** Screen to return to upon canceling or closing. */
    private final Screen parentScreen;

    /** Mode toggle: true = Crates mode, false = Misc Groups mode. */
    private boolean isCratesMode;

    /** Active sub-category filter scoping the reorder list (e.g. "All", "Seasonal", "Unsorted"). */
    private String activeSubFilter;

    /**
     * Generic container row transfer model enabling unified reordering logic
     * across both CrateEntry and MiscGroupEntry datasets.
     *
     * @param id        Primary numeric identifier.
     * @param name      Display title.
     * @param date      Formatted release date string.
     * @param itemCount Total number of child items assigned to this container.
     * @param type      Container classification type.
     */
    private record ContainerRow(int id, String name, String date, int itemCount, String type) {}

    /** Active mutable list of containers currently displayed in the table. */
    private final List<ContainerRow> orderedRows = new ArrayList<>();

    /** Current vertical scroll offset for table rows. */
    private int scrollOffset = 0;

    /** Save action button. */
    private Button saveButton;

    /** Ephemeral informational or error banner text rendered at the bottom. */
    private String statusMessage = "";

    /** Status text color. */
    private int statusColor = 0xFFFFAA00;

    /** Flag indicating whether unsaved order changes exist in memory. */
    private boolean isDirty = false;

    /**
     * Constructs the Manage Misc Group / Crate Order screen with an explicit sub-filter.
     *
     * @param parentScreen    Parent screen to return to.
     * @param isCratesMode    True for crates, false for misc groups.
     * @param activeSubFilter Initial sub-filter category string.
     */
    public ManageMiscGroupOrderScreen(Screen parentScreen, boolean isCratesMode, String activeSubFilter) {
        super(Component.literal(isCratesMode ? "Manage Crate Order" : "Manage Group Order"));
        this.parentScreen = parentScreen;
        this.isCratesMode = isCratesMode;
        this.activeSubFilter = (activeSubFilter == null || activeSubFilter.isBlank()) ? "All" : activeSubFilter;
    }

    /**
     * Constructs the screen defaulting to "All" sub-categories.
     */
    public ManageMiscGroupOrderScreen(Screen parentScreen, boolean isCratesMode) {
        this(parentScreen, isCratesMode, "All");
    }

    /**
     * Constructs the screen defaulting to Misc Groups mode and "All" sub-categories.
     */
    public ManageMiscGroupOrderScreen(Screen parentScreen) {
        this(parentScreen, false, "All");
    }

    @Override
    protected void init() {
        loadContainers();
        rebuildLayout();
    }

    /**
     * Parses non-standard or user-entered date strings into a comparable LocalDate.
     */
    private LocalDate parseFlexibleDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return LocalDate.of(2000, 1, 1);
        try {
            String[] parts = dateStr.replace('-', '/').split("/");
            if (parts.length == 3) {
                int m = Integer.parseInt(parts[0].trim());
                int d = Integer.parseInt(parts[1].trim());
                int y = Integer.parseInt(parts[2].trim());
                if (y < 100) y += 2000;
                return LocalDate.of(y, m, d);
            }
        } catch (Exception ignored) {}
        return LocalDate.of(2000, 1, 1);
    }

    /**
     * Populates the orderedRows list based on active mode and sub-category filter.
     */
    private void loadContainers() {
        this.orderedRows.clear();
        if (isCratesMode) {
            List<CrateEntry> list = new ArrayList<>(DatabaseManager.CRATES.values());
            list.sort((a, b) -> parseFlexibleDate(a.releaseDate()).compareTo(parseFlexibleDate(b.releaseDate())));

            for (CrateEntry c : list) {
                if (activeSubFilter.equalsIgnoreCase("All")
                        || (c.crateType() != null && c.crateType().equalsIgnoreCase(activeSubFilter))) {
                    orderedRows.add(new ContainerRow(c.id(), c.name(), c.releaseDate(), c.items().size(), c.crateType()));
                }
            }
        } else {
            List<MiscGroupEntry> list = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
            list.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));

            for (MiscGroupEntry g : list) {
                boolean matches = false;
                if (activeSubFilter.equalsIgnoreCase("All")) {
                    matches = true;
                } else if (activeSubFilter.equalsIgnoreCase("Unsorted")) {
                    matches = (g.groupType() == null || g.groupType().isBlank()
                            || g.groupType().equalsIgnoreCase("Unsorted")
                            || g.groupType().equalsIgnoreCase("None")
                            || g.groupType().equalsIgnoreCase("null"));
                } else {
                    matches = (g.groupType() != null && g.groupType().equalsIgnoreCase(activeSubFilter));
                }

                if (matches) {
                    orderedRows.add(new ContainerRow(g.id(), g.name(), g.releaseDate(), g.items().size(), g.groupType()));
                }
            }
        }
    }

    /**
     * Clears and constructs all form widgets, mode toggles, position edit boxes,
     * up/down buttons, and submission actions.
     */
    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int tableLeft = panelLeft + 16;
        int tableW = panelW - 32;

        // --- Mode Toggle: [ Crates | Misc Groups ] ---
        int toggleW = 110;
        int toggleY = panelTop + 51;

        Button cratesTabBtn = Button.builder(
                Component.literal(isCratesMode ? "§b§lCrates" : "§7Crates"),
                b -> {
                    if (!isCratesMode) {
                        isCratesMode = true;
                        activeSubFilter = "All";
                        scrollOffset = 0;
                        isDirty = false;
                        statusMessage = "";
                        loadContainers();
                        rebuildLayout();
                    }
                }
        ).bounds(tableLeft, toggleY, toggleW, 16).build();
        this.addRenderableWidget(cratesTabBtn);

        Button miscTabBtn = Button.builder(
                Component.literal(!isCratesMode ? "§d§lMisc Groups" : "§7Misc Groups"),
                b -> {
                    if (isCratesMode) {
                        isCratesMode = false;
                        activeSubFilter = "All";
                        scrollOffset = 0;
                        isDirty = false;
                        statusMessage = "";
                        loadContainers();
                        rebuildLayout();
                    }
                }
        ).bounds(tableLeft + toggleW + 4, toggleY, toggleW, 16).build();
        this.addRenderableWidget(miscTabBtn);

        // --- Reorder Table Rows ---
        int rowStartY = panelTop + 90;
        int rowH = 20;
        int maxVisible = (panelH - 128) / rowH;

        for (int i = 0; i < maxVisible; i++) {
            final int index = scrollOffset + i;
            if (index >= orderedRows.size()) break;

            int rowY = rowStartY + (i * rowH);

            // Direct numerical position input box
            EditBox orderBox = new EditBox(this.font, tableLeft + 8, rowY + 2, 28, 15, Component.literal(""));
            orderBox.setValue(String.valueOf(index + 1));
            orderBox.setResponder(val -> {
                if (!val.trim().isEmpty() && !val.trim().equals(String.valueOf(index + 1))) {
                    try {
                        int targetPos = Integer.parseInt(val.trim());
                        moveRowToPosition(index, targetPos - 1);
                    } catch (NumberFormatException ignored) {}
                }
            });
            this.addRenderableWidget(orderBox);

            // Move Up Button (▲) - Shift jumps directly to top
            Button upBtn = Button.builder(
                    Component.literal("▲"),
                    b -> {
                        boolean shift = hasShiftDown();
                        moveRow(index, shift ? -index : -1);
                    }
            ).bounds(tableLeft + tableW - 54, rowY + 1, 22, 16).build();
            upBtn.active = (index > 0);
            this.addRenderableWidget(upBtn);

            // Move Down Button (▼) - Shift jumps directly to bottom
            Button downBtn = Button.builder(
                    Component.literal("▼"),
                    b -> {
                        boolean shift = hasShiftDown();
                        moveRow(index, shift ? (orderedRows.size() - 1 - index) : 1);
                    }
            ).bounds(tableLeft + tableW - 28, rowY + 1, 22, 16).build();
            downBtn.active = (index < orderedRows.size() - 1);
            this.addRenderableWidget(downBtn);
        }

        // --- Bottom Controls ---
        int actionY = panelTop + panelH - 24;

        saveButton = Button.builder(
                Component.literal(isDirty ? "§a§lSave Order!" : "Save Order"),
                b -> commitReorder()
        ).bounds(tableLeft + tableW - 170, actionY, 90, 18).build();
        saveButton.active = isDirty;
        this.addRenderableWidget(saveButton);

        Button cancelBtn = Button.builder(
                CommonComponents.GUI_CANCEL,
                b -> {
                    this.isDirty = false;
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new ManageCratesScreen(this.parentScreen));
                    }
                }
        ).bounds(tableLeft + tableW - 74, actionY, 70, 18).build();
        this.addRenderableWidget(cancelBtn);
    }

    /**
     * Checks if either Left Shift or Right Shift is currently pressed.
     */
    private boolean hasShiftDown() {
        if (this.minecraft == null) return false;
        long window = this.minecraft.getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    /**
     * Moves a container row by a relative delta and flags the view as dirty.
     */
    private void moveRow(int currentIndex, int delta) {
        int targetIndex = Mth.clamp(currentIndex + delta, 0, orderedRows.size() - 1);
        if (targetIndex != currentIndex) {
            ContainerRow moving = orderedRows.remove(currentIndex);
            orderedRows.add(targetIndex, moving);
            isDirty = true;
            statusMessage = "Unsaved order changes.";
            statusColor = 0xFFFFFF55;
            rebuildLayout();
        }
    }

    /**
     * Moves a container row directly to a target 0-indexed position and flags as dirty.
     */
    private void moveRowToPosition(int currentIndex, int targetIndex) {
        int clamped = Mth.clamp(targetIndex, 0, orderedRows.size() - 1);
        if (clamped != currentIndex) {
            ContainerRow moving = orderedRows.remove(currentIndex);
            orderedRows.add(clamped, moving);
            isDirty = true;
            statusMessage = "Unsaved order changes.";
            statusColor = 0xFFFFFF55;
            rebuildLayout();
        }
    }

    /**
     * Assembles the complete ordered list of container IDs and persists them to the backend.
     * When scoped by a sub-filter, merges reordered items in place with un-filtered containers.
     */
    private void commitReorder() {
        if (orderedRows.isEmpty()) return;

        statusMessage = "Saving order to backend...";
        statusColor = 0xFFFFFF55;
        saveButton.active = false;

        // Merge reordered subset back into the full container sequence
        List<Integer> finalIds = new ArrayList<>();
        if (isCratesMode) {
            List<Integer> activeIds = orderedRows.stream().map(ContainerRow::id).toList();
            finalIds.addAll(activeIds);
            for (CrateEntry c : DatabaseManager.CRATES.values()) {
                if (!activeIds.contains(c.id())) {
                    finalIds.add(c.id());
                }
            }
        } else {
            List<MiscGroupEntry> allGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
            allGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));

            List<Integer> reorderedSubIds = orderedRows.stream().map(ContainerRow::id).toList();
            int subIdx = 0;

            for (MiscGroupEntry g : allGroups) {
                boolean matchesFilter = false;
                if (activeSubFilter.equalsIgnoreCase("All")) {
                    matchesFilter = true;
                } else if (activeSubFilter.equalsIgnoreCase("Unsorted")) {
                    matchesFilter = (g.groupType() == null || g.groupType().isBlank()
                            || g.groupType().equalsIgnoreCase("Unsorted")
                            || g.groupType().equalsIgnoreCase("None")
                            || g.groupType().equalsIgnoreCase("null"));
                } else {
                    matchesFilter = (g.groupType() != null && g.groupType().equalsIgnoreCase(activeSubFilter));
                }

                if (matchesFilter && subIdx < reorderedSubIds.size()) {
                    finalIds.add(reorderedSubIds.get(subIdx++));
                } else {
                    finalIds.add(g.id());
                }
            }
        }

        var future = isCratesMode
                ? AdminApiClient.reorderCrates(finalIds)
                : AdminApiClient.reorderMiscGroups(finalIds);

        future.thenAccept(res -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (res.success()) {
                        statusMessage = "§aOrder saved successfully!";
                        statusColor = 0xFF55FF55;
                        isDirty = false;
                        if (isCratesMode) {
                            DatabaseManager.fetchCrates().thenRun(DatabaseManager::mapItemsToCrates);
                        } else {
                            DatabaseManager.fetchMiscGroups().thenRun(DatabaseManager::mapItemsToMiscGroups);
                        }
                        rebuildLayout();
                    } else {
                        statusMessage = "§cError: " + res.error();
                        statusColor = 0xFFFF5555;
                        saveButton.active = true;
                    }
                });
            }
        });
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount < 0 && scrollOffset + 1 < orderedRows.size()) {
            scrollOffset++;
            rebuildLayout();
            return true;
        } else if (verticalAmount > 0 && scrollOffset > 0) {
            scrollOffset--;
            rebuildLayout();
            return true;
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
                        if (i == 0) {
                            this.minecraft.setScreen(new AdminUploadScreen(this.parentScreen));
                            return true;
                        } else if (i == 1) {
                            this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
                            return true;
                        } else if (i == 2) {
                            this.minecraft.setScreen(new ManageItemOrderScreen(this.parentScreen));
                            return true;
                        } else if (i == 3) {
                            this.minecraft.setScreen(new ManageCratesScreen(this.parentScreen));
                            return true;
                        } else if (i == 4) {
                            this.minecraft.setScreen(new SetManagementScreen(this.parentScreen));
                            return true;
                        } else if (i == 5) {
                            this.minecraft.setScreen(new MissingImagesScreen(this.parentScreen));
                            return true;
                        }
                    }
                    break;
                }
                tabX += tabW + 14;
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

        // Header tabs bar
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelW - 1, panelTop + 24, 0xFF2A2D36);
        String[] adminTabs = {"Add Item", "Item List", "Manage Item Order", "Manage Crates", "Set Management", "Missing Images"};
        int tabX = panelLeft + 12;
        for (int i = 0; i < adminTabs.length; i++) {
            int col = (i == 3) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 3 ("Manage Crates") cyan active highlight
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + panelW - 8, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, panelW - 16, 18, 0xFF5C1656);
        String filterTag = activeSubFilter.equalsIgnoreCase("All") ? "" : " §6[" + activeSubFilter + "]§7";
        String headerLabel = isCratesMode
                ? "§bManage Crate Display Order" + filterTag + " §7(▲/▼ to shift, Shift+Click for ends)"
                : "§dManage Misc Group Display Order" + filterTag + " §7(▲/▼ to shift, Shift+Click for ends)";
        graphics.text(this.font, Component.literal(headerLabel), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        // Table container
        int tableLeft = panelLeft + 16;
        int tableW = panelW - 32;
        int tableH = panelH - 76;
        graphics.fill(tableLeft, panelTop + 69, tableLeft + tableW, panelTop + panelH - 8, 0xFF0D0E12);
        graphics.outline(tableLeft, panelTop + 69, tableW, tableH, 0xFF353945);

        // Column offsets
        int posColX = tableLeft + 10;
        int idColX = tableLeft + 48;
        int nameColX = tableLeft + 90;
        int dateColX = tableLeft + 330;
        int countColX = tableLeft + 450;
        int moveColX = tableLeft + tableW - 54;

        // Table column headers
        graphics.fill(tableLeft + 1, panelTop + 70, tableLeft + tableW - 1, panelTop + 87, 0xFF22242C);
        graphics.text(this.font, Component.literal("§7Pos"), posColX, panelTop + 75, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7ID"), idColX, panelTop + 75, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal(isCratesMode ? "§7Crate Name" : "§7Group Name"), nameColX, panelTop + 75, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Release Date"), dateColX, panelTop + 75, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Items"), countColX, panelTop + 75, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Move"), moveColX, panelTop + 75, 0xFFAAAAAA);

        int rowStartY = panelTop + 90;
        int rowH = 20;
        int maxVisible = (panelH - 128) / rowH;

        for (int i = 0; i < maxVisible; i++) {
            int index = scrollOffset + i;
            if (index >= orderedRows.size()) break;

            ContainerRow row = orderedRows.get(index);
            int rowY = rowStartY + (i * rowH);
            int textY = rowY + 6;

            // ID
            graphics.text(this.font, Component.literal("§8#" + row.id()), idColX, textY, 0xFF888888);

            // Container Name
            graphics.enableScissor(nameColX, rowY, dateColX - 10, rowY + rowH);
            graphics.text(this.font, Component.literal("§f" + row.name()), nameColX, textY, 0xFFFFFFFF);
            graphics.disableScissor();

            // Release Date
            String dateStr = (row.date() != null && !row.date().isBlank()) ? row.date() : "None";
            graphics.text(this.font, Component.literal("§e" + dateStr), dateColX, textY, 0xFFFFAA00);

            // Items count
            graphics.text(this.font, Component.literal("§7" + row.itemCount() + " items"), countColX, textY, 0xFFAAAAAA);
        }

        if (!statusMessage.isEmpty()) {
            graphics.text(this.font, Component.literal(statusMessage), tableLeft + 10, panelTop + panelH - 20, statusColor);
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