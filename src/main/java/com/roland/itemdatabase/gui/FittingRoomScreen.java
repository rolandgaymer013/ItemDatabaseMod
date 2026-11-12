package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.config.ModConfig;
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
    private final List<ItemEntry> passiveEntries = new ArrayList<>(java.util.Arrays.asList(new ItemEntry[4]));
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

    // Dynamic Loadout Stats & Traits
    private int totalArmor = 0;
    private float totalToughness = 0.0f;
    private double totalAttackDamage = 1.0;
    private int totalAttackSpeedBonus = 0;
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

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.currentLoadout.put(slot, ItemStack.EMPTY);
        }
    }

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
            final boolean isTopLeftSlot = (index == 0); // Slot 0 is dedicated to Top-Left Inv
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
        this.totalAttackSpeedBonus = 0;
        this.totalBonusHealth = 0;
        this.consolidatedTraits.clear();
        this.equippedTraits.clear();

        // 1. Equipped Gear & Weapons
        for (PreviewSlotWidget widget : this.slotWidgets) {
            ItemStack stack = widget.getItemStack();
            if (stack != null && !stack.isEmpty()) {
                processItemStats(stack, widget.getCurrentEntry(), widget.getSlot(), false);
            }
        }

        // 2. Passive Hotbar & Inventory items
        for (PreviewSlotWidget widget : this.passiveSlotWidgets) {
            ItemStack stack = widget.getItemStack();
            if (stack != null && !stack.isEmpty()) {
                processItemStats(stack, widget.getCurrentEntry(), null, true);
            }
        }

        // 3. Populate final traits taking highest tiers
        for (TraitInfo trait : this.consolidatedTraits.values()) {
            this.equippedTraits.add(Component.literal("§6• §f" + trait.format()));
        }

        if (this.equippedTraits.isEmpty()) {
            this.equippedTraits.add(Component.literal("§8• None"));
        }
    }

    private void processItemStats(ItemStack stack, ItemEntry entry, EquipmentSlot slot, boolean isPassive) {
        if (stack == null || stack.isEmpty()) return;

        // 1. Gather raw lore lines
        List<Component> rawLines = new ArrayList<>();
        if (entry != null && entry.rawData() != null && !entry.rawData().isBlank()) {
            rawLines.addAll(RawDataParser.parseLore(entry.rawData()));
        } else {
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                rawLines.addAll(lore.lines());
            }
        }

        // 2. Stitch multi-line ability entries together and sanitize dividers
        List<String> combinedSections = new ArrayList<>();
        StringBuilder currentBlock = null;

        for (Component comp : rawLines) {
            String rawStr = RawDataParser.sanitizeLegacyAmpersands(comp.getString());
            String clean = net.minecraft.util.StringUtil.stripColor(rawStr).trim();

            // Ignore quotes, dividers, and empty lines
            if (clean.isBlank() || clean.equals("\"\"") || clean.startsWith("===") || clean.startsWith("---")) {
                if (currentBlock != null) {
                    combinedSections.add(currentBlock.toString());
                    currentBlock = null;
                }
                continue;
            }

            // Boundary metadata lines that terminate an active trait block
            if (clean.startsWith("Rarity:") || clean.startsWith("Season") || clean.startsWith("Win Chance:")
                    || clean.startsWith("Unmodifiable") || clean.startsWith("▶") || clean.startsWith("◀")) {
                if (currentBlock != null) {
                    combinedSections.add(currentBlock.toString());
                    currentBlock = null;
                }
                continue;
            }

            // A line starting with '>' begins an active/passive ability
            if (clean.startsWith(">")) {
                if (currentBlock != null) {
                    combinedSections.add(currentBlock.toString());
                }
                currentBlock = new StringBuilder(clean.substring(1).trim());
            } else if (currentBlock != null) {
                currentBlock.append(" ").append(clean);
            } else {
                combinedSections.add(clean);
            }
        }
        if (currentBlock != null) {
            combinedSections.add(currentBlock.toString());
        }

        boolean parsedFromLore = false;

        // 3. Process combined sections for stats and structured traits
        for (String rawSection : combinedSections) {
            String clean = rawSection.replaceAll("[\"']+$", "").trim();
            String lower = clean.toLowerCase();

            // Exclude active abilities that buff others or require active clicking
            if (lower.contains("everyone around")
                    || lower.contains("all players around")
                    || lower.contains("give everyone")
                    || lower.contains("heal everyone")
                    || lower.contains("around you")
                    || lower.startsWith("right-click to")
                    || lower.startsWith("sneak-right-click to")
                    || lower.startsWith("left-click to")
                    || lower.startsWith("sneak-left-click to")
                    || lower.startsWith("shift-right-click to")
                    || lower.startsWith("shift-left-click to")) {
                continue;
            }

            // --- Numerical Stat Parsing (Armor, Toughness, Damage, Attack Speed, Health) ---
            if (lower.contains("armor:") || lower.matches(".*\\+\\d+\\s+armor.*")) {
                this.totalArmor += extractInt(clean);
                parsedFromLore = true;
            } else if (lower.contains("toughness:") || lower.matches(".*\\+\\d+\\s+toughness.*")) {
                this.totalToughness += extractInt(clean);
                parsedFromLore = true;
            } else if (lower.contains("attack speed:") || lower.contains("attack-speed:")) {
                this.totalAttackSpeedBonus += extractInt(clean);
                parsedFromLore = true;
            } else if (lower.contains("attack damage:") || (lower.contains("damage:") && !lower.contains("resistance")) || lower.matches(".*\\+\\d+(\\.\\d+)?\\s+damage.*")) {
                if (slot == EquipmentSlot.MAINHAND || isPassive) {
                    this.totalAttackDamage += extractDouble(clean);
                    parsedFromLore = true;
                }
            } else if (lower.contains("health") || lower.contains("heart")) {
                int healthVal = extractInt(clean);
                if (healthVal > 0) {
                    this.totalBonusHealth += healthVal;
                    parsedFromLore = true;
                }
            }

            // Determine activation location
            String req = "";
            if (lower.contains("top-left") || lower.contains("top left")) {
                req = "Top-Left Inv";
            } else if (lower.contains("hotbar")) {
                req = "Hotbar";
            } else if (lower.contains("when held") || lower.contains("while held")) {
                req = "Held";
            } else if (lower.contains("when worn") || lower.contains("while worn")) {
                req = "Worn";
            } else if (lower.contains("inventory")) {
                req = "Inventory";
            }

            // Identify potion effects: optional roman numeral ([IVXLCDM]+)? so un-tiered effects like Night Vision match
            java.util.regex.Matcher effMatcher = java.util.regex.Pattern.compile(
                    "(?i)\\b(Hero of the Village|Dolphins Grace|Fire Resistance|Water Breathing|Jump Boost|Night Vision|Conduit Power|Speed|Strength|Resistance|Haste|Regeneration|Absorption|Invisibility|Health Boost|Saturation)(?:\\s+([IVXLCDM]+))?"
            ).matcher(clean);

            boolean foundEffect = false;
            while (effMatcher.find()) {
                foundEffect = true;
                String name = formatEffectName(effMatcher.group(1));
                String roman = effMatcher.group(2) != null ? effMatcher.group(2).toUpperCase() : "";
                int level = roman.isEmpty() ? 1 : ROMAN_TO_INT.getOrDefault(roman, 1);

                addOrUpgradeTrait(name, level, req);
            }

            // Fallback for custom trigger actions (e.g. Spawn Egg drop, Dash, Deflection)
            if (!foundEffect && (lower.contains("spawn egg") || lower.contains("dash") || lower.contains("projectile deflection") || lower.contains("second chance"))) {
                String action = clean.replaceFirst("^[>•✦▶*\\-]+\\s*", "");
                int whenIdx = action.toLowerCase().indexOf(" when ");
                if (whenIdx != -1) action = action.substring(0, whenIdx).trim();
                this.equippedTraits.add(Component.literal("§6• §f" + action + (req.isEmpty() ? "" : " §8[" + req + "]")));
            }
        }

        // 4. Vanilla Attribute Modifiers fallback
        if (!parsedFromLore && slot != null) {
            ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (modifiers != null && !modifiers.modifiers().isEmpty()) {
                for (ItemAttributeModifiers.Entry modEntry : modifiers.modifiers()) {
                    if (modEntry.slot().test(slot)) {
                        var attr = modEntry.attribute();
                        double amount = modEntry.modifier().amount();

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

        // 5. Offhand Shields / Totems
        if (slot == EquipmentSlot.OFFHAND) {
            if (stack.is(Items.SHIELD)) {
                this.equippedTraits.add(Component.literal("§7• §fProjectile Deflection"));
            } else if (stack.is(Items.TOTEM_OF_UNDYING)) {
                this.totalBonusHealth += 4;
                this.equippedTraits.add(Component.literal("§7• §eSecond Chance (Totem)"));
            }
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
                this.playerPitch = Mth.clamp(this.pitchVelocity, -85.0f, 85.0f);
                this.pitchVelocity *= 0.85f;
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

        graphics.text(this.font, Component.literal("§6§lLoadout Stats"), statsX, statsY, 0xFFFFFFFF);
        graphics.horizontalLine(statsX, panelX + panelW - 16, statsY + 10, 0xFF4A1A5E);

        // Even 11px vertical spacing between each stat line
        graphics.text(this.font, Component.literal("§bArmor: §f+" + this.totalArmor), statsX, statsY + 14, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§9Armor Toughness: §f+" + (int) this.totalToughness), statsX, statsY + 25, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal(String.format("§cAttack Damage: §f%.1f", this.totalAttackDamage)), statsX, statsY + 36, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§eAttack Speed: §f+" + this.totalAttackSpeedBonus + "%"), statsX, statsY + 47, 0xFFFFFFFF);
        graphics.text(this.font, Component.literal("§aBonus Health: §f+" + this.totalBonusHealth + " ❤"), statsX, statsY + 58, 0xFFFFFFFF);

        // Traits header positioned cleanly below the stats block
        int traitsY = statsY + 74;
        List<Component> distinctTraits = getDistinctTraits();
        int maxTextWidth = (panelX + panelW - 20) - statsX;

        // Flatten multi-line wrapped formatted strings into printable lines
        List<net.minecraft.util.FormattedCharSequence> displayLines = new ArrayList<>();
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

        // Render tooltips on top of all components
        for (PreviewSlotWidget widget : this.slotWidgets) {
            widget.renderTooltip(graphics, mouseX, mouseY);
        }
        for (PreviewSlotWidget widget : this.passiveSlotWidgets) {
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

    public record TraitInfo(String effectName, int level, String rawLevel, String requirement, String customAction) {
        public String format() {
            if (customAction != null) {
                return customAction + (requirement.isEmpty() ? "" : " §8[" + requirement + "]");
            }
            String lvlStr = rawLevel.isEmpty() ? "" : " " + rawLevel;
            return effectName + lvlStr + (requirement.isEmpty() ? "" : " §8[" + requirement + "]");
        }
    }

    private static final Map<String, Integer> ROMAN_TO_INT = Map.of(
            "I", 1, "II", 2, "III", 3, "IV", 4, "V", 5
    );
    private static final Map<Integer, String> INT_TO_ROMAN = Map.of(
            1, "I", 2, "II", 3, "III", 4, "IV", 5, "V"
    );

    private final Map<String, TraitInfo> consolidatedTraits = new java.util.LinkedHashMap<>();

    private void addOrUpgradeTrait(String effect, int level, String requirement) {
        TraitInfo existing = consolidatedTraits.get(effect);
        if (existing == null || level > existing.level()) {
            boolean isUntiered = effect.equalsIgnoreCase("Night Vision")
                    || effect.equalsIgnoreCase("Fire Resistance")
                    || effect.equalsIgnoreCase("Water Breathing")
                    || effect.equalsIgnoreCase("Invisibility");

            String numeral = isUntiered && level == 1 ? "" : INT_TO_ROMAN.getOrDefault(level, "I");
            consolidatedTraits.put(effect, new TraitInfo(effect, level, numeral, requirement, null));
        }
    }

    private String formatEffectName(String raw) {
        String[] words = raw.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }


}