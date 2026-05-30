package com.unnamedworld.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandChunkLoad extends CommandBase {

    public static final CommandChunkLoad INSTANCE = new CommandChunkLoad();

    private static final int DEFAULT_XZ_RADIUS = 16;
    private static int chunksPerTick = 1;
    private static final long TICK_BUDGET_NS = 45_000_000L; // 45 мс максимум за тик
    private static final int REPORT_INTERVAL   = 60;

    // ------------------------------------------------------------------ //
    // CubicChunks — рефлексия, инициализируется один раз при загрузке класса
    // Если CubicChunks не установлен — все поля остаются null, код не падает
    // ------------------------------------------------------------------ //
    private static final Class<?>  CC_WORLD_CLASS;
    private static final Method    CC_IS_CUBIC_WORLD;
    private static final Method    CC_GET_CUBE_CACHE;
    private static final Method    CC_GET_CUBE;
    private static final Object    CC_POPULATE;

    static {
        Class<?>  worldClass = null;
        Method    isCubic    = null;
        Method    getCache   = null;
        Method    getCube    = null;
        Object    populate   = null;
        try {
            worldClass = Class.forName(
                "io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer");
            Class<?> providerClass = Class.forName(
                "io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer");
            @SuppressWarnings("unchecked")
            Class<Enum> reqClass = (Class<Enum>) Class.forName(
                "io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer$Requirement");

            isCubic  = worldClass.getMethod("isCubicWorld");
            getCache = worldClass.getMethod("getCubeCache");
            getCube  = providerClass.getMethod("getCube", int.class, int.class, int.class, reqClass);
            populate = Enum.valueOf(reqClass, "POPULATE");
        } catch (Exception ignored) { /* CubicChunks не установлен */ }
        CC_WORLD_CLASS    = worldClass;
        CC_IS_CUBIC_WORLD = isCubic;
        CC_GET_CUBE_CACHE = getCache;
        CC_GET_CUBE       = getCube;
        CC_POPULATE       = populate;
    }

    private static boolean isCubicWorld(WorldServer world) {
        if (CC_WORLD_CLASS == null || !CC_WORLD_CLASS.isInstance(world)) return false;
        try { return (boolean) CC_IS_CUBIC_WORLD.invoke(world); }
        catch (Exception e) { return false; }
    }

    private static void loadCube(WorldServer world, int cx, int cy, int cz) {
        try {
            Object cache = CC_GET_CUBE_CACHE.invoke(world);
            CC_GET_CUBE.invoke(cache, cx, cy, cz, CC_POPULATE);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ //

    private boolean        running          = false;
    private boolean        cubicMode        = false;
    private List<int[]>    pending          = new ArrayList<>();
    private int            total            = 0;
    private int            loadedCount      = 0;
    private long           startMs          = 0;
    private ICommandSender initiator        = null;
    private WorldServer    targetWorld      = null;
    private int            ticksSinceReport = 0;

    @Override public String getName() { return "chunkload"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/chunkload [xzRadius [minY maxY] [dim]]  |  stop  |  status  |  speed [n]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {

        if (args.length > 0 && "speed".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                msg(sender, TextFormatting.YELLOW, "Текущая скорость: " + chunksPerTick + " чанков/тик.");
                return;
            }
            try {
                int val = Integer.parseInt(args[1]);
                if (val < 1) { msg(sender, TextFormatting.RED, "Скорость должна быть >= 1."); return; }
                chunksPerTick = val;
                msg(sender, TextFormatting.GREEN, "Скорость прогрузки: " + chunksPerTick + " чанков/тик (~"
                    + (chunksPerTick * 20) + "/сек).");
            } catch (NumberFormatException e) {
                msg(sender, TextFormatting.RED, "Неверное значение: " + args[1]);
            }
            return;
        }

        if (args.length > 0 && "stop".equalsIgnoreCase(args[0])) {
            if (!running) { msg(sender, TextFormatting.YELLOW, "Нет активной задачи прогрузки."); return; }
            running = false;
            pending.clear();
            msg(sender, TextFormatting.RED,
                "Прогрузка остановлена. Загружено " + loadedCount + "/" + total
                + (cubicMode ? " кубов." : " чанков."));
            return;
        }

        if (args.length > 0 && "status".equalsIgnoreCase(args[0])) {
            if (!running) { msg(sender, TextFormatting.YELLOW, "Нет активной задачи прогрузки."); return; }
            sender.sendMessage(buildProgressBar());
            return;
        }

        if (running) {
            msg(sender, TextFormatting.RED, "Прогрузка уже идёт. Используй /chunkload stop для отмены.");
            return;
        }

        // xzRadius
        int xzRadius = DEFAULT_XZ_RADIUS;
        if (args.length >= 1) {
            try { xzRadius = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) {
                msg(sender, TextFormatting.RED, "Неверный xzRadius: " + args[0]); return;
            }
            if (xzRadius < 1) {
                msg(sender, TextFormatting.RED, "XZ-радиус должен быть >= 1."); return;
            }
        }

        // minY / maxY (args[1] и args[2])
        int minY = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int dimArgPos = 1; // позиция аргумента dim по умолчанию

        if (args.length >= 3) {
            try {
                minY = Integer.parseInt(args[1]);
                maxY = Integer.parseInt(args[2]);
                dimArgPos = 3;
            } catch (NumberFormatException e) {
                msg(sender, TextFormatting.RED, "Неверные minY/maxY: " + args[1] + " " + args[2]); return;
            }
        }

        // dimension
        WorldServer world;
        if (args.length > dimArgPos) {
            int dimId;
            try { dimId = Integer.parseInt(args[dimArgPos]); }
            catch (NumberFormatException e) {
                msg(sender, TextFormatting.RED, "Неверный ID измерения: " + args[dimArgPos]); return;
            }
            world = server.getWorld(dimId);
            if (world == null) {
                msg(sender, TextFormatting.RED, "Неизвестное измерение: " + dimId); return;
            }
        } else {
            world = sender instanceof EntityPlayerMP
                ? (WorldServer) ((EntityPlayerMP) sender).world
                : server.getWorld(0);
        }

        // Дефолтные Y-границы из мира (если не заданы явно)
        if (minY == Integer.MIN_VALUE) minY = 0;
        if (maxY == Integer.MIN_VALUE) maxY = world.getHeight() - 1;

        if (minY >= maxY) {
            msg(sender, TextFormatting.RED, "minY (" + minY + ") должен быть меньше maxY (" + maxY + ")."); return;
        }

        boolean cc = isCubicWorld(world);

        BlockPos pos = sender.getPosition();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        pending = new ArrayList<>();
        if (cc) {
            int minCubeY = minY >> 4;
            int maxCubeY = maxY >> 4;
            for (int dx = -xzRadius; dx <= xzRadius; dx++)
                for (int dz = -xzRadius; dz <= xzRadius; dz++)
                    for (int cubeY = minCubeY; cubeY <= maxCubeY; cubeY++)
                        pending.add(new int[]{cx + dx, cubeY, cz + dz});
        } else {
            for (int dx = -xzRadius; dx <= xzRadius; dx++)
                for (int dz = -xzRadius; dz <= xzRadius; dz++)
                    pending.add(new int[]{cx + dx, 0, cz + dz});
        }

        total            = pending.size();
        loadedCount      = 0;
        startMs          = System.currentTimeMillis();
        initiator        = sender;
        targetWorld      = world;
        cubicMode        = cc;
        ticksSinceReport = 0;
        running          = true;

        int side = 2 * xzRadius + 1;
        String shape = cc
            ? side + "x" + ((maxY >> 4) - (minY >> 4) + 1) + "x" + side + " кубов (Y: " + minY + ".." + maxY + ")"
            : side + "x" + side + " чанков";

        sender.sendMessage(new TextComponentString(
            TextFormatting.GREEN + "Прогрузка [" + (cc ? "CubicChunks" : "vanilla") + "]: "
            + TextFormatting.WHITE + total + TextFormatting.GREEN + " " + shape
            + " в измерении " + TextFormatting.WHITE + world.provider.getDimension()
            + TextFormatting.GREEN + " вокруг " + TextFormatting.WHITE
            + "(" + (cx << 4) + ", " + (cz << 4) + ")"
        ));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) return;

        long tickStart = System.nanoTime();
        for (int i = 0; i < chunksPerTick && !pending.isEmpty(); i++) {
            int[] c = pending.remove(pending.size() - 1);
            if (cubicMode) {
                loadCube(targetWorld, c[0], c[1], c[2]);
            } else {
                targetWorld.getChunkProvider().provideChunk(c[0], c[2]);
            }
            loadedCount++;
            // Прерываем если тик уже занял слишком много времени
            if (System.nanoTime() - tickStart > TICK_BUDGET_NS) break;
        }

        ticksSinceReport++;
        boolean finished = pending.isEmpty();

        if (finished || ticksSinceReport >= REPORT_INTERVAL) {
            ticksSinceReport = 0;
            sendProgress();
        }

        if (finished) running = false;
    }

    private void sendProgress() {
        if (initiator == null) return;
        if (initiator instanceof EntityPlayerMP && ((EntityPlayerMP) initiator).connection == null) return;
        initiator.sendMessage(buildProgressBar());
    }

    private TextComponentString buildProgressBar() {
        double pct    = total == 0 ? 100.0 : loadedCount * 100.0 / total;
        int    BAR    = 20;
        int    filled = (int) Math.round(pct / 100.0 * BAR);

        StringBuilder sb = new StringBuilder();
        sb.append(TextFormatting.WHITE).append("[");
        for (int i = 0; i < BAR; i++)
            sb.append(i < filled ? "" + TextFormatting.GREEN + "█" : "" + TextFormatting.DARK_GRAY + "░");
        sb.append(TextFormatting.WHITE).append("] ");
        sb.append(TextFormatting.YELLOW).append(String.format("%.1f%%", pct));
        sb.append(TextFormatting.GRAY).append(" (").append(loadedCount).append("/").append(total).append(")");

        double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
        if (loadedCount > 0 && loadedCount < total) {
            int etaSec = (int) ((total - loadedCount) / (loadedCount / elapsedSec));
            sb.append(TextFormatting.AQUA).append("  ~").append(formatTime(etaSec));
        } else if (loadedCount >= total) {
            sb.append(TextFormatting.GREEN)
              .append("  Готово за ")
              .append(String.format("%.1f", elapsedSec)).append("с!");
        }

        return new TextComponentString(sb.toString());
    }

    private static String formatTime(int sec) {
        if (sec < 60) return sec + "с";
        return (sec / 60) + "м " + (sec % 60) + "с";
    }

    private static void msg(ICommandSender s, TextFormatting c, String t) {
        s.sendMessage(new TextComponentString(c + t));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "stop", "status", "speed", "8", "16", "32", "64");
        if (args.length == 2 && "speed".equalsIgnoreCase(args[0]))
            return getListOfStringsMatchingLastWord(args, "5", "10", "20", "50");
        if (args.length == 2)
            return getListOfStringsMatchingLastWord(args, "-64", "0");
        if (args.length == 3)
            return getListOfStringsMatchingLastWord(args, "256", "300", "320");
        if (args.length == 4)
            return getListOfStringsMatchingLastWord(args, "0", "-1", "1");
        return Collections.emptyList();
    }
}
