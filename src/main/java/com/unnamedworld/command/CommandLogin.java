package com.unnamedworld.command;

import com.unnamedworld.manager.AuthManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

public class CommandLogin extends CommandBase {

    @Override
    public String getName() { return "login"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/login <пароль>"; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) sender;

        if (AuthManager.INSTANCE.isAuthenticated(player.getUniqueID())) {
            player.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Ты уже вошёл."));
            return;
        }

        if (!AuthManager.INSTANCE.isRegistered(player.getUniqueID())) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Ты не зарегистрирован. Используй /register <пароль>"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Использование: " + getUsage(sender)));
            return;
        }

        if (AuthManager.INSTANCE.login(player.getUniqueID(), args[0])) {
            player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Добро пожаловать, " + player.getName() + "!"));
        } else {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Неверный пароль."));
        }
    }
}
