package com.unnamedworld.command;

import com.unnamedworld.manager.ReportManager;
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

public class CommandReport extends CommandBase {

    @Override
    public String getName() { return "report"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/report <сообщение>"; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(msg(TextFormatting.RED, "Команда только для игроков."));
            return;
        }
        if (args.length == 0) {
            sender.sendMessage(msg(TextFormatting.RED, "Использование: /report <сообщение>"));
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        String message = String.join(" ", args);
        int id = ReportManager.INSTANCE.addReport(player.getUniqueID(), player.getName(), message);

        sender.sendMessage(msg(TextFormatting.GREEN,
                "Репорт #" + id + " отправлен администраторам. Спасибо!"));

        // Уведомить всех онлайн-операторов
        String notify = TextFormatting.YELLOW + "[Репорт #" + id + "] "
                + TextFormatting.WHITE + player.getName()
                + TextFormatting.GRAY  + ": " + message;
        for (EntityPlayerMP online : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().getOppedPlayers().getEntry(online.getGameProfile()) != null) {
                online.sendMessage(new TextComponentString(notify));
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        return Collections.emptyList();
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
