package com.roland.itemdatabase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds API request payloads for item registration and modifications.
 */
public class AdminPayloadBuilder {

    public static class DraftItem {
        public int crateId;
        public int itemId = -1;
        public String itemName;
        public String itemNameHtml;
        public String rarityHuman;
        public String rarityHtml;
        public String winPercentage;
        public String tagPrimary;
        public String tagSecondary;
        public String tagTertiary;
        public String tagQuaternary;
        public String tagQuinary;
        public String tagSenary;
        public String tagSeptenary;
        public String notes;
        public String rawData;
        public String itemHuman;
        public String itemHtml;
        public String imageType = "png";
        public String iconUrl = "";
        public java.io.File pendingImageFile = null;
        public ItemStack sourceStack;
        public List<Component> loreLines = new ArrayList<>();
        public boolean isCrateItem = true;
        public int groupId = 1;
    }

    /**
     * Inspects an in-game ItemStack and populates a new DraftItem instance.
     * Extracts name, enchantments, lore, unbreakable status, and full component JSON.
     */
    public static DraftItem buildDraftFromHeldItem(ItemStack stack, int crateId) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        DraftItem draft = new DraftItem();
        draft.crateId = crateId;
        draft.sourceStack = stack.copy();
        draft.imageType = "png";

        // 1. Resolve display name (prefer custom name, fallback to hover name)
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        Component displayName = customName != null ? customName : stack.getHoverName();
        draft.itemName = displayName.getString().trim();
        draft.itemNameHtml = componentToHtml(displayName);

        // 2. Build RawData JSON payload with full DataComponentPatch
        JsonObject root = new JsonObject();
        String itemIdentifier = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        root.addProperty("id", itemIdentifier);
        root.addProperty("count", stack.getCount());

        JsonObject componentsObj = new JsonObject();
        DataComponentPatch.CODEC
                .encodeStart(JsonOps.INSTANCE, stack.getComponentsPatch())
                .result()
                .ifPresent(json -> {
                    if (json.isJsonObject()) {
                        for (var entry : json.getAsJsonObject().entrySet()) {
                            componentsObj.add(entry.getKey(), entry.getValue());
                        }
                    }
                });

        if (!componentsObj.has("minecraft:custom_name") && customName != null) {
            ComponentSerialization.CODEC
                    .encodeStart(JsonOps.INSTANCE, customName)
                    .result()
                    .ifPresent(json -> componentsObj.add("minecraft:custom_name", json));
        }

        StringBuilder humanSb = new StringBuilder();
        StringBuilder htmlSb = new StringBuilder();

        // Prepend ❀ <exact itemNameHtml> ❀ at top of tooltip HTML
        htmlSb.append("<span style=\"color:#FFAA00;\">❀</span> ")
                .append(draft.itemNameHtml)
                .append(" <span style=\"color:#FFAA00;\">❀</span><br>");

        TooltipDisplay tooltipDisplay = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);

        // 3. Extract Enchantments (vanilla gray #AAAAAA)
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null && !enchantments.isEmpty() && tooltipDisplay.shows(DataComponents.ENCHANTMENTS)) {
            for (Holder<Enchantment> holder : enchantments.keySet()) {
                int level = enchantments.getLevel(holder);
                Component enchComp = Enchantment.getFullname(holder, level);
                humanSb.append(enchComp.getString()).append("\n");
                htmlSb.append("<span style=\"color:#AAAAAA;\">")
                        .append(escapeHtml(enchComp.getString()))
                        .append("</span><br>");
            }
        }

        ItemEnchantments storedEnchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (storedEnchantments != null && !storedEnchantments.isEmpty() && tooltipDisplay.shows(DataComponents.STORED_ENCHANTMENTS)) {
            for (Holder<Enchantment> holder : storedEnchantments.keySet()) {
                int level = storedEnchantments.getLevel(holder);
                Component enchComp = Enchantment.getFullname(holder, level);
                humanSb.append(enchComp.getString()).append("\n");
                htmlSb.append("<span style=\"color:#AAAAAA;\">")
                        .append(escapeHtml(enchComp.getString()))
                        .append("</span><br>");
            }
        }

        // 4. Extract Tooltip Lore lines with exact component colors
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            JsonArray loreArray = new JsonArray();
            List<Component> lines = lore.lines();
            draft.loreLines.addAll(lines);

            for (Component line : lines) {
                ComponentSerialization.CODEC
                        .encodeStart(JsonOps.INSTANCE, line)
                        .result()
                        .ifPresent(loreArray::add);

                String linePlain = line.getString();
                humanSb.append(linePlain).append("\n");

                if (linePlain.isEmpty()) {
                    htmlSb.append("<br>");
                } else {
                    htmlSb.append(componentToHtml(line)).append("<br>");
                }

                detectRarityFromLore(line, draft);
            }
            if (!componentsObj.has("minecraft:lore")) {
                componentsObj.add("minecraft:lore", loreArray);
            }
        }

        // 5. Extract Unbreakable status (vanilla blue #5555FF)
        if (stack.has(DataComponents.UNBREAKABLE) && tooltipDisplay.shows(DataComponents.UNBREAKABLE)) {
            humanSb.append("Unbreakable\n");
            htmlSb.append("<span style=\"color:#5555FF;\">Unbreakable</span><br>");
        }

        // Player profile / skull texture fallback
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile != null && !componentsObj.has("minecraft:profile")) {
            ResolvableProfile.CODEC
                    .encodeStart(JsonOps.INSTANCE, profile)
                    .result()
                    .ifPresent(json -> componentsObj.add("minecraft:profile", json));
        }

        // Trim trailing <br>
        String finalHtml = htmlSb.toString().replaceAll("(<br>)+$", "");
        draft.itemHuman = humanSb.toString().trim();
        draft.itemHtml = finalHtml;

        root.add("components", componentsObj);
        draft.rawData = root.toString();

        // 6. Auto-detect item tag based on item identifier
        draft.tagPrimary = detectPrimaryTag(itemIdentifier);

        return draft;
    }

    /**
     * Converts a DraftItem into the exact JSON schema required by the Flask admin API endpoints.
     */
    public static JsonObject serializeToApiJson(DraftItem draft) {
        JsonObject json = new JsonObject();

        if (draft.isCrateItem) {
            json.addProperty("CrateID", String.valueOf(draft.crateId));
            json.addProperty("WinPercentage", draft.winPercentage != null ? draft.winPercentage : "0%");
            json.addProperty("TagPrimary", draft.tagPrimary != null ? draft.tagPrimary : "None");
            json.addProperty("TagSecondary", draft.tagSecondary != null ? draft.tagSecondary : "None");
            json.addProperty("TagTertiary", draft.tagTertiary != null ? draft.tagTertiary : "None");
            json.addProperty("TagQuaternary", draft.tagQuaternary != null ? draft.tagQuaternary : "None");
            json.addProperty("TagQuinary", draft.tagQuinary != null ? draft.tagQuinary : "None");
            json.addProperty("TagSenary", draft.tagSenary != null ? draft.tagSenary : "None");
            json.addProperty("TagSeptenary", draft.tagSeptenary != null ? draft.tagSeptenary : "None");
        } else {
            json.addProperty("GroupID", String.valueOf(draft.groupId));
        }

        json.addProperty("ItemName", draft.itemName != null ? draft.itemName : "");
        json.addProperty("ItemNameHTML", draft.itemNameHtml != null ? draft.itemNameHtml : draft.itemName);
        json.addProperty("RarityHuman", draft.rarityHuman != null ? draft.rarityHuman : "Common");
        json.addProperty("RarityHTML", draft.rarityHtml != null ? draft.rarityHtml : "<span>Common</span>");
        json.addProperty("Notes", draft.notes != null ? draft.notes : "");
        json.addProperty("RawData", draft.rawData != null ? draft.rawData : "");
        json.addProperty("ItemHuman", draft.itemHuman != null ? draft.itemHuman : "");

        // Use exact component-rendered HTML when available; fallback only if manually edited without itemHtml
        String itemHtml = (draft.itemHtml != null && !draft.itemHtml.isBlank())
                ? draft.itemHtml
                : convertHumanToHtml(draft.itemName, draft.rarityHuman, draft.itemHuman);
        json.addProperty("ItemHTML", itemHtml);

        return json;
    }

    /**
     * Traverses a Minecraft Component and converts both Style colors/decorations
     * and any embedded §/legacy hex codes into exact HTML spans.
     */
    public static String componentToHtml(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder();

        component.visit((style, text) -> {
            if (text.isEmpty()) return Optional.empty();

            TextColor color = style.getColor();
            // Default Minecraft lore color when style.getColor() is null is #AA00AA (dark purple) or #FFFFFF if explicitly set
            String baseColor = (color != null) ? color.formatValue() : null;

            sb.append(renderStyledRun(text, baseColor, style.isBold(), style.isItalic(), style.isUnderlined(), style.isStrikethrough()));
            return Optional.empty();
        }, Style.EMPTY);

        return sb.toString();
    }

    private static String renderStyledRun(String text, String defaultColor, boolean bold, boolean italic, boolean underline, boolean strike) {
        StringBuilder out = new StringBuilder();
        String curColor = defaultColor;
        boolean curBold = bold;
        boolean curItalic = italic;
        boolean curUnder = underline;
        boolean curStrike = strike;

        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));

                // Hex format: §x§R§R§G§G§B§B
                if (code == 'x' && i + 13 < text.length()) {
                    StringBuilder hex = new StringBuilder("#");
                    boolean validHex = true;
                    for (int h = 0; h < 6; h++) {
                        char prefix = text.charAt(i + 2 + (h * 2));
                        char digit = text.charAt(i + 3 + (h * 2));
                        if ((prefix == '§' || prefix == '&') && Character.digit(digit, 16) != -1) {
                            hex.append(digit);
                        } else {
                            validHex = false;
                            break;
                        }
                    }
                    if (validHex) {
                        flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                        curColor = hex.toString().toUpperCase();
                        curBold = false;
                        curItalic = false;
                        curUnder = false;
                        curStrike = false;
                        i += 13;
                        continue;
                    }
                }

                String mapped = legacyCodeToHex(code);
                if (mapped != null) {
                    flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                    curColor = mapped;
                    curBold = false;
                    curItalic = false;
                    curUnder = false;
                    curStrike = false;
                    i++;
                    continue;
                } else if (code == 'l') {
                    flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                    curBold = true;
                    i++;
                    continue;
                } else if (code == 'o') {
                    flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                    curItalic = true;
                    i++;
                    continue;
                } else if (code == 'n') {
                    flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                    curUnder = true;
                    i++;
                    continue;
                } else if (code == 'm') {
                    flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                    curStrike = true;
                    i++;
                    continue;
                } else if (code == 'r') {
                    flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
                    curColor = defaultColor;
                    curBold = bold;
                    curItalic = italic;
                    curUnder = underline;
                    curStrike = strike;
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }

        flushSpan(out, buf, curColor, curBold, curItalic, curUnder, curStrike);
        return out.toString();
    }

    private static void flushSpan(StringBuilder out, StringBuilder buf, String color, boolean bold, boolean italic, boolean underline, boolean strike) {
        if (buf.isEmpty()) return;
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();

        if (color != null) {
            open.append("<span style=\"color:").append(color).append(";\">");
            close.insert(0, "</span>");
        }
        if (bold) { open.append("<b>"); close.insert(0, "</b>"); }
        if (italic) { open.append("<i>"); close.insert(0, "</i>"); }
        if (underline) { open.append("<u>"); close.insert(0, "</u>"); }
        if (strike) { open.append("<s>"); close.insert(0, "</s>"); }

        out.append(open).append(escapeHtml(buf.toString())).append(close);
        buf.setLength(0);
    }

    private static String legacyCodeToHex(char code) {
        return switch (code) {
            case '0' -> "#000000";
            case '1' -> "#0000AA";
            case '2' -> "#00AA00";
            case '3' -> "#00AAAA";
            case '4' -> "#AA0000";
            case '5' -> "#AA00AA";
            case '6' -> "#FFAA00";
            case '7' -> "#AAAAAA";
            case '8' -> "#555555";
            case '9' -> "#5555FF";
            case 'a' -> "#55FF55";
            case 'b' -> "#55FFFF";
            case 'c' -> "#FF5555";
            case 'd' -> "#FF55FF";
            case 'e' -> "#FFFF55";
            case 'f' -> "#FFFFFF";
            default -> null;
        };
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static void detectRarityFromLore(Component lineComp, DraftItem draft) {
        String loreLine = lineComp.getString();
        if (loreLine.toLowerCase().contains("rarity:")) {
            String[] parts = loreLine.split(":", 2);
            if (parts.length > 1) {
                String rarity = parts[1].replaceAll("[^a-zA-Z]", "").trim();
                if (!rarity.isEmpty()) {
                    draft.rarityHuman = rarity;
                    String fullLineHtml = componentToHtml(lineComp);
                    int colonIdx = fullLineHtml.indexOf(':');
                    if (colonIdx != -1 && colonIdx + 1 < fullLineHtml.length()) {
                        draft.rarityHtml = fullLineHtml.substring(colonIdx + 1).trim();
                    } else {
                        draft.rarityHtml = String.format("<span>%s</span>", rarity);
                    }
                }
            }
        }
    }

    private static String detectPrimaryTag(String identifier) {
        String name = identifier.toLowerCase();
        if (name.contains("pickaxe")) return "Pickaxe";
        if (name.contains("axe")) return "Axe";
        if (name.contains("shovel")) return "Shovel";
        if (name.contains("hoe")) return "Hoe";
        if (name.contains("sword")) return "Sword";
        if (name.contains("bow") || name.contains("crossbow")) return "Bow";
        if (name.contains("helmet") || name.contains("chestplate") || name.contains("leggings") || name.contains("boots")) return "Armor";
        if (name.contains("rod")) return "Fishing Rod";
        if (name.contains("shears")) return "Shears";
        return "Misc";
    }

    private static boolean isEnchantmentLine(String line) {
        return line.matches("^(Protection|Fire Protection|Feather Falling|Blast Protection|Projectile Protection|Respiration|Aqua Affinity|Thorns|Depth Strider|Frost Walker|Curse of Binding|Soul Speed|Swift Sneak|Sharpness|Smite|Bane of Arthropods|Knockback|Fire Aspect|Looting|Sweeping Edge|Efficiency|Silk Touch|Unbreaking|Fortune|Power|Punch|Flame|Infinity|Luck of the Sea|Lure|Loyalty|Impaling|Riptide|Channeling|Multishot|Quick Charge|Piercing|Density|Breach|Wind Burst|Mending|Curse of Vanishing)(\\s+[IVXLCDM]+|\\s+\\d+)?$");
    }

    private static String convertHumanToHtml(String itemName, String rarity, String loreText) {
        StringBuilder html = new StringBuilder();

        // 1. Prepend styled item title line: ❀ Name ❀
        if (itemName != null && !itemName.isBlank()) {
            String cleanName = itemName.replaceAll("(?i)[§&][0-9a-fk-or]", "")
                    .replaceAll("^[❀*•\\s]+|[❀*•\\s]+$", "")
                    .trim();

            String nameColor = switch (rarity != null ? rarity.trim().toUpperCase() : "") {
                case "EXOTIC" -> "#FF5555";     // Red
                case "LEGENDARY" -> "#FFAA00";  // Gold
                case "MYTHIC" -> "#FF55FF";     // Pink
                case "EPIC" -> "#AA00AA";       // Dark Purple
                case "RARE" -> "#55FFFF";       // Aqua
                case "UNCOMMON" -> "#55FF55";   // Green
                default -> "#55FFFF";           // Default Aqua
            };

            html.append("<span style=\"color:#FFAA00;\">❀</span> ")
                    .append("<span style=\"color:").append(nameColor).append(";\">").append(cleanName).append("</span> ")
                    .append("<span style=\"color:#FFAA00;\">❀</span><br>");
        }

        if (loreText == null || loreText.isBlank()) {
            return html.toString();
        }

        // 2. Convert each line of the lore
        String[] lines = loreText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                if (i < lines.length - 1) html.append("<br>");
                continue;
            }

            String lower = line.toLowerCase();

            // Divider lines: dark gray
            if (line.matches("^[\u2550\u2500=-]+$")) {
                html.append("<span style=\"color:#555555;\">").append(line).append("</span>");
            }
            // Unbreakable flag: blue
            else if (lower.equals("unbreakable")) {
                html.append("<span style=\"color:#5555FF;\">Unbreakable</span>");
            }
            // Enchantment lines: gray
            else if (isEnchantmentLine(line)) {
                html.append("<span style=\"color:#AAAAAA;\">").append(line).append("</span>");
            }
            // Blossom Crate badge: gray arrows, pink Blossom, aqua Crate
            else if (lower.contains("crate") && (lower.contains("blossom") || lower.contains("▶") || lower.contains("►"))) {
                html.append("<span style=\"color:#AAAAAA;\">▶ </span><span style=\"color:#FF55FF;\">Blossom</span> <span style=\"color:#55FFFF;\">Crate</span><span style=\"color:#AAAAAA;\"> ◀</span>");
            }
            // Season line: gold blossom, yellow season name
            else if (lower.startsWith("season")) {
                String clean = line.replaceAll("[❀*•]", "").trim();
                html.append("<span style=\"color:#AAAAAA;\">").append(clean.replaceFirst("(?i)season\\s*(\\d+):?\\s*(.*)", "Season $1: <span style=\"color:#FFAA00;\">❀</span> <span style=\"color:#FFFF55;\">$2</span>")).append(" <span style=\"color:#FFAA00;\">❀</span></span>");
            }
            // Rarity line: gray label, aqua rarity
            else if (lower.startsWith("rarity:")) {
                String val = line.substring(7).trim();
                html.append("<span style=\"color:#AAAAAA;\">Rarity: </span><span style=\"color:#55FFFF;\">").append(val).append("</span>");
            }
            // Win Chance line: gold label, yellow percentage
            else if (lower.startsWith("win chance:")) {
                String val = line.substring(11).trim();
                html.append("<span style=\"color:#FFAA00;\">Win Chance: </span><span style=\"color:#FFFF55;\">").append(val).append("</span>");
            }
            // Regular lore lines: white
            else {
                html.append("<span style=\"color:#FFFFFF;\">").append(line).append("</span>");
            }

            if (i < lines.length - 1) {
                html.append("<br>");
            }
        }
        return html.toString();
    }
}