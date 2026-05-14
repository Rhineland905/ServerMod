package com.example.command;

import com.example.manager.AIManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandNoAI extends CommandBase {

    @Override
    public String getName() { return "noai"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/noai <mob_id> <on|off>"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        String mobId = AIManager.normalize(args[0]);
        boolean disable = args[1].equalsIgnoreCase("off");
        boolean enable  = args[1].equalsIgnoreCase("on");

        if (!disable && !enable) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Second argument must be 'on' or 'off'"));
            return;
        }

        if (EntityList.getClass(new ResourceLocation(mobId)) == null) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Unknown mob: " + TextFormatting.WHITE + mobId));
            return;
        }

        AIManager.INSTANCE.setMobAIDisabled(mobId, disable, server);
        sender.sendMessage(new TextComponentString(
                TextFormatting.GRAY + mobId + " AI: " + statusText(disable)));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            List<String> ids = new ArrayList<>();
            for (EntityEntry entry : ForgeRegistries.ENTITIES.getValues()) {
                ResourceLocation rl = entry.getRegistryName();
                if (rl != null) ids.add(rl.toString());
            }
            return getListOfStringsMatchingLastWord(args, ids);
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return Collections.emptyList();
    }

    private String statusText(boolean disabled) {
        return disabled
                ? TextFormatting.RED + "OFF" + TextFormatting.GRAY + " (AI disabled)"
                : TextFormatting.GREEN + "ON" + TextFormatting.GRAY + " (AI enabled)";
    }
}
