package com.roland.itemdatabase.data;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

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

    public Component getFormattedHeader() {
        List<TextColor> palette = findThemePalette();

        if (!palette.isEmpty()) {
            return applyGradient(name, palette);
        } else {
            String defaultColor = switch (groupType != null ? groupType.toLowerCase() : "") {
                case "voucher" -> "§b";
                case "event" -> "§6";
                case "furniture" -> "§a";
                default -> "§d";
            };
            return Component.literal(defaultColor + name);
        }
    }

    private List<TextColor> findThemePalette() {
        List<TextColor> colors = new ArrayList<>();
        String lowerName = name.toLowerCase().replace("–", "-").replace("—", "-").trim();

        // 1. Exact theme presets for known seasons & events (guarantees colors even without item lore)
        if (lowerName.contains("odyssey ii") || lowerName.contains("odyssey 2") || lowerName.contains("odyssey")) {
            // Odyssey II palette: Yellow -> Gold -> Coral Red
            return List.of(
                    TextColor.fromRgb(0xFFFF55),
                    TextColor.fromRgb(0xFFAA00),
                    TextColor.fromRgb(0xFF5555)
            );
        }
        if (lowerName.contains("christmas") || lowerName.contains("xmas") || lowerName.contains("winter")) {
            // Christmas palette: Red -> Green
            return List.of(
                    TextColor.fromRgb(0xFF5555),
                    TextColor.fromRgb(0x55FF55)
            );
        }
        if (lowerName.contains("halloween") || lowerName.contains("spooky")) {
            // Halloween palette: Orange -> Gold
            return List.of(
                    TextColor.fromRgb(0xFFAA00),
                    TextColor.fromRgb(0xFFFF55)
            );
        }
        if (lowerName.contains("enchanted")) {
            // Enchanted palette: Lime -> Soft Green
            return List.of(
                    TextColor.fromRgb(0x55FF55),
                    TextColor.fromRgb(0xAAFFAA)
            );
        }
        if (lowerName.contains("fable")) {
            // Fables palette: Pink -> Aqua
            return List.of(
                    TextColor.fromRgb(0xFFAAFF),
                    TextColor.fromRgb(0x55FFFF)
            );
        }
        if (lowerName.contains("infinity")) {
            // Infinity palette: Deep Blue -> Ice Blue
            return List.of(
                    TextColor.fromRgb(0x5555FF),
                    TextColor.fromRgb(0x55FFFF)
            );
        }
        if (lowerName.contains("chronopunk")) {
            // Chronopunk palette: Aqua -> Orange
            return List.of(
                    TextColor.fromRgb(0x55FFFF),
                    TextColor.fromRgb(0xFFAA00)
            );
        }
        if (lowerName.contains("labubu")) {
            // Labubu palette: Pastel Pink -> Soft Yellow
            return List.of(
                    TextColor.fromRgb(0xFFAACC),
                    TextColor.fromRgb(0xFFFF88)
            );
        }

        // 2. Dynamic lookup: Search global CRATES for matching name/season
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
                                        if (colors.isEmpty() || !colors.get(colors.size() - 1).equals(style.getColor())) {
                                            colors.add(style.getColor());
                                        }
                                    }
                                    return java.util.Optional.empty();
                                }, Style.EMPTY);
                                if (colors.size() >= 2) return colors;
                            }
                        }
                    }
                }
            }
        }

        // 3. Fallback: Check items in this group
        if (items != null && !items.isEmpty()) {
            for (ItemEntry it : items) {
                if (it.rawData() != null && !it.rawData().isBlank()) {
                    Component nameComp = RawDataParser.parseCustomName(it.rawData(), "");
                    nameComp.visit((style, text) -> {
                        if (style.getColor() != null && !text.isBlank()) {
                            if (colors.isEmpty() || !colors.get(colors.size() - 1).equals(style.getColor())) {
                                colors.add(style.getColor());
                            }
                        }
                        return java.util.Optional.empty();
                    }, Style.EMPTY);
                    if (colors.size() >= 2) return colors;
                }
            }
        }

        return colors;
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