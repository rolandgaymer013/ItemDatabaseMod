package com.roland.itemdatabase.data;

import java.util.ArrayList;
import java.util.List;

public record MiscGroupEntry(
        int id,
        String name,
        String groupType,
        String urlTag,
        String releaseDate,
        List<ItemEntry> items
) {
    public MiscGroupEntry(int id, String name, String groupType, String urlTag, String releaseDate) {
        this(id, name, groupType, urlTag, releaseDate, new ArrayList<>());
    }
}