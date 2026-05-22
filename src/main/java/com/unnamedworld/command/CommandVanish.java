package com.unnamedworld.command;

import com.unnamedworld.manager.VanishManager;
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

public class CommandVanish extends CommandBase {

    @Override
    public String getName() { return "vanish"; }

    @Override
    public List<String> getAliases() { return Collections.singletonList("v"); }

    @Override
    public String getUsage(ICommandSender sender) { return "/vanish [player]"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP target;
        if (args.length >= 1) {
            target = getPlayer(server, sender, args[0]);
        } else if (sender instanceof EntityPlayerMP) {
            target = (EntityPlayerMP) sender;
        } else {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        VanishManager vm = VanishManager.INSTANCE;
        boolean wasVanished = vm.isVanished(target.getUniqueID());

        if (wasVanished) {
            vm.unvanish(target, server);
            target.sendMessage(new TextComponentString(
                    TextFormatting.GREEN + "Ты снова видим для других игроков."));
            if (sender != target) {
                sender.sendMessage(new TextComponentString(
                        TextFormatting.GREEN + target.getName() + " теперь виден всем."));
            }
        } else {
            vm.vanish(target, server);
            target.sendMessage(new TextComponentString(
                    TextFormatting.AQUA + "Ты в режиме ваниша. Тебя никто не видит."));
            if (sender != target) {
                sender.sendMessage(new TextComponentString(
                        TextFormatting.AQUA + target.getName() + " скрыт от всех игроков."));
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        return Collections.emptyList();
    }
}
