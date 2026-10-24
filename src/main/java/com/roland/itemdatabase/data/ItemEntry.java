package com.roland.itemdatabase.data;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public boolean hasCooldown() {
        return getCooldown() != null;
    }

    /**
     * Checks if this item matches a given tag name (case-insensitive).
     */
    public boolean hasTag(String queryTag) {
        if (this.tags == null || this.tags.isEmpty() || queryTag == null) return false;
        String normalized = queryTag.trim();
        for (String tag : this.tags) {
            if (tag.equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

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
                id != null ? id : "",
                itemName != null ? itemName : "",
                itemHuman != null ? itemHuman : "",
                rawData != null ? rawData : "",
                imageType != null ? imageType : "png",
                winPercentage,
                rarityHuman != null ? rarityHuman : "Common",
                notes != null ? notes : "",
                groupId != null ? groupId : "",
                crateId != null ? crateId : "",
                iconUrl != null ? iconUrl : "",
                isCrateItem,
                tags != null ? tags : List.of()
        );
    }
}