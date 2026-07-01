package com.unnamedworld.command;

import com.unnamedworld.manager.PregenManager;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Прореживание уже сгенерированной руды (по умолчанию — алмазной на минусовых Y).
 * Можно указать ЛЮБОЙ блок (в т.ч. модовый) его registry-именем, напр. hbm:ore_uranium.
 * Лишнюю руду заменяет камнем.
 *
 *   /orethin <радиус> [процент] [maxY] [minY] [блок]
 *
 *   радиус   — в чанках вокруг тебя (реально обрабатываются только прогруженные)
 *   процент  — сколько % найденной руды убрать (по умолчанию 85)
 *   maxY/minY — диапазон высот (по умолчанию 0..-64, т.е. «минусовые»)
 *   блок     — id блока (по умолчанию minecraft:diamond_ore); числа и id можно в любом порядке
 *
 * Примеры:
 *   /orethin 6                       — убрать 85% алмазов на Y -64..0 в радиусе 6 чанков
 *   /orethin 8 90 16 -64 hbm:ore_uranium  — убрать 90% урановой руды HBM на Y -64..16
 */
public class CommandOreThin extends CommandBase {

    private static final int MAX_RADIUS = 32;
    private final Random rand = new Random();

    @Override public String getName() { return "orethin"; }
    @Override public List<String> getAliases() { return Arrays.asList("thinore"); }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/orethin <радиус> [%] [maxY] [minY] [блок] [блок_замены]  |  area|auto <полуразмер> [...] [блок] [блок_замены]  |  status | stop";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        // Фоновые подкоманды для большой области (20k×20k и т.п.) — через движок PregenManager.
        if (args.length >= 1) {
            switch (args[0].toLowerCase()) {
                case "status": reply(sender, TextFormatting.AQUA, PregenManager.INSTANCE.status()); return;
                case "stop":   reply(sender, TextFormatting.RED,  PregenManager.INSTANCE.stop());   return;
                case "area":   startArea(server, sender, args, false); return; // сразу, бюджет на тик
                case "auto":   startArea(server, sender, args, true);  return; // только когда нет игроков
            }
        }

        // Иначе — немедленный режим по уже ПРОГРУЖЕННЫМ чанкам вокруг тебя.
        if (!(sender instanceof EntityPlayerMP)) {
            reply(sender, TextFormatting.RED, "Команда — для игрока (центр берётся по тебе). Для всей области: /orethin area|auto <полуразмер>.");
            return;
        }
        if (args.length < 1) { reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return; }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        World world = player.world;

        List<Integer> n = new ArrayList<>();
        String blockId = null, fillId = null;
        for (String a : args) {
            if (isInt(a)) n.add(Integer.parseInt(a));
            else if (blockId == null) blockId = a;
            else if (fillId == null)  fillId = a;
        }
        if (n.isEmpty()) { reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return; }

        int radius  = clamp(n.get(0), 1, MAX_RADIUS);
        int percent = n.size() >= 2 ? clamp(n.get(1), 0, 100) : 85;
        int maxY    = n.size() >= 3 ? n.get(2) : 0;
        int minY    = n.size() >= 4 ? n.get(3) : -64;
        if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }

        Block target = resolveBlock(blockId, Blocks.DIAMOND_ORE, sender);
        if (target == null) return;
        Block fillBlock = resolveBlock(fillId, Blocks.STONE, sender);
        if (fillBlock == null) return;
        final IBlockState fill = fillBlock.getDefaultState();
        final double removeFrac = percent / 100.0;

        int pcx = player.chunkCoordX, pcz = player.chunkCoordZ;
        long found = 0, removed = 0, skippedUnloaded = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                int baseX = cx << 4, baseZ = cz << 4;
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int wx = baseX + dx, wz = baseZ + dz;
                        for (int y = maxY; y >= minY; y--) {
                            pos.setPos(wx, y, wz);
                            if (!world.isBlockLoaded(pos)) { skippedUnloaded++; break; } // куб не загружен — всю колонку пропускаем
                            if (world.getBlockState(pos).getBlock() != target) continue;
                            found++;
                            if (rand.nextDouble() < removeFrac) {
                                world.setBlockState(pos, fill, 2); // flag 2: клиентам да, апдейт соседей нет
                                removed++;
                            }
                        }
                    }
                }
            }
        }

        reply(sender, TextFormatting.GREEN, "Готово [" + target.getRegistryName() + " → "
                + fillBlock.getRegistryName() + "]. Найдено: "
                + TextFormatting.WHITE + found + TextFormatting.GREEN + ", убрано: "
                + TextFormatting.WHITE + removed + TextFormatting.GREEN + ", осталось: "
                + TextFormatting.WHITE + (found - removed) + ".");
        reply(sender, TextFormatting.GRAY, "Y " + minY + ".." + maxY + ", радиус " + radius
                + " чанк. Обрабатывались только прогруженные чанки — пройдись по области и повтори, если надо.");
    }

    /** /orethin area|auto <полуразмер> [процент] [maxY] [minY] [блок] — фоновое прореживание всей области. */
    private void startArea(MinecraftServer server, ICommandSender sender, String[] args, boolean whenEmpty)
            throws CommandException {
        List<Integer> n = new ArrayList<>();
        String blockId = null, fillId = null;
        for (int i = 1; i < args.length; i++) {
            if (isInt(args[i])) n.add(Integer.parseInt(args[i]));
            else if (blockId == null) blockId = args[i];
            else if (fillId == null)  fillId = args[i];
        }
        if (n.isEmpty()) {
            reply(sender, TextFormatting.RED, "Использование: /orethin " + args[0].toLowerCase()
                    + " <полуразмер> [процент=85] [maxY=0] [minY=-64] [блок] [блок_замены]  (для 20k×20k: полуразмер 10000)");
            return;
        }
        int half    = Math.max(1, n.get(0));
        int percent = n.size() >= 2 ? clamp(n.get(1), 0, 100) : 85;
        int maxY    = n.size() >= 3 ? n.get(2) : 0;
        int minY    = n.size() >= 4 ? n.get(3) : -64;

        Block target = resolveBlock(blockId, Blocks.DIAMOND_ORE, sender);
        if (target == null) return;
        Block fillBlock = resolveBlock(fillId, Blocks.STONE, sender);
        if (fillBlock == null) return;

        int dim = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).dimension : 0;
        WorldServer world = server.getWorld(dim);
        if (world == null) { reply(sender, TextFormatting.RED, "Нет измерения " + dim + "."); return; }
        reply(sender, TextFormatting.GREEN,
                PregenManager.INSTANCE.startThin(world, sender, 0, 0, half, minY, maxY, percent, target, fillBlock, whenEmpty));
    }

    /** Резолвит id блока; null id → дефолт. При неизвестном id шлёт ошибку и возвращает null. */
    private Block resolveBlock(String id, Block def, ICommandSender sender) {
        if (id == null) return def;
        Block b = Block.getBlockFromName(id);
        if (b == null) reply(sender, TextFormatting.RED, "Неизвестный блок: " + id
                + " (пример: minecraft:diamond_ore, samplemod112:deepslate)");
        return b;
    }

    /** Целое число (с возможным знаком)? Иначе считаем токен id-блока. */
    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) return false;
        int i = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
        if (i == s.length()) return false;
        for (; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "area", "auto", "status", "stop", "4", "6", "8");
        if (args.length == 2 && (args[0].equalsIgnoreCase("area") || args[0].equalsIgnoreCase("auto")))
            return getListOfStringsMatchingLastWord(args, "10000", "5000", "2000");
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "80", "85", "90", "100");
        return java.util.Collections.emptyList();
    }
}
