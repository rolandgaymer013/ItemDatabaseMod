package com.roland.itemdatabase.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;

/**
 * Parses raw JSON item definitions and metadata from the database backend
 * into live Minecraft ItemStacks with proper components, lore, and equippable assets.
 */
public class RawDataParser {

    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * Parses a raw JSON string using ItemEntry metadata for slot resolution and asset mapping.
     */
    public static ItemStack parseItemStack(ItemEntry entry) {
        if (entry == null) return ItemStack.EMPTY;
        return parseItemStack(entry.rawData(), entry.getTargetEquipmentSlot(), entry.getCleanName());
    }

    /**
     * Legacy convenience overload defaulting to null hints.
     */
    public static ItemStack parseItemStack(String rawDataJson) {
        return parseItemStack(rawDataJson, null, null);
    }

    /**
     * Primary parser reconstructing the full ItemStack.
     */
    public static ItemStack parseItemStack(String rawDataJson, EquipmentSlot explicitSlot, String cleanNameHint) {
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return ItemStack.EMPTY;
        }

        try {
            JsonElement parsed = JsonParser.parseString(rawDataJson);
            if (!parsed.isJsonObject()) return ItemStack.EMPTY;

            JsonObject root = parsed.getAsJsonObject();
            ItemStack stack = ItemStack.EMPTY;

            // 1. Try official CODEC first
            var level = Minecraft.getInstance().level;
            if (level != null) {
                var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                var result = ItemStack.CODEC.parse(ops, root).result();
                if (result.isPresent() && !result.get().isEmpty()) {
                    stack = result.get();
                }
            }

            // 2. Fallback to base item creation if CODEC produced empty stack
            if (stack.isEmpty()) {
                String idStr = root.has("id") ? root.get("id").getAsString() : "";
                if (!idStr.isEmpty()) {
                    Identifier id = Identifier.tryParse(idStr.contains(":") ? idStr : "minecraft:" + idStr);
                    if (id != null) {
                        Item item = BuiltInRegistries.ITEM.getValue(id);
                        if (item != null && item != Items.AIR) {
                            stack = new ItemStack(item);
                        }
                    }
                }
            }

            if (stack.isEmpty()) return ItemStack.EMPTY;

            // 3. Fix Raw JSON Lore
            List<Component> cleanLore = parseLore(rawDataJson);
            if (!cleanLore.isEmpty()) {
                stack.set(DataComponents.LORE, new ItemLore(cleanLore));
            }

            // 4. Custom Name
            Component customName = parseCustomName(rawDataJson, "");
            if (!customName.getString().isBlank()) {
                stack.set(DataComponents.CUSTOM_NAME, customName);
            }

            // 5. Dyed Color (only applied initially; stripped later if equippable armor matches)
            Integer dyeColor = parseDyedColor(rawDataJson);
            if (dyeColor != null) {
                stack.set(DataComponents.DYED_COLOR, new DyedItemColor(dyeColor));
            }

            // 6. Modern components passthrough
            if (root.has("components") && root.get("components").isJsonObject()) {
                JsonObject compObj = root.getAsJsonObject("components");

                if (compObj.has("minecraft:custom_model_data")) {
                    try {
                        float cmd = compObj.get("minecraft:custom_model_data").getAsFloat();
                        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                                new CustomModelData(List.of(cmd), List.of(), List.of(), List.of()));
                    } catch (Exception ignored) {}
                }

                if (compObj.has("minecraft:item_model")) {
                    String modelPath = compObj.get("minecraft:item_model").getAsString();
                    Identifier modelId = Identifier.tryParse(modelPath.contains(":") ? modelPath : "minecraft:" + modelPath);
                    if (modelId != null) {
                        stack.set(DataComponents.ITEM_MODEL, modelId);
                    }
                }

                if (compObj.has("minecraft:equippable") && level != null) {
                    var equipElem = compObj.get("minecraft:equippable");
                    var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    var parsedEq = Equippable.CODEC.parse(ops, equipElem).result();
                    if (parsedEq.isPresent()) {
                        stack.set(DataComponents.EQUIPPABLE, parsedEq.get());
                    }
                }
            }

            // 7. Dynamic Equippable & Custom Armor Asset Resolver
            EquipmentSlot slot = explicitSlot;
            if (slot == null) {
                var defaultEquip = stack.getItem().components().get(DataComponents.EQUIPPABLE);
                if (defaultEquip != null) {
                    slot = defaultEquip.slot();
                } else {
                    String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                    if (itemId.contains("helmet") || itemId.contains("cap") || itemId.contains("head")) slot = EquipmentSlot.HEAD;
                    else if (itemId.contains("chestplate") || itemId.contains("tunic") || itemId.contains("chest")) slot = EquipmentSlot.CHEST;
                    else if (itemId.contains("leggings") || itemId.contains("pants") || itemId.contains("legs")) slot = EquipmentSlot.LEGS;
                    else if (itemId.contains("boots") || itemId.contains("feet")) slot = EquipmentSlot.FEET;
                }
            }

            if (slot != null) {
                String nameToInspect = (cleanNameHint != null && !cleanNameHint.isBlank())
                        ? cleanNameHint.toLowerCase()
                        : stack.getHoverName().getString().toLowerCase();

                // Strip armor slot words and non-alphanumeric decorators to isolate set identifier
                String cleanSetKey = nameToInspect
                        .replace("chestplate", "")
                        .replace("leggings", "")
                        .replace("helmet", "")
                        .replace("boots", "")
                        .replace("elytra", "")
                        .replace("hat", "")
                        .replaceAll("[^a-z0-9]", "")
                        .trim();

                if (!cleanSetKey.isBlank()) {
                    // Strips red/leather dye tint so true custom asset textures render cleanly
                    stack.remove(DataComponents.DYED_COLOR);

                    Identifier assetId = Identifier.tryParse(cleanSetKey + ":" + cleanSetKey + "armor");
                    if (assetId != null) {
                        var assetKey = ResourceKey.create(
                                EquipmentAssets.ROOT_ID,
                                assetId
                        );

                        var equippable = Equippable.builder(slot)
                                .setAsset(assetKey)
                                .build();

                        stack.set(DataComponents.EQUIPPABLE, equippable);
                        stack.remove(DataComponents.DYED_COLOR);
                    }
                }
            }

            return stack;
        } catch (Exception ignored) {}

        return ItemStack.EMPTY;
    }

    /**
     * Parses custom lore lines preserving JSON component hierarchies, hex colors, and legacy styling.
     */
    public static List<Component> parseLore(String rawDataJson) {
        List<Component> result = new ArrayList<>();
        if (rawDataJson == null || rawDataJson.isBlank()) return result;

        try {
            JsonElement root = JsonParser.parseString(rawDataJson);
            if (!root.isJsonObject()) return result;

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("components") && obj.get("components").isJsonObject()) {
                JsonObject comp = obj.getAsJsonObject("components");
                if (comp.has("minecraft:lore") && comp.get("minecraft:lore").isJsonArray()) {
                    var registryAccess = Minecraft.getInstance().level != null
                            ? Minecraft.getInstance().level.registryAccess()
                            : null;

                    for (JsonElement el : comp.getAsJsonArray("minecraft:lore")) {
                        if (el.isJsonPrimitive()) {
                            result.add(parseFormattedText(el.getAsString(), registryAccess));
                        } else if (el.isJsonObject()) {
                            Component parsedComp = parseJsonComponent(el.toString(), registryAccess);
                            result.add(parsedComp != null ? parsedComp : Component.literal(el.toString()));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return result;
    }

    /**
     * Parses custom or vanilla enchantments from raw backend JSON for UI tooltips.
     */
    public static List<Component> parseEnchantments(String rawDataJson) {
        List<Component> result = new ArrayList<>();
        if (rawDataJson == null || rawDataJson.isBlank()) return result;

        try {
            JsonElement root = JsonParser.parseString(rawDataJson);
            if (!root.isJsonObject()) return result;

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("components") && obj.get("components").isJsonObject()) {
                JsonObject comp = obj.getAsJsonObject("components");

                // Check modern minecraft:enchantments component
                if (comp.has("minecraft:enchantments") && comp.get("minecraft:enchantments").isJsonObject()) {
                    JsonObject enchantsObj = comp.getAsJsonObject("minecraft:enchantments");
                    JsonObject levels = enchantsObj.has("levels") && enchantsObj.get("levels").isJsonObject()
                            ? enchantsObj.getAsJsonObject("levels")
                            : enchantsObj;

                    for (Map.Entry<String, JsonElement> entry : levels.entrySet()) {
                        String enchId = entry.getKey();
                        int lvl = entry.getValue().getAsInt();
                        String cleanEnchName = enchId.contains(":") ? enchId.substring(enchId.indexOf(':') + 1) : enchId;
                        cleanEnchName = cleanEnchName.replace('_', ' ');
                        cleanEnchName = Character.toUpperCase(cleanEnchName.charAt(0)) + cleanEnchName.substring(1);

                        result.add(Component.literal("§7" + cleanEnchName + " " + lvl));
                    }
                }

                // Check stored_enchantments (enchanted books)
                if (comp.has("minecraft:stored_enchantments") && comp.get("minecraft:stored_enchantments").isJsonObject()) {
                    JsonObject storedObj = comp.getAsJsonObject("minecraft:stored_enchantments");
                    JsonObject levels = storedObj.has("levels") && storedObj.get("levels").isJsonObject()
                            ? storedObj.getAsJsonObject("levels")
                            : storedObj;

                    for (Map.Entry<String, JsonElement> entry : levels.entrySet()) {
                        String enchId = entry.getKey();
                        int lvl = entry.getValue().getAsInt();
                        String cleanEnchName = enchId.contains(":") ? enchId.substring(enchId.indexOf(':') + 1) : enchId;
                        cleanEnchName = cleanEnchName.replace('_', ' ');
                        cleanEnchName = Character.toUpperCase(cleanEnchName.charAt(0)) + cleanEnchName.substring(1);

                        result.add(Component.literal("§7" + cleanEnchName + " " + lvl));
                    }
                }
            }
        } catch (Exception ignored) {}

        return result;
    }

    /**
     * Parses custom display name preserving colors and symbols.
     */
    public static Component parseCustomName(String rawDataJson, String fallback) {
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return fallback != null ? Component.literal(fallback) : Component.empty();
        }

        try {
            JsonElement root = JsonParser.parseString(rawDataJson);
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("components") && obj.get("components").isJsonObject()) {
                    JsonObject comp = obj.getAsJsonObject("components");
                    if (comp.has("minecraft:custom_name")) {
                        JsonElement nameElem = comp.get("minecraft:custom_name");
                        var registryAccess = Minecraft.getInstance().level != null
                                ? Minecraft.getInstance().level.registryAccess()
                                : null;

                        if (nameElem.isJsonPrimitive()) {
                            return parseFormattedText(nameElem.getAsString(), registryAccess);
                        } else if (nameElem.isJsonObject()) {
                            Component parsedComp = parseJsonComponent(nameElem.toString(), registryAccess);
                            if (parsedComp != null) return parsedComp;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return fallback != null ? Component.literal(fallback) : Component.empty();
    }

    /**
     * Parses integer RGB dye color component if present.
     */
    public static Integer parseDyedColor(String rawDataJson) {
        if (rawDataJson == null || rawDataJson.isBlank()) return null;

        try {
            JsonElement root = JsonParser.parseString(rawDataJson);
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("components") && obj.get("components").isJsonObject()) {
                    JsonObject comp = obj.getAsJsonObject("components");
                    if (comp.has("minecraft:dyed_color")) {
                        JsonElement dyed = comp.get("minecraft:dyed_color");
                        if (dyed.isJsonObject() && dyed.getAsJsonObject().has("rgb")) {
                            return dyed.getAsJsonObject().get("rgb").getAsInt();
                        } else if (dyed.isJsonPrimitive()) {
                            return dyed.getAsInt();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Formats legacy string with section codes, ampersands, and raw hex (&#RRGGBB).
     */
    public static Component parseFormattedText(String text, net.minecraft.core.HolderLookup.Provider registryAccess) {
        if (text == null || text.isBlank()) return Component.empty();

        // If string contains JSON structure, deserialize it directly
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("{\"") && text.endsWith("\"}"))) {
            Component fromJson = parseJsonComponent(text, registryAccess);
            if (fromJson != null) return fromJson;
        }

        // Process &#RRGGBB hex patterns into native components
        Matcher hexMatcher = HEX_COLOR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (hexMatcher.find()) {
            String hex = hexMatcher.group(1);
            hexMatcher.appendReplacement(sb, "§#" + hex);
        }
        hexMatcher.appendTail(sb);
        String normalized = sb.toString().replace('&', '§');

        var root = Component.empty();
        String[] parts = normalized.split("§");
        Style currentStyle = Style.EMPTY.withItalic(false);

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            if (i == 0 && !normalized.startsWith("§")) {
                root.append(Component.literal(part).setStyle(currentStyle));
                continue;
            }

            if (part.startsWith("#") && part.length() >= 7) {
                String hex = part.substring(0, 7);
                try {
                    currentStyle = Style.EMPTY.withColor(TextColor.parseColor(hex).getOrThrow()).withItalic(false);
                } catch (Exception ignored) {}
                String content = part.substring(7);
                if (!content.isEmpty()) {
                    root.append(Component.literal(content).setStyle(currentStyle));
                }
            } else {
                char code = part.charAt(0);
                String content = part.substring(1);
                ChatFormatting formatting = ChatFormatting.getByCode(code);
                if (formatting != null) {
                    if (formatting.isColor()) {
                        currentStyle = Style.EMPTY.withColor(formatting).withItalic(false);
                    } else if (formatting == ChatFormatting.RESET) {
                        currentStyle = Style.EMPTY.withItalic(false);
                    } else if (formatting == ChatFormatting.BOLD) {
                        currentStyle = currentStyle.withBold(true);
                    } else if (formatting == ChatFormatting.ITALIC) {
                        currentStyle = currentStyle.withItalic(true);
                    } else if (formatting == ChatFormatting.UNDERLINE) {
                        currentStyle = currentStyle.withUnderlined(true);
                    } else if (formatting == ChatFormatting.STRIKETHROUGH) {
                        currentStyle = currentStyle.withStrikethrough(true);
                    } else if (formatting == ChatFormatting.OBFUSCATED) {
                        currentStyle = currentStyle.withObfuscated(true);
                    }
                }
                if (!content.isEmpty()) {
                    root.append(Component.literal(content).setStyle(currentStyle));
                }
            }
        }
        return root;
    }

    private static Component parseJsonComponent(String json, net.minecraft.core.HolderLookup.Provider registryAccess) {
        try {
            var elem = JsonParser.parseString(json);
            if (registryAccess != null) {
                var ops = registryAccess.createSerializationContext(JsonOps.INSTANCE);
                var res = ComponentSerialization.CODEC.parse(ops, elem).result();
                if (res.isPresent()) return res.get();
            } else {
                var res = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, elem).result();
                if (res.isPresent()) return res.get();
            }
        } catch (Exception ignored) {}
        return null;
    }
}