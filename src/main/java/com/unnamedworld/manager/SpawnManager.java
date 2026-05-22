package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.google.gson.*;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.*;
import java.util.*;

public class SpawnManager {

    public static final SpawnManager INSTANCE = new SpawnManager();

    // Store ResourceLocations directly — avoids toString() in hot spawn event path
    private final Set<ResourceLocation> blocked = new HashSet<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(File configDir) {
        saveFile = new File(configDir, "samplemod112_nospawn.json");
        load();
    }

    // --- Public API ---

    public boolean isBlocked(String entityId) {
        return blocked.contains(new ResourceLocation(normalize(entityId)));
    }

    public Set<String> getBlocked() {
        Set<String> result = new LinkedHashSet<>();
        for (ResourceLocation rl : blocked) result.add(rl.toString());
        return Collections.unmodifiableSet(result);
    }

    public void setBlocked(String entityId, boolean block) {
        ResourceLocation rl = new ResourceLocation(normalize(entityId));
        if (block) blocked.add(rl);
        else       blocked.remove(rl);
        save();
    }

    // --- Events ---

    // Natural spawning — direct ResourceLocation comparison, no String allocation
    @SubscribeEvent
    public void onCheckSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (blocked.isEmpty()) return;
        ResourceLocation key = EntityList.getKey(event.getEntity());
        if (key != null && blocked.contains(key)) {
            event.setResult(Event.Result.DENY);
        }
    }

    // Special spawning (spawn eggs, /summon)
    @SubscribeEvent
    public void onSpecialSpawn(LivingSpawnEvent.SpecialSpawn event) {
        if (blocked.isEmpty()) return;
        ResourceLocation key = EntityList.getKey(event.getEntity());
        if (key != null && blocked.contains(key)) {
            event.setCanceled(true);
        }
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonArray arr = gson.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (JsonElement el : arr) blocked.add(new ResourceLocation(el.getAsString()));
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load nospawn list", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonArray arr = new JsonArray();
            for (ResourceLocation rl : blocked) arr.add(rl.toString());
            gson.toJson(arr, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save nospawn list", e);
        }
    }

    // --- Helpers ---

    public static String normalize(String id) {
        return id.contains(":") ? id.toLowerCase() : "minecraft:" + id.toLowerCase();
    }
}
