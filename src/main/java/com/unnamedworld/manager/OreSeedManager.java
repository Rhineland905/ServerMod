package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Досев руд HBM NTM — обход CubicChunks. CC вызывает форджевские
 * IWorldGenerator только при useVanillaChunkWorldGenerators=true, а по умолчанию
 * НЕ вызывает вовсе, поэтому руды HBM в мире не генерируются.
 *
 * ВАЖНО про производительность. HBM-руды ставятся через WorldGenMinableNonCascade
 * (setBlockState с флагом 18), а это живой путь света CubicChunks. Один чанк HBM —
 * это 40+ рудных жил + отложения = сотни setBlockState, каждый кидает пересчёт
 * света в очередь CC. Если обработать много чанков за тик, очередь пересчёта света
 * взрывается и следующий тик LightingManager.relightMultiBlock уходит в минуты →
 * ServerHangWatchdog роняет сервер. Поэтому здесь ЖЁСТКИЙ троттлинг:
 *   • чанки-кандидаты копятся в очереди {@link #pending} (вокруг игроков);
 *   • за раз генерируется НЕ БОЛЬШЕ ОДНОГО чанка и не чаще, чем раз в
 *     {@link #processInterval} тиков;
 *   • если сервер уже лагает (средний тик > {@link #MAX_MEAN_TICK_MS} мс) — пропускаем,
 *     чтобы не подсыпать нагрузки.
 *
 * Включён по умолчанию — троттлинг делает его безопасным; /uwores off выключает
 * (запоминается в oreseed.cfg). Обработанные чанки — в hbm_ores_seeded.dat
 * (по 8 байт на чанк). Только обычный мир (dim 0).
 *
 * /uwores reset снимает отметки «обработан» вокруг игрока: нужно после отката
 * мира, когда отметки сохранились, а рудные блоки — нет.
 */
public class OreSeedManager {

    public static final OreSeedManager INSTANCE = new OreSeedManager();

    private static final String HBM_WORLDGEN_CLASS = "com.hbm.lib.HbmWorldGen";

    private static final int SCAN_INTERVAL_TICKS = 100;   // раз в 5 c пополняем очередь вокруг игроков
    private static final int SCAN_RADIUS_CHUNKS  = 4;
    private static final int DEFAULT_PROCESS_INTERVAL = 20; // не чаще 1 чанка в секунду
    private static final int MIN_PROCESS_INTERVAL = 5;      // не даём совсем разгоняться
    private static final int MAX_QUEUE = 512;               // потолок очереди
    private static final int ROTATE_PER_PASS = 16;          // сколько НЕготовых чанков прокрутить за проход
    private static final int SAVE_INTERVAL_TICKS = 1200;
    // Порог TPS-защиты. 45 мс было слишком строго: сервер из-за света CC хронически
    // держит тик выше, и досев не срабатывал ВООБЩЕ. 150 мс = <7 TPS, реально умирает.
    private static final double MAX_MEAN_TICK_MS = 150.0;
    // Руды HBM ложатся в полосе примерно y 0..120. Прежде чем звать генератор,
    // убеждаемся, что кубы CC этой полосы прогружены (в чанке + 4 соседях).
    private static final int[] PROBE_YS = {8, 56, 104};

    private File saveFile;
    private File configFile;
    private boolean enabled = true;                        // троттлинг делает дефолт-вкл безопасным
    private int processInterval = DEFAULT_PROCESS_INTERVAL;

    // Рефлексию резолвим лениво: на preInit классы HBM ещё не готовы.
    private boolean resolved = false;
    private Object hbmGen;
    private Method generateOres;

    // NTM-CE: жилы руд — «отложенные структуры» (AbstractPhasedStructure с
    // useDynamicScheduler). generateOres их лишь ПЛАНИРУЕТ, а ставит блоки
    // PhasedEventHandler по событиям генерации/загрузки чанков. Для уже
    // существующих чанков события не приходят, и жилы выметает TTL-очистка.
    // Дожимаем вручную тем же путём, что onChunkLoaded (см. flushPhased).
    private Method pehGetState;   // PhasedEventHandler.getState(WorldServer)
    private Method pehProcess;    // PhasedEventHandler.processChunkAvailable(WorldServer, long)
    private Method pehUnregister; // PhasedEventHandler.unregisterWaitJob(DimensionState, AbstractChunkWaitJob)
    private Method psgGenFast;    // PhasedStructureGenerator.generateForChunkFast(World, DimensionState, int, int)
    private Method pdsRun;        // PendingDynamicStructure.run(WorldServer) — постановка блоков + самоочистка
    private java.lang.reflect.Field dsJobsByOrigin; // DimensionState.jobsByOriginChunk
    private Method l2oRemove;     // Long2ObjectOpenHashMap.remove(long)
    private Long reflushKey;      // чанк прошлого прохода — дожать ещё раз (вдруг валидация была отложена)

    private final Set<Long> seeded = new HashSet<>();
    private final LinkedHashSet<Long> pending = new LinkedHashSet<>(); // очередь (dedup + порядок)
    private boolean dirty = false;
    private int scanTick = 0;
    private int processTick = 0;
    private int saveCounter = 0;
    private int generatedThisSession = 0;
    private int tpsSkips = 0;          // сколько раз TPS-защита отложила досев
    private int errors = 0;
    private String lastError = null;

    public void init(File configDir) {
        // v3: имя сменено НАМЕРЕННО (уже второй раз). v1 отравлен крашем (мир
        // откатился, отметки остались), v2 отравлен phased-багом NTM-CE (чанки
        // помечались, а жилы умирали в планировщике). Новый файл = полный
        // перепроход всех чанков с нуля.
        saveFile = new File(configDir, "hbm_ores_seeded3.dat");
        configFile = new File(configDir, "oreseed.cfg");
        loadSet();
        loadConfig();
    }

    // --- Public API (для команды) ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { enabled = e; saveConfig(); }
    public int seededCount() { return seeded.size(); }
    public int queueSize() { return pending.size(); }
    public int getInterval() { return processInterval; }
    public boolean isAvailable() { return resolve(); }

    public void setInterval(int ticks) {
        processInterval = Math.max(MIN_PROCESS_INTERVAL, ticks);
        saveConfig();
    }

    public String status() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        double tick = server != null ? meanTickMs(server) : 0.0;
        return "Досев руд HBM: " + (enabled ? "включён" : "выключен")
                + (resolve() ? "" : " (HBM не найден — досев не работает!)")
                + ". Обработано чанков: " + seeded.size()
                + " (за сессию: " + generatedThisSession + ")"
                + ", в очереди: " + pending.size()
                + ", темп: 1 чанк / " + processInterval + " тиков"
                + ", тик: " + String.format("%.1f", tick) + " мс (порог " + (int) MAX_MEAN_TICK_MS + ")"
                + ", пропусков по TPS: " + tpsSkips
                + (errors > 0 ? ", ОШИБОК: " + errors + " (последняя: " + lastError + ")" : "")
                + ".";
    }

    /**
     * Поставить в очередь необработанные чанки вокруг игрока (команда /uwores seed).
     * НЕ генерирует сразу — иначе снова словим шторм света. Очередь сама сольётся
     * по 1 чанку за раз. Возвращает, сколько чанков добавлено в очередь.
     */
    public int forceSeed(WorldServer world, EntityPlayerMP player, int radiusChunks) {
        if (!resolve()) return -1;
        int pcx = MathHelper.floor(player.posX) >> 4;
        int pcz = MathHelper.floor(player.posZ) >> 4;
        int added = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (enqueue(ChunkPos.asLong(pcx + dx, pcz + dz))) added++;
            }
        }
        return added;
    }

    /**
     * Снять отметку «обработан» с чанков вокруг игрока и заново поставить их в
     * очередь (/uwores reset). Нужно после отката мира: отметки в
     * hbm_ores_seeded.dat пережили крэш, а рудные блоки — нет, и такие чанки
     * иначе не досеются никогда. Возвращает число снятых отметок.
     */
    public int resetAround(EntityPlayerMP player, int radiusChunks) {
        if (!resolve()) return -1;
        int pcx = MathHelper.floor(player.posX) >> 4;
        int pcz = MathHelper.floor(player.posZ) >> 4;
        int cleared = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                long key = ChunkPos.asLong(pcx + dx, pcz + dz);
                if (seeded.remove(key)) {
                    cleared++;
                    dirty = true;
                }
                enqueue(key);
            }
        }
        save();
        return cleared;
    }

    /** Полный сброс: забыть ВСЕ отметки «обработан» — алгоритм пойдёт по чанкам заново. */
    public int resetAll() {
        int cleared = seeded.size();
        seeded.clear();
        dirty = true;
        save();
        return cleared;
    }

    /** Поставить в очередь чанки вокруг произвольной точки (блочные координаты) — для консоли. */
    public int seedAt(int blockX, int blockZ, int radiusChunks) {
        if (!resolve()) return -1;
        int pcx = blockX >> 4;
        int pcz = blockZ >> 4;
        int added = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (enqueue(ChunkPos.asLong(pcx + dx, pcz + dz))) added++;
            }
        }
        return added;
    }

    /**
     * Диагностика (/uwores check): прогружена ли рудная полоса чанка и сколько в нём
     * блоков HBM (y 0..130). Отвечает на вопрос «руда реально есть?» без копания.
     */
    public String checkChunk(WorldServer world, int cx, int cz) {
        boolean loaded = oreBandLoaded(world, cx, cz);
        boolean marked = seeded.contains(ChunkPos.asLong(cx, cz));
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                for (int y = 0; y <= 130; y++) {
                    net.minecraft.block.Block b = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    net.minecraft.util.ResourceLocation rl = net.minecraft.block.Block.REGISTRY.getNameForObject(b);
                    if (rl != null && "hbm".equals(rl.getNamespace())) {
                        counts.merge(rl.getPath(), 1, Integer::sum);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("Чанк ").append(cx).append(',').append(cz)
                .append(": полоса руд ").append(loaded ? "прогружена" : "НЕ прогружена")
                .append(", отметка «обработан»: ").append(marked ? "есть" : "нет")
                .append(". Блоков HBM: ");
        if (counts.isEmpty()) {
            sb.append("НЕТ");
        } else {
            int total = 0;
            for (int v : counts.values()) total += v;
            sb.append(total).append(" (");
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append('x').append(e.getValue());
                first = false;
            }
            sb.append(')');
        }
        return sb.toString();
    }

    // --- Tick ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (++saveCounter >= SAVE_INTERVAL_TICKS) {
            saveCounter = 0;
            save();
        }

        if (!enabled) return;
        if (!resolve()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        WorldServer overworld = server.getWorld(0);
        if (overworld == null) return;

        // 1) Периодически пополняем очередь чанками вокруг игроков.
        if (++scanTick >= SCAN_INTERVAL_TICKS) {
            scanTick = 0;
            enqueueAroundPlayers(server);
        }

        // 2) Обрабатываем НЕ БОЛЬШЕ ОДНОГО чанка за цикл и только если серв не умирает.
        //    reflushKey учитываем отдельно: последний чанк очереди тоже должен дожаться.
        if (++processTick >= processInterval) {
            processTick = 0;
            if (!pending.isEmpty() || reflushKey != null) {
                if (meanTickMs(server) <= MAX_MEAN_TICK_MS) {
                    processOne(overworld);
                } else {
                    tpsSkips++;
                }
            }
        }
    }

    // --- Очередь ---

    private void enqueueAroundPlayers(MinecraftServer server) {
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player.dimension != 0) continue;
            int pcx = MathHelper.floor(player.posX) >> 4;
            int pcz = MathHelper.floor(player.posZ) >> 4;
            for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
                for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
                    if (pending.size() >= MAX_QUEUE) return;
                    enqueue(ChunkPos.asLong(pcx + dx, pcz + dz));
                }
            }
        }
    }

    private boolean enqueue(long key) {
        if (seeded.contains(key)) return false;
        if (pending.size() >= MAX_QUEUE) return false;
        return pending.add(key);
    }

    /**
     * Обработать ОДИН чанк из очереди (генерация руд HBM). Непрогруженные чанки
     * не блокируют очередь: прокручиваем до {@link #ROTATE_PER_PASS} штук в конец,
     * пока не найдём готовый. Генерация — строго одна за проход.
     */
    private void processOne(WorldServer world) {
        // Дожать чанк прошлого прохода: если валидация жил была отложена на тик,
        // сейчас (через processInterval тиков) они уже ждут «доступности» чанков.
        if (reflushKey != null) {
            flushPhased(world, (int) (long) reflushKey, (int) (reflushKey >> 32));
            reflushKey = null;
        }

        java.util.List<Long> notReady = new java.util.ArrayList<>();
        long key = 0;
        boolean found = false;

        Iterator<Long> it = pending.iterator();
        while (it.hasNext() && notReady.size() < ROTATE_PER_PASS) {
            long k = it.next();
            it.remove();
            if (seeded.contains(k)) continue;

            // ChunkPos.asLong: x в младших 32 битах, z в старших (со знаком).
            if (!oreBandLoaded(world, (int) k, (int) (k >> 32))) {
                notReady.add(k); // вернём в конец очереди после обхода
                continue;
            }
            key = k;
            found = true;
            break;
        }
        pending.addAll(notReady); // добавлять в LinkedHashSet во время итерации нельзя — CME

        if (!found) return;
        int cx = (int) key;
        int cz = (int) (key >> 32);

        // Помечаем ДО вызова: если генератор HBM упадёт на этом чанке,
        // не будем пытаться снова.
        seeded.add(key);
        dirty = true;
        try {
            generateOres.invoke(hbmGen, world, world.rand, cx << 4, cz << 4);
            flushPhased(world, cx, cz);
            reflushKey = key;
            generatedThisSession++;
            if (generatedThisSession == 1 || generatedThisSession % 64 == 0) {
                ServerMod.LOGGER.info("[Ores] Досеяно чанков за сессию: {} (последний: {},{})",
                        generatedThisSession, cx, cz);
            }
        } catch (Exception e) {
            errors++;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            lastError = cause.toString();
            ServerMod.LOGGER.error("[Ores] Ошибка генерации руд HBM в чанке {},{}", cx, cz, e);
        }
    }

    /**
     * NTM-CE: дожать запланированные generateOres жилы НЕМЕДЛЕННО. Ждать событий
     * бесполезно: PendingDynamicStructure.evaluate() проверяет готовность чанков
     * через ванильную мапу ChunkProviderServer.loadedChunks + isTerrainPopulated,
     * что под CubicChunks не выполняется НИКОГДА — джобы вечно болтаются в
     * ожидании и умирают по TTL. Поэтому:
     *   1) processChunkAvailable по чанку и соседям ±2 — переводит отложенную
     *      валидацию жил в jobsByOriginChunk;
     *   2) вынимаем джобы этого чанка из jobsByOriginChunk, снимаем с ожидания
     *      (unregisterWaitJob — иначе двойная генерация от поздних событий)
     *      и зовём run() напрямую — он ставит блоки и сам чистит за собой;
     *   3) generateForChunkFast — статичные phased-части (структуры) рядом.
     * Вызывается сразу после генерации и повторно через processInterval тиков
     * (reflushKey) — на случай, если валидация жил была отложена на тик.
     */
    private void flushPhased(WorldServer world, int cx, int cz) {
        if (pehProcess == null) return;
        try {
            Object state = pehGetState.invoke(null, world);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    pehProcess.invoke(null, world, ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
            Object jobsMap = dsJobsByOrigin.get(state);
            Object jobs = l2oRemove.invoke(jobsMap, ChunkPos.asLong(cx, cz));
            if (jobs != null) {
                for (Object job : new java.util.ArrayList<>((java.util.List<?>) jobs)) {
                    pehUnregister.invoke(null, state, job);
                    pdsRun.invoke(job, world);
                }
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    psgGenFast.invoke(null, world, state, cx + dx, cz + dz);
                }
            }
        } catch (Exception e) {
            errors++;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            lastError = cause.toString();
            ServerMod.LOGGER.error("[Ores] Ошибка дожима phased-жил у чанка {},{}", cx, cz, e);
        }
    }

    /** Прогружены ли кубы CC рудной полосы — в чанке и в 4 соседних колонках. */
    private boolean oreBandLoaded(WorldServer world, int cx, int cz) {
        int x = (cx << 4) + 8;
        int z = (cz << 4) + 8;
        for (int y : PROBE_YS) {
            if (!world.isBlockLoaded(new BlockPos(x, y, z))) return false;
            if (!world.isBlockLoaded(new BlockPos(x + 16, y, z))) return false;
            if (!world.isBlockLoaded(new BlockPos(x - 16, y, z))) return false;
            if (!world.isBlockLoaded(new BlockPos(x, y, z + 16))) return false;
            if (!world.isBlockLoaded(new BlockPos(x, y, z - 16))) return false;
        }
        return true;
    }

    /** Средний тик сервера в миллисекундах (последние ~100 тиков). */
    private double meanTickMs(MinecraftServer server) {
        long[] arr = server.tickTimeArray;
        if (arr == null || arr.length == 0) return 0.0;
        long sum = 0;
        for (long t : arr) sum += t;
        return (sum / (double) arr.length) * 1.0e-6;
    }

    private boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (!Loader.isModLoaded("hbm")) {
                ServerMod.LOGGER.warn("[Ores] Мод HBM не найден — досев руд выключен.");
            } else {
                try {
                    Class<?> cls = Class.forName(HBM_WORLDGEN_CLASS);
                    hbmGen = cls.newInstance();
                    generateOres = cls.getMethod("generateOres",
                            World.class, Random.class, int.class, int.class);
                    ServerMod.LOGGER.info("[Ores] Досев руд HBM NTM доступен (обход CubicChunks).");
                } catch (Exception e) {
                    hbmGen = null;
                    generateOres = null;
                    ServerMod.LOGGER.error("[Ores] Не удалось подключиться к HbmWorldGen — досев руд выключен.", e);
                }
                try {
                    Class<?> peh = Class.forName("com.hbm.world.phased.PhasedEventHandler");
                    Class<?> psg = Class.forName("com.hbm.world.phased.PhasedStructureGenerator");
                    Class<?> dimState = Class.forName("com.hbm.world.phased.DimensionState");
                    Class<?> waitJob = Class.forName("com.hbm.world.phased.PhasedEventHandler$AbstractChunkWaitJob");
                    Class<?> pds = Class.forName("com.hbm.world.phased.DynamicStructureDispatcher$PendingDynamicStructure");
                    pehGetState = peh.getDeclaredMethod("getState", WorldServer.class);
                    pehGetState.setAccessible(true);
                    pehProcess = peh.getDeclaredMethod("processChunkAvailable", WorldServer.class, long.class);
                    pehProcess.setAccessible(true);
                    pehUnregister = peh.getDeclaredMethod("unregisterWaitJob", dimState, waitJob);
                    pehUnregister.setAccessible(true);
                    psgGenFast = psg.getDeclaredMethod("generateForChunkFast", World.class, dimState, int.class, int.class);
                    psgGenFast.setAccessible(true);
                    pdsRun = pds.getDeclaredMethod("run", WorldServer.class);
                    pdsRun.setAccessible(true);
                    dsJobsByOrigin = dimState.getDeclaredField("jobsByOriginChunk");
                    dsJobsByOrigin.setAccessible(true);
                    l2oRemove = dsJobsByOrigin.getType().getMethod("remove", long.class);
                    ServerMod.LOGGER.info("[Ores] Phased-планировщик NTM-CE найден — жилы дожимаются немедленно.");
                } catch (Exception e) {
                    // Старый HBM без phased-системы — жилы ставятся сразу, дожим не нужен.
                    pehGetState = null;
                    pehProcess = null;
                    pehUnregister = null;
                    psgGenFast = null;
                    pdsRun = null;
                    dsJobsByOrigin = null;
                    l2oRemove = null;
                    ServerMod.LOGGER.info("[Ores] Phased-планировщик не найден (обычный HBM) — дожим не требуется.");
                }
            }
        }
        return generateOres != null;
    }

    // --- Persistence набора обработанных чанков (по 8 байт на ключ) ---

    private void loadSet() {
        if (saveFile == null || !saveFile.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(saveFile)))) {
            //noinspection InfiniteLoopStatement
            while (true) seeded.add(in.readLong());
        } catch (EOFException eof) {
            // конец файла — норма
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Ores] Не удалось загрузить {}", saveFile.getName(), e);
        }
    }

    public void save() {
        if (!dirty || saveFile == null) return;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(saveFile)))) {
            for (long k : seeded) out.writeLong(k);
            dirty = false;
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Ores] Не удалось сохранить {}", saveFile.getName(), e);
        }
    }

    // --- Persistence настроек (enabled/interval) — простой текстовый файл ---

    private void loadConfig() {
        if (configFile == null || !configFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals("enabled")) enabled = Boolean.parseBoolean(v);
                else if (k.equals("interval")) {
                    try { processInterval = Math.max(MIN_PROCESS_INTERVAL, Integer.parseInt(v)); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Ores] Не удалось загрузить {}", configFile.getName(), e);
        }
    }

    private void saveConfig() {
        if (configFile == null) return;
        try (PrintWriter w = new PrintWriter(configFile)) {
            w.println("enabled=" + enabled);
            w.println("interval=" + processInterval);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Ores] Не удалось сохранить {}", configFile.getName(), e);
        }
    }
}
