package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administrative GUI screen providing complete set management:
 * <ul>
 *   <li>Browsing existing armor/bonus sets in a scrollable sidebar.</li>
 *   <li>Automatic chronological sorting by Crate Release Date.</li>
 *   <li>Reordering sets directly via ▲ / ▼ buttons with Shift-to-jump.</li>
 *   <li>Smart "Auto-Detect" feature scanning item lore for matching unassigned set items and bonus perks.</li>
 *   <li>Multi-line perk/bonus input for long lore descriptions.</li>
 *   <li>Visual Item Gallery with custom web icons and click-to-view Lore popup.</li>
 *   <li>Deleting sets with a double-click confirm guard.</li>
 * </ul>
 */
public class SetManagementScreen extends Screen {

    private static final DateTimeFormatter FLEXIBLE_DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.MONTH_OF_YEAR)
            .appendLiteral('/')
            .appendValue(ChronoField.DAY_OF_MONTH)
            .appendLiteral('/')
            .appendValue(ChronoField.YEAR, 4)
            .toFormatter();

    private final Screen parentScreen;

    private final List<AdminApiClient.SetRow> sets = new ArrayList<>();
    private int selectedSetId = -1;
    private int setScrollOffset = 0;

    // Form inputs
    private EditBox nameBox;
    private EditBox typeBox;
    private EditBox itemsBox;
    private MultiLineEditBox descriptionBox;

    // Action buttons & confirmation flags
    private Button submitButton;
    private Button deleteButton;
    private Button saveOrderBtn;
    private Button sortDateBtn;
    private boolean confirmDelete = false;
    private boolean orderDirty = false;

    // Lore Modal Overlay State
    private ItemEntry inspectedItem = null;
    private final List<net.minecraft.util.FormattedCharSequence> inspectedLoreLines = new ArrayList<>();
    private int modalScrollOffset = 0;

    // Status feedback banner
    private String statusMessage = "";
    private int statusColor = 0xFFFFAA00;

    private record ClickableTarget(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableTarget> sidebarClickables = new ArrayList<>();

    public SetManagementScreen(Screen parentScreen) {
        super(Component.literal("Set Management"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        loadSetsFromBackend();
    }

    /**
     * Queries the backend API for all registered sets, sorts by existing order index,
     * and initializes the form selection.
     */
    private void loadSetsFromBackend() {
        statusMessage = "Loading sets...";
        statusColor = 0xFFFFAA00;

        AdminApiClient.fetchSets().thenAccept(loaded -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    this.sets.clear();
                    this.sets.addAll(loaded);
                    this.sets.sort(Comparator.comparingInt(AdminApiClient.SetRow::order));

                    if (selectedSetId == -1 && !sets.isEmpty()) {
                        selectedSetId = sets.get(0).id();
                    }

                    this.statusMessage = "";
                    this.orderDirty = false;
                    rebuildLayout();
                    populateFormForSelection();
                });
            }
        });
    }

    /**
     * Rebuilds all interactive widgets (sidebar list, form fields, action buttons)
     * based on active screen resolution and selection state.
     */
    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int sidebarW = 195;
        int sidebarStartY = panelTop + 54;

        // Top "+ New Set" Button
        Button newSetBtn = Button.builder(Component.literal(selectedSetId == -1 ? "§a+ New Set" : "+ New Set"), b -> {
            selectedSetId = -1;
            confirmDelete = false;
            inspectedItem = null;
            rebuildLayout();
            populateFormForSelection();
        }).bounds(panelLeft + 10, sidebarStartY, sidebarW, 16).build();
        this.addRenderableWidget(newSetBtn);

        // Sidebar Actions: [Sort Chronological] & [Save Set Order]
        sortDateBtn = Button.builder(Component.literal("📅 Auto-Sort by Date"), b -> sortSetsChronologically())
                .bounds(panelLeft + 10, panelTop + panelH - 42, sidebarW, 16)
                .build();
        this.addRenderableWidget(sortDateBtn);

        saveOrderBtn = Button.builder(Component.literal(orderDirty ? "§e§lSave Set Order" : "Set Order Saved"), b -> commitSetOrder())
                .bounds(panelLeft + 10, panelTop + panelH - 24, sidebarW, 16)
                .build();
        saveOrderBtn.active = orderDirty;
        this.addRenderableWidget(saveOrderBtn);

        // --- Right Panel Form ---
        int formLeft = panelLeft + sidebarW + 20;
        int formW = panelW - sidebarW - 30;
        int labelOffset = 82;
        int inputW = formW - labelOffset - 12;
        int curY = panelTop + 54;
        int formRowH = 21;

        // Set Name
        nameBox = new EditBox(this.font, formLeft + labelOffset, curY, inputW, 16, Component.literal(""));
        nameBox.setMaxLength(128);
        this.addRenderableWidget(nameBox);
        curY += formRowH;

        // Set Type
        typeBox = new EditBox(this.font, formLeft + labelOffset, curY, inputW, 16, Component.literal(""));
        typeBox.setMaxLength(64);
        this.addRenderableWidget(typeBox);
        curY += formRowH;

        // Item IDs Input + Auto-Detect Button
        int detectBtnW = 75;
        int idInputW = inputW - detectBtnW - 4;

        itemsBox = new EditBox(this.font, formLeft + labelOffset, curY, idInputW, 16, Component.literal(""));
        itemsBox.setMaxLength(256);
        itemsBox.setHint(Component.literal("e.g. 108, 109, 110, 111"));
        this.addRenderableWidget(itemsBox);

        Button detectBtn = Button.builder(Component.literal("Auto-Detect"), b -> detectSetItems())
                .bounds(formLeft + labelOffset + idInputW + 4, curY, detectBtnW, 16)
                .build();
        this.addRenderableWidget(detectBtn);
        curY += formRowH;

        // Multi-line Perk/Bonus Box
        descriptionBox = MultiLineEditBox.builder()
                .setX(formLeft + labelOffset)
                .setY(curY)
                .build(this.font, inputW, 36, Component.empty());
        descriptionBox.setCharacterLimit(512);
        this.addRenderableWidget(descriptionBox);
        curY += 42;

        // --- Action Buttons ---
        boolean isEditing = (selectedSetId != -1);

        submitButton = Button.builder(Component.literal(isEditing ? "§aSave Changes" : "§aAdd New Set!"), b -> executeSubmit())
                .bounds(formLeft + labelOffset, curY, 95, 18)
                .build();
        this.addRenderableWidget(submitButton);

        if (isEditing) {
            String delLabel = confirmDelete ? "§c§lCONFIRM?" : "§cDelete";
            deleteButton = Button.builder(Component.literal(delLabel), b -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    deleteButton.setMessage(Component.literal("§c§lCONFIRM?"));
                    statusMessage = "§cClick CONFIRM again to delete this set.";
                    statusColor = 0xFFFF5555;
                } else {
                    executeDelete();
                }
            }).bounds(formLeft + labelOffset + 105, curY, 80, 18).build();
            this.addRenderableWidget(deleteButton);
        }

        // Cancel Button: Escapes "+ New Set" mode back to existing list, or returns to Admin Item List
        Button cancelBtn = Button.builder(CommonComponents.GUI_CANCEL, b -> {
            if (selectedSetId == -1 && !sets.isEmpty()) {
                selectedSetId = sets.get(0).id();
                confirmDelete = false;
                inspectedItem = null;
                rebuildLayout();
                populateFormForSelection();
            } else {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
                }
            }
        }).bounds(formLeft + labelOffset + (isEditing ? 195 : 105), curY, 65, 18).build();
        this.addRenderableWidget(cancelBtn);

        populateFormForSelection();
    }

    /**
     * Automatically sorts all registered sets chronologically based on the release
     * date of the earliest crate associated with their constituent item IDs.
     */
    private void sortSetsChronologically() {
        int originalSize = sets.size();
        sets.sort((a, b) -> {
            LocalDate dateA = getEarliestReleaseDate(a);
            LocalDate dateB = getEarliestReleaseDate(b);
            return dateA.compareTo(dateB);
        });

        orderDirty = true;
        statusMessage = "§eSorted " + originalSize + " sets chronologically. Click 'Save Set Order' to apply.";
        statusColor = 0xFFFFFF55;
        rebuildLayout();
    }

    private LocalDate getEarliestReleaseDate(AdminApiClient.SetRow set) {
        LocalDate earliest = LocalDate.MAX;
        for (int itemId : set.itemIds()) {
            CrateEntry crate = DatabaseManager.findCrateByItemId(itemId);
            if (crate != null && crate.releaseDate() != null && !crate.releaseDate().isBlank()) {
                LocalDate d = parseFlexibleDate(crate.releaseDate().trim());
                if (d != null && d.isBefore(earliest)) {
                    earliest = d;
                }
            }
        }
        return earliest.equals(LocalDate.MAX) ? LocalDate.of(2000, 1, 1) : earliest;
    }

    private LocalDate parseFlexibleDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, FLEXIBLE_DATE_FORMAT);
        } catch (Exception e) {
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
            return null;
        }
    }

    private boolean hasShiftDown() {
        if (this.minecraft == null) return false;
        long window = this.minecraft.getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private void moveSet(int currentIndex, int delta) {
        int targetIndex = Mth.clamp(currentIndex + delta, 0, sets.size() - 1);
        if (targetIndex != currentIndex) {
            AdminApiClient.SetRow moving = sets.remove(currentIndex);
            sets.add(targetIndex, moving);
            orderDirty = true;
            statusMessage = "Unsaved set order.";
            statusColor = 0xFFFFFF55;
            rebuildLayout();
        }
    }

    /**
     * Commits the updated set order indices to the backend API.
     */
    private void commitSetOrder() {
        if (sets.isEmpty()) return;

        statusMessage = "Saving set order...";
        statusColor = 0xFFFFFF55;
        saveOrderBtn.active = false;

        List<Integer> ids = sets.stream().map(AdminApiClient.SetRow::id).toList();

        AdminApiClient.reorderSets(ids).thenAccept(res -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (res.success()) {
                        statusMessage = "§aSet order saved!";
                        statusColor = 0xFF55FF55;
                        orderDirty = false;
                        rebuildLayout();
                    } else {
                        statusMessage = "§cError: " + res.error();
                        statusColor = 0xFFFF5555;
                        saveOrderBtn.active = true;
                    }
                });
            }
        });
    }

    /**
     * Populates form inputs with the selected set's data, or resets them when adding a new set.
     */
    private void populateFormForSelection() {
        if (selectedSetId == -1) {
            nameBox.setValue("");
            typeBox.setValue("Armor Set");
            itemsBox.setValue("");
            if (descriptionBox != null) descriptionBox.setValue("");
            statusMessage = "Creating new set.";
            statusColor = 0xFFFFAA00;
        } else {
            AdminApiClient.SetRow set = sets.stream().filter(s -> s.id() == selectedSetId).findFirst().orElse(null);
            if (set != null) {
                nameBox.setValue(set.name());
                typeBox.setValue(set.type());
                if (descriptionBox != null) descriptionBox.setValue(set.description());

                String idListStr = set.itemIds().stream().map(String::valueOf).collect(Collectors.joining(", "));
                itemsBox.setValue(idListStr);

                statusMessage = "Editing set #" + set.id() + ".";
                statusColor = 0xFFAAAAAA;
            }
        }
    }

    private List<Integer> parseItemIds(String input) {
        List<Integer> result = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) return result;

        String[] tokens = input.replaceAll("[\\[\\]]", "").split("[,\\s]+");
        for (String tok : tokens) {
            if (!tok.trim().isEmpty()) {
                try {
                    result.add(Integer.parseInt(tok.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    private void executeSubmit() {
        String name = nameBox.getValue().trim();
        String type = typeBox.getValue().trim();
        String desc = descriptionBox != null ? descriptionBox.getValue().trim() : "";
        List<Integer> itemIds = parseItemIds(itemsBox.getValue());

        if (name.isEmpty()) {
            statusMessage = "§cSet Name is required!";
            statusColor = 0xFFFF5555;
            return;
        }
        if (itemIds.size() < 2) {
            statusMessage = "§cSet must contain at least 2 item IDs!";
            statusColor = 0xFFFF5555;
            return;
        }

        submitButton.active = false;
        statusMessage = (selectedSetId == -1) ? "Creating set..." : "Saving set...";
        statusColor = 0xFFFFFF55;

        var future = (selectedSetId == -1)
                ? AdminApiClient.createSet(name, type, desc, itemIds)
                : AdminApiClient.updateSet(selectedSetId, name, type, desc, itemIds);

        future.thenAccept(res -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    submitButton.active = true;
                    if (res.success()) {
                        statusMessage = "§a" + res.message();
                        statusColor = 0xFF55FF55;
                        loadSetsFromBackend();
                    } else {
                        statusMessage = "§cError: " + res.error();
                        statusColor = 0xFFFF5555;
                    }
                });
            }
        });
    }

    private void executeDelete() {
        if (selectedSetId == -1) return;

        int idToDelete = selectedSetId;
        statusMessage = "Deleting set #" + idToDelete + "...";
        statusColor = 0xFFFFFF55;
        if (deleteButton != null) deleteButton.active = false;

        AdminApiClient.deleteSet(idToDelete).thenAccept(res -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (res.success()) {
                        statusMessage = "§aSet #" + idToDelete + " deleted.";
                        statusColor = 0xFF55FF55;
                        selectedSetId = -1;
                        confirmDelete = false;
                        loadSetsFromBackend();
                    } else {
                        statusMessage = "§cDelete failed: " + res.error();
                        statusColor = 0xFFFF5555;
                        confirmDelete = false;
                        if (deleteButton != null) {
                            deleteButton.active = true;
                            deleteButton.setMessage(Component.literal("§cDelete"));
                        }
                    }
                });
            }
        });
    }

    /**
     * Parses and displays lore lines for an item when clicked, matching the website view.
     */
    private void openLoreModal(ItemEntry item) {
        this.inspectedItem = item;
        this.inspectedLoreLines.clear();
        this.modalScrollOffset = 0;

        int maxLoreTextWidth = 326;

        // 1. Priority 1: Use RawDataParser to render Enchantments and authentic lore
        boolean parsedLore = false;
        if (item.rawData() != null && !item.rawData().isBlank()) {
            try {
                List<Component> combined = new ArrayList<>();

                // Add enchantments at top
                List<Component> enchants = RawDataParser.parseEnchantments(item.rawData());
                if (enchants != null && !enchants.isEmpty()) {
                    combined.addAll(enchants);
                }

                // Add lore lines
                List<Component> rawLore = RawDataParser.parseLore(item.rawData());
                if (rawLore != null && !rawLore.isEmpty()) {
                    combined.addAll(rawLore);
                }

                if (!combined.isEmpty()) {
                    String canonicalItemName = item.itemName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

                    for (Component comp : combined) {
                        String plain = comp.getString().trim();

                        // Filter out item name duplicates, internal IDs, and stray quotation artifact lines
                        if (plain.equalsIgnoreCase(item.itemName().trim())) continue;
                        if (plain.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().equals(canonicalItemName)) continue;
                        if (plain.toLowerCase().startsWith("minecraft:") || plain.toLowerCase().startsWith("item: minecraft:")) continue;
                        if (plain.equals("\"\"") || plain.equals("\"") || plain.equals("''") || plain.equals("''''")) {
                            this.inspectedLoreLines.add(null);
                            continue;
                        }

                        if (plain.isEmpty()) {
                            this.inspectedLoreLines.add(null);
                            continue;
                        }

                        this.inspectedLoreLines.addAll(this.font.split(comp, maxLoreTextWidth));
                        parsedLore = true;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Priority 2: Fallback to itemHuman
        if (!parsedLore && item.itemHuman() != null && !item.itemHuman().isBlank()) {
            String human = item.itemHuman()
                    .replace("<br>", "\n")
                    .replace("<br/>", "\n")
                    .replace("<br />", "\n")
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .replaceAll("(?i)</?p>", "\n")
                    .replaceAll("(?i)</?div>", "\n");

            String canonicalItemName = item.itemName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

            for (String raw : human.split("\n")) {
                String line = raw.trim();

                // Strip quotes that represent empty spacer rows
                if (line.isEmpty() || line.equals("\"\"") || line.equals("\"") || line.equals("''")) {
                    this.inspectedLoreLines.add(null);
                    continue;
                }

                String tokenKey = line.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                if (tokenKey.equals(canonicalItemName)) continue;

                if (line.contains("═") || line.contains("─") || line.matches("^[=-]{3,}$")) {
                    this.inspectedLoreLines.addAll(this.font.split(Component.literal("§8" + "═".repeat(34)), maxLoreTextWidth));
                    continue;
                }

                Component lineComp = Component.literal(line.replace('&', '§'));
                this.inspectedLoreLines.addAll(this.font.split(lineComp, maxLoreTextWidth));
            }
        }

        if (this.inspectedLoreLines.isEmpty()) {
            this.inspectedLoreLines.addAll(this.font.split(Component.literal("§7(No lore available for this item)"), maxLoreTextWidth));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (inspectedItem != null) {
            int maxScroll = Math.max(0, (inspectedLoreLines.size() * 11) - 200);
            if (maxScroll > 0) {
                modalScrollOffset = Mth.clamp(modalScrollOffset - (int) (verticalAmount * 14), 0, maxScroll);
            }
            return true;
        }

        int panelW = Math.min(this.width - 20, 750);
        int panelLeft = (this.width - panelW) / 2;
        int sidebarW = 195;

        if (mouseX >= panelLeft && mouseX <= panelLeft + sidebarW + 15) {
            if (verticalAmount < 0 && setScrollOffset + 1 < sets.size()) {
                setScrollOffset++;
                rebuildLayout();
            } else if (verticalAmount > 0 && setScrollOffset > 0) {
                setScrollOffset--;
                rebuildLayout();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        double mouseX = event.x();
        double mouseY = event.y();

        // If inspecting item lore, any click dismisses the popup
        if (inspectedItem != null) {
            inspectedItem = null;
            return true;
        }

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

        // Check sidebar row clicks (selection & up/down buttons)
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (ClickableTarget target : sidebarClickables) {
                if (mouseX >= target.x() && mouseX <= target.x() + target.width()
                        && mouseY >= target.y() && mouseY <= target.y() + target.height()) {
                    target.action().run();
                    return true;
                }
            }
        }

        // Item Gallery Card Click -> Open Lore Modal
        int sidebarW = 195;
        int formLeft = panelLeft + sidebarW + 20;
        int formW = panelW - sidebarW - 28;
        int galleryTop = panelTop + 185;
        int cardStartY = galleryTop + 12;

        int cardsPerRow = 3;
        int cardSpacing = 6;
        int totalAvailableW = formW - 12;
        int cardW = (totalAvailableW - (cardSpacing * (cardsPerRow - 1))) / cardsPerRow;
        int cardH = 34;

        List<Integer> parsedIds = parseItemIds(itemsBox != null ? itemsBox.getValue() : "");
        for (int k = 0; k < parsedIds.size(); k++) {
            int id = parsedIds.get(k);
            int row = k / cardsPerRow;
            int col = k % cardsPerRow;

            int cardX = formLeft + (col * (cardW + cardSpacing));
            int cardY = cardStartY + (row * (cardH + 6));

            if (mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cardY && mouseY <= cardY + cardH) {
                ItemEntry item = DatabaseManager.findItemById(id);
                if (item != null) {
                    openLoreModal(item);
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
            int col = (i == 4) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 4 ("Set Management") cyan active highlight
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + panelW - 8, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, panelW - 16, 18, 0xFF5C1656);
        graphics.text(this.font, Component.literal("§6Manage item sets and bonus perks. Click any item card below to inspect its Lore."), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        // Left sidebar container (Player GUI dark violet styling)
        int sidebarW = 195;
        int sidebarStartX = panelLeft + 8;
        int sidebarStartY = panelTop + 50;
        int sidebarH = panelH - 58;

        graphics.fill(sidebarStartX, sidebarStartY, sidebarStartX + sidebarW + 4, sidebarStartY + sidebarH, 0xFF14071D);
        graphics.outline(sidebarStartX, sidebarStartY, sidebarW + 4, sidebarH, 0xFF4A1A5E);

        // Sidebar list entries
        int listStartY = sidebarStartY + 24;
        int rowH = 17;
        int viewBottom = panelTop + panelH - 48; // Leave room for bottom sort/save buttons
        int maxVisible = (viewBottom - listStartY) / rowH;

        for (int i = 0; i < maxVisible; i++) {
            final int index = setScrollOffset + i;
            if (index >= sets.size()) break;

            AdminApiClient.SetRow set = sets.get(index);
            boolean isSel = (set.id() == selectedSetId);
            int ry = listStartY + (i * rowH);

            int rowW = sidebarW - 4;
            int selectAreaW = rowW - 32; // Reserve right side for ▲ and ▼ buttons

            boolean hovered = mouseX >= sidebarStartX + 4 && mouseX <= sidebarStartX + 4 + selectAreaW
                    && mouseY >= ry - 1 && mouseY < ry + 15;

            // Highlight row
            if (isSel) {
                graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + 4 + rowW, ry + 15, 0x55BA68C8);
                graphics.outline(sidebarStartX + 4, ry - 1, rowW, 16, 0xFFBA68C8);
            } else if (hovered) {
                graphics.fill(sidebarStartX + 4, ry - 1, sidebarStartX + 4 + selectAreaW, ry + 15, 0x25FFFFFF);
            }

            // Determine origin release date for display
            String originDateStr = "";
            LocalDate earliestDate = getEarliestReleaseDate(set);
            if (!earliestDate.equals(LocalDate.of(2000, 1, 1)) && !earliestDate.equals(LocalDate.MAX)) {
                originDateStr = " §8(" + earliestDate.getMonthValue() + "/" + earliestDate.getDayOfMonth() + "/" + earliestDate.getYear() + ")";
            }

            // Set Title Component with arrow and origin release date
            Component titleComp = Component.literal((isSel ? "§b▶ §f§l" : "§7") + set.name() + originDateStr);
            graphics.enableScissor(sidebarStartX + 6, ry, sidebarStartX + 4 + selectAreaW - 2, ry + 16);
            graphics.text(this.font, titleComp, sidebarStartX + 6, ry + 3, 0xFFFFFFFF);
            graphics.disableScissor();

            // Row selection clickable
            sidebarClickables.add(new ClickableTarget(sidebarStartX + 4, ry - 1, selectAreaW, 16, () -> {
                selectedSetId = set.id();
                confirmDelete = false;
                inspectedItem = null;
                rebuildLayout();
                populateFormForSelection();
            }));

            // --- Move Buttons on Right Side (▲ / ▼) ---
            int upBtnX = sidebarStartX + 4 + rowW - 28;
            int downBtnX = sidebarStartX + 4 + rowW - 14;
            int btnY = ry;
            int btnSize = 13;

            boolean canMoveUp = (index > 0);
            boolean canMoveDown = (index < sets.size() - 1);

            boolean upHover = canMoveUp && mouseX >= upBtnX && mouseX <= upBtnX + btnSize && mouseY >= btnY && mouseY <= btnY + btnSize;
            boolean downHover = canMoveDown && mouseX >= downBtnX && mouseX <= downBtnX + btnSize && mouseY >= btnY && mouseY <= btnY + btnSize;

            // ▲ Button
            int upBg = canMoveUp ? (upHover ? 0xFF8B2582 : 0xFF2A1038) : 0xFF180A20;
            int upBorder = canMoveUp ? (upHover ? 0xFFBA68C8 : 0xFF4A1A5E) : 0xFF251030;
            int upTextCol = canMoveUp ? (upHover ? 0xFFFFFFFF : 0xFFDDAADD) : 0xFF553355;

            graphics.fill(upBtnX, btnY, upBtnX + btnSize, btnY + btnSize, upBg);
            graphics.outline(upBtnX, btnY, btnSize, btnSize, upBorder);
            graphics.centeredText(this.font, Component.literal("▲"), upBtnX + (btnSize / 2), btnY + 2, upTextCol);

            if (canMoveUp) {
                sidebarClickables.add(new ClickableTarget(upBtnX, btnY, btnSize, btnSize, () -> {
                    boolean shift = hasShiftDown();
                    moveSet(index, shift ? -index : -1);
                }));
            }

            // ▼ Button
            int downBg = canMoveDown ? (downHover ? 0xFF8B2582 : 0xFF2A1038) : 0xFF180A20;
            int downBorder = canMoveDown ? (downHover ? 0xFFBA68C8 : 0xFF4A1A5E) : 0xFF251030;
            int downTextCol = canMoveDown ? (downHover ? 0xFFFFFFFF : 0xFFDDAADD) : 0xFF553355;

            graphics.fill(downBtnX, btnY, downBtnX + btnSize, btnY + btnSize, downBg);
            graphics.outline(downBtnX, btnY, btnSize, btnSize, downBorder);
            graphics.centeredText(this.font, Component.literal("▼"), downBtnX + (btnSize / 2), btnY + 2, downTextCol);

            if (canMoveDown) {
                sidebarClickables.add(new ClickableTarget(downBtnX, btnY, btnSize, btnSize, () -> {
                    boolean shift = hasShiftDown();
                    moveSet(index, shift ? (sets.size() - 1 - index) : 1);
                }));
            }
        }

        // Right form container
        int formLeft = panelLeft + sidebarW + 20;
        int formW = panelW - sidebarW - 28;
        int formH = panelH - 58;
        graphics.fill(formLeft - 4, panelTop + 50, formLeft + formW, panelTop + panelH - 8, 0xFF0D0E12);
        graphics.outline(formLeft - 4, panelTop + 50, formW + 4, formH, 0xFF353945);

        // Form labels
        int curY = panelTop + 54;
        int formRowH = 21;

        graphics.text(this.font, Component.literal("§7Set Name:"), formLeft, curY + 4, 0xFFAAAAAA);
        curY += formRowH;

        graphics.text(this.font, Component.literal("§7Set Type:"), formLeft, curY + 4, 0xFFAAAAAA);
        curY += formRowH;

        graphics.text(this.font, Component.literal("§7Item IDs:"), formLeft, curY + 4, 0xFFAAAAAA);
        curY += formRowH;

        graphics.text(this.font, Component.literal("§7Perk/Bonus:"), formLeft, curY + 4, 0xFFAAAAAA);

        // --- LOWER RIGHT: Origin Banner & Item Gallery ---
        int galleryTop = panelTop + 185;
        graphics.fill(formLeft, galleryTop - 8, formLeft + formW - 8, galleryTop - 7, 0xFF353945);

        List<Integer> parsedIds = parseItemIds(itemsBox != null ? itemsBox.getValue() : "");

        String crateOriginInfo = "§8Origin Crate: Unknown";
        for (int id : parsedIds) {
            CrateEntry crate = DatabaseManager.findCrateByItemId(id);
            if (crate != null) {
                crateOriginInfo = "§6Origin: §f" + crate.name() + " §7(Released: §e" + (crate.releaseDate() != null ? crate.releaseDate() : "N/A") + "§7)";
                break;
            }
        }
        graphics.text(this.font, Component.literal(crateOriginInfo), formLeft + 2, galleryTop - 2, 0xFFFFFFFF);

        // Dynamic Card Sizing
        int cardStartY = galleryTop + 12;
        int cardsPerRow = 3;
        int cardSpacing = 6;
        int totalAvailableW = formW - 12;
        int cardW = (totalAvailableW - (cardSpacing * (cardsPerRow - 1))) / cardsPerRow;
        int cardH = 34;

        for (int k = 0; k < parsedIds.size(); k++) {
            int id = parsedIds.get(k);
            int row = k / cardsPerRow;
            int col = k % cardsPerRow;

            int cardX = formLeft + (col * (cardW + cardSpacing));
            int cardY = cardStartY + (row * (cardH + 6));

            if (cardY + cardH > panelTop + panelH - 26) break;

            boolean hovered = mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cardY && mouseY <= cardY + cardH;

            graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, hovered ? 0xFF242733 : 0xFF1B1D24);
            graphics.outline(cardX, cardY, cardW, cardH, hovered ? 0xFF7A8299 : 0xFF434756);

            ItemEntry item = DatabaseManager.findItemById(id);

            // 1. Render Downloaded Icon via TextureDownloader
            int iconX = cardX + 5;
            int iconY = cardY + 5;
            int iconSize = 24;

            if (item != null && item.iconUrl() != null && !item.iconUrl().isBlank()) {
                String cacheKey = (item.isCrateItem() ? "crate_" : "misc_") + item.id();
                Identifier texture = TextureDownloader.getOrLoadTexture(item.iconUrl(), cacheKey);

                if (texture != null) {
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            texture,
                            iconX,
                            iconY,
                            0.0F,
                            0.0F,
                            iconSize,
                            iconSize,
                            iconSize,
                            iconSize
                    );
                } else {
                    graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF14151B);
                    graphics.text(this.font, Component.literal("..."), iconX + 6, iconY + 8, 0xFF666666);
                }
            } else {
                graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF14151B);
                graphics.text(this.font, Component.literal("?"), iconX + 9, iconY + 8, 0xFF888888);
            }

            // 2. Render Styled Item Name with Scissor Clipping
            Component itemComp = getStyledItemName(item);
            int textLeft = cardX + 34;
            int nameAvailableW = cardW - 38;

            graphics.enableScissor(textLeft, cardY, textLeft + nameAvailableW, cardY + cardH);
            graphics.text(this.font, itemComp, textLeft, cardY + 6, 0xFFFFFFFF);
            graphics.disableScissor();

            // 3. Render Item ID & Click Hint
            graphics.text(this.font, Component.literal("§8#" + id + " §7(Click lore)"), textLeft, cardY + 18, 0xFF888888);
        }

        // Status message
        if (!statusMessage.isEmpty()) {
            graphics.text(this.font, Component.literal(statusMessage), formLeft, panelTop + panelH - 18, statusColor);
        }

        // Render widgets (Buttons, textboxes)
        super.extractRenderState(graphics, mouseX, mouseY, a);

        // --- LORE POPUP MODAL ---
        if (inspectedItem != null) {
            graphics.fill(0, 0, this.width, this.height, 0xB0000000);

            int modalW = 350;
            int modalH = Math.min(this.height - 30, 310);
            int modalX = (this.width - modalW) / 2;
            int modalY = (this.height - modalH) / 2;

            // Modal Background & Frame
            graphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xFF14071D);
            graphics.outline(modalX, modalY, modalW, modalH, 0xFF8B2582);

            // Modal Header with Icon & Title
            String modalCacheKey = (inspectedItem.isCrateItem() ? "crate_" : "misc_") + inspectedItem.id();
            Identifier texture = TextureDownloader.getOrLoadTexture(inspectedItem.iconUrl(), modalCacheKey);
            if (texture != null) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        texture,
                        modalX + 10,
                        modalY + 10,
                        0.0F,
                        0.0F,
                        24,
                        24,
                        24,
                        24
                );
            } else {
                graphics.fill(modalX + 10, modalY + 10, modalX + 34, modalY + 34, 0xFF0D0E12);
            }

            // Render with authentic database colors
            Component modalTitle = getStyledItemName(inspectedItem);
            graphics.text(this.font, modalTitle, modalX + 40, modalY + 12, 0xFFFFFFFF);
            graphics.text(this.font, Component.literal("§8#" + inspectedItem.id() + "  §7(Click anywhere or Esc to close)"), modalX + 40, modalY + 24, 0xFFAAAAAA);

            // Header Separator
            graphics.fill(modalX + 8, modalY + 38, modalX + modalW - 8, modalY + 39, 0xFF353945);

            // Scissored Lore Body
            int viewTop = modalY + 44;
            int modalViewBottom = modalY + modalH - 8;
            int viewHeight = modalViewBottom - viewTop;

            graphics.enableScissor(modalX + 8, viewTop, modalX + modalW - 8, modalViewBottom);

            int lineY = viewTop - modalScrollOffset;
            for (net.minecraft.util.FormattedCharSequence line : inspectedLoreLines) {
                if (line != null && lineY + 10 >= viewTop && lineY <= modalViewBottom) {
                    graphics.text(this.font, line, modalX + 12, lineY, 0xFFFFFFFF);
                }
                lineY += 11;
            }

            graphics.disableScissor();

            // Scrollbar if lore exceeds modal height
            int totalLoreHeight = inspectedLoreLines.size() * 11;
            int maxScroll = Math.max(0, totalLoreHeight - viewHeight);
            if (maxScroll > 0) {
                int barX = modalX + modalW - 6;
                int barHeight = Math.max(14, (int) ((float) viewHeight / totalLoreHeight * viewHeight));
                int barY = viewTop + (int) ((float) modalScrollOffset / maxScroll * (viewHeight - barHeight));

                graphics.fill(barX, viewTop, barX + 2, modalViewBottom, 0xFF220D30);
                graphics.fill(barX, barY, barX + 2, barY + barHeight, 0xFF8E24AA);
            }
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (inspectedItem != null && (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE)) {
            inspectedItem = null;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
        }
    }

    /**
     * Automatically scans the database for items belonging to the typed set name,
     * ignoring items that already belong to any registered set.
     */
    private void detectSetItems() {
        String query = nameBox != null ? nameBox.getValue().trim() : "";
        if (query.isEmpty()) {
            statusMessage = "§cEnter a Set Name first to detect items!";
            statusColor = 0xFFFF5555;
            return;
        }

        // Clean query terms (e.g. "Emberfall Set" -> "emberfall")
        String queryClean = query.toLowerCase()
                .replaceAll("(?i)\\b(set|armor|armour)\\b", "")
                .trim();

        if (queryClean.length() < 3) {
            queryClean = query.toLowerCase().trim();
        }

        // 1. Gather all item IDs already claimed by registered sets
        Set<Integer> claimedIds = new HashSet<>();
        for (AdminApiClient.SetRow set : sets) {
            if (set.id() != selectedSetId) {
                claimedIds.addAll(set.itemIds());
            }
        }

        // 2. Scan all database items
        List<ItemEntry> allItems = new ArrayList<>();
        allItems.addAll(DatabaseManager.CRATE_ITEMS.values());
        allItems.addAll(DatabaseManager.MISC_ITEMS.values());

        List<Integer> matchedIds = new ArrayList<>();
        String extractedBonus = "";

        for (ItemEntry it : allItems) {
            int id;
            try {
                id = Integer.parseInt(it.id().trim());
            } catch (Exception e) {
                continue;
            }

            // Ignore items already assigned to another set
            if (claimedIds.contains(id)) {
                continue;
            }

            boolean matches = false;

            // Check itemHuman lore
            String lore = (it.itemHuman() != null) ? it.itemHuman() : "";
            // Also inspect rawData NBT if present
            if (it.rawData() != null) {
                lore += " " + it.rawData();
            }

            String lowerLore = lore.toLowerCase();

            // Match if lore contains "[SetName] Set" or the set term
            if (lowerLore.contains(queryClean + " set") || lowerLore.contains(queryClean + " bonus")
                    || (lowerLore.contains(queryClean) && lowerLore.contains("set bonus"))) {
                matches = true;
            }

            if (matches) {
                matchedIds.add(id);

                // Attempt to parse out only the exact bonus perk line
                if (extractedBonus.isEmpty()) {
                    String sourceText = (it.itemHuman() != null && !it.itemHuman().isBlank())
                            ? it.itemHuman()
                            : (it.rawData() != null ? it.rawData() : "");

                    if (!sourceText.isEmpty()) {
                        String clean = sourceText
                                .replace("\\r\\n", "\n")
                                .replace("\\n", "\n")
                                .replace("\r", "")
                                .replaceAll("(?i)<br\\s*/?>", "\n")
                                .replaceAll("(?i)§[0-9a-fk-or]", "")
                                .replaceAll("(?i)&[0-9a-fk-or]", "");

                        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                                "(?i)(?:" + java.util.regex.Pattern.quote(queryClean) + "\\s+set|set\\s+bonus)[\\s:>]+([^\\n]+)",
                                java.util.regex.Pattern.CASE_INSENSITIVE
                        );
                        java.util.regex.Matcher m = p.matcher(clean);
                        if (m.find()) {
                            String candidate = m.group(1).trim();
                            // Cut off immediately at divider symbols (=, ═, -), crate labels, or seasons
                            String[] cutoffs = candidate.split("(?i)[=═─-]{2,}|\\bblossom\\b|\\bcrate\\b|\\bseason\\b|\\brarity\\b");
                            if (cutoffs.length > 0) {
                                candidate = cutoffs[0].trim();
                            }
                            candidate = candidate.replaceAll("^[>\\-\\s]+", "").trim();

                            if (!candidate.isEmpty() && candidate.length() > 5) {
                                extractedBonus = candidate;
                            }
                        }
                    }
                }
            }
        }

        if (matchedIds.isEmpty()) {
            statusMessage = "§cNo unassigned items found matching '" + query + "'.";
            statusColor = 0xFFFF5555;
            return;
        }

        Collections.sort(matchedIds);
        String idListStr = matchedIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
        itemsBox.setValue(idListStr);

        if (!extractedBonus.isEmpty() && descriptionBox != null && descriptionBox.getValue().trim().isEmpty()) {
            descriptionBox.setValue(extractedBonus);
        }

        statusMessage = "§aDetected " + matchedIds.size() + " items for " + query + "!";
        statusColor = 0xFF55FF55;
    }

    private Component getStyledItemName(ItemEntry item) {
        if (item == null) return Component.literal("Unknown");

        // 1. Check rawData NBT custom_name first
        if (item.rawData() != null && !item.rawData().isBlank()) {
            try {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(item.rawData()).getAsJsonObject();
                com.google.gson.JsonObject compObj = root.has("components") ? root.getAsJsonObject("components") : root;
                if (compObj.has("minecraft:custom_name")) {
                    com.google.gson.JsonElement nameEl = compObj.get("minecraft:custom_name");
                    if (nameEl.isJsonPrimitive() && nameEl.getAsJsonPrimitive().isString()) {
                        try {
                            nameEl = com.google.gson.JsonParser.parseString(nameEl.getAsString());
                        } catch (Exception ignored) {}
                    }
                    Component parsed = net.minecraft.network.chat.ComponentSerialization.CODEC
                            .parse(com.mojang.serialization.JsonOps.INSTANCE, nameEl)
                            .result()
                            .orElse(null);
                    if (parsed != null) return parsed;
                }
            } catch (Throwable ignored) {}
        }

        // 2. Fallback to getStyledComponent() from ItemEntry
        return item.getStyledComponent();
    }
}