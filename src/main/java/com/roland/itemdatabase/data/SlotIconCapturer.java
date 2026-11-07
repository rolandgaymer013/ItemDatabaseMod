package com.roland.itemdatabase.data;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Base64;

public class SlotIconCapturer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase/SlotIconCapturer");

    /**
     * Reads pixels from an active on-screen container slot, scales the icon to the target dimension,
     * and returns the resulting PNG image encoded as a Base64 string.
     */
    public static String captureSlotIconBase64(int slotScreenX, int slotScreenY, int targetSize) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) return null;

        double guiScale = mc.getWindow().getGuiScale();
        int frameHeight = target.height;

        int glX = (int) Math.round(slotScreenX * guiScale);
        int glSlotW = (int) Math.round(16 * guiScale);
        int glSlotH = (int) Math.round(16 * guiScale);
        int glY = (int) Math.round(frameHeight - ((slotScreenY + 16) * guiScale));

        if (glSlotW <= 0 || glSlotH <= 0) return null;

        try {
            GL11.glReadBuffer(GL11.GL_BACK);
            ByteBuffer buffer = BufferUtils.createByteBuffer(glSlotW * glSlotH * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(glX, glY, glSlotW, glSlotH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            BufferedImage slotImage = new BufferedImage(glSlotW, glSlotH, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < glSlotH; y++) {
                for (int x = 0; x < glSlotW; x++) {
                    int flippedY = glSlotH - 1 - y;
                    int i = (flippedY * glSlotW + x) * 4;

                    int r = buffer.get(i) & 0xFF;
                    int g = buffer.get(i + 1) & 0xFF;
                    int b = buffer.get(i + 2) & 0xFF;
                    int a = buffer.get(i + 3) & 0xFF;

                    slotImage.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(slotImage, 0, 0, targetSize, targetSize, null);
            g2d.dispose();

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", pngOut);
            return Base64.getEncoder().encodeToString(pngOut.toByteArray());

        } catch (Exception e) {
            LOGGER.error("Failed to capture slot icon base64 at [{}, {}]", slotScreenX, slotScreenY, e);
            return null;
        }
    }

    /**
     * Reads pixels from an active on-screen container slot, scales to targetSize,
     * and writes directly to a PNG file on disk for staging and uploading.
     */
    public static File captureSlotIconToFile(int slotScreenX, int slotScreenY, int targetSize, String filePrefix) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) return null;

        double guiScale = mc.getWindow().getGuiScale();
        int frameHeight = target.height;

        int glX = (int) Math.round(slotScreenX * guiScale);
        int glSlotW = (int) Math.round(16 * guiScale);
        int glSlotH = (int) Math.round(16 * guiScale);
        int glY = (int) Math.round(frameHeight - ((slotScreenY + 16) * guiScale));

        if (glSlotW <= 0 || glSlotH <= 0) return null;

        try {
            GL11.glReadBuffer(GL11.GL_BACK);
            ByteBuffer buffer = BufferUtils.createByteBuffer(glSlotW * glSlotH * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(glX, glY, glSlotW, glSlotH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            BufferedImage slotImage = new BufferedImage(glSlotW, glSlotH, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < glSlotH; y++) {
                for (int x = 0; x < glSlotW; x++) {
                    int flippedY = glSlotH - 1 - y;
                    int i = (flippedY * glSlotW + x) * 4;

                    int r = buffer.get(i) & 0xFF;
                    int g = buffer.get(i + 1) & 0xFF;
                    int b = buffer.get(i + 2) & 0xFF;
                    int a = buffer.get(i + 3) & 0xFF;

                    slotImage.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(slotImage, 0, 0, targetSize, targetSize, null);
            g2d.dispose();

            File tempDir = new File(mc.gameDirectory, "itemdatabase_icons");
            if (!tempDir.exists()) tempDir.mkdirs();

            String sanitizedPrefix = filePrefix.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (sanitizedPrefix.length() > 30) sanitizedPrefix = sanitizedPrefix.substring(0, 30);

            File tempFile = File.createTempFile("icon_" + sanitizedPrefix + "_", ".png", tempDir);
            ImageIO.write(scaled, "png", tempFile);

            LOGGER.info("Captured slot icon to file: {} ({} bytes)", tempFile.getAbsolutePath(), tempFile.length());
            return tempFile;
        } catch (Exception e) {
            LOGGER.error("Failed to capture slot icon to file at [{}, {}]", slotScreenX, slotScreenY, e);
            return null;
        }
    }
}