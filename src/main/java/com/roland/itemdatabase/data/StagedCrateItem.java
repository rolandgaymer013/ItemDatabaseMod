package com.roland.itemdatabase.data;

import net.minecraft.world.item.ItemStack;
import java.io.File;

public class StagedCrateItem {
    private final ItemStack stack;
    private final String displayName;
    private final String materialName;
    private int count;
    private final String rawNbtJson;
    private File iconFile;
    private boolean selected;

    public StagedCrateItem(ItemStack stack, String displayName, String materialName, int count, String rawNbtJson, File iconFile) {
        this.stack = stack;
        this.displayName = displayName;
        this.materialName = materialName;
        this.count = count;
        this.rawNbtJson = rawNbtJson;
        this.iconFile = iconFile;
        this.selected = true; // Selected by default
    }

    public ItemStack getStack() { return stack; }
    public String getDisplayName() { return displayName; }
    public String getMaterialName() { return materialName; }
    public int getCount() { return count; }
    public void addCount(int extra) { this.count += extra; }
    public String getRawNbtJson() { return rawNbtJson; }
    public File getIconFile() { return iconFile; }
    public void setIconFile(File file) { this.iconFile = file; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public void toggleSelected() { this.selected = !this.selected; }
}