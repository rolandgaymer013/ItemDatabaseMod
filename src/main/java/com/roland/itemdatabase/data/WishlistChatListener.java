package com.roland.itemdatabase.data;

import com.roland.itemdatabase.gui.WishlistToast;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WishlistChatListener {

    // Matches "[HH:MM:SS] Player got <Item> from <Crate>!"
    // Pattern.DOTALL allows matching even if Minecraft wrapped the text across lines
    private static final Pattern CRATE_WIN_PATTERN = Pattern.compile(
            "(?:^|\\[\\d{2}:\\d{2}:\\d{2}\\]\\s*)([a-zA-Z0-9_]{3,16})\\s+got\\s+(.+?)\\s+from\\s+([a-zA-Z0-9_ ]+)(?:!|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );;

    // Strips Minecraft color codes (§a, &e) and crate decorative icons (💀, ✦, ❀, etc.)
    private static final Pattern CLEAN_PATTERN = Pattern.compile(
            "(?i)[&§][0-9a-fk-or]|[\uD83D\uDC80\u2726\u2727\u25C6\u25C7\u25BA\u25C4\u2605\u2606\u2744\u26A1\u2748\u273F\u22EE\u2022\u2550\u2500]+"
    );

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                checkMessage(message);
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            checkMessage(message);
        });
    }

    private static void checkMessage(Component component) {
        if (component == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // If the player has no wishlisted items at all, skip processing
        if (CollectionManager.getWishlistCount() == 0) return;

        // Normalize spaces and newlines so wrapped chat lines parse as a continuous string
        String rawText = component.getString().replace("\r", " ").replace("\n", " ").trim();
        String clean = CLEAN_PATTERN.matcher(rawText).replaceAll(" ").replaceAll("\\s+", " ").trim();

        Matcher matcher = CRATE_WIN_PATTERN.matcher(clean);
        if (matcher.find()) {
            String playerName = matcher.group(1).trim();
            String capturedItem = matcher.group(2).trim();
            String crateName = matcher.group(3).trim();

            // Ignore messages if you were the one who rolled the item
            if (playerName.equalsIgnoreCase(client.player.getName().getString())) {
                return;
            }

            ItemEntry matchedWishlistItem = findMatchingWishlistItem(capturedItem);
            if (matchedWishlistItem != null) {
                triggerWishlistAlert(playerName, matchedWishlistItem.itemName(), crateName);
            }
        }
    }

    /**
     * Matches the broadcast string against items currently on the wishlist.
     */
    private static ItemEntry findMatchingWishlistItem(String capturedName) {
        String test = normalize(capturedName);

        for (ItemEntry item : DatabaseManager.CRATE_ITEMS.values()) {
            if (CollectionManager.isWishlisted(item)) {
                if (normalize(item.itemName()).equals(test)) {
                    return item;
                }
            }
        }

        for (ItemEntry item : DatabaseManager.MISC_ITEMS.values()) {
            if (CollectionManager.isWishlisted(item)) {
                if (normalize(item.itemName()).equals(test)) {
                    return item;
                }
            }
        }

        return null;
    }

    private static void triggerWishlistAlert(String player, String item, String crate) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // 1. Audio chime
        client.execute(() -> {
            client.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.2F, 1.0F)
            );
        });

        // 2. Popup Toast
        client.getToastManager().addToast(new WishlistToast(player, item, crate));

        // 3. Clickable Chat Alert with [MSG] and [VIEW]
        MutableComponent prefix = Component.literal("\n§6★ §e§lWISHLIST ALERT §6★ §7| ");
        MutableComponent body = Component.literal("§b" + player + " §7just won §d" + item + " §7from §e" + crate + "§7! ");

        MutableComponent msgBtn = Component.literal("§a§l[MSG]§r")
                .setStyle(Component.empty().getStyle()
                        .withClickEvent(new ClickEvent.SuggestCommand("/msg " + player + " "))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§7Click to whisper §b" + player))));

        // Clean out icons/symbols for the /idb command search query
        String cleanSearchName = CLEAN_PATTERN.matcher(item).replaceAll("").trim();

        MutableComponent viewBtn = Component.literal(" §e§l[VIEW]§r\n")
                .setStyle(Component.empty().getStyle()
                        .withClickEvent(new ClickEvent.RunCommand("/idb \"" + cleanSearchName + "\""))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§7Click to inspect §d" + cleanSearchName + " §7in database"))));

        client.player.sendSystemMessage(prefix.append(body).append(msgBtn).append(viewBtn));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return CLEAN_PATTERN.matcher(s).replaceAll("")
                .replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase()
                .trim();
    }
}