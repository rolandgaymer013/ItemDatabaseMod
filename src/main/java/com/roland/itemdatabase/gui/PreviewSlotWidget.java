package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.AnimatedTexture;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive 20x20 equipment slot widget displayed in the Fitting Room.
 * Renders the equipped item's downloaded icon (or 3D/vanilla item fallback),
 * displays slot-specific placeholder glyphs when empty, and renders rich tooltips on hover.
 */
public class PreviewSlotWidget extends AbstractWidget {

    private final EquipmentSlot slot;
    private final Consumer<PreviewSlotWidget> onClick;
    private ItemStack itemStack = ItemStack.EMPTY;
    private ItemEntry currentEntry;
    private boolean entryLookupAttempted = false;

    /** Full constructor accepting both an {@link ItemStack} and its paired database {@link ItemEntry}. */
    public PreviewSlotWidget(int x, int y, EquipmentSlot slot, ItemStack initialItem, ItemEntry initialEntry, Consumer<PreviewSlotWidget> onClick) {
        super(x, y, 20, 20, Component.literal(slot.getName()));
        this.slot = slot;
        this.itemStack = initialItem != null ? initialItem : ItemStack.EMPTY;
        this.currentEntry = initialEntry;
        this.entryLookupAttempted = (initialEntry != null);
        this.onClick = onClick;
    }

    /** Convenience constructor when only an {@link ItemStack} is available initially. */
    public PreviewSlotWidget(int x, int y, EquipmentSlot slot, ItemStack initialItem, Consumer<PreviewSlotWidget> onClick) {
        this(x, y, slot, initialItem, null, onClick);
    }

    /** Convenience constructor creating an empty equipment slot. */
    public PreviewSlotWidget(int x, int y, EquipmentSlot slot, Consumer<PreviewSlotWidget> onClick) {
        this(x, y, slot, ItemStack.EMPTY, null, onClick);
    }

    public EquipmentSlot getSlot() {
        return this.slot;
    }

    public ItemStack getItemStack() {
        return this.itemStack;
    }

    public ItemEntry getCurrentEntry() {
        return this.currentEntry;
    }

    public ItemEntry getItemEntry() {
        return this.currentEntry;
    }

    /** Updates both the displayed {@link ItemStack} and its paired {@link ItemEntry} metadata. */
    public void setItem(ItemStack stack, ItemEntry entry) {
        this.itemStack = stack != null ? stack : ItemStack.EMPTY;
        this.currentEntry = entry;
        this.entryLookupAttempted = (entry != null);
    }

    /** Updates the displayed {@link ItemStack} without explicit database metadata. */
    public void setItemStack(ItemStack stack) {
        setItem(stack, null);
    }

    /** Lazily resolves the matching {@link ItemEntry} once if the stack was equipped without metadata. */
    private void resolveEntryIfNeeded() {
        if (this.currentEntry == null && !this.entryLookupAttempted && !this.itemStack.isEmpty()) {
            this.entryLookupAttempted = true;
            this.currentEntry = DatabaseManager.findItemByName(this.itemStack.getHoverName().getString());
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (event.button() == 0 && this.isHovered()) {
            if (this.onClick != null) {
                this.onClick.accept(this);
            }
            return true;
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        boolean hovered = this.isHovered();

        int bg = hovered ? 0xDD2A1038 : 0xAA14071D;
        int border = hovered ? 0xFFBA68C8 : 0xFF4A1A5E;

        graphics.fill(x, y, x + this.width, y + this.height, bg);
        graphics.outline(x, y, this.width, this.height, border);

        if (!this.itemStack.isEmpty()) {
            boolean renderedCustom = false;
            resolveEntryIfNeeded();

            if (this.currentEntry != null && this.currentEntry.iconUrl() != null && !this.currentEntry.iconUrl().isBlank()) {
                String cacheKey = (this.currentEntry.isCrateItem() ? "crate_" : "misc_") + this.currentEntry.id();
                Identifier spriteLoc = TextureDownloader.getOrLoadTexture(this.currentEntry.iconUrl(), cacheKey);
                AnimatedTexture anim = TextureDownloader.getTextureInfo(cacheKey);

                if (spriteLoc != null) {
                    int maxDim = 16;
                    int imgW = (anim != null && anim.width() > 0) ? anim.width() : maxDim;
                    int imgH = (anim != null && anim.height() > 0) ? anim.height() : maxDim;

                    float scale = Math.min((float) maxDim / imgW, (float) maxDim / imgH);
                    int renderW = Math.max(1, Math.round(imgW * scale));
                    int renderH = Math.max(1, Math.round(imgH * scale));

                    int spriteX = x + 2 + (maxDim - renderW) / 2;
                    int spriteY = y + 2 + (maxDim - renderH) / 2;

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
                ItemStack displayStack = this.itemStack.is(Items.ELYTRA)
                        ? new ItemStack(Items.FEATHER)
                        : this.itemStack;
                graphics.item(displayStack, x + 2, y + 2);
            }
        } else {
            String icon = switch (this.slot) {
                case HEAD -> "⛊";
                case CHEST -> "👕";
                case LEGS -> "👖";
                case FEET -> "👢";
                case MAINHAND -> "⚔";
                case OFFHAND -> "🛡";
                default -> "•";
            };
            graphics.centeredText(Minecraft.getInstance().font, Component.literal("§8" + icon), x + (this.width / 2), y + 6, 0xFF666666);
        }
    }

    /**
     * Renders the full formatted tooltip for the equipped item when the cursor hovers over this slot.
     * Reconstructs gradient names, enchantments, and lore from raw database JSON when available.
     */
    public void renderTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.getX();
        int y = this.getY();
        boolean hovered = mouseX >= x && mouseX <= x + this.width && mouseY >= y && mouseY <= y + this.height;
        if (!hovered || this.itemStack == null || this.itemStack.isEmpty()) {
            return;
        }

        resolveEntryIfNeeded();

        List<Component> tooltipLines = new ArrayList<>();

        if (this.currentEntry != null && this.currentEntry.rawData() != null && !this.currentEntry.rawData().isBlank()) {
            // 1. Custom styled name
            Component titleComp = RawDataParser.parseCustomName(this.currentEntry.rawData(), this.currentEntry.itemName());
            tooltipLines.add(titleComp);

            // 2. Enchantments
            tooltipLines.addAll(RawDataParser.parseEnchantments(this.currentEntry.rawData()));

            // 3. Full Lore lines
            tooltipLines.addAll(RawDataParser.parseLore(this.currentEntry.rawData()));
        } else {
            // Fallback for vanilla or copied inventory gear
            tooltipLines.add(this.itemStack.getHoverName());
            var lore = this.itemStack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore != null) {
                tooltipLines.addAll(lore.lines());
            }
        }

        List<ClientTooltipComponent> components = tooltipLines.stream()
                .map(Component::getVisualOrderText)
                .map(ClientTooltipComponent::create)
                .toList();

        graphics.tooltip(
                Minecraft.getInstance().font,
                components,
                mouseX,
                mouseY,
                DefaultTooltipPositioner.INSTANCE,
                null
        );
    }
}