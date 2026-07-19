package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Сам подсаживает растения модов в мир — в обход сломанного под CubicChunks
 * ворлдгена (CC не вызывает декораторы, на которых моды генерируют флору):
 *  • мистические цветы Botania (botania:flower, патчи как в родном ворлдгене);
 *  • растения HBM NTM (hbm:plant_flower): конопля — везде, наперстянка — леса,
 *    табак — джунгли, паслён — тёмный лес (биомы как в родном NTMFlowers).
 *
 * Каждый чанк оценивается ОДИН раз на каждый мод (наборы хранятся в
 * flower_seeded.dat / hbm_plants_seeded.dat), поэтому повторных подсадок и
 * переуплотнения после рестартов не происходит.
 *
 * Только обычный мир (dim 0). Если мод не установлен — его часть тихо выключается.
 */
public class FlowerSeedManager {

    public static final FlowerSeedManager INSTANCE = new FlowerSeedManager();

    private static final ResourceLocation FLOWER_RL = new ResourceLocation("botania", "flower");

    private static final int SEED_INTERVAL_TICKS  = 200;  // проход раз в 10 c
    private static final int SEED_RADIUS_CHUNKS   = 4;    // сколько чанков вокруг игрока оцениваем
    private static final int PATCH_INVERSE_CHANCE = 16;   // ~1/16 чанков получает патч (как worldgen.flower.patchChance)
    private static final int PATCH_SIZE           = 6;    // радиус разброса патча (блоки)
    private static final int PATCH_DENSITY        = 10;   // попыток поставить цветок в патче
    private static final int SCAN_UP             = 32;    // окно поиска поверхности вверх от игрока
    private static final int SCAN_DOWN           = 48;    // и вниз (CC-безопасно: не полагаемся на heightmap)
    private static final int SAVE_INTERVAL_TICKS = 1200;  // запись набора на диск не чаще раза в минуту

    // --- Растения HBM NTM (hbm:plant_flower, мета = тип растения) ---
    private static final ResourceLocation HBM_FLOWER_RL = new ResourceLocation("hbm", "plant_flower");
    private static final int HBM_PATCH_INVERSE_CHANCE = 10; // ~1/10 чанков получает патч
    private static final int HBM_PATCH_DENSITY        = 8;  // попыток на патч
    // Меты блока (порядок enum'а EnumFlowerPlantType в HBM; 2/3 — mustard willow, её не сеем)
    private static final int HBM_FOXGLOVE = 0, HBM_HEMP = 1, HBM_NIGHTSHADE = 4, HBM_TOBACCO = 5;

    private File saveFile;
    private File saveFileHbm;
    private boolean enabled = true;

    // Блоки резолвим лениво: на preInit они ещё не зарегистрированы.
    private boolean resolved = false;
    private Block flowerBlock;
    private boolean resolvedHbm = false;
    private Block hbmFlowerBlock;

    private final Set<Long> seeded    = new HashSet<>(); // оценённые чанки: Botania
    private final Set<Long> seededHbm = new HashSet<>(); // оценённые чанки: HBM
    private boolean dirty = false;
    private boolean dirtyHbm = false;
    private int tickCounter = 0;
    private int saveCounter = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "flower_seeded.dat");
        saveFileHbm = new File(configDir, "hbm_plants_seeded.dat");
        loadSet(saveFile, seeded);
        loadSet(saveFileHbm, seededHbm);
    }

    // --- Public API (для команды) ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { enabled = e; }
    public int seededCount() { return seeded.size(); }
    public int seededHbmCount() { return seededHbm.size(); }
    public boolean hasFlowerBlock() { return flowerBlock() != null; }
    public boolean hasHbmBlock() { return hbmFlower() != null; }

    /** Принудительно засеять радиус чанков вокруг игрока (команда /uwflowers seed). */
    public int forceSeed(WorldServer world, EntityPlayerMP player, int radiusChunks) {
        if (flowerBlock() == null && hbmFlower() == null) return -1;
        int placed = 0;
        if (flowerBlock() != null) placed += seedAround(world, player, radiusChunks, true);
        if (hbmFlower() != null)   placed += seedAroundHbm(world, player, radiusChunks, true);
        saveAll();
        return placed;
    }

    // --- Tick ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (++saveCounter >= SAVE_INTERVAL_TICKS) {
            saveCounter = 0;
            saveAll();
        }

        if (!enabled) return;
        if (++tickCounter < SEED_INTERVAL_TICKS) return;
        tickCounter = 0;

        boolean botania = flowerBlock() != null;
        boolean hbm     = hbmFlower() != null;
        if (!botania && !hbm) return; // сеять нечего

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        WorldServer overworld = server.getWorld(0);
        if (overworld == null) return;

        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (player.dimension != 0) continue;
            if (botania) seedAround(overworld, player, SEED_RADIUS_CHUNKS, false);
            if (hbm)     seedAroundHbm(overworld, player, SEED_RADIUS_CHUNKS, false);
        }
    }

    // --- Seeding ---

    private int seedAround(WorldServer world, EntityPlayerMP player, int radiusChunks, boolean force) {
        Block flower = flowerBlock();
        if (flower == null) return 0;

        Random rand = world.rand;
        int pcx = MathHelper.floor(player.posX) >> 4;
        int pcz = MathHelper.floor(player.posZ) >> 4;
        int feetY = MathHelper.floor(player.posY);

        int placed = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = ChunkPos.asLong(cx, cz);

                if (!force && seeded.contains(key)) continue;
                if (seeded.add(key)) dirty = true; // помечаем оценённым (даже если патч не выпал)

                // Чанк должен быть прогружен (около игрока — всегда)
                if (!world.isBlockLoaded(new BlockPos((cx << 4) + 8, feetY, (cz << 4) + 8))) continue;

                // ~1/16 чанков получает патч (force — гарантированно)
                if (!force && rand.nextInt(PATCH_INVERSE_CHANCE) != 0) continue;

                placed += placePatch(world, flower, cx, cz, feetY, rand);
            }
        }
        return placed;
    }

    private int placePatch(WorldServer world, Block flower, int cx, int cz, int refY, Random rand) {
        int baseX = (cx << 4) + rand.nextInt(16);
        int baseZ = (cz << 4) + rand.nextInt(16);
        int yHi = refY + SCAN_UP;
        int yLo = Math.max(1, refY - SCAN_DOWN);

        int placed = 0;
        for (int i = 0; i < PATCH_DENSITY; i++) {
            int x = baseX + rand.nextInt(PATCH_SIZE * 2 + 1) - PATCH_SIZE;
            int z = baseZ + rand.nextInt(PATCH_SIZE * 2 + 1) - PATCH_SIZE;

            BlockPos surface = findGrassSurface(world, x, z, yHi, yLo);
            if (surface == null) continue;
            if (!flower.canPlaceBlockAt(world, surface)) continue;

            // meta = индекс цвета (0..15 = white..black у BlockModFlower)
            world.setBlockState(surface, flower.getStateFromMeta(rand.nextInt(16)), 2);
            placed++;
        }
        return placed;
    }

    // --- Подсадка растений HBM NTM ---

    // Тот же принцип, что и для Botania, но со своим набором оценённых чанков:
    // старые чанки, уже «оценённые» для цветов, получают свежую оценку для HBM.
    private int seedAroundHbm(WorldServer world, EntityPlayerMP player, int radiusChunks, boolean force) {
        Block plant = hbmFlower();
        if (plant == null) return 0;

        Random rand = world.rand;
        int pcx = MathHelper.floor(player.posX) >> 4;
        int pcz = MathHelper.floor(player.posZ) >> 4;
        int feetY = MathHelper.floor(player.posY);

        int placed = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = ChunkPos.asLong(cx, cz);

                if (!force && seededHbm.contains(key)) continue;
                if (seededHbm.add(key)) dirtyHbm = true;

                if (!world.isBlockLoaded(new BlockPos((cx << 4) + 8, feetY, (cz << 4) + 8))) continue;
                if (!force && rand.nextInt(HBM_PATCH_INVERSE_CHANCE) != 0) continue;

                placed += placeHbmPatch(world, plant, cx, cz, feetY, rand);
            }
        }
        return placed;
    }

    private int placeHbmPatch(WorldServer world, Block plant, int cx, int cz, int refY, Random rand) {
        int meta = pickHbmMeta(world, cx, cz, rand);

        int baseX = (cx << 4) + rand.nextInt(16);
        int baseZ = (cz << 4) + rand.nextInt(16);
        int yHi = refY + SCAN_UP;
        int yLo = Math.max(1, refY - SCAN_DOWN);

        int placed = 0;
        for (int i = 0; i < HBM_PATCH_DENSITY; i++) {
            int x = baseX + rand.nextInt(PATCH_SIZE * 2 + 1) - PATCH_SIZE;
            int z = baseZ + rand.nextInt(PATCH_SIZE * 2 + 1) - PATCH_SIZE;

            BlockPos surface = findGrassSurface(world, x, z, yHi, yLo);
            if (surface == null) continue;
            if (!plant.canPlaceBlockAt(world, surface)) continue;

            world.setBlockState(surface, plant.getStateFromMeta(meta), 2);
            placed++;
        }
        return placed;
    }

    /** Тип растения для чанка — биомные правила как в родном ворлдгене HBM (NTMFlowers). */
    private int pickHbmMeta(WorldServer world, int cx, int cz, Random rand) {
        Biome biome = world.getBiome(new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8));
        List<Integer> candidates = new ArrayList<>(4);
        candidates.add(HBM_HEMP); // конопля — в любом биоме
        if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.FOREST)) candidates.add(HBM_FOXGLOVE);
        if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.JUNGLE)) candidates.add(HBM_TOBACCO);
        if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SPOOKY)) candidates.add(HBM_NIGHTSHADE); // тёмный лес
        return candidates.get(rand.nextInt(candidates.size()));
    }

    private Block hbmFlower() {
        if (!resolvedHbm) {
            hbmFlowerBlock = ForgeRegistries.BLOCKS.getValue(HBM_FLOWER_RL);
            resolvedHbm = true;
            if (hbmFlowerBlock == null) {
                ServerMod.LOGGER.warn("[Flowers] Блок hbm:plant_flower не найден — подсадка растений HBM выключена.");
            } else {
                ServerMod.LOGGER.info("[Flowers] Авто-подсадка растений HBM NTM активна (обход CubicChunks).");
            }
        }
        return hbmFlowerBlock;
    }

    /**
     * Ищет верхний блок травы с воздухом над ним в окне [yLo..yHi] столбца (x,z)
     * и возвращает позицию НАД ним (куда ставить цветок). null — если травы нет.
     * Поиск сверху вниз и относительно Y игрока — надёжно в кубических мирах,
     * где heightmap CubicChunks врёт.
     */
    private BlockPos findGrassSurface(WorldServer world, int x, int z, int yHi, int yLo) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, yHi, z);
        boolean airAbove = false;
        for (int y = yHi; y >= yLo; y--) {
            m.setPos(x, y, z);
            Block b = world.getBlockState(m).getBlock();
            if (b == Blocks.GRASS && airAbove) {
                return new BlockPos(x, y + 1, z);
            }
            airAbove = (b == Blocks.AIR);
        }
        return null;
    }

    private Block flowerBlock() {
        if (!resolved) {
            flowerBlock = ForgeRegistries.BLOCKS.getValue(FLOWER_RL);
            resolved = true;
            if (flowerBlock == null) {
                ServerMod.LOGGER.warn("[Flowers] Блок botania:flower не найден — авто-подсадка цветов выключена.");
            } else {
                ServerMod.LOGGER.info("[Flowers] Авто-подсадка мистических цветов Botania активна (обход CubicChunks).");
            }
        }
        return flowerBlock;
    }

    // --- Persistence (компактно: по 8 байт на ключ чанка) ---

    private void saveAll() {
        if (dirty && saveSet(saveFile, seeded)) dirty = false;
        if (dirtyHbm && saveSet(saveFileHbm, seededHbm)) dirtyHbm = false;
    }

    private void loadSet(File file, Set<Long> set) {
        if (file == null || !file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            //noinspection InfiniteLoopStatement
            while (true) set.add(in.readLong());
        } catch (EOFException eof) {
            // конец файла — норма
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Flowers] Не удалось загрузить {}", file.getName(), e);
        }
    }

    private boolean saveSet(File file, Set<Long> set) {
        if (file == null) return false;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            for (long k : set) out.writeLong(k);
            return true;
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Flowers] Не удалось сохранить {}", file.getName(), e);
            return false;
        }
    }
}
