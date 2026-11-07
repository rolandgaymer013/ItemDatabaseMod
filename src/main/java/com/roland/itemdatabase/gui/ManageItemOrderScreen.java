package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.MiscGroupEntry;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Administrative GUI screen allowing staff to inspect, reorder, and persist
 * the sequential display order of items within a crate or miscellaneous group.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Browsing containers via the dark violet and slate left sidebar drawer.</li>
 *   <li>Reordering via increment/decrement buttons, direct numerical position inputs, or Shift-jump to ends.</li>
 *   <li>Tracking unsaved changes via dirty state detection and confirmation banners.</li>
 *   <li>Committing new ordering sequences to backend REST endpoints and refreshing database caches.</li>
 * </ul>
 */
public class ManageItemOrderScreen extends Screen {

    /** Screen to return to upon closing or cancellation. */
    private final Screen parentScreen;

    /** Mode toggle: true = Crates mode, false = Misc Groups mode. */
    private boolean isCratesMode = true;

    /** Cached collection of all registered crates available for reordering. */
    private List<CrateEntry> crates = new ArrayList<>();

    /** Cached collection of all misc groups available for reordering. */
    private List<MiscGroupEntry> miscGroups = new ArrayList<>();

    /** Database ID of the crate currently selected in the sidebar. */
    private int selectedCrateId = 1;

    /** Database ID of the misc group currently selected in the sidebar. */
    private int selectedGroupId = -1;

    /** In-memory mutable list of items representing the active display order. */
    private final List<AdminApiClient.CrateItemRow> orderedItems = new ArrayList<>();

    /** Custom click target for sidebar drawer item entries. */
    private record ClickableTarget(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableTarget> sidebarClickables = new ArrayList<>();

    // --- Scrolling Offsets ---
    private int crateScrollOffset = 0;
    private int itemScrollOffset = 0;

    // --- State and Feedback ---
    private Button saveButton;
    private String statusMessage = "";
    private int statusColor = 0xFFFFAA00;
    private boolean isDirty = false;

    /**
     * Constructs the Manage Item Order administrative screen.
     *
     * @param parentScreen Parent screen to display upon escaping or canceling.
     */
    public ManageItemOrderScreen(Screen parentScreen) {
        super(Component.literal("Manage Item Order"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        // Sort crates chronologically with ID fallback
        this.crates = new ArrayList<>(DatabaseManager.CRATES.values());
        this.crates.sort((a, b) -> {
            LocalDate da = parseFlexibleDate(a.releaseDate());
            LocalDate db = parseFlexibleDate(b.releaseDate());
            int cmp = da.compareTo(db);
            return cmp != 0 ? cmp : Integer.compare(a.id(), b.id());
        });

        // Retain backend GroupOrder sequence for misc groups
        this.miscGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
        this.miscGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));

        if (!crates.isEmpty() && selectedCrateId == 1) {
            selectedCrateId = crates.get(0).id();
        }
        if (!miscGroups.isEmpty() && selectedGroupId == -1) {
            selectedGroupId = miscGroups.get(0).id();
        }

        rebuildLayout();
        if (isCratesMode) {
            loadItemsForCrate(selectedCrateId);
        } else {
            loadItemsForGroup(selectedGroupId);
        }
    }

    /**
     * Parses flexible date formats (MM/dd/yyyy or YYYY-MM-DD) into a comparable LocalDate.
     */
    private LocalDate parseFlexibleDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank() || dateStr.equalsIgnoreCase("N/A") || dateStr.equalsIgnoreCase("None")) {
            return LocalDate.of(2099, 1, 1);
        }
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
        return LocalDate.of(2099, 1, 1);
    }

    /**
     * Reconstructs all interactive screen widgets, mode selectors,
     * table reorder buttons, text boxes, and action buttons.
     */
    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int crateSidebarW = 185;

        // --- Mode Toggle: [ Crates | Misc ] ---
        int toggleW = (crateSidebarW - 4) / 2;
        int toggleY = panelTop + 52;

        Button cratesTabBtn = Button.builder(
                Component.literal(isCratesMode ? "§b§lCrates" : "§7Crates"),
                b -> {
                    if (!isCratesMode) {
                        isCratesMode = true;
                        itemScrollOffset = 0;
                        crateScrollOffset = 0;
                        isDirty = false;
                        rebuildLayout();
                        loadItemsForCrate(selectedCrateId);
                    }
                }
        ).bounds(panelLeft + 10, toggleY, toggleW, 16).build();
        this.addRenderableWidget(cratesTabBtn);

        Button miscTabBtn = Button.builder(
                Component.literal(!isCratesMode ? "§d§lMisc" : "§7Misc"),
                b -> {
                    if (isCratesMode) {
                        isCratesMode = false;
                        itemScrollOffset = 0;
                        crateScrollOffset = 0;
                        isDirty = false;
                        this.miscGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
                        this.miscGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));
                        if (selectedGroupId == -1 && !miscGroups.isEmpty()) {
                            selectedGroupId = miscGroups.get(0).id();
                        }
                        rebuildLayout();
                        loadItemsForGroup(selectedGroupId);
                    }
                }
        ).bounds(panelLeft + 10 + toggleW + 4, toggleY, toggleW, 16).build();
        this.addRenderableWidget(miscTabBtn);

        // --- Right Panel: Item Table Layout ---
        int tableLeft = panelLeft + crateSidebarW + 18;
        int tableW = panelW - crateSidebarW - 28;
        int itemStartY = panelTop + 72;
        int itemRowH = 19;
        int maxVisibleItems = (panelH - 110) / itemRowH;

        for (int i = 0; i < maxVisibleItems; i++) {
            final int index = itemScrollOffset + i;
            if (index >= orderedItems.size()) break;

            AdminApiClient.CrateItemRow item = orderedItems.get(index);
            int rowY = itemStartY + (i * itemRowH);

            // Direct numerical position input box
            EditBox orderBox = new EditBox(this.font, tableLeft + 6, rowY + 1, 26, 15, Component.literal(""));
            orderBox.setValue(String.valueOf(index + 1));
            orderBox.setResponder(val -> {
                if (!val.trim().isEmpty() && !val.trim().equals(String.valueOf(index + 1))) {
                    try {
                        int targetPos = Integer.parseInt(val.trim());
                        moveItemToPosition(index, targetPos - 1);
                    } catch (NumberFormatException ignored) {}
                }
            });
            this.addRenderableWidget(orderBox);

            // Move Up Button (▲) - Shift jumps directly to top (position 1)
            Button upBtn = Button.builder(Component.literal("▲"), b -> {
                boolean shift = hasShiftDown();
                moveItem(index, shift ? -index : -1);
            }).bounds(tableLeft + tableW - 46, rowY, 20, 16).build();
            upBtn.active = (index > 0);
            this.addRenderableWidget(upBtn);

            // Move Down Button (▼) - Shift jumps directly to bottom (last position)
            Button downBtn = Button.builder(Component.literal("▼"), b -> {
                boolean shift = hasShiftDown();
                moveItem(index, shift ? (orderedItems.size() - 1 - index) : 1);
            }).bounds(tableLeft + tableW - 24, rowY, 20, 16).build();
            downBtn.active = (index < orderedItems.size() - 1);
            this.addRenderableWidget(downBtn);
        }

        // --- Bottom Action Controls ---
        int actionY = panelTop + panelH - 24;

        saveButton = Button.builder(Component.literal(isDirty ? "§a§lSave Order!" : "Save Order"), b -> commitReorder())
                .bounds(tableLeft + tableW - 170, actionY, 90, 18)
                .build();
        saveButton.active = isDirty;
        this.addRenderableWidget(saveButton);

        Button cancelBtn = Button.builder(CommonComponents.GUI_CANCEL, b -> {
            this.isDirty = false;
            if (this.minecraft != null) {
                this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
            }
        }).bounds(tableLeft + tableW - 74, actionY, 70, 18).build();
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
     * Moves an item by a relative offset and flags the screen as dirty.
     */
    private void moveItem(int currentIndex, int delta) {
        int targetIndex = Mth.clamp(currentIndex + delta, 0, orderedItems.size() - 1);
        if (targetIndex != currentIndex) {
            AdminApiClient.CrateItemRow moving = orderedItems.remove(currentIndex);
            orderedItems.add(targetIndex, moving);
            isDirty = true;
            statusMessage = "Unsaved order changes.";
            statusColor = 0xFFFFFF55;
            rebuildLayout();
        }
    }

    /**
     * Moves an item directly to a designated 0-indexed position and flags the screen as dirty.
     */
    private void moveItemToPosition(int currentIndex, int targetIndex) {
        int clamped = Mth.clamp(targetIndex, 0, orderedItems.size() - 1);
        if (clamped != currentIndex) {
            AdminApiClient.CrateItemRow moving = orderedItems.remove(currentIndex);
            orderedItems.add(clamped, moving);
            isDirty = true;
            statusMessage = "Unsaved order changes.";
            statusColor = 0xFFFFFF55;
            rebuildLayout();
        }
    }

    /**
     * Queries the backend API asynchronously for items belonging to a crate ID and sorts by sortOrder.
     */
    private void loadItemsForCrate(int crateId) {
        statusMessage = "Loading crate items...";
        statusColor = 0xFFFFAA00;

        AdminApiClient.fetchCrateItems(crateId).thenAccept(loaded -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    this.orderedItems.clear();
                    this.orderedItems.addAll(loaded);
                    this.orderedItems.sort(Comparator.comparingInt(AdminApiClient.CrateItemRow::sortOrder));
                    this.statusMessage = "";
                    this.isDirty = false;
                    rebuildLayout();
                });
            }
        });
    }

    /**
     * Loads items for a miscellaneous group from cache, sorted by itemOrder and ID.
     */
    private void loadItemsForGroup(int groupId) {
        statusMessage = "Loading group items...";
        statusColor = 0xFFFFAA00;

        MiscGroupEntry group = DatabaseManager.MISC_GROUPS.get(groupId);
        List<ItemEntry> groupItems = new ArrayList<>();

        if (group != null && !group.items().isEmpty()) {
            groupItems.addAll(group.items());
        } else {
            for (ItemEntry it : DatabaseManager.MISC_ITEMS.values()) {
                String gId = it.groupId() != null ? it.groupId().trim() : "";
                if (gId.equals(String.valueOf(groupId))) {
                    groupItems.add(it);
                } else if (group != null && (gId.equalsIgnoreCase(group.name()) || gId.equalsIgnoreCase(group.urlTag()))) {
                    groupItems.add(it);
                }
            }
        }

        groupItems.sort(Comparator
                .comparingInt(ItemEntry::itemOrder)
                .thenComparingInt(it -> {
                    try {
                        return Integer.parseInt(it.id().trim());
                    } catch (Exception e) {
                        return 0;
                    }
                })
        );

        this.orderedItems.clear();
        for (ItemEntry it : groupItems) {
            int itemId;
            try {
                itemId = Integer.parseInt(it.id().trim());
            } catch (Exception e) {
                continue;
            }

            this.orderedItems.add(new AdminApiClient.CrateItemRow(
                    itemId,
                    it.itemName(),
                    it.rarityHuman(),
                    it.itemOrder(),
                    "0%",
                    "None", "None", "None", "None", "None", "None", "None",
                    it.notes()
            ));
        }

        this.statusMessage = "";
        this.isDirty = false;
        rebuildLayout();
    }

    /**
     * Submits the updated sequence of item IDs to the backend reorder endpoints.
     */
    private void commitReorder() {
        if (orderedItems.isEmpty()) return;

        statusMessage = "Saving order to backend...";
        statusColor = 0xFFFFFF55;
        saveButton.active = false;

        List<Integer> ids = orderedItems.stream().map(AdminApiClient.CrateItemRow::id).toList();

        var future = isCratesMode
                ? AdminApiClient.reorderCrateItems(selectedCrateId, ids)
                : AdminApiClient.reorderMiscGroupItems(selectedGroupId, ids);

        future.thenAccept(res -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (res.success()) {
                        statusMessage = "§aOrder saved successfully!";
                        statusColor = 0xFF55FF55;
                        isDirty = false;
                        if (isCratesMode) {
                            DatabaseManager.fetchCrateItems().thenRun(() -> {
                                DatabaseManager.mapItemsToCrates();
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> loadItemsForCrate(selectedCrateId));
                                }
                            });
                        } else {
                            DatabaseManager.fetchMiscItems().thenRun(() -> {
                                DatabaseManager.mapItemsToMiscGroups();
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> loadItemsForGroup(selectedGroupId));
                                }
                            });
                        }
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
        int panelW = Math.min(this.width - 20, 750);
        int panelLeft = (this.width - panelW) / 2;
        int crateSidebarW = 185;
        int maxListSize = isCratesMode ? crates.size() : miscGroups.size();

        // Partition scroll wheel events between sidebar drawer and table
        if (mouseX >= panelLeft && mouseX <= panelLeft + crateSidebarW + 15) {
            if (verticalAmount < 0 && crateScrollOffset + 1 < maxListSize) {
                crateScrollOffset++;
                rebuildLayout();
            } else if (verticalAmount > 0 && crateScrollOffset > 0) {
                crateScrollOffset--;
                rebuildLayout();
            }
            return true;
        } else {
            if (verticalAmount < 0 && itemScrollOffset + 1 < orderedItems.size()) {
                itemScrollOffset++;
                rebuildLayout();
            } else if (verticalAmount > 0 && itemScrollOffset > 0) {
                itemScrollOffset--;
                rebuildLayout();
            }
            return true;
        }
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

        // Custom sidebar drawer clickable rows
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (ClickableTarget target : sidebarClickables) {
                if (mouseX >= target.x() && mouseX <= target.x() + target.width()
                        && mouseY >= target.y() && mouseY <= target.y() + target.height()) {
                    target.action().run();
                    return true;
                }
            }
        }

        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        sidebarClickables.clear();

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
            int col = (i == 2) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 2 ("Manage Item Order") cyan active highlight
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + panelW - 8, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, panelW - 16, 18, 0xFF5C1656);
        String subheader = isCratesMode
                ? "§6Use ▲/▼ to shift, Shift+Click to jump to ends, or type position directly."
                : "§dUse ▲/▼ to shift, Shift+Click to jump to ends, or type position directly.";
        graphics.text(this.font, Component.literal(subheader), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        // Left sidebar container (dark violet palette)
        int crateSidebarW = 185;
        int sidebarStartX = panelLeft + 8;
        int sidebarStartY = panelTop + 50;
        int sidebarH = panelH - 58;

        graphics.fill(sidebarStartX, sidebarStartY, sidebarStartX + crateSidebarW + 4, sidebarStartY + sidebarH, 0xFF14071D);
        graphics.outline(sidebarStartX, sidebarStartY, crateSidebarW + 4, sidebarH, 0xFF4A1A5E);

        // Render colorful drawer list items
        int listStartY = sidebarStartY + 24;
        int rowH = 14;
        int viewBottom = sidebarStartY + sidebarH - 6;
        int maxVisible = (viewBottom - listStartY) / rowH;

        if (isCratesMode) {
            for (int i = 0; i < maxVisible; i++) {
                int index = crateScrollOffset + i;
                if (index >= crates.size()) break;

                CrateEntry crate = crates.get(index);
                boolean isSel = (crate.id() == selectedCrateId);
                int ry = listStartY + (i * rowH);

                boolean hovered = mouseX >= sidebarStartX + 4 && mouseX <= sidebarStartX + crateSidebarW
                        && mouseY >= ry - 1 && mouseY < ry + 12;

                if (isSel) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + crateSidebarW, ry + 12, 0x55BA68C8);
                    graphics.outline(sidebarStartX + 4, ry - 1, crateSidebarW - 4, 13, 0xFFBA68C8);
                } else if (hovered) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + crateSidebarW, ry + 12, 0x25FFFFFF);
                }

                MutableComponent headerComp = Component.empty();
                headerComp.append(crate.getFormattedHeader(isSel));
                headerComp.append(Component.literal(" §8(" + crate.items().size() + ")"));

                graphics.enableScissor(sidebarStartX + 6, ry, sidebarStartX + crateSidebarW - 4, ry + 13);
                graphics.text(this.font, headerComp, sidebarStartX + 6, ry + 2, 0xFFFFFFFF);
                graphics.disableScissor();

                sidebarClickables.add(new ClickableTarget(sidebarStartX + 4, ry - 1, crateSidebarW - 4, 13, () -> {
                    if (selectedCrateId != crate.id()) {
                        selectedCrateId = crate.id();
                        itemScrollOffset = 0;
                        isDirty = false;
                        rebuildLayout();
                        loadItemsForCrate(selectedCrateId);
                    }
                }));
            }
        } else {
            for (int i = 0; i < maxVisible; i++) {
                int index = crateScrollOffset + i;
                if (index >= miscGroups.size()) break;

                MiscGroupEntry group = miscGroups.get(index);
                boolean isSel = (group.id() == selectedGroupId);
                int ry = listStartY + (i * rowH);

                boolean hovered = mouseX >= sidebarStartX + 4 && mouseX <= sidebarStartX + crateSidebarW
                        && mouseY >= ry - 1 && mouseY < ry + 12;

                if (isSel) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + crateSidebarW, ry + 12, 0x55BA68C8);
                    graphics.outline(sidebarStartX + 4, ry - 1, crateSidebarW - 4, 13, 0xFFBA68C8);
                } else if (hovered) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + crateSidebarW, ry + 12, 0x25FFFFFF);
                }

                MutableComponent headerComp = Component.literal(isSel ? "§d▶ " : "§8▶ ");
                headerComp.append(group.getFormattedHeader());
                headerComp.append(Component.literal(" §8(" + group.items().size() + ")"));

                graphics.enableScissor(sidebarStartX + 6, ry, sidebarStartX + crateSidebarW - 4, ry + 13);
                graphics.text(this.font, headerComp, sidebarStartX + 6, ry + 2, 0xFFFFFFFF);
                graphics.disableScissor();

                sidebarClickables.add(new ClickableTarget(sidebarStartX + 4, ry - 1, crateSidebarW - 4, 13, () -> {
                    if (selectedGroupId != group.id()) {
                        selectedGroupId = group.id();
                        itemScrollOffset = 0;
                        isDirty = false;
                        rebuildLayout();
                        loadItemsForGroup(selectedGroupId);
                    }
                }));
            }
        }

        int tableLeft = panelLeft + crateSidebarW + 18;
        int tableW = panelW - crateSidebarW - 26;
        int tableH = panelH - 58;
        graphics.fill(tableLeft, panelTop + 50, tableLeft + tableW, panelTop + panelH - 8, 0xFF0D0E12);
        graphics.outline(tableLeft, panelTop + 50, tableW, tableH, 0xFF353945);

        int posColX = tableLeft + 8;
        int iconColX = tableLeft + 36;
        int idColX = tableLeft + 60;
        int nameColX = tableLeft + 102;
        int moveColX = tableLeft + tableW - 46;

        graphics.fill(tableLeft + 1, panelTop + 51, tableLeft + tableW - 1, panelTop + 68, 0xFF22242C);
        graphics.text(this.font, Component.literal("§7Pos"), posColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Icon"), iconColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7ID"), idColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Item Name"), nameColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Move"), moveColX, panelTop + 56, 0xFFAAAAAA);

        int itemStartY = panelTop + 72;
        int itemRowH = 19;
        int maxVisibleItems = (panelH - 110) / itemRowH;
        int maxNameW = moveColX - nameColX - 10;

        for (int i = 0; i < maxVisibleItems; i++) {
            int index = itemScrollOffset + i;
            if (index >= orderedItems.size()) break;

            AdminApiClient.CrateItemRow item = orderedItems.get(index);
            int rowY = itemStartY + (i * itemRowH);
            int textY = rowY + 5;

            ItemEntry cached = isCratesMode
                    ? DatabaseManager.CRATE_ITEMS.get(String.valueOf(item.id()))
                    : DatabaseManager.MISC_ITEMS.get(String.valueOf(item.id()));

            // 1. Icon thumbnail (16x16)
            int iconX = iconColX;
            int iconY = rowY + 1;
            if (cached != null && cached.iconUrl() != null && !cached.iconUrl().isBlank()) {
                String cacheKey = (cached.isCrateItem() ? "crate_" : "misc_") + cached.id();
                Identifier textureId = TextureDownloader.getOrLoadTexture(cached.iconUrl(), cacheKey);
                if (textureId != null) {
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            textureId,
                            iconX,
                            iconY,
                            0.0F,
                            0.0F,
                            16,
                            16,
                            16,
                            16
                    );
                } else {
                    graphics.text(this.font, Component.literal("§8▪"), iconX + 4, textY, 0xFF666666);
                }
            } else {
                graphics.text(this.font, Component.literal("§6⚠"), iconX + 3, textY, 0xFFFFAA00);
            }

            // 2. Database Item ID
            graphics.text(this.font, Component.literal("§8#" + item.id()), idColX, textY, 0xFF888888);

            // 3. Styled Item Name
            Component nameComp = null;
            if (cached != null && cached.rawData() != null && !cached.rawData().isBlank()) {
                try {
                    com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(cached.rawData()).getAsJsonObject();
                    com.google.gson.JsonObject compObj = root.has("components") ? root.getAsJsonObject("components") : root;
                    if (compObj.has("minecraft:custom_name")) {
                        com.google.gson.JsonElement nameEl = compObj.get("minecraft:custom_name");
                        if (nameEl.isJsonPrimitive() && nameEl.getAsJsonPrimitive().isString()) {
                            try {
                                nameEl = com.google.gson.JsonParser.parseString(nameEl.getAsString());
                            } catch (Exception ignored) {}
                        }
                        nameComp = net.minecraft.network.chat.ComponentSerialization.CODEC
                                .parse(com.mojang.serialization.JsonOps.INSTANCE, nameEl)
                                .result()
                                .orElse(null);
                    }
                } catch (Throwable ignored) {}
            }

            if (nameComp == null) {
                if (cached != null) {
                    nameComp = cached.getStyledComponent();
                } else {
                    nameComp = Component.literal(item.name() != null ? item.name().replace('&', '§') : "Unknown");
                }
            }

            graphics.enableScissor(nameColX, rowY, nameColX + maxNameW, rowY + itemRowH);
            graphics.text(this.font, nameComp, nameColX, textY, 0xFFFFFFFF);
            graphics.disableScissor();
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