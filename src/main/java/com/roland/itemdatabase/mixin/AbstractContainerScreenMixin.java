package com.roland.itemdatabase.mixin;

import com.roland.itemdatabase.config.ModConfig;
import com.roland.itemdatabase.data.CollectionContainerScanner;
import com.roland.itemdatabase.data.CollectionManager;
import com.roland.itemdatabase.data.CrateContainerScanner;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin extends Screen {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow protected AbstractContainerMenu menu;

    @Unique private static final Set<String> CACHED_WISHLIST_KEYS = new HashSet<>();
    @Unique private static int lastWishlistCount = -1;

    // Slot-level cache: stores whether slot i has a wishlist item
    @Unique private final boolean[] slotWishlistCache = new boolean[128];
    @Unique private final ItemStack[] slotStackCache = new ItemStack[128];

    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void injectExteriorTabs(CallbackInfo ci) {
        // Reset slot cache on screen open
        for (int i = 0; i < slotStackCache.length; i++) {
            slotStackCache[i] = ItemStack.EMPTY;
            slotWishlistCache[i] = false;
        }

        rebuildWishlistLookupCache();

        if (!(this.menu instanceof ChestMenu || this.menu instanceof ShulkerBoxMenu)) {
            return;
        }

        String titlePlain = ChatFormatting.stripFormatting(this.title.getString()).toLowerCase().trim();
        if (titlePlain.contains("auction")
                || titlePlain.contains("ender chest")
                || titlePlain.contains("backpack")
                || titlePlain.contains("vault")
                || titlePlain.contains("disposal")
                || titlePlain.contains("trash")) {
            return;
        }

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        boolean isAdmin = ModConfig.get().hasAdminToken();

        int tabHeight = 16;
        int tabY = this.topPos - tabHeight - 1;
        int currentRightX = this.leftPos + this.imageWidth - 4;

        if (isAdmin) {
            int adminWidth = 46;
            int adminX = currentRightX - adminWidth;
            currentRightX = adminX - 3;

            Button adminBtn = Button.builder(Component.literal("§d⚡ Admin"), b -> {
                CrateContainerScanner.scanAndOpenReview(screen, null);
            }).bounds(adminX, tabY, adminWidth, tabHeight).build();

            adminBtn.setTooltip(Tooltip.create(Component.literal("Open Admin Staging & Upload Screen")));
            this.addRenderableWidget(adminBtn);
        }

        int collectWidth = 52;
        int collectX = currentRightX - collectWidth;

        Button collectBtn = Button.builder(Component.literal("§a📦 Collect"), b -> {
            CollectionContainerScanner.scanAndOpenReview(screen);
        }).bounds(collectX, tabY, collectWidth, tabHeight).build();

        collectBtn.setTooltip(Tooltip.create(Component.literal("Scan chest & choose items to add to your Collection")));
        this.addRenderableWidget(collectBtn);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void highlightWishlistSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.menu == null) return;
        if (CACHED_WISHLIST_KEYS.isEmpty()) return;

        int containerSlotCount = Math.min(Math.max(0, this.menu.slots.size() - 36), slotWishlistCache.length);

        for (int i = 0; i < containerSlotCount; i++) {
            Slot slot = this.menu.slots.get(i);
            if (!slot.hasItem()) {
                slotWishlistCache[i] = false;
                slotStackCache[i] = ItemStack.EMPTY;
                continue;
            }

            ItemStack currentStack = slot.getItem();

            // Re-evaluate matching only if slot item reference or count changes
            if (slotStackCache[i] != currentStack) {
                slotStackCache[i] = currentStack;
                slotWishlistCache[i] = isStackWishlistedFast(currentStack);
            }

            if (slotWishlistCache[i]) {
                int slotScreenX = this.leftPos + slot.x;
                int slotScreenY = this.topPos + slot.y;

                graphics.fill(slotScreenX, slotScreenY, slotScreenX + 16, slotScreenY + 16, 0x55FFD700);
                graphics.outline(slotScreenX - 1, slotScreenY - 1, 18, 18, 0xFFFFD700);
            }
        }
    }

    @Unique
    private static void rebuildWishlistLookupCache() {
        int currentCount = CollectionManager.getWishlistCount();
        if (currentCount == lastWishlistCount && !CACHED_WISHLIST_KEYS.isEmpty()) {
            return;
        }

        CACHED_WISHLIST_KEYS.clear();
        for (ItemEntry item : DatabaseManager.getAllItems()) {
            if (CollectionManager.isWishlisted(item)) {
                String rawClean = CollectionContainerScanner.stripSectionSignsOnly(item.itemName()).toLowerCase();
                String alphanum = item.itemName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                String cleanAlphanum = item.getCleanName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

                if (!rawClean.isBlank()) CACHED_WISHLIST_KEYS.add(rawClean);
                if (!alphanum.isBlank()) CACHED_WISHLIST_KEYS.add(alphanum);
                if (!cleanAlphanum.isBlank()) CACHED_WISHLIST_KEYS.add(cleanAlphanum);
            }
        }
        lastWishlistCount = currentCount;
    }

    @Unique
    private boolean isStackWishlistedFast(ItemStack stack) {
        if (stack.isEmpty()) return false;

        Component nameComp = stack.get(DataComponents.CUSTOM_NAME);
        if (nameComp == null) {
            nameComp = stack.getHoverName();
        }

        StringBuilder rawLeaves = new StringBuilder();
        nameComp.visit((Style style, String text) -> {
            rawLeaves.append(text);
            return Optional.empty();
        }, Style.EMPTY);

        String raw = rawLeaves.toString().isEmpty() ? nameComp.getString() : rawLeaves.toString();
        String cleanDisplay = CollectionContainerScanner.stripSectionSignsOnly(raw).toLowerCase();

        if (CACHED_WISHLIST_KEYS.contains(cleanDisplay)) {
            return true;
        }

        String normAlphanum = cleanDisplay.replaceAll("[^a-zA-Z0-9]", "");
        return !normAlphanum.isBlank() && CACHED_WISHLIST_KEYS.contains(normAlphanum);
    }
}