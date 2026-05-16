package com.example.command;

import com.example.manager.OpModeManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

public class CommandOpMode extends CommandBase {

    @Override
    public String getName() { return "opmode"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/opmode — переключить между режимом игрока и режимом ОПа"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; } // только для OPов

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Команда только для игроков."));
            return;
        }
        OpModeManager.INSTANCE.toggle((EntityPlayerMP) sender);
    }
}
