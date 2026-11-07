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
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Captures pixel-perfect item icon renders directly from the active OpenGL framebuffer.
 * Translates Minecraft GUI slot coordinates into native window viewport pixels,
 * handles OpenGL bottom-to-top pixel inversion, and exports clean Base64-encoded PNG strings.
 */
public class SlotIconCapturer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemDatabase/SlotIconCapturer");

    /**
     * Reads pixels from an active on-screen container slot, scales the icon to the target dimension,
     * and returns the resulting PNG image encoded as a Base64 string.
     *
     * @param slotScreenX Screen-space X coordinate of the container slot.
     * @param slotScreenY Screen-space Y coordinate of the container slot.
     * @param targetSize  Final width and height (in pixels) of the exported icon.
     * @return Base64-encoded PNG image string, or null if capture fails.
     */
    public static String captureSlotIconBase64(int slotScreenX, int slotScreenY, int targetSize) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) return null;

        double guiScale = mc.getWindow().getGuiScale();
        int frameHeight = mc.getWindow().getHeight();

        // Convert virtual GUI coordinates into actual framebuffer pixel dimensions
        int glX = (int) Math.round(slotScreenX * guiScale);
        int glSlotW = (int) Math.round(16 * guiScale);
        int glSlotH = (int) Math.round(16 * guiScale);

        // OpenGL coordinates have an inverted Y-axis (origin is at bottom-left)
        int glY = (int) Math.round(frameHeight - ((slotScreenY + 16) * guiScale));

        if (glSlotW <= 0 || glSlotH <= 0) return null;

        try {
            // Bind and read directly from the back display buffer
            GL11.glReadBuffer(GL11.GL_BACK);

            ByteBuffer buffer = BufferUtils.createByteBuffer(glSlotW * glSlotH * 4);
            GL11.glReadPixels(glX, glY, glSlotW, glSlotH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            BufferedImage slotImage = new BufferedImage(glSlotW, glSlotH, BufferedImage.TYPE_INT_ARGB);

            // Reorder inverted OpenGL scanlines into standard top-down image coordinates
            for (int y = 0; y < glSlotH; y++) {
                for (int x = 0; x < glSlotW; x++) {
                    int flippedY = glSlotH - 1 - y;
                    int i = (flippedY * glSlotW + x) * 4;

                    int r = buffer.get(i) & 0xFF;
                    int g = buffer.get(i + 1) & 0xFF;
                    int b = buffer.get(i + 2) & 0xFF;
                    int a = buffer.get(i + 3) & 0xFF;

                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    slotImage.setRGB(x, y, argb);
                }
            }

            // Rescale to target dimension using nearest-neighbor interpolation to keep pixel art crisp
            BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(slotImage, 0, 0, targetSize, targetSize, null);
            g2d.dispose();

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", pngOut);
            return Base64.getEncoder().encodeToString(pngOut.toByteArray());

        } catch (Exception e) {
            LOGGER.error("Failed to capture slot icon at [{}, {}]", slotScreenX, slotScreenY, e);
            return null;
        }
    }
}