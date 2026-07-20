package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import net.minecraft.block.Block;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Генератор месторождений нефти HBM (Комплексный).
 * Генерирует ДВА типа нефти независимо друг от друга:
 * 1. Обычная нефть (ore_oil) - спавнится сферами в слоях камня.
 * 2. Бедроковая нефть (ore_bedrock_oil) - спавнится ОДИНОЧНЫМИ БЛОКАМИ на уровне бедрока.
 */
public class OilSeedManager {

    public static final OilSeedManager INSTANCE = new OilSeedManager();

    private static final int SCAN_INTERVAL_TICKS = 100;
    private static final int SCAN_RADIUS_CHUNKS  = 4;
    private static final int DEFAULT_PROCESS_INTERVAL = 20;
    private static final int MIN_PROCESS_INTERVAL = 5;
    private static final int MAX_QUEUE = 512;
    private static final int ROTATE_PER_PASS = 16;
    private static final int SAVE_INTERVAL_TICKS = 1200;
    private static final double MAX_MEAN_TICK_MS = 150.0;
    private static final int[] PROBE_YS = {-64, -30, 0, 30};

    // --- Параметры ОБЫЧНОЙ нефти (сферы) ---
    private static final float NORMAL_OIL_CHANCE = 0.40f;
    private static final int NORMAL_SPHERES_PER_CHUNK = 1;
    private static final int MIN_SPHERE_RADIUS = 3;
    private static final int MAX_SPHERE_RADIUS = 10;
    private static final int NORMAL_Y_MIN = -50;
    private static final int NORMAL_Y_MAX = 30;
    private static final String NORMAL_BLOCK_NAME = "ore_oil";

    // --- Параметры БЕДРОКОВОЙ нефти (одиночные блоки) ---
    private static final float BEDROCK_OIL_CHANCE = 0.40f;  // Шанс появления блока в чанке
    private static final int BEDROCK_BLOCKS_PER_CHUNK = 1;  // Макс. количество одиночных блоков на чанк
    private static final int BEDROCK_Y_MIN = -64;
    private static final int BEDROCK_Y_MAX = -60;
    private static final String BEDROCK_BLOCK_NAME = "ore_bedrock_oil";


    private File saveFile;
    private File configFile;
    private boolean enabled = true;
    private int processInterval = DEFAULT_PROCESS_INTERVAL;

    private boolean resolved = false;
    private Block normalOilBlock;
    private Block bedrockOilBlock;

    private final Set<Long> seeded = new HashSet<>();
    private final LinkedHashSet<Long> pending = new LinkedHashSet<>();
    private boolean dirty = false;
    private int scanTick = 0;
    private int processTick = 0;
    private int saveCounter = 0;
    private int generatedThisSession = 0;
    private int tpsSkips = 0;
    private int errors = 0;
    private String lastError = null;

    public void init(File configDir) {
        saveFile = new File(configDir, "hbm_oil_seeded.dat");
        configFile = new File(configDir, "oilseed.cfg");
        loadSet();
        loadConfig();
    }

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
        return "Досев комплексной нефти: " + (enabled ? "вкл" : "выкл")
                + (resolve() ? "" : " (ОШИБКА БЛОКОВ!)")
                + ". Обработано: " + seeded.size()
                + " (за сессию: " + generatedThisSession + ")"
                + ", в очереди: " + pending.size()
                + ", темп: 1 чанк/" + processInterval + " тик"
                + ", тик: " + String.format("%.1f", tick) + " мс";
    }

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

    public int resetAll() {
        int cleared = seeded.size();
        seeded.clear();
        dirty = true;
        save();
        return cleared;
    }

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

    public String checkChunk(WorldServer world, int cx, int cz) {
        boolean loaded = oilBandLoaded(world, cx, cz);
        boolean marked = seeded.contains(ChunkPos.asLong(cx, cz));
        int normalCount = 0;
        int bedrockCount = 0;

        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                for (int y = -64; y <= NORMAL_Y_MAX; y++) {
                    Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (block == normalOilBlock) normalCount++;
                    if (block == bedrockOilBlock) bedrockCount++;
                }
            }
        }
        return "Чанк " + cx + "," + cz
                + ": " + (loaded ? "прогружен" : "НЕ прогружен")
                + ", отметка: " + (marked ? "есть" : "нет")
                + ". Обычной нефти: " + normalCount
                + ", Бедроковой: " + bedrockCount;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (++saveCounter >= SAVE_INTERVAL_TICKS) {
            saveCounter = 0;
            save();
        }

        if (!enabled || !resolve()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        WorldServer overworld = server.getWorld(0);
        if (overworld == null) return;

        if (++scanTick >= SCAN_INTERVAL_TICKS) {
            scanTick = 0;
            enqueueAroundPlayers(server);
        }

        if (++processTick >= processInterval) {
            processTick = 0;
            if (!pending.isEmpty()) {
                if (meanTickMs(server) <= MAX_MEAN_TICK_MS) {
                    processOne(overworld);
                } else {
                    tpsSkips++;
                }
            }
        }
    }

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

    private void processOne(WorldServer world) {
        java.util.List<Long> notReady = new java.util.ArrayList<>();
        long key = 0;
        boolean found = false;

        Iterator<Long> it = pending.iterator();
        while (it.hasNext() && notReady.size() < ROTATE_PER_PASS) {
            long k = it.next();
            it.remove();
            if (seeded.contains(k)) continue;

            if (!oilBandLoaded(world, (int) k, (int) (k >> 32))) {
                notReady.add(k);
                continue;
            }
            key = k;
            found = true;
            break;
        }
        pending.addAll(notReady);

        if (!found) return;
        int cx = (int) key;
        int cz = (int) (key >> 32);

        seeded.add(key);
        dirty = true;
        try {
            generateOil(world, world.rand, cx, cz);
            generatedThisSession++;
            if (generatedThisSession == 1 || generatedThisSession % 64 == 0) {
                ServerMod.LOGGER.info("[Oil] Досеяно чанков комплексной нефти за сессию: {}", generatedThisSession);
            }
        } catch (Exception e) {
            errors++;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            lastError = cause.toString();
            ServerMod.LOGGER.error("[Oil] Ошибка генерации в чанке {},{}", cx, cz, e);
        }
    }

    /**
     * Вызывает генерацию обоих типов нефти.
     */
    private void generateOil(WorldServer world, Random rand, int cx, int cz) {
        int chunkBlockX = cx << 4;
        int chunkBlockZ = cz << 4;

        // 1. Проход для ОБЫЧНОЙ нефти (Сферы)
        for (int i = 0; i < NORMAL_SPHERES_PER_CHUNK; i++) {
            if (rand.nextFloat() <= NORMAL_OIL_CHANCE) {
                int bx = chunkBlockX + rand.nextInt(16);
                int bz = chunkBlockZ + rand.nextInt(16);
                int by = NORMAL_Y_MIN + rand.nextInt(NORMAL_Y_MAX - NORMAL_Y_MIN + 1);
                int radius = MIN_SPHERE_RADIUS + rand.nextInt(MAX_SPHERE_RADIUS - MIN_SPHERE_RADIUS + 1);

                generateNormalSphere(world, bx, by, bz, radius);
            }
        }

        // 2. Проход для БЕДРОКОВОЙ нефти (Одиночные блоки)
        for (int i = 0; i < BEDROCK_BLOCKS_PER_CHUNK; i++) {
            if (rand.nextFloat() <= BEDROCK_OIL_CHANCE) {
                int bx = chunkBlockX + rand.nextInt(16);
                int bz = chunkBlockZ + rand.nextInt(16);
                int by = BEDROCK_Y_MIN + rand.nextInt(BEDROCK_Y_MAX - BEDROCK_Y_MIN + 1);

                BlockPos pos = new BlockPos(bx, by, bz);

                // Проверяем, что на этом месте именно ванильный бедрок
                if (world.getBlockState(pos).getBlock() == net.minecraft.init.Blocks.BEDROCK) {
                    try {
                        world.setBlockState(pos, bedrockOilBlock.getDefaultState(), 18);
                    } catch (Exception e) {
                        // Игнорируем ошибки постановки
                    }
                }
            }
        }
    }

    /**
     * Генерация сферы ТОЛЬКО для обычной нефти.
     */
    private void generateNormalSphere(WorldServer world, int centerX, int centerY, int centerZ, int radius) {
        int radiusSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq <= radiusSq) {
                        BlockPos pos = new BlockPos(centerX + dx, centerY + dy, centerZ + dz);

                        if (pos.getY() < -64 || pos.getY() > 256) continue;

                        net.minecraft.block.Block existingBlock = world.getBlockState(pos).getBlock();

                        // Обычная нефть заменяет камень/землю, но не трогает воздух, воду, лаву и бедрок
                        if (existingBlock == net.minecraft.init.Blocks.AIR ||
                                existingBlock == net.minecraft.init.Blocks.WATER ||
                                existingBlock == net.minecraft.init.Blocks.LAVA ||
                                existingBlock == net.minecraft.init.Blocks.BEDROCK) {
                            continue;
                        }

                        try {
                            world.setBlockState(pos, normalOilBlock.getDefaultState(), 18);
                        } catch (Exception e) {
                            // Игнорируем ошибки постановки
                        }
                    }
                }
            }
        }
    }

    private boolean oilBandLoaded(WorldServer world, int cx, int cz) {
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
            try {
                net.minecraft.util.ResourceLocation normalRl = new net.minecraft.util.ResourceLocation("hbm", NORMAL_BLOCK_NAME);
                normalOilBlock = net.minecraft.block.Block.REGISTRY.getObject(normalRl);

                net.minecraft.util.ResourceLocation bedrockRl = new net.minecraft.util.ResourceLocation("hbm", BEDROCK_BLOCK_NAME);
                bedrockOilBlock = net.minecraft.block.Block.REGISTRY.getObject(bedrockRl);

                if (normalOilBlock == null || normalOilBlock == net.minecraft.init.Blocks.AIR) {
                    throw new IllegalArgumentException("Блок hbm:" + NORMAL_BLOCK_NAME + " не найден в реестре");
                }
                if (bedrockOilBlock == null || bedrockOilBlock == net.minecraft.init.Blocks.AIR) {
                    throw new IllegalArgumentException("Блок hbm:" + BEDROCK_BLOCK_NAME + " не найден в реестре");
                }
                ServerMod.LOGGER.info("[Oil] Комплексный генератор инициализирован. Обычная нефть: Y {} до {}, Бедроковая (точечно): Y {} до {}.",
                        NORMAL_Y_MIN, NORMAL_Y_MAX, BEDROCK_Y_MIN, BEDROCK_Y_MAX);
            } catch (Exception e) {
                normalOilBlock = null;
                bedrockOilBlock = null;
                ServerMod.LOGGER.error("[Oil] Не удалось найти нужные блоки HBM — генерация выключена.", e);
            }
        }
        return normalOilBlock != null && bedrockOilBlock != null;
    }

    private void loadSet() {
        if (saveFile == null || !saveFile.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(saveFile)))) {
            //noinspection InfiniteLoopStatement
            while (true) seeded.add(in.readLong());
        } catch (EOFException eof) {
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Oil] Не удалось загрузить {}", saveFile.getName(), e);
        }
    }

    public void save() {
        if (!dirty || saveFile == null) return;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(saveFile)))) {
            for (long k : seeded) out.writeLong(k);
            dirty = false;
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Oil] Не удалось сохранить {}", saveFile.getName(), e);
        }
    }

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
            ServerMod.LOGGER.error("[Oil] Не удалось загрузить {}", configFile.getName(), e);
        }
    }

    private void saveConfig() {
        if (configFile == null) return;
        try (PrintWriter w = new PrintWriter(configFile)) {
            w.println("enabled=" + enabled);
            w.println("interval=" + processInterval);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Oil] Не удалось сохранить {}", configFile.getName(), e);
        }
    }
}