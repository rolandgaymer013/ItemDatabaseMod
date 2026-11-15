package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.CollectionManager;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.LoadoutAnalyzer;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive Armor Set Showcase screen for BlossomCraft.
 * <p>
 * Displays a searchable directory of registered armor sets with completion filters,
 * an orbiting 3D mannequin wearing the selected set, clickable piece inspection slots,
 * aggregated set stats, full-set bonus descriptions, and one-click Fitting Room /
 * Comparison / Missing-Piece Wishlist actions.
 */
public class SetCatalogScreen extends Screen {

    /** Completion filter tabs for the left armor-set sidebar. */
    public enum SetFilter {
        ALL("All"),
        COMPLETED("4/4"),
        IN_PROGRESS("1-3/4"),
        UNOWNED("0/4");

        private final String label;
        SetFilter(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final Screen parent;
    private final int initialSetId;

    private final List<AdminApiClient.SetRow> filteredSets = new ArrayList<>();
    private final Map<Integer, Integer> cachedOwnedCounts = new HashMap<>();
    private final List<FormattedCharSequence> cachedTraitLines = new ArrayList<>();
    private int selectedSetId = -1;
    private SetFilter currentFilter = SetFilter.ALL;
    private int listScrollOffset = 0;
    private int listMaxScroll = 0;

    private EditBox searchBox;
    private PreviewDummyPlayer dummy;

    // Active set equipment & analysis
    private final Map<EquipmentSlot, ItemEntry> setEntriesBySlot = new EnumMap<>(EquipmentSlot.class);
    private final List<ItemEntry> orderedSetPieces = new ArrayList<>();
    private LoadoutAnalyzer.LoadoutSummary currentSummary =
            new LoadoutAnalyzer.LoadoutSummary(0, 0.0f, 1.0, 0, 0, List.of());

    // Mannequin orbit physics
    private float playerYaw = 0.0f;
    private float playerPitch = 0.0f;
    private float yawVelocity = 0.0f;
    private float pitchVelocity = 0.0f;
    private boolean autoRotate = true;
    private boolean isDraggingModel = false;
    private boolean wasMouseDown = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;

    // Right panel traits scroll
    private int traitsScrollOffset = 0;
    private int traitsMaxScroll = 0;

    private Button wishlistMissingBtn;
    private Button tryFittingRoomBtn;

    /** Bounds and click action for custom-rendered filter pills, list rows, and piece icons. */
    private record ClickTarget(int x, int y, int w, int h, Runnable action) {}
    private final List<ClickTarget> clickTargets = new ArrayList<>();

    /** Constructs the Armor Set Showcase defaulting to the first available set. */
    public SetCatalogScreen(Screen parent) {
        this(parent, -1);
    }

    /**
     * Constructs the Armor Set Showcase, optionally pre-selecting a specific set by ID.
     *
     * @param parent       Parent screen to return to upon closing.
     * @param initialSetId Target set primary key ID to select initially, or {@code -1} for default.
     */
    public SetCatalogScreen(Screen parent, int initialSetId) {
        super(Component.literal("Armor Set Showcase"));
        this.parent = parent;
        this.initialSetId = initialSetId;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private int getPanelX(int panelW) {
        int base = (this.width - panelW) / 2 + ModConfig.get().cardOffsetX;
        return Mth.clamp(base, 8, Math.max(8, this.width - panelW - 8));
    }

    private int getPanelY(int panelH) {
        int base = (this.height - panelH) / 2 - 10 + ModConfig.get().cardOffsetY;
        return Mth.clamp(base, 8, Math.max(8, this.height - panelH - 34));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            this.dummy = new PreviewDummyPlayer(mc);
        }

        int panelW = 470;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        this.searchBox = new EditBox(this.font, panelX + 10, panelY + 26, 122, 14, Component.literal("Search sets..."));
        this.searchBox.setHint(Component.literal("§8Search sets or perks..."));
        this.searchBox.setMaxLength(32);
        this.searchBox.setResponder(q -> {
            this.listScrollOffset = 0;
            refreshFilteredSets(false);
        });
        this.addRenderableWidget(this.searchBox);

        // Ensure sets are loaded if opened before preload completed
        if (DatabaseManager.ARMOR_SETS.isEmpty()) {
            DatabaseManager.fetchSets().thenRun(() -> {
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> refreshFilteredSets(true));
                }
            });
        } else {
            refreshFilteredSets(true);
        }

        // Bottom Action Bar
        int btnY = panelY + panelH + 6;
        int gap = 4;
        int wFit = 104;
        int wComp = 62;
        int wWish = 98;
        int wSpin = 62;
        int wReset = 46;
        int wBack = 44;
        int totalW = wFit + wComp + wWish + wSpin + wReset + wBack + (gap * 5);
        int startX = panelX + (panelW - totalW) / 2;

        this.tryFittingRoomBtn = Button.builder(Component.literal("§dTry in Fitting Room"), b -> openInFittingRoom())
                .bounds(startX, btnY, wFit, 20)
                .build();
        this.addRenderableWidget(this.tryFittingRoomBtn);

        this.addRenderableWidget(Button.builder(Component.literal("§bCompare"), b -> {
            AdminApiClient.SetRow active = getSelectedSet();
            if (active != null && this.minecraft != null) {
                ComparisonScreen.pinTarget(ComparisonScreen.CompareTarget.fromSet(active));
                this.minecraft.setScreen(new ComparisonScreen(this));
            }
        }).bounds(startX + wFit + gap, btnY, wComp, 20).build());

        this.wishlistMissingBtn = Button.builder(Component.literal("§6★ Wishlist Missing"), b -> wishlistMissingPieces())
                .bounds(startX + wFit + wComp + (gap * 2), btnY, wWish, 20)
                .build();
        this.addRenderableWidget(this.wishlistMissingBtn);

        this.addRenderableWidget(Button.builder(Component.literal(this.autoRotate ? "Spin: ON" : "Spin: OFF"), b -> {
            this.autoRotate = !this.autoRotate;
            b.setMessage(Component.literal(this.autoRotate ? "Spin: ON" : "Spin: OFF"));
        }).bounds(startX + wFit + wComp + wWish + (gap * 3), btnY, wSpin, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            this.playerYaw = 0.0f;
            this.playerPitch = 0.0f;
            this.yawVelocity = 0.0f;
            this.pitchVelocity = 0.0f;
        }).bounds(startX + wFit + wComp + wWish + wSpin + (gap * 4), btnY, wReset, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(startX + wFit + wComp + wWish + wSpin + wReset + (gap * 5), btnY, wBack, 20)
                .build());

        updateActionButtons();
    }

    /** Rebuilds the filtered set directory and caches piece ownership counts per set. */
    private void refreshFilteredSets(boolean applyInitialSelection) {
        this.cachedOwnedCounts.clear();
        for (AdminApiClient.SetRow set : DatabaseManager.ARMOR_SETS) {
            this.cachedOwnedCounts.put(set.id(), countOwnedPieces(set));
        }
        this.filteredSets.clear();
        String query = (this.searchBox != null) ? this.searchBox.getValue().trim().toLowerCase() : "";

        for (AdminApiClient.SetRow set : DatabaseManager.ARMOR_SETS) {
            int total = set.itemIds().size();
            int owned = this.cachedOwnedCounts.getOrDefault(set.id(), 0);

            if (currentFilter == SetFilter.COMPLETED && (total == 0 || owned < total)) continue;
            if (currentFilter == SetFilter.IN_PROGRESS && (owned == 0 || owned >= total)) continue;
            if (currentFilter == SetFilter.UNOWNED && owned > 0) continue;

            if (!query.isEmpty()) {
                boolean matchesName = set.name().toLowerCase().contains(query);
                boolean matchesDesc = set.description() != null && set.description().toLowerCase().contains(query);
                boolean matchesPiece = false;
                for (int id : set.itemIds()) {
                    ItemEntry item = DatabaseManager.findItemById(id);
                    if (item != null && item.getCleanName().toLowerCase().contains(query)) {
                        matchesPiece = true;
                        break;
                    }
                }
                if (!matchesName && !matchesDesc && !matchesPiece) continue;
            }

            this.filteredSets.add(set);
        }

        if (applyInitialSelection && this.initialSetId != -1) {
            this.selectedSetId = this.initialSetId;
        }

        boolean selectionStillVisible = this.filteredSets.stream().anyMatch(s -> s.id() == this.selectedSetId);
        if (!selectionStillVisible) {
            this.selectedSetId = this.filteredSets.isEmpty() ? -1 : this.filteredSets.get(0).id();
        }

        equipSelectedSet();
    }

    /** Returns the currently selected {@link AdminApiClient.SetRow}, or {@code null} if none. */
    private AdminApiClient.SetRow getSelectedSet() {
        for (AdminApiClient.SetRow set : this.filteredSets) {
            if (set.id() == this.selectedSetId) return set;
        }
        return null;
    }

    /** Counts how many pieces in the given set are owned (including "Repeat appearance" duplicates). */
    private int countOwnedPieces(AdminApiClient.SetRow set) {
        int owned = 0;
        for (int id : set.itemIds()) {
            ItemEntry entry = DatabaseManager.findItemById(id);
            if (entry != null && isPieceOrDuplicateOwned(entry)) {
                owned++;
            }
        }
        return owned;
    }

    /** O(1) ownership check via {@link CollectionManager#isOwned(ItemEntry)}. */
    private boolean isPieceOrDuplicateOwned(ItemEntry canonicalPiece) {
        return canonicalPiece != null && CollectionManager.isOwned(canonicalPiece);
    }

    /** Equips the selected set onto the 3D mannequin and pre-wraps all stat and trait lines. */
    private void equipSelectedSet() {
        this.setEntriesBySlot.clear();
        this.orderedSetPieces.clear();
        this.cachedTraitLines.clear();
        this.traitsScrollOffset = 0;

        if (this.dummy != null) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                this.dummy.setPreviewSlot(slot, ItemStack.EMPTY);
            }
        }

        int innerW = 200; // rightW (214) - 14
        AdminApiClient.SetRow set = getSelectedSet();
        if (set == null) {
            this.currentSummary = new LoadoutAnalyzer.LoadoutSummary(0, 0.0f, 1.0, 0, 0, List.of());
            for (Component tc : this.currentSummary.toTraitComponents(true)) {
                this.cachedTraitLines.addAll(this.font.split(tc, innerW - 4));
            }
            updateActionButtons();
            return;
        }

        List<LoadoutAnalyzer.SlotInput> analyzerInputs = new ArrayList<>();

        for (int id : set.itemIds()) {
            ItemEntry entry = DatabaseManager.findItemById(id);
            if (entry == null) continue;

            this.orderedSetPieces.add(entry);
            EquipmentSlot slot = entry.getTargetEquipmentSlot();
            ItemStack stack = RawDataParser.parseItemStack(entry);

            if (slot != null && !stack.isEmpty()) {
                this.setEntriesBySlot.put(slot, entry);
                if (this.dummy != null) {
                    this.dummy.setPreviewSlot(slot, stack.copy());
                }
                analyzerInputs.add(new LoadoutAnalyzer.SlotInput(
                        stack,
                        entry,
                        slot,
                        false,
                        getShortSlotName(slot)
                ));
            }
        }

        this.currentSummary = LoadoutAnalyzer.analyze(analyzerInputs);
        for (Component tc : this.currentSummary.toTraitComponents(true)) {
            this.cachedTraitLines.addAll(this.font.split(tc, innerW - 4));
        }
        updateActionButtons();
    }

    /** Returns a compact piece label for trait source attribution. */
    private String getShortSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "Helmet";
            case CHEST -> "Chestplate";
            case LEGS -> "Leggings";
            case FEET -> "Boots";
            default -> "";
        };
    }

    /** Updates active states and dynamic missing-piece counts on the bottom action buttons. */
    private void updateActionButtons() {
        AdminApiClient.SetRow set = getSelectedSet();
        if (this.tryFittingRoomBtn != null) {
            this.tryFittingRoomBtn.active = (set != null && !this.orderedSetPieces.isEmpty());
        }
        if (this.wishlistMissingBtn != null) {
            if (set == null) {
                this.wishlistMissingBtn.active = false;
                this.wishlistMissingBtn.setMessage(Component.literal("§7★ Wishlist Missing"));
            } else {
                int missingUnwishlisted = 0;
                for (ItemEntry entry : this.orderedSetPieces) {
                    if (!isPieceOrDuplicateOwned(entry) && !CollectionManager.isWishlisted(entry)) {
                        missingUnwishlisted++;
                    }
                }
                this.wishlistMissingBtn.active = (missingUnwishlisted > 0);
                this.wishlistMissingBtn.setMessage(Component.literal(
                        missingUnwishlisted > 0
                                ? "§6★ Wishlist Missing (" + missingUnwishlisted + ")"
                                : "§a✔ All Tracked"
                ));
            }
        }
    }

    /** Opens {@link FittingRoomScreen} pre-loaded with all armor pieces of the selected set. */
    private void openInFittingRoom() {
        if (this.minecraft == null || this.orderedSetPieces.isEmpty()) return;
        FittingRoomScreen fittingRoom = new FittingRoomScreen(this);
        fittingRoom.equipFullSet(this.orderedSetPieces);
        this.minecraft.setScreen(fittingRoom);
    }

    /** Adds all unowned, non-wishlisted pieces of the selected set to the player's wishlist. */
    private void wishlistMissingPieces() {
        if (this.orderedSetPieces.isEmpty()) return;
        boolean addedAny = false;
        for (ItemEntry entry : this.orderedSetPieces) {
            if (!isPieceOrDuplicateOwned(entry) && !CollectionManager.isWishlisted(entry)) {
                CollectionManager.toggleWishlist(entry);
                addedAny = true;
            }
        }
        if (addedAny && this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.1f));
        }
        AdminApiClient.SetRow activeSet = getSelectedSet();
        if (activeSet != null) {
            this.cachedOwnedCounts.put(activeSet.id(), countOwnedPieces(activeSet));
        }
        updateActionButtons();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int panelW = 470;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        // Left sidebar scroll
        if (mouseX >= panelX + 8 && mouseX <= panelX + 136 && mouseY >= panelY + 58 && mouseY <= panelY + panelH - 8) {
            if (this.listMaxScroll > 0) {
                this.listScrollOffset = Mth.clamp(this.listScrollOffset - (int) (verticalAmount * 14), 0, this.listMaxScroll);
                return true;
            }
        }

        // Right traits/details scroll
        if (mouseX >= panelX + 266 && mouseX <= panelX + panelW - 8 && mouseY >= panelY + 24 && mouseY <= panelY + panelH - 8) {
            if (this.traitsMaxScroll > 0) {
                this.traitsScrollOffset = Mth.clamp(this.traitsScrollOffset - (int) (verticalAmount * 12), 0, this.traitsMaxScroll);
                return true;
            }
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
    public boolean keyPressed(KeyEvent event) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                this.searchBox.setFocused(false);
                return true;
            }
            return this.searchBox.keyPressed(event);
        }

        if (event.key() == GLFW.GLFW_KEY_DOWN || event.key() == GLFW.GLFW_KEY_S) {
            stepSelection(1);
            return true;
        } else if (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_W) {
            stepSelection(-1);
            return true;
        }

        return super.keyPressed(event);
    }

    /** Cycles the active set selection forward or backward through {@code filteredSets}. */
    private void stepSelection(int delta) {
        if (this.filteredSets.isEmpty()) return;
        int curIdx = 0;
        for (int i = 0; i < this.filteredSets.size(); i++) {
            if (this.filteredSets.get(i).id() == this.selectedSetId) {
                curIdx = i;
                break;
            }
        }
        int nextIdx = (curIdx + delta + this.filteredSets.size()) % this.filteredSets.size();
        this.selectedSetId = this.filteredSets.get(nextIdx).id();
        equipSelectedSet();
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.searchBox != null && this.searchBox.isFocused()) {
            return this.searchBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (this.minecraft == null) return;
        this.clickTargets.clear();

        int panelW = 470;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        // Outer Frame
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, Component.literal("§6§lArmor Set Showcase & Full-Set Traits"), panelX + (panelW / 2), panelY + 9, 0xFFFFFFFF);

        // ============================================================
        // 1. LEFT SIDEBAR: Filter Tabs & Armor Set Directory
        // ============================================================
        int sideX = panelX + 8;
        int sideY = panelY + 24;
        int sideW = 126;
        int sideH = panelH - 32;

        graphics.fill(sideX, sideY, sideX + sideW, sideY + sideH, 0xFF0D0414);
        graphics.outline(sideX, sideY, sideW, sideH, 0xFF351242);

        // Filter Pills: [ All | 4/4 | 1-3/4 | 0/4 ]
        int filterY = sideY + 19;
        SetFilter[] filters = SetFilter.values();
        int pillW = (sideW - 6) / filters.length;
        for (int i = 0; i < filters.length; i++) {
            SetFilter f = filters[i];
            int px = sideX + 3 + (i * pillW);
            boolean active = (this.currentFilter == f);
            graphics.fill(px, filterY, px + pillW - 1, filterY + 12, active ? 0xFF692A82 : 0xFF1D0B28);
            graphics.outline(px, filterY, pillW - 1, 12, active ? 0xFFBA68C8 : 0xFF3D1650);
            graphics.centeredText(this.font, Component.literal((active ? "§f§l" : "§7") + f.label()), px + (pillW / 2), filterY + 2, 0xFFFFFFFF);

            this.clickTargets.add(new ClickTarget(px, filterY, pillW, 12, () -> {
                this.currentFilter = f;
                this.listScrollOffset = 0;
                refreshFilteredSets(false);
            }));
        }

        int listTop = filterY + 16;
        int listBottom = sideY + sideH - 4;
        int listViewH = listBottom - listTop;
        int rowH = 15;
        int totalListH = this.filteredSets.size() * rowH;
        this.listMaxScroll = Math.max(0, totalListH - listViewH);

        graphics.enableScissor(sideX + 2, listTop, sideX + sideW - 2, listBottom);
        int rowY = listTop - this.listScrollOffset;

        for (AdminApiClient.SetRow set : this.filteredSets) {
            if (rowY + rowH >= listTop && rowY <= listBottom) {
                boolean isSelected = (set.id() == this.selectedSetId);
                boolean hovered = mouseX >= sideX + 3 && mouseX <= sideX + sideW - 5
                        && mouseY >= Math.max(listTop, rowY) && mouseY <= Math.min(listBottom, rowY + rowH - 1);

                if (isSelected) {
                    graphics.fill(sideX + 3, rowY, sideX + sideW - 5, rowY + rowH - 1, 0x55BA68C8);
                    graphics.outline(sideX + 3, rowY, sideW - 8, rowH - 1, 0xFFBA68C8);
                } else if (hovered) {
                    graphics.fill(sideX + 3, rowY, sideX + sideW - 5, rowY + rowH - 1, 0x25FFFFFF);
                }

                int total = set.itemIds().size();
                int owned = this.cachedOwnedCounts.getOrDefault(set.id(), 0);
                String badgeColor = (total > 0 && owned >= total) ? "§a" : (owned > 0 ? "§e" : "§8");
                String badgeText = badgeColor + owned + "/" + total;
                int badgeW = this.font.width(badgeText);

                int maxNameW = sideW - badgeW - 14;
                String displayName = (isSelected ? "§f§l" : "§7") + set.name();
                if (this.font.width(displayName) > maxNameW) {
                    displayName = this.font.substrByWidth(Component.literal(displayName), maxNameW - 6).getString() + "..";
                }

                graphics.text(this.font, Component.literal(displayName), sideX + 6, rowY + 3, 0xFFFFFFFF);
                graphics.text(this.font, Component.literal(badgeText), sideX + sideW - badgeW - 8, rowY + 3, 0xFFFFFFFF);
            }

            if (rowY + rowH >= listTop && rowY <= listBottom) {
                int hitTop = Math.max(listTop, rowY);
                int hitH = Math.min(listBottom, rowY + rowH) - hitTop;
                this.clickTargets.add(new ClickTarget(sideX + 3, hitTop, sideW - 8, hitH, () -> {
                    this.selectedSetId = set.id();
                    equipSelectedSet();
                }));
            }
            rowY += rowH;
        }
        graphics.disableScissor();

        if (this.listMaxScroll > 0) {
            int barX = sideX + sideW - 4;
            int thumbH = Math.max(12, (listViewH * listViewH) / totalListH);
            int thumbY = listTop + ((listViewH - thumbH) * this.listScrollOffset) / this.listMaxScroll;
            graphics.fill(barX, listTop, barX + 2, listBottom, 0xFF220D30);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFF8E24AA);
        }

        // ============================================================
        // 2. CENTER STAGE: 3D Mannequin & 4-Piece Slot Strip
        // ============================================================
        int centerX = sideX + sideW + 6;
        int centerY = sideY;
        int centerW = 118;
        int centerH = sideH;

        long windowHandle = this.minecraft.getWindow().handle();
        boolean isMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        int mannequinH = 148;

        if (isMouseDown) {
            if (!wasMouseDown) {
                if (mouseX >= centerX && mouseX <= centerX + centerW
                        && mouseY >= centerY && mouseY <= centerY + mannequinH) {
                    isDraggingModel = true;
                    lastMouseX = mouseX;
                    lastMouseY = mouseY;
                    this.yawVelocity = 0.0f;
                    this.pitchVelocity = 0.0f;
                }
            } else if (isDraggingModel) {
                double dx = mouseX - lastMouseX;
                double dy = mouseY - lastMouseY;
                this.yawVelocity = (float) dx * 1.4f;
                this.pitchVelocity = (float) dy * 1.2f;
                this.playerYaw = (this.playerYaw + this.yawVelocity) % 360.0f;
                this.playerPitch = Mth.clamp(this.playerPitch + this.pitchVelocity, -85.0f, 85.0f);
                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
        } else {
            isDraggingModel = false;
            float dt = Math.min(this.minecraft.getDeltaTracker().getGameTimeDeltaTicks(), 2.0f);
            if (Math.abs(this.yawVelocity) > 0.05f) {
                this.playerYaw = (this.playerYaw + this.yawVelocity * dt) % 360.0f;
                this.yawVelocity *= (float) Math.pow(0.88, dt);
            } else {
                this.yawVelocity = 0.0f;
                if (this.autoRotate) {
                    this.playerYaw = (this.playerYaw + 1.10f * dt) % 360.0f;
                }
            }
            if (Math.abs(this.pitchVelocity) > 0.05f) {
                this.playerPitch = Mth.clamp(this.playerPitch + this.pitchVelocity * dt, -85.0f, 85.0f);
                this.pitchVelocity *= (float) Math.pow(0.85, dt);
            } else {
                this.pitchVelocity = 0.0f;
            }
        }
        wasMouseDown = isMouseDown;

        graphics.fill(centerX, centerY, centerX + centerW, centerY + mannequinH, 0xFF0D0414);
        graphics.outline(centerX, centerY, centerW, mannequinH, isDraggingModel ? 0xFFBA68C8 : 0xFF351242);

        if (this.dummy != null) {
            renderFreeOrbitDummy(graphics, centerX + 6, centerY + 6, centerX + centerW - 6, centerY + mannequinH - 6, 52);
        }

        // 4 Armor Piece Icons below Mannequin (Click any piece to jump to its card in ItemDatabaseScreen)
        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int slotBoxSize = 24;
        int slotGap = 4;
        int slotsTotalW = (slotBoxSize * 4) + (slotGap * 3);
        int slotStartX = centerX + (centerW - slotsTotalW) / 2;
        int slotY = centerY + mannequinH + 7;

        ItemEntry hoveredPiece = null;
        for (int i = 0; i < armorSlots.length; i++) {
            EquipmentSlot slot = armorSlots[i];
            ItemEntry piece = this.setEntriesBySlot.get(slot);
            int sx = slotStartX + i * (slotBoxSize + slotGap);

            boolean owned = piece != null && isPieceOrDuplicateOwned(piece);
            boolean wish = piece != null && CollectionManager.isWishlisted(piece);
            boolean slotHover = mouseX >= sx && mouseX <= sx + slotBoxSize && mouseY >= slotY && mouseY <= slotY + slotBoxSize;
            if (slotHover && piece != null) hoveredPiece = piece;

            int borderCol = owned ? 0xFF55FF55 : (wish ? 0xFFFFD700 : (slotHover ? 0xFFBA68C8 : 0xFF4A1A5E));
            graphics.fill(sx, slotY, sx + slotBoxSize, slotY + slotBoxSize, 0xFF0D0414);
            graphics.outline(sx, slotY, slotBoxSize, slotBoxSize, borderCol);

            if (piece != null && piece.iconUrl() != null && !piece.iconUrl().isBlank()) {
                String cacheKey = (piece.isCrateItem() ? "crate_" : "misc_") + piece.id();
                Identifier tex = TextureDownloader.getOrLoadTexture(piece.iconUrl(), cacheKey);
                if (tex != null) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, tex, sx + 3, slotY + 3, 0.0F, 0.0F, 18, 18, 18, 18);
                }
                if (owned) {
                    graphics.text(this.font, Component.literal("§a✔"), sx + 15, slotY + 14, 0xFFFFFFFF);
                } else if (wish) {
                    graphics.text(this.font, Component.literal("§6★"), sx + 15, slotY + 14, 0xFFFFFFFF);
                }

                this.clickTargets.add(new ClickTarget(sx, slotY, slotBoxSize, slotBoxSize, () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new ItemDatabaseScreen(piece));
                    }
                }));
            }
        }
        graphics.centeredText(this.font, Component.literal("§8Click piece to inspect"), centerX + (centerW / 2), slotY + 28, 0xFF888888);

        // ============================================================
        // 3. RIGHT PANEL: Full Set Bonus, Combined Stats & All Traits
        // ============================================================
        int rightX = centerX + centerW + 6;
        int rightY = sideY;
        int rightW = (panelX + panelW - 8) - rightX;
        int rightH = sideH;

        graphics.fill(rightX, rightY, rightX + rightW, rightY + rightH, 0xFF0D0414);
        graphics.outline(rightX, rightY, rightW, rightH, 0xFF351242);

        AdminApiClient.SetRow activeSet = getSelectedSet();
        if (activeSet != null) {
            int curY = rightY + 6;
            int innerW = rightW - 14;

            // Set Title & Origin Crate
            String originName = "Unknown";
            for (int id : activeSet.itemIds()) {
                CrateEntry crate = DatabaseManager.findCrateByItemId(id);
                if (crate != null) {
                    originName = crate.name();
                    break;
                }
            }
            graphics.text(this.font, Component.literal("§e§l" + activeSet.name()), rightX + 7, curY, 0xFFFFFFFF);
            curY += 10;
            graphics.text(this.font, Component.literal("§7Origin: §b" + originName), rightX + 7, curY, 0xFFFFFFFF);
            curY += 12;

            // Full Set Bonus Highlight Box
            String bonusText = (activeSet.description() != null && !activeSet.description().isBlank())
                    ? activeSet.description()
                    : "No full-set bonus text recorded.";
            List<FormattedCharSequence> bonusLines = this.font.split(Component.literal("§6★ Full Set Bonus: §f" + bonusText), innerW - 6);
            int bonusBoxH = Math.max(16, (bonusLines.size() * 9) + 6);
            graphics.fill(rightX + 6, curY, rightX + rightW - 6, curY + bonusBoxH, 0x33692A82);
            graphics.outline(rightX + 6, curY, rightW - 12, bonusBoxH, 0xFFFFAA00);

            int by = curY + 3;
            for (FormattedCharSequence line : bonusLines) {
                graphics.text(this.font, line, rightX + 9, by, 0xFFFFFFFF);
                by += 9;
            }
            curY += bonusBoxH + 6;

            // Combined 4-Piece Armor Stats
            graphics.text(this.font, Component.literal("§6§lComplete Set Totals"), rightX + 7, curY, 0xFFFFFFFF);
            curY += 10;
            graphics.text(this.font, Component.literal("§bArmor: §f+" + currentSummary.totalArmor()
                    + "   §9Toughness: §f+" + (int) currentSummary.totalToughness()), rightX + 7, curY, 0xFFFFFFFF);
            curY += 10;
            graphics.text(this.font, Component.literal("§aBonus Health: §f+" + currentSummary.totalBonusHealth() + " ❤"
                    + (currentSummary.totalAttackSpeedBonus() > 0 ? "   §eAtk Spd: §f+" + currentSummary.totalAttackSpeedBonus() + "%" : "")), rightX + 7, curY, 0xFFFFFFFF);
            curY += 12;

            // All Piece Traits (Scrollable)
            graphics.text(this.font, Component.literal("§e§lAll Piece Traits:"), rightX + 7, curY, 0xFFFFFFFF);
            curY += 10;

            int traitsTop = curY;
            int traitsBottom = rightY + rightH - 5;
            int traitsViewH = Math.max(10, traitsBottom - traitsTop);

            int totalTraitsH = this.cachedTraitLines.size() * 10;
            this.traitsMaxScroll = Math.max(0, totalTraitsH - traitsViewH);
            this.traitsScrollOffset = Mth.clamp(this.traitsScrollOffset, 0, this.traitsMaxScroll);

            graphics.enableScissor(rightX + 5, traitsTop, rightX + rightW - 5, traitsBottom);
            int ty = traitsTop - this.traitsScrollOffset;
            for (FormattedCharSequence line : this.cachedTraitLines) {
                if (ty + 9 >= traitsTop && ty <= traitsBottom) {
                    graphics.text(this.font, line, rightX + 7, ty, 0xFFFFFFFF);
                }
                ty += 10;
            }
            graphics.disableScissor();

            if (this.traitsMaxScroll > 0) {
                int barX = rightX + rightW - 4;
                int thumbH = Math.max(10, (traitsViewH * traitsViewH) / totalTraitsH);
                int thumbY = traitsTop + ((traitsViewH - thumbH) * this.traitsScrollOffset) / this.traitsMaxScroll;
                graphics.fill(barX, traitsTop, barX + 2, traitsBottom, 0xFF220D30);
                graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFFFAA00);
            }
        } else {
            graphics.centeredText(this.font, Component.literal("§7No armor sets match filter."), rightX + (rightW / 2), rightY + (rightH / 2), 0xFF888888);
        }

        // Render widgets (Search box & bottom action buttons)
        super.extractRenderState(graphics, mouseX, mouseY, a);

        // Tooltip for hovered armor piece icon
        if (hoveredPiece != null) {
            boolean owned = isPieceOrDuplicateOwned(hoveredPiece);
            boolean wish = CollectionManager.isWishlisted(hoveredPiece);
            String status = owned ? "§a✔ Owned" : (wish ? "§6★ Wishlisted" : "§7Missing");
            List<Component> tip = List.of(
                    hoveredPiece.getStyledComponent(),
                    Component.literal(status + " §8• Click to view item card")
            );
            graphics.setComponentTooltipForNextFrame(this.font, tip, mouseX, mouseY);
        }
    }

    /** Renders the 3D {@link PreviewDummyPlayer} inside the center showcase stage. */
    private void renderFreeOrbitDummy(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int size) {
        if (this.dummy == null) return;

        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf xRotation = new Quaternionf().rotateX(this.playerPitch * (float) (Math.PI / 180.0));
        rotation.mul(xRotation);

        Minecraft mc = Minecraft.getInstance();
        EntityRenderState renderState = mc.getEntityRenderDispatcher().getRenderer(this.dummy).createRenderState(this.dummy, 1.0f);

        if (renderState instanceof LivingEntityRenderState livingRenderState) {
            livingRenderState.bodyRot = 180.0F + this.playerYaw;
            livingRenderState.yRot = this.playerYaw;
            livingRenderState.xRot = (livingRenderState.pose != Pose.FALL_FLYING) ? -this.playerPitch : 0.0F;
            livingRenderState.boundingBoxWidth = livingRenderState.boundingBoxWidth / livingRenderState.scale;
            livingRenderState.boundingBoxHeight = livingRenderState.boundingBoxHeight / livingRenderState.scale;
            livingRenderState.scale = 1.0F;
        }

        float pivotY = (renderState.boundingBoxHeight / 2.0F) + 0.15F;
        Vector3f translation = new Vector3f(0.0F, pivotY, 0.0F);
        graphics.entity(renderState, size, translation, rotation, xRotation, x0, y0, x1, y1);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}