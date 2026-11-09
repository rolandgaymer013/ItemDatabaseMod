package com.roland.itemdatabase.gui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class PreviewDummyPlayer extends RemotePlayer {

    public PreviewDummyPlayer(Minecraft client) {
        super(client.level, client.player != null
                ? client.player.getGameProfile()
                : new GameProfile(UUID.randomUUID(), "FittingDummy"));
    }

    public void setPreviewSlot(EquipmentSlot slot, ItemStack stack) {
        this.setItemSlot(slot, stack);
    }

    public void clearAllSlots() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setItemSlot(slot, ItemStack.EMPTY);
        }
    }
}