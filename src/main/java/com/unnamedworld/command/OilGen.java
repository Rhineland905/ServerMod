package com.unnamedworld.command;

import com.unnamedworld.manager.OilSeedManager; // Исправлен импорт
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Управление досевом руд HBM NTM 1.12.2 (см. {@link OilSeedManager}).
 *   /oil status            — состояние (вкл/выкл, очередь, темп)
 *   /oil on|off            — вкл/выкл фоновый досев (запоминается)
 *   /oil seed [радиус]     — поставить чанки вокруг себя в очередь досева
 *   /oil reset [радиус]    — снять отметки «обработан» вокруг себя и досеять
 *                               заново (после отката мира: отметки есть, руды нет)
 *   /oil reset all         — забыть ВСЕ отметки: полный перепроход чанков
 *   /oil seedat <x> <z> [радиус] — очередь вокруг точки (можно с консоли)
 *   /oil check [x z]       — сколько блоков HBM в чанке (диагностика)
 *   /oil interval <тиков>  — темп: не чаще 1 чанка за N тиков (мин. 5)
 */
public class OilGen extends CommandBase {

    @Override
    public String getName() {
        return "oil";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/oil <status|on|off|seed [радиус]|reset [радиус]|reset all|seedat <x> <z> [радиус]|check [x z]|interval <тиков>>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        OilSeedManager mgr = OilSeedManager.INSTANCE; // Исправлено имя класса менеджера
        String sub = args.length >= 1 ? args[0].toLowerCase() : "status";

        switch (sub) {
            case "on":
                mgr.setEnabled(true);
                reply(sender, TextFormatting.GREEN, "Досев нефти HBM включён (безопасный темп). " + mgr.status());
                break;

            case "off":
                mgr.setEnabled(false);
                reply(sender, TextFormatting.YELLOW, "Досев нефти HBM выключен.");
                break;

            case "interval":
                if (args.length < 2) {
                    reply(sender, TextFormatting.RED, "Укажи интервал: /oil interval <тиков>");
                    return;
                }
                try {
                    int n = parseInt(args[1]);
                    if (n < 5) n = 5;
                    if (n > 600) n = 600;
                    mgr.setInterval(n);
                    reply(sender, TextFormatting.GREEN, "Темп досева: 1 чанк за " + n + " тиков ("
                            + String.format("%.1f", n / 20.0) + " сек). Меньше = быстрее, но выше нагрузка.");
                } catch (NumberFormatException e) {
                    throw new CommandException("Ошибка: укажи число!");
                }
                break;

            case "seed":
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED, "Команду seed может выполнить только игрок.");
                    return;
                }
                EntityPlayerMP playerSeed = (EntityPlayerMP) sender;
                if (playerSeed.dimension != 0) {
                    reply(sender, TextFormatting.RED, "Досев руд работает только в обычном мире (Overworld).");
                    return;
                }
                int radius = 4;
                if (args.length >= 2) {
                    try {
                        radius = parseInt(args[1]);
                        if (radius < 1) radius = 1;
                        if (radius > 8) radius = 8;
                    } catch (NumberFormatException e) {
                        throw new CommandException("Радиус должен быть числом от 1 до 8!");
                    }
                }
                int added = mgr.forceSeed((WorldServer) playerSeed.world, playerSeed, radius);
                if (added < 0) {
                    reply(sender, TextFormatting.RED, "HBM не найден — досев невозможен.");
                } else {
                    if (added > 0) {
                        reply(sender, TextFormatting.GREEN,
                                "В очередь добавлено чанков: " + added + " вокруг тебя. Сольются по 1 чанку за раз — следи за TPS.");
                    } else {
                        reply(sender, TextFormatting.YELLOW,
                                "В очередь добавлено чанков: 0 (вокруг всё уже обработано).");
                    }
                    if (!mgr.isEnabled()) {
                        reply(sender, TextFormatting.GOLD, "⚠ Досев ВЫКЛЮЧЕН! Включи: /oil on");
                    }
                }
                break;

            case "reset":
                if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
                    int cleared = mgr.resetAll();
                    reply(sender, TextFormatting.GREEN, "✓ Полный сброс: забыто " + cleared + " отметок.");
                    reply(sender, TextFormatting.YELLOW,
                            "Алгоритм пройдёт вокруг игроков заново. ⚠ Руда может задвоиться на уже обработанных участках!");
                    break;
                }
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED, "С консоли доступен только «/oil reset all».");
                    return;
                }
                EntityPlayerMP playerReset = (EntityPlayerMP) sender;
                if (playerReset.dimension != 0) {
                    reply(sender, TextFormatting.RED, "Досев руд работает только в обычном мире.");
                    return;
                }
                radius = 4;
                if (args.length >= 2) {
                    try {
                        radius = parseInt(args[1]);
                        if (radius < 1) radius = 1;
                        if (radius > 8) radius = 8;
                    } catch (NumberFormatException e) {
                        throw new CommandException("Радиус должен быть числом от 1 до 8!");
                    }
                }
                int cleared = mgr.resetAround(playerReset, radius);
                if (cleared < 0) {
                    reply(sender, TextFormatting.RED, "HBM не найден — досев невозможен.");
                } else {
                    reply(sender, TextFormatting.GREEN, "✓ Снято отметок: " + cleared);
                    reply(sender, TextFormatting.YELLOW,
                            "Чанки в радиусе " + radius + " поставлены в очередь заново.");
                    reply(sender, TextFormatting.RED,
                            "⚠ ВНИМАНИЕ: там, где руда уже есть, она задвоится! Используй только на пустых участках.");
                    if (!mgr.isEnabled()) {
                        reply(sender, TextFormatting.GOLD, "Досев ВЫКЛЮЧЕН! Включи: /oil on");
                    }
                }
                break;

            case "seedat":
                if (args.length < 3) {
                    reply(sender, TextFormatting.RED, "Использование: /oil seedat <x> <z> [радиус]");
                    return;
                }
                try {
                    int bx = parseInt(args[1]);
                    int bz = parseInt(args[2]);
                    radius = 4;
                    if (args.length >= 4) {
                        radius = parseInt(args[3]);
                        if (radius < 1) radius = 1;
                        if (radius > 8) radius = 8;
                    }
                    int added_at = mgr.seedAt(bx, bz, radius);
                    if (added_at < 0) {
                        reply(sender, TextFormatting.RED, "HBM не найден — досев невозможен.");
                    } else {
                        reply(sender, TextFormatting.GREEN, "✓ В очередь добавлено чанков: " + added_at
                                + " вокруг x=" + bx + ", z=" + bz + ".");
                    }
                } catch (NumberFormatException e) {
                    throw new CommandException("Координаты и радиус должны быть числами!");
                }
                break;

            case "check":
                WorldServer world = server.getWorld(0);
                if (world == null) {
                    reply(sender, TextFormatting.RED, "Обычный мир (Overworld) не загружен!");
                    return;
                }
                int cx, cz;
                try {
                    if (args.length >= 3) {
                        cx = parseInt(args[1]) >> 4;  // координаты → чанк
                        cz = parseInt(args[2]) >> 4;
                    } else if (sender instanceof EntityPlayerMP) {
                        EntityPlayerMP playerCheck = (EntityPlayerMP) sender;
                        cx = ((int) Math.floor(playerCheck.posX)) >> 4;
                        cz = ((int) Math.floor(playerCheck.posZ)) >> 4;
                    } else {
                        reply(sender, TextFormatting.RED, "С консоли укажи координаты: /oil check <x> <z>");
                        return;
                    }
                    reply(sender, TextFormatting.AQUA, mgr.checkChunk(world, cx, cz));
                } catch (NumberFormatException e) {
                    throw new CommandException("Координаты должны быть числами!");
                }
                break;

            case "status":
            default:
                reply(sender, TextFormatting.AQUA, mgr.status());
        }
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String[] options = {"status", "on", "off", "seed", "seedat", "reset", "check", "interval"};
            for (String opt : options) {
                if (opt.startsWith(args[0].toLowerCase())) {
                    completions.add(opt);
                }
            }
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            if ("all".startsWith(args[1].toLowerCase())) {
                List<String> compl = new ArrayList<>();
                compl.add("all");
                return compl;
            }
        }
        return Collections.emptyList();
    }
}