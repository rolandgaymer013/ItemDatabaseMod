package com.roland.itemdatabase.data;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable data record representing a miscellaneous container group (e.g. Vouchers,
 * Events, Furniture, or Unsorted items) in the BlossomCraft database.
 * <p>
 * Handles category classification, persistent group ordering, and dynamic title
 * styling via theme palette matching and character-by-character color gradients.
 *
 * @param id          Unique numeric identifier assigned by the database.
 * @param name        Display title of the group (e.g., "Music Discs", "Odyssey II Furniture").
 * @param groupType   Categorical sub-filter ("Voucher", "Event", "Furniture", or "Unsorted").
 * @param urlTag      Slug identifier used for web URLs and resource lookup.
 * @param releaseDate Release date timestamp or formatted string.
 * @param groupOrder  Sequential display order index stored in the database.
 * @param items       List of miscellaneous child items indexed under this group.
 */
public record MiscGroupEntry(
        int id,
        String name,
        String groupType,
        String urlTag,
        String releaseDate,
        int groupOrder,
        List<ItemEntry> items
) {
    /**
     * Convenience constructor with custom order index and an empty item collection.
     */
    public MiscGroupEntry(int id, String name, String groupType, String urlTag, String releaseDate, int groupOrder) {
        this(id, name, groupType, urlTag, releaseDate, groupOrder, new ArrayList<>());
    }

    /**
     * Default constructor defaulting groupOrder to 0.
     */
    public MiscGroupEntry(int id, String name, String groupType, String urlTag, String releaseDate) {
        this(id, name, groupType, urlTag, releaseDate, 0, new ArrayList<>());
    }

    /**
     * Compact constructor guaranteeing non-null item list initialization.
     */
    public MiscGroupEntry {
        items = (items != null) ? items : new ArrayList<>();
    }

    /**
     * Resolves the formatted title component for navigation lists and drawer trees.
     * Applies an interpolated gradient if a matching palette is discovered, or falls
     * back to standard categorical color codes.
     *
     * @return Formatted Minecraft {@link Component} without navigation arrows.
     */
    public Component getFormattedHeader() {
        List<TextColor> palette = findThemePalette();

        if (!palette.isEmpty()) {
            return applyGradient(name, palette);
        } else {
            String defaultColor = switch (groupType != null ? groupType.toLowerCase() : "") {
                case "voucher" -> "§b";    // Aqua
                case "event" -> "§6";      // Gold
                case "furniture" -> "§a";  // Green
                default -> "§d";           // Light Purple / Unsorted
            };
            return Component.literal(defaultColor + name);
        }
    }

    /**
     * Discovers a multi-stop color palette for this group by querying:
     * <ol>
     *   <li>Explicit hardcoded theme keywords in the group name.</li>
     *   <li>Matching crate season banners if the group shares a seasonal title.</li>
     *   <li>Direct custom name component sampling from constituent items.</li>
     * </ol>
     *
     * @return List of {@link TextColor} stops, or an empty list if no theme matched.
     */
    private List<TextColor> findThemePalette() {
        List<TextColor> colors = new ArrayList<>();
        String lowerName = name.toLowerCase().replace("–", "-").replace("—", "-").trim();

        // 1. Explicit theme keyword mappings
        if (lowerName.contains("odyssey ii") || lowerName.contains("odyssey 2") || lowerName.contains("odyssey")) {
            return List.of(
                    TextColor.fromRgb(0xFFFF55), // Yellow
                    TextColor.fromRgb(0xFFAA00), // Orange
                    TextColor.fromRgb(0xFF5555)  // Light Red
            );
        }
        if (lowerName.contains("christmas") || lowerName.contains("xmas") || lowerName.contains("winter")) {
            return List.of(
                    TextColor.fromRgb(0xFF5555), // Red
                    TextColor.fromRgb(0x55FF55)  // Green
            );
        }
        if (lowerName.contains("halloween") || lowerName.contains("spooky")) {
            return List.of(
                    TextColor.fromRgb(0xFFAA00), // Orange
                    TextColor.fromRgb(0xFFFF55)  // Yellow
            );
        }
        if (lowerName.contains("enchanted")) {
            return List.of(
                    TextColor.fromRgb(0x55FF55), // Green
                    TextColor.fromRgb(0xAAFFAA)  // Light Mint
            );
        }
        if (lowerName.contains("fable")) {
            return List.of(
                    TextColor.fromRgb(0xFFAAFF), // Pink
                    TextColor.fromRgb(0x55FFFF)  // Aqua
            );
        }
        if (lowerName.contains("infinity")) {
            return List.of(
                    TextColor.fromRgb(0x5555FF), // Blue
                    TextColor.fromRgb(0x55FFFF)  // Aqua
            );
        }
        if (lowerName.contains("chronopunk")) {
            return List.of(
                    TextColor.fromRgb(0x55FFFF), // Aqua
                    TextColor.fromRgb(0xFFAA00)  // Orange
            );
        }
        if (lowerName.contains("labubu")) {
            return List.of(
                    TextColor.fromRgb(0xFFAACC), // Pastel Pink
                    TextColor.fromRgb(0xFFFF88)  // Pastel Yellow
            );
        }

        // 2. Cross-reference matching crate season banners
        for (CrateEntry crate : DatabaseManager.CRATES.values()) {
            String crateName = crate.name().toLowerCase();
            String cleanedCrate = crateName.replaceAll("(?i)season\\s*\\d+:?", "").trim();

            if (!cleanedCrate.isEmpty() && lowerName.contains(cleanedCrate)) {
                for (ItemEntry it : crate.items()) {
                    if (it.rawData() != null && !it.rawData().isBlank()) {
                        List<Component> lore = RawDataParser.parseLore(it.rawData());
                        for (Component line : lore) {
                            String plain = line.getString().toLowerCase();
                            if (plain.contains(cleanedCrate) || plain.startsWith("season")) {
                                line.visit((style, text) -> {
                                    if (style.getColor() != null && !text.isBlank()) {
                                        int val = style.getColor().getValue();
                                        int r = (val >> 16) & 0xFF, g = (val >> 8) & 0xFF, b = val & 0xFF;
                                        if (r + g + b > 120) {
                                            if (colors.isEmpty() || !colors.get(colors.size() - 1).equals(style.getColor())) {
                                                colors.add(style.getColor());
                                            }
                                        }
                                    }
                                    return Optional.empty();
                                }, Style.EMPTY);
                                if (colors.size() >= 2) return colors;
                            }
                        }
                    }
                }
            }
        }

        // 3. Dynamic color extraction from member items
        if (!items.isEmpty()) {
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    Component nameComp = RawDataParser.parseCustomName(it.rawData(), "");
                    nameComp.visit((style, text) -> {
                        if (style.getColor() != null && !text.isBlank()) {
                            int val = style.getColor().getValue();
                            int r = (val >> 16) & 0xFF, g = (val >> 8) & 0xFF, b = val & 0xFF;
                            if (r + g + b > 120) {
                                if (colors.isEmpty() || !colors.get(colors.size() - 1).equals(style.getColor())) {
                                    colors.add(style.getColor());
                                }
                            }
                        }
                        return Optional.empty();
                    }, Style.EMPTY);
                    if (colors.size() >= 2) return colors;
                }
            }
        }

        return colors;
    }

    /**
     * Interpolates RGB values across a text string character-by-character.
     *
     * @param text    The plain string to format.
     * @param palette The sequence of color stops to blend between.
     * @return Formatted Component with colored text segments.
     */
    private static Component applyGradient(String text, List<TextColor> palette) {
        if (palette == null || palette.isEmpty()) return Component.literal(text);
        if (palette.size() == 1 || text.length() <= 1) {
            return Component.literal(text).setStyle(Style.EMPTY.withColor(palette.get(0)));
        }

        MutableComponent comp = Component.empty();
        int len = text.length();

        for (int i = 0; i < len; i++) {
            float progress = (float) i / (len - 1);
            float scaled = progress * (palette.size() - 1);
            int idx = (int) scaled;
            int nextIdx = Math.min(idx + 1, palette.size() - 1);
            float factor = scaled - idx;

            int c1 = palette.get(idx).getValue();
            int c2 = palette.get(nextIdx).getValue();

            int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
            int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;

            int r = Mth.clamp((int) (r1 + (r2 - r1) * factor), 0, 255);
            int g = Mth.clamp((int) (g1 + (g2 - g1) * factor), 0, 255);
            int b = Mth.clamp((int) (b1 + (b2 - b1) * factor), 0, 255);

            comp.append(Component.literal(String.valueOf(text.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb((r << 16) | (g << 8) | b))));
        }

        return comp;
    }
}