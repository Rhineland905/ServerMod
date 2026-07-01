package com.unnamedworld.command;

import com.unnamedworld.manager.AutoRestartManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Управление авто-рестартом сервера (см. {@link AutoRestartManager}).
 *
 *   /autorestart every <минут>  — включить рестарт каждые N минут (предупреждает за 5 мин)
 *   /autorestart off            — выключить периодический рестарт
 *   /autorestart in <минут>     — разовый рестарт через N минут
 *   /autorestart now            — рестарт через 30 секунд (с предупреждением)
 *   /autorestart cancel         — отменить запланированный рестарт
 *   /autorestart status         — статус
 */
public class CommandAutoRestart extends CommandBase {

    @Override public String getName() { return "autorestart"; }
    @Override public List<String> getAliases() { return Arrays.asList("arestart"); }
    @Override public int getRequiredPermissionLevel() { return 4; } // рестарт — только админ/консоль

    @Override
    public String getUsage(ICommandSender sender) {
        return "/autorestart <every <мин> | off | in <мин> | now | cancel | warn <ЧЧ:ММ|off> | status>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        AutoRestartManager mgr = AutoRestartManager.INSTANCE;
        if (args.length == 0) { reply(sender, TextFormatting.AQUA, mgr.status()); return; }

        switch (args[0].toLowerCase()) {
            case "every": {
                int m = parseInt(args.length >= 2 ? args[1] : "0", 1);
                mgr.setEvery(m);
                reply(sender, TextFormatting.GREEN, "Авто-рестарт включён: каждые " + m
                        + " мин (предупреждение игрокам за 5 минут). " + mgr.status());
                break;
            }
            case "off":
                mgr.off();
                reply(sender, TextFormatting.YELLOW, "Периодический авто-рестарт выключен.");
                break;
            case "in": {
                int m = parseInt(args.length >= 2 ? args[1] : "0", 1);
                mgr.scheduleIn(m);
                reply(sender, TextFormatting.GREEN, "Разовый рестарт через " + m + " мин. " + mgr.status());
                break;
            }
            case "now":
                mgr.scheduleNow();
                reply(sender, TextFormatting.GREEN, "Рестарт через 30 секунд (игроки предупреждены).");
                break;
            case "cancel":
                if (mgr.isScheduled()) {
                    mgr.cancel();
                    reply(sender, TextFormatting.YELLOW, "Запланированный рестарт отменён.");
                } else {
                    reply(sender, TextFormatting.GRAY, "Нечего отменять — рестарт не запланирован.");
                }
                break;
            case "warn": {
                if (args.length < 2) { reply(sender, TextFormatting.RED, "Использование: /autorestart warn <ЧЧ:ММ | off>"); return; }
                if (args[1].equalsIgnoreCase("off")) {
                    mgr.setWarnDaily(-1);
                    reply(sender, TextFormatting.YELLOW, "Ежедневные предупреждения выключены.");
                    return;
                }
                int minute = parseHHMM(args[1]);
                if (minute < 0) { reply(sender, TextFormatting.RED, "Время в формате ЧЧ:ММ, например 00:00."); return; }
                mgr.setWarnDaily(minute);
                reply(sender, TextFormatting.GREEN, "Буду предупреждать игроков перед рестартом в "
                        + String.format("%02d:%02d", minute / 60, minute % 60)
                        + " (за 5 мин и далее обратный отсчёт). Сам рестарт выполняет панель хостинга.");
                break;
            }
            case "status":
                reply(sender, TextFormatting.AQUA, mgr.status());
                break;
            default:
                reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender));
        }
    }

    /** "ЧЧ:ММ" → минуты суток (0..1439); 24:00 = 00:00; -1 при ошибке. */
    private static int parseHHMM(String s) {
        String[] p = s.split(":");
        if (p.length != 2) return -1;
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            if (h == 24 && m == 0) h = 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (NumberFormatException e) { return -1; }
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "every", "off", "in", "now", "cancel", "warn", "status");
        if (args.length == 2 && (args[0].equalsIgnoreCase("every") || args[0].equalsIgnoreCase("in")))
            return getListOfStringsMatchingLastWord(args, "60", "120", "180", "360", "720");
        if (args.length == 2 && args[0].equalsIgnoreCase("warn"))
            return getListOfStringsMatchingLastWord(args, "00:00", "off");
        return Collections.emptyList();
    }
}
