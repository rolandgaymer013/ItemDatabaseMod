package com.roland.itemdatabase.data;

import java.util.ArrayList;
import java.util.List;

public record CrateEntry(
        int id,
        String name,
        String releaseDate,
        String urlTag,
        String crateType,
        List<ItemEntry> items
) {
    public CrateEntry(int id, String name, String releaseDate, String urlTag, String crateType) {
        this(id, name, releaseDate, urlTag, crateType, new ArrayList<>());
    }
}