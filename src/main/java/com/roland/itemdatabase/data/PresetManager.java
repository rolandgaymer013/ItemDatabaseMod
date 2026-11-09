package com.roland.itemdatabase.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PresetManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File PRESET_DIR = new File(Minecraft.getInstance().gameDirectory, "config/itemdatabase/presets");

    public static void ensureDirectory() {
        if (!PRESET_DIR.exists()) {
            PRESET_DIR.mkdirs();
        }
    }

    public static List<String> listPresets() {
        ensureDirectory();
        List<String> names = new ArrayList<>();
        File[] files = PRESET_DIR.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                names.add(file.getName().replace(".json", ""));
            }
        }
        return names;
    }

    public static boolean savePreset(String name, Map<EquipmentSlot, ItemStack> loadout) {
        ensureDirectory();
        File file = new File(PRESET_DIR, name + ".json");
        var registryAccess = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.registryAccess()
                : null;

        if (registryAccess == null) return false;

        JsonObject root = new JsonObject();
        var ops = registryAccess.createSerializationContext(JsonOps.INSTANCE);

        for (Map.Entry<EquipmentSlot, ItemStack> entry : loadout.entrySet()) {
            ItemStack stack = entry.getValue();
            if (stack != null && !stack.isEmpty()) {
                ItemStack.CODEC.encodeStart(ops, stack)
                        .result()
                        .ifPresent(jsonElem -> root.add(entry.getKey().getName(), jsonElem));
            }
        }

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(root, writer);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static Map<EquipmentSlot, ItemStack> loadPreset(String name) {
        ensureDirectory();
        File file = new File(PRESET_DIR, name + ".json");
        Map<EquipmentSlot, ItemStack> result = new HashMap<>();

        if (!file.exists()) return result;

        var registryAccess = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.registryAccess()
                : null;

        if (registryAccess == null) return result;

        var ops = registryAccess.createSerializationContext(JsonOps.INSTANCE);

        try (FileReader reader = new FileReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (root.has(slot.getName())) {
                    ItemStack.CODEC.parse(ops, root.get(slot.getName()))
                            .result()
                            .ifPresent(stack -> result.put(slot, stack));
                } else {
                    result.put(slot, ItemStack.EMPTY);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    public static boolean deletePreset(String name) {
        File file = new File(PRESET_DIR, name + ".json");
        return file.exists() && file.delete();
    }
}