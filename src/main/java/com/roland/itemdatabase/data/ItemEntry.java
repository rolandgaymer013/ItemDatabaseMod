package com.roland.itemdatabase.data;

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