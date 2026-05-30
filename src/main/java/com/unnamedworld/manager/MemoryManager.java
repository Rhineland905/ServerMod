package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.*;

public class MemoryManager {

    public static final MemoryManager INSTANCE = new MemoryManager();

    // --- Settings (persisted) ---
    public int itemsPerChunkLimit   = 32;
    public int xpOrbsPerChunkLimit  = 24;
    public int cleanupIntervalTicks = 600;

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Counters
    private int tickCounter      = 0;
    private int lastItemsRemoved = 0;
    private int lastXpRemoved    = 0;

    // Reused maps — allocated once, cleared each cleanup cycle instead of recreated
    private final Map<Long, List<EntityItem>>  itemMap = new HashMap<>();
    private final Map<Long, List<EntityXPOrb>> xpMap   = new HashMap<>();

    public void init(File configDir) {
        saveFile = new File(configDir, "memopt.json");
        load();
    }

    // --- Tick ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < cleanupIntervalTicks) return;
        tickCounter = 0;

        lastItemsRemoved = 0;
        lastXpRemoved    = 0;

        for (WorldServer world : FMLCommonHandler.instance()
                .getMinecraftServerInstance().worlds) {
            runCleanup(world);
        }

        if (lastItemsRemoved > 0 || lastXpRemoved > 0) {
            ServerMod.LOGGER.info("[MemOpt] Cleaned {} items, {} xp-orbs",
                    lastItemsRemoved, lastXpRemoved);
        }
    }

    // --- Cleanup ---

    public int[] runCleanupNow() {
        lastItemsRemoved = 0;
        lastXpRemoved    = 0;
        for (WorldServer world : FMLCommonHandler.instance()
                .getMinecraftServerInstance().worlds) {
            runCleanup(world);
        }
        return new int[]{ lastItemsRemoved, lastXpRemoved };
    }

    private void runCleanup(WorldServer world) {
        // Reuse maps instead of allocating new ones each cycle
        itemMap.clear();
        xpMap.clear();

        for (Entity entity : world.loadedEntityList) {
            long key = ChunkPos.asLong(entity.chunkCoordX, entity.chunkCoordZ);
            if (entity instanceof EntityItem) {
                itemMap.computeIfAbsent(key, k -> new ArrayList<>()).add((EntityItem) entity);
            } else if (entity instanceof EntityXPOrb) {
                xpMap.computeIfAbsent(key, k -> new ArrayList<>()).add((EntityXPOrb) entity);
            }
        }

        // Items: remove oldest (highest ticksExisted) when over limit
        for (List<EntityItem> list : itemMap.values()) {
            if (list.size() <= itemsPerChunkLimit) continue;
            list.sort((a, b) -> b.ticksExisted - a.ticksExisted);
            for (int i = itemsPerChunkLimit; i < list.size(); i++) {
                list.get(i).setDead();
                lastItemsRemoved++;
            }
        }

        // XP orbs: remove smallest-value ones when over limit
        for (List<EntityXPOrb> list : xpMap.values()) {
            if (list.size() <= xpOrbsPerChunkLimit) continue;
            list.sort(Comparator.comparingInt(e -> e.xpValue));
            for (int i = xpOrbsPerChunkLimit; i < list.size(); i++) {
                list.get(i).setDead();
                lastXpRemoved++;
            }
        }
    }

    // --- Stats ---

    public String getMemoryStats(WorldServer[] worlds) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long max  = rt.maxMemory() / 1024 / 1024;
        int pct   = (int) (used * 100 / max);

        int totalEntities = 0, totalItems = 0, totalXp = 0;
        for (WorldServer w : worlds) {
            for (Entity e : w.loadedEntityList) {
                totalEntities++;
                if (e instanceof EntityItem)  totalItems++;
                if (e instanceof EntityXPOrb) totalXp++;
            }
        }

        return String.format(
            "RAM: %d/%d MB (%d%%)  |  Entities: %d  |  Items: %d  |  XP orbs: %d",
            used, max, pct, totalEntities, totalItems, totalXp);
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) { save(); return; }
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            if (obj.has("itemsPerChunkLimit"))   itemsPerChunkLimit   = obj.get("itemsPerChunkLimit").getAsInt();
            if (obj.has("xpOrbsPerChunkLimit"))  xpOrbsPerChunkLimit  = obj.get("xpOrbsPerChunkLimit").getAsInt();
            if (obj.has("cleanupIntervalTicks")) cleanupIntervalTicks = obj.get("cleanupIntervalTicks").getAsInt();
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load memopt config", e);
        }
    }

    public void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("itemsPerChunkLimit",   itemsPerChunkLimit);
            obj.addProperty("xpOrbsPerChunkLimit",  xpOrbsPerChunkLimit);
            obj.addProperty("cleanupIntervalTicks", cleanupIntervalTicks);
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save memopt config", e);
        }
    }
}
