package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.AnimatedTexture;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Modal item picker screen used by the Fitting Room to browse, search, and equip
 * crate and miscellaneous server items into armor, hand, or passive charm/relic slots.
 */
public class ItemCatalogPickerScreen extends Screen {

    /** Pairs a reconstructed client {@link ItemStack} with its database {@link ItemEntry} metadata. */
    public record CatalogItem(ItemStack stack, ItemEntry entry) {}

    /** Per-slot pool cache keyed by {@code <SLOT>_<isPassiveOnly>_<isTopLeftOnly>}. */
    private static final java.util.Map<String, List<CatalogItem>> SLOT_POOL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private final Screen parent;
    private final EquipmentSlot targetSlot;
    private final BiConsumer<ItemStack, ItemEntry> onSelectCallback;
    private final List<CatalogItem> fullPool;
    private final List<CatalogItem> filteredPool = new ArrayList<>();

    private EditBox searchBox;
    private Button prevPageBtn;
    private Button nextPageBtn;

    private int currentPage = 0;
    private static final int COLUMNS = 6;
    private static final int ROWS = 4;
    private static final int ITEMS_PER_PAGE = COLUMNS * ROWS; // 24 items per page

    private final boolean isPassiveOnly;
    private final boolean isTopLeftOnly;

    /** Tracks the combined Crate + Misc item count so caches invalidate automatically when async fetches complete. */
    private static int lastCachedItemCount = -1;

    /** Full 5-argument constructor supporting standard gear slots, general passive slots, and top-left passive slots. */
    public ItemCatalogPickerScreen(Screen parent, EquipmentSlot targetSlot, boolean isPassiveOnly, boolean isTopLeftOnly, BiConsumer<ItemStack, ItemEntry> onSelectCallback) {
        super(Component.literal(isPassiveOnly ? (isTopLeftOnly ? "Select Top-Left Inv Item" : "Select Hotbar / Inv Item") : "Select Item: " + targetSlot.getName().toUpperCase()));
        this.parent = parent;
        this.targetSlot = targetSlot;
        this.isPassiveOnly = isPassiveOnly;
        this.isTopLeftOnly = isTopLeftOnly;
        this.onSelectCallback = onSelectCallback;
        this.fullPool = fetchDatabaseItemsForSlot(targetSlot);
    }

    /** 4-argument overload for passive hotbar/inventory charm and relic slots. */
    public ItemCatalogPickerScreen(Screen parent, EquipmentSlot targetSlot, boolean isPassiveOnly, BiConsumer<ItemStack, ItemEntry> onSelectCallback) {
        this(parent, targetSlot, isPassiveOnly, false, onSelectCallback);
    }

    /** 3-argument overload for standard armor and hand equipment slots. */
    public ItemCatalogPickerScreen(Screen parent, EquipmentSlot targetSlot, BiConsumer<ItemStack, ItemEntry> onSelectCallback) {
        this(parent, targetSlot, false, false, onSelectCallback);
    }

    /**
     * Builds (or retrieves from cache) the deduplicated list of wearable/equippable items valid for {@code slot}.
     * Uses a fast raw-JSON pre-filter before invoking {@link RawDataParser#parseItemStack(ItemEntry)}.
     */
    private List<CatalogItem> fetchDatabaseItemsForSlot(EquipmentSlot slot) {
        if (DatabaseManager.MISC_ITEMS.isEmpty() || DatabaseManager.CRATE_ITEMS.isEmpty()) {
            DatabaseManager.preloadDatabase();
        }

        List<ItemEntry> allEntries = DatabaseManager.getAllItems();
        if (allEntries.size() != lastCachedItemCount) {
            SLOT_POOL_CACHE.clear();
            lastCachedItemCount = allEntries.size();
        }

        String poolKey = slot.name() + "_" + this.isPassiveOnly + "_" + this.isTopLeftOnly;
        List<CatalogItem> cachedPool = SLOT_POOL_CACHE.get(poolKey);
        if (cachedPool != null) {
            return cachedPool;
        }

        List<CatalogItem> list = new ArrayList<>();

        // 1. Always offer an empty slot option to unequip
        list.add(new CatalogItem(ItemStack.EMPTY, null));

        java.util.Set<String> seenItems = new java.util.HashSet<>();

        for (ItemEntry entry : allEntries) {
            if (!isValidGearEntry(entry)) {
                continue;
            }

            // Deduplicate repeat appearances across multiple crates
            String cleanKey = entry.getCleanName().toLowerCase().trim();
            if (cleanKey.isBlank()) {
                cleanKey = entry.itemName().toLowerCase().trim();
            }
            if (!seenItems.add(cleanKey)) {
                continue;
            }

            String raw = entry.rawData();
            if (raw == null || raw.isBlank()) continue;

            // Fast pre-filter on raw JSON across ALL slots before expensive ItemStack parsing
            String rawLower = raw.toLowerCase();
            if (this.isPassiveOnly) {
                boolean hasPassiveTag = entry.hasTag("Hotbar") || entry.hasTag("Inventory") || entry.hasTag("Charm") || entry.hasTag("Relic");
                if (!hasPassiveTag) continue;
            } else if (slot == EquipmentSlot.HEAD) {
                if (!rawLower.contains("helmet") && !rawLower.contains("hat") && !rawLower.contains("crown")
                        && !rawLower.contains("hood") && !rawLower.contains("ears") && !rawLower.contains("mask")
                        && !rawLower.contains("cap") && !rawLower.contains("pumpkin") && !rawLower.contains("player_head")
                        && !rawLower.contains("wing") && !rawLower.contains("\"head\"")
                        && !entry.hasTag("Helmet") && !entry.hasTag("Hat") && !entry.hasTag("Armor") && !entry.hasTag("Wings")) {
                    continue;
                }
            } else if (slot == EquipmentSlot.CHEST) {
                if (!rawLower.contains("chestplate") && !rawLower.contains("elytra") && !rawLower.contains("tunic")
                        && !rawLower.contains("cape") && !rawLower.contains("\"chest\"")) {
                    continue;
                }
            } else if (slot == EquipmentSlot.LEGS) {
                if (!rawLower.contains("leggings") && !rawLower.contains("pants") && !rawLower.contains("\"legs\"")) {
                    continue;
                }
            } else if (slot == EquipmentSlot.FEET) {
                if (!rawLower.contains("boots") && !rawLower.contains("slipper") && !rawLower.contains("\"feet\"")) {
                    continue;
                }
            } else if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                boolean likelyHand = rawLower.contains("sword") || rawLower.contains("axe") || rawLower.contains("pickaxe")
                        || rawLower.contains("shovel") || rawLower.contains("hoe") || rawLower.contains("bow")
                        || rawLower.contains("trident") || rawLower.contains("mace") || rawLower.contains("shield")
                        || rawLower.contains("totem") || rawLower.contains("when held") || rawLower.contains("while held")
                        || rawLower.contains("in offhand to wear")
                        || rawLower.contains("wing") || rawLower.contains("\"offhand\"")
                        || entry.hasTag("Weapons") || entry.hasTag("Tools") || entry.hasTag("Wings")
                        || entry.hasTag("Offhand") || entry.hasTag("Shield") || entry.hasTag("Relic")
                        || entry.hasTag("Charm") || entry.hasTag("Artifact") || entry.hasTag("Star");
                if (!likelyHand) continue;
            }

            ItemStack stack = RawDataParser.parseItemStack(entry);
            if (stack == null || stack.isEmpty()) continue;

            // --- PASSIVE CHARM / RELIC SLOTS ---
            if (this.isPassiveOnly) {
                boolean hasPassiveTag = entry.hasTag("Hotbar") || entry.hasTag("Inventory") || entry.hasTag("Charm") || entry.hasTag("Relic");
                if (!hasPassiveTag) continue;

                boolean requiresTopLeft = rawLower.contains("top-left") || rawLower.contains("top left");

                if (this.isTopLeftOnly) {
                    if (requiresTopLeft) list.add(new CatalogItem(stack, entry));
                } else {
                    if (!requiresTopLeft) list.add(new CatalogItem(stack, entry));
                }
                continue;
            }

            // --- STANDARD GEAR SLOTS ---
            if (isExcludedBaseItem(stack, entry, slot)) {
                continue;
            }

            var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            EquipmentSlot itemSlot = equippable != null ? equippable.slot() : null;

            if (slot == EquipmentSlot.HEAD) {
                String nameLower = entry.getCleanName().toLowerCase();
                boolean isAnyWing = nameLower.contains("wing") || entry.hasTag("Wings");
                boolean isOffhandOnlyCosmetic = rawLower.contains("in offhand to wear") && !rawLower.contains("wear as helmet");
                if (isOffhandOnlyCosmetic) {
                    continue;
                }

                boolean isHelmetWearableWing = (stack.is(Items.PAPER) || stack.is(Items.FEATHER) || stack.is(Items.LEATHER_HORSE_ARMOR) || stack.is(Items.CARVED_PUMPKIN))
                        && isAnyWing
                        && (rawLower.contains("wear as helmet") || !rawLower.contains("in offhand to wear"));
                boolean isCustomHeadwear = ((stack.is(Items.PAPER) || stack.is(Items.CARVED_PUMPKIN) || stack.is(Items.PLAYER_HEAD))
                        && (nameLower.contains("helmet") || nameLower.contains("hat")
                        || nameLower.contains("crown") || nameLower.contains("hood")
                        || nameLower.contains("ears") || nameLower.contains("mask")
                        || entry.hasTag("Helmet") || entry.hasTag("Hat") || entry.hasTag("Armor")))
                        || isHelmetWearableWing;

                if ((itemSlot == EquipmentSlot.HEAD && !isAnyWing) || isCustomHeadwear) {
                    list.add(new CatalogItem(stack, entry));
                }
            } else if (slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
                if (itemSlot == slot) {
                    list.add(new CatalogItem(stack, entry));
                }
            } else if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                String nameLower = entry.getCleanName().toLowerCase();
                boolean grantsWhenHeld = rawLower.contains("when held") || rawLower.contains("while held") || rawLower.contains("in offhand to wear");
                boolean isCosmeticWing = (stack.is(Items.PAPER) || stack.is(Items.FEATHER) || stack.is(Items.LEATHER_HORSE_ARMOR) || stack.is(Items.CARVED_PUMPKIN))
                        && (nameLower.contains("wing") || entry.hasTag("Wings"));

                boolean isHandItem = isWeaponOrTool(stack, entry)
                        || grantsWhenHeld
                        || isCosmeticWing
                        || itemSlot == EquipmentSlot.OFFHAND
                        || stack.is(Items.SHIELD)
                        || stack.is(Items.TOTEM_OF_UNDYING)
                        || entry.hasTag("Offhand")
                        || entry.hasTag("Shield")
                        || entry.hasTag("Relic")
                        || entry.hasTag("Charm")
                        || entry.hasTag("Artifact")
                        || entry.hasTag("Star");

                if (isHandItem) {
                    list.add(new CatalogItem(stack, entry));
                }
            }
        }

        SLOT_POOL_CACHE.put(poolKey, list);
        return list;
    }

    /**
     * Filters out non-wearable database entries (vouchers, collection bundles, keys, spawners,
     * plushies, and decorative furniture) while allowing explicit equippable armor and wearable wings.
     */
    private boolean isValidGearEntry(ItemEntry entry) {
        if (entry == null) return false;

        String name = entry.itemName().toLowerCase();
        String group = entry.groupId() != null ? entry.groupId().toLowerCase() : "";
        String rawLower = entry.rawData() != null ? entry.rawData().toLowerCase() : "";

        // Reject loot containers, upgrade/exchange vouchers, and decorative furniture
        if (name.contains("voucher") || name.contains("plushie") || name.contains("infinite block")
                || name.contains("spawner") || name.contains("key") || name.contains("collection")) {
            return false;
        }

        if (rawLower.contains("sneak-right-click to open")
                || rawLower.contains("sneak-right-click to upgrade")
                || rawLower.contains("with an exchange")
                || rawLower.contains("furniture to decorate")) {
            return false;
        }

        // Always allow items with an explicit wearable equippable slot or wearable wing lore in RawData
        if (rawLower.contains("\"slot\":\"feet\"") || rawLower.contains("\"slot\": \"feet\"")
                || rawLower.contains("\"slot\":\"legs\"") || rawLower.contains("\"slot\": \"legs\"")
                || rawLower.contains("\"slot\":\"chest\"") || rawLower.contains("\"slot\": \"chest\"")
                || rawLower.contains("\"slot\":\"head\"") || rawLower.contains("\"slot\": \"head\"")
                || name.contains("wing")
                || rawLower.contains("in offhand to wear")
                || rawLower.contains("to wear as helmet")) {
            return true;
        }

        // Reject non-equipment classifications
        if (group.contains("voucher") || group.contains("furniture") || group.contains("plushie") || group.contains("pet")) {
            return false;
        }

        // Allow Hotbar, Inventory, Offhand, and Hand-held charms/relics/artifacts
        if (entry.hasTag("Hotbar") || entry.hasTag("Inventory") || entry.hasTag("Offhand")
                || entry.hasTag("Relic") || entry.hasTag("Charm") || entry.hasTag("Artifact") || entry.hasTag("Star")
                || rawLower.contains("when held") || rawLower.contains("while held")) {
            return true;
        }

        if (entry.hasTag("Voucher") || entry.hasTag("Furniture") || entry.hasTag("Plushie") || entry.hasTag("Block") || entry.hasTag("Pet")) {
            return false;
        }

        return true;
    }

    /**
     * Prevents non-wearable block items, name tags, and unmodelled paper items from appearing
     * in standard equipment slots while allowing custom 3D headwear, wings, and offhand cosmetics.
     */
    private boolean isExcludedBaseItem(ItemStack stack, ItemEntry entry, EquipmentSlot slot) {
        var item = stack.getItem();
        String nameLower = entry.getCleanName().toLowerCase();
        String rawLower = entry.rawData() != null ? entry.rawData().toLowerCase() : "";
        boolean isWing = nameLower.contains("wing") || entry.hasTag("Wings");
        boolean isHelmetWing = isWing && (rawLower.contains("wear as helmet") || !rawLower.contains("in offhand to wear"));
        boolean isOffhandCosmetic = isWing || rawLower.contains("in offhand to wear");

        // Disallow blocks except custom skull/pumpkin helmets or offhand cosmetics
        if (item instanceof net.minecraft.world.item.BlockItem) {
            if (item == Items.CARVED_PUMPKIN || item == Items.PLAYER_HEAD) {
                if (slot == EquipmentSlot.HEAD) {
                    return !entry.hasTag("Armor") && !entry.hasTag("Hat") && !entry.hasTag("Helmet") && !isHelmetWing;
                }
                if (slot == EquipmentSlot.OFFHAND || slot == EquipmentSlot.MAINHAND) {
                    return !isOffhandCosmetic;
                }
            }
            return true;
        }

        if (item == Items.NAME_TAG) {
            return true;
        }

        // Allow custom model data Paper items for HEAD headwear/dual-wear wings or OFFHAND/MAINHAND cosmetics
        if (item == Items.PAPER) {
            if (slot == EquipmentSlot.HEAD) {
                boolean isHeadwear = nameLower.contains("helmet") || nameLower.contains("hat")
                        || nameLower.contains("crown") || nameLower.contains("hood")
                        || nameLower.contains("ears") || nameLower.contains("mask")
                        || isHelmetWing
                        || entry.hasTag("Helmet") || entry.hasTag("Hat") || entry.hasTag("Armor");
                return !isHeadwear;
            }
            if (slot == EquipmentSlot.OFFHAND || slot == EquipmentSlot.MAINHAND) {
                return !isOffhandCosmetic;
            }
            return true;
        }

        return false;
    }

    /**
     * Checks whether a stack represents a weapon or tool via vanilla item tags,
     * {@link net.minecraft.core.component.DataComponents#TOOL}, or database metadata tags.
     */
    private boolean isWeaponOrTool(ItemStack stack, ItemEntry entry) {
        // 1. Tag-based checks (covers all vanilla and modded weapon/tool variants)
        if (stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES)
                || stack.is(ItemTags.BOW_ENCHANTABLE)
                || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)
                || stack.is(Items.TRIDENT)
                || stack.is(Items.MACE)) {
            return true;
        }

        // 2. Component check: items that have tool rules or weapon attributes
        if (stack.has(net.minecraft.core.component.DataComponents.TOOL)) {
            return true;
        }

        // 3. Database metadata tag fallback
        return entry.hasTag("Weapons") || entry.hasTag("Tools") || entry.hasTag("Sword") || entry.hasTag("Bow");
    }

    @Override
    protected void init() {
        super.init();

        int panelW = 180;
        int panelH = 180;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Search input
        this.searchBox = new EditBox(this.font, panelX + 16, panelY + 22, panelW - 32, 16, Component.literal("Search..."));
        this.searchBox.setResponder(this::updateSearchFilter);
        this.addRenderableWidget(this.searchBox);

        // Pagination buttons
        this.prevPageBtn = this.addRenderableWidget(Button.builder(Component.literal("<"), btn -> changePage(-1))
                .bounds(panelX + 16, panelY + panelH - 42, 20, 16)
                .build());

        this.nextPageBtn = this.addRenderableWidget(Button.builder(Component.literal(">"), btn -> changePage(1))
                .bounds(panelX + panelW - 36, panelY + panelH - 42, 20, 16)
                .build());

        // Cancel / Back button
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> this.onClose())
                .bounds(panelX + (panelW - 60) / 2, panelY + panelH - 22, 60, 16)
                .build());

        updateSearchFilter("");
    }

    /** Filters {@code fullPool} by clean item name whenever the search box text changes. */
    private void updateSearchFilter(String query) {
        this.filteredPool.clear();
        String lower = query.toLowerCase().trim();

        for (CatalogItem item : this.fullPool) {
            if (item.stack().isEmpty()) {
                if (lower.isEmpty() || "empty".contains(lower) || "none".contains(lower) || "unequip".contains(lower)) {
                    this.filteredPool.add(item);
                }
                continue;
            }

            String searchTarget = item.entry() != null ? item.entry().getCleanName().toLowerCase() : item.stack().getHoverName().getString().toLowerCase();
            if (searchTarget.contains(lower)) {
                this.filteredPool.add(item);
            }
        }
        this.currentPage = 0;
        updatePaginationButtons();
    }

    private int getMaxPages() {
        if (this.filteredPool.isEmpty()) return 1;
        return (int) Math.ceil((double) this.filteredPool.size() / ITEMS_PER_PAGE);
    }

    private void changePage(int delta) {
        int maxPages = getMaxPages();
        this.currentPage = Math.max(0, Math.min(this.currentPage + delta, maxPages - 1));
        updatePaginationButtons();
    }

    private void updatePaginationButtons() {
        int maxPages = getMaxPages();
        if (this.prevPageBtn != null) {
            this.prevPageBtn.active = this.currentPage > 0;
        }
        if (this.nextPageBtn != null) {
            this.nextPageBtn.active = this.currentPage < maxPages - 1;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) {
            changePage(-1);
            return true;
        } else if (scrollY < 0) {
            changePage(1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (event.button() == 0) {
            int panelW = 180;
            int panelH = 180;
            int panelX = (this.width - panelW) / 2;
            int panelY = (this.height - panelH) / 2;

            int gridStartX = panelX + 22;
            int gridStartY = panelY + 44;

            double mouseX = event.x();
            double mouseY = event.y();

            for (int i = 0; i < ITEMS_PER_PAGE; i++) {
                int index = (this.currentPage * ITEMS_PER_PAGE) + i;
                if (index >= this.filteredPool.size()) break;

                int col = i % COLUMNS;
                int row = i / COLUMNS;
                int slotX = gridStartX + (col * 22);
                int slotY = gridStartY + (row * 22);

                if (mouseX >= slotX && mouseX <= slotX + 20 && mouseY >= slotY && mouseY <= slotY + 20) {
                    CatalogItem selected = this.filteredPool.get(index);
                    this.onSelectCallback.accept(selected.stack(), selected.entry());
                    this.onClose();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelW = 180;
        int panelH = 180;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Background Modal
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        // Header Title
        graphics.centeredText(this.font, this.title, this.width / 2, panelY + 8, 0xFFFFAA00);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Page Indicator
        int maxPages = getMaxPages();
        String pageStr = (this.currentPage + 1) + " / " + maxPages;
        graphics.centeredText(this.font, Component.literal(pageStr), this.width / 2, panelY + panelH - 38, 0xFFCCCCCC);

        // Item Slot Grid
        int gridStartX = panelX + 22;
        int gridStartY = panelY + 44;

        CatalogItem hoveredItem = null;

        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int index = (this.currentPage * ITEMS_PER_PAGE) + i;
            if (index >= this.filteredPool.size()) break;

            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int slotX = gridStartX + (col * 22);
            int slotY = gridStartY + (row * 22);

            boolean isHovered = mouseX >= slotX && mouseX <= slotX + 20 && mouseY >= slotY && mouseY <= slotY + 20;

            graphics.fill(slotX, slotY, slotX + 20, slotY + 20, 0xFF0D0414);
            graphics.outline(slotX, slotY, 20, 20, isHovered ? 0xFFBA68C8 : 0xFF4A1A5E);

            CatalogItem item = this.filteredPool.get(index);
            if (!item.stack().isEmpty()) {
                boolean renderedCustom = false;
                if (item.entry() != null && item.entry().iconUrl() != null && !item.entry().iconUrl().isBlank()) {
                    String cacheKey = (item.entry().isCrateItem() ? "crate_" : "misc_") + item.entry().id();
                    Identifier spriteLoc = TextureDownloader.getOrLoadTexture(item.entry().iconUrl(), cacheKey);
                    AnimatedTexture anim = TextureDownloader.getTextureInfo(cacheKey);

                    if (spriteLoc != null) {
                        int maxDim = 16;
                        int imgW = (anim != null && anim.width() > 0) ? anim.width() : maxDim;
                        int imgH = (anim != null && anim.height() > 0) ? anim.height() : maxDim;

                        float scale = Math.min((float) maxDim / imgW, (float) maxDim / imgH);
                        int renderW = Math.max(1, Math.round(imgW * scale));
                        int renderH = Math.max(1, Math.round(imgH * scale));

                        int spriteX = slotX + 2 + (maxDim - renderW) / 2;
                        int spriteY = slotY + 2 + (maxDim - renderH) / 2;

                        graphics.blit(
                                RenderPipelines.GUI_TEXTURED,
                                spriteLoc,
                                spriteX,
                                spriteY,
                                0.0F,
                                0.0F,
                                renderW,
                                renderH,
                                renderW,
                                renderH
                        );
                        renderedCustom = true;
                    }
                }

                if (!renderedCustom) {
                    ItemStack displayStack = item.stack().is(Items.ELYTRA)
                            ? new ItemStack(Items.FEATHER)
                            : item.stack();
                    graphics.item(displayStack, slotX + 2, slotY + 2);
                }
            } else {
                graphics.centeredText(this.font, Component.literal("§c✕"), slotX + 10, slotY + 6, 0xFFFF5555);
            }

            if (isHovered) {
                hoveredItem = item;
            }
        }

        // Clean Tooltip Rendering using RawDataParser
        if (hoveredItem != null && !hoveredItem.stack().isEmpty()) {
            List<Component> tooltipLines = new ArrayList<>();

            if (hoveredItem.entry() != null) {
                Component titleComp = RawDataParser.parseCustomName(hoveredItem.entry().rawData(), hoveredItem.entry().itemName());
                tooltipLines.add(titleComp);
                tooltipLines.addAll(RawDataParser.parseEnchantments(hoveredItem.entry().rawData()));
                tooltipLines.addAll(RawDataParser.parseLore(hoveredItem.entry().rawData()));
            } else {
                tooltipLines.add(hoveredItem.stack().getHoverName());
            }

            List<ClientTooltipComponent> components = tooltipLines.stream()
                    .map(Component::getVisualOrderText)
                    .map(ClientTooltipComponent::create)
                    .toList();

            graphics.tooltip(this.font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        } else if (hoveredItem != null && hoveredItem.stack().isEmpty()) {
            List<ClientTooltipComponent> emptyTooltip = List.of(
                    ClientTooltipComponent.create(Component.literal("§cUnequip Slot (Empty)").getVisualOrderText())
            );
            graphics.tooltip(this.font, emptyTooltip, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}