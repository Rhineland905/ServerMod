package com.unnamedworld.command;

import com.mojang.authlib.GameProfile;
import com.unnamedworld.manager.Durations;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Мут игрока для модераторов — глушит СРАЗУ чат и голосовой чат.
 *
 *   /mute &lt;ник&gt; [время] [причина...]
 *
 * Время: число = минуты, либо с суффиксом s/m/h/d (10s, 30m, 2h, 1d),
 * либо «perm»/«навсегда» / без указания = навсегда.
 * Примеры:
 *   /mute Steve 30m спам в чате
 *   /mute Alex 1d
 *   /mute Bob оскорбления        (навсегда, причина «оскорбления»)
 */
public class CommandMute extends CommandBase {

    @Override public String getName() { return "mute"; }
    @Override public int getRequiredPermissionLevel() { return 2; } // модераторы/операторы

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mute <ник> [время: 30m/2h/1d/perm] [причина]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) { reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return; }

        String targetName = args[0];

        // Разбор: [время] [причина...]. Если args[1] — длительность, она идёт временем, иначе всё это причина.
        long durationSeconds = 0L; // 0 = навсегда
        int reasonStart = 1;
        if (args.length >= 2) {
            long parsed = Durations.parse(args[1]);
            if (parsed != Durations.NOT_DURATION) { durationSeconds = parsed; reasonStart = 2; }
        }
        StringBuilder rb = new StringBuilder();
        for (int i = reasonStart; i < args.length; i++) {
            if (rb.length() > 0) rb.append(' ');
            rb.append(args[i]);
        }
        String reason = rb.toString();

        // Резолвим игрока -> UUID + актуальное имя (онлайн, иначе кэш профилей).
        UUID uuid;
        String name;
        EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(targetName);
        if (online != null) {
            uuid = online.getUniqueID();
            name = online.getName();
        } else {
            GameProfile prof = server.getPlayerProfileCache().getGameProfileForUsername(targetName);
            if (prof == null || prof.getId() == null) {
                reply(sender, TextFormatting.RED, "Игрок \"" + targetName + "\" не найден (ещё ни разу не заходил?).");
                return;
            }
            uuid = prof.getId();
            name = prof.getName();
        }

        MuteManager.Mute m = MuteManager.INSTANCE.mute(uuid, name, durationSeconds, reason, sender.getName());

        // Сообщение модератору.
        String when = m.until == 0 ? "навсегда" : "на " + MuteManager.formatDuration(durationSeconds);
        reply(sender, TextFormatting.GREEN, "Замучен " + TextFormatting.WHITE + name + TextFormatting.GREEN
                + " (чат + войс) " + when + (reason.isEmpty() ? "" : ". Причина: " + reason) + ".");

        // Уведомляем самого игрока, если онлайн.
        if (online != null) {
            online.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Тебе выдан мут (чат и голосовой чат) " + when
                    + (reason.isEmpty() ? "" : ". Причина: " + reason) + "."));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        if (args.length == 2)
            return getListOfStringsMatchingLastWord(args, "10m", "30m", "1h", "1d", "perm");
        return Collections.emptyList();
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }
}
