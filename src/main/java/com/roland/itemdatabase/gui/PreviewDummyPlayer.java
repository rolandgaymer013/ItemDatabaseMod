package com.roland.itemdatabase.gui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class PreviewDummyPlayer extends RemotePlayer {

    private final Map<EquipmentSlot, ItemStack> previewEquipment = new EnumMap<>(EquipmentSlot.class);

    public PreviewDummyPlayer(Minecraft client) {
        super(client.level, client.player != null
                ? client.player.getGameProfile()
                : new GameProfile(UUID.randomUUID(), "FittingDummy"));

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.previewEquipment.put(slot, ItemStack.EMPTY);
        }
    }

    public void setPreviewSlot(EquipmentSlot slot, ItemStack stack) {
        ItemStack copy = (stack == null || stack.isEmpty()) ? ItemStack.EMPTY : stack.copy();
        this.previewEquipment.put(slot, copy);
        this.setItemSlot(slot, copy);
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        if (this.previewEquipment != null && this.previewEquipment.containsKey(slot)) {
            return this.previewEquipment.get(slot);
        }
        return super.getItemBySlot(slot);
    }

    @Override
    public boolean isModelPartShown(PlayerModelPart part) {
        // Show all outer skin layers (hat, jacket, sleeves, pants) on the mannequin
        return true;
    }
}