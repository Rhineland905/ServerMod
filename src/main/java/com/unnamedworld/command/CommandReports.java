package com.unnamedworld.command;

import com.unnamedworld.manager.ReportManager;
import com.unnamedworld.manager.ReportManager.Report;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.*;

public class CommandReports extends CommandBase {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd.MM.yy HH:mm");

    @Override
    public String getName() { return "reports"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/reports [list [all] | resolve <id> | delete <id>]";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            boolean showAll = args.length >= 2 && args[1].equalsIgnoreCase("all");
            List<Report> list = showAll
                    ? ReportManager.INSTANCE.getAll()
                    : ReportManager.INSTANCE.getUnresolved();

            if (list.isEmpty()) {
                sender.sendMessage(msg(TextFormatting.GREEN,
                        showAll ? "Нет репортов." : "Нет нерешённых репортов."));
                return;
            }

            sender.sendMessage(msg(TextFormatting.GOLD,
                    "--- Репорты" + (showAll ? " (все)" : " (нерешённые)") + " ---"));
            for (Report r : list) {
                String status = r.resolved
                        ? TextFormatting.GREEN + "[OK]"
                        : TextFormatting.RED   + "[?] ";
                String line = status
                        + TextFormatting.YELLOW + " #" + r.id + " "
                        + TextFormatting.WHITE  + r.playerName
                        + TextFormatting.GRAY   + " [" + DATE_FMT.format(new Date(r.timestamp)) + "] "
                        + TextFormatting.WHITE  + r.message;
                sender.sendMessage(new TextComponentString(line));
            }
            return;
        }

        switch (args[0].toLowerCase()) {

            case "resolve": {
                if (args.length < 2) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Использование: /reports resolve <id>"));
                    return;
                }
                int id = parseId(sender, args[1]);
                if (id < 0) return;
                if (!ReportManager.INSTANCE.resolve(id)) {
                    Report r = ReportManager.INSTANCE.getById(id);
                    if (r == null) {
                        sender.sendMessage(msg(TextFormatting.RED, "Репорт #" + id + " не найден."));
                    } else {
                        sender.sendMessage(msg(TextFormatting.YELLOW,
                                "Репорт #" + id + " уже отмечен как исправленный."));
                    }
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Репорт #" + id + " отмечен как исправленный."));
                break;
            }

            case "delete": {
                if (args.length < 2) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Использование: /reports delete <id>"));
                    return;
                }
                int id = parseId(sender, args[1]);
                if (id < 0) return;
                if (!ReportManager.INSTANCE.delete(id)) {
                    sender.sendMessage(msg(TextFormatting.RED, "Репорт #" + id + " не найден."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GREEN, "Репорт #" + id + " удалён."));
                break;
            }

            default:
                sender.sendMessage(msg(TextFormatting.RED,
                        "Использование: " + getUsage(sender)));
        }
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "list", "resolve", "delete");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return getListOfStringsMatchingLastWord(args, "all");
        }
        if (args.length == 2) {
            boolean forResolve = args[0].equalsIgnoreCase("resolve");
            boolean forDelete  = args[0].equalsIgnoreCase("delete");
            if (forResolve || forDelete) {
                List<String> ids = new ArrayList<>();
                for (Report r : ReportManager.INSTANCE.getAll()) {
                    if (forDelete || !r.resolved) ids.add(String.valueOf(r.id));
                }
                return getListOfStringsMatchingLastWord(args, ids);
            }
        }
        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int parseId(ICommandSender sender, String s) {
        try {
            int id = Integer.parseInt(s);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            sender.sendMessage(msg(TextFormatting.RED, "Неверный ID: " + s));
            return -1;
        }
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
