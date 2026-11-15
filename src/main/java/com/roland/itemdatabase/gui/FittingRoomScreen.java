package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.LoadoutAnalyzer;
import com.roland.itemdatabase.data.RawDataParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interactive 3D Fitting Room and Loadout Preview screen for BlossomCraft gear.
 * <p>
 * Provides a free-orbiting 3D player mannequin, four armor slots, mainhand/offhand slots,
 * four passive inventory charm slots, live stat aggregation via {@link LoadoutAnalyzer},
 * automatic full-set bonus detection, player inventory sync, and saved loadout presets.
 */
public class FittingRoomScreen extends Screen {

    private int getPanelX(int panelW) {
        int base = (this.width - panelW) / 2 + ModConfig.get().cardOffsetX;
        return Mth.clamp(base, 8, Math.max(8, this.width - panelW - 8));
    }

    private int getPanelY(int panelH) {
        int base = (this.height - panelH) / 2 - 10 + ModConfig.get().cardOffsetY;
        return Mth.clamp(base, 8, Math.max(8, this.height - panelH - 34));
    }

    private final Map<EquipmentSlot, ItemStack> currentLoadout = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, ItemEntry> currentEntries = new EnumMap<>(EquipmentSlot.class);

    // Passive Charms / Inventory Buffs (4 slots)
    private final List<ItemStack> passiveLoadout = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
    private final List<ItemEntry> passiveEntries = new ArrayList<>(Arrays.asList(new ItemEntry[4]));
    private final List<PreviewSlotWidget> passiveSlotWidgets = new ArrayList<>();

    private final Screen parent;
    private PreviewDummyPlayer dummy;

    // Full 360-degree rotation angles
    private float playerYaw = 0.0f;
    private float playerPitch = 0.0f;

    // Drag tracking state
    private boolean isDraggingModel = false;
    private boolean wasMouseDown = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;

    // Interactive Equipment Slots
    private final List<PreviewSlotWidget> slotWidgets = new ArrayList<>();

    // Dynamic Loadout Stats & Traits (powered by LoadoutAnalyzer)
    private LoadoutAnalyzer.LoadoutSummary summary =
            new LoadoutAnalyzer.LoadoutSummary(0, 0.0f, 1.0, 0, 0, List.of());
    private AdminApiClient.SetRow activeCompletedSet = null;
    private final List<Component> equippedTraits = new ArrayList<>();
    private int traitsScrollOffset = 0;
    private final int maxVisibleTraits = 5;

    // Motion Physics & Auto-Rotation
    private float yawVelocity = 0.0f;
    private float pitchVelocity = 0.0f;
    private boolean autoRotate = true;

    /**
     * Constructs the Fitting Room screen with empty equipment slots.
     *
     * @param parent Parent screen to return to upon closing.
     */
    public FittingRoomScreen(Screen parent) {
        super(Component.literal("Fitting Room & Loadout Preview"));
        this.parent = parent;

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.currentLoadout.put(slot, ItemStack.EMPTY);
        }
    }

    /**
     * Equips a single database {@link ItemEntry} into its detected target {@link EquipmentSlot}.
     */
    public void equipItemFromCatalog(ItemEntry entry) {
        if (entry == null) return;
        EquipmentSlot slot = entry.getTargetEquipmentSlot();
        if (slot == null) return;

        ItemStack stack = RawDataParser.parseItemStack(entry);
        if (!stack.isEmpty()) {
            this.currentLoadout.put(slot, stack.copy());
            this.currentEntries.put(slot, entry);
        }
    }

    /**
     * Replaces all four armor slots with the pieces from a complete registered armor set.
     */
    public void equipFullSet(List<ItemEntry> setEntries) {
        if (setEntries == null) return;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            this.currentLoadout.put(slot, ItemStack.EMPTY);
            this.currentEntries.remove(slot);
        }
        for (ItemEntry entry : setEntries) {
            equipItemFromCatalog(entry);
        }
    }

    @Override
    protected void init() {
        super.init();

        this.clearWidgets();
        this.slotWidgets.clear();
        this.passiveSlotWidgets.clear();

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            this.dummy = new PreviewDummyPlayer(client);
            for (Map.Entry<EquipmentSlot, ItemStack> entry : this.currentLoadout.entrySet()) {
                this.dummy.setPreviewSlot(entry.getKey(), entry.getValue());
            }
        }

        int panelW = 390;
        int panelH = 190;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        int mannequinBoxX = panelX + 36;
        int mannequinBoxY = panelY + 36;
        int mannequinBoxW = 110;

        // Left Column Slots: Head, Chest, Legs, Feet
        int leftColX = panelX + 10;
        this.addSlot(leftColX, mannequinBoxY + 6, EquipmentSlot.HEAD, this.currentLoadout.getOrDefault(EquipmentSlot.HEAD, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.HEAD));
        this.addSlot(leftColX, mannequinBoxY + 32, EquipmentSlot.CHEST, this.currentLoadout.getOrDefault(EquipmentSlot.CHEST, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.CHEST));
        this.addSlot(leftColX, mannequinBoxY + 58, EquipmentSlot.LEGS, this.currentLoadout.getOrDefault(EquipmentSlot.LEGS, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.LEGS));
        this.addSlot(leftColX, mannequinBoxY + 84, EquipmentSlot.FEET, this.currentLoadout.getOrDefault(EquipmentSlot.FEET, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.FEET));

        // Right Column Slots: Mainhand & Offhand
        int rightColX = mannequinBoxX + mannequinBoxW + 6;
        this.addSlot(rightColX, mannequinBoxY + 6, EquipmentSlot.MAINHAND, this.currentLoadout.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.MAINHAND));
        this.addSlot(rightColX, mannequinBoxY + 32, EquipmentSlot.OFFHAND, this.currentLoadout.getOrDefault(EquipmentSlot.OFFHAND, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.OFFHAND));

        // 4 Passive Charm Slots
        int passiveY = mannequinBoxY + 58;
        for (int i = 0; i < 4; i++) {
            final int index = i;
            final boolean isTopLeftSlot = (index == 0);
            int px = rightColX + (i % 2) * 22;
            int py = passiveY + (i / 2) * 22;

            PreviewSlotWidget passiveWidget = new PreviewSlotWidget(px, py, EquipmentSlot.MAINHAND, this.passiveLoadout.get(index), this.passiveEntries.get(index), widget -> {
                openPassivePicker(index, isTopLeftSlot, widget);
            });
            this.passiveSlotWidgets.add(passiveWidget);
            this.addRenderableWidget(passiveWidget);
        }

        recalculateStats();

        // Bottom Control Bar
        int btnY = panelY + panelH + 8;
        int btnSpacing = 6;
        int btnW1 = 70;
        int btnW2 = 74;
        int btnW3 = 90;
        int btnW4 = 68;
        int btnW5 = 52;
        int totalBtnW = btnW1 + btnW2 + btnW3 + btnW4 + btnW5 + (btnSpacing * 4);
        int btnStartX = panelX + (panelW - totalBtnW) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
            this.playerYaw = 0.0f;
            this.playerPitch = 0.0f;
            this.yawVelocity = 0.0f;
            this.pitchVelocity = 0.0f;
        }).bounds(btnStartX, btnY, btnW1, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal(this.autoRotate ? "Spin: ON" : "Spin: OFF"), button -> {
            this.autoRotate = !this.autoRotate;
            button.setMessage(Component.literal(this.autoRotate ? "Spin: ON" : "Spin: OFF"));
        }).bounds(btnStartX + btnW1 + btnSpacing, btnY, btnW2, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Copy Gear"), button -> {
            this.syncFromPlayerInventory();
        }).bounds(btnStartX + btnW1 + btnW2 + (btnSpacing * 2), btnY, btnW3, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Presets"), button -> {
            this.openPresetScreen();
        }).bounds(btnStartX + btnW1 + btnW2 + btnW3 + (btnSpacing * 3), btnY, btnW4, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            this.onClose();
        }).bounds(btnStartX + btnW1 + btnW2 + btnW3 + btnW4 + (btnSpacing * 4), btnY, btnW5, 20).build());
    }

    /** Registers an interactive equipment slot widget and syncs its initial stack onto the 3D dummy. */
    private void addSlot(int x, int y, EquipmentSlot slot, ItemStack defaultItem, ItemEntry defaultEntry) {
        PreviewSlotWidget widget = new PreviewSlotWidget(x, y, slot, defaultItem, defaultEntry, this::openSlotPicker);
        this.slotWidgets.add(widget);
        this.addRenderableWidget(widget);

        if (this.dummy != null && !defaultItem.isEmpty()) {
            this.dummy.setPreviewSlot(slot, defaultItem);
        }
    }

    /** Re-evaluates aggregated combat stats, passive traits, and active full-set bonuses. */
    private void recalculateStats() {
        List<LoadoutAnalyzer.SlotInput> inputs = new ArrayList<>();

        for (PreviewSlotWidget widget : this.slotWidgets) {
            ItemStack stack = widget.getItemStack();
            if (stack != null && !stack.isEmpty()) {
                inputs.add(new LoadoutAnalyzer.SlotInput(stack, widget.getCurrentEntry(), widget.getSlot(), false, ""));
            }
        }
        for (PreviewSlotWidget widget : this.passiveSlotWidgets) {
            ItemStack stack = widget.getItemStack();
            if (stack != null && !stack.isEmpty()) {
                inputs.add(new LoadoutAnalyzer.SlotInput(stack, widget.getCurrentEntry(), null, true, ""));
            }
        }

        this.summary = LoadoutAnalyzer.analyze(inputs);
        this.activeCompletedSet = LoadoutAnalyzer.findCompletedSet(this.currentEntries, this.currentLoadout);

        this.equippedTraits.clear();
        if (this.activeCompletedSet != null && this.activeCompletedSet.description() != null && !this.activeCompletedSet.description().isBlank()) {
            this.equippedTraits.add(Component.literal("§6★ [" + this.activeCompletedSet.name() + "] §f" + this.activeCompletedSet.description()));
        }
        this.equippedTraits.addAll(this.summary.toTraitComponents(false));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (this.minecraft == null) return;

        int panelW = 390;
        int panelH = 190;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        int mannequinBoxX = panelX + 36;
        int mannequinBoxY = panelY + 36;
        int mannequinBoxW = 110;
        int mannequinBoxH = 140;

        long windowHandle = this.minecraft.getWindow().handle();
        boolean isMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (isMouseDown) {
            if (!wasMouseDown) {
                if (mouseX >= mannequinBoxX && mouseX <= mannequinBoxX + mannequinBoxW
                        && mouseY >= mannequinBoxY && mouseY <= mannequinBoxY + mannequinBoxH) {
                    isDraggingModel = true;
                    lastMouseX = mouseX;
                    lastMouseY = mouseY;
                    this.yawVelocity = 0.0f;
                    this.pitchVelocity = 0.0f;
                }
            } else if (isDraggingModel) {
                double deltaX = mouseX - lastMouseX;
                double deltaY = mouseY - lastMouseY;

                this.yawVelocity = (float) deltaX * 1.4f;
                this.pitchVelocity = (float) deltaY * 1.2f;

                this.playerYaw = (this.playerYaw + this.yawVelocity) % 360.0f;
                this.playerPitch = Mth.clamp(this.playerPitch + this.pitchVelocity, -85.0f, 85.0f);

                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
        } else {
            if (isDraggingModel) {
                isDraggingModel = false;
            }

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

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, this.title, panelX + (panelW / 2), panelY + 10, 0xFFFFAA00);
        graphics.centeredText(this.font, Component.literal("§7(Drag inside frame to rotate • Click slots to change gear)"), panelX + (panelW / 2), panelY + 22, 0xFFAAAAAA);

        graphics.fill(mannequinBoxX, mannequinBoxY, mannequinBoxX + mannequinBoxW, mannequinBoxY + mannequinBoxH, 0xFF0D0414);
        graphics.outline(mannequinBoxX, mannequinBoxY, mannequinBoxW, mannequinBoxH, isDraggingModel ? 0xFFBA68C8 : 0xFF351242);

        if (this.dummy != null) {
            renderFreeOrbitDummy(
                    graphics,
                    mannequinBoxX + 6,
                    mannequinBoxY + 6,
                    mannequinBoxX + mannequinBoxW - 6,
                    mannequinBoxY + mannequinBoxH - 6,
                    48
            );
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);

        // Shift stats further right to clear the 2x2 charm slots
        int statsX = panelX + 208;
        int statsY = panelY + 36;

        String headerTitle = this.activeCompletedSet != null
                ? "§6§lLoadout Stats §e(★ " + this.activeCompletedSet.name() + ")"
                : "§6§lLoadout Stats";
        graphics.text(this.font, Component.literal(headerTitle), statsX, statsY, 0xFFFFFFFF);
        graphics.horizontalLine(statsX, panelX + panelW - 16, statsY + 10, 0xFF4A1A5E);

        graphics.text(this.font, Component.literal("§bArmor: §f+" + this.summary.totalArmor()), statsX, statsY + 14, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§9Armor Toughness: §f+" + (int) this.summary.totalToughness()), statsX, statsY + 25, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal(String.format("§cAttack Damage: §f%.1f", this.summary.totalAttackDamage())), statsX, statsY + 36, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§eAttack Speed: §f+" + this.summary.totalAttackSpeedBonus() + "%"), statsX, statsY + 47, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§aBonus Health: §f+" + this.summary.totalBonusHealth() + " ❤"), statsX, statsY + 58, 0xFFFFFFFF);

        // Traits header positioned cleanly below the stats block
        int traitsY = statsY + 74;
        List<Component> distinctTraits = getDistinctTraits();
        int maxTextWidth = (panelX + panelW - 20) - statsX;

        List<FormattedCharSequence> displayLines = new ArrayList<>();
        for (Component trait : distinctTraits) {
            displayLines.addAll(this.font.split(trait, maxTextWidth));
        }

        int totalLines = displayLines.size();
        int maxScroll = Math.max(0, totalLines - maxVisibleTraits);
        this.traitsScrollOffset = Mth.clamp(this.traitsScrollOffset, 0, maxScroll);

        graphics.text(this.font, Component.literal("§e§lEquipped Traits:"), statsX, traitsY, 0xFFFFFFFF);

        int lineOffset = 11;
        int endIndex = Math.min(totalLines, this.traitsScrollOffset + maxVisibleTraits);
        for (int i = this.traitsScrollOffset; i < endIndex; i++) {
            graphics.text(this.font, displayLines.get(i), statsX, traitsY + lineOffset, 0xFFFFFFFF);
            lineOffset += 10;
        }

        if (totalLines > maxVisibleTraits) {
            int barX = panelX + panelW - 14;
            int barY = traitsY + 11;
            int barHeight = maxVisibleTraits * 10;
            int barWidth = 3;

            graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x44FFFFFF);

            int thumbHeight = Math.max(10, (maxVisibleTraits * barHeight) / totalLines);
            int thumbY = barY + ((barHeight - thumbHeight) * this.traitsScrollOffset) / maxScroll;
            graphics.fill(barX, thumbY, barX + barWidth, thumbY + thumbHeight, 0xFFFFAA00);
        }

        for (PreviewSlotWidget widget : this.slotWidgets) {
            widget.renderTooltip(graphics, mouseX, mouseY);
        }
        for (PreviewSlotWidget widget : this.passiveSlotWidgets) {
            widget.renderTooltip(graphics, mouseX, mouseY);
        }
    }

    /** Renders the 3D {@link PreviewDummyPlayer} inside the preview viewport with full yaw/pitch orbit. */
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

            if (livingRenderState.pose != Pose.FALL_FLYING) {
                livingRenderState.xRot = -this.playerPitch;
            } else {
                livingRenderState.xRot = 0.0F;
            }

            livingRenderState.boundingBoxWidth = livingRenderState.boundingBoxWidth / livingRenderState.scale;
            livingRenderState.boundingBoxHeight = livingRenderState.boundingBoxHeight / livingRenderState.scale;
            livingRenderState.scale = 1.0F;
        }

        float pivotY = (renderState.boundingBoxHeight / 2.0F) + 0.15F;
        Vector3f translation = new Vector3f(0.0F, pivotY, 0.0F);

        graphics.entity(renderState, size, translation, rotation, xRotation, x0, y0, x1, y1);
    }

    /** Copies the local player's currently equipped armor and hand items onto the mannequin. */
    private void syncFromPlayerInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        for (PreviewSlotWidget widget : this.slotWidgets) {
            ItemStack equipped = client.player.getItemBySlot(widget.getSlot());
            this.currentLoadout.put(widget.getSlot(), equipped.copy());
            this.currentEntries.remove(widget.getSlot());
            widget.setItem(equipped.copy(), null);
            if (this.dummy != null) {
                this.dummy.setPreviewSlot(widget.getSlot(), equipped.copy());
            }
        }
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_NETHERITE, 1.0f));
        recalculateStats();
    }

    /** Plays a slot-appropriate armor or weapon equip sound effect. */
    private void playEquipSound(EquipmentSlot slot, ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        net.minecraft.core.Holder<SoundEvent> soundHolder;

        if (stack.isEmpty()) {
            soundHolder = SoundEvents.ARMOR_EQUIP_GENERIC;
        } else if (slot == EquipmentSlot.MAINHAND) {
            soundHolder = SoundEvents.ARMOR_EQUIP_IRON;
        } else if (slot == EquipmentSlot.OFFHAND) {
            soundHolder = SoundEvents.ARMOR_EQUIP_GENERIC;
        } else {
            soundHolder = SoundEvents.ARMOR_EQUIP_NETHERITE;
        }

        if (soundHolder != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(soundHolder.value(), 1.0f));
        }
    }

    /** Opens the catalog item picker for a primary armor or hand slot. */
    private void openSlotPicker(PreviewSlotWidget widget) {
        if (this.minecraft == null) return;

        this.minecraft.setScreen(new ItemCatalogPickerScreen(this, widget.getSlot(), false, (selectedStack, selectedEntry) -> {
            ItemStack copy = selectedStack == null || selectedStack.isEmpty() ? ItemStack.EMPTY : selectedStack.copy();

            this.currentLoadout.put(widget.getSlot(), copy);
            if (selectedEntry != null) {
                this.currentEntries.put(widget.getSlot(), selectedEntry);
            } else {
                this.currentEntries.remove(widget.getSlot());
            }
            widget.setItem(copy, selectedEntry);

            if (this.dummy != null) {
                this.dummy.setPreviewSlot(widget.getSlot(), ItemStack.EMPTY);
                this.dummy.setPreviewSlot(widget.getSlot(), copy);
            }
            recalculateStats();
            playEquipSound(widget.getSlot(), copy);
        }));
    }

    /** Opens the catalog item picker for one of the four passive inventory charm slots. */
    private void openPassivePicker(int index, boolean isTopLeftSlot, PreviewSlotWidget widget) {
        if (this.minecraft == null) return;

        this.minecraft.setScreen(new ItemCatalogPickerScreen(this, EquipmentSlot.MAINHAND, true, isTopLeftSlot, (selectedStack, selectedEntry) -> {
            ItemStack copy = selectedStack == null || selectedStack.isEmpty() ? ItemStack.EMPTY : selectedStack.copy();
            this.passiveLoadout.set(index, copy);
            this.passiveEntries.set(index, selectedEntry);
            widget.setItem(copy, selectedEntry);
            recalculateStats();
            playEquipSound(EquipmentSlot.MAINHAND, copy);
        }));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    /** Opens the saved Loadout Presets modal and applies the selected preset on return. */
    private void openPresetScreen() {
        if (this.minecraft == null) return;

        this.minecraft.setScreen(new PresetScreen(this, this.currentLoadout, (Map<EquipmentSlot, ItemStack> loadedMap) -> {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = loadedMap.getOrDefault(slot, ItemStack.EMPTY);
                this.currentLoadout.put(slot, stack.copy());
                this.currentEntries.remove(slot);
            }

            for (PreviewSlotWidget widget : this.slotWidgets) {
                ItemStack stack = this.currentLoadout.getOrDefault(widget.getSlot(), ItemStack.EMPTY);
                widget.setItem(stack.copy(), null);
                if (this.dummy != null) {
                    this.dummy.setPreviewSlot(widget.getSlot(), stack.copy());
                }
            }
            recalculateStats();
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_NETHERITE.value(), 1.0f));
        }));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int panelW = 390;
        int panelH = 190;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);
        int statsX = panelX + 208;
        int traitsY = panelY + 36 + 74;

        if (mouseX >= statsX && mouseX <= panelX + panelW - 8 && mouseY >= traitsY) {
            int maxTextWidth = (panelX + panelW - 20) - statsX;
            int totalLines = 0;
            for (Component trait : this.getDistinctTraits()) {
                totalLines += this.font.split(trait, maxTextWidth).size();
            }

            int maxScroll = Math.max(0, totalLines - maxVisibleTraits);
            if (verticalAmount > 0) {
                this.traitsScrollOffset = Math.max(0, this.traitsScrollOffset - 1);
            } else if (verticalAmount < 0) {
                this.traitsScrollOffset = Math.min(maxScroll, this.traitsScrollOffset + 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** Deduplicates rendered trait lines by their plain text string. */
    private List<Component> getDistinctTraits() {
        List<Component> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Component trait : this.equippedTraits) {
            if (seen.add(trait.getString())) {
                distinct.add(trait);
            }
        }
        return distinct;
    }
}