package com.unnamedworld.command;

import com.unnamedworld.manager.SpawnManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class CommandNoSpawn extends CommandBase {

    @Override
    public String getName() { return "nospawn"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/nospawn <entity_id> <on|off>  |  /nospawn list"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) { sendUsage(sender); return; }

        if ("list".equalsIgnoreCase(args[0])) {
            Set<String> blocked = SpawnManager.INSTANCE.getBlocked();
            if (blocked.isEmpty()) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Нет заблокированных спавнов."));
            } else {
                StringBuilder sb = new StringBuilder(TextFormatting.GOLD + "Заблокированные спавны:\n");
                for (String id : blocked) {
                    sb.append(TextFormatting.YELLOW).append(" - ").append(id).append("\n");
                }
                sender.sendMessage(new TextComponentString(sb.toString().trim()));
            }
            return;
        }

        if (args.length < 2) { sendUsage(sender); return; }

        String mobId = SpawnManager.normalize(args[0]);
        boolean block  = args[1].equalsIgnoreCase("off");
        boolean unblock = args[1].equalsIgnoreCase("on");

        if (!block && !unblock) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Second argument must be 'on' or 'off'"));
            return;
        }

        if (EntityList.getClass(new ResourceLocation(mobId)) == null) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Unknown entity: " + TextFormatting.WHITE + mobId));
            return;
        }

        SpawnManager.INSTANCE.setBlocked(mobId, block);
        sender.sendMessage(new TextComponentString(
                TextFormatting.GRAY + "Spawn " + TextFormatting.WHITE + mobId
                + TextFormatting.GRAY + ": " + (block
                        ? TextFormatting.RED + "BLOCKED"
                        : TextFormatting.GREEN + "ALLOWED")));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            List<String> ids = new ArrayList<>();
            ids.add("list");
            for (EntityEntry entry : ForgeRegistries.ENTITIES.getValues()) {
                ResourceLocation rl = entry.getRegistryName();
                if (rl != null) ids.add(rl.toString());
            }
            return getListOfStringsMatchingLastWord(args, ids);
        }
        if (args.length == 2 && !"list".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        return Collections.emptyList();
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
    }
}
