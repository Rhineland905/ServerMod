package com.unnamedworld.command;

import com.mojang.authlib.GameProfile;
import com.unnamedworld.manager.MuteManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Снятие мута: /unmute &lt;ник&gt;  (снимает и с чата, и с войса).
 */
public class CommandUnmute extends CommandBase {

    @Override public String getName() { return "unmute"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) { return "/unmute <ник>"; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) { reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return; }
        String targetName = args[0];

        // Резолвим UUID так же, как в /mute (онлайн -> кэш профилей).
        UUID uuid;
        String name;
        EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(targetName);
        if (online != null) {
            uuid = online.getUniqueID();
            name = online.getName();
        } else {
            GameProfile prof = server.getPlayerProfileCache().getGameProfileForUsername(targetName);
            if (prof == null || prof.getId() == null) {
                reply(sender, TextFormatting.RED, "Игрок \"" + targetName + "\" не найден.");
                return;
            }
            uuid = prof.getId();
            name = prof.getName();
        }

        if (MuteManager.INSTANCE.unmute(uuid)) {
            reply(sender, TextFormatting.GREEN, "Снят мут с " + TextFormatting.WHITE + name
                    + TextFormatting.GREEN + " (чат + войс).");
            MuteManager.INSTANCE.announceUnmuted(uuid, name);
        } else {
            reply(sender, TextFormatting.YELLOW, name + " и так не в муте.");
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            // подсказываем именно замученных
            List<String> names = new ArrayList<>();
            for (Map.Entry<UUID, MuteManager.Mute> e : MuteManager.INSTANCE.list())
                names.add(e.getValue().name);
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        return Collections.emptyList();
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }
}
