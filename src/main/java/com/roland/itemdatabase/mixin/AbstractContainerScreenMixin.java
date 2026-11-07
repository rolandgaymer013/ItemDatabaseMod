package com.roland.itemdatabase.mixin;

import com.roland.itemdatabase.data.CollectionManager;
import com.roland.itemdatabase.data.CrateContainerScanner;
import com.roland.itemdatabase.data.CrateEntry;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import com.roland.itemdatabase.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> extends Screen {

    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    protected T menu;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    @Unique
    private static final Pattern STRIP_FORMATTING = Pattern.compile("(?i)[&§][0-9a-fk-or]|[^a-zA-Z0-9]");

    @SuppressWarnings("MixinAnnotationTarget")
    @Inject(method = "init", at = @At("RETURN"))
    private void itemDatabase$addScanButton(CallbackInfo ci) {
        // 1. Only show for players who have an admin API token configured
        if (!ModConfig.get().hasAdminToken()) {
            return;
        }

        // 2. Exclude Creative Mode tab screens and standard player survival inventory screens
        Object current = this;
        if (current instanceof CreativeModeInventoryScreen || current instanceof InventoryScreen) {
            return;
        }

        // 3. Exclude server navigation menus, personal storage, backpacks, and ender chests by title
        Component titleComp = this.getTitle();
        if (titleComp != null) {
            String rawTitle = titleComp.getString().toLowerCase().trim();
            if (rawTitle.contains("select a server")
                    || rawTitle.contains("server selector")
                    || rawTitle.contains("hub")
                    || rawTitle.contains("navigator")
                    || rawTitle.contains("warps")
                    || rawTitle.contains("menu")
                    || rawTitle.contains("backpack")
                    || rawTitle.contains("ender chest")) {
                return;
            }
        }

        // 4. Ensure there is a top container section (exclude screens with <= 36 total slots)
        if (this.menu == null || this.menu.slots.size() <= 36) {
            return;
        }

        // 5. Only show if crates are loaded in the database
        List<CrateEntry> crates = new ArrayList<>(DatabaseManager.CRATES.values());
        if (crates.isEmpty()) return;

        CrateEntry defaultCrate = crates.get(0);

        int btnW = 54;
        int btnH = 14;
        int btnX = this.leftPos + this.imageWidth - btnW - 6;
        int btnY = this.topPos + 4;

        Button scanBtn = Button.builder(Component.literal("§d⚡ Scan"), btn -> {
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
            CrateContainerScanner.scanAndOpenReview(screen, defaultCrate);
        }).bounds(btnX, btnY, btnW, btnH).build();

        this.addRenderableWidget(scanBtn);
    }

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void itemDatabase$highlightWishlistSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (CollectionManager.getWishlistCount() == 0 || this.menu == null) return;

        float pulse = (float) (Math.sin(System.currentTimeMillis() / 220.0) * 0.5 + 0.5);
        int alpha = 0x50 + (int) (pulse * 0x80);
        int pulseBackground = (alpha << 24) | 0x00FFD700;

        for (Slot slot : this.menu.slots) {
            if (!slot.isActive()) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            if (itemDatabase$isWishlisted(stack)) {
                int slotX = slot.x;
                int slotY = slot.y;

                graphics.fill(slotX, slotY, slotX + 16, slotY + 16, pulseBackground);
                graphics.outline(slotX - 1, slotY - 1, 18, 18, 0xFFFFD700);
                graphics.outline(slotX, slotY, 16, 16, 0x88FFAA00);

                int badgeX = slotX + 11;
                int badgeY = slotY + 1;
                graphics.fill(badgeX + 2, badgeY,     badgeX + 3, badgeY + 1, 0xFFFFD700);
                graphics.fill(badgeX + 1, badgeY + 1, badgeX + 4, badgeY + 2, 0xFFFFD700);
                graphics.fill(badgeX,     badgeY + 2, badgeX + 5, badgeY + 3, 0xFFFFD700);
                graphics.fill(badgeX + 1, badgeY + 3, badgeX + 4, badgeY + 4, 0xFFFFD700);
                graphics.fill(badgeX + 2, badgeY + 4, badgeX + 3, badgeY + 5, 0xFFFFD700);
            }
        }
    }

    @Unique
    private boolean itemDatabase$isWishlisted(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            var tag = customData.copyTag();
            var idOpt = tag.getString("id");
            if (idOpt.isPresent()) {
                String customId = idOpt.get().toLowerCase();
                for (ItemEntry it : DatabaseManager.getAllItems()) {
                    if (it.id().equalsIgnoreCase(customId) && CollectionManager.isWishlisted(it)) {
                        return true;
                    }
                }
            }
        }

        var hoverName = stack.get(DataComponents.CUSTOM_NAME);
        if (hoverName != null) {
            String cleanStackName = STRIP_FORMATTING.matcher(hoverName.getString()).replaceAll("").toLowerCase().trim();
            if (!cleanStackName.isEmpty()) {
                for (ItemEntry it : DatabaseManager.getAllItems()) {
                    if (CollectionManager.isWishlisted(it)) {
                        String cleanCandidate = STRIP_FORMATTING.matcher(it.itemName()).replaceAll("").toLowerCase().trim();
                        if (cleanCandidate.equals(cleanStackName) || cleanCandidate.contains(cleanStackName)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}