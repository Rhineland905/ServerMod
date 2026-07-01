package com.unnamedworld.command;

import com.unnamedworld.manager.HealthManager;
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

public class CommandHealth extends CommandBase {

    @Override
    public String getName() { return "hp"; }

    @Override
    public List<String> getAliases() {
        return java.util.Arrays.asList("sethp", "health");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/hp [игрок] <HP|reset>";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        EntityPlayerMP target;
        String value;

        if (args.length >= 2) {
            target = getPlayer(server, sender, args[0]);
            value  = args[1];
        } else if (sender instanceof EntityPlayerMP) {
            target = (EntityPlayerMP) sender;
            value  = args[0];
        } else {
            sender.sendMessage(msg(TextFormatting.RED, "Из консоли укажи игрока: /hp <игрок> <HP>"));
            return;
        }

        float hp;
        if (value.equalsIgnoreCase("reset")) {
            hp = HealthManager.DEFAULT;
        } else {
            try {
                hp = Float.parseFloat(value.replace(',', '.'));
            } catch (NumberFormatException e) {
                sender.sendMessage(msg(TextFormatting.RED,
                        "Неверное число: '" + value + "'. Пример: /hp " + target.getName() + " 10"));
                return;
            }
            if (hp <= 0) {
                sender.sendMessage(msg(TextFormatting.RED, "HP должно быть больше нуля."));
                return;
            }
        }

        float applied = HealthManager.clamp(hp);
        HealthManager.INSTANCE.setMaxHealth(target, applied);

        String shown = formatHp(applied);
        sender.sendMessage(msg(TextFormatting.GREEN,
                "Макс. HP игрока " + target.getName() + " теперь "
                + TextFormatting.GOLD + shown));
        if (sender != target) {
            target.sendMessage(msg(TextFormatting.GOLD, "Твоё макс. HP изменено: " + shown));
        }
        if (applied != hp) {
            sender.sendMessage(msg(TextFormatting.GRAY,
                    "(ограничено диапазоном " + formatHp(HealthManager.MIN)
                    + "–" + (int) HealthManager.MAX + " HP)"));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "1", "2", "6", "10", "20", "40", "reset");
        }
        return Collections.emptyList();
    }

    private String formatHp(float hp) {
        float hearts = hp / 2.0f;
        String heartsStr = (hearts == Math.floor(hearts))
                ? String.valueOf((int) hearts)
                : String.valueOf(hearts);
        String hpStr = (hp == Math.floor(hp)) ? String.valueOf((int) hp) : String.valueOf(hp);
        return hpStr + " HP (" + heartsStr + " ❤)";
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.RED, "Использование: " + getUsage(sender)));
        sender.sendMessage(msg(TextFormatting.GRAY,
                "HP в очках: 20 = обычные 10 сердец, 1 сердце = 2 HP.\n"
              + "/hp Steve 6 — оставить игроку 3 сердца\n"
              + "/hp 10 — выставить себе 5 сердец\n"
              + "/hp Steve reset — вернуть обычные 10 сердец"));
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
