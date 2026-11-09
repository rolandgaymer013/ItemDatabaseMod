package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.PresetManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PresetScreen extends Screen {

    private final FittingRoomScreen parent;
    private final Map<EquipmentSlot, ItemStack> currentLoadout;
    private final java.util.function.Consumer<java.util.Map<EquipmentSlot, ItemStack>> onLoadCallback;

    private final List<String> presetList = new ArrayList<>();
    private EditBox presetNameBox;
    private int selectedIndex = -1;

    public PresetScreen(FittingRoomScreen parent, java.util.Map<EquipmentSlot, ItemStack> currentLoadout, java.util.function.Consumer<java.util.Map<EquipmentSlot, ItemStack>> onLoadCallback) {
        super(Component.literal("Loadout Presets"));
        this.parent = parent;
        this.currentLoadout = currentLoadout;
        this.onLoadCallback = onLoadCallback;
    }

    @Override
    protected void init() {
        super.init();
        refreshPresets();

        int panelW = 200;
        int panelH = 190;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Name input box for saving new preset
        this.presetNameBox = new EditBox(this.font, panelX + 16, panelY + 24, panelW - 32, 16, Component.literal("Preset Name"));
        this.addRenderableWidget(this.presetNameBox);

        // Save Button
        this.addRenderableWidget(Button.builder(Component.literal("Save New"), btn -> {
            String name = this.presetNameBox.getValue().trim();
            if (!name.isEmpty()) {
                PresetManager.savePreset(name, this.currentLoadout);
                this.presetNameBox.setValue("");
                refreshPresets();
                if (this.minecraft != null) {
                    this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
                }
            }
        }).bounds(panelX + 16, panelY + 44, 78, 18).build());

        // Delete Button
        this.addRenderableWidget(Button.builder(Component.literal("Delete"), btn -> {
            if (selectedIndex >= 0 && selectedIndex < presetList.size()) {
                PresetManager.deletePreset(presetList.get(selectedIndex));
                selectedIndex = -1;
                refreshPresets();
                if (this.minecraft != null) {
                    this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f));
                }
            }
        }).bounds(panelX + 104, panelY + 44, 78, 18).build());

        // Load Selected Button
        this.addRenderableWidget(Button.builder(Component.literal("Load Preset"), btn -> {
            if (selectedIndex >= 0 && selectedIndex < presetList.size()) {
                var loaded = PresetManager.loadPreset(presetList.get(selectedIndex));
                this.onLoadCallback.accept(loaded);
                this.onClose();
            }
        }).bounds(panelX + 16, panelY + panelH - 24, 78, 18).build());

        // Close Button
        this.addRenderableWidget(Button.builder(Component.literal("Back"), btn -> this.onClose())
                .bounds(panelX + 104, panelY + panelH - 24, 78, 18)
                .build());
    }

    private void refreshPresets() {
        this.presetList.clear();
        this.presetList.addAll(PresetManager.listPresets());
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (event.button() == 0) { // Left click
            int panelW = 200;
            int panelX = (this.width - panelW) / 2;
            int panelY = (this.height - 190) / 2;

            int listStartY = panelY + 68;
            double mouseX = event.x();
            double mouseY = event.y();

            for (int i = 0; i < Math.min(presetList.size(), 5); i++) {
                int itemY = listStartY + (i * 18);
                if (mouseX >= panelX + 16 && mouseX <= panelX + panelW - 16 && mouseY >= itemY && mouseY <= itemY + 16) {
                    this.selectedIndex = i;
                    this.presetNameBox.setValue(presetList.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, bl);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelW = 200;
        int panelH = 190;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Modal Background
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        // Header Title
        graphics.centeredText(this.font, this.title, this.width / 2, panelY + 10, 0xFFFFAA00);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Preset items list
        int listStartY = panelY + 68;
        for (int i = 0; i < Math.min(presetList.size(), 5); i++) {
            int itemY = listStartY + (i * 18);
            boolean isSelected = (i == this.selectedIndex);
            boolean isHovered = mouseX >= panelX + 16 && mouseX <= panelX + panelW - 16 && mouseY >= itemY && mouseY <= itemY + 16;

            int bg = isSelected ? 0xFF4A1A5E : (isHovered ? 0xFF2D0E3B : 0xFF0D0414);
            graphics.fill(panelX + 16, itemY, panelX + panelW - 16, itemY + 16, bg);
            graphics.outline(panelX + 16, itemY, panelW - 32, 16, isSelected ? 0xFFBA68C8 : 0xFF351242);

            graphics.text(this.font, Component.literal(presetList.get(i)), panelX + 22, itemY + 4, 0xFFFFFFFF);
        }

        if (presetList.isEmpty()) {
            graphics.centeredText(this.font, Component.literal("§8(No saved presets)"), this.width / 2, listStartY + 30, 0xFF888888);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}