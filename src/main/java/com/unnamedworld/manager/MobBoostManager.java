package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntitySpawnPlacementRegistry;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldEntitySpawner;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.List;
import java.util.Random;

/**
 * «Доспавниватель» враждебных мобов — обход неэффективного спавнера CubicChunks.
 *
 * Родной спавнер CC размазывает попытки по вертикали ±128 блоков: почти все они
 * попадают в толщу камня или в небо (это видно в spark-профиле), и вокруг игроков
 * пусто даже при свободном мобкапе. Этот менеджер регулярно делает попытки спавна
 * НА ВЫСОТЕ ИГРОКА (±16 блоков), где они имеют смысл.
 *
 * Честность сохранена: мобы берутся из спавн-листа биома, проверяются свет,
 * опора/коллизии (ванильные проверки), дистанция до игроков (не ближе 24 блоков),
 * событие CheckSpawn (уважает /nospawn и правила других модов), глобальный мобкап
 * и локальный потолок плотности — чтобы не превращать ночь в ад.
 *
 * Только обычный мир (dim 0). Управляется командой /mobboost, настройки в mobboost.json.
 */
public class MobBoostManager {

    public static final MobBoostManager INSTANCE = new MobBoostManager();

    // --- Настройки (сохраняются) ---
    public int intervalTicks = 100; // проход раз в 5 c
    public int localCap      = 20;  // максимум враждебных в радиусе вокруг игрока
    public int localRadius   = 48;  // радиус подсчёта локальной плотности (блоки)
    public int maxPerPass    = 3;   // максимум заспавненных за проход на игрока
    // Свой глобальный потолок монстров в измерении. НЕ ванильный кап: тот (70) на этом
    // сервере навсегда забит неспавнящимися мобами структур (вампиры/глифиды/охотники),
    // из-за чего доспавн не работал бы вовсе.
    public int globalCap     = 500;
    // Внешний край кольца спавна. Учти механику деспавна: дальше 32 блоков от игрока
    // праздных мобов постепенно убирает игра, дальше 128 — мгновенно. Разумный максимум ~80-96.
    public int maxDist       = 56;
    private boolean enabled  = true;

    private static final int ATTEMPTS_PER_PASS = 16; // попыток позиций за проход на игрока
    private static final int MIN_DIST = 24;          // не ближе к игрокам (ваниль)
    private static final int VERTICAL_RANGE = 16;    // окно по высоте вокруг Y игрока

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tickCounter = 0;
    private long spawnedTotal = 0; // за сессию, для /mobboost status

    public void init(File configDir) {
        saveFile = new File(configDir, "mobboost.json");
        load();
    }

    // --- API для команды ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { enabled = e; save(); }
    public long getSpawnedTotal() { return spawnedTotal; }

    public String status() {
        return "Доспавниватель мобов: " + (enabled ? "вкл" : "выкл")
                + ". Интервал " + intervalTicks + " тиков, локальный потолок " + localCap
                + " в радиусе " + localRadius + ", до " + maxPerPass + " за проход, кольцо спавна "
                + MIN_DIST + "-" + maxDist + ", глобальный потолок " + globalCap
                + ". Заспавнено за сессию: " + spawnedTotal + ".";
    }

    /** Принудительный проход вокруг игрока (команда /mobboost test). Возвращает число заспавненных. */
    public int forcePass(EntityPlayerMP player) {
        if (!(player.world instanceof WorldServer)) return 0;
        return passAroundPlayer((WorldServer) player.world, player);
    }

    // --- Тик ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled) return;
        if (++tickCounter < intervalTicks) return;
        tickCounter = 0;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        WorldServer world = server.getWorld(0);
        if (world == null) return;
        if (world.getDifficulty() == EnumDifficulty.PEACEFUL) return;
        if (!world.getGameRules().getBoolean("doMobSpawning")) return;

        // Свой глобальный потолок (ванильный кап забит вечными мобами структур).
        if (world.countEntities(EnumCreatureType.MONSTER, true) >= globalCap) return;

        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player.dimension != 0 || player.isSpectator()) continue;
            passAroundPlayer(world, player);
        }
    }

    // --- Спавн ---

    private int passAroundPlayer(WorldServer world, EntityPlayerMP player) {
        Random rand = world.rand;

        // Локальная плотность: враждебных рядом уже достаточно — не доспавниваем.
        AxisAlignedBB box = new AxisAlignedBB(player.posX, player.posY, player.posZ,
                player.posX, player.posY, player.posZ).grow(Math.max(localRadius, maxDist)); // учитываем всё кольцо спавна
        if (world.getEntitiesWithinAABB(EntityLiving.class, box,
                e -> e instanceof IMob && e.isEntityAlive()).size() >= localCap) return 0;

        int px = MathHelper.floor(player.posX);
        int py = MathHelper.floor(player.posY);
        int pz = MathHelper.floor(player.posZ);
        BlockPos worldSpawn = world.getSpawnPoint();

        int spawned = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < ATTEMPTS_PER_PASS && spawned < maxPerPass; i++) {
            // Случайная точка в кольце [MIN_DIST..maxDist] по горизонтали; высоту НЕ угадываем,
            // а ищем реальный «пол» (поверхность или пол пещеры) в окне ±VERTICAL_RANGE от игрока —
            // иначе случайный Y почти всегда попадает в толщу породы или в воздух.
            double angle = rand.nextDouble() * Math.PI * 2;
            int dist = MIN_DIST + rand.nextInt(Math.max(1, maxDist - MIN_DIST + 1));
            int x = px + (int) (Math.cos(angle) * dist);
            int z = pz + (int) (Math.sin(angle) * dist);

            int y = findSpawnFloor(world, x, z, py + VERTICAL_RANGE, py - VERTICAL_RANGE, rand);
            if (y == Integer.MIN_VALUE) continue;
            m.setPos(x, y, z);
            if (m.distanceSq(worldSpawn) < 576.0) continue;               // 24 блока от спавна мира (ваниль)
            if (world.getClosestPlayer(x, y, z, MIN_DIST, false) != null) continue; // не ближе 24 к любому игроку

            // Темнота: грубый ванильный фильтр (детально перепроверит getCanSpawnHere)
            if (world.getLightFromNeighbors(m) > 7) continue;
            if (world.isDaytime() && world.canSeeSky(m)) continue;

            // Кого спавнить — ванильный путь: getSpawnListEntryForTypeAt стреляет Forge-событием
            // PotentialSpawns, так что моды, подменяющие список по позиции, тоже уважаются.
            Biome.SpawnListEntry entry = world.getSpawnListEntryForTypeAt(EnumCreatureType.MONSTER, m);
            if (entry == null) continue;
            if (!world.canCreatureTypeSpawnHere(EnumCreatureType.MONSTER, entry, m)) continue;

            // Ванильная проверка места под тип размещения (опора, не в блоке, не в жидкости)
            EntityLiving.SpawnPlacementType placement =
                    EntitySpawnPlacementRegistry.getPlacementForEntity(entry.entityClass);
            if (placement == null) placement = EntityLiving.SpawnPlacementType.ON_GROUND;
            if (!WorldEntitySpawner.canCreatureTypeSpawnAtLocation(placement, world, m)) continue;

            EntityLiving entity;
            try {
                entity = (EntityLiving) entry.entityClass.getConstructor(net.minecraft.world.World.class)
                        .newInstance(world);
            } catch (Exception e) {
                continue; // кривый класс сущности — пропускаем
            }
            entity.setLocationAndAngles(x + 0.5, y, z + 0.5, rand.nextFloat() * 360.0f, 0.0f);

            // Событие Forge: уважает /nospawn, In Control, Vampirism и т.п.
            Event.Result canSpawn = ForgeEventFactory.canEntitySpawn(entity, world, x + 0.5f, y, z + 0.5f, null);
            if (canSpawn == Event.Result.DENY) { entity.setDead(); continue; }
            if (canSpawn == Event.Result.DEFAULT
                    && (!entity.getCanSpawnHere() || !entity.isNotColliding())) {
                entity.setDead();
                continue;
            }

            if (!ForgeEventFactory.doSpecialSpawn(entity, world, x + 0.5f, y, z + 0.5f, null)) {
                entity.onInitialSpawn(world.getDifficultyForLocation(new BlockPos(entity)), null);
            }
            world.spawnEntity(entity);
            spawned++;
            spawnedTotal++;
        }
        return spawned;
    }

    /**
     * Диагностика для /mobboost debug: 48 попыток вокруг игрока БЕЗ спавна,
     * с подсчётом, на каком фильтре они отсеиваются, + уровни света под ногами.
     */
    public String debugProbe(EntityPlayerMP player) {
        if (!(player.world instanceof WorldServer)) return "Только на сервере.";
        WorldServer world = (WorldServer) player.world;
        Random rand = world.rand;

        int px = MathHelper.floor(player.posX);
        int py = MathHelper.floor(player.posY);
        int pz = MathHelper.floor(player.posZ);
        BlockPos feet = new BlockPos(px, py, pz);
        BlockPos worldSpawn = world.getSpawnPoint();

        int noFloor = 0, tooClose = 0, tooLight = 0, daySky = 0, noEntry = 0,
            badPlace = 0, denied = 0, cantSpawn = 0, ok = 0;

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 48; i++) {
            double angle = rand.nextDouble() * Math.PI * 2;
            int dist = MIN_DIST + rand.nextInt(Math.max(1, maxDist - MIN_DIST + 1));
            int x = px + (int) (Math.cos(angle) * dist);
            int z = pz + (int) (Math.sin(angle) * dist);

            int y = findSpawnFloor(world, x, z, py + VERTICAL_RANGE, py - VERTICAL_RANGE, rand);
            if (y == Integer.MIN_VALUE) { noFloor++; continue; }
            m.setPos(x, y, z);

            if (m.distanceSq(worldSpawn) < 576.0
                    || world.getClosestPlayer(x, y, z, MIN_DIST, false) != null) { tooClose++; continue; }
            if (world.getLightFromNeighbors(m) > 7) { tooLight++; continue; }
            if (world.isDaytime() && world.canSeeSky(m)) { daySky++; continue; }

            Biome.SpawnListEntry entry = world.getSpawnListEntryForTypeAt(EnumCreatureType.MONSTER, m);
            if (entry == null || !world.canCreatureTypeSpawnHere(EnumCreatureType.MONSTER, entry, m)) { noEntry++; continue; }

            EntityLiving.SpawnPlacementType placement =
                    EntitySpawnPlacementRegistry.getPlacementForEntity(entry.entityClass);
            if (placement == null) placement = EntityLiving.SpawnPlacementType.ON_GROUND;
            if (!WorldEntitySpawner.canCreatureTypeSpawnAtLocation(placement, world, m)) { badPlace++; continue; }

            EntityLiving entity;
            try {
                entity = (EntityLiving) entry.entityClass.getConstructor(net.minecraft.world.World.class).newInstance(world);
            } catch (Exception e) { badPlace++; continue; }
            entity.setLocationAndAngles(x + 0.5, y, z + 0.5, 0, 0);

            Event.Result canSpawn = ForgeEventFactory.canEntitySpawn(entity, world, x + 0.5f, y, z + 0.5f, null);
            if (canSpawn == Event.Result.DENY) { denied++; entity.setDead(); continue; }
            if (canSpawn == Event.Result.DEFAULT
                    && (!entity.getCanSpawnHere() || !entity.isNotColliding())) { cantSpawn++; entity.setDead(); continue; }
            entity.setDead(); // debug: не спавним
            ok++;
        }

        return "Свет у ног: небо=" + world.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, feet)
                + " блок=" + world.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK, feet)
                + " итог=" + world.getLightFromNeighbors(feet)
                + (world.isDaytime() ? " (день)" : " (ночь)")
                + " | 48 попыток: прошло бы=" + ok
                + ", нет пола=" + noFloor
                + ", близко=" + tooClose
                + ", светло=" + tooLight
                + ", дневное небо=" + daySky
                + ", нет моба для биома=" + noEntry
                + ", место не подходит=" + badPlace
                + ", запрет модов=" + denied
                + ", ванильная проверка (свет/коллизии)=" + cantSpawn;
    }

    /**
     * Ищет «полы» в столбике (x,z) в окне [yLo..yHi]: воздух (2 блока на рост моба)
     * с непустой опорой снизу. Ловит и поверхность, и полы пещер. Возвращает Y
     * случайного из найденных, либо Integer.MIN_VALUE, если пола нет/чанк не прогружен.
     */
    private int findSpawnFloor(WorldServer world, int x, int z, int yHi, int yLo, Random rand) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, yHi, z);
        if (!world.isBlockLoaded(m)) return Integer.MIN_VALUE;

        int[] floors = new int[8];
        int found = 0;
        boolean airAbove = false; // воздух на y+1 (уже проверенный сверху)
        for (int y = yHi; y >= yLo && found < floors.length; y--) {
            m.setPos(x, y, z);
            boolean air = world.isAirBlock(m);
            if (!air && airAbove && world.isAirBlock(m.setPos(x, y + 2, z))) {
                // y — опора, y+1 и y+2 — воздух: пол найден (точка спавна = y+1)
                floors[found++] = y + 1;
            }
            airAbove = air;
        }
        if (found == 0) return Integer.MIN_VALUE;
        return floors[rand.nextInt(found)];
    }

    // --- Persistence ---

    private void load() {
        if (saveFile == null || !saveFile.exists()) { save(); return; }
        try (Reader r = new FileReader(saveFile)) {
            JsonObject o = gson.fromJson(r, JsonObject.class);
            if (o == null) return;
            if (o.has("enabled"))       enabled       = o.get("enabled").getAsBoolean();
            if (o.has("intervalTicks")) intervalTicks = Math.max(20, o.get("intervalTicks").getAsInt());
            if (o.has("localCap"))      localCap      = Math.max(1, o.get("localCap").getAsInt());
            if (o.has("localRadius"))   localRadius   = Math.max(16, o.get("localRadius").getAsInt());
            if (o.has("maxPerPass"))    maxPerPass    = Math.max(1, o.get("maxPerPass").getAsInt());
            if (o.has("globalCap"))     globalCap     = Math.max(50, o.get("globalCap").getAsInt());
            if (o.has("maxDist"))       maxDist       = MathHelper.clamp(o.get("maxDist").getAsInt(), 32, 112);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[MobBoost] Не удалось загрузить mobboost.json", e);
        }
    }

    public void save() {
        if (saveFile == null) return;
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("intervalTicks", intervalTicks);
            o.addProperty("localCap", localCap);
            o.addProperty("localRadius", localRadius);
            o.addProperty("maxPerPass", maxPerPass);
            o.addProperty("globalCap", globalCap);
            o.addProperty("maxDist", maxDist);
            gson.toJson(o, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[MobBoost] Не удалось сохранить mobboost.json", e);
        }
    }
}
