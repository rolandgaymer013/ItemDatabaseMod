package com.roland.itemdatabase.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimMaterials;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import net.minecraft.world.item.equipment.trim.TrimPatterns;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.ArrayList;
import java.util.List;

public class MockServerItems {

    /**
     * Returns a test pool of items for a specific slot to test offline cycling.
     */
    public static List<ItemStack> getTestPoolForSlot(EquipmentSlot slot) {
        List<ItemStack> list = new ArrayList<>();

        switch (slot) {
            case HEAD -> {
                list.add(new ItemStack(Items.DIAMOND_HELMET));
                list.add(createCustomHelmet());
                list.add(createTrimmedHelmet());
                list.add(new ItemStack(Items.NETHERITE_HELMET));
                list.add(ItemStack.EMPTY);
            }
            case CHEST -> {
                list.add(new ItemStack(Items.NETHERITE_CHESTPLATE));
                list.add(createCustomChestplate());
                list.add(createDyedChestplate());
                list.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
                list.add(ItemStack.EMPTY);
            }
            case LEGS -> {
                list.add(new ItemStack(Items.NETHERITE_LEGGINGS));
                list.add(new ItemStack(Items.DIAMOND_LEGGINGS));
                list.add(ItemStack.EMPTY);
            }
            case FEET -> {
                list.add(new ItemStack(Items.DIAMOND_BOOTS));
                list.add(createCustomBoots());
                list.add(new ItemStack(Items.NETHERITE_BOOTS));
                list.add(ItemStack.EMPTY);
            }
            case MAINHAND -> {
                list.add(new ItemStack(Items.NETHERITE_SWORD));
                list.add(createCustomWeapon());
                list.add(new ItemStack(Items.DIAMOND_AXE));
                list.add(ItemStack.EMPTY);
            }
            case OFFHAND -> {
                list.add(new ItemStack(Items.SHIELD));
                list.add(new ItemStack(Items.TOTEM_OF_UNDYING));
                list.add(ItemStack.EMPTY);
            }
            default -> {}
        }
        return list;
    }

    private static ItemStack createCustomHelmet() {
        ItemStack stack = new ItemStack(Items.TURTLE_HELMET);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6★ Abyssal Crown §7[Tier IV]"));

        List<Component> lore = List.of(
                Component.literal("§7Forged in the deep ocean vents."),
                Component.literal("§eTrait: §fWater Breathing & Speed"),
                Component.literal("§8BlossomCraft Exclusive")
        );
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static ItemStack createCustomChestplate() {
        ItemStack stack = new ItemStack(Items.NETHERITE_CHESTPLATE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d★ Aegis of the Colossus §7[Legendary]"));

        ItemAttributeModifiers modifiers = ItemAttributeModifiers.builder()
                .add(
                        Attributes.ARMOR,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("itemdatabase", "colossus_armor"), 12.0, AttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.CHEST
                )
                .add(
                        Attributes.ARMOR_TOUGHNESS,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("itemdatabase", "colossus_toughness"), 5.0, AttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.CHEST
                )
                .add(
                        Attributes.MAX_HEALTH,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("itemdatabase", "colossus_health"), 6.0, AttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.CHEST
                )
                .build();

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
        return stack;
    }

    private static ItemStack createCustomBoots() {
        ItemStack stack = new ItemStack(Items.NETHERITE_BOOTS);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b★ Hermes Striders §7[Epic]"));
        return stack;
    }

    private static ItemStack createCustomWeapon() {
        ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c★ Voidrender Blade §7[Mythic]"));

        ItemAttributeModifiers modifiers = ItemAttributeModifiers.builder()
                .add(
                        Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(Identifier.fromNamespaceAndPath("itemdatabase", "voidrender_dmg"), 14.0, AttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND
                )
                .build();

        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers);
        return stack;
    }

    private static ItemStack createTrimmedHelmet() {
        ItemStack stack = new ItemStack(Items.NETHERITE_HELMET);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6★ Spire Crown §7[Trimmed]"));

        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client.level != null) {
            var registryAccess = client.level.registryAccess();
            var materialRegistry = registryAccess.lookupOrThrow(Registries.TRIM_MATERIAL);
            var patternRegistry = registryAccess.lookupOrThrow(Registries.TRIM_PATTERN);

            Holder<TrimMaterial> gold = materialRegistry.getOrThrow(TrimMaterials.GOLD);
            Holder<TrimPattern> spire = patternRegistry.getOrThrow(TrimPatterns.SPIRE);

            stack.set(DataComponents.TRIM, new ArmorTrim(gold, spire));
        }
        return stack;
    }

    private static ItemStack createDyedChestplate() {
        ItemStack stack = new ItemStack(Items.LEATHER_CHESTPLATE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§5★ Royal Tunic §7[Dyed]"));

        // Deep purple tint (RGB: 0x5B1E7A)
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x5B1E7A));
        return stack;
    }
}