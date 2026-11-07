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
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Administrative GUI screen providing crate and miscellaneous group container management:
 * creation, metadata updates, deletion with cascading safeguards, and reorder dispatching.
 * Styled with BlossomCraft dark purple and slate themes.
 */
public class ManageCratesScreen extends Screen {

    private final Screen parentScreen;

    // Toggle Mode: true = Crates, false = Misc Groups
    private boolean isCratesMode = true;

    private List<CrateEntry> crates = new ArrayList<>();
    private List<MiscGroupEntry> miscGroups = new ArrayList<>();

    private int selectedCrateId = -1; // -1 denotes "New" creation mode
    private int selectedGroupId = -1;
    private int scrollOffset = 0;

    // Sub-category filters
    private static final String[] CRATE_SUB_FILTERS = {"All", "Seasonal", "Side", "Misc"};
    private static final String[] MISC_SUB_FILTERS = {"All", "Unsorted", "Voucher", "Event", "Furniture"};
    private String crateSubFilter = "All";
    private String miscSubFilter = "All";

    // Form Edit Boxes
    private EditBox nameBox;
    private EditBox releaseDateBox;
    private EditBox tagBox;

    // Type Options
    private Button typeButton;
    private static final String[] CRATE_TYPES = {"Seasonal", "Side", "Misc"};
    private static final String[] GROUP_TYPES = {"Furniture", "Voucher", "Event", "Unsorted"};
    private int typeIndex = 0;

    // Form Action Buttons
    private Button submitButton;
    private Button deleteButton;
    private boolean confirmDelete = false;

    // Clickable target tracking for custom drawer items
    private record ClickableTarget(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableTarget> sidebarClickables = new ArrayList<>();

    // Status Banner
    private String statusMessage = "";
    private int statusColor = 0xFFFFAA00;

    /**
     * Constructs the Manage Crates administrative screen.
     *
     * @param parentScreen Parent screen to display upon escaping or canceling.
     */
    public ManageCratesScreen(Screen parentScreen) {
        super(Component.literal("Manage Crates"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        refreshDataLists();

        if (isCratesMode) {
            if (selectedCrateId == -1 && !crates.isEmpty()) {
                selectedCrateId = crates.get(0).id();
            }
        } else {
            if (selectedGroupId == -1 && !miscGroups.isEmpty()) {
                selectedGroupId = miscGroups.get(0).id();
            }
        }

        rebuildLayout();
    }

    /**
     * Refreshes local cached container lists from DatabaseManager.
     * Crates are sorted chronologically with ID fallback, while Misc Groups
     * retain their explicit groupOrder sequence.
     */
    private void refreshDataLists() {
        this.crates = new ArrayList<>(DatabaseManager.CRATES.values());
        this.crates.sort((a, b) -> {
            LocalDate da = parseFlexibleDate(a.releaseDate());
            LocalDate db = parseFlexibleDate(b.releaseDate());
            int cmp = da.compareTo(db);
            return cmp != 0 ? cmp : Integer.compare(a.id(), b.id());
        });

        // Retains the exact backend display order without overriding it by ID or Date
        this.miscGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
        this.miscGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));
    }

    /**
     * Parses non-standard or user-entered date strings into a comparable LocalDate.
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
     * Reconstructs all interactive UI components, sub-filter pill tabs,
     * text input boxes, and action buttons.
     */
    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int sidebarW = 230;
        int sidebarStartY = panelTop + 52;

        // --- Mode Toggle: [ Crates | Misc ] ---
        int toggleW = (sidebarW - 4) / 2;
        Button cratesTabBtn = Button.builder(
                Component.literal(isCratesMode ? "§b§lCrates" : "§7Crates"),
                b -> {
                    if (!isCratesMode) {
                        isCratesMode = true;
                        scrollOffset = 0;
                        confirmDelete = false;
                        if (selectedCrateId == -1 && !crates.isEmpty()) {
                            selectedCrateId = crates.get(0).id();
                        }
                        rebuildLayout();
                    }
                }
        ).bounds(panelLeft + 10, sidebarStartY, toggleW, 16).build();
        this.addRenderableWidget(cratesTabBtn);

        Button miscTabBtn = Button.builder(
                Component.literal(!isCratesMode ? "§d§lMisc" : "§7Misc"),
                b -> {
                    if (isCratesMode) {
                        isCratesMode = false;
                        scrollOffset = 0;
                        confirmDelete = false;
                        if (selectedGroupId == -1 && !miscGroups.isEmpty()) {
                            selectedGroupId = miscGroups.get(0).id();
                        }
                        rebuildLayout();
                    }
                }
        ).bounds(panelLeft + 10 + toggleW + 4, sidebarStartY, toggleW, 16).build();
        this.addRenderableWidget(miscTabBtn);

        // --- Sub-Filter Pills Row ---
        int subFilterY = sidebarStartY + 19;
        String[] activeFilters = isCratesMode ? CRATE_SUB_FILTERS : MISC_SUB_FILTERS;
        int filterBtnW = (sidebarW - ((activeFilters.length - 1) * 3)) / activeFilters.length;

        for (int i = 0; i < activeFilters.length; i++) {
            String filter = activeFilters[i];
            boolean isSelected = isCratesMode
                    ? crateSubFilter.equalsIgnoreCase(filter)
                    : miscSubFilter.equalsIgnoreCase(filter);

            int fx = panelLeft + 10 + (i * (filterBtnW + 3));
            int actualW = (i == activeFilters.length - 1) ? (panelLeft + 10 + sidebarW - fx) : filterBtnW;

            String displayFilter = filter;
            if (!isCratesMode && filter.equalsIgnoreCase("Furniture")) displayFilter = "Furn";

            String label = (isSelected ? (isCratesMode ? "§b§l" : "§d§l") : "§7") + displayFilter;
            Button subBtn = Button.builder(Component.literal(label), b -> {
                if (isCratesMode) {
                    crateSubFilter = filter;
                } else {
                    miscSubFilter = filter;
                }
                scrollOffset = 0;
                rebuildLayout();
            }).bounds(fx, subFilterY, actualW, 14).build();
            this.addRenderableWidget(subBtn);
        }

        // --- Sidebar Actions: "+ New ..." & "⇅ Order" ---
        int actionBtnY = subFilterY + 17;
        int actionBtnW = (sidebarW - 4) / 2;

        boolean isNewSelected = isCratesMode ? (selectedCrateId == -1) : (selectedGroupId == -1);
        String newBtnLabel = isCratesMode
                ? (isNewSelected ? "§a§l+ New Crate" : "§a+ New Crate")
                : (isNewSelected ? "§a§l+ New Group" : "§a+ New Group");

        Button newContainerBtn = Button.builder(Component.literal(newBtnLabel), b -> {
            if (isCratesMode) {
                selectedCrateId = -1;
            } else {
                selectedGroupId = -1;
            }
            confirmDelete = false;
            rebuildLayout();
        }).bounds(panelLeft + 10, actionBtnY, actionBtnW, 16).build();
        this.addRenderableWidget(newContainerBtn);

        String reorderBtnLabel = isCratesMode ? "§b⇅ Order" : "§d⇅ Order";
        Button reorderBtn = Button.builder(Component.literal(reorderBtnLabel), b -> {
            if (this.minecraft != null) {
                String activeFilter = isCratesMode ? crateSubFilter : miscSubFilter;
                this.minecraft.setScreen(new ManageMiscGroupOrderScreen(this, isCratesMode, activeFilter));
            }
        }).bounds(panelLeft + 10 + actionBtnW + 4, actionBtnY, actionBtnW, 16).build();
        this.addRenderableWidget(reorderBtn);

        // --- Right Form Panel ---
        int formLeft = panelLeft + sidebarW + 20;
        int formW = panelW - sidebarW - 30;
        int labelOffset = 85;
        int inputW = Math.min(formW - labelOffset, 280);
        int formStartY = panelTop + 60;
        int formRowH = 24;
        int curY = formStartY;

        // Container Name Box
        nameBox = new EditBox(this.font, formLeft + labelOffset, curY, inputW, 16, Component.literal(""));
        this.addRenderableWidget(nameBox);
        curY += formRowH;

        // Release Date Box
        releaseDateBox = new EditBox(this.font, formLeft + labelOffset, curY, inputW, 16, Component.literal(""));
        this.addRenderableWidget(releaseDateBox);
        curY += formRowH;

        // URL Tag Box
        tagBox = new EditBox(this.font, formLeft + labelOffset, curY, inputW, 16, Component.literal(""));
        this.addRenderableWidget(tagBox);
        curY += formRowH;

        // Type Cycle Button
        String[] currentTypes = isCratesMode ? CRATE_TYPES : GROUP_TYPES;
        typeIndex = Math.floorMod(typeIndex, currentTypes.length);

        typeButton = Button.builder(Component.literal(currentTypes[typeIndex]), b -> {
            typeIndex = (typeIndex + 1) % currentTypes.length;
            typeButton.setMessage(Component.literal(currentTypes[typeIndex]));
        }).bounds(formLeft + labelOffset, curY, inputW, 16).build();
        this.addRenderableWidget(typeButton);
        curY += 32;

        boolean isEditing = isCratesMode ? (selectedCrateId != -1) : (selectedGroupId != -1);
        String saveLabel = isEditing ? "§aSave Changes" : (isCratesMode ? "§aAdd Crate!" : "§aAdd Group!");
        submitButton = Button.builder(Component.literal(saveLabel), b -> executeSubmit())
                .bounds(formLeft + labelOffset, curY, 95, 18)
                .build();
        this.addRenderableWidget(submitButton);

        if (isEditing) {
            String delText = confirmDelete ? "§c§lCONFIRM?" : "§cDelete";
            deleteButton = Button.builder(Component.literal(delText), b -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    deleteButton.setMessage(Component.literal("§c§lCONFIRM?"));
                    String entity = isCratesMode ? "crate" : "misc group";
                    statusMessage = "§cClick CONFIRM again to delete " + entity + " AND its items!";
                    statusColor = 0xFFFF5555;
                } else {
                    executeDelete();
                }
            }).bounds(formLeft + labelOffset + 105, curY, 80, 18).build();
            this.addRenderableWidget(deleteButton);
        }

        Button cancelBtn = Button.builder(CommonComponents.GUI_CANCEL, b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
            }
        }).bounds(formLeft + labelOffset + (isEditing ? 195 : 105), curY, 65, 18).build();
        this.addRenderableWidget(cancelBtn);

        populateFormForCurrentSelection();
    }

    /**
     * Populates form edit fields based on the selected crate or miscellaneous group,
     * or resets fields with current dates for new additions.
     */
    private void populateFormForCurrentSelection() {
        if (isCratesMode) {
            if (selectedCrateId == -1) {
                nameBox.setValue("");
                tagBox.setValue("");
                releaseDateBox.setValue(LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy")));
                typeIndex = 0;
                if (!crateSubFilter.equalsIgnoreCase("All")) {
                    for (int i = 0; i < CRATE_TYPES.length; i++) {
                        if (CRATE_TYPES[i].equalsIgnoreCase(crateSubFilter)) {
                            typeIndex = i;
                            break;
                        }
                    }
                }
                if (typeButton != null) {
                    typeButton.setMessage(Component.literal(CRATE_TYPES[typeIndex]));
                }
                statusMessage = "Creating new crate.";
                statusColor = 0xFFFFAA00;
            } else {
                CrateEntry crate = DatabaseManager.CRATES.get(selectedCrateId);
                if (crate != null) {
                    nameBox.setValue(crate.name() != null ? crate.name() : "");
                    tagBox.setValue(crate.urlTag() != null ? crate.urlTag() : "");
                    releaseDateBox.setValue(crate.releaseDate() != null ? crate.releaseDate() : "");

                    String type = crate.crateType() != null ? crate.crateType() : "Seasonal";
                    typeIndex = 0;
                    for (int i = 0; i < CRATE_TYPES.length; i++) {
                        if (CRATE_TYPES[i].equalsIgnoreCase(type)) {
                            typeIndex = i;
                            break;
                        }
                    }
                    if (typeButton != null) {
                        typeButton.setMessage(Component.literal(CRATE_TYPES[typeIndex]));
                    }
                    statusMessage = "Editing crate #" + crate.id() + " (" + crate.items().size() + " items).";
                    statusColor = 0xFFAAAAAA;
                }
            }
        } else {
            if (selectedGroupId == -1) {
                nameBox.setValue("");
                tagBox.setValue("");
                releaseDateBox.setValue(LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy")));
                typeIndex = 0;
                if (!miscSubFilter.equalsIgnoreCase("All")) {
                    for (int i = 0; i < GROUP_TYPES.length; i++) {
                        if (GROUP_TYPES[i].equalsIgnoreCase(miscSubFilter)) {
                            typeIndex = i;
                            break;
                        }
                    }
                }
                if (typeButton != null) {
                    typeButton.setMessage(Component.literal(GROUP_TYPES[typeIndex]));
                }
                statusMessage = "Creating new misc group.";
                statusColor = 0xFFFFAA00;
            } else {
                MiscGroupEntry group = DatabaseManager.MISC_GROUPS.get(selectedGroupId);
                if (group != null) {
                    nameBox.setValue(group.name() != null ? group.name() : "");
                    tagBox.setValue(group.urlTag() != null ? group.urlTag() : "");
                    releaseDateBox.setValue(group.releaseDate() != null ? group.releaseDate() : "");

                    String type = group.groupType() != null ? group.groupType() : "Furniture";
                    typeIndex = 0;
                    for (int i = 0; i < GROUP_TYPES.length; i++) {
                        if (GROUP_TYPES[i].equalsIgnoreCase(type)) {
                            typeIndex = i;
                            break;
                        }
                    }
                    if (typeButton != null) {
                        typeButton.setMessage(Component.literal(GROUP_TYPES[typeIndex]));
                    }
                    statusMessage = "Editing misc group #" + group.id() + " (" + group.items().size() + " items).";
                    statusColor = 0xFFAAAAAA;
                }
            }
        }
    }

    /**
     * Submits creation or update payloads via REST API and refreshes client caches upon success.
     */
    private void executeSubmit() {
        String name = nameBox.getValue().trim();
        String date = releaseDateBox.getValue().trim();
        String tag = tagBox.getValue().trim();
        String type = isCratesMode ? CRATE_TYPES[typeIndex] : GROUP_TYPES[typeIndex];

        if (name.isEmpty() || date.isEmpty() || tag.isEmpty()) {
            statusMessage = "§cName, Release Date, and URL Tag are required!";
            statusColor = 0xFFFF5555;
            return;
        }

        submitButton.active = false;
        statusMessage = (isCratesMode ? (selectedCrateId == -1) : (selectedGroupId == -1)) ? "Creating..." : "Saving changes...";
        statusColor = 0xFFFFFF55;

        if (isCratesMode) {
            var future = (selectedCrateId == -1)
                    ? AdminApiClient.createCrate(name, date, tag, type)
                    : AdminApiClient.updateCrate(selectedCrateId, name, date, tag, type);

            future.thenAccept(res -> {
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        submitButton.active = true;
                        if (res.success()) {
                            statusMessage = "§a" + res.message();
                            statusColor = 0xFF55FF55;
                            if (selectedCrateId == -1 && res.itemId() > 0) {
                                selectedCrateId = res.itemId();
                            }
                            DatabaseManager.fetchCrates().thenRun(() -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> {
                                        refreshDataLists();
                                        rebuildLayout();
                                    });
                                }
                            });
                        } else {
                            statusMessage = "§cError: " + res.error();
                            statusColor = 0xFFFF5555;
                        }
                    });
                }
            });
        } else {
            var future = (selectedGroupId == -1)
                    ? AdminApiClient.createMiscGroup(name, date, tag, type)
                    : AdminApiClient.updateMiscGroup(selectedGroupId, name, date, tag, type);

            future.thenAccept(res -> {
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        submitButton.active = true;
                        if (res.success()) {
                            statusMessage = "§a" + res.message();
                            statusColor = 0xFF55FF55;
                            if (selectedGroupId == -1 && res.itemId() > 0) {
                                selectedGroupId = res.itemId();
                            }
                            DatabaseManager.fetchMiscGroups().thenRun(() -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> {
                                        refreshDataLists();
                                        rebuildLayout();
                                    });
                                }
                            });
                        } else {
                            statusMessage = "§cError: " + res.error();
                            statusColor = 0xFFFF5555;
                        }
                    });
                }
            });
        }
    }

    /**
     * Executes deletion of the container and its constituent child items
     * following double-click confirmation.
     */
    private void executeDelete() {
        if (isCratesMode) {
            if (selectedCrateId == -1) return;
            int crateIdToDelete = selectedCrateId;
            statusMessage = "Deleting crate #" + crateIdToDelete + "...";
            statusColor = 0xFFFFFF55;
            if (deleteButton != null) deleteButton.active = false;

            AdminApiClient.deleteCrate(crateIdToDelete).thenAccept(res -> {
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        if (res.success()) {
                            statusMessage = "§aCrate #" + crateIdToDelete + " deleted.";
                            statusColor = 0xFF55FF55;
                            selectedCrateId = -1;
                            confirmDelete = false;

                            DatabaseManager.fetchCrates().thenRun(() -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> {
                                        refreshDataLists();
                                        if (!crates.isEmpty()) selectedCrateId = crates.get(0).id();
                                        rebuildLayout();
                                    });
                                }
                            });
                        } else {
                            statusMessage = "§cDelete failed: " + res.error();
                            statusColor = 0xFFFF5555;
                            confirmDelete = false;
                            if (deleteButton != null) {
                                deleteButton.active = true;
                                deleteButton.setMessage(Component.literal("Delete"));
                            }
                        }
                    });
                }
            });
        } else {
            if (selectedGroupId == -1) return;
            int groupIdToDelete = selectedGroupId;
            statusMessage = "Deleting misc group #" + groupIdToDelete + "...";
            statusColor = 0xFFFFFF55;
            if (deleteButton != null) deleteButton.active = false;

            AdminApiClient.deleteMiscGroup(groupIdToDelete).thenAccept(res -> {
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        if (res.success()) {
                            statusMessage = "§aMisc group #" + groupIdToDelete + " deleted.";
                            statusColor = 0xFF55FF55;
                            selectedGroupId = -1;
                            confirmDelete = false;

                            DatabaseManager.fetchMiscGroups().thenRun(() -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> {
                                        refreshDataLists();
                                        if (!miscGroups.isEmpty()) selectedGroupId = miscGroups.get(0).id();
                                        rebuildLayout();
                                    });
                                }
                            });
                        } else {
                            statusMessage = "§cDelete failed: " + res.error();
                            statusColor = 0xFFFF5555;
                            confirmDelete = false;
                            if (deleteButton != null) {
                                deleteButton.active = true;
                                deleteButton.setMessage(Component.literal("Delete"));
                            }
                        }
                    });
                }
            });
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int panelW = Math.min(this.width - 20, 750);
        int panelLeft = (this.width - panelW) / 2;
        int sidebarW = 230;

        int maxListSize;
        if (isCratesMode) {
            maxListSize = (int) crates.stream()
                    .filter(c -> crateSubFilter.equalsIgnoreCase("All")
                            || (c.crateType() != null && c.crateType().equalsIgnoreCase(crateSubFilter)))
                    .count();
        } else {
            maxListSize = (int) miscGroups.stream()
                    .filter(g -> {
                        if (miscSubFilter.equalsIgnoreCase("All")) return true;
                        if (miscSubFilter.equalsIgnoreCase("Unsorted")) {
                            return g.groupType() == null
                                    || g.groupType().isBlank()
                                    || g.groupType().equalsIgnoreCase("Unsorted")
                                    || g.groupType().equalsIgnoreCase("None")
                                    || g.groupType().equalsIgnoreCase("null");
                        }
                        return g.groupType() != null && g.groupType().equalsIgnoreCase(miscSubFilter);
                    })
                    .count();
        }

        if (mouseX >= panelLeft && mouseX <= panelLeft + sidebarW + 15) {
            if (verticalAmount < 0 && scrollOffset + 1 < maxListSize) {
                scrollOffset++;
            } else if (verticalAmount > 0 && scrollOffset > 0) {
                scrollOffset--;
            }
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
            int col = (i == 3) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 3 ("Manage Crates") cyan active highlight
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + panelW - 8, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, panelW - 16, 18, 0xFF5C1656);

        boolean isNew = isCratesMode ? (selectedCrateId == -1) : (selectedGroupId == -1);
        String entity = isCratesMode ? "crate" : "misc group";
        String subhead = isNew
                ? "§6Enter details to add a new " + entity + " container to the database."
                : "§6Edit " + entity + " attributes or delete this " + entity + " and its associated items.";
        graphics.text(this.font, Component.literal(subhead), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        int sidebarW = 230;
        int sidebarStartX = panelLeft + 8;
        int sidebarStartY = panelTop + 50;
        int sidebarH = panelH - 58;

        // Dark violet sidebar container
        graphics.fill(sidebarStartX, sidebarStartY, sidebarStartX + sidebarW + 4, sidebarStartY + sidebarH, 0xFF14071D);
        graphics.outline(sidebarStartX, sidebarStartY, sidebarW + 4, sidebarH, 0xFF4A1A5E);

        // Sidebar list entries
        int listStartY = sidebarStartY + 55; // Sits neatly below tab, sub-filter, and action buttons
        int rowH = 14;
        int viewBottom = sidebarStartY + sidebarH - 6;
        int maxVisible = (viewBottom - listStartY) / rowH;

        if (isCratesMode) {
            List<CrateEntry> visible = crates.stream()
                    .filter(c -> crateSubFilter.equalsIgnoreCase("All")
                            || (c.crateType() != null && c.crateType().equalsIgnoreCase(crateSubFilter)))
                    .toList();

            for (int i = 0; i < maxVisible; i++) {
                int index = scrollOffset + i;
                if (index >= visible.size()) break;

                CrateEntry crate = visible.get(index);
                boolean isSel = (crate.id() == selectedCrateId);
                int ry = listStartY + (i * rowH);

                boolean hovered = mouseX >= sidebarStartX + 4 && mouseX <= sidebarStartX + sidebarW
                        && mouseY >= ry - 1 && mouseY < ry + 12;

                if (isSel) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + sidebarW, ry + 12, 0x55BA68C8);
                    graphics.outline(sidebarStartX + 4, ry - 1, sidebarW - 4, 13, 0xFFBA68C8);
                } else if (hovered) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + sidebarW, ry + 12, 0x25FFFFFF);
                }

                MutableComponent headerComp = Component.empty();
                headerComp.append(crate.getFormattedHeader(isSel));
                headerComp.append(Component.literal(" §8(" + crate.items().size() + ")"));

                graphics.enableScissor(sidebarStartX + 6, ry, sidebarStartX + sidebarW - 4, ry + 13);
                graphics.text(this.font, headerComp, sidebarStartX + 6, ry + 2, 0xFFFFFFFF);
                graphics.disableScissor();

                sidebarClickables.add(new ClickableTarget(sidebarStartX + 4, ry - 1, sidebarW - 4, 13, () -> {
                    selectedCrateId = crate.id();
                    confirmDelete = false;
                    rebuildLayout();
                }));
            }
        } else {
            List<MiscGroupEntry> visible = miscGroups.stream()
                    .filter(g -> {
                        if (miscSubFilter.equalsIgnoreCase("All")) return true;
                        if (miscSubFilter.equalsIgnoreCase("Unsorted")) {
                            return g.groupType() == null
                                    || g.groupType().isBlank()
                                    || g.groupType().equalsIgnoreCase("Unsorted")
                                    || g.groupType().equalsIgnoreCase("None")
                                    || g.groupType().equalsIgnoreCase("null");
                        }
                        return g.groupType() != null && g.groupType().equalsIgnoreCase(miscSubFilter);
                    })
                    .toList();

            for (int i = 0; i < maxVisible; i++) {
                int index = scrollOffset + i;
                if (index >= visible.size()) break;

                MiscGroupEntry group = visible.get(index);
                boolean isSel = (group.id() == selectedGroupId);
                int ry = listStartY + (i * rowH);

                boolean hovered = mouseX >= sidebarStartX + 4 && mouseX <= sidebarStartX + sidebarW
                        && mouseY >= ry - 1 && mouseY < ry + 12;

                if (isSel) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + sidebarW, ry + 12, 0x55BA68C8);
                    graphics.outline(sidebarStartX + 4, ry - 1, sidebarW - 4, 13, 0xFFBA68C8);
                } else if (hovered) {
                    graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + sidebarW, ry + 12, 0x25FFFFFF);
                }

                MutableComponent headerComp = Component.literal(isSel ? "§d▶ " : "§8▶ ");
                headerComp.append(group.getFormattedHeader());
                headerComp.append(Component.literal(" §8(" + group.items().size() + ")"));

                graphics.enableScissor(sidebarStartX + 6, ry, sidebarStartX + sidebarW - 4, ry + 13);
                graphics.text(this.font, headerComp, sidebarStartX + 6, ry + 2, 0xFFFFFFFF);
                graphics.disableScissor();

                sidebarClickables.add(new ClickableTarget(sidebarStartX + 4, ry - 1, sidebarW - 4, 13, () -> {
                    selectedGroupId = group.id();
                    confirmDelete = false;
                    rebuildLayout();
                }));
            }
        }

        int formLeft = panelLeft + sidebarW + 20;
        int formW = panelW - sidebarW - 30;
        int formH = panelH - 58;
        graphics.fill(formLeft - 6, panelTop + 50, formLeft + formW, panelTop + panelH - 8, 0xFF0D0E12);
        graphics.outline(formLeft - 6, panelTop + 50, formW + 6, formH, 0xFF353945);

        int formStartY = panelTop + 60;
        int formRowH = 24;
        int curY = formStartY;

        String nameLabel = isCratesMode ? "§7Crate Name:" : "§7Group Name:";
        graphics.text(this.font, Component.literal(nameLabel), formLeft, curY + 4, 0xFFAAAAAA);
        curY += formRowH;

        graphics.text(this.font, Component.literal("§7Release Date:"), formLeft, curY + 4, 0xFFAAAAAA);
        curY += formRowH;

        String tagLabel = isCratesMode ? "§7Crate Tag:" : "§7URL Tag:";
        graphics.text(this.font, Component.literal(tagLabel), formLeft, curY + 4, 0xFFAAAAAA);
        curY += formRowH;

        String typeLabel = isCratesMode ? "§7Crate Type:" : "§7Group Type:";
        graphics.text(this.font, Component.literal(typeLabel), formLeft, curY + 4, 0xFFAAAAAA);

        if (!statusMessage.isEmpty()) {
            graphics.text(this.font, Component.literal(statusMessage), formLeft, panelTop + panelH - 24, statusColor);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
        }
    }
}