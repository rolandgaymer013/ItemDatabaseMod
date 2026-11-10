package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.MockServerItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class FittingRoomScreen extends Screen {

    private final Map<EquipmentSlot, ItemStack> currentLoadout = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, ItemEntry> currentEntries = new EnumMap<>(EquipmentSlot.class);

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
    private int traitsScrollOffset = 0;
    private final int maxVisibleTraits = 5;

    // Motion Physics & Auto-Rotation
    private float yawVelocity = 0.0f;
    private float pitchVelocity = 0.0f;
    private boolean autoRotate = true;

    public FittingRoomScreen(Screen parent) {
        super(Component.literal("Fitting Room & Loadout Preview"));
        this.parent = parent;

        // Initialize all slots as EMPTY by default so no unwanted brown leather lingers
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.currentLoadout.put(slot, ItemStack.EMPTY);
        }
    }

    @Override
    protected void init() {
        super.init();

        this.clearWidgets();
        this.slotWidgets.clear();

        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            this.dummy = new PreviewDummyPlayer(client);
            // Immediately sync existing loadout to the dummy
            for (Map.Entry<EquipmentSlot, ItemStack> entry : this.currentLoadout.entrySet()) {
                this.dummy.setPreviewSlot(entry.getKey(), entry.getValue());
            }
        }

        int panelW = 390;
        int panelH = 190;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2 - 10;

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
        this.addSlot(rightColX, mannequinBoxY + 32, EquipmentSlot.MAINHAND, this.currentLoadout.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.MAINHAND));
        this.addSlot(rightColX, mannequinBoxY + 58, EquipmentSlot.OFFHAND, this.currentLoadout.getOrDefault(EquipmentSlot.OFFHAND, ItemStack.EMPTY), this.currentEntries.get(EquipmentSlot.OFFHAND));

        recalculateStats();

        // Bottom Control Bar: Evenly centered 5-button footer
        int btnY = panelY + panelH + 8;
        int btnSpacing = 6;
        int btnW1 = 70;  // Reset View
        int btnW2 = 74;  // Spin: ON
        int btnW3 = 90;  // Copy Gear
        int btnW4 = 68;  // Presets
        int btnW5 = 52;  // Back
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

    private void addSlot(int x, int y, EquipmentSlot slot, ItemStack defaultItem, ItemEntry defaultEntry) {
        PreviewSlotWidget widget = new PreviewSlotWidget(x, y, slot, defaultItem, defaultEntry, this::openSlotPicker);
        this.slotWidgets.add(widget);
        this.addRenderableWidget(widget);

        if (this.dummy != null && !defaultItem.isEmpty()) {
            this.dummy.setPreviewSlot(slot, defaultItem);
        }
    }

    private void recalculateStats() {
        this.totalArmor = 0;
        this.totalToughness = 0.0f;
        this.totalAttackDamage = 1.0;
        this.totalBonusHealth = 0;
        this.equippedTraits.clear();

        for (PreviewSlotWidget widget : this.slotWidgets) {
            ItemStack stack = widget.getItemStack();
            if (stack == null || stack.isEmpty()) continue;

            EquipmentSlot slot = widget.getSlot();

            // 1. Process custom BlossomCraft lore stats
            ItemLore lore = stack.get(DataComponents.LORE);
            boolean parsedFromLore = false;

            if (lore != null) {
                for (Component line : lore.lines()) {
                    String clean = net.minecraft.util.StringUtil.stripColor(line.getString()).trim();

                    if (clean.matches("(?i)^Armor:\\s*\\+\\d+.*")) {
                        this.totalArmor += extractInt(clean);
                        parsedFromLore = true;
                    } else if (clean.matches("(?i)^Armor Toughness:\\s*\\+\\d+.*")) {
                        this.totalToughness += extractInt(clean);
                        parsedFromLore = true;
                    } else if (clean.matches("(?i)^(Attack Damage|Damage):\\s*\\+?\\d+(\\.\\d+)?.*")) {
                        if (slot == EquipmentSlot.MAINHAND) {
                            this.totalAttackDamage += extractDouble(clean);
                            parsedFromLore = true;
                        }
                    } else if (clean.matches("(?i)^Health:\\s*\\+\\d+.*")) {
                        this.totalBonusHealth += extractInt(clean);
                        parsedFromLore = true;
                    } else if (clean.startsWith("Trait:") || clean.contains("Trait:")) {
                        this.equippedTraits.add(Component.literal("§7• " + clean.replace("Trait:", "").trim()));
                    } else if (clean.startsWith(">") && (clean.contains("gain") || clean.contains("passively"))) {
                        // Capture special set traits (e.g. "> When worn gain Haste II")
                        this.equippedTraits.add(Component.literal("§6• §f" + clean.replace(">", "").trim()));
                    }
                }
            }

            // 2. If no custom lore stats were defined, fall back to vanilla DataComponents
            if (!parsedFromLore) {
                ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
                if (modifiers != null && !modifiers.modifiers().isEmpty()) {
                    for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                        if (entry.slot().test(slot)) {
                            var attr = entry.attribute();
                            double amount = entry.modifier().amount();

                            if (attr.is(Attributes.ARMOR)) {
                                this.totalArmor += (int) amount;
                            } else if (attr.is(Attributes.ARMOR_TOUGHNESS)) {
                                this.totalToughness += (float) amount;
                            } else if (attr.is(Attributes.ATTACK_DAMAGE)) {
                                this.totalAttackDamage += amount;
                            } else if (attr.is(Attributes.MAX_HEALTH)) {
                                this.totalBonusHealth += (int) amount;
                            }
                        }
                    }
                }
            }

            if (slot == EquipmentSlot.OFFHAND) {
                if (stack.is(Items.SHIELD)) {
                    this.equippedTraits.add(Component.literal("§7• §fProjectile Deflection"));
                } else if (stack.is(Items.TOTEM_OF_UNDYING)) {
                    this.totalBonusHealth += 4;
                    this.equippedTraits.add(Component.literal("§7• §eSecond Chance (Totem)"));
                }
            }
        }

        if (this.equippedTraits.isEmpty()) {
            this.equippedTraits.add(Component.literal("§8• None"));
        }
    }

    private int extractInt(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\+(\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private double extractDouble(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\+?(\\d+(\\.\\d+)?)").matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int panelW = 390;
        int panelH = 190;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2 - 10;

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

            if (Math.abs(this.yawVelocity) > 0.05f) {
                this.playerYaw = (this.playerYaw + this.yawVelocity) % 360.0f;
                this.yawVelocity *= 0.88f;
            } else {
                this.yawVelocity = 0.0f;
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

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, this.title, this.width / 2, panelY + 10, 0xFFFFAA00);
        graphics.centeredText(this.font, Component.literal("§7(Drag inside frame to rotate • Click slots to change gear)"), this.width / 2, panelY + 22, 0xFFAAAAAA);

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

        int statsX = panelX + 176;
        int statsY = panelY + 45;

        graphics.text(this.font, Component.literal("§6§lLoadout Stats"), statsX, statsY, 0xFFFFFFFF);
        graphics.horizontalLine(statsX, panelX + panelW - 16, statsY + 11, 0xFF4A1A5E);

        graphics.text(this.font, Component.literal("§bArmor: §f+" + this.totalArmor), statsX, statsY + 18, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§9Armor Toughness: §f+" + (int) this.totalToughness), statsX, statsY + 32, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal(String.format("§cAttack Damage: §f%.1f", this.totalAttackDamage)), statsX, statsY + 46, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§aBonus Health: §f+" + this.totalBonusHealth + " ❤"), statsX, statsY + 60, 0xFFFFFFFF);

        int traitsY = statsY + 76;
        List<Component> distinctTraits = getDistinctTraits();
        int totalTraits = distinctTraits.size();
        int maxScroll = Math.max(0, totalTraits - maxVisibleTraits);
        this.traitsScrollOffset = Mth.clamp(this.traitsScrollOffset, 0, maxScroll);

        // Header without bracketed numbers
        graphics.text(this.font, Component.literal("§e§lEquipped Traits:"), statsX, traitsY, 0xFFFFFFFF);

        int lineOffset = 12;
        int endIndex = Math.min(distinctTraits.size(), this.traitsScrollOffset + maxVisibleTraits);
        for (int i = this.traitsScrollOffset; i < endIndex; i++) {
            graphics.text(this.font, distinctTraits.get(i), statsX, traitsY + lineOffset, 0xFFFFFFFF);
            lineOffset += 11;
        }

        // Vertical scrollbar on the right edge of the traits area
        if (totalTraits > maxVisibleTraits) {
            int barX = panelX + panelW - 14;
            int barY = traitsY + 12;
            int barHeight = maxVisibleTraits * 11;
            int barWidth = 3;

            // Track background
            graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x44FFFFFF);

            // Scroll thumb
            int thumbHeight = Math.max(10, (maxVisibleTraits * barHeight) / totalTraits);
            int thumbY = barY + ((barHeight - thumbHeight) * this.traitsScrollOffset) / maxScroll;
            graphics.fill(barX, thumbY, barX + barWidth, thumbY + thumbHeight, 0xFFFFAA00);
        }

        // --- RENDER HOVER TOOLTIPS LAST (Draws over all UI elements) ---
        for (PreviewSlotWidget widget : this.slotWidgets) {
            widget.renderTooltip(graphics, mouseX, mouseY);
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

    private void playEquipSound(EquipmentSlot slot, ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        net.minecraft.core.Holder<SoundEvent> soundHolder = null;

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

    private void openSlotPicker(PreviewSlotWidget widget) {
        if (this.minecraft == null) return;

        this.minecraft.setScreen(new ItemCatalogPickerScreen(this, widget.getSlot(), (selectedStack, selectedEntry) -> {
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

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void openPresetScreen() {
        if (this.minecraft == null) return;

        this.minecraft.setScreen(new PresetScreen(this, this.currentLoadout, (Map<EquipmentSlot, ItemStack> loadedMap) -> {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = loadedMap.getOrDefault(slot, ItemStack.EMPTY);
                this.currentLoadout.put(slot, stack.copy());
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
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2 - 10;
        int statsX = panelX + 176;
        int traitsY = panelY + 45 + 76;

        // If mouse is inside the traits region, scroll the list
        if (mouseX >= statsX && mouseX <= panelX + panelW - 8 && mouseY >= traitsY) {
            int totalTraits = this.getDistinctTraits().size();
            int maxScroll = Math.max(0, totalTraits - maxVisibleTraits);
            if (verticalAmount > 0) {
                this.traitsScrollOffset = Math.max(0, this.traitsScrollOffset - 1);
            } else if (verticalAmount < 0) {
                this.traitsScrollOffset = Math.min(maxScroll, this.traitsScrollOffset + 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private List<Component> getDistinctTraits() {
        List<Component> distinct = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Component trait : this.equippedTraits) {
            if (seen.add(trait.getString())) {
                distinct.add(trait);
            }
        }
        return distinct;
    }
}