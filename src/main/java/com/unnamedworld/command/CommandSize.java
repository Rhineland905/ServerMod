package com.unnamedworld.command;

import com.unnamedworld.manager.SizeManager;
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

public class CommandSize extends CommandBase {

    @Override
    public String getName() { return "size"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/size [игрок] <число|reset>";
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
            sender.sendMessage(msg(TextFormatting.RED, "Из консоли укажи игрока: /size <игрок> <число>"));
            return;
        }

        float scale;
        if (value.equalsIgnoreCase("reset")) {
            scale = SizeManager.DEFAULT;
        } else {
            try {
                scale = Float.parseFloat(value.replace(',', '.'));
            } catch (NumberFormatException e) {
                sender.sendMessage(msg(TextFormatting.RED,
                        "Неверное число: '" + value + "'. Пример: /size " + target.getName() + " 1.5"));
                return;
            }
            if (scale <= 0) {
                sender.sendMessage(msg(TextFormatting.RED, "Размер должен быть больше нуля."));
                return;
            }
        }

        float applied = SizeManager.clamp(scale);
        SizeManager.INSTANCE.setScale(target, applied);

        String shown = String.format("%.2f", applied);
        sender.sendMessage(msg(TextFormatting.GREEN,
                "Размер игрока " + target.getName() + " теперь " + TextFormatting.GOLD + "x" + shown));
        if (sender != target) {
            target.sendMessage(msg(TextFormatting.GOLD,
                    "Твой размер изменён на x" + shown));
        }
        if (applied != scale) {
            sender.sendMessage(msg(TextFormatting.GRAY,
                    "(ограничено диапазоном " + SizeManager.MIN + "–" + SizeManager.MAX + ")"));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "0.5", "1", "1.5", "2", "3", "reset");
        }
        return Collections.emptyList();
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.RED, "Использование: " + getUsage(sender)));
        sender.sendMessage(msg(TextFormatting.GRAY,
                "Пример: /size Steve 2  — сделать игрока в 2 раза больше\n"
              + "/size 0.5  — уменьшить себя вдвое\n"
              + "/size Steve reset  — вернуть обычный размер"));
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
