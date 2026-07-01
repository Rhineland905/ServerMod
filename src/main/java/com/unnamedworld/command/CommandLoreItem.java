package com.unnamedworld.command;

import com.unnamedworld.manager.LoreCosmeticManager;
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

public class CommandLoreItem extends CommandBase {

    @Override
    public String getName() { return "loreitem"; }

    @Override
    public List<String> getAliases() { return Collections.singletonList("lore"); }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/loreitem <give|remove|list> <игрок> [cape|horns|propeller|catears]";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        LoreCosmeticManager cm = LoreCosmeticManager.INSTANCE;

        switch (args[0].toLowerCase()) {

            case "give": {
                if (args.length < 3) { sendUsage(sender); return; }
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                int bit = parseCosmetic(sender, args[2]);
                if (bit == 0) return;

                if (!cm.give(target, bit)) {
                    sender.sendMessage(msg(TextFormatting.YELLOW,
                            target.getName() + " уже имеет '" + args[2].toLowerCase() + "'."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Выдано " + TextFormatting.GOLD + display(args[2])
                        + TextFormatting.GREEN + " игроку " + target.getName()));
                target.sendMessage(msg(TextFormatting.GOLD,
                        "Тебе выдана лорная вещь: " + TextFormatting.WHITE + display(args[2])));
                break;
            }

            case "remove": {
                if (args.length < 3) { sendUsage(sender); return; }
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                int bit = parseCosmetic(sender, args[2]);
                if (bit == 0) return;

                if (!cm.remove(target, bit)) {
                    sender.sendMessage(msg(TextFormatting.YELLOW,
                            target.getName() + " не имеет '" + args[2].toLowerCase() + "'."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GREEN,
                        display(args[2]) + " снято с игрока " + target.getName()));
                target.sendMessage(msg(TextFormatting.RED,
                        "Лорная вещь " + display(args[2]) + " снята."));
                break;
            }

            case "list": {
                if (args.length < 2) { sendUsage(sender); return; }
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                int mask = cm.getMask(target.getUniqueID());
                if (mask == 0) {
                    sender.sendMessage(msg(TextFormatting.GRAY,
                            target.getName() + " не имеет лорных вещей."));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(TextFormatting.GOLD).append("Лорные вещи ").append(target.getName()).append(": ")
                  .append(TextFormatting.WHITE);
                if ((mask & LoreCosmeticManager.CAPE)      != 0) sb.append("плащ ");
                if ((mask & LoreCosmeticManager.HORNS)     != 0) sb.append("рога ");
                if ((mask & LoreCosmeticManager.PROPELLER) != 0) sb.append("пропеллер ");
                if ((mask & LoreCosmeticManager.CATEARS)   != 0) sb.append("котоушки ");
                sender.sendMessage(new TextComponentString(sb.toString().trim()));
                break;
            }

            default:
                sendUsage(sender);
        }
    }

    // ── Tab completion (подсказки) ────────────────────────────────────────────

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "give", "remove", "list");
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            return getListOfStringsMatchingLastWord(args, LoreCosmeticManager.IDS);
        }
        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int parseCosmetic(ICommandSender sender, String id) {
        int bit = LoreCosmeticManager.bitForId(id);
        if (bit == 0) {
            sender.sendMessage(msg(TextFormatting.RED,
                    "Неизвестная вещь '" + id + "'. Доступные: cape, horns, propeller, catears"));
        }
        return bit;
    }

    private String display(String id) {
        if ("cape".equalsIgnoreCase(id))      return "плащ (cape)";
        if ("horns".equalsIgnoreCase(id))     return "рога (horns)";
        if ("propeller".equalsIgnoreCase(id)) return "пропеллерная шапочка (propeller)";
        if ("catears".equalsIgnoreCase(id))   return "котячьи ушки (catears)";
        return id;
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.RED, "Использование: " + getUsage(sender)));
        sender.sendMessage(msg(TextFormatting.GRAY,
                "/loreitem give <игрок> cape — выдать плащ\n"
              + "/loreitem give <игрок> horns — выдать рога демона\n"
              + "/loreitem give <игрок> propeller — выдать пропеллерную шапочку (плавное падение)\n"
              + "/loreitem give <игрок> catears — выдать котячьи ушки\n"
              + "/loreitem remove <игрок> <cape|horns|propeller|catears> — снять\n"
              + "/loreitem list <игрок> — показать вещи игрока"));
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
