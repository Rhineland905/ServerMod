package com.example.manager;

import com.example.SampleMod112;
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

    // Store ResourceLocations directly — avoids toString() in hot event path
    private final Set<ResourceLocation> disabledMobs = new HashSet<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(FMLPreInitializationEvent event) {
        saveFile = new File(event.getModConfigurationDirectory(), "samplemod112_noai.json");
        load();
    }

    // --- Public API ---

    public boolean isMobAIDisabled(String mobId) {
        return disabledMobs.contains(new ResourceLocation(normalize(mobId)));
    }

    public Set<String> getDisabledMobs() {
        Set<String> result = new HashSet<>();
        for (ResourceLocation rl : disabledMobs) result.add(rl.toString());
        return Collections.unmodifiableSet(result);
    }

    public void setMobAIDisabled(String mobId, boolean disabled, MinecraftServer server) {
        ResourceLocation rl = new ResourceLocation(normalize(mobId));
        if (disabled) disabledMobs.add(rl);
        else          disabledMobs.remove(rl);
        save();
        applyToWorld(server, rl, disabled);
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonArray arr = gson.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (JsonElement el : arr) disabledMobs.add(new ResourceLocation(el.getAsString()));
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to load noai list", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonArray arr = new JsonArray();
            for (ResourceLocation rl : disabledMobs) arr.add(rl.toString());
            gson.toJson(arr, w);
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to save noai list", e);
        }
    }

    // --- Helpers ---

    public static String normalize(String id) {
        return id.contains(":") ? id.toLowerCase() : "minecraft:" + id.toLowerCase();
    }

    private void applyToWorld(MinecraftServer server, ResourceLocation targetRL, boolean noAI) {
        for (WorldServer world : server.worlds) {
            for (Entity entity : world.loadedEntityList) {
                if (!(entity instanceof EntityLiving)) continue;
                // Direct ResourceLocation comparison — no string allocation
                if (targetRL.equals(EntityList.getKey(entity))) {
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
        // Direct ResourceLocation comparison — no toString(), no String allocation
        if (disabledMobs.contains(EntityList.getKey(entity))) {
            ((EntityLiving) entity).setNoAI(true);
        }
    }
}
