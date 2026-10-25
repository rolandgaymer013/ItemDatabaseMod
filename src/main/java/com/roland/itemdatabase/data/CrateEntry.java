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
 * Represents a crate category in BlossomCraft containing grouped items.
 * Handles dynamic title styling, custom gradient extraction, and banner resolution.
 *
 * @param id          Unique numeric crate ID.
 * @param name        Display name of the crate.
 * @param releaseDate Release date timestamp or string.
 * @param urlTag      Slug identifier used for web resources.
 * @param crateType   Category grouping (e.g., Seasonal, Side, Misc).
 * @param items       List of items contained in this crate.
 */
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

    public CrateEntry {
        items = (items != null) ? items : new ArrayList<>();
    }

    /**
     * Resolves and formats the crate's display header component for the navigation tree.
     * Extracts authentic seasonal lore banners or calculates a custom theme gradient.
     *
     * @param isExpanded Whether this crate folder is currently open in the tree view.
     * @return Formatted Minecraft Component ready for rendering.
     */
    public Component getFormattedHeader(boolean isExpanded) {
        String arrow = isExpanded ? "§d▼ " : "§8▶ ";

        // Override database flavor text for Christmas 2021
        String cleanName = name;
        if (cleanName.toLowerCase().contains("eggnog") || cleanName.toLowerCase().contains("a christmas")) {
            cleanName = "Christmas Crate";
        }

        if (!items.isEmpty()) {
            // 1. Seasonal Crates: Find lore line starting with "Season"
            if ("Seasonal".equalsIgnoreCase(crateType) || name.toLowerCase().startsWith("season")) {
                for (ItemEntry it : items) {
                    if (it.rawData() != null && !it.rawData().isBlank()) {
                        List<Component> lore = RawDataParser.parseLore(it.rawData());
                        for (Component line : lore) {
                            String plain = line.getString().trim();
                            if (plain.toLowerCase().startsWith("season")) {
                                return Component.literal(arrow).append(line);
                            }
                        }
                    }
                }
            }

            // 2. Specific Crate Banners (e.g. ▶ ELYTRA CRATE ◀, ▶ CONCRETE CRATE ◀)
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    List<Component> lore = RawDataParser.parseLore(it.rawData());
                    for (Component line : lore) {
                        String plain = line.getString().trim();
                        String lower = plain.toLowerCase();

                        // Skip ability or instruction lines that happen to mention "crate"
                        if (plain.startsWith(">") || lower.startsWith("sneak") || lower.startsWith("grants")
                                || lower.startsWith("emits") || lower.startsWith("immune") || lower.startsWith("chance")) {
                            continue;
                        }

                        // Banner lines must contain "crate" or be wrapped in triangle brackets
                        boolean hasCrateWord = lower.contains("crate") && !lower.contains("blossom crate");
                        boolean hasBrackets = (plain.contains("▶") || plain.contains("►")) && (plain.contains("◀") || plain.contains("◄"));

                        if (hasCrateWord && (hasBrackets || plain.length() <= 24)) {
                            MutableComponent banner = Component.empty();
                            line.visit((style, text) -> {
                                String filtered = text.replaceAll("[▶◀►◄<>•|=]", "");
                                if (!filtered.isEmpty()) {
                                    banner.append(Component.literal(filtered).setStyle(style));
                                }
                                return Optional.empty();
                            }, Style.EMPTY);

                            String trimmedPlain = banner.getString().trim();
                            if (trimmedPlain.length() >= 3) {
                                return Component.literal(arrow).append(banner);
                            }
                        }
                    }
                }
            }

            // 3. Known themed fallbacks for crates without clean banners
            String lowerName = cleanName.toLowerCase();
            if (lowerName.contains("concrete")) {
                List<TextColor> concretePalette = List.of(
                        TextColor.fromRgb(0x55FF55), // Green
                        TextColor.fromRgb(0xFF55FF), // Pink
                        TextColor.fromRgb(0x55FFFF)  // Cyan
                );
                return Component.literal(arrow).append(applyGradient("CONCRETE CRATE", concretePalette));
            }
            if (lowerName.contains("terracotta")) {
                List<TextColor> terracottaPalette = List.of(
                        TextColor.fromRgb(0xFFAA00), // Orange
                        TextColor.fromRgb(0xFFFF55), // Gold
                        TextColor.fromRgb(0xFF5555)  // Light Red
                );
                return Component.literal(arrow).append(applyGradient("TERRACOTTA CRATE", terracottaPalette));
            }
            if (lowerName.contains("halloween") && !lowerName.contains("2022")) {
                List<TextColor> halloweenPalette = List.of(
                        TextColor.fromRgb(0xFFAA00),
                        TextColor.fromRgb(0xFFFF55)
                );
                return Component.literal(arrow).append(applyGradient("Halloween Crate", halloweenPalette));
            }

            // 4. Sample palette from item custom names (ignoring dark colors for readability)
            List<TextColor> palette = new ArrayList<>();
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    Component nameComp = RawDataParser.parseCustomName(it.rawData(), "");
                    nameComp.visit((style, text) -> {
                        if (style.getColor() != null && !text.isBlank()) {
                            int val = style.getColor().getValue();
                            int r = (val >> 16) & 0xFF;
                            int g = (val >> 8) & 0xFF;
                            int b = val & 0xFF;
                            if (r + g + b > 120) {
                                if (palette.isEmpty() || !palette.get(palette.size() - 1).equals(style.getColor())) {
                                    palette.add(style.getColor());
                                }
                            }
                        }
                        return Optional.empty();
                    }, Style.EMPTY);

                    if (palette.size() >= 2) break;
                }
            }

            if (!palette.isEmpty()) {
                return Component.literal(arrow).append(applyGradient(cleanName, palette));
            }
        }

        // Default styling if no banners or custom palettes exist
        String fallbackColor = "Side".equalsIgnoreCase(crateType) ? "§6" : "§f";
        return Component.literal(arrow + fallbackColor + cleanName);
    }

    /**
     * Interpolates RGB colors across a string of text character by character.
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