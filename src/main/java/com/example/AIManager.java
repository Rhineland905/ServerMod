package com.example;

import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.*;
import java.util.*;

public class AIManager {

    public static final AIManager INSTANCE = new AIManager();

    private final Set<String> disabledMobs = new HashSet<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(FMLPreInitializationEvent event) {
        saveFile = new File(event.getModConfigurationDirectory(), "samplemod112_noai.json");
        load();
    }

    // --- Public API ---

    public boolean isMobAIDisabled(String mobId) {
        return disabledMobs.contains(normalize(mobId));
    }

    public Set<String> getDisabledMobs() {
        return Collections.unmodifiableSet(disabledMobs);
    }

    public void setMobAIDisabled(String mobId, boolean disabled, MinecraftServer server) {
        String id = normalize(mobId);
        if (disabled) disabledMobs.add(id);
        else          disabledMobs.remove(id);
        save();
        applyToWorld(server, id, disabled);
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonArray arr = gson.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (JsonElement el : arr) {
                disabledMobs.add(el.getAsString());
            }
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to load noai list", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonArray arr = new JsonArray();
            for (String id : disabledMobs) arr.add(id);
            gson.toJson(arr, w);
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to save noai list", e);
        }
    }

    // --- Helpers ---

    // "zombie" -> "minecraft:zombie", "minecraft:zombie" -> "minecraft:zombie"
    public static String normalize(String id) {
        return id.contains(":") ? id.toLowerCase() : "minecraft:" + id.toLowerCase();
    }

    private void applyToWorld(MinecraftServer server, String normalizedId, boolean noAI) {
        for (WorldServer world : server.worlds) {
            for (Entity entity : world.loadedEntityList) {
                if (!(entity instanceof EntityLiving)) continue;
                ResourceLocation key = EntityList.getKey(entity);
                if (key != null && normalizedId.equals(key.toString())) {
                    ((EntityLiving) entity).setNoAI(noAI);
                }
            }
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof EntityLiving)) return;
        ResourceLocation key = EntityList.getKey(entity);
        if (key != null && disabledMobs.contains(key.toString())) {
            ((EntityLiving) entity).setNoAI(true);
        }
    }
}
