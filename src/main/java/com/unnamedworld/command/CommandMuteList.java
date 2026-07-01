package com.unnamedworld.command;

import com.unnamedworld.manager.MuteManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Список текущих мутов: /mutelist
 */
public class CommandMuteList extends CommandBase {

    @Override public String getName() { return "mutelist"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) { return "/mutelist"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        List<Map.Entry<UUID, MuteManager.Mute>> list = MuteManager.INSTANCE.list();
        if (list.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Замученных нет."));
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "Муты (" + list.size() + "):"));
        for (Map.Entry<UUID, MuteManager.Mute> e : list) {
            MuteManager.Mute m = e.getValue();
            long left = MuteManager.INSTANCE.remainingSeconds(e.getKey());
            String time = left < 0 ? "навсегда" : MuteManager.formatDuration(left);
            sender.sendMessage(new TextComponentString(
                    TextFormatting.WHITE + " • " + m.name
                    + TextFormatting.GRAY + " — " + time
                    + (m.reason.isEmpty() ? "" : TextFormatting.GRAY + " (" + m.reason + ")")));
        }
    }
}
