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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class PreviewSlotWidget extends AbstractWidget {

    private final EquipmentSlot slot;
    private final Consumer<PreviewSlotWidget> onClick;
    private ItemStack itemStack = ItemStack.EMPTY;
    private ItemEntry currentEntry;

    public PreviewSlotWidget(int x, int y, EquipmentSlot slot, ItemStack initialItem, ItemEntry initialEntry, Consumer<PreviewSlotWidget> onClick) {
        super(x, y, 20, 20, Component.literal(slot.getName()));
        this.slot = slot;
        this.itemStack = initialItem != null ? initialItem : ItemStack.EMPTY;
        this.currentEntry = initialEntry;
        this.onClick = onClick;
    }

    public PreviewSlotWidget(int x, int y, EquipmentSlot slot, ItemStack initialItem, Consumer<PreviewSlotWidget> onClick) {
        this(x, y, slot, initialItem, null, onClick);
    }

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

    public void setItem(ItemStack stack, ItemEntry entry) {
        this.itemStack = stack != null ? stack : ItemStack.EMPTY;
        this.currentEntry = entry;
    }

    public ItemEntry getItemEntry() {
        return this.currentEntry;
    }

    public void setItemStack(ItemStack stack) {
        setItem(stack, null);
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

            // Fallback lookup by item name if entry wasn't passed directly
            if (this.currentEntry == null) {
                this.currentEntry = DatabaseManager.findItemByName(this.itemStack.getHoverName().getString());
            }

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
                graphics.item(this.itemStack, x + 2, y + 2);
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

    public void renderTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.getX();
        int y = this.getY();
        boolean hovered = mouseX >= x && mouseX <= x + this.width && mouseY >= y && mouseY <= y + this.height;
        if (!hovered || this.itemStack == null || this.itemStack.isEmpty()) {
            return;
        }

        // Ensure currentEntry is found if it was equipped without one
        if (this.currentEntry == null) {
            this.currentEntry = DatabaseManager.findItemByName(this.itemStack.getHoverName().getString());
        }

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