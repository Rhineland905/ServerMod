package com.unnamedworld.command;

import com.unnamedworld.manager.PregenManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /pregen — фоновый пре-ген большой области (см. {@link PregenManager}).
 *   /pregen <полуразмер> [minY maxY]              — квадрат вокруг (0,0), сторона = 2*полуразмер
 *   /pregen at <x> <z> <полуразмер> [minY maxY] [dim] — то же вокруг указанной точки
 *   /pregen stop | resume | status
 *
 * Пример «20k×20k вокруг центра, поверхность»: /pregen 10000 -64 160
 */
public class CommandPregen extends CommandBase {

    private static final int DEFAULT_MIN_Y = -64;
    private static final int DEFAULT_MAX_Y = 320;

    @Override public String getName() { return "pregen"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/pregen <полуразмер> [minY maxY] | auto <полуразмер> [minY maxY] | at <x> <z> <полуразмер> [minY maxY] [dim] | stop | resume | status";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        PregenManager mgr = PregenManager.INSTANCE;

        if (args.length == 0) { reply(sender, TextFormatting.YELLOW, getUsage(sender)); return; }
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "stop":   reply(sender, TextFormatting.RED, mgr.stop()); return;
            case "status": reply(sender, TextFormatting.AQUA, mgr.status()); return;
            case "resume": reply(sender, TextFormatting.GREEN, mgr.resume(worldOf(server, sender, -999), sender)); return;
            case "auto":
                // /pregen auto <полуразмер> [minY maxY] [dim] — ФОН: генерит, только когда нет игроков
                startCentered(server, sender, args, 1, true);
                return;
            case "at": {
                if (args.length < 4) { reply(sender, TextFormatting.RED, "Использование: /pregen at <x> <z> <полуразмер> [minY maxY] [dim]"); return; }
                int x = parseInt(args[1]);
                int z = parseInt(args[2]);
                int half = parseInt(args[3], 1);
                int minY = DEFAULT_MIN_Y, maxY = DEFAULT_MAX_Y, dimPos = 4;
                if (args.length >= 6 && isInt(args[4]) && isInt(args[5])) {
                    minY = parseInt(args[4]); maxY = parseInt(args[5]); dimPos = 6;
                }
                int dim = (args.length > dimPos && isInt(args[dimPos])) ? parseInt(args[dimPos])
                        : (sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).dimension : 0);
                startWith(server, sender, x, z, half, minY, maxY, dim, false);
                return;
            }
            default:
                // /pregen <полуразмер> [minY maxY] [dim] — сразу, центр (0,0)
                startCentered(server, sender, args, 0, false);
        }
    }

    /** Разбирает «<полуразмер> [minY maxY] [dim]» начиная с args[offset], центр (0,0). */
    private void startCentered(MinecraftServer server, ICommandSender sender,
                               String[] args, int offset, boolean whenEmpty) throws CommandException {
        if (args.length <= offset || !isInt(args[offset])) {
            reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return;
        }
        int half = parseInt(args[offset], 1);
        int minY = DEFAULT_MIN_Y, maxY = DEFAULT_MAX_Y, dimPos = offset + 1;
        if (args.length >= offset + 3 && isInt(args[offset + 1]) && isInt(args[offset + 2])) {
            minY = parseInt(args[offset + 1]); maxY = parseInt(args[offset + 2]); dimPos = offset + 3;
        }
        int dim = (args.length > dimPos && isInt(args[dimPos])) ? parseInt(args[dimPos])
                : (sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).dimension : 0);
        startWith(server, sender, 0, 0, half, minY, maxY, dim, whenEmpty);
    }

    private void startWith(MinecraftServer server, ICommandSender sender,
                           int x, int z, int half, int minY, int maxY, int dim, boolean whenEmpty) {
        if (minY >= maxY) { reply(sender, TextFormatting.RED, "minY должен быть меньше maxY."); return; }
        WorldServer world = server.getWorld(dim);
        if (world == null) { reply(sender, TextFormatting.RED, "Неизвестное измерение: " + dim); return; }
        String res = PregenManager.INSTANCE.start(world, sender, x, z, half, minY, maxY, whenEmpty);
        reply(sender, TextFormatting.GREEN, res);
        reply(sender, TextFormatting.GRAY, "Прогресс: /pregen status   •   Остановить: /pregen stop");
    }

    private WorldServer worldOf(MinecraftServer server, ICommandSender sender, int dim) {
        if (dim != -999) { WorldServer w = server.getWorld(dim); if (w != null) return w; }
        return sender instanceof EntityPlayerMP ? (WorldServer) ((EntityPlayerMP) sender).world : server.getWorld(0);
    }

    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, Arrays.asList("auto", "at", "stop", "resume", "status", "5000", "10000"));
        return Collections.emptyList();
    }
}
