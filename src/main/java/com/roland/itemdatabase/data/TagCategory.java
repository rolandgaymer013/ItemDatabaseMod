package com.roland.itemdatabase.data;

import java.util.List;

public enum TagCategory {
    ARMOR("Armor", List.of("Hat", "Helmet", "Chestplate", "Leggings", "Boots", "Elytra")),
    TOOLS("Tools", List.of("Pickaxe", "Axe", "Hoe", "Shovel", "Rod", "Shield", "Shears")),
    WEAPONS("Weapons", List.of("Crossbow", "Sword", "Axe", "Bow", "Trident", "Mace", "Spear")),
    TOOL_FEATURES("Tool Features", List.of("Treefeller", "Veinminer", "Axe Projectile", "Radius Breaking", "Block Counter", "Auto Replant")),
    WEAPON_FEATURES("Weapon Features", List.of("Bleed", "Head Dropping", "Egg Dropping", "Mob Capturing", "Kill Counter")),
    ITEM_PLACEMENT("Item Placement", List.of("Offhand", "Hotbar", "Inventory")),
    BLOCK_FEATURES("Block Features", List.of("Infinite", "Special Block", "Cooker", "Teleportation")),
    QUEST_RELATED("Quest Related", List.of("Quest", "Quest Only")),
    MISCELLANEOUS("Miscellaneous", List.of("Disguise", "Repeat Appearance", "Unbreakable Kit", "Telekinesis", "Speed Attribute", "Custom Loot", "Furniture Dropping"));

    private final String label;
    private final List<String> tags;

    TagCategory(String label, List<String> tags) {
        this.label = label;
        this.tags = tags;
    }

    public String label() { return label; }
    public List<String> tags() { return tags; }
}