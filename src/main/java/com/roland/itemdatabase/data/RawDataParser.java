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

public class RawDataParser {

    /**
     * Parses custom_name from RawData into a formatted Minecraft Component
     * preserving all per-character hex gradients.
     */
    public static Component parseCustomName(String rawDataJson, String fallback) {
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return Component.literal(fallback);
        }
        try {
            JsonObject root = JsonParser.parseString(rawDataJson).getAsJsonObject();
            JsonObject components = root.getAsJsonObject("components");
            if (components != null && components.has("minecraft:custom_name")) {
                JsonElement nameElem = components.get("minecraft:custom_name");
                return parseTextComponent(nameElem);
            }
        } catch (Exception ignored) {}
        return Component.literal(fallback);
    }

    /**
     * Parses the lore array from RawData into a list of formatted Minecraft Components.
     * Accurately parses nested/stringified JSON lines.
     */
    public static List<Component> parseLore(String rawDataJson) {
        List<Component> lines = new ArrayList<>();
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return lines;
        }
        try {
            JsonObject root = JsonParser.parseString(rawDataJson).getAsJsonObject();
            JsonObject components = root.getAsJsonObject("components");
            if (components != null && components.has("minecraft:lore")) {
                JsonArray loreArray = components.getAsJsonArray("minecraft:lore");
                for (JsonElement lineElem : loreArray) {
                    Component parsed = parseTextComponent(lineElem);
                    lines.add(parsed);
                }
            }
        } catch (Exception ignored) {}
        return lines;
    }

    /**
     * Recursively parses Minecraft text component JSON into a MutableComponent.
     * Detects stringified JSON components and unpacks them.
     */
    public static MutableComponent parseTextComponent(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Component.empty();
        }

        // If it's a string, check if it's actually an embedded/escaped JSON component
        if (element.isJsonPrimitive()) {
            String str = element.getAsString();
            if (str.trim().startsWith("{") && str.trim().endsWith("}")) {
                try {
                    JsonElement inner = JsonParser.parseString(str);
                    if (inner.isJsonObject()) {
                        return parseTextComponent(inner);
                    }
                } catch (Exception ignored) {}
            }
            return Component.literal(str.replace('&', '§'));
        }

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
     * Guaranteed Priority:
     * 1. Season line (Season X: ...) ALWAYS takes top priority.
     * 2. Specific Crate line (ignoring generic "Blossom Crate" if a specific name is present).
     * 3. Item title's own gradient if no header lore line exists.
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

                // 1. TOP PRIORITY: Any Season line
                if (lower.startsWith("season")) {
                    return line;
                }

                // 2. Specific Crate Banner (skip generic "blossom crate" so it doesn't mask seasons)
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

            // 3. Decorative stars / banners
            for (Component line : lore) {
                String plain = line.getString().trim();
                if (plain.startsWith("✦") || plain.startsWith("❀") || plain.startsWith("★")) {
                    return line;
                }
            }

            // 4. If no lore banner exists, extract the gradient palette directly from the item's custom_name!
            Component customName = parseCustomName(rawDataJson, "");
            if (customName != null && !customName.getString().isBlank()) {
                return customName;
            }
        } catch (Exception ignored) {}

        return Component.literal(fallbackTitle);
    }
}