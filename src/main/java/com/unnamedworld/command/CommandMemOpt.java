package com.unnamedworld.command;

import com.unnamedworld.manager.MemoryManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CommandMemOpt extends CommandBase {

    @Override
    public String getName() { return "memopt"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/memopt <info|clean|gc|set <items|xp|interval> <value>>";
    }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        MemoryManager mm = MemoryManager.INSTANCE;

        // Default: info
        if (args.length == 0 || "info".equalsIgnoreCase(args[0])) {
            String stats = mm.getMemoryStats(server.worlds);
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + stats));
            sender.sendMessage(new TextComponentString(
                    TextFormatting.GRAY + "Limits — items/chunk: " + TextFormatting.WHITE + mm.itemsPerChunkLimit
                    + TextFormatting.GRAY + "  xp/chunk: " + TextFormatting.WHITE + mm.xpOrbsPerChunkLimit
                    + TextFormatting.GRAY + "  interval: " + TextFormatting.WHITE + mm.cleanupIntervalTicks + " ticks"));
            return;
        }

        switch (args[0].toLowerCase()) {

            case "clean": {
                int[] result = mm.runCleanupNow();
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + "Cleaned: " + TextFormatting.WHITE + result[0]
                        + TextFormatting.GREEN + " items, " + TextFormatting.WHITE + result[1]
                        + TextFormatting.GREEN + " XP orbs."));
                break;
            }

            case "gc": {
                long before = Runtime.getRuntime().freeMemory();
                System.gc();
                long freed = (Runtime.getRuntime().freeMemory() - before) / 1024 / 1024;
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + "GC hint sent. ~" + TextFormatting.WHITE + Math.max(freed, 0)
                        + " MB" + TextFormatting.GREEN + " potentially freed."));
                break;
            }

            case "set": {
                if (args.length < 3) {
                    sender.sendMessage(new TextComponentString(
                            TextFormatting.RED + "Usage: /memopt set <items|xp|interval> <value>"));
                    return;
                }
                int value;
                try { value = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) {
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "Value must be a number."));
                    return;
                }
                if (value < 1) {
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "Value must be >= 1."));
                    return;
                }
                switch (args[1].toLowerCase()) {
                    case "items":
                        mm.itemsPerChunkLimit = value;
                        break;
                    case "xp":
                        mm.xpOrbsPerChunkLimit = value;
                        break;
                    case "interval":
                        mm.cleanupIntervalTicks = value;
                        break;
                    default:
                        sender.sendMessage(new TextComponentString(
                                TextFormatting.RED + "Unknown param. Use: items, xp, interval"));
                        return;
                }
                mm.save();
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + "Set " + TextFormatting.WHITE + args[1].toLowerCase()
                        + TextFormatting.GREEN + " = " + TextFormatting.WHITE + value));
                break;
            }

            default:
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "info", "clean", "gc", "set");
        if (args.length == 2 && "set".equalsIgnoreCase(args[0]))
            return getListOfStringsMatchingLastWord(args, "items", "xp", "interval");
        return Collections.emptyList();
    }
}
