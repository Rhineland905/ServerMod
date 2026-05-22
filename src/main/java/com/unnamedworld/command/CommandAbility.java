package com.unnamedworld.command;

import com.unnamedworld.ability.Ability;
import com.unnamedworld.manager.AbilityManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.*;

public class CommandAbility extends CommandBase {

    @Override
    public String getName() {
        return "ability";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ability <give|remove|list> [player] [ability]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // только операторы
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        switch (args[0].toLowerCase()) {

            case "give": {
                if (args.length < 3) { sendUsage(sender); return; }
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                Ability ability = parseAbility(sender, args[2]);
                if (ability == null) return;

                if (AbilityManager.INSTANCE.hasAbility(target.getUniqueID(), ability)) {
                    sender.sendMessage(msg(TextFormatting.YELLOW,
                            target.getName() + " уже имеет способность " + ability.id));
                    return;
                }
                AbilityManager.INSTANCE.giveAbility(target.getUniqueID(), ability);
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Выдана способность " + TextFormatting.GOLD + ability.id
                        + TextFormatting.GREEN + " игроку " + target.getName()));
                target.sendMessage(msg(TextFormatting.GOLD,
                        "Тебе выдана лорная способность: " + TextFormatting.WHITE + ability.id
                        + "\n" + TextFormatting.GRAY + ability.description));
                break;
            }

            case "remove": {
                if (args.length < 3) { sendUsage(sender); return; }
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                Ability ability = parseAbility(sender, args[2]);
                if (ability == null) return;

                if (!AbilityManager.INSTANCE.hasAbility(target.getUniqueID(), ability)) {
                    sender.sendMessage(msg(TextFormatting.YELLOW,
                            target.getName() + " не имеет способности " + ability.id));
                    return;
                }
                AbilityManager.INSTANCE.removeAbility(target.getUniqueID(), ability);
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Способность " + TextFormatting.GOLD + ability.id
                        + TextFormatting.GREEN + " снята с игрока " + target.getName()));
                target.sendMessage(msg(TextFormatting.RED,
                        "Лорная способность " + ability.id + " снята."));
                break;
            }

            case "list": {
                EntityPlayerMP target;
                if (args.length >= 2) {
                    target = getPlayer(server, sender, args[1]);
                } else if (sender instanceof EntityPlayerMP) {
                    target = (EntityPlayerMP) sender;
                } else {
                    sender.sendMessage(msg(TextFormatting.RED, "Укажи игрока: /ability list <player>"));
                    return;
                }

                Set<Ability> abilities = AbilityManager.INSTANCE.getAbilities(target.getUniqueID());
                if (abilities.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY,
                            target.getName() + " не имеет лорных способностей."));
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(TextFormatting.GOLD).append("Способности ").append(target.getName()).append(":\n");
                    for (Ability a : abilities) {
                        sb.append(TextFormatting.YELLOW).append(" - ").append(a.id)
                          .append(TextFormatting.GRAY).append(": ").append(a.description).append("\n");
                    }
                    sender.sendMessage(new TextComponentString(sb.toString().trim()));
                }
                break;
            }

            default:
                sendUsage(sender);
        }
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "give", "remove", "list");
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            String[] ids = Arrays.stream(Ability.values()).map(a -> a.id).toArray(String[]::new);
            return getListOfStringsMatchingLastWord(args, ids);
        }
        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Nullable
    private Ability parseAbility(ICommandSender sender, String id) {
        Ability ability = Ability.fromId(id);
        if (ability == null) {
            StringBuilder sb = new StringBuilder(TextFormatting.RED + "Неизвестная способность '" + id + "'. Доступные: ");
            for (Ability a : Ability.values()) sb.append(a.id).append(" ");
            sender.sendMessage(new TextComponentString(sb.toString().trim()));
        }
        return ability;
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.RED, "Использование: " + getUsage(sender)));
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
