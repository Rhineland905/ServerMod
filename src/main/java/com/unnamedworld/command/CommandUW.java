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
        msg(sender, TextFormatting.YELLOW, "/report <текст>       " + TextFormatting.GRAY + "- сообщить админам о баге");
        msg(sender, TextFormatting.YELLOW, "/town pos1|pos2       " + TextFormatting.GRAY + "- выделить углы зоны");
        msg(sender, TextFormatting.YELLOW, "/town region create <название>  " + TextFormatting.GRAY + "- свой регион (в городе)");
        msg(sender, TextFormatting.YELLOW, "/town list|info <город>         " + TextFormatting.GRAY + "- города и регионы");

        if (isOp) {
            msg(sender, TextFormatting.AQUA, "" + TextFormatting.BOLD + "--- Только для операторов ---");

            msg(sender, TextFormatting.DARK_AQUA, "" + TextFormatting.BOLD + "• Модерация:");
            msg(sender, TextFormatting.YELLOW, "/mute <ник> [время] [причина]    " + TextFormatting.GRAY + "- мут чата и войса (время: 30m/2h/1d/perm)");
            msg(sender, TextFormatting.YELLOW, "/unmute <ник>  |  /mutelist       " + TextFormatting.GRAY + "- снять мут / список мутов");
            msg(sender, TextFormatting.YELLOW, "/tempban <ник> <время> [причина] " + TextFormatting.GRAY + "- временный бан (алиас /tban; снять: /pardon)");
            msg(sender, TextFormatting.YELLOW, "/vanish [игрок]                  " + TextFormatting.GRAY + "- скрыть игрока (алиас /v)");
            msg(sender, TextFormatting.YELLOW, "/uwwhitelist <on|off|add|remove|list|status>  " + TextFormatting.GRAY + "- вайтлист (алиас /uwwl)");
            msg(sender, TextFormatting.YELLOW, "/opmode  " + TextFormatting.GRAY + "- режим ОПа/игрока (своя инвентарка)");

            msg(sender, TextFormatting.DARK_AQUA, "" + TextFormatting.BOLD + "• Способности и косметика:");
            msg(sender, TextFormatting.YELLOW, "/ability give|remove <игрок> <warden|demon|fish|blaze|void|assassin>");
            msg(sender, TextFormatting.YELLOW, "/ability list [игрок]");
            msg(sender, TextFormatting.YELLOW, "/size [игрок] <число|reset>   " + TextFormatting.GRAY + "- размер модели");
            msg(sender, TextFormatting.YELLOW, "/hp [игрок] <HP|reset>        " + TextFormatting.GRAY + "- макс. здоровье");
            msg(sender, TextFormatting.YELLOW, "/loreitem give|remove|list <игрок> <cape|horns|propeller|catears>");

            msg(sender, TextFormatting.DARK_AQUA, "" + TextFormatting.BOLD + "• Мир и производительность:");
            msg(sender, TextFormatting.YELLOW, "/memopt <info|clean|gc|set <items|xp|interval|delay> <n>>  " + TextFormatting.GRAY + "- чистка дропа по таймеру");
            msg(sender, TextFormatting.YELLOW, "/entities [<dim>|top [N]|here [радиус]]  " + TextFormatting.GRAY + "- что наспавнено (алиас /ents)");
            msg(sender, TextFormatting.YELLOW, "/pregen <half>|auto <half>|at <x> <z> <half>|stop|resume|status  " + TextFormatting.GRAY + "- пре-ген");
            msg(sender, TextFormatting.YELLOW, "/orethin <радиус>|area <half>|auto <half> [%] [maxY minY] [блок] [замена]  " + TextFormatting.GRAY + "- прореживание руды");
            msg(sender, TextFormatting.YELLOW, "/uwflowers <status|on|off|seed [радиус]>  " + TextFormatting.GRAY + "- цветы Botania");
            msg(sender, TextFormatting.YELLOW, "/chunkload [xzR [minY maxY] [dim]] | stop | status  " + TextFormatting.GRAY + "- прогрузка");
            msg(sender, TextFormatting.YELLOW, "/nospawn <id> <on|off>|list  |  /noai <id> <on|off>  |  /purge <id> [block]");
            msg(sender, TextFormatting.YELLOW, "/endportal <on|off>");

            msg(sender, TextFormatting.DARK_AQUA, "" + TextFormatting.BOLD + "• Города и репорты:");
            msg(sender, TextFormatting.YELLOW, "/town create|delete <название>  |  /town member add|remove <игрок> [город]");
            msg(sender, TextFormatting.YELLOW, "/reports [list [all]|resolve <id>|delete <id>]");
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
