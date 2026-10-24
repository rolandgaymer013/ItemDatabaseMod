package com.roland.itemdatabase.data;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

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

    public Component getFormattedHeader(boolean isExpanded) {
        String arrow = isExpanded ? "§d▼ " : "§8▶ ";

        // Override database flavor text for Christmas 2021
        String cleanName = name;
        if (cleanName.toLowerCase().contains("eggnog") || cleanName.toLowerCase().contains("a christmas")) {
            cleanName = "Christmas Crate";
        }

        if (items != null && !items.isEmpty()) {
            // 1. If this is a Seasonal Crate, find the line starting with "Season"
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

            // 2. Specific Crate Banners (e.g. ▶ ELYTRA CRATE ◀, ▶ CONCRETE CRATE ◀, ▶ Halloween Crate ◀)
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    List<Component> lore = RawDataParser.parseLore(it.rawData());
                    for (Component line : lore) {
                        String plain = line.getString().trim();
                        String lower = plain.toLowerCase();

                        // Skip ability/instruction lines that happen to mention the crate word
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
                                return java.util.Optional.empty();
                            }, Style.EMPTY);

                            String trimmedPlain = banner.getString().trim();
                            if (!trimmedPlain.isEmpty() && trimmedPlain.length() >= 3) {
                                return Component.literal(arrow).append(banner);
                            }
                        }
                    }
                }
            }

            // 3. Known themed fallbacks for crates without clean banners
            String lowerName = cleanName.toLowerCase();
            if (lowerName.contains("concrete")) {
                // Concrete rainbow palette: Green -> Pink -> Cyan (from the website banner)
                List<TextColor> concretePalette = List.of(
                        TextColor.fromRgb(0x55FF55), // Green
                        TextColor.fromRgb(0xFF55FF), // Pink
                        TextColor.fromRgb(0x55FFFF)  // Cyan
                );
                return Component.literal(arrow).append(applyGradient("CONCRETE CRATE", concretePalette));
            }
            if (lowerName.contains("terracotta")) {
                // Terracotta warm gradient: Orange -> Gold -> Light Red
                List<TextColor> terracottaPalette = List.of(
                        TextColor.fromRgb(0xFFAA00),
                        TextColor.fromRgb(0xFFFF55),
                        TextColor.fromRgb(0xFF5555)
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

            // 4. Sample palette from item custom names (ignoring pure black/dark gray values)
            List<TextColor> palette = new ArrayList<>();
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    Component nameComp = RawDataParser.parseCustomName(it.rawData(), "");
                    nameComp.visit((style, text) -> {
                        if (style.getColor() != null && !text.isBlank()) {
                            int val = style.getColor().getValue();
                            // Skip pure black or dark colors (brightness threshold) so text remains readable
                            int r = (val >> 16) & 0xFF, g = (val >> 8) & 0xFF, b = val & 0xFF;
                            if (r + g + b > 120) {
                                if (palette.isEmpty() || !palette.get(palette.size() - 1).equals(style.getColor())) {
                                    palette.add(style.getColor());
                                }
                            }
                        }
                        return java.util.Optional.empty();
                    }, Style.EMPTY);

                    if (palette.size() >= 2) break;
                }
            }

            if (!palette.isEmpty()) {
                return Component.literal(arrow).append(applyGradient(cleanName, palette));
            }
        }

        String fallbackColor = "Side".equalsIgnoreCase(crateType) ? "§6" : "§f";
        return Component.literal(arrow + fallbackColor + cleanName);
    }

    private static Component applyGradient(String text, List<TextColor> palette) {
        if (palette.isEmpty()) return Component.literal(text);
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

            int r = (int) (((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * factor));
            int g = (int) (((c1 >> 8) & 0xFF) + ((((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * factor));
            int b = (int) ((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * factor));

            comp.append(Component.literal(String.valueOf(text.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb((r << 16) | (g << 8) | b))));
        }

        return comp;
    }
}