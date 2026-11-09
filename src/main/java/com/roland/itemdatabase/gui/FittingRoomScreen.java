package com.roland.itemdatabase.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class FittingRoomScreen extends Screen {

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

    // Dynamic Loadout Stats & Traits
    private int totalArmor = 0;
    private float totalToughness = 0.0f;
    private double totalAttackDamage = 1.0;
    private int totalBonusHealth = 0;
    private final List<Component> equippedTraits = new ArrayList<>();

    // Motion Physics & Auto-Rotation
    private float yawVelocity = 0.0f;
    private float pitchVelocity = 0.0f;
    private boolean autoRotate = true;

    public FittingRoomScreen(Screen parent) {
        super(Component.literal("Fitting Room & Loadout Preview"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // 1. Clear existing widgets to prevent duplicates on resize
        this.clearWidgets();
        this.slotWidgets.clear();

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            this.dummy = new PreviewDummyPlayer(client);
        }

        // Panel and viewport frame layout calculations
        int panelW = 340;
        int panelH = 190;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2 - 10;

        int mannequinBoxX = panelX + 36;
        int mannequinBoxY = panelY + 36;
        int mannequinBoxW = 110;

        // 2. Left Column Slots: Head, Chest, Legs, Feet
        int leftColX = panelX + 10;
        this.addSlot(leftColX, mannequinBoxY + 6, EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        this.addSlot(leftColX, mannequinBoxY + 32, EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        this.addSlot(leftColX, mannequinBoxY + 58, EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        this.addSlot(leftColX, mannequinBoxY + 84, EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));

        // 3. Right Column Slots: Mainhand & Offhand
        int rightColX = mannequinBoxX + mannequinBoxW + 6;
        this.addSlot(rightColX, mannequinBoxY + 32, EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        this.addSlot(rightColX, mannequinBoxY + 58, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));

        // Compute initial loadout stats
        recalculateStats();

        // Bottom Control Bar: Evenly spaced 4-button footer
        int btnY = panelY + panelH + 8;
        int btnSpacing = 6;
        int btnW1 = 80;  // Reset View
        int btnW2 = 85;  // Auto-Rotate
        int btnW3 = 110; // Copy Player Gear
        int btnW4 = 60;  // Back
        int totalBtnW = btnW1 + btnW2 + btnW3 + btnW4 + (btnSpacing * 3);
        int btnStartX = panelX + (panelW - totalBtnW) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Reset View"), button -> {
            this.playerYaw = 0.0f;
            this.playerPitch = 0.0f;
            this.yawVelocity = 0.0f;
            this.pitchVelocity = 0.0f;
        }).bounds(btnStartX, btnY, btnW1, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Spin: ON"), button -> {
            this.autoRotate = !this.autoRotate;
            button.setMessage(Component.literal(this.autoRotate ? "Spin: ON" : "Spin: OFF"));
        }).bounds(btnStartX + btnW1 + btnSpacing, btnY, btnW2, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Copy Player Gear"), button -> {
            this.syncFromPlayerInventory();
        }).bounds(btnStartX + btnW1 + btnW2 + (btnSpacing * 2), btnY, btnW3, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            this.onClose();
        }).bounds(btnStartX + btnW1 + btnW2 + btnW3 + (btnSpacing * 3), btnY, btnW4, 20).build());
    }

    private void addSlot(int x, int y, EquipmentSlot slot, ItemStack defaultItem) {
        PreviewSlotWidget widget = new PreviewSlotWidget(x, y, slot, defaultItem, this::cycleSlotItem);
        this.slotWidgets.add(widget);
        this.addRenderableWidget(widget);

        if (this.dummy != null) {
            this.dummy.setPreviewSlot(slot, defaultItem);
        }
    }

    /**
     * Cycles demo gear when clicking a slot to preview different armor sets.
     */
    private void cycleSlotItem(PreviewSlotWidget widget) {
        ItemStack current = widget.getItemStack();
        ItemStack next = ItemStack.EMPTY;

        switch (widget.getSlot()) {
            case HEAD -> {
                if (current.is(Items.DIAMOND_HELMET)) next = new ItemStack(Items.NETHERITE_HELMET);
                else if (current.is(Items.NETHERITE_HELMET)) next = new ItemStack(Items.GOLDEN_HELMET);
                else if (current.is(Items.GOLDEN_HELMET)) next = ItemStack.EMPTY;
                else next = new ItemStack(Items.DIAMOND_HELMET);
            }
            case CHEST -> {
                if (current.is(Items.NETHERITE_CHESTPLATE)) next = new ItemStack(Items.DIAMOND_CHESTPLATE);
                else if (current.is(Items.DIAMOND_CHESTPLATE)) next = new ItemStack(Items.IRON_CHESTPLATE);
                else if (current.is(Items.IRON_CHESTPLATE)) next = ItemStack.EMPTY;
                else next = new ItemStack(Items.NETHERITE_CHESTPLATE);
            }
            case LEGS -> {
                if (current.is(Items.NETHERITE_LEGGINGS)) next = new ItemStack(Items.DIAMOND_LEGGINGS);
                else if (current.is(Items.DIAMOND_LEGGINGS)) next = new ItemStack(Items.IRON_LEGGINGS);
                else if (current.is(Items.IRON_LEGGINGS)) next = ItemStack.EMPTY;
                else next = new ItemStack(Items.NETHERITE_LEGGINGS);
            }
            case FEET -> {
                if (current.is(Items.DIAMOND_BOOTS)) next = new ItemStack(Items.NETHERITE_BOOTS);
                else if (current.is(Items.NETHERITE_BOOTS)) next = new ItemStack(Items.IRON_BOOTS);
                else if (current.is(Items.IRON_BOOTS)) next = ItemStack.EMPTY;
                else next = new ItemStack(Items.DIAMOND_BOOTS);
            }
            case MAINHAND -> {
                if (current.is(Items.NETHERITE_SWORD)) next = new ItemStack(Items.DIAMOND_AXE);
                else if (current.is(Items.DIAMOND_AXE)) next = new ItemStack(Items.TRIDENT);
                else if (current.is(Items.TRIDENT)) next = ItemStack.EMPTY;
                else next = new ItemStack(Items.NETHERITE_SWORD);
            }
            case OFFHAND -> {
                if (current.is(Items.SHIELD)) next = new ItemStack(Items.TOTEM_OF_UNDYING);
                else if (current.is(Items.TOTEM_OF_UNDYING)) next = ItemStack.EMPTY;
                else next = new ItemStack(Items.SHIELD);
            }
            default -> {}
        }

        widget.setItemStack(next);
        if (this.dummy != null) {
            this.dummy.setPreviewSlot(widget.getSlot(), next);
        }
        recalculateStats();
    }

    private void recalculateStats() {
        this.totalArmor = 0;
        this.totalToughness = 0.0f;
        this.totalAttackDamage = 1.0;
        this.totalBonusHealth = 0;
        this.equippedTraits.clear();

        boolean hasNetherite = false;
        boolean hasShield = false;
        boolean hasTotem = false;

        for (PreviewSlotWidget widget : this.slotWidgets) {
            ItemStack stack = widget.getItemStack();
            if (stack == null || stack.isEmpty()) continue;

            // Armor calculations
            if (stack.is(Items.DIAMOND_HELMET)) { this.totalArmor += 3; this.totalToughness += 2.0f; }
            else if (stack.is(Items.NETHERITE_HELMET)) { this.totalArmor += 3; this.totalToughness += 3.0f; hasNetherite = true; }
            else if (stack.is(Items.GOLDEN_HELMET)) { this.totalArmor += 2; }

            else if (stack.is(Items.NETHERITE_CHESTPLATE)) { this.totalArmor += 8; this.totalToughness += 3.0f; hasNetherite = true; }
            else if (stack.is(Items.DIAMOND_CHESTPLATE)) { this.totalArmor += 8; this.totalToughness += 2.0f; }
            else if (stack.is(Items.IRON_CHESTPLATE)) { this.totalArmor += 6; }

            else if (stack.is(Items.NETHERITE_LEGGINGS)) { this.totalArmor += 6; this.totalToughness += 3.0f; hasNetherite = true; }
            else if (stack.is(Items.DIAMOND_LEGGINGS)) { this.totalArmor += 6; this.totalToughness += 2.0f; }
            else if (stack.is(Items.IRON_LEGGINGS)) { this.totalArmor += 5; }

            else if (stack.is(Items.DIAMOND_BOOTS)) { this.totalArmor += 3; this.totalToughness += 2.0f; }
            else if (stack.is(Items.NETHERITE_BOOTS)) { this.totalArmor += 3; this.totalToughness += 3.0f; hasNetherite = true; }
            else if (stack.is(Items.IRON_BOOTS)) { this.totalArmor += 2; }

            // Weapon & Offhand checks
            if (widget.getSlot() == EquipmentSlot.MAINHAND) {
                if (stack.is(Items.NETHERITE_SWORD)) { this.totalAttackDamage = 8.0; }
                else if (stack.is(Items.DIAMOND_AXE)) { this.totalAttackDamage = 9.0; }
                else if (stack.is(Items.TRIDENT)) { this.totalAttackDamage = 9.0; }
            }

            if (widget.getSlot() == EquipmentSlot.OFFHAND) {
                if (stack.is(Items.SHIELD)) hasShield = true;
                if (stack.is(Items.TOTEM_OF_UNDYING)) hasTotem = true;
            }
        }

        // Derive traits dynamically from equipped items
        if (hasNetherite) {
            this.equippedTraits.add(Component.literal("§7• §fKnockback Resistance"));
        }
        if (hasShield) {
            this.equippedTraits.add(Component.literal("§7• §fProjectile Deflection"));
        }
        if (hasTotem) {
            this.totalBonusHealth += 4;
            this.equippedTraits.add(Component.literal("§7• §eSecond Chance (Totem)"));
        }

        if (this.equippedTraits.isEmpty()) {
            this.equippedTraits.add(Component.literal("§8• None"));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int panelW = 340;
        int panelH = 190;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2 - 10;

        // Viewport Frame
        int mannequinBoxX = panelX + 36;
        int mannequinBoxY = panelY + 36;
        int mannequinBoxW = 110;
        int mannequinBoxH = 140;

        // --- GLFW Direct Drag Tracking with Inertia ---
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

            // Apply friction damping to manual throw
            if (Math.abs(this.yawVelocity) > 0.05f) {
                this.playerYaw = (this.playerYaw + this.yawVelocity) % 360.0f;
                this.yawVelocity *= 0.88f;
            } else {
                this.yawVelocity = 0.0f;
                // Idle turntable spin when auto-rotate is active
                if (this.autoRotate) {
                    this.playerYaw = (this.playerYaw + 0.45f) % 360.0f;
                }
            }

            if (Math.abs(this.pitchVelocity) > 0.05f) {
                this.playerPitch = Mth.clamp(this.playerPitch + this.pitchVelocity, -85.0f, 85.0f);
                this.pitchVelocity *= 0.85f;
            } else {
                this.pitchVelocity = 0.0f;
            }
        }
        wasMouseDown = isMouseDown;

        // 1. Draw Panel Background & Border FIRST so it doesn't cover widgets
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        // 2. Header Titles
        graphics.centeredText(this.font, this.title, this.width / 2, panelY + 10, 0xFFFFAA00);
        graphics.centeredText(this.font, Component.literal("§7(Drag inside frame to rotate • Click slots to cycle items)"), this.width / 2, panelY + 22, 0xFFAAAAAA);

        // 3. Mannequin Box Viewport Background & Outline
        graphics.fill(mannequinBoxX, mannequinBoxY, mannequinBoxX + mannequinBoxW, mannequinBoxY + mannequinBoxH, 0xFF0D0414);
        graphics.outline(mannequinBoxX, mannequinBoxY, mannequinBoxW, mannequinBoxH, isDraggingModel ? 0xFFBA68C8 : 0xFF351242);

        // 4. Render 3D Mannequin
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

        // 5. Render Screen Widgets (Slot Buttons, Reset View, Back) ON TOP of background
        super.extractRenderState(graphics, mouseX, mouseY, a);

        // 6. Stats Preview Panel (Right Side)
        int statsX = panelX + 176;
        int statsY = panelY + 45;

        graphics.text(this.font, Component.literal("§6§lLoadout Stats"), statsX, statsY, 0xFFFFFFFF);
        graphics.horizontalLine(statsX, panelX + panelW - 16, statsY + 11, 0xFF4A1A5E);

        graphics.text(this.font, Component.literal("§bArmor: §f+" + this.totalArmor), statsX, statsY + 18, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§9Armor Toughness: §f+" + (int) this.totalToughness), statsX, statsY + 32, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal(String.format("§cAttack Damage: §f%.1f", this.totalAttackDamage)), statsX, statsY + 46, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§aBonus Health: §f+" + this.totalBonusHealth + " ❤"), statsX, statsY + 60, 0xFFFFFFFF);

        int traitsY = statsY + 80;
        graphics.text(this.font, Component.literal("§e§lEquipped Traits:"), statsX, traitsY, 0xFFFFFFFF);

        int lineOffset = 14;
        for (Component trait : this.equippedTraits) {
            graphics.text(this.font, trait, statsX, traitsY + lineOffset, 0xFFFFFFFF);
            lineOffset += 12;
        }

    }

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

    /**
     * Reads the player's active equipment directly from their live inventory.
     */
    private void syncFromPlayerInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        for (PreviewSlotWidget widget : this.slotWidgets) {
            ItemStack equipped = client.player.getItemBySlot(widget.getSlot());
            widget.setItemStack(equipped.copy());
            if (this.dummy != null) {
                this.dummy.setPreviewSlot(widget.getSlot(), equipped.copy());
            }
        }
        recalculateStats();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}