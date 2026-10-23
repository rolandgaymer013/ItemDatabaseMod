package com.roland.itemdatabase.data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ItemEntry(
        String id,
        String itemName,
        String itemHuman,
        String imageType,
        String winPercentage,
        String rarityHuman,
        String notes,
        String groupId,
        String crateId,
        String iconUrl,
        boolean isCrateItem
) {
    // Matches "Cooldown = 4 minutes", "Cooldown: 30s", "Cooldown - 1 hour", etc.
    private static final Pattern COOLDOWN_PATTERN = Pattern.compile(
            "(?i)cooldown\\s*[:=-]?\\s*([0-9]+\\s*(?:minutes?|mins?|seconds?|secs?|hours?|hrs?|m|s|h))"
    );

    /**
     * Extracts and returns the cooldown string from the notes field.
     * Example: "4 minutes", "30s", "1 hour".
     * Returns null if no cooldown is found or notes are empty.
     */
    public String getCooldown() {
        if (this.notes == null || this.notes.isBlank()) {
            return null;
        }
        Matcher matcher = COOLDOWN_PATTERN.matcher(this.notes);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Helper to verify if this entry has a parsed cooldown.
     */
    public boolean hasCooldown() {
        return getCooldown() != null;
    }

    // Convenience constructor if you use default-sanitizing instantiation
    public static ItemEntry of(
            String id,
            String itemName,
            String itemHuman,
            String imageType,
            String winPercentage,
            String rarityHuman,
            String notes,
            String groupId,
            String crateId,
            String iconUrl,
            boolean isCrateItem
    ) {
        return new ItemEntry(
                id != null ? id : "",
                itemName != null ? itemName : "",
                itemHuman != null ? itemHuman : "",
                imageType != null ? imageType : "png",
                winPercentage,
                rarityHuman != null ? rarityHuman : "Common",
                notes != null ? notes : "",
                groupId != null ? groupId : "",
                crateId != null ? crateId : "",
                iconUrl != null ? iconUrl : "",
                isCrateItem
        );
    }
}