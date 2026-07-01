package com.unnamedworld.command;

import com.unnamedworld.manager.WhitelistManager;
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
 * Управление вайтлистом сервера (свой, не ванильный).
 * /uwwhitelist <on|off|add|remove|list|status>   (только операторы)
 */
public class CommandWhitelist extends CommandBase {

    @Override
    public String getName() { return "uwwhitelist"; }

    @Override
    public List<String> getAliases() { return Arrays.asList("uwwl"); }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/uwwhitelist <on|off|add|remove|list|status>";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; } // только операторы

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        WhitelistManager wl = WhitelistManager.INSTANCE;
        if (args.length < 1) { sendUsage(sender); return; }

        switch (args[0].toLowerCase()) {

            case "on":
                wl.setEnabled(true);
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Вайтлист ВКЛЮЧЁН. Кто не в списке (кроме ОПов) — кикнут."));
                break;

            case "off":
                wl.setEnabled(false);
                sender.sendMessage(msg(TextFormatting.YELLOW, "Вайтлист выключен — заходят все."));
                break;

            case "add": {
                if (args.length < 2) { sendUsage(sender); return; }
                if (wl.add(args[1]))
                    sender.sendMessage(msg(TextFormatting.GREEN, "Добавлен в вайтлист: "
                            + TextFormatting.WHITE + args[1]));
                else
                    sender.sendMessage(msg(TextFormatting.YELLOW, args[1] + " уже в вайтлисте."));
                break;
            }

            case "remove":
            case "rem": {
                if (args.length < 2) { sendUsage(sender); return; }
                if (wl.remove(args[1]))
                    sender.sendMessage(msg(TextFormatting.GREEN, "Убран из вайтлиста: "
                            + TextFormatting.WHITE + args[1]));
                else
                    sender.sendMessage(msg(TextFormatting.YELLOW, args[1] + " не в вайтлисте."));
                break;
            }

            case "list": {
                List<String> names = wl.getNames();
                if (names.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY, "Вайтлист пуст."));
                } else {
                    sender.sendMessage(msg(TextFormatting.GOLD, "Вайтлист (" + names.size() + "): "
                            + TextFormatting.WHITE + String.join(", ", names)));
                }
                break;
            }

            case "status":
                sender.sendMessage(msg(TextFormatting.GOLD, "Вайтлист: "
                        + (wl.isEnabled() ? TextFormatting.GREEN + "включён" : TextFormatting.RED + "выключен")
                        + TextFormatting.GOLD + ", игроков в списке: " + TextFormatting.WHITE + wl.getNames().size()));
                break;

            default:
                sendUsage(sender);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "on", "off", "add", "remove", "list", "status");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("rem"))) {
            return getListOfStringsMatchingLastWord(args,
                    WhitelistManager.INSTANCE.getNames().toArray(new String[0]));
        }
        return Collections.emptyList();
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.RED, "Использование: " + getUsage(sender)));
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
