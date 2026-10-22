package com.roland.itemdatabase.data;

import net.minecraft.resources.Identifier;
import java.util.List;

public record AnimatedTexture(
        List<Identifier> frameIdentifiers,
        List<Integer> frameDelaysMs,
        int totalDurationMs,
        int width,
        int height
) {
    public Identifier getCurrentFrameIdentifier() {
        if (frameIdentifiers.isEmpty()) return null;
        if (frameIdentifiers.size() == 1 || totalDurationMs <= 0) {
            return frameIdentifiers.get(0);
        }

        long timeInLoop = System.currentTimeMillis() % totalDurationMs;
        long elapsed = 0;

        for (int i = 0; i < frameDelaysMs.size(); i++) {
            elapsed += frameDelaysMs.get(i);
            if (timeInLoop < elapsed) {
                return frameIdentifiers.get(i);
            }
        }

        return frameIdentifiers.get(0);
    }
}