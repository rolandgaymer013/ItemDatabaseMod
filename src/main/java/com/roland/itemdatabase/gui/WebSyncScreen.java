package com.roland.itemdatabase.gui;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.CollectionManager;
import com.roland.itemdatabase.data.ItemEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-way clipboard synchronization screen between BlossomCraft's web Item Tracker
 * ({@code /itemtracker} and {@code /miscitemtracker}) and {@link CollectionManager}.
 * <p>
 * Performs a three-way diff before importing or exporting so players are warned about
 * mismatched items, wrong-tracker clipboard pastes, and can choose between Merge or Overwrite.
 */
public class WebSyncScreen extends Screen {

    private final Screen parent;
    private boolean targetCrate = true; // true = Crate Tracker (/itemtracker), false = Misc Tracker (/miscitemtracker)

    private CollectionManager.WebSyncDiff lastDiff = null;
    private String statusBanner = "§7Copy your code on the website, then click §bScan Clipboard§7—or export directly.";
    private int scrollOffset = 0;
    private int maxScroll = 0;

    private Button mergeBtn;
    private Button overwriteBtn;

    /**
     * Constructs the two-way website tracker synchronization screen.
     *
     * @param parent Parent screen to return to when closed.
     */
    public WebSyncScreen(Screen parent) {
        super(Component.literal("Web Tracker Two-Way Sync"));
        this.parent = parent;
    }

    /** Computes the horizontal origin of the sync panel, clamped within screen bounds. */
    private int getPanelX(int panelW) {
        int base = (this.width - panelW) / 2 + ModConfig.get().cardOffsetX;
        return Mth.clamp(base, 8, Math.max(8, this.width - panelW - 8));
    }

    /** Computes the vertical origin of the sync panel, clamped within screen bounds. */
    private int getPanelY(int panelH) {
        int base = (this.height - panelH) / 2 - 10 + ModConfig.get().cardOffsetY;
        return Mth.clamp(base, 8, Math.max(8, this.height - panelH - 34));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();

        int panelW = 450;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        // Top Row: Tracker Target Toggle + Scan Clipboard + Export to Clipboard
        int row1Y = panelY + 25;
        int gap = 6;
        int wToggle = 138;
        int wScan = 144;
        int wExport = 140;
        int r1Total = wToggle + wScan + wExport + (gap * 2);
        int r1Start = panelX + (panelW - r1Total) / 2;

        this.addRenderableWidget(Button.builder(
                Component.literal(this.targetCrate ? "§6Mode: Crate Tracker" : "§bMode: Misc Tracker"),
                b -> {
                    this.targetCrate = !this.targetCrate;
                    b.setMessage(Component.literal(this.targetCrate ? "§6Mode: Crate Tracker" : "§bMode: Misc Tracker"));
                    this.lastDiff = null;
                    this.scrollOffset = 0;
                    this.statusBanner = this.targetCrate
                            ? "§7Ready for §6/itemtracker §7(Crate items)."
                            : "§7Ready for §b/miscitemtracker §7(Misc items).";
                    updateActionStates();
                }).bounds(r1Start, row1Y, wToggle, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("§bScan Clipboard"), b -> scanClipboardForDiff())
                .bounds(r1Start + wToggle + gap, row1Y, wScan, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("§aExport → Clipboard"), b -> exportToClipboard())
                .bounds(r1Start + wToggle + wScan + (gap * 2), row1Y, wExport, 20).build());

        // Bottom Bar: Merge (Safe) | Overwrite (Match Web) | Back
        int btnY = panelY + panelH + 6;
        int wMerge = 136;
        int wOver = 146;
        int wBack = 64;
        int bTotal = wMerge + wOver + wBack + (gap * 2);
        int bStart = panelX + (panelW - bTotal) / 2;

        this.mergeBtn = Button.builder(Component.literal("§a✔ Merge (Keep Both)"), b -> applyImport(false))
                .bounds(bStart, btnY, wMerge, 20).build();
        this.addRenderableWidget(this.mergeBtn);

        this.overwriteBtn = Button.builder(Component.literal("§e⚠ Overwrite w/ Web"), b -> applyImport(true))
                .bounds(bStart + wMerge + gap, btnY, wOver, 20).build();
        this.addRenderableWidget(this.overwriteBtn);

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(bStart + wMerge + wOver + (gap * 2), btnY, wBack, 20).build());

        updateActionStates();
    }

    /** Enables or disables the Merge and Overwrite buttons based on the current diff state. */
    private void updateActionStates() {
        boolean canApply = this.lastDiff != null && !this.lastDiff.looksLikeWrongTracker()
                && (!this.lastDiff.onlyOnWeb().isEmpty() || !this.lastDiff.onlyInMod().isEmpty());
        if (this.mergeBtn != null) {
            this.mergeBtn.active = canApply && !this.lastDiff.onlyOnWeb().isEmpty();
        }
        if (this.overwriteBtn != null) {
            this.overwriteBtn.active = canApply;
        }
    }

    /** Reads the system clipboard and runs a three-way diff against the active tracker category. */
    private void scanClipboardForDiff() {
        if (this.minecraft == null) return;
        String clip = this.minecraft.keyboardHandler.getClipboard();
        if (clip == null || clip.isBlank() || !clip.contains("[")) {
            this.lastDiff = null;
            this.statusBanner = "§cClipboard does not contain a valid website JSON array (e.g. [14, 27, 89]).";
            updateActionStates();
            return;
        }

        this.lastDiff = CollectionManager.analyzeWebClipboard(clip, this.targetCrate);
        this.scrollOffset = 0;

        if (this.lastDiff.looksLikeWrongTracker()) {
            this.statusBanner = "§c⚠ Collision Alert: Those " + this.lastDiff.oppositeHits()
                    + " IDs belong to the " + (this.targetCrate ? "Misc" : "Crate") + " Tracker! Switch Mode above.";
        } else if (!this.lastDiff.hasCollisions()) {
            this.statusBanner = "§a✔ Perfect Sync! Both the website and your mod match (" + this.lastDiff.inBoth().size() + " items).";
        } else {
            this.statusBanner = "§e⚠ Differences Detected: §a+" + this.lastDiff.onlyOnWeb().size()
                    + " new on Web §7| §6" + this.lastDiff.onlyInMod().size() + " only in Mod.";
        }
        updateActionStates();
    }

    /** Applies the scanned website diff to {@link CollectionManager} via merge or overwrite. */
    private void applyImport(boolean overwrite) {
        if (this.lastDiff == null || this.minecraft == null) return;
        int added = CollectionManager.applyWebImport(this.lastDiff, overwrite);
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.15f));

        String modeLabel = overwrite ? "Overwrote local list to match website" : "Merged website items (kept mod-only items)";
        this.statusBanner = "§a✔ " + modeLabel + " (+" + added + " added).";
        this.lastDiff = CollectionManager.analyzeWebClipboard(this.minecraft.keyboardHandler.getClipboard(), this.targetCrate);
        updateActionStates();
    }

    /** Exports the local owned IDs for the active category to the clipboard and warns on unmerged web items. */
    private void exportToClipboard() {
        if (this.minecraft == null) return;
        String currentClip = this.minecraft.keyboardHandler.getClipboard();
        CollectionManager.WebSyncDiff preCheck = (currentClip != null && currentClip.contains("["))
                ? CollectionManager.analyzeWebClipboard(currentClip, this.targetCrate)
                : null;

        String jsonOut = CollectionManager.exportWebTrackerJson(this.targetCrate);
        this.minecraft.keyboardHandler.setClipboard(jsonOut);
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));

        if (preCheck != null && !preCheck.looksLikeWrongTracker() && !preCheck.onlyOnWeb().isEmpty()) {
            this.lastDiff = preCheck;
            this.statusBanner = "§eCopied to clipboard! ⚠ Warning: " + preCheck.onlyOnWeb().size()
                    + " item(s) on your clipboard were NOT in your mod and will uncheck on the site unless merged first.";
        } else {
            this.statusBanner = "§a✔ Copied " + (this.targetCrate ? "Crate" : "Misc")
                    + " JSON to clipboard! Paste into 'Import from text' on the website.";
        }
        updateActionStates();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.maxScroll > 0) {
            this.scrollOffset = Mth.clamp(this.scrollOffset - (int) (verticalAmount * 14), 0, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int panelW = 450;
        int panelH = 225;
        int panelX = getPanelX(panelW);
        int panelY = getPanelY(panelH);

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF14071D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF4A1A5E);

        graphics.centeredText(this.font, Component.literal("§6§lTwo-Way Website Tracker Sync & Diff Inspector"), panelX + (panelW / 2), panelY + 9, 0xFFFFFFFF);

        // Status Banner Box
        int bannerX = panelX + 8;
        int bannerY = panelY + 50;
        int bannerW = panelW - 16;
        int bannerH = 22;
        graphics.fill(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, 0xFF0D0414);
        graphics.outline(bannerX, bannerY, bannerW, bannerH, 0xFF692A82);

        List<FormattedCharSequence> bannerLines = this.font.split(Component.literal(this.statusBanner), bannerW - 10);
        int by = bannerY + (bannerLines.size() > 1 ? 2 : 7);
        for (FormattedCharSequence line : bannerLines) {
            graphics.text(this.font, line, bannerX + 6, by, 0xFFFFFFFF);
            by += 9;
        }

        // Diff Breakdown Viewport
        int boxX = panelX + 8;
        int boxY = bannerY + bannerH + 6;
        int boxW = panelW - 16;
        int boxBottom = panelY + panelH - 8;
        int boxH = boxBottom - boxY;

        graphics.fill(boxX, boxY, boxX + boxW, boxBottom, 0xFF0D0414);
        graphics.outline(boxX, boxY, boxW, boxH, 0xFF351242);

        List<Component> lines = new ArrayList<>();
        if (this.lastDiff == null) {
            lines.add(Component.literal("§eHow to Sync Without Losing Progress:"));
            lines.add(Component.literal("§71. Select §6Crate Tracker §7(/itemtracker) or §bMisc Tracker §7(/miscitemtracker) above."));
            lines.add(Component.literal("§72. On the website, click §fCopy To Clipboard§7, then click §bScan Clipboard §7here."));
            lines.add(Component.literal("§73. Review any collisions below and click §aMerge (Keep Both) §7to combine lists safely."));
            lines.add(Component.literal("§74. Click §aExport → Clipboard §7and paste into §fImport from text §7on the website."));
        } else {
            lines.add(Component.literal("§a✔ Already Synced (Both): §f" + this.lastDiff.inBoth().size() + " items"));

            lines.add(Component.literal(""));
            lines.add(Component.literal("§b▼ Only on Website (" + this.lastDiff.onlyOnWeb().size() + " — will be added on Merge):"));
            if (this.lastDiff.onlyOnWeb().isEmpty()) {
                lines.add(Component.literal("  §8• None"));
            } else {
                for (ItemEntry it : this.lastDiff.onlyOnWeb()) {
                    lines.add(Component.literal("  §a+ §f" + it.getCleanName() + " §8(#" + it.id() + ")"));
                }
            }

            lines.add(Component.literal(""));
            lines.add(Component.literal("§6▼ Only in Mod (" + this.lastDiff.onlyInMod().size() + " — kept on Merge, removed on Overwrite):"));
            if (this.lastDiff.onlyInMod().isEmpty()) {
                lines.add(Component.literal("  §8• None"));
            } else {
                for (ItemEntry it : this.lastDiff.onlyInMod()) {
                    lines.add(Component.literal("  §e• §f" + it.getCleanName() + " §8(#" + it.id() + ")"));
                }
            }

            if (!this.lastDiff.invalidIds().isEmpty()) {
                lines.add(Component.literal(""));
                lines.add(Component.literal("§c⚠ Unrecognized IDs (" + this.lastDiff.invalidIds().size() + "): §7" + this.lastDiff.invalidIds()));
            }
        }

        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component c : lines) {
            wrapped.addAll(this.font.split(c, boxW - 16));
        }

        int totalH = wrapped.size() * 10;
        this.maxScroll = Math.max(0, totalH - (boxH - 6));
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0, this.maxScroll);

        graphics.enableScissor(boxX + 2, boxY + 2, boxX + boxW - 2, boxBottom - 2);
        int ty = boxY + 4 - this.scrollOffset;
        for (FormattedCharSequence line : wrapped) {
            if (ty + 9 >= boxY && ty <= boxBottom) {
                graphics.text(this.font, line, boxX + 6, ty, 0xFFFFFFFF);
            }
            ty += 10;
        }
        graphics.disableScissor();

        if (this.maxScroll > 0) {
            int barX = boxX + boxW - 5;
            int viewH = boxH - 4;
            int thumbH = Math.max(14, (viewH * viewH) / totalH);
            int thumbY = boxY + 2 + ((viewH - thumbH) * this.scrollOffset) / this.maxScroll;
            graphics.fill(barX, boxY + 2, barX + 2, boxBottom - 2, 0xFF220D30);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFFFAA00);
        }

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}