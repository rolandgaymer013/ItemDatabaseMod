package com.roland.itemdatabase.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {

    // UPDATE THESE WITH YOUR EXACT GITHUB USERNAME/ORGANIZATION AND REPO NAME
    private static final String GITHUB_OWNER = "rolandgaymer013";
    private static final String GITHUB_REPO = "ItemDatabaseMod";

    private static final String API_URL = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases";

    private static boolean checkedThisSession = false;

    public static void checkForUpdates() {
        if (checkedThisSession) return;
        checkedThisSession = true;

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Get current installed mod version from fabric.mod.json
                String currentVersionStr = FabricLoader.getInstance()
                        .getModContainer("itemdatabase")
                        .map(ModContainer::getMetadata)
                        .map(m -> m.getVersion().getFriendlyString())
                        .orElse("1.5.0");

                // 2. Fetch latest release from GitHub API
                URL url = URI.create(API_URL).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "ItemDatabaseMod-UpdateChecker");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) {
                    return; // Fail silently if offline or rate limited
                }

                JsonObject json = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
                if (!json.has("tag_name")) return;

                String rawTag = json.get("tag_name").getAsString().trim();
                String latestVersionStr = rawTag.startsWith("v") ? rawTag.substring(1) : rawTag;
                String releaseUrl = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_PAGE;

                // 3. Compare semantic versions
                if (isNewerVersion(currentVersionStr, latestVersionStr)) {
                    // Slight delay to ensure the chat HUD is fully active and not flooded with server join motds
                    Thread.sleep(2500);

                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (mc.player != null) {
                            sendUpdateAlert(currentVersionStr, latestVersionStr, releaseUrl);
                        }
                    });
                }
            } catch (Exception ignored) {
                // Ignore connection errors to prevent interrupting gameplay
            }
        });
    }

    private static void sendUpdateAlert(String currentVer, String latestVer, String url) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Clean single prefix
        Component notice = Component.literal("[ITEMDATABASE] ")
                .withStyle(Style.EMPTY.withColor(0x3B82F6).withBold(true))
                .append(Component.literal("UPDATE AVAILABLE ").withStyle(Style.EMPTY.withColor(0xEF4444).withBold(true)))
                .append(Component.literal("(installed: " + currentVer + ") ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Latest: " + latestVer + " ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("[DOWNLOAD]")
                        .withStyle(Style.EMPTY
                                .withColor(0x22C55E)
                                .withBold(true)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to open the latest release download page!")))
                        ));

        mc.player.sendSystemMessage(notice);
    }

    private static boolean isNewerVersion(String currentVer, String latestVer) {
        String[] currParts = currentVer.split("[^0-9]+");
        String[] lateParts = latestVer.split("[^0-9]+");

        int length = Math.max(currParts.length, lateParts.length);
        for (int i = 0; i < length; i++) {
            int c = (i < currParts.length && !currParts[i].isEmpty()) ? Integer.parseInt(currParts[i]) : 0;
            int l = (i < lateParts.length && !lateParts[i].isEmpty()) ? Integer.parseInt(lateParts[i]) : 0;
            if (l > c) return true;
            if (c > l) return false;
        }
        return false;
    }
}
