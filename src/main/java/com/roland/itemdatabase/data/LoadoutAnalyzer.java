package com.roland.itemdatabase.data;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared stat, attribute, passive trait, and armor-set completion analyzer for BlossomCraft gear.
 * <p>
 * Used by {@code FittingRoomScreen} and {@code ComparisonScreen} to aggregate armor, toughness,
 * attack damage, attack speed, bonus health, potion traits, and full-set bonuses across
 * equipped or previewed loadouts.
 */
public class LoadoutAnalyzer {

    private static final Pattern EFFECT_PATTERN = Pattern.compile(
            "(?i)\\b(Hero of the Village|Dolphins Grace|Fire Resistance|Water Breathing|Jump Boost|Night Vision|Conduit Power|Speed|Strength|Resistance|Haste|Regeneration|Absorption|Invisibility|Health Boost|Saturation)(?:\\s+([IVXLCDM]+))?"
    );
    private static final Pattern INT_STAT_PATTERN = Pattern.compile("\\+(\\d+)");
    private static final Pattern DOUBLE_STAT_PATTERN = Pattern.compile("\\+?(\\d+(\\.\\d+)?)");

    private static final Map<String, Integer> ROMAN_TO_INT = Map.of(
            "I", 1, "II", 2, "III", 3, "IV", 4, "V", 5,
            "VI", 6, "VII", 7, "VIII", 8, "IX", 9, "X", 10
    );
    private static final Map<Integer, String> INT_TO_ROMAN = Map.of(
            1, "I", 2, "II", 3, "III", 4, "IV", 5, "V",
            6, "VI", 7, "VII", 8, "VIII", 9, "IX", 10, "X"
    );

    /**
     * Represents a single equipped or passive item input to be evaluated by {@link #analyze(List)}.
     *
     * @param stack       Resolved Minecraft {@link ItemStack}.
     * @param entry       Optional database {@link ItemEntry} (used for authoritative RawData lore).
     * @param slot        Target {@link EquipmentSlot}, or {@code null} for passive inventory charms.
     * @param isPassive   True if evaluated as a passive inventory/hotbar charm rather than held/worn gear.
     * @param sourceLabel Optional display label identifying the item or slot providing traits.
     */
    public record SlotInput(
            ItemStack stack,
            ItemEntry entry,
            EquipmentSlot slot,
            boolean isPassive,
            String sourceLabel
    ) {}

    /**
     * Represents a parsed passive potion effect or special gear trait.
     */
    public record TraitInfo(
            String effectName,
            int level,
            String rawLevel,
            String requirement,
            String customAction,
            String sourceLabel
    ) {
        /** Formats this trait into a colored single-line display string. */
        public String format(boolean includeSource) {
            String srcSuffix = (includeSource && sourceLabel != null && !sourceLabel.isEmpty())
                    ? " §7(" + sourceLabel + ")"
                    : "";
            if (customAction != null) {
                return customAction + (requirement.isEmpty() ? "" : " §8[" + requirement + "]") + srcSuffix;
            }
            String lvlStr = rawLevel.isEmpty() ? "" : " " + rawLevel;
            return effectName + lvlStr + (requirement.isEmpty() ? "" : " §8[" + requirement + "]") + srcSuffix;
        }
    }

    /**
     * Aggregated combat, defense, and passive trait totals for an analyzed loadout.
     */
    public record LoadoutSummary(
            int totalArmor,
            float totalToughness,
            double totalAttackDamage,
            int totalAttackSpeedBonus,
            int totalBonusHealth,
            List<TraitInfo> traits
    ) {
        /** Converts consolidated traits into formatted chat/GUI {@link Component} lines. */
        public List<Component> toTraitComponents(boolean includeSource) {
            List<Component> list = new ArrayList<>();
            for (TraitInfo trait : traits) {
                list.add(Component.literal("§6• §f" + trait.format(includeSource)));
            }
            if (list.isEmpty()) {
                list.add(Component.literal("§8• None"));
            }
            return list;
        }
    }

    /**
     * Checks whether the four armor slots form a complete registered BlossomCraft armor set,
     * matching by either direct database ID or normalized clean item name.
     *
     * @return Matching {@link AdminApiClient.SetRow}, or {@code null} if no complete set is worn.
     */
    public static AdminApiClient.SetRow findCompletedSet(Map<EquipmentSlot, ItemEntry> equippedArmor, Map<EquipmentSlot, ItemStack> equippedStacks) {
        Set<String> equippedNames = new HashSet<>();
        Set<Integer> equippedIds = new HashSet<>();

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemEntry entry = equippedArmor != null ? equippedArmor.get(slot) : null;
            if (entry != null) {
                try {
                    equippedIds.add(Integer.parseInt(entry.id().trim()));
                } catch (NumberFormatException ignored) {}
                String clean = entry.getCleanName().trim().toLowerCase();
                if (!clean.isEmpty()) equippedNames.add(clean);
            } else if (equippedStacks != null) {
                ItemStack stack = equippedStacks.get(slot);
                if (stack != null && !stack.isEmpty()) {
                    String clean = StringUtil.stripColor(stack.getHoverName().getString())
                            .replaceAll("[^a-zA-Z0-9\\s'\\-]", "")
                            .trim()
                            .toLowerCase();
                    if (!clean.isEmpty()) equippedNames.add(clean);
                }
            }
        }

        if (equippedNames.isEmpty() && equippedIds.isEmpty()) return null;

        for (AdminApiClient.SetRow set : DatabaseManager.ARMOR_SETS) {
            if (set.itemIds().isEmpty()) continue;
            int matched = 0;
            for (int reqId : set.itemIds()) {
                if (equippedIds.contains(reqId)) {
                    matched++;
                    continue;
                }
                ItemEntry reqEntry = DatabaseManager.findItemById(reqId);
                if (reqEntry != null && equippedNames.contains(reqEntry.getCleanName().trim().toLowerCase())) {
                    matched++;
                }
            }
            if (matched >= set.itemIds().size()) {
                return set;
            }
        }
        return null;
    }

    /**
     * Parses custom BlossomCraft lore stats, vanilla attribute modifiers, and passive potion
     * effects across all provided {@link SlotInput} entries.
     */
    public static LoadoutSummary analyze(List<SlotInput> inputs) {
        int totalArmor = 0;
        float totalToughness = 0.0f;
        double totalAttackDamage = 1.0;
        int totalAttackSpeedBonus = 0;
        int totalBonusHealth = 0;
        Map<String, TraitInfo> consolidatedTraits = new LinkedHashMap<>();

        for (SlotInput input : inputs) {
            ItemStack stack = input.stack();
            if (stack == null || stack.isEmpty()) continue;

            ItemEntry entry = input.entry();
            EquipmentSlot slot = input.slot();
            boolean isPassive = input.isPassive();
            String sourceLabel = input.sourceLabel() != null ? input.sourceLabel() : "";

            List<Component> rawLines = new ArrayList<>();
            if (entry != null && entry.rawData() != null && !entry.rawData().isBlank()) {
                rawLines.addAll(RawDataParser.parseLore(entry.rawData()));
            } else {
                ItemLore lore = stack.get(DataComponents.LORE);
                if (lore != null) {
                    rawLines.addAll(lore.lines());
                }
            }

            // Group multi-line '>' lore blocks into single logical sentences for accurate parsing
            List<String> combinedSections = new ArrayList<>();
            StringBuilder currentBlock = null;

            for (Component comp : rawLines) {
                String rawStr = RawDataParser.sanitizeLegacyAmpersands(comp.getString());
                String clean = StringUtil.stripColor(rawStr).trim();

                if (clean.isBlank() || clean.equals("\"\"") || clean.startsWith("===") || clean.startsWith("---")) {
                    if (currentBlock != null) {
                        combinedSections.add(currentBlock.toString());
                        currentBlock = null;
                    }
                    continue;
                }

                if (clean.startsWith("Rarity:") || clean.startsWith("Season") || clean.startsWith("Win Chance:")
                        || clean.startsWith("Unmodifiable") || clean.startsWith("▶") || clean.startsWith("◀")) {
                    if (currentBlock != null) {
                        combinedSections.add(currentBlock.toString());
                        currentBlock = null;
                    }
                    continue;
                }

                if (clean.startsWith(">")) {
                    if (currentBlock != null) {
                        combinedSections.add(currentBlock.toString());
                    }
                    currentBlock = new StringBuilder(clean.substring(1).trim());
                } else if (currentBlock != null) {
                    currentBlock.append(" ").append(clean);
                } else {
                    combinedSections.add(clean);
                }
            }
            if (currentBlock != null) {
                combinedSections.add(currentBlock.toString());
            }

            boolean parsedFromLore = false;

            for (String rawSection : combinedSections) {
                String clean = rawSection.replaceAll("[\"']+$", "").trim();
                String lower = clean.toLowerCase();

                // Skip active click abilities or AoE ally buffs that are not passive self-stats
                if (lower.contains("everyone around")
                        || lower.contains("all players around")
                        || lower.contains("give everyone")
                        || lower.contains("heal everyone")
                        || lower.contains("around you")
                        || lower.startsWith("right-click to")
                        || lower.startsWith("sneak-right-click to")
                        || lower.startsWith("left-click to")
                        || lower.startsWith("sneak-left-click to")
                        || lower.startsWith("shift-right-click to")
                        || lower.startsWith("shift-left-click to")) {
                    continue;
                }

                if (lower.contains("armor:") || lower.matches(".*\\+\\d+\\s+armor.*")) {
                    totalArmor += extractInt(clean);
                    parsedFromLore = true;
                } else if (lower.contains("toughness:") || lower.matches(".*\\+\\d+\\s+toughness.*")) {
                    totalToughness += extractInt(clean);
                    parsedFromLore = true;
                } else if (lower.contains("attack speed:") || lower.contains("attack-speed:")) {
                    totalAttackSpeedBonus += extractInt(clean);
                    parsedFromLore = true;
                } else if (lower.contains("attack damage:") || (lower.contains("damage:") && !lower.contains("resistance")) || lower.matches(".*\\+\\d+(\\.\\d+)?\\s+damage.*")) {
                    if (slot == EquipmentSlot.MAINHAND || isPassive) {
                        totalAttackDamage += extractDouble(clean);
                        parsedFromLore = true;
                    }
                } else if (lower.contains("health") || lower.contains("heart")) {
                    int healthVal = extractInt(clean);
                    if (healthVal > 0) {
                        totalBonusHealth += healthVal;
                        parsedFromLore = true;
                    }
                }

                String req = "";
                if (lower.contains("top-left") || lower.contains("top left")) {
                    req = "Top-Left Inv";
                } else if (lower.contains("hotbar")) {
                    req = "Hotbar";
                } else if (lower.contains("when held") || lower.contains("while held")) {
                    req = "Held";
                } else if (lower.contains("when worn") || lower.contains("while worn")) {
                    req = "Worn";
                } else if (lower.contains("inventory")) {
                    req = "Inventory";
                }

                Matcher effMatcher = EFFECT_PATTERN.matcher(clean);
                boolean foundEffect = false;
                while (effMatcher.find()) {
                    foundEffect = true;
                    String name = formatEffectName(effMatcher.group(1));
                    String roman = effMatcher.group(2) != null ? effMatcher.group(2).toUpperCase() : "";
                    int level = roman.isEmpty() ? 1 : ROMAN_TO_INT.getOrDefault(roman, 1);

                    addOrUpgradeTrait(consolidatedTraits, name, level, req, sourceLabel);
                }

                if (!foundEffect && (lower.contains("spawn egg") || lower.contains("dash") || lower.contains("projectile deflection") || lower.contains("second chance"))) {
                    String action = clean.replaceFirst("^[>•✦▶*\\-]+\\s*", "");
                    int whenIdx = action.toLowerCase().indexOf(" when ");
                    if (whenIdx != -1) action = action.substring(0, whenIdx).trim();
                    consolidatedTraits.putIfAbsent("custom:" + action.toLowerCase(), new TraitInfo(action, 1, "", req, action, sourceLabel));
                }
            }

            // Fallback to vanilla item attribute modifiers when no custom lore stats were parsed
            if (!parsedFromLore && slot != null) {
                ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
                if (modifiers != null && !modifiers.modifiers().isEmpty()) {
                    for (ItemAttributeModifiers.Entry modEntry : modifiers.modifiers()) {
                        if (modEntry.slot().test(slot)) {
                            var attr = modEntry.attribute();
                            double amount = modEntry.modifier().amount();

                            if (attr.is(Attributes.ARMOR)) {
                                totalArmor += (int) amount;
                            } else if (attr.is(Attributes.ARMOR_TOUGHNESS)) {
                                totalToughness += (float) amount;
                            } else if (attr.is(Attributes.ATTACK_DAMAGE)) {
                                totalAttackDamage += amount;
                            } else if (attr.is(Attributes.ATTACK_SPEED) && slot == EquipmentSlot.MAINHAND) {
                                totalAttackSpeedBonus += (int) Math.round((amount / 4.0) * 100.0);
                            } else if (attr.is(Attributes.MAX_HEALTH)) {
                                totalBonusHealth += (int) amount;
                            }
                        }
                    }
                }
            }

            // Special offhand utility items
            if (slot == EquipmentSlot.OFFHAND) {
                if (stack.is(Items.SHIELD)) {
                    consolidatedTraits.putIfAbsent("custom:shield_deflect", new TraitInfo("Projectile Deflection", 1, "", "Offhand", "Projectile Deflection", sourceLabel));
                } else if (stack.is(Items.TOTEM_OF_UNDYING)) {
                    totalBonusHealth += 4;
                    consolidatedTraits.putIfAbsent("custom:totem_second_chance", new TraitInfo("Second Chance (Totem)", 1, "", "Offhand", "§eSecond Chance (Totem)", sourceLabel));
                }
            }
        }

        return new LoadoutSummary(
                totalArmor,
                totalToughness,
                totalAttackDamage,
                totalAttackSpeedBonus,
                totalBonusHealth,
                new ArrayList<>(consolidatedTraits.values())
        );
    }

    private static void addOrUpgradeTrait(Map<String, TraitInfo> map, String effect, int level, String requirement, String sourceLabel) {
        TraitInfo existing = map.get(effect);
        if (existing == null || level > existing.level()) {
            boolean isUntiered = effect.equalsIgnoreCase("Night Vision")
                    || effect.equalsIgnoreCase("Fire Resistance")
                    || effect.equalsIgnoreCase("Water Breathing")
                    || effect.equalsIgnoreCase("Invisibility");

            String numeral = isUntiered && level == 1 ? "" : INT_TO_ROMAN.getOrDefault(level, "I");
            map.put(effect, new TraitInfo(effect, level, numeral, requirement, null, sourceLabel));
        }
    }

    private static int extractInt(String text) {
        Matcher m = INT_STAT_PATTERN.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static double extractDouble(String text) {
        Matcher m = DOUBLE_STAT_PATTERN.matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String formatEffectName(String raw) {
        String[] words = raw.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}