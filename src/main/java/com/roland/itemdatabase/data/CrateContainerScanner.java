package com.roland.itemdatabase.data;

import com.google.gson.JsonObject;
import com.roland.itemdatabase.ItemDatabase;
import com.roland.itemdatabase.gui.AdminCrateReviewScreen;
import com.roland.itemdatabase.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CrateContainerScanner {

    @SuppressWarnings("MixinClassReference")
    public static void scanAndOpenReview(AbstractContainerScreen<?> screen, CrateEntry defaultCrate) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int totalSlots = screen.getMenu().slots.size();
        int containerSlots = Math.max(0, totalSlots - 36);
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;

        Map<String, StagedCrateItem> stagedMap = new LinkedHashMap<>();
        List<ItemStack> rawStacks = new ArrayList<>();

        for (int i = 0; i < containerSlots; i++) {
            Slot slot = screen.getMenu().getSlot(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            rawStacks.add(stack);

            String cleanName = stack.getHoverName().getString().trim();
            if (cleanName.isEmpty()) {
                cleanName = stack.getItem().getName(stack).getString().trim();
            }
            if (cleanName.isEmpty()) {
                cleanName = stack.getItem().toString();
            }
            String materialName = stack.getItem().toString().toLowerCase();

            // Extract unique distinguishing identity: custom model data or card number lore
            String cardOrLoreTag = "";
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (Component l : lore.lines()) {
                    String lineStr = l.getString().trim();
                    if (lineStr.matches("(?i).*Number:\\s*\\d+/\\d+.*")) {
                        cardOrLoreTag = "::" + lineStr;
                        break;
                    }
                }
            }

            var customModel = stack.get(DataComponents.CUSTOM_MODEL_DATA);
            String modelTag = (customModel != null) ? "::cmd_" + customModel.toString() : "";

            // Dedup key distinguishes distinct cards even if display name is identical
            String dedupKey = cleanName + "::" + materialName + cardOrLoreTag + modelTag;

            if (stagedMap.containsKey(dedupKey)) {
                stagedMap.get(dedupKey).addCount(stack.getCount());
            } else {
                int slotScreenX = accessor.getLeftPos() + slot.x;
                int slotScreenY = accessor.getTopPos() + slot.y;

                String fileSuffix = cardOrLoreTag.replaceAll("[^a-zA-Z0-9]", "_") + modelTag;
                File iconFile = SlotIconCapturer.captureSlotIconToFile(slotScreenX, slotScreenY, 64, "icon_" + cleanName.replaceAll("[^a-zA-Z0-9]", "") + fileSuffix);
                AdminPayloadBuilder.DraftItem itemDraft = AdminPayloadBuilder.buildDraftFromHeldItem(stack, defaultCrate != null ? defaultCrate.id() : 1);
                String rawNbt = (itemDraft != null && itemDraft.rawData != null) ? itemDraft.rawData : "";

                // Append card number to display name if present so both versions are clearly identifiable in review
                String reviewDisplayName = cleanName;
                if (!cardOrLoreTag.isEmpty()) {
                    reviewDisplayName = cleanName + " [" + cardOrLoreTag.replace("::", "") + "]";
                }

                StagedCrateItem staged = new StagedCrateItem(
                        stack.copy(),
                        reviewDisplayName,
                        materialName,
                        stack.getCount(),
                        rawNbt,
                        iconFile
                );
                stagedMap.put(dedupKey, staged);
            }
        }

        if (stagedMap.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("§c[ItemDatabase] No items found in the container to scan."));
            return;
        }

        List<StagedCrateItem> stagedList = new ArrayList<>(stagedMap.values());
        DetectedContainerMeta meta = detectContainerMetadata(rawStacks);

        mc.setScreen(new AdminCrateReviewScreen(
                defaultCrate,
                stagedList,
                screen,
                meta,
                (isCrate, targetId, newName, newTag, newType, confirmedItems) ->
                        executeStagedUpload(isCrate, targetId, newName, newTag, newType, confirmedItems)
        ));
    }

    private static void executeStagedUpload(boolean isCrate, int targetId, String newName, String newTag, String newType, List<StagedCrateItem> itemsToUpload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String destLabel = (!newName.isEmpty()) ? newName : (isCrate ? "Crate #" + targetId : "Group #" + targetId);
        mc.player.sendSystemMessage(Component.literal("§e[ItemDatabase] Uploading " + itemsToUpload.size() + " items to " + destLabel + "..."));
        mc.setScreen(null);

        new Thread(() -> {
            int finalTargetId = targetId;

            if (!newName.isEmpty()) {
                try {
                    String releaseDateFormatted = LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));

                    if (isCrate) {
                        var res = AdminApiClient.createCrate(newName, releaseDateFormatted, newTag, newType).get();
                        if (res.success() && res.itemId() > 0) {
                            finalTargetId = res.itemId();
                        } else {
                            String err = res.error() != null ? res.error() : "Unknown error";
                            mc.execute(() -> mc.player.sendSystemMessage(Component.literal("§c[ItemDatabase] Failed to create crate: " + err)));
                            return; // Stop upload! Do not proceed with item upload on failure!
                        }
                    } else {
                        var res = AdminApiClient.createMiscGroup(newName, releaseDateFormatted, newTag, newType).get();
                        if (res.success() && res.itemId() > 0) {
                            finalTargetId = res.itemId();
                        } else {
                            String err = res.error() != null ? res.error() : "Unknown error";
                            mc.execute(() -> mc.player.sendSystemMessage(Component.literal("§c[ItemDatabase] Failed to create group: " + err)));
                            return; // Stop upload!
                        }
                    }
                } catch (Exception e) {
                    ItemDatabase.LOGGER.error("Failed creating new container category: " + newName, e);
                    return;
                }
            }

            int successCount = 0;
            for (StagedCrateItem item : itemsToUpload) {
                AdminPayloadBuilder.DraftItem draft = new AdminPayloadBuilder.DraftItem();
                draft.isCrateItem = isCrate;
                if (isCrate) {
                    draft.crateId = finalTargetId;
                    draft.rarityHuman = "Common";
                    draft.rarityHtml = "<span>Common</span>";
                    draft.winPercentage = "0%";
                    draft.tagPrimary = "Misc";
                    draft.tagSecondary = "None";
                    draft.tagTertiary = "None";
                    draft.tagQuaternary = "None";
                    draft.tagQuinary = "None";
                    draft.tagSenary = "None";
                    draft.tagSeptenary = "None";
                } else {
                    draft.groupId = finalTargetId;
                    draft.rarityHuman = "";
                    draft.rarityHtml = "";
                }

                draft.itemName = item.getDisplayName();
                draft.notes = "";
                draft.rawData = item.getRawNbtJson();
                draft.pendingImageFile = item.getIconFile();

                File f = item.getIconFile();
                ItemDatabase.LOGGER.info("[Scanner] Item: '{}', iconFile: {}, exists: {}, length: {}",
                        item.getDisplayName(),
                        f != null ? f.getAbsolutePath() : "null",
                        f != null && f.exists(),
                        f != null && f.exists() ? f.length() : 0);

                try {
                    JsonObject payload = AdminPayloadBuilder.serializeToApiJson(draft);
                    var createResult = AdminApiClient.createItem(payload).get();
                    if (createResult.success()) {
                        int newItemId = createResult.itemId();
                        if (f != null && f.exists() && f.length() > 0) {
                            ItemDatabase.LOGGER.info("[Scanner] Sending icon upload for item #{}", newItemId);
                            var iconRes = AdminApiClient.uploadItemIcon(newItemId, f, !isCrate).get();
                            if (!iconRes.success()) {
                                ItemDatabase.LOGGER.warn("[Scanner] Icon upload failed for item #{}: {}", newItemId, iconRes.error());
                            } else {
                                ItemDatabase.LOGGER.info("[Scanner] Icon uploaded successfully for item #{}", newItemId);
                            }
                        } else {
                            ItemDatabase.LOGGER.warn("[Scanner] Skipping icon upload for item #{}: file missing or empty", newItemId);
                        }
                        successCount++;
                    } else {
                        ItemDatabase.LOGGER.error("[Scanner] Failed creating item '{}': {}", item.getDisplayName(), createResult.error());
                    }
                } catch (Exception e) {
                    ItemDatabase.LOGGER.error("[Scanner] Failed uploading staged item: " + item.getDisplayName(), e);
                }
            }

            final int finalSuccess = successCount;
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("§a[ItemDatabase] Upload complete: " + finalSuccess + "/" + itemsToUpload.size() + " items saved to " + destLabel));
                }
                // Refresh caches completely
                DatabaseManager.fetchCrates()
                        .thenCompose(v -> DatabaseManager.fetchCrateItems())
                        .thenCompose(v -> DatabaseManager.fetchMiscGroups())
                        .thenCompose(v -> DatabaseManager.fetchMiscItems())
                        .thenRun(() -> {
                            DatabaseManager.mapItemsToCrates();
                            DatabaseManager.mapItemsToMiscGroups();
                            mc.execute(() -> {
                                if (mc.player != null) {
                                    mc.player.sendSystemMessage(Component.literal("§7[ItemDatabase] Local database records synchronized."));
                                }
                            });
                        });
            });
        }, "CrateBatchUploadThread").start();
    }

    public record DetectedContainerMeta(boolean isCrate, String suggestedName, String suggestedTag, String suggestedType) {}

    public static DetectedContainerMeta detectContainerMetadata(List<ItemStack> stacks) {
        String detectedCrateSeason = null;
        String detectedGroupName = null;

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;

            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;

            for (Component line : lore.lines()) {
                String plain = line.getString().replaceAll("(?i)[&§][0-9a-fk-or]", "").trim();

                // 1. Check for "Season XX: <NAME>"
                if (plain.matches("(?i).*Season\\s+\\d+:\\s*.*")) {
                    int idx = plain.toLowerCase().indexOf("season");
                    detectedCrateSeason = cleanDisplayName(plain.substring(idx).trim());
                    break;
                }

                // 2. Check for trading cards or defined sub-sets (e.g. "Nascent Nature")
                if (plain.equalsIgnoreCase("Nascent Nature") || plain.contains("Series") || plain.contains("Set")) {
                    if (detectedGroupName == null) {
                        detectedGroupName = cleanDisplayName(plain);
                    }
                }
            }
            if (detectedCrateSeason != null) break;
        }

        // Scenario 1: Main Seasonal Crate detected
        if (detectedCrateSeason != null) {
            String slug = detectedCrateSeason.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            return new DetectedContainerMeta(true, detectedCrateSeason, slug, "Seasonal");
        }

        // Scenario 2: Sub-group detected under an active season
        if (detectedGroupName != null) {
            String combinedName = "Emberfall - " + detectedGroupName;
            String slug = "emberfall-" + detectedGroupName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            return new DetectedContainerMeta(false, combinedName, slug, "Furniture");
        }

        // Scenario 3: Container without specific lore lines
        return new DetectedContainerMeta(false, "Emberfall - ", "emberfall-", "Unsorted");
    }

    private static String cleanDisplayName(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?i)[&§][0-9a-fk-or]", "") // remove minecraft color codes
                .replaceAll("[^\\x20-\\x7E]", "")       // remove unprintable / non-ascii symbols
                .replaceAll("\\s+", " ")                // normalize spaces
                .trim();
    }
}