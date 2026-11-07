package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.AdminPayloadBuilder;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.MiscGroupEntry;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Administrative GUI screen providing a searchable list and table view of all items
 * partitioned by crate or miscellaneous group.
 * <p>
 * Key features:
 * <ul>
 *   <li>Browsing crates and misc groups in a scrollable, theme-styled left drawer sidebar.</li>
 *   <li>Inspecting registered items, custom web icons, IDs, names, and classification badges.</li>
 *   <li>Instantiating {@link AdminUploadScreen} pre-populated with an item's draft to edit metadata.</li>
 *   <li>Deleting obsolete item records with a two-click confirmation guard and cache eviction.</li>
 * </ul>
 */
public class AdminItemListScreen extends Screen {

    /** Screen to return to when closing or escaping this menu. */
    private final Screen parentScreen;

    /** Cached collection of all crates retrieved from DatabaseManager. */
    private List<CrateEntry> crates = new ArrayList<>();

    /** Currently highlighted and inspected crate ID. */
    private int selectedCrateId = 1;

    /** Items belonging to the currently selected crate or miscellaneous group. */
    private List<AdminApiClient.CrateItemRow> items = new ArrayList<>();

    /** Scroll index for the left-hand drawer sidebar list. */
    private int crateScrollOffset = 0;

    /** Scroll index for the right-hand items table view. */
    private int itemScrollOffset = 0;

    /** Informational, status, or error banner text rendered at the bottom of the table. */
    private String statusMessage = "";

    /** Toggle state: true = Crates mode, false = Misc Groups mode. */
    private boolean isCratesMode = true;

    /** Cached collection of all miscellaneous groups. */
    private List<MiscGroupEntry> miscGroups = new ArrayList<>();

    /** Currently highlighted and inspected miscellaneous group ID. */
    private int selectedGroupId = -1;

    /** Tracks which item ID is awaiting a second confirmation click to delete (-1 for none). */
    private int pendingDeleteItemId = -1;

    /** Clickable target representation for custom direct-drawn sidebar rows. */
    private record ClickableTarget(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableTarget> sidebarClickables = new ArrayList<>();

    /**
     * Constructs the Admin Item List screen.
     *
     * @param parentScreen Parent screen to display when pressing Escape or Cancel.
     */
    public AdminItemListScreen(Screen parentScreen) {
        super(Component.literal("Item List"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        // Sort crates chronologically with ID fallback
        this.crates = new ArrayList<>(DatabaseManager.CRATES.values());
        this.crates.sort((a, b) -> {
            var da = parseFlexibleDate(a.releaseDate());
            var db = parseFlexibleDate(b.releaseDate());
            int cmp = da.compareTo(db);
            return cmp != 0 ? cmp : Integer.compare(a.id(), b.id());
        });

        // Sort misc groups by persistent groupOrder
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
     * Parses loose date strings (e.g. MM/dd/yyyy or YYYY-MM-DD) into LocalDate objects.
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
     * Clears and reconstructs interactive screen widgets (mode toggle pills,
     * item management buttons, and two-click confirmation deletion buttons).
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
                        pendingDeleteItemId = -1;
                        itemScrollOffset = 0;
                        crateScrollOffset = 0;
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
                        pendingDeleteItemId = -1;
                        itemScrollOffset = 0;
                        crateScrollOffset = 0;

                        // Refresh local group list from DatabaseManager cache
                        this.miscGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
                        this.miscGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));

                        if (!miscGroups.isEmpty()) {
                            boolean validGroup = miscGroups.stream().anyMatch(g -> g.id() == selectedGroupId);
                            if (!validGroup || selectedGroupId == -1) {
                                selectedGroupId = miscGroups.get(0).id();
                            }
                        }

                        rebuildLayout();

                        if (selectedGroupId != -1) {
                            loadItemsForGroup(selectedGroupId);
                        } else {
                            // Fallback fetch if cache was empty
                            DatabaseManager.fetchMiscGroups().thenRun(() -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> {
                                        this.miscGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
                                        this.miscGroups.sort(Comparator.comparingInt(MiscGroupEntry::groupOrder));
                                        if (!miscGroups.isEmpty()) {
                                            selectedGroupId = miscGroups.get(0).id();
                                        }
                                        rebuildLayout();
                                        if (selectedGroupId != -1) {
                                            loadItemsForGroup(selectedGroupId);
                                        }
                                    });
                                }
                            });
                        }
                    }
                }
        ).bounds(panelLeft + 10 + toggleW + 4, toggleY, toggleW, 16).build();
        this.addRenderableWidget(miscTabBtn);

        // --- Right Column Items Table Action Buttons (Manage / Delete) ---
        int tableLeft = panelLeft + crateSidebarW + 18;
        int tableW = panelW - crateSidebarW - 28;
        int itemStartY = panelTop + 72;
        int itemRowH = 18;
        int maxVisibleItems = (panelH - 82) / itemRowH;

        for (int i = 0; i < maxVisibleItems; i++) {
            int index = itemScrollOffset + i;
            if (index >= items.size()) break;

            AdminApiClient.CrateItemRow item = items.get(index);
            int rowY = itemStartY + (i * itemRowH);

            // Manage Button -> Opens AdminUploadScreen populated with DraftItem
            Button manageBtn = Button.builder(Component.literal("§eManage"), b -> {
                try {
                    AdminPayloadBuilder.DraftItem editDraft = new AdminPayloadBuilder.DraftItem();
                    editDraft.itemId = item.id();
                    editDraft.isCrateItem = isCratesMode;
                    editDraft.crateId = isCratesMode ? selectedCrateId : 0;
                    editDraft.groupId = !isCratesMode ? selectedGroupId : 0;
                    editDraft.itemName = item.name();
                    editDraft.rarityHuman = item.rarity();
                    editDraft.rarityHtml = "<span>" + item.rarity() + "</span>";
                    editDraft.winPercentage = item.winPercentage();
                    editDraft.tagPrimary = item.primaryTag();
                    editDraft.tagSecondary = item.secondaryTag();
                    editDraft.tagTertiary = item.tertiaryTag();
                    editDraft.notes = item.notes();

                    var cached = isCratesMode
                            ? DatabaseManager.CRATE_ITEMS.get(String.valueOf(item.id()))
                            : DatabaseManager.MISC_ITEMS.get(String.valueOf(item.id()));

                    if (cached != null) {
                        editDraft.iconUrl = cached.iconUrl();
                        editDraft.itemHuman = cached.itemHuman();
                        editDraft.rawData = cached.rawData();
                        editDraft.imageType = cached.imageType();
                        boolean parsedLore = false;

                        if (cached.itemHuman() != null && !cached.itemHuman().isBlank()) {
                            String cleanLore = cached.itemHuman()
                                    .replace("<br>", "\n")
                                    .replace("<br/>", "\n")
                                    .replace("\r\n", "\n")
                                    .replace("\r", "\n");

                            for (String rawLine : cleanLore.split("\n")) {
                                String line = rawLine.trim();
                                if (!line.isEmpty() && !line.equalsIgnoreCase(item.name().trim())) {
                                    editDraft.loreLines.add(Component.literal(line.replace('&', '§')));
                                    parsedLore = true;
                                }
                            }
                        }

                        if (!parsedLore && cached.rawData() != null && !cached.rawData().isBlank()) {
                            try {
                                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(cached.rawData()).getAsJsonObject();
                                com.google.gson.JsonObject components = root.has("components") ? root.getAsJsonObject("components") : root;
                                if (components.has("minecraft:lore")) {
                                    com.google.gson.JsonArray loreArr = components.getAsJsonArray("minecraft:lore");
                                    for (com.google.gson.JsonElement el : loreArr) {
                                        try {
                                            com.google.gson.JsonElement parsedElement = el;
                                            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                                                try {
                                                    parsedElement = com.google.gson.JsonParser.parseString(el.getAsString());
                                                } catch (Exception ignored) {}
                                            }
                                            Component comp = net.minecraft.network.chat.ComponentSerialization.CODEC
                                                    .parse(com.mojang.serialization.JsonOps.INSTANCE, parsedElement)
                                                    .result()
                                                    .orElse(null);
                                            if (comp != null) editDraft.loreLines.add(comp);
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }

                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new AdminUploadScreen(editDraft, this));
                    }
                } catch (Throwable t) {
                    com.roland.itemdatabase.ItemDatabase.LOGGER.error("Error opening Manage screen for item " + item.id(), t);
                }
            }).bounds(tableLeft + tableW - 96, rowY, 44, 15).build();
            this.addRenderableWidget(manageBtn);

            // Delete Button with Two-Click Confirmation Safeguard
            boolean isPendingConfirm = (pendingDeleteItemId == item.id());
            String delLabel = isPendingConfirm ? "§c§lCONFIRM?" : "§cDelete";
            int delBtnW = isPendingConfirm ? 62 : 44;

            Button deleteBtn = Button.builder(Component.literal(delLabel), b -> {
                if (pendingDeleteItemId != item.id()) {
                    pendingDeleteItemId = item.id();
                    statusMessage = "§cClick CONFIRM to permanently delete item #" + item.id() + "!";
                    rebuildLayout();
                } else {
                    pendingDeleteItemId = -1;
                    statusMessage = "Deleting item #" + item.id() + "...";
                    rebuildLayout();

                    AdminApiClient.deleteItem(item.id(), isCratesMode).thenAccept(res -> {
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                if (res.success()) {
                                    // Evict textures from memory
                                    TextureDownloader.evictCache("crate_" + item.id());
                                    TextureDownloader.evictCache("misc_" + item.id());
                                    TextureDownloader.evictCache("admin_" + item.id());

                                    statusMessage = "§aItem #" + item.id() + " deleted.";

                                    if (isCratesMode) {
                                        DatabaseManager.CRATE_ITEMS.remove(String.valueOf(item.id()));
                                        DatabaseManager.fetchCrateItems().thenRun(() -> {
                                            if (this.minecraft != null) {
                                                this.minecraft.execute(() -> {
                                                    DatabaseManager.mapItemsToCrates();
                                                    loadItemsForCrate(selectedCrateId);
                                                });
                                            }
                                        });
                                    } else {
                                        DatabaseManager.MISC_ITEMS.remove(String.valueOf(item.id()));
                                        DatabaseManager.fetchMiscItems().thenRun(() -> {
                                            if (this.minecraft != null) {
                                                this.minecraft.execute(() -> {
                                                    DatabaseManager.mapItemsToMiscGroups();
                                                    loadItemsForGroup(selectedGroupId);
                                                });
                                            }
                                        });
                                    }
                                } else {
                                    statusMessage = "§cDelete failed: " + res.error();
                                    rebuildLayout();
                                }
                            });
                        }
                    });
                }
            }).bounds(tableLeft + tableW - (isPendingConfirm ? 66 : 48), rowY, delBtnW, 15).build();
            this.addRenderableWidget(deleteBtn);
        }
    }

    /**
     * Queries the backend API asynchronously for all items indexed under a crate ID.
     */
    private void loadItemsForCrate(int crateId) {
        statusMessage = "Loading crate items...";
        AdminApiClient.fetchCrateItems(crateId).thenAccept(loaded -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    this.items = loaded;
                    statusMessage = "";
                    rebuildLayout();
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
            if (verticalAmount < 0 && itemScrollOffset + 1 < items.size()) {
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

        // Custom sidebar clickable row targets
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

        // Header tabs bar
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelW - 1, panelTop + 24, 0xFF2A2D36);
        String[] adminTabs = {"Add Item", "Item List", "Manage Item Order", "Manage Crates", "Set Management", "Missing Images"};
        int tabX = panelLeft + 12;
        for (int i = 0; i < adminTabs.length; i++) {
            int col = (i == 1) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 1 ("Item List") cyan
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + panelW - 8, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, panelW - 16, 18, 0xFF5C1656);
        String subheader = isCratesMode
                ? "§6Click a crate on the left to inspect, manage, or delete its items."
                : "§dClick a misc group on the left to inspect, manage, or delete its items.";
        graphics.text(this.font, Component.literal(subheader), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        // Left sidebar container (dark violet palette)
        int crateSidebarW = 185;
        int sidebarStartX = panelLeft + 8;
        int sidebarStartY = panelTop + 50;
        int sidebarH = panelH - 58;

        graphics.fill(sidebarStartX, sidebarStartY, sidebarStartX + crateSidebarW + 4, sidebarStartY + sidebarH, 0xFF14071D);
        graphics.outline(sidebarStartX, sidebarStartY, crateSidebarW + 4, sidebarH, 0xFF4A1A5E);

        // Render drawer rows
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
                    selectedCrateId = crate.id();
                    pendingDeleteItemId = -1;
                    itemScrollOffset = 0;
                    rebuildLayout();
                    loadItemsForCrate(selectedCrateId);
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
                    selectedGroupId = group.id();
                    pendingDeleteItemId = -1;
                    itemScrollOffset = 0;
                    rebuildLayout();
                    loadItemsForGroup(selectedGroupId);
                }));
            }
        }

        // Right items table container
        int tableLeft = panelLeft + crateSidebarW + 18;
        int tableW = panelW - crateSidebarW - 26;
        int tableH = panelH - 58;
        graphics.fill(tableLeft, panelTop + 50, tableLeft + tableW, panelTop + panelH - 8, 0xFF0D0E12);
        graphics.outline(tableLeft, panelTop + 50, tableW, tableH, 0xFF353945);

        // Column headers
        graphics.fill(tableLeft + 1, panelTop + 51, tableLeft + tableW - 1, panelTop + 68, 0xFF22242C);
        int iconColX = tableLeft + 6;
        int idColX = tableLeft + 32;
        int nameColX = tableLeft + 74;
        int tagsColX = tableLeft + 270;
        int actionsColX = tableLeft + tableW - 85;

        graphics.text(this.font, Component.literal("§7Icon"), iconColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7ID"), idColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Item Name"), nameColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Tags"), tagsColX, panelTop + 56, 0xFFAAAAAA);
        graphics.text(this.font, Component.literal("§7Actions"), actionsColX, panelTop + 56, 0xFFAAAAAA);

        // Render rows
        int itemStartY = panelTop + 72;
        int itemRowH = 18;
        int maxVisibleItems = (panelH - 82) / itemRowH;

        for (int i = 0; i < maxVisibleItems; i++) {
            int index = itemScrollOffset + i;
            if (index >= items.size()) break;

            AdminApiClient.CrateItemRow item = items.get(index);
            int rowY = itemStartY + (i * itemRowH);
            int textY = rowY + 4;

            ItemEntry cached = isCratesMode
                    ? DatabaseManager.CRATE_ITEMS.get(String.valueOf(item.id()))
                    : DatabaseManager.MISC_ITEMS.get(String.valueOf(item.id()));

            // 1. Icon thumbnail (16x16)
            int iconX = iconColX;
            int iconY = rowY;
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

            // 2. ID
            graphics.text(this.font, Component.literal("§8#" + item.id()), idColX, textY, 0xFF888888);

            // 3. Styled Item Name (NBT Component codec parsing -> getStyledComponent fallback)
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

            int maxNameW = tagsColX - nameColX - 8;
            graphics.enableScissor(nameColX, rowY, nameColX + maxNameW, rowY + itemRowH);
            graphics.text(this.font, nameComp, nameColX, textY, 0xFFFFFFFF);
            graphics.disableScissor();

            // 4. Tags Column
            List<String> tags = (cached != null && cached.tags() != null) ? cached.tags() : List.of();
            if (!tags.isEmpty()) {
                StringBuilder tagStr = new StringBuilder();
                for (int t = 0; t < tags.size(); t++) {
                    tagStr.append("§8[§7").append(tags.get(t)).append("§8]");
                    if (t < tags.size() - 1) tagStr.append(" ");
                }
                Component tagsComp = Component.literal(tagStr.toString());
                int maxTagsW = actionsColX - tagsColX - 8;
                graphics.enableScissor(tagsColX, rowY, tagsColX + maxTagsW, rowY + itemRowH);
                graphics.text(this.font, tagsComp, tagsColX, textY, 0xFFAAAAAA);
                graphics.disableScissor();
            } else {
                graphics.text(this.font, Component.literal("§8None"), tagsColX, textY, 0xFF555555);
            }
        }

        if (!statusMessage.isEmpty()) {
            graphics.text(this.font, Component.literal(statusMessage), tableLeft + 6, panelTop + panelH - 18, 0xFFFFAA00);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /**
     * Resolves and populates items for a selected miscellaneous group container.
     */
    private void loadItemsForGroup(int groupId) {
        this.items.clear();

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

        if (groupItems.isEmpty() && DatabaseManager.MISC_ITEMS.isEmpty()) {
            statusMessage = "Loading misc items...";
            DatabaseManager.fetchMiscItems().thenRun(() -> {
                DatabaseManager.mapItemsToMiscGroups();
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> loadItemsForGroup(groupId));
                }
            });
            rebuildLayout();
            return;
        }

        groupItems.sort(Comparator.comparingInt(it -> {
            try {
                return Integer.parseInt(it.id().trim());
            } catch (Exception e) {
                return 0;
            }
        }));

        for (ItemEntry it : groupItems) {
            int itemId;
            try {
                itemId = Integer.parseInt(it.id().trim());
            } catch (Exception e) {
                continue;
            }

            String categoryBadge = (group != null && group.groupType() != null && !group.groupType().isBlank())
                    ? group.groupType().trim()
                    : "Misc";

            List<String> displayTags = new ArrayList<>();
            displayTags.add(categoryBadge);
            if (it.tags() != null) {
                for (String t : it.tags()) {
                    if (!t.equalsIgnoreCase(categoryBadge) && !t.equals(String.valueOf(groupId))
                            && !t.equalsIgnoreCase("null") && !t.equalsIgnoreCase("none")) {
                        displayTags.add(t);
                    }
                }
            }

            String pTag = displayTags.size() > 0 ? displayTags.get(0) : "None";
            String sTag = displayTags.size() > 1 ? displayTags.get(1) : "None";
            String tTag = displayTags.size() > 2 ? displayTags.get(2) : "None";

            this.items.add(new AdminApiClient.CrateItemRow(
                    itemId,
                    it.itemName(),
                    it.rarityHuman(),
                    0,
                    "0%",
                    pTag, sTag, tTag,
                    "None", "None", "None", "None",
                    it.notes()
            ));
        }

        statusMessage = "";
        rebuildLayout();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parentScreen);
        }
    }
}