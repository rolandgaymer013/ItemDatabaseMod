package com.roland.itemdatabase.mixin;

import com.roland.itemdatabase.data.CollectionManager;
import com.roland.itemdatabase.data.DatabaseManager;
import com.roland.itemdatabase.data.ItemEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
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

import java.util.regex.Pattern;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> {

    @Shadow
    protected T menu;

    @Unique
    private static final Pattern STRIP_FORMATTING = Pattern.compile("(?i)[&§][0-9a-fk-or]|[^a-zA-Z0-9]");

    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void itemDatabase$highlightWishlistSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (CollectionManager.getWishlistCount() == 0 || this.menu == null) return;

        // Dynamic sine pulse between ~0.35 and 0.85 opacity
        float pulse = (float) (Math.sin(System.currentTimeMillis() / 220.0) * 0.5 + 0.5);
        int alpha = 0x50 + (int) (pulse * 0x80);
        int pulseBackground = (alpha << 24) | 0x00FFD700; // Glowing gold fill

        Minecraft mc = Minecraft.getInstance();

        for (Slot slot : this.menu.slots) {
            if (!slot.isActive()) continue;

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            if (itemDatabase$isWishlisted(stack)) {
                int slotX = slot.x;
                int slotY = slot.y;

                // 1. Pulsing golden fill behind item
                graphics.fill(slotX, slotY, slotX + 16, slotY + 16, pulseBackground);

                // 2. Double border (outer 18x18, inner 16x16) for high contrast
                graphics.outline(slotX - 1, slotY - 1, 18, 18, 0xFFFFD700);
                graphics.outline(slotX, slotY, 16, 16, 0x88FFAA00);

                // 3. Crisp golden diamond badge in top-right corner
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
        // 1. Check custom NBT item ID if present
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

        // 2. Check Custom / Hover Name
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