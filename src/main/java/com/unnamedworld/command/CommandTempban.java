package com.unnamedworld.command;

import com.mojang.authlib.GameProfile;
import com.unnamedworld.manager.Durations;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.UserListBans;
import net.minecraft.server.management.UserListBansEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Временный бан для модераторов через ШТАТНЫЙ бан-лист Minecraft.
 *
 *   /tempban &lt;ник&gt; &lt;время&gt; [причина...]
 *
 * Время: число = минуты, либо 10s/30m/2h/1d, либо «perm» = навсегда.
 * Примеры:
 *   /tempban Steve 2h гриферство
 *   /tempban Alex 7d
 *   /tempban Bob perm чит
 *
 * Запись кладётся в стандартный banned-players.json, поэтому:
 *   • срок снимается ванилой АВТОМАТически по истечении;
 *   • снять досрочно — ванильным /pardon &lt;ник&gt;;
 *   • при попытке зайти игрок видит причину и дату окончания (ваниль сама пишет).
 */
public class CommandTempban extends CommandBase {

    @Override public String getName() { return "tempban"; }
    @Override public List<String> getAliases() { return Collections.singletonList("tban"); }
    @Override public int getRequiredPermissionLevel() { return 2; } // модераторы (как /mute)

    @Override
    public String getUsage(ICommandSender sender) {
        return "/tempban <ник> <время: 30m/2h/7d/perm> [причина]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) { reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return; }

        String targetName = args[0];

        long seconds = Durations.parse(args[1]);
        if (seconds == Durations.NOT_DURATION) {
            reply(sender, TextFormatting.RED, "Неверное время. Примеры: 30m, 2h, 7d, perm.");
            return;
        }
        Date expiry = seconds == 0 ? null : new Date(System.currentTimeMillis() + seconds * 1000L);

        StringBuilder rb = new StringBuilder();
        for (int i = 2; i < args.length; i++) { if (rb.length() > 0) rb.append(' '); rb.append(args[i]); }
        String reason = rb.length() == 0 ? "Забанен" : rb.toString();

        // Профиль игрока: онлайн -> его профиль, иначе кэш профилей (кто уже заходил).
        GameProfile profile;
        EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(targetName);
        if (online != null) {
            profile = online.getGameProfile();
        } else {
            profile = server.getPlayerProfileCache().getGameProfileForUsername(targetName);
            if (profile == null) {
                reply(sender, TextFormatting.RED, "Игрок \"" + targetName + "\" не найден (ещё ни разу не заходил?).");
                return;
            }
        }

        // Кладём запись в штатный бан-лист (он сам сохранится в banned-players.json и сам истечёт).
        UserListBans bans = server.getPlayerList().getBannedPlayers();
        bans.addEntry(new UserListBansEntry(profile, new Date(), sender.getName(), expiry, reason));

        String when = expiry == null ? "навсегда" : "на " + Durations.format(seconds);

        // Если онлайн — выкидываем сейчас с понятным сообщением.
        if (online != null) {
            online.connection.disconnect(new TextComponentString(
                    TextFormatting.RED + "Ты забанен " + when + ".\n"
                    + TextFormatting.WHITE + "Причина: " + reason
                    + (expiry == null ? "" : "\n" + TextFormatting.GRAY + "Разбан: " + expiry)));
        }

        reply(sender, TextFormatting.GREEN, "Забанен " + TextFormatting.WHITE + profile.getName()
                + TextFormatting.GREEN + " " + when + ". Причина: " + reason
                + (online == null ? " (был оффлайн)" : "") + ".");
        reply(sender, TextFormatting.GRAY, "Снять досрочно: /pardon " + profile.getName());
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        if (args.length == 2)
            return getListOfStringsMatchingLastWord(args, "30m", "1h", "2h", "1d", "7d", "perm");
        return Collections.emptyList();
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }
}
