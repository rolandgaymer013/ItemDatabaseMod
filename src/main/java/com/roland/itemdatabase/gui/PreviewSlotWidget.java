package com.roland.itemdatabase.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class PreviewSlotWidget extends AbstractWidget {

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

        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        var lines = net.minecraft.client.gui.screens.Screen.getTooltipFromItem(client, this.itemStack);

        if (lines.isEmpty()) {
            this.setTooltip(null);
            return;
        }

        // Join tooltip lines with newlines so vanilla builds a multi-line formatted Tooltip
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
                this.playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
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

        // Render item icon inside slot
        if (this.itemStack != null && !this.itemStack.isEmpty()) {
            graphics.item(this.itemStack, this.getX() + 2, this.getY() + 2);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        this.defaultButtonNarrationText(narrationElementOutput);
    }

}