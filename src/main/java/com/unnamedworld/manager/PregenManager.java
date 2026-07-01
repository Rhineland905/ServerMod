package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.lang.reflect.Method;
import java.util.Random;

/**
 * Фоновый пре-генератор большой области (для прогрузки терры заранее, чтобы сервер
 * меньше лагал при исследовании). В отличие от /chunkload:
 *  • идёт КУРСОРОМ по координатам (без списка на миллионы — память O(1));
 *  • генерит ВДАЛИ от игроков, поэтому кубы не удерживаются тикетами и CubicChunks
 *    выгружает их позади «волны» → RAM остаётся в норме;
 *  • СЛЕДИТ за кучей: заполнилась >85% — пауза, ждём выгрузки/GC, продолжаем при <65%;
 *  • ВОЗОБНОВЛЯЕТСЯ после рестарта (прогресс пишется в pregen.json).
 *
 * Управляется командой /pregen. Лучше запускать, когда на сервере никого нет.
 */
public class PregenManager {

    public static final PregenManager INSTANCE = new PregenManager();

    // --- Тюнинг ---
    private static final int    CUBES_PER_TICK   = 40;            // максимум кубов за тик
    private static final long   TICK_BUDGET_NS   = 40_000_000L;   // и не дольше 40 мс
    private static final int    HEAVY_MS         = 100;           // тяжёлый тик → пауза
    private static final int    COOLDOWN_MAX     = 400;           // макс пауза, тиков (20с)
    private static final long   PAUSE_FREE_BYTES  = 600L  * 1024 * 1024; // <600МБ запаса кучи → пауза
    private static final long   RESUME_FREE_BYTES = 1200L * 1024 * 1024; // >1.2ГБ → продолжаем (гистерезис)
    private static final int    SAVE_EVERY_CUBES = 5000;          // как часто писать прогресс
    private static final int    REPORT_INTERVAL  = 100;           // отчёт раз в 5с (тиков)

    // --- CubicChunks через рефлексию (как в CommandChunkLoad) ---
    private static final Class<?> CC_WORLD_CLASS;
    private static final Method   CC_IS_CUBIC;
    private static final Method   CC_GET_CACHE;
    private static final Method   CC_GET_CUBE;
    private static final Method   CC_IS_GENERATED;   // isCubeGenerated(x,y,z) на кэше — уже на диске?
    private static final Method   CC_UNLOAD_OLD;     // unloadOldCubes() на мире — выгрузить ненужные кубы
    private static final Object   CC_POPULATE;
    static {
        Class<?> wc = null; Method ic = null, gc = null, gcube = null, isgen = null, unload = null; Object pop = null;
        try {
            wc = Class.forName("io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer");
            Class<?> prov = Class.forName("io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer");
            @SuppressWarnings("unchecked")
            Class<Enum> req = (Class<Enum>) Class.forName(
                "io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer$Requirement");
            ic = wc.getMethod("isCubicWorld");
            gc = wc.getMethod("getCubeCache");
            unload = wc.getMethod("unloadOldCubes");
            gcube = prov.getMethod("getCube", int.class, int.class, int.class, req);
            isgen = prov.getMethod("isCubeGenerated", int.class, int.class, int.class);
            pop = Enum.valueOf(req, "POPULATE");
        } catch (Exception ignored) { /* CC не установлен */ }
        CC_WORLD_CLASS = wc; CC_IS_CUBIC = ic; CC_GET_CACHE = gc; CC_GET_CUBE = gcube;
        CC_IS_GENERATED = isgen; CC_UNLOAD_OLD = unload; CC_POPULATE = pop;
    }

    private static boolean isCubic(WorldServer w) {
        if (CC_WORLD_CLASS == null || !CC_WORLD_CLASS.isInstance(w)) return false;
        try { return (boolean) CC_IS_CUBIC.invoke(w); } catch (Exception e) { return false; }
    }
    private static void loadCube(WorldServer w, int cx, int cy, int cz) {
        try { CC_GET_CUBE.invoke(CC_GET_CACHE.invoke(w), cx, cy, cz, CC_POPULATE); }
        catch (Exception ignored) {}
    }
    /** Уже сгенерирован (есть на диске)? Тогда не грузим его в RAM повторно. */
    private static boolean isGenerated(WorldServer w, int cx, int cy, int cz) {
        if (CC_IS_GENERATED == null) return false;
        try { return (boolean) CC_IS_GENERATED.invoke(CC_GET_CACHE.invoke(w), cx, cy, cz); }
        catch (Exception e) { return false; }
    }
    /** Принудительно выгрузить ненужные кубы из памяти (CC сохранит их на диск). */
    private static void unloadOld(WorldServer w) {
        if (CC_UNLOAD_OLD == null) return;
        try { CC_UNLOAD_OLD.invoke(w); } catch (Exception ignored) {}
    }

    // --- Состояние задачи ---
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private boolean running = false;
    private boolean cubic   = false;
    private boolean whenEmpty = false;                      // фон-режим: генерить только когда нет игроков
    private int dim;

    // --- Режим прореживания руды (вместо генерации) ---
    private boolean thin = false;             // true → не генерим, а режем уже готовые кубы
    private int     thinMinY, thinMaxY;       // диапазон высот для прореживания (в блоках)
    private double  thinFrac;                 // доля руды, которую убрать (0..1)
    private String  thinBlockName;            // целевой блок (registry name), сохраняется для resume
    private transient Block thinTarget;       // он же, резолвленный
    private String  thinFillName;             // чем заменять (registry name), сохраняется
    private transient IBlockState thinFill;   // он же, резолвленный стейт
    private long    oresFound, oresRemoved;   // счётчики для отчёта
    private static final IBlockState THIN_STONE = Blocks.STONE.getDefaultState();
    private final Random rand = new Random();
    private int minCX, maxCX, minCZ, maxCZ, minCY, maxCY;   // границы в координатах чанков/кубов
    private int curCX, curCZ, curCY;                        // курсор
    private long totalCubes, doneCubes;
    private long startMs;

    private transient WorldServer world;          // резолвится лениво (в т.ч. после рестарта)
    private transient ICommandSender initiator;   // для отчётов; после рестарта null → в лог
    private transient boolean pausedForPlayers = false;
    private transient boolean memWait = false;
    private transient int memWaitTicks = 0;
    private transient int cooldown = 0;
    private transient int ticksSinceReport = 0;
    private transient int sinceSave = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "pregen.json");
        load(); // если был незавершённый запуск — running=true, продолжим на первом тике
        if (running) {
            ServerMod.LOGGER.info("[Pregen] Обнаружена незавершённая задача — продолжу после старта мира.");
        }
    }

    public boolean isRunning() { return running; }

    // --- Запуск/останов ---

    public String start(WorldServer w, ICommandSender by, int centerX, int centerZ,
                        int halfBlocks, int minY, int maxY, boolean whenEmpty) {
        if (running) return "Пре-ген уже идёт. Сначала /pregen stop.";
        this.world = w;
        this.initiator = by;
        this.whenEmpty = whenEmpty;
        this.thin = false;
        this.dim = w.provider.getDimension();
        this.cubic = isCubic(w);

        int minBX = centerX - halfBlocks, maxBX = centerX + halfBlocks;
        int minBZ = centerZ - halfBlocks, maxBZ = centerZ + halfBlocks;
        minCX = minBX >> 4; maxCX = maxBX >> 4;
        minCZ = minBZ >> 4; maxCZ = maxBZ >> 4;
        if (cubic) { minCY = minY >> 4; maxCY = maxY >> 4; }
        else       { minCY = 0;        maxCY = 0;        }

        curCX = minCX; curCZ = minCZ; curCY = minCY;
        long cols = (long) (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
        totalCubes = cols * (maxCY - minCY + 1);
        doneCubes = 0;
        startMs = System.currentTimeMillis();
        memWait = false; cooldown = 0; ticksSinceReport = 0; sinceSave = 0;
        running = true;
        save();

        int side = (maxCX - minCX + 1);
        return "Пре-ген [" + (cubic ? "CubicChunks" : "vanilla") + "] запущен"
                + (whenEmpty ? " в ФОНОВОМ режиме (только когда нет игроков)" : "") + ": "
                + side + "x" + side + " чанков"
                + (cubic ? " x " + (maxCY - minCY + 1) + " слоёв (Y " + minY + ".." + maxY + ")" : "")
                + ", всего " + totalCubes + (cubic ? " кубов" : " чанков")
                + ", dim " + dim + ", центр (" + centerX + ", " + centerZ + ")."
                + (whenEmpty ? " Зашёл игрок — пауза, вышел — продолжит. Переживает рестарт."
                             : " Лучше — когда никого нет онлайн.");
    }

    /**
     * Фоновое прореживание алмазной руды по большой области (для «починки» уже
     * сгенерированных чанков). Идёт тем же курсором, что и пре-ген, но вместо генерации
     * грузит уже готовый куб, заменяет часть руды камнем и выгружает его. Ungenerated
     * кубы пропускаются (ничего не генерим). Резюмируется после рестарта.
     */
    public String startThin(WorldServer w, ICommandSender by, int centerX, int centerZ,
                            int halfBlocks, int minY, int maxY, int percent,
                            Block target, Block fillBlock, boolean whenEmpty) {
        if (running) return "Уже идёт задача (пре-ген/прореживание). Сначала /orethin stop.";
        this.world = w;
        this.initiator = by;
        this.whenEmpty = whenEmpty;
        this.thin = true;
        this.dim = w.provider.getDimension();
        this.cubic = isCubic(w);
        this.thinMinY = Math.min(minY, maxY);
        this.thinMaxY = Math.max(minY, maxY);
        this.thinFrac = Math.max(0, Math.min(100, percent)) / 100.0;
        this.thinTarget = target != null ? target : Blocks.DIAMOND_ORE;
        this.thinBlockName = thinTarget.getRegistryName().toString();
        Block fb = fillBlock != null ? fillBlock : Blocks.STONE;
        this.thinFill = fb.getDefaultState();
        this.thinFillName = fb.getRegistryName().toString();
        this.oresFound = 0; this.oresRemoved = 0;

        int minBX = centerX - halfBlocks, maxBX = centerX + halfBlocks;
        int minBZ = centerZ - halfBlocks, maxBZ = centerZ + halfBlocks;
        minCX = minBX >> 4; maxCX = maxBX >> 4;
        minCZ = minBZ >> 4; maxCZ = maxBZ >> 4;
        if (cubic) { minCY = thinMinY >> 4; maxCY = thinMaxY >> 4; }
        else       { minCY = 0;             maxCY = 0;             }

        curCX = minCX; curCZ = minCZ; curCY = minCY;
        long cols = (long) (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
        totalCubes = cols * (maxCY - minCY + 1);
        doneCubes = 0;
        startMs = System.currentTimeMillis();
        memWait = false; cooldown = 0; ticksSinceReport = 0; sinceSave = 0;
        running = true;
        save();

        int side = (maxCX - minCX + 1);
        return "Прореживание [" + thinBlockName + " → " + thinFillName + "] запущено"
                + (whenEmpty ? " в ФОНЕ (только когда нет игроков)" : "")
                + ": " + side + "x" + side + " чанков, Y " + thinMinY + ".." + thinMaxY
                + ", убираю " + percent + "% руды, dim " + dim + ", центр (" + centerX + ", " + centerZ + ")."
                + " Обрабатываются только уже сгенерированные кубы. Долго — лучше на ночь. Прогресс: /orethin status.";
    }

    public String stop() {
        if (!running) return "Активной задачи пре-гена нет.";
        running = false;
        save();
        return "Пре-ген остановлен на " + doneCubes + "/" + totalCubes
                + ". Можно продолжить позже: /pregen resume.";
    }

    public String resume(WorldServer fallback, ICommandSender by) {
        if (running) return "Пре-ген уже идёт.";
        if (totalCubes <= 0 || doneCubes >= totalCubes) return "Нечего продолжать (нет сохранённой задачи).";
        this.initiator = by;
        this.world = null; // перерезолвим по dim на тике
        running = true;
        memWait = false; cooldown = 0;
        save();
        return "Пре-ген возобновлён: " + doneCubes + "/" + totalCubes + ".";
    }

    public String status() {
        if (!running && (totalCubes <= 0 || doneCubes >= totalCubes)) return "Пре-ген не запущен.";
        return strip(progress());
    }

    // --- Движок ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) return;

        ticksSinceReport++;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        if (world == null) {
            world = server.getWorld(dim);
            if (world == null) { running = false; ServerMod.LOGGER.error("[Pregen] dim " + dim + " недоступен — стоп."); return; }
        }

        // Фон-режим: пока на сервере есть игроки — стоим (не пиним чанки, не лагаем им)
        if (whenEmpty && server.getCurrentPlayerCount() > 0) {
            pausedForPlayers = true;
            return; // тихо ждём; состояние видно в /pregen status
        }
        pausedForPlayers = false;

        // Пауза, чтобы сервер догнал тики после тяжёлой генерации
        if (cooldown > 0) { cooldown--; periodicReport(); return; }

        // Память: смотрим АБСОЛЮТНЫЙ свободный запас кучи (до -Xmx), а не долю — так
        // корректно при любом размере кучи и базовом потреблении сборки.
        if (memWait) {
            if (++memWaitTicks % 40 == 0) System.gc();              // форсим сборку раз в ~2с
            if (freeHeadroom() > RESUME_FREE_BYTES) { memWait = false; memWaitTicks = 0; }
            else {
                if (memWaitTicks == 600) ServerMod.LOGGER.warn(
                    "[Pregen] Куча не освобождается (~30с не набрать {}МБ свободно). Не хватает RAM — "
                    + "подними -Xmx/контейнер. Пре-ген на паузе.", RESUME_FREE_BYTES / (1024 * 1024));
                periodicReport();
                return;
            }
        } else if (freeHeadroom() < PAUSE_FREE_BYTES) {
            memWait = true; memWaitTicks = 0;
            System.gc(); // подсказка: освободить уже выгруженные кубы
            periodicReport();
            return;
        }

        long t0 = System.nanoTime();
        boolean generatedAny = false;
        for (int i = 0; i < CUBES_PER_TICK; i++) {
            if (cubic) {
                if (thin) {
                    // прореживание: режем ТОЛЬКО уже сгенерированные кубы, новые не трогаем
                    if (isGenerated(world, curCX, curCY, curCZ)) {
                        loadCube(world, curCX, curCY, curCZ);
                        thinLoadedCube(world, curCX, curCY, curCZ);
                        generatedAny = true;
                    }
                } else if (!isGenerated(world, curCX, curCY, curCZ)) {
                    // Уже на диске — пропускаем, НЕ загружая в RAM (важно для resume/перекрытий)
                    loadCube(world, curCX, curCY, curCZ);
                    generatedAny = true;
                }
            } else {
                if (thin) thinVanillaColumn(world, curCX, curCZ);
                else      world.getChunkProvider().provideChunk(curCX, curCZ);
                generatedAny = true;
            }
            doneCubes++;

            if (!advance()) { if (cubic) unloadOld(world); finish(); return; } // дошли до конца
            if (System.nanoTime() - t0 > TICK_BUDGET_NS) break;   // лимит времени тика
            if (freeHeadroom() < PAUSE_FREE_BYTES) { memWait = true; memWaitTicks = 0; System.gc(); break; }
        }

        // КЛЮЧЕВОЕ: сразу выгружаем сгенерированные кубы из памяти — CubicChunks сохранит
        // их на диск и освободит RAM, не дожидаясь ленивого сборщика (который к тому же
        // глохнет при лагах). Так память остаётся плоской при любом размере области.
        if (cubic && generatedAny) unloadOld(world);

        // Тяжёлый тик → пропорциональная пауза (как в /chunkload)
        long spentMs = (System.nanoTime() - t0) / 1_000_000L;
        if (spentMs > HEAVY_MS) cooldown = (int) Math.min(spentMs * 2 / 50, COOLDOWN_MAX);

        if ((sinceSave += CUBES_PER_TICK) >= SAVE_EVERY_CUBES) { sinceSave = 0; save(); }
        periodicReport();
    }

    /** Двигает курсор. false — если область пройдена. Порядок: Y, затем Z, затем X. */
    private boolean advance() {
        if (++curCY > maxCY) {
            curCY = minCY;
            if (++curCZ > maxCZ) {
                curCZ = minCZ;
                if (++curCX > maxCX) return false;
            }
        }
        return true;
    }

    /** Режет целевую руду в одном уже загруженном кубе (только строки внутри [thinMinY,thinMaxY]). */
    private void thinLoadedCube(WorldServer w, int cx, int cy, int cz) {
        if (thinTarget == null) return;
        int y0 = cy << 4, y1 = y0 + 15;
        int from = Math.max(y0, thinMinY), to = Math.min(y1, thinMaxY);
        if (from > to) return;
        int bx0 = cx << 4, bz0 = cz << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++)
                for (int y = from; y <= to; y++) {
                    pos.setPos(bx0 + dx, y, bz0 + dz);
                    if (w.getBlockState(pos).getBlock() != thinTarget) continue;
                    oresFound++;
                    if (rand.nextDouble() < thinFrac) { w.setBlockState(pos, thinFill != null ? thinFill : THIN_STONE, 2); oresRemoved++; }
                }
    }

    /** Ванильный вариант: грузит колонку и режет её в диапазоне высот. */
    private void thinVanillaColumn(WorldServer w, int cx, int cz) {
        if (thinTarget == null) return;
        w.getChunkProvider().provideChunk(cx, cz);
        int bx0 = cx << 4, bz0 = cz << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++)
                for (int y = thinMinY; y <= thinMaxY; y++) {
                    pos.setPos(bx0 + dx, y, bz0 + dz);
                    if (w.getBlockState(pos).getBlock() != thinTarget) continue;
                    oresFound++;
                    if (rand.nextDouble() < thinFrac) { w.setBlockState(pos, thinFill != null ? thinFill : THIN_STONE, 2); oresRemoved++; }
                }
    }

    private void finish() {
        running = false;
        save();
        double sec = (System.currentTimeMillis() - startMs) / 1000.0;
        String done = thin
                ? TextFormatting.GREEN + "Прореживание завершено: проверено " + doneCubes
                  + " кубов за " + fmtTime((int) sec) + ". Алмазной руды: найдено " + oresFound
                  + ", убрано " + oresRemoved + "."
                : TextFormatting.GREEN + "Пре-ген завершён: " + doneCubes
                  + (cubic ? " кубов" : " чанков") + " за " + fmtTime((int) sec) + ".";
        report(new TextComponentString(done));
        ServerMod.LOGGER.info("[Pregen] {} done: {} cubes, {}s (ore found {}, removed {})",
                thin ? "Thin" : "Pregen", doneCubes, (int) sec, oresFound, oresRemoved);
    }

    private void periodicReport() {
        if (ticksSinceReport >= REPORT_INTERVAL) {
            ticksSinceReport = 0;
            report(progress());
        }
    }

    private TextComponentString progress() {
        double pct = totalCubes == 0 ? 100.0 : doneCubes * 100.0 / totalCubes;
        int bar = 20, filled = (int) Math.round(pct / 100.0 * bar);
        StringBuilder sb = new StringBuilder();
        sb.append(TextFormatting.WHITE).append("[");
        for (int i = 0; i < bar; i++)
            sb.append(i < filled ? "" + TextFormatting.GREEN + "█" : "" + TextFormatting.DARK_GRAY + "░");
        sb.append(TextFormatting.WHITE).append("] ")
          .append(TextFormatting.YELLOW).append(String.format("%.2f%%", pct))
          .append(TextFormatting.GRAY).append(" (").append(doneCubes).append("/").append(totalCubes).append(")");

        double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
        if (doneCubes > 0 && doneCubes < totalCubes && elapsed > 0) {
            long eta = (long) ((totalCubes - doneCubes) / (doneCubes / elapsed));
            sb.append(TextFormatting.AQUA).append("  ~").append(fmtTime((int) eta));
        }
        if (thin) sb.append(TextFormatting.LIGHT_PURPLE).append(" | руда ").append(oresRemoved).append("↓/").append(oresFound);
        if (whenEmpty) sb.append(TextFormatting.DARK_AQUA).append(" | фон");
        if (pausedForPlayers) sb.append(TextFormatting.GOLD).append(" | пауза: игроки онлайн");
        else if (memWait) sb.append(TextFormatting.RED).append(" | ждёт выгрузки RAM");
        else if (cooldown > 0) sb.append(TextFormatting.GRAY).append(" | пауза ").append(cooldown / 20 + 1).append("с");
        return new TextComponentString(sb.toString());
    }

    private void report(TextComponentString c) {
        if (initiator != null) initiator.sendMessage(c);
        else ServerMod.LOGGER.info("[Pregen] {}", strip(c));
    }

    /** Сколько ещё может вырасти куча (свободный запас до -Xmx), байт. */
    private static long freeHeadroom() {
        Runtime rt = Runtime.getRuntime();
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
    }

    private static String fmtTime(int sec) {
        if (sec < 60) return sec + "с";
        if (sec < 3600) return (sec / 60) + "м " + (sec % 60) + "с";
        return (sec / 3600) + "ч " + ((sec % 3600) / 60) + "м";
    }

    private static String strip(TextComponentString c) {
        return TextFormatting.getTextWithoutFormattingCodes(c.getUnformattedText());
    }

    // --- Сохранение прогресса (возобновление после рестарта) ---

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject o = new JsonObject();
            o.addProperty("running", running);
            o.addProperty("cubic", cubic);
            o.addProperty("whenEmpty", whenEmpty);
            o.addProperty("thin", thin);
            o.addProperty("thinMinY", thinMinY); o.addProperty("thinMaxY", thinMaxY);
            o.addProperty("thinFrac", thinFrac);
            if (thinBlockName != null) o.addProperty("thinBlock", thinBlockName);
            if (thinFillName != null)  o.addProperty("thinFill", thinFillName);
            o.addProperty("oresFound", oresFound); o.addProperty("oresRemoved", oresRemoved);
            o.addProperty("dim", dim);
            o.addProperty("minCX", minCX); o.addProperty("maxCX", maxCX);
            o.addProperty("minCZ", minCZ); o.addProperty("maxCZ", maxCZ);
            o.addProperty("minCY", minCY); o.addProperty("maxCY", maxCY);
            o.addProperty("curCX", curCX); o.addProperty("curCZ", curCZ); o.addProperty("curCY", curCY);
            o.addProperty("total", totalCubes); o.addProperty("done", doneCubes);
            o.addProperty("startMs", startMs);
            gson.toJson(o, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Pregen] Не удалось сохранить pregen.json", e);
        }
    }

    private void load() {
        if (saveFile == null || !saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject o = gson.fromJson(r, JsonObject.class);
            if (o == null) return;
            running = o.get("running").getAsBoolean();
            cubic   = o.get("cubic").getAsBoolean();
            whenEmpty = o.has("whenEmpty") && o.get("whenEmpty").getAsBoolean();
            thin     = o.has("thin") && o.get("thin").getAsBoolean();
            thinMinY = o.has("thinMinY") ? o.get("thinMinY").getAsInt() : 0;
            thinMaxY = o.has("thinMaxY") ? o.get("thinMaxY").getAsInt() : 0;
            thinFrac = o.has("thinFrac") ? o.get("thinFrac").getAsDouble() : 0.0;
            thinBlockName = o.has("thinBlock") ? o.get("thinBlock").getAsString() : "minecraft:diamond_ore";
            thinTarget = Block.getBlockFromName(thinBlockName);
            if (thinTarget == null) thinTarget = Blocks.DIAMOND_ORE;
            thinFillName = o.has("thinFill") ? o.get("thinFill").getAsString() : "minecraft:stone";
            Block fb = Block.getBlockFromName(thinFillName);
            thinFill = (fb != null ? fb : Blocks.STONE).getDefaultState();
            oresFound   = o.has("oresFound")   ? o.get("oresFound").getAsLong()   : 0;
            oresRemoved = o.has("oresRemoved") ? o.get("oresRemoved").getAsLong() : 0;
            dim     = o.get("dim").getAsInt();
            minCX = o.get("minCX").getAsInt(); maxCX = o.get("maxCX").getAsInt();
            minCZ = o.get("minCZ").getAsInt(); maxCZ = o.get("maxCZ").getAsInt();
            minCY = o.get("minCY").getAsInt(); maxCY = o.get("maxCY").getAsInt();
            curCX = o.get("curCX").getAsInt(); curCZ = o.get("curCZ").getAsInt(); curCY = o.get("curCY").getAsInt();
            totalCubes = o.get("total").getAsLong(); doneCubes = o.get("done").getAsLong();
            startMs = o.has("startMs") ? o.get("startMs").getAsLong() : System.currentTimeMillis();
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Pregen] Не удалось загрузить pregen.json", e);
            running = false;
        }
    }
}
