package com.roland.itemdatabase.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable data record representing a crate category in BlossomCraft.
 * <p>
 * Handles deserialization from API responses, fallback initialization, and
 * dynamic header styling (extracting authentic lore banners, applying themed palettes,
 * or sampling gradient colors from child items).
 *
 * @param id          Unique numeric identifier assigned by the database.
 * @param name        Display name of the crate (e.g., "Season 1: LEGACY", "Christmas Crate").
 * @param releaseDate Release date string (typically formatted as MM/dd/yyyy or YYYY-MM-DD).
 * @param urlTag      Slug identifier used for web URLs and resource lookups.
 * @param crateType   Category grouping ("Seasonal", "Side", or "Misc").
 * @param items       Mutable/cached collection of items indexed under this crate.
 */
public record CrateEntry(
        @SerializedName("id")
        int id,

        @SerializedName(value = "name", alternate = {"CrateName", "crate_name"})
        String name,

        @SerializedName(value = "releaseDate", alternate = {"ReleaseDate", "release_date"})
        String releaseDate,

        @SerializedName(value = "urlTag", alternate = {"URLTag", "url_tag"})
        String urlTag,

        @SerializedName(value = "crateType", alternate = {"CrateType", "crate_type"})
        String crateType,

        @SerializedName("items")
        List<ItemEntry> items
) {
    /**
     * Convenience constructor creating a CrateEntry with an empty list of child items.
     */
    public CrateEntry(int id, String name, String releaseDate, String urlTag, String crateType) {
        this(id, name, releaseDate, urlTag, crateType, new ArrayList<>());
    }

    /**
     * Compact constructor providing default fallbacks and null safety across all attributes.
     */
    public CrateEntry {
        items = (items != null) ? items : new ArrayList<>();
        name = (name != null) ? name : "";
        releaseDate = (releaseDate != null) ? releaseDate : "";
        urlTag = (urlTag != null) ? urlTag : "";
        crateType = (crateType != null) ? crateType : "Seasonal";
    }

    /**
     * Resolves and formats the crate display header component for UI lists and tree drawers.
     * <p>
     * Resolution order:
     * <ol>
     *   <li><b>Seasonal Lore:</b> Scans child item lore for lines starting with "Season".</li>
     *   <li><b>Banner Headers:</b> Scans child item lore for bracketed crate banners (e.g., "▶ ELYTRA CRATE ◀").</li>
     *   <li><b>Hardcoded Themes:</b> Matches known themes like "Concrete", "Terracotta", or "Halloween".</li>
     *   <li><b>Sampled Palette:</b> Samples bright RGB colors from item custom names to construct a gradient.</li>
     *   <li><b>Default Fallback:</b> Renders plain text with yellow (§6) or white (§f) accents.</li>
     * </ol>
     *
     * @param isSelectedOrExpanded Whether this crate is currently selected in a list or expanded in a drawer.
     *                             When true, highlights the indicator arrow in gold/magenta.
     * @return Fully formatted Minecraft {@link Component} ready for font rendering.
     */
    public Component getFormattedHeader(boolean isSelectedOrExpanded) {
        // Active selections use vibrant gold/cyan, while inactive items use muted dark grey
        String arrow = isSelectedOrExpanded ? "§e▶ " : "§8▶ ";

        // Normalize flavor names from legacy database entries
        String cleanName = name;
        if (cleanName.toLowerCase().contains("eggnog") || cleanName.toLowerCase().contains("a christmas")) {
            cleanName = "Christmas Crate";
        }

        if (!items.isEmpty()) {
            // --- 1. Seasonal Crates: Locate the authentic Season header from child lore ---
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

            // --- 2. Crate Banners: Scan for decorative title lines (e.g. ▶ ELYTRA CRATE ◀) ---
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    List<Component> lore = RawDataParser.parseLore(it.rawData());
                    for (Component line : lore) {
                        String plain = line.getString().trim();
                        String lower = plain.toLowerCase();

                        // Discard gameplay mechanics, tooltips, or perk lines that happen to mention "crate"
                        if (plain.startsWith(">") || lower.startsWith("sneak") || lower.startsWith("grants")
                                || lower.startsWith("emits") || lower.startsWith("immune") || lower.startsWith("chance")) {
                            continue;
                        }

                        // Banner lines must contain "crate" (excluding general "blossom crate") or triangle bracket delimiters
                        boolean hasCrateWord = lower.contains("crate") && !lower.contains("blossom crate");
                        boolean hasBrackets = (plain.contains("▶") || plain.contains("►")) && (plain.contains("◀") || plain.contains("◄"));

                        if (hasCrateWord && (hasBrackets || plain.length() <= 24)) {
                            MutableComponent banner = Component.empty();
                            line.visit((style, text) -> {
                                // Strip decorative arrow characters so we don't end up with duplicate nested arrows
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

            // --- 3. Hardcoded Palette Fallbacks for legacy crates lacking custom lore banners ---
            String lowerName = cleanName.toLowerCase();
            if (lowerName.contains("concrete")) {
                List<TextColor> concretePalette = List.of(
                        TextColor.fromRgb(0x55FF55), // Lime Green
                        TextColor.fromRgb(0xFF55FF), // Magenta Pink
                        TextColor.fromRgb(0x55FFFF)  // Aqua Cyan
                );
                return Component.literal(arrow).append(applyGradient("CONCRETE CRATE", concretePalette));
            }
            if (lowerName.contains("terracotta")) {
                List<TextColor> terracottaPalette = List.of(
                        TextColor.fromRgb(0xFFAA00), // Orange
                        TextColor.fromRgb(0xFFFF55), // Yellow
                        TextColor.fromRgb(0xFF5555)  // Light Red
                );
                return Component.literal(arrow).append(applyGradient("TERRACOTTA CRATE", terracottaPalette));
            }
            if (lowerName.contains("halloween") && !lowerName.contains("2022")) {
                List<TextColor> halloweenPalette = List.of(
                        TextColor.fromRgb(0xFFAA00), // Orange
                        TextColor.fromRgb(0xFFFF55)  // Yellow
                );
                return Component.literal(arrow).append(applyGradient("Halloween Crate", halloweenPalette));
            }

            // --- 4. Dynamic Palette Sampling: Extract dominant bright colors from item titles ---
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
                            // Filter out muddy/dark colors (luminance threshold) to maintain high contrast
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

        // --- 5. Flat Text Fallback ---
        String fallbackColor = "Side".equalsIgnoreCase(crateType) ? "§6" : "§f";
        return Component.literal(arrow + fallbackColor + cleanName);
    }

    /**
     * Interpolates RGB colors across a string of text character by character to create a smooth gradient.
     *
     * @param text    The plain string to format with a color gradient.
     * @param palette An ordered list of {@link TextColor} stops to blend across.
     * @return Formatted {@link Component} containing per-character colored slices.
     */
    private static Component applyGradient(String text, List<TextColor> palette) {
        if (palette == null || palette.isEmpty()) return Component.literal(text);
        if (palette.size() == 1 || text.length() <= 1) {
            return Component.literal(text).setStyle(Style.EMPTY.withColor(palette.get(0)));
        }

        MutableComponent comp = Component.empty();
        int len = text.length();

        for (int i = 0; i < len; i++) {
            // Determine linear progression across color stops
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