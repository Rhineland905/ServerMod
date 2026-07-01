package com.unnamedworld.command;

import com.unnamedworld.manager.ModCheckManager;
import com.unnamedworld.manager.TelegramManager;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверка модов игроков (см. {@link ModCheckManager}).
 *
 *   /modcheck <игрок>          — показать моды зашедшего игрока (и подсветить подозрительные)
 *   /modcheck blacklist        — показать чёрный список modid
 *   /modcheck block <modid>    — добавить modid в чёрный список
 *   /modcheck unblock <modid>  — убрать modid из чёрного списка
 *   /modcheck reload           — перечитать modcheck.json
 */
public class CommandModCheck extends CommandBase {

    @Override public String getName() { return "modcheck"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/modcheck <игрок> | blacklist | block <modid> | unblock <modid> | reload | tg <test|reload|status>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        ModCheckManager mgr = ModCheckManager.INSTANCE;
        if (args.length == 0) { reply(sender, TextFormatting.YELLOW, getUsage(sender)); return; }

        switch (args[0].toLowerCase()) {
            case "blacklist": {
                Set<String> bl = mgr.getBlacklist();
                reply(sender, TextFormatting.AQUA, "Чёрный список (" + bl.size() + "): "
                        + (bl.isEmpty() ? "пусто" : String.join(", ", bl)));
                return;
            }
            case "block": {
                if (args.length < 2) { reply(sender, TextFormatting.RED, "Использование: /modcheck block <modid>"); return; }
                boolean ok = mgr.block(args[1]);
                reply(sender, ok ? TextFormatting.GREEN : TextFormatting.GRAY,
                        ok ? "Добавлено в чёрный список: " + args[1].toLowerCase()
                           : args[1].toLowerCase() + " уже в списке.");
                return;
            }
            case "unblock": {
                if (args.length < 2) { reply(sender, TextFormatting.RED, "Использование: /modcheck unblock <modid>"); return; }
                boolean ok = mgr.unblock(args[1]);
                reply(sender, ok ? TextFormatting.GREEN : TextFormatting.GRAY,
                        ok ? "Убрано из чёрного списка: " + args[1].toLowerCase()
                           : args[1].toLowerCase() + " не было в списке.");
                return;
            }
            case "reload": {
                mgr.reload();
                reply(sender, TextFormatting.GREEN, "modcheck.json перечитан. В списке " + mgr.getBlacklist().size() + " modid.");
                return;
            }
            case "tg": {
                TelegramManager tg = TelegramManager.INSTANCE;
                String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
                switch (sub) {
                    case "test":
                        if (tg.sendTest())
                            reply(sender, TextFormatting.GREEN, "Тест отправлен в Telegram. Проверь чат (и консоль на [Telegram] ошибки).");
                        else
                            reply(sender, TextFormatting.RED, "Telegram не настроен: задай enabled/botToken/chatId в telegram.json, затем /modcheck tg reload.");
                        return;
                    case "reload":
                        tg.reload();
                        reply(sender, TextFormatting.GREEN, "telegram.json перечитан. " + tg.status());
                        return;
                    default:
                        reply(sender, TextFormatting.AQUA, tg.status());
                        return;
                }
            }
            default: {
                // /modcheck <игрок> — только онлайн (моды читаются из активного подключения)
                EntityPlayerMP target = getPlayer(server, sender, args[0]);
                Map<String, String> mods = mgr.getClientModList(target);
                if (mods == null || mods.isEmpty()) {
                    reply(sender, TextFormatting.YELLOW, "Список модов для " + target.getName()
                            + " недоступен (ванильный/локальный клиент или Forge не прислал список).");
                    return;
                }
                List<String> ids = new ArrayList<>(mods.keySet());
                Collections.sort(ids);
                reply(sender, TextFormatting.AQUA, target.getName() + ": " + ids.size() + " модов:");
                reply(sender, TextFormatting.GRAY, String.join(", ", ids));

                List<String> hits = mgr.findSuspicious(ids);
                if (!hits.isEmpty())
                    reply(sender, TextFormatting.RED, ">>> Подозрительные: " + String.join(", ", hits));
                else
                    reply(sender, TextFormatting.GREEN, "Подозрительных модов не найдено.");
            }
        }
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(Arrays.asList("blacklist", "block", "unblock", "reload", "tg"));
            opts.addAll(Arrays.asList(server.getOnlinePlayerNames()));
            return getListOfStringsMatchingLastWord(args, opts);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unblock"))
            return getListOfStringsMatchingLastWord(args, new ArrayList<>(ModCheckManager.INSTANCE.getBlacklist()));
        if (args.length == 2 && args[0].equalsIgnoreCase("tg"))
            return getListOfStringsMatchingLastWord(args, "test", "reload", "status");
        return Collections.emptyList();
    }
}
