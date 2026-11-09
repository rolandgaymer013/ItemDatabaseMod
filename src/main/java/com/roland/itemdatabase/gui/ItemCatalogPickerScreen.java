package com.roland.itemdatabase.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ItemCatalogPickerScreen extends Screen {

    private final Screen parent;
    private final EquipmentSlot targetSlot;
    private final Consumer<ItemStack> onSelectCallback;
    private final List<ItemStack> fullPool;
    private final List<ItemStack> filteredPool = new ArrayList<>();

    private EditBox searchBox;
    private int scrollOffset = 0;
    private static final int COLUMNS = 6;
    private static final int ROWS = 4;
    private static final int ITEMS_PER_PAGE = COLUMNS * ROWS;

    public ItemCatalogPickerScreen(Screen parent, EquipmentSlot targetSlot, Consumer<ItemStack> onSelectCallback) {
        super(Component.literal("Select Item: " + targetSlot.getName().toUpperCase()));
        this.parent = parent;
        this.targetSlot = targetSlot;
        this.onSelectCallback = onSelectCallback;
        this.fullPool = MockServerItems.getTestPoolForSlot(targetSlot);
    }

    @Override
    protected void init() {
        super.init();

        int panelW = 180;
        int panelH = 170;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Search bar
        this.searchBox = new EditBox(this.font, panelX + 16, panelY + 24, panelW - 32, 16, Component.literal("Search..."));
        this.searchBox.setResponder(this::updateSearchFilter);
        this.addRenderableWidget(this.searchBox);

        // Cancel / Back Button
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> this.onClose())
                .bounds(panelX + (panelW - 60) / 2, panelY + panelH - 24, 60, 18)
                .build());

        updateSearchFilter("");
    }

    private void updateSearchFilter(String query) {
        this.filteredPool.clear();
        String lower = query.toLowerCase().trim();

        for (ItemStack stack : this.fullPool) {
            if (stack.isEmpty()) {
                if (lower.isEmpty() || "empty".contains(lower) || "none".contains(lower)) {
                    this.filteredPool.add(stack);
                }
                continue;
            }

            String name = stack.getHoverName().getString().toLowerCase();
            if (name.contains(lower)) {
                this.filteredPool.add(stack);
            }
        }
        this.scrollOffset = 0;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (event.button() == 0) { // Left click
            int panelW = 180;
            int panelH = 170;
            int panelX = (this.width - panelW) / 2;
            int panelY = (this.height - panelH) / 2;

            int gridStartX = panelX + 22;
            int gridStartY = panelY + 46;

            double mouseX = event.x();
            double mouseY = event.y();

            for (int i = 0; i < ITEMS_PER_PAGE; i++) {
                int index = this.scrollOffset * COLUMNS + i;
                if (index >= this.filteredPool.size()) break;

                int col = i % COLUMNS;
                int row = i / COLUMNS;
                int slotX = gridStartX + (col * 22);
                int slotY = gridStartY + (row * 22);

                if (mouseX >= slotX && mouseX <= slotX + 20 && mouseY >= slotY && mouseY <= slotY + 20) {
                    ItemStack selected = this.filteredPool.get(index);
                    this.onSelectCallback.accept(selected);
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
        int panelH = 170;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Background Modal
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        // Header Title
        graphics.centeredText(this.font, this.title, this.width / 2, panelY + 10, 0xFFFFAA00);

        // Render widgets (Search bar and Cancel button)
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Grid slots
        int gridStartX = panelX + 22;
        int gridStartY = panelY + 46;

        ItemStack hoveredItem = null;

        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int index = this.scrollOffset * COLUMNS + i;
            if (index >= this.filteredPool.size()) break;

            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int slotX = gridStartX + (col * 22);
            int slotY = gridStartY + (row * 22);

            boolean isHovered = mouseX >= slotX && mouseX <= slotX + 20 && mouseY >= slotY && mouseY <= slotY + 20;

            graphics.fill(slotX, slotY, slotX + 20, slotY + 20, 0xFF0D0414);
            graphics.outline(slotX, slotY, 20, 20, isHovered ? 0xFFBA68C8 : 0xFF4A1A5E);

            ItemStack stack = this.filteredPool.get(index);
            if (!stack.isEmpty()) {
                graphics.item(stack, slotX + 2, slotY + 2);
            }

            if (isHovered && !stack.isEmpty()) {
                hoveredItem = stack;
            }
        }

        // Tooltip rendering on top of the grid
        if (hoveredItem != null && this.minecraft != null) {
            var lines = Screen.getTooltipFromItem(this.minecraft, hoveredItem);
            List<ClientTooltipComponent> components = lines.stream()
                    .map(Component::getVisualOrderText)
                    .map(ClientTooltipComponent::create)
                    .toList();
            graphics.tooltip(this.font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}