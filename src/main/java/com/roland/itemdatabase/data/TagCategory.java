package com.roland.itemdatabase.data;

import java.util.List;

/**
 * Defines curated categories and filter tags used by the BlossomCraft item database.
 * Used to group weapons, tools, armor, and custom gameplay attributes in the drawer tree.
 */
public enum TagCategory {
    ARMOR("Armor", List.of(
            "Hat", "Helmet", "Chestplate", "Leggings", "Boots", "Elytra"
    )),
    TOOLS("Tools", List.of(
            "Pickaxe", "Axe", "Hoe", "Shovel", "Rod", "Shield", "Shears"
    )),
    WEAPONS("Weapons", List.of(
            "Crossbow", "Sword", "Axe", "Bow", "Trident", "Mace", "Spear"
    )),
    TOOL_FEATURES("Tool Features", List.of(
            "Treefeller", "Veinminer", "Axe Projectile", "Radius Breaking", "Block Counter", "Auto Replant"
    )),
    WEAPON_FEATURES("Weapon Features", List.of(
            "Bleed", "Head Dropping", "Egg Dropping", "Mob Capturing", "Kill Counter"
    )),
    ITEM_PLACEMENT("Item Placement", List.of(
            "Offhand", "Hotbar", "Inventory"
    )),
    BLOCK_FEATURES("Block Features", List.of(
            "Infinite", "Special Block", "Cooker", "Teleportation"
    )),
    QUEST_RELATED("Quest Related", List.of(
            "Quest", "Quest Only"
    )),
    MISCELLANEOUS("Miscellaneous", List.of(
            "Disguise", "Repeat Appearance", "Unbreakable Kit", "Telekinesis", "Speed Attribute", "Custom Loot", "Furniture Dropping"
    ));

    private final String label;
    private final List<String> tags;

    TagCategory(String label, List<String> tags) {
        this.label = label;
        this.tags = (tags != null) ? List.copyOf(tags) : List.of();
    }

    /**
     * @return User-friendly label displayed in the drawer category carousel.
     */
    public String label() {
        return label;
    }

    /**
     * @return Unmodifiable list of tags associated with this category.
     */
    public List<String> tags() {
        return tags;
    }

    /**
     * Checks if this category contains a specific tag (case-insensitive).
     *
     * @param queryTag Tag to look for.
     * @return True if present in this category.
     */
    public boolean containsTag(String queryTag) {
        if (queryTag == null || queryTag.isBlank()) return false;
        String normalized = queryTag.trim();
        for (String t : tags) {
            if (t.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }
}