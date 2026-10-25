package com.roland.itemdatabase.data;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable data carrier representing an item in the BlossomCraft database.
 * Supports metadata lookup, tag categorization, and dynamic cooldown extraction.
 *
 * @param id            Unique database identifier.
 * @param itemName      Display name of the item.
 * @param itemHuman     Formatted lore / human-readable description.
 * @param rawData       Raw NBT JSON / component data from the backend.
 * @param imageType     Image extension (png, gif).
 * @param winPercentage Drop percentage string for crate rewards (nullable for misc).
 * @param rarityHuman   Rarity tier string (Common, Rare, Immortal, etc.).
 * @param notes         Administrative notes or ability specifications.
 * @param groupId       Identifier of the miscellaneous item group.
 * @param crateId       Identifier of the parent crate.
 * @param iconUrl       Resolved web URL for fetching item artwork.
 * @param isCrateItem   True if linked to a crate; false if miscellaneous.
 * @param tags          Assigned category and search tags.
 */
public record ItemEntry(
        String id,
        String itemName,
        String itemHuman,
        String rawData,
        String imageType,
        String winPercentage,
        String rarityHuman,
        String notes,
        String groupId,
        String crateId,
        String iconUrl,
        boolean isCrateItem,
        List<String> tags
) {
    private static final Pattern COOLDOWN_PATTERN = Pattern.compile(
            "(?i)cooldown\\s*[:=-]?\\s*([0-9]+\\s*(?:minutes?|mins?|seconds?|secs?|hours?|hrs?|m|s|h))"
    );

    // Alternative pattern for phrases like: "with a 5 second cooldown" or "5s cooldown"
    private static final Pattern REVERSE_COOLDOWN_PATTERN = Pattern.compile(
            "(?i)([0-9]+\\s*(?:minutes?|mins?|seconds?|secs?|hours?|hrs?|m|s|h))\\s*(?:cooldown)"
    );

    public ItemEntry {
        id = (id != null) ? id : "";
        itemName = (itemName != null) ? itemName : "";
        itemHuman = (itemHuman != null) ? itemHuman : "";
        rawData = (rawData != null) ? rawData : "";
        imageType = (imageType != null) ? imageType.toLowerCase() : "png";
        rarityHuman = (rarityHuman != null) ? rarityHuman : "Common";
        notes = (notes != null) ? notes : "";
        groupId = (groupId != null) ? groupId : "";
        crateId = (crateId != null) ? crateId : "";
        iconUrl = (iconUrl != null) ? iconUrl : "";
        tags = (tags != null) ? List.copyOf(tags) : List.of();
    }

    /**
     * Extracts an item's active cooldown duration, searching notes first,
     * then falling back to human-readable lore descriptions.
     *
     * @return Formatted cooldown string (e.g., "5 seconds", "10 minutes"), or null if none.
     */
    public String getCooldown() {
        // 1. Check notes column
        if (!this.notes.isBlank()) {
            String cd = extractCooldownFromString(this.notes);
            if (cd != null) return cd;
        }

        // 2. Check itemHuman lore fallback
        if (!this.itemHuman.isBlank()) {
            return extractCooldownFromString(this.itemHuman);
        }

        return null;
    }

    private static String extractCooldownFromString(String text) {
        Matcher matcher = COOLDOWN_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        Matcher reverseMatcher = REVERSE_COOLDOWN_PATTERN.matcher(text);
        if (reverseMatcher.find()) {
            return reverseMatcher.group(1).trim();
        }
        return null;
    }

    /**
     * @return True if a valid cooldown duration was found for this item.
     */
    public boolean hasCooldown() {
        return getCooldown() != null;
    }

    /**
     * Checks if this item matches a given tag name (case-insensitive).
     *
     * @param queryTag Tag string to test.
     * @return True if the item contains the tag.
     */
    public boolean hasTag(String queryTag) {
        if (queryTag == null || queryTag.isBlank() || this.tags.isEmpty()) {
            return false;
        }
        String normalized = queryTag.trim();
        for (String tag : this.tags) {
            if (tag.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Factory helper to construct an ItemEntry with safe defaults.
     */
    public static ItemEntry of(
            String id,
            String itemName,
            String itemHuman,
            String rawData,
            String imageType,
            String winPercentage,
            String rarityHuman,
            String notes,
            String groupId,
            String crateId,
            String iconUrl,
            boolean isCrateItem,
            List<String> tags
    ) {
        return new ItemEntry(
                id,
                itemName,
                itemHuman,
                rawData,
                imageType,
                winPercentage,
                rarityHuman,
                notes,
                groupId,
                crateId,
                iconUrl,
                isCrateItem,
                tags
        );
    }
}