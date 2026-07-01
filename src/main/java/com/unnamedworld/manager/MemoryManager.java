package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.*;

/**
 * Анти-клаттер по таймеру.
 *
 * Логика:
 *   1. Раз в {@link #scanIntervalTicks} тиков сканируем все миры, считаем в каждом
 *      чанке дропнутые предметы ({@link EntityItem}) и сферы опыта ({@link EntityXPOrb}).
 *   2. Если в чанке предметов > {@link #itemsPerChunkLimit} ИЛИ орбов > {@link #xpOrbsPerChunkLimit} —
 *      по этому чанку ЗАПУСКАЕТСЯ таймер ({@link #cleanupDelayTicks}). Игроки в чанке получают
 *      предупреждение, что мусор будет убран через N секунд.
 *   3. Если до срабатывания чанк «остыл» (дроп растащили/деспавнился, или чанк выгрузился) —
 *      таймер отменяется.
 *   4. Когда таймер срабатывает и чанк всё ещё переполнен — лишнее удаляется
 *      (предметы: убираем самые старые; орбы: убираем самую мелочь, оставляя ценные).
 *
 * Таким образом нюкается только реально захламлённый чанк, и только если игроки
 * проигнорировали предупреждение.
 */
public class MemoryManager {

    public static final MemoryManager INSTANCE = new MemoryManager();

    // --- Настройки (сохраняются) ---
    public int itemsPerChunkLimit  = 32;    // порог: при превышении запускается таймер
    public int xpOrbsPerChunkLimit = 24;    // порог по сферам опыта
    public int scanIntervalTicks   = 100;   // как часто проверяем чанки (20 тиков = 1 c)
    public int cleanupDelayTicks   = 1200;  // таймер до очистки помеченного чанка (60 c)

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Глобальный счётчик тиков (монотонный) — по нему считаем срок таймеров.
    private long ticks = 0;
    private int lastItemsRemoved = 0;
    private int lastXpRemoved    = 0;

    // Переиспользуемые карты — чистим, а не пересоздаём каждый скан.
    private final Map<Long, List<EntityItem>>  itemMap = new HashMap<>();
    private final Map<Long, List<EntityXPOrb>> xpMap   = new HashMap<>();

    // (измерение + чанк) -> абсолютный тик, когда чистить. Это и есть «таймеры».
    private final Map<ChunkKey, Long> pending = new HashMap<>();

    public void init(File configDir) {
        saveFile = new File(configDir, "memopt.json");
        load();
    }

    // --- Тик ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ticks++;
        if (scanIntervalTicks < 1) scanIntervalTicks = 1;
        if (ticks % scanIntervalTicks != 0) return;

        lastItemsRemoved = 0;
        lastXpRemoved    = 0;
        scan();
        if (lastItemsRemoved > 0 || lastXpRemoved > 0) {
            ServerMod.LOGGER.info("[MemOpt] Таймер очистил {} предметов, {} сфер опыта",
                    lastItemsRemoved, lastXpRemoved);
        }
    }

    // --- Скан + таймеры ---

    private void scan() {
        Set<ChunkKey> seen = new HashSet<>();

        for (WorldServer world : FMLCommonHandler.instance().getMinecraftServerInstance().worlds) {
            int dim = world.provider.getDimension();
            bucket(world);

            Set<Long> chunks = new HashSet<>(itemMap.keySet());
            chunks.addAll(xpMap.keySet());

            for (long c : chunks) {
                List<EntityItem>  items = itemMap.get(c);
                List<EntityXPOrb> xps   = xpMap.get(c);
                int ni = items == null ? 0 : items.size();
                int nx = xps   == null ? 0 : xps.size();
                boolean over = ni > itemsPerChunkLimit || nx > xpOrbsPerChunkLimit;

                ChunkKey key = new ChunkKey(dim, c);
                seen.add(key);
                Long fireAt = pending.get(key);

                if (over) {
                    if (fireAt == null) {
                        // порог только что превышен — заводим таймер и предупреждаем игроков
                        pending.put(key, ticks + cleanupDelayTicks);
                        warnChunk(world, c);
                    } else if (ticks >= fireAt) {
                        // таймер дотикал, а чанк всё ещё переполнен — чистим
                        trim(items, xps);
                        pending.remove(key);
                    }
                    // иначе — таймер ещё идёт, ждём
                } else if (fireAt != null) {
                    pending.remove(key); // чанк остыл — отменяем таймер
                }
            }
        }

        // Таймеры для чанков, которые за этот скан исчезли (выгрузились/опустели) — снять.
        if (!pending.isEmpty()) pending.keySet().removeIf(k -> !seen.contains(k));
    }

    /** Раскладывает предметы и орбы текущего мира по чанкам в переиспользуемые карты. */
    private void bucket(WorldServer world) {
        itemMap.clear();
        xpMap.clear();
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityItem) {
                long key = ChunkPos.asLong(entity.chunkCoordX, entity.chunkCoordZ);
                itemMap.computeIfAbsent(key, k -> new ArrayList<>()).add((EntityItem) entity);
            } else if (entity instanceof EntityXPOrb) {
                long key = ChunkPos.asLong(entity.chunkCoordX, entity.chunkCoordZ);
                xpMap.computeIfAbsent(key, k -> new ArrayList<>()).add((EntityXPOrb) entity);
            }
        }
    }

    /** Срезает переполненный чанк до лимитов. Предметы: убираем старейшие. Орбы: убираем мелочь. */
    private void trim(List<EntityItem> items, List<EntityXPOrb> xps) {
        if (items != null && items.size() > itemsPerChunkLimit) {
            items.sort((a, b) -> b.ticksExisted - a.ticksExisted); // старые первыми
            int remove = items.size() - itemsPerChunkLimit;
            for (int i = 0; i < remove; i++) { items.get(i).setDead(); lastItemsRemoved++; }
        }
        if (xps != null && xps.size() > xpOrbsPerChunkLimit) {
            xps.sort(Comparator.comparingInt(o -> o.xpValue)); // мелкие первыми
            int remove = xps.size() - xpOrbsPerChunkLimit;
            for (int i = 0; i < remove; i++) { xps.get(i).setDead(); lastXpRemoved++; }
        }
    }

    /** Предупреждает игроков, стоящих в помеченном чанке, о скорой очистке. */
    private void warnChunk(WorldServer world, long chunk) {
        int cx = (int) (chunk & 0xFFFFFFFFL);
        int cz = (int) (chunk >>> 32);
        int secs = Math.max(1, cleanupDelayTicks / 20);
        for (EntityPlayer p : world.playerEntities) {
            if (p.chunkCoordX == cx && p.chunkCoordZ == cz) {
                p.sendMessage(new TextComponentString(TextFormatting.YELLOW
                        + "В этом чанке много дропа — он будет очищен через " + secs + " c. Забери нужное!"));
            }
        }
    }

    // --- Немедленная очистка (/memopt clean) ---

    /** Чистит все переполненные чанки прямо сейчас, минуя таймеры, и сбрасывает таймеры. */
    public int[] runCleanupNow() {
        lastItemsRemoved = 0;
        lastXpRemoved    = 0;
        for (WorldServer world : FMLCommonHandler.instance().getMinecraftServerInstance().worlds) {
            bucket(world);
            Set<Long> chunks = new HashSet<>(itemMap.keySet());
            chunks.addAll(xpMap.keySet());
            for (long c : chunks) trim(itemMap.get(c), xpMap.get(c));
        }
        pending.clear();
        return new int[]{ lastItemsRemoved, lastXpRemoved };
    }

    // --- Статус ---

    public int pendingCount() { return pending.size(); }

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

    // --- Ключ чанка с учётом измерения ---

    private static final class ChunkKey {
        final int dim;
        final long chunk;
        ChunkKey(int dim, long chunk) { this.dim = dim; this.chunk = chunk; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkKey)) return false;
            ChunkKey k = (ChunkKey) o;
            return dim == k.dim && chunk == k.chunk;
        }
        @Override public int hashCode() { return 31 * dim + (int) (chunk ^ (chunk >>> 32)); }
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) { save(); return; }
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            if (obj.has("itemsPerChunkLimit"))  itemsPerChunkLimit  = obj.get("itemsPerChunkLimit").getAsInt();
            if (obj.has("xpOrbsPerChunkLimit")) xpOrbsPerChunkLimit = obj.get("xpOrbsPerChunkLimit").getAsInt();
            // имя сменилось со старого cleanupIntervalTicks -> scanIntervalTicks (читаем оба)
            if (obj.has("scanIntervalTicks"))        scanIntervalTicks = obj.get("scanIntervalTicks").getAsInt();
            else if (obj.has("cleanupIntervalTicks")) scanIntervalTicks = obj.get("cleanupIntervalTicks").getAsInt();
            if (obj.has("cleanupDelayTicks"))   cleanupDelayTicks   = obj.get("cleanupDelayTicks").getAsInt();
            if (scanIntervalTicks < 1) scanIntervalTicks = 1;
            if (cleanupDelayTicks < 1) cleanupDelayTicks = 1;
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load memopt config", e);
        }
    }

    public void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("itemsPerChunkLimit",  itemsPerChunkLimit);
            obj.addProperty("xpOrbsPerChunkLimit", xpOrbsPerChunkLimit);
            obj.addProperty("scanIntervalTicks",   scanIntervalTicks);
            obj.addProperty("cleanupDelayTicks",   cleanupDelayTicks);
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save memopt config", e);
        }
    }
}
