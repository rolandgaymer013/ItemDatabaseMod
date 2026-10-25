package com.roland.itemdatabase.data;

import net.minecraft.resources.Identifier;
import java.util.List;

/**
 * Represents an animated texture containing multiple frames, delay metadata,
 * and dimensional bounds for rendering inside the Item Database UI.
 *
 * @param frameIdentifiers Registered Minecraft texture identifiers for each animation frame.
 * @param frameDelaysMs    Display duration in milliseconds for each respective frame.
 * @param totalDurationMs  Total cycle duration across all frames in milliseconds.
 * @param width            Natural pixel width of the texture frames.
 * @param height           Natural pixel height of the texture frames.
 */
public record AnimatedTexture(
        List<Identifier> frameIdentifiers,
        List<Integer> frameDelaysMs,
        int totalDurationMs,
        int width,
        int height
) {
    public AnimatedTexture {
        // Guarantee immutability and prevent null lists
        frameIdentifiers = (frameIdentifiers != null) ? List.copyOf(frameIdentifiers) : List.of();
        frameDelaysMs = (frameDelaysMs != null) ? List.copyOf(frameDelaysMs) : List.of();
    }

    /**
     * Resolves the active frame identifier based on the current system clock time.
     *
     * @return The Identifier for the active frame, or null if no frames exist.
     */
    public Identifier getCurrentFrameIdentifier() {
        if (frameIdentifiers.isEmpty()) {
            return null;
        }

        // Return the first frame directly for static images or invalid cycle durations
        if (frameIdentifiers.size() == 1 || totalDurationMs <= 0 || frameDelaysMs.isEmpty()) {
            return frameIdentifiers.get(0);
        }

        long timeInLoop = System.currentTimeMillis() % totalDurationMs;
        long elapsed = 0;

        int frameCount = Math.min(frameIdentifiers.size(), frameDelaysMs.size());
        for (int i = 0; i < frameCount; i++) {
            elapsed += frameDelaysMs.get(i);
            if (timeInLoop < elapsed) {
                return frameIdentifiers.get(i);
            }
        }

        return frameIdentifiers.get(0);
    }
}