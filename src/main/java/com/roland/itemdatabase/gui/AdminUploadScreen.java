package com.roland.itemdatabase.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.roland.itemdatabase.ItemDatabase;
import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.AdminPayloadBuilder;
import com.roland.itemdatabase.data.AnimatedTexture;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.MiscGroupEntry;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.data.TagCategory;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Administrative GUI screen providing tools to upload new items or modify existing database records.
 * <p>
 * Supports both Crate items and Miscellaneous items with features including:
 * <ul>
 *   <li>Visual real-time rendering preview for item textures, animated GIFs, or held stacks.</li>
 *   <li>Direct multi-line lore editor dialog preserving Minecraft text formatting codes.</li>
 *   <li>Hierarchical 7-slot tag classification picker with real-time text search.</li>
 *   <li>Operating-system native file browser integration via {@link TinyFileDialogs}.</li>
 *   <li>Sequential REST API submission committing metadata before uploading binary icons.</li>
 * </ul>
 */
public class AdminUploadScreen extends Screen {

    /** Working draft containing item metadata prior to submission. */
    private final AdminPayloadBuilder.DraftItem draft;

    /** Parent screen to return to upon closing or saving. */
    private final Screen parentScreen;

    // Available categories
    private List<CrateEntry> availableCrates;
    private int selectedCrateIndex = 0;

    private List<MiscGroupEntry> availableGroups;
    private int selectedGroupIndex = 0;

    private Button categorySelectButton;

    // Classification tag buttons and modal state (Crate mode only)
    private final Button[] tagButtons = new Button[7];
    private boolean isTagPickerOpen = false;
    private int activeTagIndex = -1;
    private EditBox tagFilterBox;
    private int tagPickerScroll = 0;

    // Core item editable input fields
    private EditBox winPercentageBox;
    private EditBox nameBox;
    private EditBox notesBox;
    private Button rarityButton;
    private int rarityIndex = 0;

    // Image staging and action controls
    private Button browseImageButton;
    private String selectedImageName = "";
    private Identifier localPreviewIdentifier = null;
    private Button uploadButton;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;

    // Lore editor modal controls
    private boolean isLoreModalOpen = false;
    private MultiLineEditBox loreEditBox;
    private Button editLoreButton;

    /** Allowed rarity options in BlossomCraft. */
    private static final String[] ALLOWED_RARITIES = {
            "Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Exotic"
    };

    /** Master registry of all classification tags loaded from TagCategory. */
    private static final List<String> KNOWN_TAGS = new ArrayList<>();
    static {
        KNOWN_TAGS.add("None");
        for (TagCategory cat : TagCategory.values()) {
            for (String t : cat.tags()) {
                if (!KNOWN_TAGS.contains(t)) {
                    KNOWN_TAGS.add(t);
                }
            }
        }
    }

    /**
     * Constructs the upload screen with a pre-configured item draft.
     */
    public AdminUploadScreen(AdminPayloadBuilder.DraftItem draft, Screen parentScreen) {
        super(Component.literal("Add Item"));
        this.draft = draft != null ? draft : createEmptyDraft();
        this.parentScreen = parentScreen;
    }

    /**
     * Constructs the upload screen by converting an existing database {@link ItemEntry}.
     */
    public AdminUploadScreen(ItemEntry item, Screen parentScreen) {
        this(item != null ? fromItemEntry(item) : null, parentScreen);
    }

    /**
     * Constructs the upload screen in default creation mode.
     */
    public AdminUploadScreen(Screen parentScreen) {
        this((AdminPayloadBuilder.DraftItem) null, parentScreen);
    }

    /**
     * Factory method creating a blank item draft with safe defaults.
     */
    private static AdminPayloadBuilder.DraftItem createEmptyDraft() {
        AdminPayloadBuilder.DraftItem empty = new AdminPayloadBuilder.DraftItem();
        empty.isCrateItem = true;
        empty.crateId = 1;
        empty.groupId = 1;
        empty.itemName = "";
        empty.rarityHuman = "Common";
        empty.winPercentage = "0%";
        empty.tagPrimary = "Misc";
        empty.tagSecondary = "None";
        empty.tagTertiary = "None";
        empty.tagQuaternary = "None";
        empty.tagQuinary = "None";
        empty.tagSenary = "None";
        empty.tagSeptenary = "None";
        empty.notes = "";
        return empty;
    }

    /**
     * Maps an existing ItemEntry into an editable DraftItem model.
     */
    private static AdminPayloadBuilder.DraftItem fromItemEntry(ItemEntry item) {
        AdminPayloadBuilder.DraftItem d = new AdminPayloadBuilder.DraftItem();
        d.isCrateItem = item.isCrateItem();

        try {
            d.itemId = Integer.parseInt(item.id().trim());
        } catch (Exception ignored) {}

        try {
            d.crateId = item.crateId() != null && !item.crateId().isBlank() ? Integer.parseInt(item.crateId().trim()) : 1;
        } catch (Exception ignored) {}

        try {
            d.groupId = item.groupId() != null && !item.groupId().isBlank() ? Integer.parseInt(item.groupId().trim()) : 1;
        } catch (Exception ignored) {}

        d.itemName = item.itemName() != null ? item.itemName() : "";
        d.rarityHuman = item.rarityHuman() != null ? item.rarityHuman() : "Common";
        d.winPercentage = item.winPercentage() != null ? (item.winPercentage().contains("%") ? item.winPercentage() : item.winPercentage() + "%") : "0%";
        d.notes = item.notes() != null ? item.notes() : "";
        d.itemHuman = item.itemHuman() != null ? item.itemHuman() : "";
        d.rawData = item.rawData() != null ? item.rawData() : "";
        d.iconUrl = item.iconUrl() != null ? item.iconUrl() : "";

        List<String> tags = item.tags() != null ? item.tags() : List.of();
        d.tagPrimary = tags.size() > 0 ? tags.get(0) : "Misc";
        d.tagSecondary = tags.size() > 1 ? tags.get(1) : "None";
        d.tagTertiary = tags.size() > 2 ? tags.get(2) : "None";
        d.tagQuaternary = tags.size() > 3 ? tags.get(3) : "None";
        d.tagQuinary = tags.size() > 4 ? tags.get(4) : "None";
        d.tagSenary = tags.size() > 5 ? tags.get(5) : "None";
        d.tagSeptenary = tags.size() > 6 ? tags.get(6) : "None";

        return d;
    }

    @Override
    protected void init() {
        // Parse lore from NBT RawData or fallback to itemHuman
        if (draft.rawData != null && !draft.rawData.isBlank()) {
            List<Component> parsed = RawDataParser.parseLore(draft.rawData);
            if (parsed != null && !parsed.isEmpty()) {
                draft.loreLines = parsed;
            }
        }
        if ((draft.loreLines == null || draft.loreLines.isEmpty()) && draft.itemHuman != null && !draft.itemHuman.isBlank()) {
            draft.loreLines = parseAndStyleLore(draft.itemHuman, draft.itemName);
        }

        // Cache and index crates
        this.availableCrates = new ArrayList<>(DatabaseManager.CRATES.values());
        this.availableCrates.sort(Comparator.comparingInt(CrateEntry::id));
        if (!availableCrates.isEmpty()) {
            for (int i = 0; i < availableCrates.size(); i++) {
                if (availableCrates.get(i).id() == draft.crateId) {
                    selectedCrateIndex = i;
                    break;
                }
            }
        }

        // Cache and index misc groups
        this.availableGroups = new ArrayList<>(DatabaseManager.MISC_GROUPS.values());
        this.availableGroups.sort(Comparator.comparingInt(MiscGroupEntry::id));
        if (!availableGroups.isEmpty()) {
            for (int i = 0; i < availableGroups.size(); i++) {
                if (availableGroups.get(i).id() == draft.groupId) {
                    selectedGroupIndex = i;
                    break;
                }
            }
        }

        rebuildLayout();
    }

    /**
     * Clears and constructs all form widgets, inputs, and action buttons.
     */
    private void rebuildLayout() {
        this.clearWidgets();

        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        int rightColX = panelLeft + panelW / 2 + 10;
        int rightColW = panelW / 2 - 20;
        int labelOffset = 90;
        int inputW = rightColW - labelOffset;
        int fieldH = 14;
        int rowH = 17;
        int curY = panelTop + 52;

        // --- Mode Toggle: [ Crates | Misc ] (Only available when drafting a new item) ---
        boolean isEditing = draft.itemId > 0;
        if (!isEditing) {
            int toggleW = (rightColW - 4) / 2;
            Button cratesTabBtn = Button.builder(
                    Component.literal(draft.isCrateItem ? "§b§lCrate Item" : "§7Crate Item"),
                    b -> {
                        if (!draft.isCrateItem) {
                            draft.isCrateItem = true;
                            rebuildLayout();
                        }
                    }
            ).bounds(rightColX, curY, toggleW, 16).build();
            this.addRenderableWidget(cratesTabBtn);

            Button miscTabBtn = Button.builder(
                    Component.literal(!draft.isCrateItem ? "§d§lMisc Item" : "§7Misc Item"),
                    b -> {
                        if (draft.isCrateItem) {
                            draft.isCrateItem = false;
                            rebuildLayout();
                        }
                    }
            ).bounds(rightColX + toggleW + 4, curY, toggleW, 16).build();
            this.addRenderableWidget(miscTabBtn);

            curY += 20;
        }

        // --- Category Selection Button (Crate or Group) ---
        if (draft.isCrateItem) {
            String crateName = availableCrates.isEmpty() ? "Crate: " + draft.crateId : availableCrates.get(selectedCrateIndex).name();
            categorySelectButton = Button.builder(Component.literal("Crate: " + crateName), btn -> cycleCrate(1))
                    .bounds(rightColX, curY, rightColW, 16).build();
        } else {
            String groupName = availableGroups.isEmpty() ? "Group: " + draft.groupId : availableGroups.get(selectedGroupIndex).name();
            categorySelectButton = Button.builder(Component.literal("Group: " + groupName), btn -> cycleGroup(1))
                    .bounds(rightColX, curY, rightColW, 16).build();
        }
        this.addRenderableWidget(categorySelectButton);
        curY += 20;

        // --- Classification Tags (Crates Mode Only) ---
        if (draft.isCrateItem) {
            for (int i = 0; i < 7; i++) {
                final int tagSlot = i;
                String currentVal = getTagValue(draft, tagSlot);
                if (currentVal == null || currentVal.isBlank()) currentVal = "None";

                Button tagBtn = Button.builder(Component.literal(currentVal), btn -> {
                    this.activeTagIndex = tagSlot;
                    this.isTagPickerOpen = true;
                    this.tagPickerScroll = 0;
                    if (this.tagFilterBox != null) {
                        this.tagFilterBox.setValue("");
                        this.tagFilterBox.setFocused(true);
                    }
                }).bounds(rightColX + labelOffset, curY, inputW, 16).build();

                this.addRenderableWidget(tagBtn);
                tagButtons[i] = tagBtn;
                curY += rowH;
            }

            // Tag search filter box
            this.tagFilterBox = new EditBox(this.font, 0, 0, 140, 14, Component.literal("Search tag..."));
            this.tagFilterBox.setHint(Component.literal("§8Filter tags..."));
            this.tagFilterBox.setResponder(s -> this.tagPickerScroll = 0);
            this.addWidget(this.tagFilterBox);

            // Win % (Crates Mode Only)
            winPercentageBox = createField(rightColX + labelOffset, curY, inputW, fieldH, draft.winPercentage != null ? draft.winPercentage : "0%");
            curY += rowH;
        } else {
            winPercentageBox = null;
            Arrays.fill(tagButtons, null);
            curY += 10;
        }

        // --- Common Metadata Fields ---
        if (draft.isCrateItem) {
            String initialRarity = (draft.rarityHuman != null && !draft.rarityHuman.isBlank()) ? draft.rarityHuman : "Common";
            rarityIndex = Math.max(0, Arrays.asList(ALLOWED_RARITIES).indexOf(initialRarity));

            rarityButton = Button.builder(Component.literal(ALLOWED_RARITIES[rarityIndex]), btn -> cycleRarity(1))
                    .bounds(rightColX + labelOffset, curY, inputW, 16)
                    .build();
            this.addRenderableWidget(rarityButton);
            curY += rowH;
        } else {
            rarityButton = null;
        }

        nameBox = createField(rightColX + labelOffset, curY, inputW, fieldH, draft.itemName != null ? draft.itemName : "");
        curY += rowH;

        notesBox = createField(rightColX + labelOffset, curY, inputW, fieldH, draft.notes != null ? draft.notes : "");
        curY += 22;

        // Image Selection Control
        browseImageButton = Button.builder(Component.literal("§bBrowse..."), btn -> openImageFileChooser())
                .bounds(rightColX + labelOffset, curY, 80, 16)
                .build();
        this.addRenderableWidget(browseImageButton);
        curY += 22;

        // Action Buttons
        String actionLabel = isEditing ? "Save Changes" : "Add to DB!";
        uploadButton = Button.builder(Component.literal(actionLabel), btn -> executeUpload())
                .bounds(rightColX, curY, 90, 18)
                .build();
        this.addRenderableWidget(uploadButton);

        Button cancelButton = Button.builder(CommonComponents.GUI_CANCEL, btn -> onClose())
                .bounds(rightColX + 100, curY, 70, 18)
                .build();
        this.addRenderableWidget(cancelButton);

        // Edit Lore Button (Left Panel)
        int leftW = panelW / 2 - 16;
        int leftX = panelLeft + 10;
        int leftY = panelTop + 52;
        int iconBoxSize = 44;
        int iconBoxX = leftX + leftW - iconBoxSize - 8;
        int btnW = 64;
        int btnH = 14;

        editLoreButton = Button.builder(Component.literal("§e✎ Lore"), btn -> openLoreEditorModal())
                .bounds(iconBoxX - btnW - 6, leftY + 34, btnW, btnH)
                .build();
        this.addRenderableWidget(editLoreButton);

        // Lore Modal MultiLineEditBox
        int modalW = 340;
        int modalH = 220;
        int modalX = (this.width - modalW) / 2;
        int modalY = (this.height - modalH) / 2;

        this.loreEditBox = MultiLineEditBox.builder()
                .build(this.font, modalW - 24, modalH - 62, Component.literal("Lore"));
        this.loreEditBox.setPosition(modalX + 12, modalY + 28);
        this.addWidget(this.loreEditBox);
    }

    private EditBox createField(int x, int y, int w, int h, String defaultValue) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(""));
        box.setValue(defaultValue);
        this.addRenderableWidget(box);
        return box;
    }

    /**
     * Serializes form state and coordinates the two-step REST upload:
     * 1. Submits metadata JSON via POST /api/v1/admin/item or PATCH /api/v1/admin/item/{id}.
     * 2. If a local image file was staged, uploads binary via multipart/form-data.
     * 3. Evicts stale texture caches and transitions back to the item list.
     */
    private void executeUpload() {
        if (nameBox.getValue().trim().isEmpty()) {
            statusMessage = "Item name is required!";
            statusColor = 0xFFFF5555;
            return;
        }

        if (draft.isCrateItem && !availableCrates.isEmpty()) {
            draft.crateId = availableCrates.get(selectedCrateIndex).id();
        } else if (!draft.isCrateItem && !availableGroups.isEmpty()) {
            draft.groupId = availableGroups.get(selectedGroupIndex).id();
        }

        draft.itemName = nameBox.getValue().trim();
        if (draft.isCrateItem) {
            draft.rarityHtml = "<span>" + draft.rarityHuman + "</span>";
        } else {
            draft.rarityHuman = "";
            draft.rarityHtml = "";
        }
        draft.winPercentage = winPercentageBox != null ? winPercentageBox.getValue().trim() : "0%";
        draft.notes = notesBox.getValue().trim();
        draft.itemHuman = (draft.itemHuman != null) ? draft.itemHuman : "";

        boolean isEditing = draft.itemId > 0;
        uploadButton.active = false;
        statusMessage = isEditing ? "Saving changes..." : "Uploading...";
        statusColor = 0xFFFFFF55;

        JsonObject payload = AdminPayloadBuilder.serializeToApiJson(draft);

        var actionFuture = isEditing
                ? AdminApiClient.updateItem(draft.itemId, payload)
                : AdminApiClient.createItem(payload);

        actionFuture.thenAccept(result -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    if (result.success()) {
                        int finalItemId = isEditing ? draft.itemId : result.itemId();

                        if (draft.pendingImageFile != null && finalItemId > 0) {
                            statusMessage = "Uploading image icon...";
                            statusColor = 0xFFFFFF55;
                            AdminApiClient.uploadItemIcon(finalItemId, draft.pendingImageFile).thenAccept(imgRes -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(() -> {
                                        updateLocalCache(finalItemId);

                                        // Invalidate in-memory and texture manager caches
                                        TextureDownloader.evictCache("crate_" + finalItemId);
                                        TextureDownloader.evictCache("misc_" + finalItemId);
                                        TextureDownloader.evictCache("admin_" + finalItemId);

                                        if (this.minecraft.player != null) {
                                            String msg = isEditing
                                                    ? "§a[ItemDatabase] Updated item #" + finalItemId + " and refreshed icon."
                                                    : "§a[ItemDatabase] Created item #" + finalItemId + " with custom icon.";
                                            this.minecraft.player.sendSystemMessage(Component.literal(msg));
                                        }
                                        refreshCacheAndNavigate();
                                    });
                                }
                            });
                        } else {
                            if (isEditing) {
                                updateLocalCache(draft.itemId);
                            }

                            if (this.minecraft.player != null) {
                                String msg = isEditing
                                        ? "§a[ItemDatabase] Updated item #" + draft.itemId + ": " + draft.itemName
                                        : "§a[ItemDatabase] Created item #" + result.itemId() + ": " + draft.itemName;
                                this.minecraft.player.sendSystemMessage(Component.literal(msg));
                            }
                            refreshCacheAndNavigate();
                        }
                    } else {
                        statusMessage = "Error: " + result.error();
                        statusColor = 0xFFFF5555;
                        uploadButton.active = true;
                    }
                });
            }
        });
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        // Modal 1: Lore Editor Dialog Click Interception
        if (isLoreModalOpen) {
            int modalW = 340;
            int modalH = 220;
            int modalX = (this.width - modalW) / 2;
            int modalY = (this.height - modalH) / 2;

            if (event.x() < modalX || event.x() > modalX + modalW || event.y() < modalY || event.y() > modalY + modalH) {
                this.isLoreModalOpen = false;
                return true;
            }

            if (event.x() >= modalX + 12 && event.x() <= modalX + modalW - 12
                    && event.y() >= modalY + 28 && event.y() <= modalY + modalH - 34) {
                this.loreEditBox.setFocused(true);
                return this.loreEditBox.mouseClicked(event, bl);
            }

            int btnY = modalY + modalH - 26;
            if (event.y() >= btnY && event.y() <= btnY + 18) {
                if (event.x() >= modalX + 70 && event.x() <= modalX + 150) {
                    saveAndCloseLoreModal();
                    return true;
                }
                if (event.x() >= modalX + 170 && event.x() <= modalX + 250) {
                    this.isLoreModalOpen = false;
                    return true;
                }
            }
            return true;
        }

        // Modal 2: Tag Picker Dialog Click Interception
        if (isTagPickerOpen) {
            int modalW = 160;
            int modalH = 190;
            int modalX = (this.width - modalW) / 2;
            int modalY = (this.height - modalH) / 2;

            if (event.x() < modalX || event.x() > modalX + modalW || event.y() < modalY || event.y() > modalY + modalH) {
                isTagPickerOpen = false;
                return true;
            }

            if (event.y() >= modalY + 20 && event.y() <= modalY + 34) {
                this.tagFilterBox.setFocused(true);
                return true;
            } else {
                this.tagFilterBox.setFocused(false);
            }

            int listY = modalY + 38;
            String filter = this.tagFilterBox.getValue().trim().toLowerCase();
            List<String> visibleTags = KNOWN_TAGS.stream()
                    .filter(t -> filter.isEmpty() || t.toLowerCase().contains(filter))
                    .toList();

            int clickedIndex = tagPickerScroll + (int) ((event.y() - listY) / 12);
            if (clickedIndex >= 0 && clickedIndex < visibleTags.size()) {
                setTagValue(draft, activeTagIndex, visibleTags.get(clickedIndex));
                isTagPickerOpen = false;
                return true;
            }
            return true;
        }

        // Right-Click Cycling for Category and Rarity
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (categorySelectButton != null && categorySelectButton.isMouseOver(event.x(), event.y())) {
                if (draft.isCrateItem) cycleCrate(-1); else cycleGroup(-1);
                return true;
            }
            if (rarityButton != null && rarityButton.isMouseOver(event.x(), event.y())) {
                cycleRarity(-1);
                return true;
            }
        }

        // Admin Tab Bar Navigation
        int panelW = Math.min(this.width - 20, 750);
        int panelH = Math.min(this.height - 20, 390);
        int panelLeft = (this.width - panelW) / 2;
        int panelTop = (this.height - panelH) / 2;

        if (event.y() >= panelTop + 6 && event.y() <= panelTop + 22) {
            String[] adminTabs = {"Add Item", "Item List", "Manage Item Order", "Manage Crates", "Set Management", "Missing Images"};
            int tabX = panelLeft + 12;
            for (int i = 0; i < adminTabs.length; i++) {
                int tabW = this.font.width(adminTabs[i]);
                if (event.x() >= tabX && event.x() <= tabX + tabW) {
                    if (this.minecraft != null) {
                        if (i == 1) {
                            if (this.parentScreen instanceof AdminItemListScreen) {
                                this.minecraft.setScreen(this.parentScreen);
                            } else {
                                this.minecraft.setScreen(new AdminItemListScreen(this.parentScreen));
                            }
                            return true;
                        } else if (i == 2) {
                            if (this.parentScreen instanceof ManageItemOrderScreen) {
                                this.minecraft.setScreen(this.parentScreen);
                            } else {
                                this.minecraft.setScreen(new ManageItemOrderScreen(this.parentScreen));
                            }
                            return true;
                        } else if (i == 3) {
                            if (this.parentScreen instanceof ManageCratesScreen) {
                                this.minecraft.setScreen(this.parentScreen);
                            } else {
                                this.minecraft.setScreen(new ManageCratesScreen(this.parentScreen));
                            }
                            return true;
                        } else if (i == 4) {
                            if (this.parentScreen instanceof SetManagementScreen) {
                                this.minecraft.setScreen(this.parentScreen);
                            } else {
                                this.minecraft.setScreen(new SetManagementScreen(this.parentScreen));
                            }
                            return true;
                        } else if (i == 5) {
                            if (this.parentScreen instanceof MissingImagesScreen) {
                                this.minecraft.setScreen(this.parentScreen);
                            } else {
                                this.minecraft.setScreen(new MissingImagesScreen(this.parentScreen));
                            }
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
    public boolean charTyped(CharacterEvent event) {
        if (isLoreModalOpen && this.loreEditBox != null && this.loreEditBox.isFocused()) {
            return this.loreEditBox.charTyped(event);
        }
        if (isTagPickerOpen && this.tagFilterBox != null && this.tagFilterBox.isFocused()) {
            return this.tagFilterBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isLoreModalOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                this.isLoreModalOpen = false;
                return true;
            }
            if (this.loreEditBox != null && this.loreEditBox.isFocused()) {
                return this.loreEditBox.keyPressed(event);
            }
        }
        if (isTagPickerOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                isTagPickerOpen = false;
                return true;
            }
            if (this.tagFilterBox != null && this.tagFilterBox.isFocused()) {
                return this.tagFilterBox.keyPressed(event);
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isTagPickerOpen) {
            String filter = this.tagFilterBox.getValue().trim().toLowerCase();
            int count = (int) KNOWN_TAGS.stream().filter(t -> filter.isEmpty() || t.toLowerCase().contains(filter)).count();
            int maxScroll = Math.max(0, count - 10);
            tagPickerScroll = Mth.clamp(tagPickerScroll - (int) verticalAmount, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
            int col = (i == 0) ? 0xFF55FFFF : 0xFF8E95A5; // Tab 0 ("Add Item") cyan active highlight
            graphics.text(this.font, Component.literal(adminTabs[i]), tabX, panelTop + 8, col);
            tabX += this.font.width(adminTabs[i]) + 14;
        }

        // Subheader instructions banner
        graphics.fill(panelLeft + 8, panelTop + 28, panelLeft + panelW - 8, panelTop + 46, 0xFF1B1D24);
        graphics.outline(panelLeft + 8, panelTop + 28, panelW - 16, 18, 0xFF5C1656);
        String noticeText = (draft.itemId > 0)
                ? "§6Editing existing " + (draft.isCrateItem ? "crate" : "misc") + " record #" + draft.itemId + "."
                : "§6Adding new " + (draft.isCrateItem ? "crate" : "misc") + " item directly to the database via API.";
        graphics.text(this.font, Component.literal(noticeText), panelLeft + 14, panelTop + 33, 0xFFFFAA00);

        // --- Left Panel: Preview Card Box (Dark violet styling) ---
        int leftW = panelW / 2 - 16;
        int leftH = panelH - 60;
        int leftX = panelLeft + 10;
        int leftY = panelTop + 52;

        graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, 0xFF14071D);
        graphics.outline(leftX, leftY, leftW, leftH, 0xFF4A1A5E);

        graphics.text(this.font, Component.literal("§eItem Preview"), leftX + 10, leftY + 8, 0xFFFFAA00);

        int iconBoxSize = 44;
        int iconBoxX = leftX + leftW - iconBoxSize - 8;
        int iconBoxY = leftY + 8;

        graphics.fill(iconBoxX, iconBoxY, iconBoxX + iconBoxSize, iconBoxY + iconBoxSize, 0xFF08040C);
        graphics.outline(iconBoxX, iconBoxY, iconBoxSize, iconBoxSize, 0xFF5C1656);

        // Preview priority: Staged local file -> Remote web icon -> Held ItemStack -> "No Icon" placeholder
        if (localPreviewIdentifier != null) {
            int maxDim = 36;
            int spriteX = iconBoxX + (iconBoxSize - maxDim) / 2;
            int spriteY = iconBoxY + (iconBoxSize - maxDim) / 2;

            graphics.blit(RenderPipelines.GUI_TEXTURED, localPreviewIdentifier, spriteX, spriteY, 0.0F, 0.0F, maxDim, maxDim, maxDim, maxDim);
        } else if (draft.iconUrl != null && !draft.iconUrl.isBlank()) {
            String prefix = draft.isCrateItem ? "crate_" : "misc_";
            String cacheKey = (draft.itemId > 0 ? prefix + draft.itemId : "admin_preview");
            String fetchUrl = draft.iconUrl.contains("?") ? draft.iconUrl : (draft.iconUrl + "?t=" + draft.itemId);
            Identifier spriteLoc = TextureDownloader.getOrLoadTexture(fetchUrl, cacheKey);
            AnimatedTexture anim = TextureDownloader.getTextureInfo(cacheKey);

            if (spriteLoc != null) {
                int maxDim = 36;
                int imgW = (anim != null && anim.width() > 0) ? anim.width() : maxDim;
                int imgH = (anim != null && anim.height() > 0) ? anim.height() : maxDim;

                float scale = Math.min((float) maxDim / imgW, (float) maxDim / imgH);
                int renderW = Math.max(1, Math.round(imgW * scale));
                int renderH = Math.max(1, Math.round(imgH * scale));

                int spriteX = iconBoxX + (iconBoxSize - renderW) / 2;
                int spriteY = iconBoxY + (iconBoxSize - renderH) / 2;

                graphics.blit(RenderPipelines.GUI_TEXTURED, spriteLoc, spriteX, spriteY, 0.0F, 0.0F, renderW, renderH, renderW, renderH);
            } else {
                graphics.centeredText(this.font, Component.literal("§8..."), iconBoxX + (iconBoxSize / 2), iconBoxY + 18, 0xFF888888);
            }
        } else if (draft.sourceStack != null && !draft.sourceStack.isEmpty()) {
            graphics.item(draft.sourceStack, iconBoxX + (iconBoxSize - 16) / 2, iconBoxY + (iconBoxSize - 16) / 2);
        } else {
            graphics.centeredText(this.font, Component.literal("§8No Icon"), iconBoxX + (iconBoxSize / 2), iconBoxY + 18, 0xFF666666);
        }

        int maxHeaderW = iconBoxX - leftX - 16;
        Component nameComp = Component.literal("§fName: §b" + (draft.itemName != null ? draft.itemName : "Unknown"));
        if (this.font.width(nameComp) > maxHeaderW) {
            String truncated = this.font.substrByWidth(nameComp, maxHeaderW - 6).getString() + "..";
            graphics.text(this.font, Component.literal(truncated), leftX + 10, leftY + 22, 0xFFFFFFFF);
        } else {
            graphics.text(this.font, nameComp, leftX + 10, leftY + 22, 0xFFFFFFFF);
        }

        if (draft.isCrateItem) {
            graphics.text(this.font, Component.literal("§7Rarity: §d" + (draft.rarityHuman != null ? draft.rarityHuman : "Common")), leftX + 10, leftY + 34, 0xFFFFFFFF);
        }
        graphics.horizontalLine(leftX + 10, leftX + leftW - 10, leftY + 56, 0xFF4A1A5E);

        int loreY = leftY + 62;
        int maxLoreW = leftW - 20;
        int maxLoreBottom = leftY + leftH - 10;

        List<Component> rawLore = new ArrayList<>();
        if (draft.loreLines != null && !draft.loreLines.isEmpty()) {
            rawLore.addAll(draft.loreLines);
        } else if (draft.sourceStack != null) {
            var loreComponent = draft.sourceStack.get(DataComponents.LORE);
            if (loreComponent != null) {
                rawLore.addAll(loreComponent.lines());
            }
        } else if (draft.rawData != null && !draft.rawData.isBlank()) {
            List<Component> parsed = RawDataParser.parseLore(draft.rawData);
            if (parsed != null && !parsed.isEmpty()) {
                rawLore.addAll(parsed);
            }
        } else if (draft.itemHuman != null && !draft.itemHuman.isBlank()) {
            rawLore.addAll(parseAndStyleLore(draft.itemHuman, draft.itemName));
        }

        if (!rawLore.isEmpty()) {
            for (Component lineComp : rawLore) {
                var splitLines = this.font.split(lineComp, maxLoreW);
                for (var formattedLine : splitLines) {
                    if (loreY > maxLoreBottom) break;
                    graphics.text(this.font, formattedLine, leftX + 10, loreY, 0xFFFFFFFF);
                    loreY += 9;
                }
                if (loreY > maxLoreBottom) break;
            }
        }

        // --- Right Column Form Labels ---
        int rightColX = panelLeft + panelW / 2 + 10;
        int rowH = 17;
        boolean isEditing = draft.itemId > 0;
        int curY = panelTop + (isEditing ? 72 : 92);

        if (draft.isCrateItem) {
            String[] tagLabels = {"Primary Tag", "Secondary Tag", "Tertiary Tag", "Quaternary Tag", "Quinary Tag", "Senary Tag", "Septenary Tag"};
            for (String tag : tagLabels) {
                graphics.text(this.font, Component.literal("§7" + tag), rightColX, curY + 3, 0xFFAAAAAA);
                curY += rowH;
            }

            curY += 4;
            graphics.text(this.font, Component.literal("§7Win %"), rightColX, curY + 3, 0xFFAAAAAA);
            curY += rowH;
        } else {
            curY += 10;
        }

        String[] metaLabels = draft.isCrateItem
                ? new String[]{"Rarity", "Item Name", "Notes"}
                : new String[]{"Item Name", "Notes"};
        for (String lbl : metaLabels) {
            graphics.text(this.font, Component.literal("§7" + lbl), rightColX, curY + 3, 0xFFAAAAAA);
            curY += rowH;
        }

        graphics.text(this.font, Component.literal("§7Upload Icon"), rightColX, curY + 3, 0xFFAAAAAA);
        if (!selectedImageName.isEmpty()) {
            graphics.text(this.font, Component.literal("§a✔ §7" + truncateFileName(selectedImageName, 22)), rightColX + 90 + 85, curY + 4, 0xFF888888);
        }
        curY += 22;

        if (!statusMessage.isEmpty()) {
            graphics.text(this.font, Component.literal(statusMessage), rightColX + 180, curY + 6, statusColor);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);

        // --- Modal 1: Tag Picker Dialog ---
        if (isTagPickerOpen) {
            int modalW = 160;
            int modalH = 190;
            int modalX = (this.width - modalW) / 2;
            int modalY = (this.height - modalH) / 2;

            graphics.fill(0, 0, this.width, this.height, 0xB0000000);
            graphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xFF14071D);
            graphics.outline(modalX, modalY, modalW, modalH, 0xFF8B2582);

            String title = "Select Tag (" + (activeTagIndex + 1) + "/7)";
            graphics.centeredText(this.font, Component.literal("§b§l" + title), modalX + modalW / 2, modalY + 6, 0xFFFFFFFF);

            int filterY = modalY + 20;
            this.tagFilterBox.setPosition(modalX + 10, filterY);
            this.tagFilterBox.setWidth(modalW - 20);
            this.tagFilterBox.extractRenderState(graphics, 0, 0, 0.0F);

            String filter = this.tagFilterBox.getValue().trim().toLowerCase();
            List<String> visibleTags = KNOWN_TAGS.stream()
                    .filter(t -> filter.isEmpty() || t.toLowerCase().contains(filter))
                    .toList();

            int listY = filterY + 18;
            int listH = modalH - 46;
            int itemH = 12;

            graphics.enableScissor(modalX + 5, listY, modalX + modalW - 5, modalY + modalH - 6);

            int startIdx = tagPickerScroll;
            int maxItems = listH / itemH;

            for (int i = 0; i < maxItems && (startIdx + i) < visibleTags.size(); i++) {
                String tag = visibleTags.get(startIdx + i);
                int rowY = listY + (i * itemH);

                boolean hovered = mouseX >= modalX + 8 && mouseX <= modalX + modalW - 8
                        && mouseY >= rowY && mouseY < rowY + itemH;

                if (hovered) {
                    graphics.fill(modalX + 8, rowY, modalX + modalW - 8, rowY + itemH, 0x44BA68C8);
                }

                String prefix = tag.equalsIgnoreCase(getTagValue(draft, activeTagIndex)) ? "§a✔ " : "  §7";
                graphics.text(this.font, Component.literal(prefix + tag), modalX + 10, rowY + 2, hovered ? 0xFFFFFFFF : 0xFFAAAAAA);
            }

            graphics.disableScissor();
        }

        // --- Modal 2: Multi-line Lore Editor Dialog ---
        if (isLoreModalOpen) {
            int modalW = 340;
            int modalH = 220;
            int modalX = (this.width - modalW) / 2;
            int modalY = (this.height - modalH) / 2;

            graphics.fill(0, 0, this.width, this.height, 0xB0000000);
            graphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xFF14071D);
            graphics.outline(modalX, modalY, modalW, modalH, 0xFF8B2582);

            graphics.centeredText(this.font, Component.literal("§e§lEdit Item Lore"), modalX + modalW / 2, modalY + 10, 0xFFFFFFFF);

            this.loreEditBox.extractRenderState(graphics, 0, 0, 0.0F);

            int btnY = modalY + modalH - 26;
            boolean doneHovered = mouseX >= modalX + 70 && mouseX <= modalX + 150 && mouseY >= btnY && mouseY <= btnY + 18;
            graphics.fill(modalX + 70, btnY, modalX + 150, btnY + 18, doneHovered ? 0xFF2E7D32 : 0xFF1B5E20);
            graphics.outline(modalX + 70, btnY, 80, 18, 0xFFA5D6A7);
            graphics.centeredText(this.font, Component.literal("§aDone"), modalX + 110, btnY + 5, 0xFFFFFFFF);

            boolean cancelHovered = mouseX >= modalX + 170 && mouseX <= modalX + 250 && mouseY >= btnY && mouseY <= btnY + 18;
            graphics.fill(modalX + 170, btnY, modalX + 250, btnY + 18, cancelHovered ? 0xFF555555 : 0xFF333333);
            graphics.outline(modalX + 170, btnY, 80, 18, 0xFF888888);
            graphics.centeredText(this.font, Component.literal("§7Cancel"), modalX + 210, btnY + 5, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parentScreen);
        }
    }

    /**
     * Spawns an OS file chooser dialog to select an image (.png, .gif, .jpg, .jpeg) for icon upload.
     */
    private void openImageFileChooser() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(4);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.gif"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.flip();

            String filePath = TinyFileDialogs.tinyfd_openFileDialog(
                    "Select Item Icon",
                    "",
                    filters,
                    "Image Files (*.png, *.gif, *.jpg, *.jpeg)",
                    false
            );

            if (filePath != null && !filePath.isBlank()) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    draft.pendingImageFile = file;
                    selectedImageName = file.getName();
                    loadLocalPreviewTexture(file);
                }
            }
        }
    }

    private String truncateFileName(String name, int maxLen) {
        if (name.length() <= maxLen) return name;
        int dot = name.lastIndexOf('.');
        if (dot != -1) {
            String ext = name.substring(dot);
            return name.substring(0, Math.max(3, maxLen - ext.length())) + ".." + ext;
        }
        return name.substring(0, maxLen - 2) + "..";
    }

    /**
     * Loads a selected local image into a Minecraft {@link DynamicTexture} for GUI previewing.
     */
    private void loadLocalPreviewTexture(File file) {
        if (this.minecraft == null) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            NativeImage nativeImage = NativeImage.read(fis);
            localPreviewIdentifier = Identifier.fromNamespaceAndPath("itemdatabase", "temp_preview_" + System.currentTimeMillis());

            DynamicTexture dynamicTexture = new DynamicTexture(localPreviewIdentifier::toString, nativeImage);
            this.minecraft.getTextureManager().register(localPreviewIdentifier, dynamicTexture);
        } catch (Exception e) {
            ItemDatabase.LOGGER.error("Failed to load local preview texture", e);
        }
    }

    private String getTagValue(AdminPayloadBuilder.DraftItem d, int slot) {
        return switch (slot) {
            case 0 -> d.tagPrimary;
            case 1 -> d.tagSecondary;
            case 2 -> d.tagTertiary;
            case 3 -> d.tagQuaternary;
            case 4 -> d.tagQuinary;
            case 5 -> d.tagSenary;
            case 6 -> d.tagSeptenary;
            default -> "None";
        };
    }

    private void setTagValue(AdminPayloadBuilder.DraftItem d, int slot, String val) {
        switch (slot) {
            case 0 -> d.tagPrimary = val;
            case 1 -> d.tagSecondary = val;
            case 2 -> d.tagTertiary = val;
            case 3 -> d.tagQuaternary = val;
            case 4 -> d.tagQuinary = val;
            case 5 -> d.tagSenary = val;
            case 6 -> d.tagSeptenary = val;
        }
        if (tagButtons[slot] != null) {
            tagButtons[slot].setMessage(Component.literal(val));
        }
    }

    private void cycleRarity(int delta) {
        rarityIndex = (rarityIndex + delta + ALLOWED_RARITIES.length) % ALLOWED_RARITIES.length;
        String nextRarity = ALLOWED_RARITIES[rarityIndex];
        draft.rarityHuman = nextRarity;
        if (rarityButton != null) {
            rarityButton.setMessage(Component.literal(nextRarity));
        }
    }

    private void cycleCrate(int delta) {
        if (availableCrates.isEmpty()) return;
        selectedCrateIndex = (selectedCrateIndex + delta + availableCrates.size()) % availableCrates.size();
        draft.crateId = availableCrates.get(selectedCrateIndex).id();
        if (categorySelectButton != null) {
            categorySelectButton.setMessage(Component.literal("Crate: " + availableCrates.get(selectedCrateIndex).name()));
        }
    }

    private void cycleGroup(int delta) {
        if (availableGroups.isEmpty()) return;
        selectedGroupIndex = (selectedGroupIndex + delta + availableGroups.size()) % availableGroups.size();
        draft.groupId = availableGroups.get(selectedGroupIndex).id();
        if (categorySelectButton != null) {
            categorySelectButton.setMessage(Component.literal("Group: " + availableGroups.get(selectedGroupIndex).name()));
        }
    }

    private void openLoreEditorModal() {
        this.isLoreModalOpen = true;
        this.isTagPickerOpen = false;

        String rawContent = "";
        if (draft.loreLines != null && !draft.loreLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < draft.loreLines.size(); i++) {
                sb.append(draft.loreLines.get(i).getString());
                if (i < draft.loreLines.size() - 1) sb.append("\n");
            }
            rawContent = sb.toString();
        } else if (draft.itemHuman != null && !draft.itemHuman.isBlank()) {
            rawContent = draft.itemHuman;
        } else if (draft.rawData != null && !draft.rawData.isBlank()) {
            List<Component> parsed = RawDataParser.parseLore(draft.rawData);
            if (parsed != null && !parsed.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parsed.size(); i++) {
                    sb.append(parsed.get(i).getString());
                    if (i < parsed.size() - 1) sb.append("\n");
                }
                rawContent = sb.toString();
            }
        }

        String cleanContent = rawContent
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)[§&][0-9a-fk-or]", "")
                .replaceAll("▶\\s*▶", "▶")
                .replaceAll("◀\\s*◀", "◀");

        this.loreEditBox.setValue(cleanContent);
        this.loreEditBox.setFocused(true);
    }

    private void saveAndCloseLoreModal() {
        String content = this.loreEditBox.getValue();
        draft.itemHuman = content.replaceAll("(?i)[§&][0-9a-fk-or]", "").trim();
        draft.loreLines = parseAndStyleLore(draft.itemHuman, draft.itemName);
        this.isLoreModalOpen = false;
    }

    private List<Component> parseAndStyleLore(String rawDesc, String itemName) {
        List<Component> result = new ArrayList<>();
        if (rawDesc == null || rawDesc.isBlank()) return result;

        String normalized = rawDesc
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?p>", "\n")
                .replaceAll("(?i)</?div>", "\n");

        if (!normalized.contains("\n") && normalized.contains("=====")) {
            normalized = normalized
                    .replaceAll("(?<=[^\\s=])\\s+(====+)", "\n$1")
                    .replaceAll("(====+)\\s+(?=[^\\s=])", "$1\n");
        }

        String[] lines = normalized.split("\n");
        for (String raw : lines) {
            String clean = raw.replaceAll("(?i)[§&][0-9a-fk-or]", "").trim();
            if (clean.isEmpty()) continue;

            if (clean.matches("^[\\u2550\\u2500=\\-_*]{4,}$")) {
                result.add(Component.literal("§8" + clean));
                continue;
            }

            String lower = clean.toLowerCase();

            if (lower.contains("crate") && (lower.contains("blossom") || lower.contains("▶") || lower.contains("►"))) {
                result.add(Component.literal("§7▶ §dBlossom §bCrate §7◀"));
            } else if (lower.startsWith("season")) {
                String stripped = clean.replaceAll("[❀*•]", "").trim();
                result.add(Component.literal("§7" + stripped.replaceFirst("(?i)season\\s*(\\d+):?\\s*(.*)", "Season $1: §6❀ §e$2") + " §6❀"));
            } else if (lower.startsWith("rarity:")) {
                String val = clean.substring(7).trim();
                result.add(Component.literal("§7Rarity: " + getRarityColor(val) + val));
            } else if (lower.startsWith("win chance:")) {
                String val = clean.substring(11).trim();
                result.add(Component.literal("§6Win Chance: §e" + val));
            } else if (raw.contains("&") || raw.contains("§")) {
                result.add(Component.literal(raw.replace('&', '§')));
            } else {
                result.add(Component.literal("§7" + clean));
            }
        }
        return result;
    }

    private String getRarityColor(String rarity) {
        if (rarity == null) return "§7";
        return switch (rarity.trim().toUpperCase()) {
            case "EXOTIC" -> "§c";
            case "LEGENDARY" -> "§6";
            case "MYTHIC" -> "§d";
            case "EPIC" -> "§5";
            case "RARE" -> "§b";
            case "UNCOMMON" -> "§a";
            case "COMMON" -> "§f";
            default -> "§e";
        };
    }

    /**
     * Updates in-memory client caches so that newly edited items appear immediately.
     */
    private void updateLocalCache(int itemId) {
        if (draft.isCrateItem) {
            var existing = DatabaseManager.CRATE_ITEMS.get(String.valueOf(itemId));
            if (existing != null) {
                String winPct = draft.winPercentage != null ? draft.winPercentage.replace("%", "").trim() : existing.winPercentage();

                List<String> tags = new ArrayList<>();
                if (draft.tagPrimary != null && !draft.tagPrimary.isBlank() && !draft.tagPrimary.equalsIgnoreCase("None")) tags.add(draft.tagPrimary);
                if (draft.tagSecondary != null && !draft.tagSecondary.isBlank() && !draft.tagSecondary.equalsIgnoreCase("None")) tags.add(draft.tagSecondary);
                if (draft.tagTertiary != null && !draft.tagTertiary.isBlank() && !draft.tagTertiary.equalsIgnoreCase("None")) tags.add(draft.tagTertiary);
                if (draft.tagQuaternary != null && !draft.tagQuaternary.isBlank() && !draft.tagQuaternary.equalsIgnoreCase("None")) tags.add(draft.tagQuaternary);
                if (draft.tagQuinary != null && !draft.tagQuinary.isBlank() && !draft.tagQuinary.equalsIgnoreCase("None")) tags.add(draft.tagQuinary);
                if (draft.tagSenary != null && !draft.tagSenary.isBlank() && !draft.tagSenary.equalsIgnoreCase("None")) tags.add(draft.tagSenary);
                if (draft.tagSeptenary != null && !draft.tagSeptenary.isBlank() && !draft.tagSeptenary.equalsIgnoreCase("None")) tags.add(draft.tagSeptenary);

                ItemEntry updated = new ItemEntry(
                        existing.id(),
                        draft.itemName,
                        draft.itemHuman,
                        existing.rawData(),
                        existing.imageType(),
                        winPct,
                        draft.rarityHuman,
                        draft.notes,
                        existing.groupId(),
                        String.valueOf(draft.crateId),
                        draft.iconUrl != null ? draft.iconUrl : existing.iconUrl(),
                        true,
                        tags,
                        0
                );

                DatabaseManager.CRATE_ITEMS.put(String.valueOf(itemId), updated);
                DatabaseManager.mapItemsToCrates();
            }
            DatabaseManager.fetchCrateItems().thenRun(DatabaseManager::mapItemsToCrates);
        } else {
            var existing = DatabaseManager.MISC_ITEMS.get(String.valueOf(itemId));
            if (existing != null) {
                ItemEntry updated = new ItemEntry(
                        existing.id(),
                        draft.itemName,
                        draft.itemHuman,
                        existing.rawData(),
                        existing.imageType(),
                        null,
                        draft.rarityHuman,
                        draft.notes,
                        String.valueOf(draft.groupId),
                        "",
                        draft.iconUrl != null ? draft.iconUrl : existing.iconUrl(),
                        false,
                        existing.tags(),
                        0
                );

                DatabaseManager.MISC_ITEMS.put(String.valueOf(itemId), updated);
                DatabaseManager.mapItemsToMiscGroups();
            }
            DatabaseManager.fetchMiscItems().thenRun(DatabaseManager::mapItemsToMiscGroups);
        }
    }

    /**
     * Refreshes the database caches asynchronously before transitioning
     * back to the admin item list screen so newly added or modified items appear immediately.
     */
    private void refreshCacheAndNavigate() {
        if (draft.isCrateItem) {
            DatabaseManager.fetchCrates()
                    .thenCompose(v -> DatabaseManager.fetchCrateItems())
                    .thenRun(() -> {
                        DatabaseManager.mapItemsToCrates();
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                this.minecraft.setScreen(this.parentScreen != null ? this.parentScreen : new AdminItemListScreen(null));
                            });
                        }
                    });
        } else {
            DatabaseManager.fetchMiscGroups()
                    .thenCompose(v -> DatabaseManager.fetchMiscItems())
                    .thenRun(() -> {
                        DatabaseManager.mapItemsToMiscGroups();
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                this.minecraft.setScreen(this.parentScreen != null ? this.parentScreen : new AdminItemListScreen(null));
                            });
                        }
                    });
        }
    }
}