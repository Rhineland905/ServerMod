package com.unnamedworld.command;

import com.unnamedworld.manager.MobBoostManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Управление доспавнивателем враждебных мобов (см. {@link MobBoostManager}).
 *   /mobboost status              — состояние
 *   /mobboost on|off              — вкл/выкл
 *   /mobboost cap <N>             — локальный потолок враждебных вокруг игрока
 *   /mobboost interval <тиков>    — как часто делать проходы (20 тиков = 1 c)
 *   /mobboost test                — принудительный проход вокруг себя (с ответом)
 */
public class CommandMobBoost extends CommandBase {

    @Override public String getName() { return "mobboost"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mobboost <status|on|off|cap <N>|globalcap <N>|dist <N>|interval <тиков>|test|debug>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        MobBoostManager mgr = MobBoostManager.INSTANCE;
        String sub = args.length >= 1 ? args[0].toLowerCase() : "status";

        switch (sub) {
            case "on":
                mgr.setEnabled(true);
                reply(sender, TextFormatting.GREEN, "Доспавниватель мобов включён. " + mgr.status());
                break;
            case "off":
                mgr.setEnabled(false);
                reply(sender, TextFormatting.YELLOW, "Доспавниватель мобов выключен.");
                break;
            case "cap": {
                int n = parseInt(args.length >= 2 ? args[1] : "0", 1, 200);
                mgr.localCap = n;
                mgr.save();
                reply(sender, TextFormatting.GREEN, "Локальный потолок: " + n + " враждебных в радиусе "
                        + mgr.localRadius + " блоков.");
                break;
            }
            case "globalcap": {
                int n = parseInt(args.length >= 2 ? args[1] : "0", 50, 5000);
                mgr.globalCap = n;
                mgr.save();
                reply(sender, TextFormatting.GREEN, "Глобальный потолок монстров для доспавна: " + n + ".");
                break;
            }
            case "dist": {
                int n = parseInt(args.length >= 2 ? args[1] : "0", 32, 112);
                mgr.maxDist = n;
                mgr.save();
                reply(sender, TextFormatting.GREEN, "Кольцо спавна: 24-" + n + " блоков."
                        + (n > 96 ? TextFormatting.YELLOW + " Учти: дальше 32 блоков праздных мобов постепенно деспавнит игра." : ""));
                break;
            }
            case "interval": {
                int n = parseInt(args.length >= 2 ? args[1] : "0", 20, 6000);
                mgr.intervalTicks = n;
                mgr.save();
                reply(sender, TextFormatting.GREEN, "Интервал проходов: " + n + " тиков (" + (n / 20) + " c).");
                break;
            }
            case "debug": {
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED, "Команду debug может выполнить только игрок.");
                    return;
                }
                reply(sender, TextFormatting.AQUA, mgr.debugProbe((EntityPlayerMP) sender));
                break;
            }
            case "test": {
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED, "Команду test может выполнить только игрок.");
                    return;
                }
                int spawned = mgr.forcePass((EntityPlayerMP) sender);
                reply(sender, spawned > 0 ? TextFormatting.GREEN : TextFormatting.YELLOW,
                        "Проход выполнен, заспавнено: " + spawned
                        + (spawned == 0 ? " (светло/негде/плотность уже достаточная?)" : ""));
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
            return getListOfStringsMatchingLastWord(args, "status", "on", "off", "cap", "globalcap", "dist", "interval", "test", "debug");
        return Collections.emptyList();
    }
}
