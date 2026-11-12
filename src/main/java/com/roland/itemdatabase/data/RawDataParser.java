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
    private static Map<String, Identifier> cachedEquipmentAssets = null;
    private static final Map<String, ItemStack> PARSED_STACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static Map<String, Identifier> cachedCmdModelMap = null;

    /**
     * Parses a raw JSON string using ItemEntry metadata for slot resolution and asset mapping.
     */
    public static ItemStack parseItemStack(ItemEntry entry) {
        if (entry == null) return ItemStack.EMPTY;
        String cacheKey = entry.id() + "_" + entry.getTargetEquipmentSlot();
        ItemStack cached = PARSED_STACK_CACHE.get(cacheKey);
        if (cached != null) {
            return cached.copy();
        }
        ItemStack parsed = parseItemStack(entry.rawData(), entry.getTargetEquipmentSlot(), entry.getCleanName());
        if (!parsed.isEmpty() && Minecraft.getInstance().level != null) {
            PARSED_STACK_CACHE.put(cacheKey, parsed.copy());
        }
        return parsed;
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

            // 6. Modern components passthrough (CustomModelData, ItemModel, Equippable)
            if (root.has("components") && root.get("components").isJsonObject()) {
                JsonObject compObj = root.getAsJsonObject("components");

                // Parse Custom Model Data (supports both primitive number, array, and object formats)
                JsonElement cmdElem = compObj.has("minecraft:custom_model_data")
                        ? compObj.get("minecraft:custom_model_data")
                        : compObj.get("custom_model_data");

                if (cmdElem != null) {
                    try {
                        List<Float> floats = new ArrayList<>();
                        List<Boolean> flags = new ArrayList<>();
                        List<String> strings = new ArrayList<>();
                        List<Integer> colors = new ArrayList<>();

                        if (cmdElem.isJsonPrimitive()) {
                            floats.add(cmdElem.getAsFloat());
                        } else if (cmdElem.isJsonObject()) {
                            JsonObject cmdObj = cmdElem.getAsJsonObject();
                            if (cmdObj.has("floats") && cmdObj.get("floats").isJsonArray()) {
                                for (JsonElement f : cmdObj.getAsJsonArray("floats")) {
                                    floats.add(f.getAsFloat());
                                }
                            }
                            if (cmdObj.has("strings") && cmdObj.get("strings").isJsonArray()) {
                                for (JsonElement s : cmdObj.getAsJsonArray("strings")) {
                                    strings.add(s.getAsString());
                                }
                            }
                        } else if (cmdElem.isJsonArray()) {
                            for (JsonElement f : cmdElem.getAsJsonArray()) {
                                if (f.isJsonPrimitive()) floats.add(f.getAsFloat());
                            }
                        }

                        if (!floats.isEmpty() || !strings.isEmpty()) {
                            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, flags, strings, colors));
                        }
                    } catch (Exception ignored) {}
                }

                // Parse Item Model (1.21.4+ item_model component)
                JsonElement modelElem = compObj.has("minecraft:item_model")
                        ? compObj.get("minecraft:item_model")
                        : compObj.get("item_model");

                if (modelElem != null && modelElem.isJsonPrimitive()) {
                    String modelPath = modelElem.getAsString();
                    Identifier modelId = Identifier.tryParse(modelPath.contains(":") ? modelPath : "minecraft:" + modelPath);
                    if (modelId != null) {
                        stack.set(DataComponents.ITEM_MODEL, modelId);
                    }
                }

                // Parse Equippable component
                JsonElement equipElem = compObj.has("minecraft:equippable")
                        ? compObj.get("minecraft:equippable")
                        : compObj.get("equippable");

                if (equipElem != null && level != null) {
                    var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    var parsedEq = Equippable.CODEC.parse(ops, equipElem).result();
                    if (parsedEq.isPresent()) {
                        stack.set(DataComponents.EQUIPPABLE, parsedEq.get());
                    }
                }

                // Parse Player Head Profile (Base64 skin textures for custom skull helmets like Bly's Helmet)
                JsonElement profileElem = compObj.has("minecraft:profile")
                        ? compObj.get("minecraft:profile")
                        : compObj.get("profile");

                if (profileElem != null && level != null) {
                    var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    var parsedProfile = net.minecraft.world.item.component.ResolvableProfile.CODEC.parse(ops, profileElem).result();
                    if (parsedProfile.isPresent()) {
                        stack.set(DataComponents.PROFILE, parsedProfile.get());
                    }
                }
            }

            // 7. Dynamic Equippable & Custom Armor Asset Resolver
            boolean isCustomHeadModel = stack.is(Items.PAPER)
                    || stack.is(Items.CARVED_PUMPKIN)
                    || stack.is(Items.PLAYER_HEAD);

            Equippable existingEq = stack.get(DataComponents.EQUIPPABLE);
            boolean hasCustomAssetAlready = existingEq != null
                    && existingEq.assetId().isPresent()
                    && !existingEq.assetId().get().identifier().getNamespace().equals("minecraft");

            // Check if raw JSON defines minecraft:equippable with slot:"head" and NO asset_id (3D Blockbench hats/helmets)
            boolean hasExplicitHeadSlotWithoutAsset = false;
            if (root.has("components") && root.get("components").isJsonObject()) {
                JsonObject compObj = root.getAsJsonObject("components");
                JsonElement eqElem = compObj.has("minecraft:equippable")
                        ? compObj.get("minecraft:equippable")
                        : compObj.get("equippable");
                if (eqElem != null && eqElem.isJsonObject()) {
                    JsonObject eqObj = eqElem.getAsJsonObject();
                    if (eqObj.has("slot") && "head".equalsIgnoreCase(eqObj.get("slot").getAsString()) && !eqObj.has("asset_id")) {
                        hasExplicitHeadSlotWithoutAsset = true;
                    }
                }
            }

            if (hasExplicitHeadSlotWithoutAsset) {
                Identifier dispatchItemModel = resolveDispatchItemModel(stack);
                if (dispatchItemModel != null) {
                    stack.set(DataComponents.ITEM_MODEL, dispatchItemModel);
                }
            } else if (!isCustomHeadModel && !hasCustomAssetAlready) {
                EquipmentSlot slot = explicitSlot != null ? explicitSlot
                        : (existingEq != null ? existingEq.slot() : null);

                // Fallback slot detection from base item ID (e.g., minecraft:leather_boots -> FEET)
                if (slot == null) {
                    String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                    if (itemId.contains("helmet") || itemId.contains("cap") || itemId.contains("head")) slot = EquipmentSlot.HEAD;
                    else if (itemId.contains("chestplate") || itemId.contains("tunic") || itemId.contains("chest")) slot = EquipmentSlot.CHEST;
                    else if (itemId.contains("leggings") || itemId.contains("pants") || itemId.contains("legs")) slot = EquipmentSlot.LEGS;
                    else if (itemId.contains("boots") || itemId.contains("feet")) slot = EquipmentSlot.FEET;
                }

                if (slot != null) {
                    // Build candidate set keys from parsed customName, cleanNameHint, and ExecutableItems ID
                    List<String> rawNames = new ArrayList<>();
                    if (!customName.getString().isBlank()) rawNames.add(customName.getString());
                    if (cleanNameHint != null && !cleanNameHint.isBlank()) rawNames.add(cleanNameHint);

                    if (root.has("components") && root.getAsJsonObject("components").has("minecraft:custom_data")) {
                        JsonObject customData = root.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data");
                        if (customData.has("PublicBukkitValues") && customData.getAsJsonObject("PublicBukkitValues").has("executableitems:ei-id")) {
                            String eiId = customData.getAsJsonObject("PublicBukkitValues").get("executableitems:ei-id").getAsString();
                            rawNames.add(eiId.replaceFirst("(?i)^blossom_", ""));
                        }
                    }

                    Identifier resolvedItemModel = resolveModelFromCmd(stack);
                    Identifier matchedAsset = null;

                    if (stack.is(Items.ELYTRA)) {
                        // Only override Elytra equippable if a dedicated *_elytra equipment asset exists in the pack
                        if (resolvedItemModel != null) {
                            matchedAsset = findElytraAssetInPack(resolvedItemModel.getNamespace());
                        }
                        if (matchedAsset == null) {
                            for (String candidateName : rawNames) {
                                String cleanSetKey = sanitizeLegacyAmpersands(candidateName.toLowerCase())
                                        .replaceAll("§[0-9a-fk-or]", "")
                                        .replace("elytra", "")
                                        .replaceAll("[^a-z0-9]", "")
                                        .trim();
                                if (!cleanSetKey.isBlank()) {
                                    matchedAsset = findElytraAssetInPack(cleanSetKey);
                                    if (matchedAsset != null) break;
                                }
                            }
                        }

                        if (matchedAsset != null) {
                            var assetKey = ResourceKey.create(EquipmentAssets.ROOT_ID, matchedAsset);
                            stack.set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST).setAsset(assetKey).build());
                        }
                        if (resolvedItemModel != null) {
                            stack.set(DataComponents.ITEM_MODEL, resolvedItemModel);
                        }
                    } else {
                        // Standard Armor (Helmet, Chestplate, Leggings, Boots)
                        if (resolvedItemModel != null) {
                            matchedAsset = findEquipmentAssetInPack(resolvedItemModel.getNamespace());
                        }

                        if (matchedAsset == null) {
                            for (String candidateName : rawNames) {
                                String cleanSetKey = sanitizeLegacyAmpersands(candidateName.toLowerCase())
                                        .replaceAll("§[0-9a-fk-or]", "")
                                        .replace("chestplate", "")
                                        .replace("leggings", "")
                                        .replace("helmet", "")
                                        .replace("boots", "")
                                        .replace("hat", "")
                                        .replaceAll("[^a-z0-9]", "")
                                        .trim();

                                if (!cleanSetKey.isBlank()) {
                                    matchedAsset = findEquipmentAssetInPack(cleanSetKey);
                                    if (matchedAsset != null) break;
                                }
                            }
                        }

                        // If this is a 3D Blockbench helmet (model JSON has "elements"), render its 3D ITEM_MODEL on the head instead of a 2D armor asset
                        if (slot == EquipmentSlot.HEAD && resolvedItemModel != null && is3DBlockbenchModel(resolvedItemModel)) {
                            stack.remove(DataComponents.DYED_COLOR);
                            stack.set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD).build());
                            Identifier dispatchItemModel = resolveDispatchItemModel(stack);
                            if (dispatchItemModel != null) {
                                stack.set(DataComponents.ITEM_MODEL, dispatchItemModel);
                            }
                        } else if (matchedAsset != null) {
                            stack.remove(DataComponents.DYED_COLOR);
                            var assetKey = ResourceKey.create(EquipmentAssets.ROOT_ID, matchedAsset);
                            var equippable = Equippable.builder(slot)
                                    .setAsset(assetKey)
                                    .build();
                            stack.set(DataComponents.EQUIPPABLE, equippable);

                            if (resolvedItemModel != null) {
                                Identifier dispatchItemModel = resolveDispatchItemModel(stack);
                                if (dispatchItemModel != null) {
                                    stack.set(DataComponents.ITEM_MODEL, dispatchItemModel);
                                }
                            }
                        }
                    }
                }
            }

            return stack;
        } catch (Exception ignored) {}

        return ItemStack.EMPTY;
    }

    private static Identifier findEquipmentAssetInPack(String setKey) {
        if (setKey == null || setKey.isBlank()) return null;

        if (cachedEquipmentAssets == null) {
            cachedEquipmentAssets = new java.util.HashMap<>();
            var rm = Minecraft.getInstance().getResourceManager();
            var found = rm.listResources("equipment", id -> id.getPath().endsWith(".json"));

            for (Identifier foundId : found.keySet()) {
                String ns = foundId.getNamespace();
                if (ns.equals("minecraft")) continue; // Skip vanilla leather/iron/diamond

                String path = foundId.getPath();
                String assetName = path.substring("equipment/".length(), path.length() - ".json".length());
                Identifier assetId = Identifier.fromNamespaceAndPath(ns, assetName);

                // Index by namespace, full asset name, and stripped base key
                String normNs = ns.replace("set", "").replace("_armor", "").replace("armor", "").replace("_", "");
                String normAsset = assetName.replace("set", "").replace("_armor", "").replace("armor", "").replace("_", "");

                if (!normNs.isEmpty()) cachedEquipmentAssets.putIfAbsent(normNs, assetId);
                if (!normAsset.isEmpty()) cachedEquipmentAssets.putIfAbsent(normAsset, assetId);
                cachedEquipmentAssets.putIfAbsent(assetName.replace("_", ""), assetId);
            }
        }

        // 1. Direct O(1) lookup
        if (cachedEquipmentAssets.containsKey(setKey)) {
            return cachedEquipmentAssets.get(setKey);
        }

        // 2. Fallback substring match against cached keys (e.g., "forestgod" -> "forest")
        for (Map.Entry<String, Identifier> entry : cachedEquipmentAssets.entrySet()) {
            String key = entry.getKey();
            if (key.length() >= 4 && (setKey.contains(key) || key.contains(setKey))) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static Identifier findElytraAssetInPack(String setKey) {
        if (setKey == null || setKey.isBlank()) return null;
        var rm = Minecraft.getInstance().getResourceManager();
        var found = rm.listResources("equipment", id -> id.getPath().endsWith(".json") && id.getPath().contains("elytra"));

        for (Identifier foundId : found.keySet()) {
            String ns = foundId.getNamespace();
            if (ns.equals("minecraft")) continue;
            String path = foundId.getPath();
            String assetName = path.substring("equipment/".length(), path.length() - ".json".length());
            String normNs = ns.replace("_", "");
            String normAsset = assetName.replace("_", "");
            if (normNs.contains(setKey) || setKey.contains(normNs) || normAsset.contains(setKey)) {
                return Identifier.fromNamespaceAndPath(ns, assetName);
            }
        }
        return null;
    }

    private static boolean is3DBlockbenchModel(Identifier modelId) {
        if (modelId == null) return false;
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            Identifier jsonLoc = Identifier.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json");
            var res = rm.getResource(jsonLoc);
            if (res.isPresent()) {
                try (var reader = res.get().openAsReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("elements") && root.get("elements").isJsonArray() && !root.getAsJsonArray("elements").isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
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

        return fallback != null ? parseFormattedText(sanitizeLegacyAmpersands(fallback), null) : Component.empty();
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
            // Convert valid & color/format codes to § and strip invalid gradient & prefixes (like &E, &H, &S)
            String cleanedJson = sanitizeLegacyAmpersands(json);
            var elem = JsonParser.parseString(cleanedJson);
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

    public static String sanitizeLegacyAmpersands(String input) {
        if (input == null || input.isEmpty()) return input;
        // 1. Convert valid Minecraft color/formatting codes (&0-9, &a-f, &k-o, &r) to §
        String converted = input.replaceAll("(?i)&([0-9a-fk-or])", "§$1");
        // 2. Remove any leftover invalid &X sequences (e.g., &E, &H, &S from broken gradient strings)
        converted = converted.replaceAll("&(?![#0-9a-fA-Fk-oK-OrR])", "");
        return converted;
    }

    private static Identifier resolveModelFromCmd(ItemStack stack) {
        if (cachedCmdModelMap == null) {
            cachedCmdModelMap = new java.util.HashMap<>();
            var rm = Minecraft.getInstance().getResourceManager();

            // 1. Scan models/item/*.json "overrides" across ALL namespaces (minecraft, ia, itemsadder, etc.)
            var legacyItemModels = rm.listResources("models/item", id -> id.getPath().endsWith(".json"));
            for (var entry : legacyItemModels.entrySet()) {
                String path = entry.getKey().getPath(); // e.g., "models/item/leather_boots.json"
                String baseItemName = path.substring("models/item/".length(), path.length() - ".json".length());

                try (var reader = entry.getValue().openAsReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("overrides") && root.get("overrides").isJsonArray()) {
                        for (JsonElement el : root.getAsJsonArray("overrides")) {
                            if (!el.isJsonObject()) continue;
                            JsonObject overrideObj = el.getAsJsonObject();
                            if (overrideObj.has("predicate") && overrideObj.get("predicate").isJsonObject()
                                    && overrideObj.has("model") && overrideObj.get("model").isJsonPrimitive()) {
                                JsonObject pred = overrideObj.getAsJsonObject("predicate");
                                if (pred.has("custom_model_data") && pred.get("custom_model_data").isJsonPrimitive()) {
                                    int cmdVal = Math.round(pred.get("custom_model_data").getAsFloat());
                                    Identifier modelId = Identifier.tryParse(overrideObj.get("model").getAsString());
                                    if (modelId != null) {
                                        cachedCmdModelMap.put(baseItemName + ":" + cmdVal, modelId);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 2. Also scan modern items/*.json across ALL namespaces
            var itemDefs = rm.listResources("items", id -> id.getPath().endsWith(".json"));
            for (var entry : itemDefs.entrySet()) {
                String path = entry.getKey().getPath();
                String baseItemName = path.substring("items/".length(), path.length() - ".json".length());

                try (var reader = entry.getValue().openAsReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("model") && root.get("model").isJsonObject()) {
                        extractRangeEntries(baseItemName, root.getAsJsonObject("model"));
                    }
                } catch (Exception ignored) {}
            }
        }

        String baseItemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null && !cmd.floats().isEmpty()) {
            int cmdInt = Math.round(cmd.floats().getFirst());

            // 1. Try exact base item first (e.g. leather_boots:1)
            Identifier exact = cachedCmdModelMap.get(baseItemPath + ":" + cmdInt);
            if (exact != null) return exact;

            // 2. Fallback across armor material equivalents (e.g. leather_boots -> netherite_boots, diamond_boots, etc.)
            String slotSuffix = null;
            if (baseItemPath.endsWith("_helmet")) slotSuffix = "_helmet";
            else if (baseItemPath.endsWith("_chestplate")) slotSuffix = "_chestplate";
            else if (baseItemPath.endsWith("_leggings")) slotSuffix = "_leggings";
            else if (baseItemPath.endsWith("_boots")) slotSuffix = "_boots";

            if (slotSuffix != null) {
                for (String tier : List.of("netherite", "diamond", "iron", "golden", "chainmail", "leather")) {
                    Identifier tierMatch = cachedCmdModelMap.get(tier + slotSuffix + ":" + cmdInt);
                    if (tierMatch != null) return tierMatch;
                }
            }
        }

        return null;
    }

    private static Identifier resolveDispatchItemModel(ItemStack stack) {
        if (cachedCmdModelMap == null) {
            resolveModelFromCmd(stack);
        }
        String baseItemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd != null && !cmd.floats().isEmpty()) {
            int cmdInt = Math.round(cmd.floats().getFirst());

            if (cachedCmdModelMap.containsKey(baseItemPath + ":" + cmdInt)) {
                return Identifier.withDefaultNamespace(baseItemPath);
            }

            String slotSuffix = null;
            if (baseItemPath.endsWith("_helmet")) slotSuffix = "_helmet";
            else if (baseItemPath.endsWith("_chestplate")) slotSuffix = "_chestplate";
            else if (baseItemPath.endsWith("_leggings")) slotSuffix = "_leggings";
            else if (baseItemPath.endsWith("_boots")) slotSuffix = "_boots";

            if (slotSuffix != null) {
                for (String tier : List.of("netherite", "diamond", "iron", "golden", "chainmail", "leather")) {
                    String candidate = tier + slotSuffix;
                    if (cachedCmdModelMap.containsKey(candidate + ":" + cmdInt)) {
                        return Identifier.withDefaultNamespace(candidate);
                    }
                }
            }
        }
        return null;
    }

    private static void extractRangeEntries(String baseItemName, JsonObject obj) {
        if (obj == null) return;

        if (obj.has("entries") && obj.get("entries").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("entries")) {
                if (!el.isJsonObject()) continue;
                JsonObject entryObj = el.getAsJsonObject();
                if (entryObj.has("threshold") && entryObj.has("model")) {
                    int threshold = Math.round(entryObj.get("threshold").getAsFloat());
                    JsonElement modelEl = entryObj.get("model");
                    String modelStr = null;

                    if (modelEl.isJsonPrimitive()) {
                        modelStr = modelEl.getAsString();
                    } else if (modelEl.isJsonObject()) {
                        JsonObject targetModel = modelEl.getAsJsonObject();
                        if (targetModel.has("model") && targetModel.get("model").isJsonPrimitive()) {
                            modelStr = targetModel.get("model").getAsString();
                        } else {
                            extractRangeEntries(baseItemName, targetModel);
                        }
                    }

                    if (modelStr != null) {
                        Identifier id = Identifier.tryParse(modelStr);
                        if (id != null) {
                            cachedCmdModelMap.put(baseItemName + ":" + threshold, id);
                        }
                    }
                }
            }
        }

        for (String key : List.of("model", "fallback", "on_true", "on_false")) {
            if (obj.has(key) && obj.get(key).isJsonObject()) {
                extractRangeEntries(baseItemName, obj.getAsJsonObject(key));
            }
        }

        if (obj.has("cases") && obj.get("cases").isJsonArray()) {
            for (JsonElement caseEl : obj.getAsJsonArray("cases")) {
                if (caseEl.isJsonObject() && caseEl.getAsJsonObject().has("model") && caseEl.getAsJsonObject().get("model").isJsonObject()) {
                    extractRangeEntries(baseItemName, caseEl.getAsJsonObject().getAsJsonObject("model"));
                }
            }
        }
    }
}