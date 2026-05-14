package com.example.command;

import com.example.manager.PortalManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CommandEndPortal extends CommandBase {

    @Override
    public String getName() { return "endportal"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/endportal <on|off>"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            boolean current = PortalManager.INSTANCE.isEndDisabled();
            sender.sendMessage(new TextComponentString(
                    TextFormatting.GRAY + "End portal: " + status(current)));
            return;
        }

        boolean disable = args[0].equalsIgnoreCase("off");
        boolean enable  = args[0].equalsIgnoreCase("on");

        if (!disable && !enable) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        PortalManager.INSTANCE.setEndDisabled(disable);
        sender.sendMessage(new TextComponentString(
                TextFormatting.GRAY + "End portal: " + status(disable)));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "on", "off");
        return Collections.emptyList();
    }

    private String status(boolean disabled) {
        return disabled
                ? TextFormatting.RED + "OFF" + TextFormatting.GRAY + " (заблокирован)"
                : TextFormatting.GREEN + "ON" + TextFormatting.GRAY + " (доступен)";
    }
}
