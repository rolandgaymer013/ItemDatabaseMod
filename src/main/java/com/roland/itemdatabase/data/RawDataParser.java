package com.roland.itemdatabase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * High-performance parser for converting BlossomCraft raw item JSON/NBT payloads
 * into native Minecraft {@link Component} hierarchies with full color and style support.
 */
public class RawDataParser {

    /**
     * Parses the item display name from raw data, preserving per-character hex gradients.
     * Supports modern components (`minecraft:custom_name`) and legacy tags (`display.Name`).
     *
     * @param rawDataJson Serialized JSON/NBT string from the backend.
     * @param fallback    Default text if the name cannot be resolved.
     * @return Formatted Component representing the item title.
     */
    public static Component parseCustomName(String rawDataJson, String fallback) {
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return Component.literal(fallback);
        }

        try {
            JsonElement parsed = JsonParser.parseString(rawDataJson);
            if (!parsed.isJsonObject()) {
                return Component.literal(fallback);
            }

            JsonObject root = parsed.getAsJsonObject();

            // 1. Modern Minecraft 1.20.5+ component structure
            if (root.has("components") && root.get("components").isJsonObject()) {
                JsonObject components = root.getAsJsonObject("components");
                if (components.has("minecraft:custom_name")) {
                    return parseTextComponent(components.get("minecraft:custom_name"));
                }
            }

            // 2. Legacy NBT tag structure (tag.display.Name)
            if (root.has("tag") && root.get("tag").isJsonObject()) {
                JsonObject tag = root.getAsJsonObject("tag");
                if (tag.has("display") && tag.get("display").isJsonObject()) {
                    JsonObject display = tag.getAsJsonObject("display");
                    if (display.has("Name")) {
                        return parseTextComponent(display.get("Name"));
                    }
                }
            }
        } catch (Exception ignored) {}

        return Component.literal(fallback);
    }

    /**
     * Parses the item lore array from raw data into formatted Component lines.
     * Supports modern components (`minecraft:lore`) and legacy tags (`display.Lore`).
     *
     * @param rawDataJson Serialized JSON/NBT string from the backend.
     * @return Ordered list of formatted lore Components.
     */
    public static List<Component> parseLore(String rawDataJson) {
        List<Component> lines = new ArrayList<>();
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return lines;
        }

        try {
            JsonElement parsed = JsonParser.parseString(rawDataJson);
            if (!parsed.isJsonObject()) {
                return lines;
            }

            JsonObject root = parsed.getAsJsonObject();

            // 1. Modern Minecraft 1.20.5+ component structure
            if (root.has("components") && root.get("components").isJsonObject()) {
                JsonObject components = root.getAsJsonObject("components");
                if (components.has("minecraft:lore") && components.get("minecraft:lore").isJsonArray()) {
                    for (JsonElement lineElem : components.getAsJsonArray("minecraft:lore")) {
                        lines.add(parseTextComponent(lineElem));
                    }
                    return lines;
                }
            }

            // 2. Legacy NBT tag structure (tag.display.Lore)
            if (root.has("tag") && root.get("tag").isJsonObject()) {
                JsonObject tag = root.getAsJsonObject("tag");
                if (tag.has("display") && tag.get("display").isJsonObject()) {
                    JsonObject display = tag.getAsJsonObject("display");
                    if (display.has("Lore") && display.get("Lore").isJsonArray()) {
                        for (JsonElement lineElem : display.getAsJsonArray("Lore")) {
                            lines.add(parseTextComponent(lineElem));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return lines;
    }

    /**
     * Recursively converts a JSON element into a {@link MutableComponent}.
     * Handles raw primitives, formatting codes, embedded/stringified JSON, and sibling arrays.
     *
     * @param element JSON node to parse.
     * @return Renderable MutableComponent with full styling applied.
     */
    public static MutableComponent parseTextComponent(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Component.empty();
        }

        // Handle string primitives (unpacking stringified JSON when present)
        if (element.isJsonPrimitive()) {
            String str = element.getAsString();
            String trimmed = str.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    JsonElement inner = JsonParser.parseString(trimmed);
                    if (inner.isJsonObject()) {
                        return parseTextComponent(inner);
                    }
                } catch (Exception ignored) {}
            }
            return Component.literal(str.replace('&', '§'));
        }

        // Handle JSON Object components
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String text = obj.has("text") ? obj.get("text").getAsString().replace('&', '§') : "";
            MutableComponent comp = Component.literal(text);

            Style style = Style.EMPTY;

            if (obj.has("color")) {
                String colorStr = obj.get("color").getAsString();
                TextColor color = TextColor.parseColor(colorStr).result().orElse(null);
                if (color != null) {
                    style = style.withColor(color);
                }
            }

            if (obj.has("bold")) style = style.withBold(obj.get("bold").getAsBoolean());
            if (obj.has("italic")) style = style.withItalic(obj.get("italic").getAsBoolean());
            if (obj.has("underlined")) style = style.withUnderlined(obj.get("underlined").getAsBoolean());
            if (obj.has("strikethrough")) style = style.withStrikethrough(obj.get("strikethrough").getAsBoolean());
            if (obj.has("obfuscated")) style = style.withObfuscated(obj.get("obfuscated").getAsBoolean());

            comp.setStyle(style);

            // Recursively process sibling extra elements
            if (obj.has("extra") && obj.get("extra").isJsonArray()) {
                for (JsonElement extraElem : obj.getAsJsonArray("extra")) {
                    comp.append(parseTextComponent(extraElem));
                }
            }

            return comp;
        }

        return Component.empty();
    }

    /**
     * Extracts a formatted title or thematic badge component from an item's lore.
     * <ol>
     *     <li>Top priority: Season indicator lines ({@code Season X: ...}).</li>
     *     <li>Specific crate banner lines (filtering out generic placeholders).</li>
     *     <li>Decorative star/flower badges ({@code ✦}, {@code ❀}, {@code ★}).</li>
     *     <li>Fallback to custom name gradient.</li>
     * </ol>
     *
     * @param rawDataJson   Raw item payload.
     * @param fallbackTitle Title to use if no thematic line exists.
     * @return Formatted Component header.
     */
    public static Component extractThematicHeader(String rawDataJson, String fallbackTitle) {
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return Component.literal(fallbackTitle);
        }

        try {
            List<Component> lore = parseLore(rawDataJson);
            Component specificCrateBanner = null;

            for (Component line : lore) {
                String plain = line.getString().trim();
                String lower = plain.toLowerCase();

                // 1. Season lines
                if (lower.startsWith("season")) {
                    return line;
                }

                // 2. Specific Crate banners
                if (lower.contains("crate") && (plain.contains("▶") || plain.contains("►") || plain.contains("•"))) {
                    if (!lower.contains("blossom crate")) {
                        String cleaned = plain.replaceAll("[▶◀►◄]", "").trim();
                        specificCrateBanner = Component.literal("§d" + cleaned);
                    } else if (specificCrateBanner == null) {
                        String cleaned = plain.replaceAll("[▶◀►◄]", "").trim();
                        specificCrateBanner = Component.literal("§d" + cleaned);
                    }
                }
            }

            if (specificCrateBanner != null) {
                return specificCrateBanner;
            }

            // 3. Decorative badges
            for (Component line : lore) {
                String plain = line.getString().trim();
                if (plain.startsWith("✦") || plain.startsWith("❀") || plain.startsWith("★")) {
                    return line;
                }
            }

            // 4. Fallback: Extract from custom name
            Component customName = parseCustomName(rawDataJson, "");
            if (customName != null && !customName.getString().isBlank()) {
                return customName;
            }
        } catch (Exception ignored) {}

        return Component.literal(fallbackTitle);
    }
}