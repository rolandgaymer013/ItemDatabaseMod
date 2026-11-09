package com.roland.itemdatabase.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.RenderPipelines;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class PreviewSlotWidget extends AbstractWidget {

    public static final Identifier EMPTY_SLOT_HELMET = Identifier.fromNamespaceAndPath("minecraft", "container/slot/helmet");
    public static final Identifier EMPTY_SLOT_CHESTPLATE = Identifier.fromNamespaceAndPath("minecraft", "container/slot/chestplate");
    public static final Identifier EMPTY_SLOT_LEGGINGS = Identifier.fromNamespaceAndPath("minecraft", "container/slot/leggings");
    public static final Identifier EMPTY_SLOT_BOOTS = Identifier.fromNamespaceAndPath("minecraft", "container/slot/boots");
    public static final Identifier EMPTY_SLOT_SHIELD = Identifier.fromNamespaceAndPath("minecraft", "container/slot/shield");
    public static final Identifier EMPTY_SLOT_SWORD = Identifier.fromNamespaceAndPath("minecraft", "container/slot/sword");

    private final EquipmentSlot slot;
    private ItemStack itemStack;
    private final Consumer<PreviewSlotWidget> onClickAction;

    public PreviewSlotWidget(int x, int y, EquipmentSlot slot, ItemStack defaultStack, Consumer<PreviewSlotWidget> onClickAction) {
        super(x, y, 20, 20, Component.literal(slot.getName()));
        this.slot = slot;
        this.itemStack = defaultStack;
        this.onClickAction = onClickAction;
        updateItemTooltip();
    }

    public EquipmentSlot getSlot() {
        return this.slot;
    }

    public ItemStack getItemStack() {
        return this.itemStack;
    }

    public void setItemStack(ItemStack stack) {
        this.itemStack = stack;
        updateItemTooltip();
    }

    private void updateItemTooltip() {
        if (this.itemStack == null || this.itemStack.isEmpty()) {
            this.setTooltip(null);
            return;
        }

        Minecraft client = Minecraft.getInstance();
        var lines = net.minecraft.client.gui.screens.Screen.getTooltipFromItem(client, this.itemStack);

        if (lines.isEmpty()) {
            this.setTooltip(null);
            return;
        }

        Component combined = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            combined = Component.empty().append(combined).append(CommonComponents.NEW_LINE).append(lines.get(i));
        }

        this.setTooltip(Tooltip.create(combined));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        if (this.active && this.visible && this.isHovered()) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                if (this.onClickAction != null) {
                    this.onClickAction.accept(this);
                }
                return true;
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // Slot background box
        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, 0xFF0D0414);

        // Slot border: highlight if hovered
        int borderColor = this.isHovered() ? 0xFFBA68C8 : 0xFF4A1A5E;
        graphics.outline(this.getX(), this.getY(), this.width, this.height, borderColor);

        // Render equipped item icon or translucent ghost silhouette
        if (this.itemStack != null && !this.itemStack.isEmpty()) {
            graphics.item(this.itemStack, this.getX() + 2, this.getY() + 2);
        } else {
            if (this.slot == EquipmentSlot.MAINHAND) {
                // Ghost sword icon fallback
                graphics.item(new ItemStack(net.minecraft.world.item.Items.IRON_SWORD), this.getX() + 2, this.getY() + 2);
                // Dark tint overlay to make it look like a ghost silhouette
                graphics.fill(this.getX() + 2, this.getY() + 2, this.getX() + 18, this.getY() + 18, 0x990D0414);
            } else {
                Identifier emptyIcon = getEmptySlotTexture();
                if (emptyIcon != null) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, emptyIcon, this.getX() + 2, this.getY() + 2, 16, 16);
                }
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }

    public Identifier getEmptySlotTexture() {
        return switch (this.slot) {
            case HEAD -> EMPTY_SLOT_HELMET;
            case CHEST -> EMPTY_SLOT_CHESTPLATE;
            case LEGS -> EMPTY_SLOT_LEGGINGS;
            case FEET -> EMPTY_SLOT_BOOTS;
            case OFFHAND -> EMPTY_SLOT_SHIELD;
            default -> null;
        };
    }
}