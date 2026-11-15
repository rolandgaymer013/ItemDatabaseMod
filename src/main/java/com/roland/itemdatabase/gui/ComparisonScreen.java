package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.AdminApiClient;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.data.LoadoutAnalyzer;
import com.roland.itemdatabase.data.RawDataParser;
import com.roland.itemdatabase.data.TextureDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side-by-side stat and passive trait delta comparison screen for individual items or full armor sets.
 * <p>
 * Compares a pinned Candidate ([A]) against either a second pinned target or the player's
 * currently equipped gear ([B]), displaying color-coded stat differences, trait tier upgrades,
 * and lost traits.
 */
public class ComparisonScreen extends Screen {

    /**
     * Immutable snapshot of an item, full armor set, or equipped player loadout being compared.
     */
    public record CompareTarget(
            String title,
            String subtitle,
            List<ItemEntry> entries,
            List<ItemStack> stacks,
            LoadoutAnalyzer.LoadoutSummary summary,
            String fullSetBonus
    ) {
        /** Builds a single-item {@link CompareTarget} from a database {@link ItemEntry}. */
        public static CompareTarget fromItem(ItemEntry entry) {
            ItemStack stack = RawDataParser.parseItemStack(entry);
            EquipmentSlot slot = entry.getTargetEquipmentSlot();
            if (slot == null) slot = EquipmentSlot.MAINHAND;

            List<LoadoutAnalyzer.SlotInput> inputs = new ArrayList<>();
            if (!stack.isEmpty()) {
                inputs.add(new LoadoutAnalyzer.SlotInput(stack, entry, slot, false, ""));
            }
            LoadoutAnalyzer.LoadoutSummary sum = LoadoutAnalyzer.analyze(inputs);
            String sub = entry.rarityHuman() != null && !entry.rarityHuman().isBlank()
                    ? entry.rarityHuman()
                    : (entry.isCrateItem() ? "Crate Item" : "Misc Item");

            return new CompareTarget(
                    entry.getCleanName(),
                    sub,
                    List.of(entry),
                    stack.isEmpty() ? List.of() : List.of(stack),
                    sum,
                    ""
            );
        }

        /** Builds a multi-piece armor set {@link CompareTarget} from a registered {@link AdminApiClient.SetRow}. */
        public static CompareTarget fromSet(AdminApiClient.SetRow set) {
            List<ItemEntry> entries = new ArrayList<>();
            List<ItemStack> stacks = new ArrayList<>();
            List<LoadoutAnalyzer.SlotInput> inputs = new ArrayList<>();

            for (int id : set.itemIds()) {
                ItemEntry entry = DatabaseManager.findItemById(id);
                if (entry == null) continue;
                entries.add(entry);
                ItemStack stack = RawDataParser.parseItemStack(entry);
                if (!stack.isEmpty()) {
                    stacks.add(stack);
                    EquipmentSlot slot = entry.getTargetEquipmentSlot();
                    if (slot != null) {
                        inputs.add(new LoadoutAnalyzer.SlotInput(stack, entry, slot, false, ""));
                    }
                }
            }
            LoadoutAnalyzer.LoadoutSummary sum = LoadoutAnalyzer.analyze(inputs);
            String bonus = set.description() != null ? set.description() : "";
            return new CompareTarget(
                    set.name(),
                    entries.size() + "-Piece Armor Set",
                    entries,
                    stacks,
                    sum,
                    bonus
            );
        }

        /**
         * Snapshots the local player's currently equipped armor (when comparing against a set)
         * or matching equipment slot (when comparing against a single item).
         */
        public static CompareTarget fromEquippedPlayer(CompareTarget reference) {
            Minecraft mc = Minecraft.getInstance();
            List<ItemStack> stacks = new ArrayList<>();
            List<LoadoutAnalyzer.SlotInput> inputs = new ArrayList<>();
            Map<EquipmentSlot, ItemStack> armorMap = new EnumMap<>(EquipmentSlot.class);

            if (mc.player != null) {
                boolean isArmorSet = reference != null && reference.entries().size() > 1;
                if (isArmorSet) {
                    for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                        ItemStack eq = mc.player.getItemBySlot(slot);
                        if (!eq.isEmpty()) {
                            stacks.add(eq.copy());
                            armorMap.put(slot, eq);
                            inputs.add(new LoadoutAnalyzer.SlotInput(eq, null, slot, false, ""));
                        }
                    }
                } else {
                    EquipmentSlot targetSlot = EquipmentSlot.MAINHAND;
                    if (reference != null && !reference.entries().isEmpty()) {
                        EquipmentSlot detected = reference.entries().get(0).getTargetEquipmentSlot();
                        if (detected != null) targetSlot = detected;
                    }
                    ItemStack eq = mc.player.getItemBySlot(targetSlot);
                    if (!eq.isEmpty()) {
                        stacks.add(eq.copy());
                        inputs.add(new LoadoutAnalyzer.SlotInput(eq, null, targetSlot, false, ""));
                    }
                }
            }

            LoadoutAnalyzer.LoadoutSummary sum = LoadoutAnalyzer.analyze(inputs);
            AdminApiClient.SetRow activeSet = LoadoutAnalyzer.findCompletedSet(null, armorMap);
            String bonus = (activeSet != null && activeSet.description() != null) ? activeSet.description() : "";
            String label = stacks.isEmpty() ? "Equipped Gear (Empty)" : "Your Equipped Gear";
            return new CompareTarget(
                    label,
                    stacks.size() + " item(s) equipped",
                    List.of(),
                    stacks,
                    sum,
                    bonus
            );
        }
    }

    // Persistent pinned slots across screen navigations
    private static CompareTarget pinnedA = null;
    private static CompareTarget pinnedB = null;

    /**
     * Pins a {@link CompareTarget} into slot [A], shifting any prior candidate into baseline slot [B].
     */
    public static void pinTarget(CompareTarget target) {
        if (target == null) return;
        if (pinnedA == null) {
            pinnedA = target;
        } else if (pinnedB == null && !pinnedA.title().equalsIgnoreCase(target.title())) {
            pinnedB = target;
        } else {
            pinnedB = pinnedA;
            pinnedA = target;
        }
    }

    /** Returns true if at least one comparison target is currently pinned. */
    public static boolean hasPinnedTarget() {
        return pinnedA != null;
    }

    /** Returns the display title of the primary pinned candidate, or an empty string if none. */
    public static String getPinnedSummaryLabel() {
        if (pinnedA == null) return "";
        return pinnedA.title();
    }

    private final Screen parent;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    /**
     * Constructs the Side-by-Side Comparison screen, defaulting baseline [B] to the player's
     * currently equipped gear when only candidate [A] is pinned.
     */
    public ComparisonScreen(Screen parent) {
        super(Component.literal("Side-by-Side Loadout & Item Comparison"));
        this.parent = parent;
        if (pinnedA != null && pinnedB == null) {
            pinnedB = CompareTarget.fromEquippedPlayer(pinnedA);
        }
    }

    private int getPanelX(int panelW) {
        int base = (this.width - panelW) / 2 + ModConfig.get().cardOffsetX;
        return Mth.clamp(base, 8, Math.max(8, this.width - panelW - 8));
    }

    private int getPanelY(int panelH) {
        int base = (this.height - panelH) / 2 - 10 + ModConfig.get().cardOffsetY;
        return Mth.clamp(base, 8, Math.max(8, this.height - panelH - 34));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        int panelW = 450;
        int panelH = 220;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        int btnY = panelY + panelH + 6;
        int gap = 6;
        int wSwap = 88;
        int wEquip = 104;
        int wClear = 76;
        int wBack = 56;
        int totalW = wSwap + wEquip + wClear + wBack + (gap * 3);
        int startX = panelX + (panelW - totalW) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("⇄ Swap A / B"), b -> {
            CompareTarget tmp = pinnedA;
            pinnedA = pinnedB;
            pinnedB = tmp;
            this.scrollOffset = 0;
        }).bounds(startX, btnY, wSwap, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("vs Equipped Gear"), b -> {
            pinnedB = CompareTarget.fromEquippedPlayer(pinnedA);
            this.scrollOffset = 0;
        }).bounds(startX + wSwap + gap, btnY, wEquip, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Clear Pins"), b -> {
            pinnedA = null;
            pinnedB = null;
            this.scrollOffset = 0;
        }).bounds(startX + wSwap + wEquip + (gap * 2), btnY, wClear, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(startX + wSwap + wEquip + wClear + (gap * 3), btnY, wBack, 20)
                .build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.maxScroll > 0) {
            this.scrollOffset = Mth.clamp(this.scrollOffset - (int) (verticalAmount * 12), 0, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int panelW = 450;
        int panelH = 220;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, Component.literal("§6§lSide-by-Side Comparison & Stat Deltas"), panelX + (panelW / 2), panelY + 9, 0xFFFFFFFF);

        int colW = (panelW - 24) / 2;
        int leftX = panelX + 8;
        int rightX = leftX + colW + 8;
        int bodyY = panelY + 24;
        int bodyH = panelH - 32;

        this.maxScroll = 0;
        renderColumn(graphics, leftX, bodyY, colW, bodyH, "§b[A] Candidate", pinnedA, pinnedB, true);
        renderColumn(graphics, rightX, bodyY, colW, bodyH, "§d[B] Baseline", pinnedB, pinnedA, false);

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    /** Renders a single comparison column with piece icons, stat totals, optional deltas, and scrollable traits. */
    private void renderColumn(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                              String tag, CompareTarget target, CompareTarget other, boolean showDeltas) {
        graphics.fill(x, y, x + w, y + h, 0xFF0D0414);
        graphics.outline(x, y, w, h, showDeltas ? 0xFF692A82 : 0xFF351242);

        if (target == null) {
            graphics.centeredText(this.font, Component.literal(tag + " §8(Empty)"), x + (w / 2), y + (h / 2) - 10, 0xFF888888);
            graphics.centeredText(this.font, Component.literal("§7Pin an item or set from the catalog"), x + (w / 2), y + (h / 2) + 4, 0xFF666666);
            return;
        }

        int curY = y + 6;
        graphics.text(this.font, Component.literal(tag), x + 6, curY, 0xFFFFFFFF);
        curY += 10;

        String titleStr = this.font.plainSubstrByWidth(target.title(), w - 14);
        graphics.text(this.font, Component.literal("§f§l" + titleStr), x + 6, curY, 0xFFFFFFFF);
        curY += 10;
        graphics.text(this.font, Component.literal("§7" + target.subtitle()), x + 6, curY, 0xFFFFFFFF);
        curY += 12;

        // Icon strip (up to 4 pieces)
        int iconSize = 18;
        int iconX = x + 6;
        if (!target.entries().isEmpty()) {
            for (int i = 0; i < Math.min(4, target.entries().size()); i++) {
                ItemEntry entry = target.entries().get(i);
                graphics.fill(iconX, curY, iconX + iconSize, curY + iconSize, 0xFF1A0926);
                graphics.outline(iconX, curY, iconSize, iconSize, 0xFF4A1A5E);
                if (entry.iconUrl() != null && !entry.iconUrl().isBlank()) {
                    String key = (entry.isCrateItem() ? "crate_" : "misc_") + entry.id();
                    Identifier tex = TextureDownloader.getOrLoadTexture(entry.iconUrl(), key);
                    if (tex != null) {
                        graphics.blit(RenderPipelines.GUI_TEXTURED, tex, iconX + 1, curY + 1, 0.0F, 0.0F, 16, 16, 16, 16);
                    }
                }
                iconX += iconSize + 4;
            }
            curY += iconSize + 5;
        } else if (!target.stacks().isEmpty()) {
            for (int i = 0; i < Math.min(4, target.stacks().size()); i++) {
                ItemStack st = target.stacks().get(i);
                graphics.fill(iconX, curY, iconX + iconSize, curY + iconSize, 0xFF1A0926);
                graphics.outline(iconX, curY, iconSize, iconSize, 0xFF4A1A5E);
                graphics.item(st, iconX + 1, curY + 1);
                iconX += iconSize + 4;
            }
            curY += iconSize + 5;
        }

        LoadoutAnalyzer.LoadoutSummary s = target.summary();
        LoadoutAnalyzer.LoadoutSummary o = (other != null) ? other.summary() : null;

        graphics.horizontalLine(x + 6, x + w - 6, curY, 0xFF351242);
        curY += 4;

        graphics.text(this.font, Component.literal("§bArmor: §f+" + s.totalArmor()
                + (showDeltas && o != null ? formatIntDelta(s.totalArmor() - o.totalArmor()) : "")), x + 6, curY, 0xFFFFFFFF);
        curY += 10;

        graphics.text(this.font, Component.literal("§9Toughness: §f+" + (int) s.totalToughness()
                + (showDeltas && o != null ? formatIntDelta((int) (s.totalToughness() - o.totalToughness())) : "")), x + 6, curY, 0xFFFFFFFF);
        curY += 10;

        graphics.text(this.font, Component.literal(String.format("§cDamage: §f%.1f", s.totalAttackDamage())
                + (showDeltas && o != null ? formatDoubleDelta(s.totalAttackDamage() - o.totalAttackDamage()) : "")), x + 6, curY, 0xFFFFFFFF);
        curY += 10;

        graphics.text(this.font, Component.literal("§eAtk Speed: §f+" + s.totalAttackSpeedBonus() + "%"
                + (showDeltas && o != null ? formatIntDelta(s.totalAttackSpeedBonus() - o.totalAttackSpeedBonus(), "%") : "")), x + 6, curY, 0xFFFFFFFF);
        curY += 10;

        graphics.text(this.font, Component.literal("§aHealth: §f+" + s.totalBonusHealth() + " ❤"
                + (showDeltas && o != null ? formatIntDelta(s.totalBonusHealth() - o.totalBonusHealth(), " ❤") : "")), x + 6, curY, 0xFFFFFFFF);
        curY += 12;

        // Traits & Deltas
        int traitsTop = curY;
        int traitsBottom = y + h - 5;
        int viewH = Math.max(10, traitsBottom - traitsTop);

        List<Component> traitLinesRaw = new ArrayList<>();
        if (!target.fullSetBonus().isBlank()) {
            traitLinesRaw.add(Component.literal("§6★ Set Bonus: §f" + target.fullSetBonus()));
        }

        Map<String, LoadoutAnalyzer.TraitInfo> otherMap = new LinkedHashMap<>();
        if (o != null) {
            for (LoadoutAnalyzer.TraitInfo t : o.traits()) {
                otherMap.put(t.effectName().toLowerCase(), t);
            }
        }

        for (LoadoutAnalyzer.TraitInfo t : s.traits()) {
            String key = t.effectName().toLowerCase();
            String badge = "";
            if (showDeltas && o != null) {
                LoadoutAnalyzer.TraitInfo prev = otherMap.get(key);
                if (prev == null) {
                    badge = " §a[+NEW]";
                } else if (t.level() > prev.level()) {
                    badge = " §a[▲ +" + (t.level() - prev.level()) + "]";
                } else if (t.level() < prev.level()) {
                    badge = " §c[▼ -" + (prev.level() - t.level()) + "]";
                }
            }
            traitLinesRaw.add(Component.literal("§6• §f" + t.format(false) + badge));
        }

        if (showDeltas && o != null) {
            Map<String, LoadoutAnalyzer.TraitInfo> selfMap = new LinkedHashMap<>();
            for (LoadoutAnalyzer.TraitInfo t : s.traits()) {
                selfMap.put(t.effectName().toLowerCase(), t);
            }
            for (LoadoutAnalyzer.TraitInfo lost : o.traits()) {
                if (!selfMap.containsKey(lost.effectName().toLowerCase())) {
                    traitLinesRaw.add(Component.literal("§c✕ Lost: §7" + lost.format(false)));
                }
            }
        }

        if (traitLinesRaw.isEmpty()) {
            traitLinesRaw.add(Component.literal("§8• No passive traits"));
        }

        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component c : traitLinesRaw) {
            wrapped.addAll(this.font.split(c, w - 14));
        }

        int totalH = wrapped.size() * 10;
        this.maxScroll = Math.max(this.maxScroll, Math.max(0, totalH - viewH));
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0, this.maxScroll);

        graphics.enableScissor(x + 4, traitsTop, x + w - 4, traitsBottom);
        int ty = traitsTop - this.scrollOffset;
        for (FormattedCharSequence line : wrapped) {
            if (ty + 9 >= traitsTop && ty <= traitsBottom) {
                graphics.text(this.font, line, x + 6, ty, 0xFFFFFFFF);
            }
            ty += 10;
        }
        graphics.disableScissor();
    }

    /** Formats a signed integer difference without units. */
    private String formatIntDelta(int delta) {
        return formatIntDelta(delta, "");
    }

    /** Formats a signed integer difference with an optional trailing unit suffix. */
    private String formatIntDelta(int delta, String unit) {
        if (delta == 0) return "";
        return delta > 0 ? " §a(+" + delta + unit + ")" : " §c(" + delta + unit + ")";
    }

    /** Formats a signed floating-point difference to one decimal place. */
    private String formatDoubleDelta(double delta) {
        if (Math.abs(delta) < 0.05) return "";
        return delta > 0
                ? String.format(" §a(+%.1f)", delta)
                : String.format(" §c(%.1f)", delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}