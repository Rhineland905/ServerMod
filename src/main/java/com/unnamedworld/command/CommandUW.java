package com.unnamedworld.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CommandUW extends CommandBase {

    public static final CommandUW INSTANCE = new CommandUW();

    @Override
    public String getName() { return "uw"; }

    @Override
    public List<String> getAliases() { return Arrays.asList("UnnamedWorld", "unnamedworld"); }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public String getUsage(ICommandSender sender) { return "/UnnamedWorld help"; }

    // /uw help  |  /UnnamedWorld help  |  /unnamedworld help
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || !"help".equalsIgnoreCase(args[0])) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Используй: /UnnamedWorld help"));
            return;
        }
        showHelp(sender, server);
    }

    // /help UnnamedWorld  |  /help uw
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onHelpCommand(CommandEvent event) {
        if (!"help".equalsIgnoreCase(event.getCommand().getName())) return;
        String[] params = event.getParameters();
        if (params.length < 1) return;
        String arg = params[0].toLowerCase();
        if (!arg.equals("unnamedworld") && !arg.equals("uw")) return;

        event.setCanceled(true);
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) showHelp(event.getSender(), server);
    }

    // --- Shared help output ---

    private void showHelp(ICommandSender sender, MinecraftServer server) {
        boolean isOp = !(sender instanceof EntityPlayerMP)
                || server.getPlayerList().getOppedPlayers()
                         .getEntry(((EntityPlayerMP) sender).getGameProfile()) != null;

        msg(sender, TextFormatting.GOLD, "" + TextFormatting.BOLD + "=== UnnamedWorld Commands ===");
        msg(sender, TextFormatting.YELLOW, "/register <password>  " + TextFormatting.GRAY + "- create account");
        msg(sender, TextFormatting.YELLOW, "/login <password>     " + TextFormatting.GRAY + "- login");
        msg(sender, TextFormatting.YELLOW, "/town pos1|pos2       " + TextFormatting.GRAY + "- выделить углы зоны");
        msg(sender, TextFormatting.YELLOW, "/town region create <название>  " + TextFormatting.GRAY + "- свой регион (в городе)");
        msg(sender, TextFormatting.YELLOW, "/town list|info <город>         " + TextFormatting.GRAY + "- города и регионы");

        if (isOp) {
            msg(sender, TextFormatting.AQUA, "" + TextFormatting.BOLD + "--- Ops only ---");
            msg(sender, TextFormatting.YELLOW, "/ability give|remove <player> <warden|demon|fish>");
            msg(sender, TextFormatting.YELLOW, "/ability list [player]");
            msg(sender, TextFormatting.YELLOW, "/noai <entity_id> <on|off>");
            msg(sender, TextFormatting.YELLOW, "/nospawn <entity_id> <on|off>  |  /nospawn list");
            msg(sender, TextFormatting.YELLOW, "/purge <entity_id> [block]");
            msg(sender, TextFormatting.YELLOW, "/endportal <on|off>");
            msg(sender, TextFormatting.YELLOW, "/memopt <info|clean|gc|set <items|xp|interval> <n>>");
            msg(sender, TextFormatting.YELLOW, "/vanish [player]  " + TextFormatting.GRAY + "- скрыть игрока (алиас: /v)");
            msg(sender, TextFormatting.YELLOW, "/chunkload [xzR [minY maxY] [dim]]  " + TextFormatting.GRAY + "- прогрузка (CC/vanilla)");
            msg(sender, TextFormatting.YELLOW, "/chunkload stop|status              " + TextFormatting.GRAY + "- отмена / прогресс");
            msg(sender, TextFormatting.YELLOW, "/town create|delete <название>      " + TextFormatting.GRAY + "- города (только админ)");
            msg(sender, TextFormatting.YELLOW, "/town region owner <город> <регион> <игрок>");
        }

        msg(sender, TextFormatting.DARK_GRAY,
                "/uw help  |  /UnnamedWorld help  |  /help UnnamedWorld");
    }

    private void msg(ICommandSender s, TextFormatting c, String t) {
        s.sendMessage(new TextComponentString(c + t));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "help");
        return Collections.emptyList();
    }
}
