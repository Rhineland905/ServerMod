package com.unnamedworld.command;

import com.unnamedworld.manager.AuthManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

public class CommandRegister extends CommandBase {

    @Override
    public String getName() { return "register"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/register <пароль>"; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    // Регистрация доступна ВСЕМ (в т.ч. до входа). Жёстко разрешаем, чтобы никакие
    // настройки прав/уровней оператора не блокировали команду обычным игрокам.
    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) { return true; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) sender;

        if (AuthManager.INSTANCE.isRegistered(player.getUniqueID())) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Ты уже зарегистрирован. Используй /login <пароль>"));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + "Использование: " + getUsage(sender)));
            return;
        }

        AuthManager.INSTANCE.register(player.getUniqueID(), args[0]);
        AuthManager.INSTANCE.login(player.getUniqueID(), args[0]);
        player.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "Аккаунт создан! Добро пожаловать, " + player.getName() + "!"));
    }
}
