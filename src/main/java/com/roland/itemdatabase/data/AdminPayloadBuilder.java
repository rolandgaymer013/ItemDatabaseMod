package com.roland.itemdatabase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * Builds API request payloads for item registration and modifications.
 * <p>
 * Handles:
 * <ul>
 *   <li>Extracting in-game {@link ItemStack} attributes and serializing {@link DataComponents} into RawData JSON.</li>
 *   <li>Auto-detecting item categories, rarity tiers, and primary tags from item metadata and lore.</li>
 *   <li>Generating styled HTML markup with inline CSS colors and decorations for web cards.</li>
 *   <li>Assembling the complete JSON schema expected by POST and PATCH /api/v1/admin/item.</li>
 * </ul>
 */
public class AdminPayloadBuilder {

    /**
     * In-memory mutable model holding item attributes prior to API transmission.
     */
    public static class DraftItem {
        /** Unique database ID of the parent crate (used when isCrateItem is true). */
        public int crateId;

        /** Database primary key; -1 indicates an uncommitted new item. */
        public int itemId = -1;

        /** Display name of the item. */
        public String itemName;

        /** Formatted HTML name snippet matching Minecraft style codes. */
        public String itemNameHtml;

        /** Human-readable rarity string (e.g., Common, Rare, Legendary). */
        public String rarityHuman;

        /** HTML span snippet containing the rarity label. */
        public String rarityHtml;

        /** Configured crate win chance percentage string (e.g. "4%"). */
        public String winPercentage;

        /** Primary item classification tag (e.g., Boots, Sword, Pickaxe). */
        public String tagPrimary;

        /** Secondary item classification tag. */
        public String tagSecondary;

        /** Tertiary item classification tag. */
        public String tagTertiary;

        /** Quaternary item classification tag. */
        public String tagQuaternary;

        /** Quinary item classification tag. */
        public String tagQuinary;

        /** Senary item classification tag. */
        public String tagSenary;

        /** Septenary item classification tag. */
        public String tagSeptenary;

        /** Administrative notes or commentary. */
        public String notes;

        /** Serialized Minecraft NBT/JSON component representation. */
        public String rawData;

        /** Plain-text multi-line lore description. */
        public String itemHuman;

        /** Generated HTML representation used by the web interface. */
        public String itemHtml;

        /** File extension of the item's sprite icon (e.g. "png" or "gif"). */
        public String imageType = "png";

        /** Web URL pointing to the item's existing icon on the server. */
        public String iconUrl = "";

        /** Staged local image file scheduled for multipart upload. */
        public java.io.File pendingImageFile = null;

        /** Reference to the source item stack if drafted directly from player inventory. */
        public net.minecraft.world.item.ItemStack sourceStack;

        /** In-memory styled Component lines rendered in GUI previews. */
        public java.util.List<Component> loreLines = new java.util.ArrayList<>();

        /** Toggle indicating if this item targets a Crate or a Misc Group. */
        public boolean isCrateItem = true;

        /** Unique ID of the parent misc group (used when isCrateItem is false). */
        public int groupId = 1;
    }

    /**
     * Inspects a held in-game ItemStack and populates a new DraftItem instance.
     * Extracts name, lore, player skulls, and primary categories.
     *
     * @param stack   The ItemStack currently held by the player.
     * @param crateId The target crate ID assigned to this new item.
     * @return Fully populated DraftItem, or null if the stack is empty.
     */
    public static DraftItem buildDraftFromHeldItem(ItemStack stack, int crateId) {
        if (stack.isEmpty()) {
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

        // 2. Build RawData JSON payload compatible with RawDataParser and Mojang codecs
        JsonObject root = new JsonObject();
        String itemIdentifier = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        root.addProperty("id", itemIdentifier);
        root.addProperty("count", stack.getCount());

        JsonObject componentsObj = new JsonObject();

        // Custom name component
        if (customName != null) {
            net.minecraft.network.chat.ComponentSerialization.CODEC
                    .encodeStart(JsonOps.INSTANCE, customName)
                    .result()
                    .ifPresent(json -> componentsObj.add("minecraft:custom_name", (com.google.gson.JsonElement) json));
        }

        // Tooltip lore component
        ItemLore lore = stack.get(DataComponents.LORE);
        StringBuilder humanSb = new StringBuilder();
        StringBuilder htmlSb = new StringBuilder();

        htmlSb.append("<div class=\"item-tooltip\">");
        htmlSb.append("<div class=\"item-title\">").append(draft.itemNameHtml).append("</div>");
        humanSb.append(draft.itemName).append("\n");

        if (lore != null && !lore.lines().isEmpty()) {
            JsonArray loreArray = new JsonArray();
            List<Component> lines = lore.lines();
            for (Component line : lines) {
                net.minecraft.network.chat.ComponentSerialization.CODEC
                        .encodeStart(JsonOps.INSTANCE, line)
                        .result()
                        .ifPresent(json -> loreArray.add((com.google.gson.JsonElement) json));

                String linePlain = line.getString();
                humanSb.append(linePlain).append("\n");
                htmlSb.append("<div class=\"item-lore-line\">").append(componentToHtml(line)).append("</div>");

                // Auto-detect Rarity line if present in lore (e.g. "Rarity: Mythic")
                detectRarityFromLore(linePlain, draft);
            }
            componentsObj.add("minecraft:lore", loreArray);
        }

        // Player profile / skull texture component
        net.minecraft.world.item.component.ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile != null) {
            net.minecraft.world.item.component.ResolvableProfile.CODEC
                    .encodeStart(JsonOps.INSTANCE, profile)
                    .result()
                    .ifPresent(json -> componentsObj.add("minecraft:profile", (com.google.gson.JsonElement) json));
        }

        htmlSb.append("</div>");
        draft.itemHuman = humanSb.toString().trim();
        draft.itemHtml = htmlSb.toString().trim();

        root.add("components", componentsObj);
        draft.rawData = root.toString();

        // 3. Auto-detect item tag based on item identifier
        draft.tagPrimary = detectPrimaryTag(itemIdentifier);

        return draft;
    }

    /**
     * Converts a DraftItem into the exact JSON schema required by the Flask admin API endpoints.
     * Maps fields to Crate Item or Miscellaneous Item database columns accordingly.
     *
     * @param draft The working draft item to serialize.
     * @return JsonObject matching backend database column specifications.
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

        String itemHtml = convertHumanToHtml(draft.itemName, draft.rarityHuman, draft.itemHuman);
        json.addProperty("ItemHTML", itemHtml);

        return json;
    }

    /**
     * Traverses a Minecraft Component and converts its styles, colors, and decorations into HTML tags.
     *
     * @param component Text component to convert.
     * @return HTML snippet preserving formatting codes and hex colors.
     */
    public static String componentToHtml(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder();

        component.visit((style, text) -> {
            if (text.isEmpty()) return java.util.Optional.empty();

            StringBuilder openTags = new StringBuilder();
            StringBuilder closeTags = new StringBuilder();

            TextColor color = style.getColor();
            if (color != null) {
                openTags.append("<span style=\"color:").append(color.formatValue()).append(";\">");
                closeTags.insert(0, "</span>");
            }
            if (style.isBold()) {
                openTags.append("<b>");
                closeTags.insert(0, "</b>");
            }
            if (style.isItalic()) {
                openTags.append("<i>");
                closeTags.insert(0, "</i>");
            }
            if (style.isUnderlined()) {
                openTags.append("<u>");
                closeTags.insert(0, "</u>");
            }
            if (style.isStrikethrough()) {
                openTags.append("<s>");
                closeTags.insert(0, "</s>");
            }

            // Escape HTML entities to prevent malformed tags
            String escaped = text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");

            sb.append(openTags).append(escaped).append(closeTags);
            return java.util.Optional.empty();
        }, Style.EMPTY);

        return sb.toString();
    }

    /**
     * Inspects plain lore lines to identify rarity declarations (e.g., "Rarity: Mythic").
     */
    private static void detectRarityFromLore(String loreLine, DraftItem draft) {
        String lower = loreLine.toLowerCase();
        if (lower.contains("rarity:")) {
            String[] parts = loreLine.split(":", 2);
            if (parts.length > 1) {
                String rarity = parts[1].replaceAll("[^a-zA-Z]", "").trim();
                if (!rarity.isEmpty()) {
                    draft.rarityHuman = rarity;
                    draft.rarityHtml = String.format("<span>%s</span>", rarity);
                }
            }
        }
    }

    /**
     * Infers an initial primary tag from the Minecraft item identifier.
     */
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

    /**
     * Converts raw lore text and item metadata into structured HTML spans for website cards.
     * Applies standard theme colors to blossom symbols, rarity tiers, and crate badges.
     *
     * @param itemName Clean item title.
     * @param rarity   Item rarity string used for title text coloring.
     * @param loreText Multi-line lore description.
     * @return Fully formatted HTML string suitable for web rendering.
     */
    private static String convertHumanToHtml(String itemName, String rarity, String loreText) {
        StringBuilder html = new StringBuilder();

        // 1. Prepend styled item title line: ❀ Name ❀
        if (itemName != null && !itemName.isBlank()) {
            // Strip Minecraft formatting codes (§a, &b, etc.) and boundary blossom symbols safely
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
            // Regular lore lines (enchantments, unmodifiable flags, quotes): white
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