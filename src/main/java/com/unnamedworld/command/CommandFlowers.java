package com.unnamedworld.command;

import com.unnamedworld.manager.FlowerSeedManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /uwflowers — управление авто-подсадкой мистических цветов Botania
 * (обход сломанного под CubicChunks ворлдгена).
 *   /uwflowers status        — состояние
 *   /uwflowers on|off        — вкл/выкл авто-подсадку
 *   /uwflowers seed [радиус] — немедленно засеять чанки вокруг себя (обычный мир)
 */
public class CommandFlowers extends CommandBase {

    private static final int SEED_RADIUS_DEFAULT = 4;
    private static final int SEED_RADIUS_MAX      = 12;

    @Override
    public String getName() { return "uwflowers"; }

    @Override
    public List<String> getAliases() { return Collections.singletonList("uwflower"); }

    @Override
    public String getUsage(ICommandSender sender) { return "/uwflowers <status|on|off|seed [радиус]>"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        FlowerSeedManager mgr = FlowerSeedManager.INSTANCE;

        String sub = args.length >= 1 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "on":
                mgr.setEnabled(true);
                reply(sender, TextFormatting.GREEN + "Авто-подсадка цветов включена.");
                break;

            case "off":
                mgr.setEnabled(false);
                reply(sender, TextFormatting.YELLOW + "Авто-подсадка цветов выключена.");
                break;

            case "status":
                reply(sender, TextFormatting.GOLD + "Цветы Botania (обход CubicChunks):");
                reply(sender, TextFormatting.GRAY + " • авто-подсадка: "
                        + (mgr.isEnabled() ? TextFormatting.GREEN + "вкл" : TextFormatting.RED + "выкл"));
                reply(sender, TextFormatting.GRAY + " • блок botania:flower: "
                        + (mgr.hasFlowerBlock() ? TextFormatting.GREEN + "найден" : TextFormatting.RED + "НЕ найден"));
                reply(sender, TextFormatting.GRAY + " • оценено чанков: "
                        + TextFormatting.WHITE + mgr.seededCount());
                break;

            case "seed": {
                if (!(sender instanceof EntityPlayerMP)) {
                    reply(sender, TextFormatting.RED + "Команду seed может выполнить только игрок.");
                    return;
                }
                EntityPlayerMP player = (EntityPlayerMP) sender;
                if (player.dimension != 0 || !(player.world instanceof WorldServer)) {
                    reply(sender, TextFormatting.RED + "Сеять цветы можно только в обычном мире (overworld).");
                    return;
                }
                int radius = SEED_RADIUS_DEFAULT;
                if (args.length >= 2) {
                    radius = MathHelper.clamp(parseInt(args[1]), 1, SEED_RADIUS_MAX);
                }
                int placed = mgr.forceSeed((WorldServer) player.world, player, radius);
                if (placed < 0) {
                    reply(sender, TextFormatting.RED + "Botania не установлена или блок botania:flower не найден.");
                } else {
                    reply(sender, TextFormatting.GREEN + "Посажено цветков: " + TextFormatting.WHITE + placed
                            + TextFormatting.GREEN + " (радиус " + radius + " чанк.). "
                            + TextFormatting.GRAY + "Прогрузи/осмотрись вокруг.");
                }
                break;
            }

            default:
                reply(sender, TextFormatting.RED + "Использование: " + getUsage(sender));
        }
    }

    private void reply(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString(msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, Arrays.asList("status", "on", "off", "seed"));
        }
        return Collections.emptyList();
    }
}
