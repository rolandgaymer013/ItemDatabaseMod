package com.roland.itemdatabase.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class WishlistToast implements Toast {
    private static final Identifier BACKGROUND_SPRITE = Identifier.fromNamespaceAndPath("minecraft", "toast/advancement");

    private final String playerName;
    private final String itemName;
    private final String crateOrSource;

    public WishlistToast(String playerName, String itemName, String crateOrSource) {
        this.playerName = playerName;
        this.itemName = itemName;
        this.crateOrSource = crateOrSource;
    }

    @Override
    public Visibility getWantedVisibility() {
        return Visibility.SHOW;
    }

    @Override
    public void update(ToastManager manager, long time) {
    }

    @Override
    public int width() {
        return 160;
    }

    @Override
    public int height() {
        return 32;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long time) {
        // Draw the native advancement toast sprite frame
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), height());

        // Header
        graphics.text(font, Component.literal("§6★ §e§lWishlist Alert!"), 12, 6, 0xFFFFFFFF);

        // Body: Truncate cleanly if roller name + item name is long
        String body = "§f" + playerName + " §7rolled §b" + itemName;
        int maxW = width() - 20;
        if (font.width(body) > maxW) {
            body = font.substrByWidth(Component.literal(body), maxW - 6).getString() + "..";
        }
        graphics.text(font, Component.literal(body), 12, 18, 0xFFFFFFFF);
    }
}