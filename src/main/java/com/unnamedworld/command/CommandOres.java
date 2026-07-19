package com.unnamedworld.command;

import com.unnamedworld.manager.OreSeedManager;
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
import java.util.Collections;
import java.util.List;

/**
 * Управление досевом руд HBM NTM (см. {@link OreSeedManager}).
 *   /uwores status            — состояние (вкл/выкл, очередь, темп)
 *   /uwores on|off            — вкл/выкл фоновый досев (запоминается)
 *   /uwores seed [радиус]     — поставить чанки вокруг себя в очередь досева
 *   /uwores reset [радиус]    — снять отметки «обработан» вокруг себя и досеять
 *                               заново (после отката мира: отметки есть, руды нет)
 *   /uwores reset all         — забыть ВСЕ отметки: полный перепроход чанков
 *   /uwores seedat <x> <z> [радиус] — очередь вокруг точки (можно с консоли)
 *   /uwores check [x z]       — сколько блоков HBM в чанке (диагностика)
 *   /uwores interval <тиков>  — темп: не чаще 1 чанка за N тиков (мин. 5)
 */
public class CommandOres extends CommandBase {

    @Override public String getName() { return "uwores"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/uwores <status|on|off|seed [радиус]|reset [радиус]|all|seedat <x> <z>|check [x z]|interval <тиков>>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        OreSeedManager mgr = OreSeedManager.INSTANCE;
        String sub = args.length >= 1 ? args[0].toLowerCase() : "status";

        switch (sub) {
            case "on":
                mgr.setEnabled(true);
                reply(sender, TextFormatting.GREEN, "Досев руд HBM включён (безопасный темп). " + mgr.status());
                break;
            case "off":
                mgr.setEnabled(false);
                reply(sender, TextFormatting.YELLOW, "Досев руд HBM выключен.");
                break;
            case "interval": {
                int n = parseInt(args.length >= 2 ? args[1] : "0", 5, 600);
                mgr.setInterval(n);
                reply(sender, TextFormatting.GREEN, "Темп досева: 1 чанк за " + n + " тиков ("
                        + String.format("%.1f", n / 20.0) + " c). Меньше = быстрее, но выше нагрузка на свет CC.");
                break;
            }
            case "seed": {
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED, "Команду seed может выполнить только игрок.");
                    return;
                }
                EntityPlayerMP player = (EntityPlayerMP) sender;
                if (player.dimension != 0) {
                    reply(sender, TextFormatting.RED, "Досев руд работает только в обычном мире.");
                    return;
                }
                int radius = args.length >= 2 ? parseInt(args[1], 1, 8) : 4;
                int added = mgr.forceSeed((WorldServer) player.world, player, radius);
                if (added < 0) {
                    reply(sender, TextFormatting.RED, "HBM не найден — досев невозможен.");
                } else {
                    reply(sender, added > 0 ? TextFormatting.GREEN : TextFormatting.YELLOW,
                            "В очередь добавлено чанков: " + added
                            + (added == 0 ? " (вокруг всё уже обработано)." : ". Сольются по 1 чанку за раз — следи за TPS."));
                    if (!mgr.isEnabled()) {
                        reply(sender, TextFormatting.GOLD, "Досев сейчас ВЫКЛЮЧЕН — включи: /uwores on");
                    }
                }
                break;
            }
            case "reset": {
                if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
                    int cleared = mgr.resetAll();
                    reply(sender, TextFormatting.GREEN, "Полный сброс: забыто отметок " + cleared
                            + ". Алгоритм пройдёт по чанкам вокруг игроков заново."
                            + " Где руда уже есть — задвоится.");
                    break;
                }
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED, "С консоли доступен только «/uwores reset all».");
                    return;
                }
                EntityPlayerMP player = (EntityPlayerMP) sender;
                if (player.dimension != 0) {
                    reply(sender, TextFormatting.RED, "Досев руд работает только в обычном мире.");
                    return;
                }
                int radius = args.length >= 2 ? parseInt(args[1], 1, 8) : 4;
                int cleared = mgr.resetAround(player, radius);
                if (cleared < 0) {
                    reply(sender, TextFormatting.RED, "HBM не найден — досев невозможен.");
                } else {
                    reply(sender, TextFormatting.GREEN, "Снято отметок: " + cleared
                            + ", чанки в радиусе " + radius + " поставлены в очередь заново."
                            + " ВНИМАНИЕ: там, где руда уже есть, она задвоится — используй только на пустых участках.");
                    if (!mgr.isEnabled()) {
                        reply(sender, TextFormatting.GOLD, "Досев сейчас ВЫКЛЮЧЕН — включи: /uwores on");
                    }
                }
                break;
            }
            case "seedat": {
                if (args.length < 3) {
                    reply(sender, TextFormatting.RED, "Использование: /uwores seedat <x> <z> [радиус]");
                    return;
                }
                int bx = parseInt(args[1]);
                int bz = parseInt(args[2]);
                int radius = args.length >= 4 ? parseInt(args[3], 1, 8) : 4;
                int added = mgr.seedAt(bx, bz, radius);
                if (added < 0) {
                    reply(sender, TextFormatting.RED, "HBM не найден — досев невозможен.");
                } else {
                    reply(sender, TextFormatting.GREEN, "В очередь добавлено чанков: " + added
                            + " вокруг " + bx + "," + bz + ".");
                }
                break;
            }
            case "check": {
                WorldServer world = server.getWorld(0);
                int cx, cz;
                if (args.length >= 3) {
                    cx = parseInt(args[1]) >> 4;
                    cz = parseInt(args[2]) >> 4;
                } else if (sender instanceof EntityPlayerMP) {
                    EntityPlayerMP player = (EntityPlayerMP) sender;
                    cx = (int) Math.floor(player.posX) >> 4;
                    cz = (int) Math.floor(player.posZ) >> 4;
                } else {
                    reply(sender, TextFormatting.RED, "С консоли укажи координаты: /uwores check <x> <z>");
                    return;
                }
                reply(sender, TextFormatting.AQUA, mgr.checkChunk(world, cx, cz));
                break;
            }
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
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "status", "on", "off", "seed", "seedat", "reset", "check", "interval");
        if (args.length == 2 && args[0].equalsIgnoreCase("reset"))
            return getListOfStringsMatchingLastWord(args, "all");
        return Collections.emptyList();
    }
}
