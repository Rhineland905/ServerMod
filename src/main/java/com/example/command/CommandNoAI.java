package com.example.command;

import com.example.AIManager;
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

public class CommandNoAI extends CommandBase {

    @Override
    public String getName() {
        return "noai";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/noai <villager|bat> <on|off>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        String entity = args[0].toLowerCase();
        boolean disable = args[1].equalsIgnoreCase("off");
        boolean enable  = args[1].equalsIgnoreCase("on");

        if (!disable && !enable) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Second argument must be 'on' or 'off'"));
            return;
        }

        switch (entity) {
            case "villager":
                AIManager.INSTANCE.setVillagerAIDisabled(disable, server);
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GRAY + "Villager AI: " + statusText(disable)));
                break;
            case "bat":
                AIManager.INSTANCE.setBatAIDisabled(disable, server);
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GRAY + "Bat AI: " + statusText(disable)));
                break;
            default:
                sender.sendMessage(new TextComponentString(
                        TextFormatting.RED + "Unknown entity '" + entity + "'. Use: villager, bat"));
        }
    }

    private String statusText(boolean disabled) {
        return disabled
                ? TextFormatting.RED + "OFF" + TextFormatting.GRAY + " (AI disabled)"
                : TextFormatting.GREEN + "ON" + TextFormatting.GRAY + " (AI enabled)";
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "villager", "bat");
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "on", "off");
        return Collections.emptyList();
    }
}
