package com.unnamedworld.command;

import com.unnamedworld.manager.OpModeManager;
import com.unnamedworld.manager.TownManager;
import com.unnamedworld.manager.TownManager.Region;
import com.unnamedworld.manager.TownManager.Town;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CommandTown extends CommandBase {

    @Override
    public String getName() { return "town"; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/town <pos1|pos2|point|create|delete|list|info|region|member> ...";
    }

    // Доступна всем: игроки создают свои регионы.
    // Админские действия (города, владельцы) проверяются отдельно через checkAdmin().
    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        TownManager tm = TownManager.INSTANCE;

        switch (args[0].toLowerCase()) {

            case "pos1":
            case "pos2": {
                if (!(sender instanceof EntityPlayerMP)) {
                    sender.sendMessage(msg(TextFormatting.RED, "Команда только для игроков."));
                    return;
                }
                EntityPlayerMP player = (EntityPlayerMP) sender;
                boolean first = args[0].equalsIgnoreCase("pos1");
                int x = MathHelper.floor(player.posX);
                int z = MathHelper.floor(player.posZ);
                tm.setPos(player.getUniqueID(), first, x, z, player.dimension);
                sender.sendMessage(msg(TextFormatting.GREEN,
                        (first ? "Точка 1" : "Точка 2") + " установлена: "
                        + TextFormatting.YELLOW + x + ", " + z
                        + TextFormatting.GREEN + " (мир " + player.dimension + ")"));
                break;
            }

            case "point": {
                if (!(sender instanceof EntityPlayerMP)) {
                    sender.sendMessage(msg(TextFormatting.RED, "Команда только для игроков."));
                    return;
                }
                EntityPlayerMP player = (EntityPlayerMP) sender;
                UUID uuid = player.getUniqueID();
                String sub = args.length >= 2 ? args[1].toLowerCase() : "add";
                switch (sub) {
                    case "add": {
                        int x = MathHelper.floor(player.posX);
                        int z = MathHelper.floor(player.posZ);
                        tm.addPolyPoint(uuid, x, z, player.dimension);
                        int n = tm.getPolyPoints(uuid).size();
                        String tail = n < 3
                                ? "Нужно ещё " + (3 - n) + " для города."
                                : "Точек достаточно — /town create <название>.";
                        sender.sendMessage(msg(TextFormatting.GREEN,
                                "Точка #" + n + ": " + TextFormatting.YELLOW + x + ", " + z
                                + TextFormatting.GREEN + " (мир " + player.dimension + "). " + tail));
                        break;
                    }
                    case "undo": {
                        int[] removed = tm.undoPolyPoint(uuid);
                        if (removed == null) {
                            sender.sendMessage(msg(TextFormatting.RED, "Список точек пуст."));
                        } else {
                            sender.sendMessage(msg(TextFormatting.GREEN,
                                    "Убрана последняя точка (" + removed[0] + ", " + removed[1]
                                    + "). Осталось: " + tm.getPolyPoints(uuid).size() + "."));
                        }
                        break;
                    }
                    case "clear": {
                        tm.clearPolyPoints(uuid);
                        sender.sendMessage(msg(TextFormatting.GREEN, "Точки разметки очищены."));
                        break;
                    }
                    case "list": {
                        List<int[]> pts = tm.getPolyPoints(uuid);
                        if (pts.isEmpty()) {
                            sender.sendMessage(msg(TextFormatting.GRAY,
                                    "Точек нет. Добавляй по одной: /town point add"));
                            break;
                        }
                        sender.sendMessage(msg(TextFormatting.GOLD,
                                "--- Точки разметки (" + pts.size() + ") ---"));
                        for (int i = 0; i < pts.size(); i++) {
                            int[] p = pts.get(i);
                            sender.sendMessage(new TextComponentString(
                                    TextFormatting.AQUA + " #" + (i + 1)
                                    + TextFormatting.GRAY + ": " + p[0] + ", " + p[1]
                                    + " (мир " + p[2] + ")"));
                        }
                        break;
                    }
                    default:
                        sender.sendMessage(msg(TextFormatting.GRAY,
                                "/town point add — добавить точку (твоя позиция)\n"
                              + "/town point undo — убрать последнюю\n"
                              + "/town point clear — очистить\n"
                              + "/town point list — список точек"));
                }
                break;
            }

            case "create": {
                if (!checkAdmin(sender, "создавать города")) return;
                if (args.length < 2) {
                    sender.sendMessage(msg(TextFormatting.RED, "Использование: /town create <название>"));
                    return;
                }
                if (!(sender instanceof EntityPlayerMP)) {
                    sender.sendMessage(msg(TextFormatting.RED, "Команда только для игроков."));
                    return;
                }
                EntityPlayerMP player = (EntityPlayerMP) sender;
                UUID uuid = player.getUniqueID();
                String name = args[1];
                if (tm.getTown(name) != null) {
                    sender.sendMessage(msg(TextFormatting.RED, "Город '" + name + "' уже существует."));
                    return;
                }

                List<int[]> poly = tm.getPolyPoints(uuid);
                if (!poly.isEmpty() && poly.size() < 3) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Для города по точкам нужно минимум 3 (сейчас " + poly.size()
                            + "). Добавь ещё /town point add, либо /town point clear и используй pos1/pos2."));
                    return;
                }

                if (poly.size() >= 3) {
                    // ── Полигональный город ──
                    int dim = poly.get(0)[2];
                    int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    List<int[]> pts = new ArrayList<>();
                    for (int[] p : poly) {
                        if (p[2] != dim) {
                            sender.sendMessage(msg(TextFormatting.RED, "Точки разметки в разных мирах."));
                            return;
                        }
                        pts.add(new int[]{ p[0], p[1] });
                        minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                        minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
                    }
                    Town overlap = tm.findOverlapping(dim, minX, minZ, maxX, maxZ);
                    if (overlap != null) {
                        sender.sendMessage(msg(TextFormatting.RED,
                                "Зона пересекается с городом '" + overlap.name + "'."));
                        return;
                    }
                    Town town = tm.createTownPoly(name, dim, pts);
                    tm.clearPolyPoints(uuid);
                    tm.clearSelection(uuid);
                    sender.sendMessage(msg(TextFormatting.GREEN,
                            "Город " + TextFormatting.GOLD + name + TextFormatting.GREEN
                            + " создан по " + pts.size() + " точкам. Границы: "
                            + zone(town.x1, town.z1, town.x2, town.z2)));
                    return;
                }

                // ── Прямоугольный город (pos1/pos2) ──
                int[] sel = getSelection(tm, sender);
                if (sel == null) return;
                Town overlap = tm.findOverlapping(sel[4], sel[0], sel[1], sel[2], sel[3]);
                if (overlap != null) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Зона пересекается с городом '" + overlap.name + "'."));
                    return;
                }
                Town town = tm.createTown(name, sel[4], sel[0], sel[1], sel[2], sel[3]);
                tm.clearSelection(uuid);
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Город " + TextFormatting.GOLD + name + TextFormatting.GREEN
                        + " создан: " + zone(town.x1, town.z1, town.x2, town.z2)));
                break;
            }

            case "delete": {
                if (!checkAdmin(sender, "удалять города")) return;
                if (args.length < 2) {
                    sender.sendMessage(msg(TextFormatting.RED, "Использование: /town delete <название>"));
                    return;
                }
                if (!tm.deleteTown(args[1])) {
                    sender.sendMessage(msg(TextFormatting.RED, "Город '" + args[1] + "' не найден."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Город '" + args[1] + "' удалён вместе со всеми регионами."));
                break;
            }

            case "list": {
                List<Town> towns = tm.getTowns();
                if (towns.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY, "Городов пока нет."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GOLD, "--- Города (" + towns.size() + ") ---"));
                for (Town t : towns) {
                    sender.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + " " + t.name
                          + TextFormatting.GRAY + " — мир " + t.dim + ", "
                          + zone(t.x1, t.z1, t.x2, t.z2)
                          + (t.points.size() >= 3 ? " [полигон " + t.points.size() + "т]" : "")
                          + ", регионов: " + t.regions.size()));
                }
                break;
            }

            case "info": {
                if (args.length < 2) {
                    sender.sendMessage(msg(TextFormatting.RED, "Использование: /town info <название>"));
                    return;
                }
                Town town = requireTown(tm, sender, args[1]);
                if (town == null) return;

                sender.sendMessage(msg(TextFormatting.GOLD, "--- Город " + town.name + " ---"));
                sender.sendMessage(msg(TextFormatting.GRAY,
                        "Мир " + town.dim + ", зона " + zone(town.x1, town.z1, town.x2, town.z2)
                        + (town.points.size() >= 3
                                ? " (полигон из " + town.points.size() + " точек)"
                                : " (прямоугольник)")));
                if (town.members.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY, "Жителей нет."));
                } else {
                    sender.sendMessage(msg(TextFormatting.GOLD,
                            "Жители (" + town.members.size() + "): " + TextFormatting.WHITE
                            + String.join(", ", town.members.values())));
                }
                if (town.regions.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY, "Регионов нет."));
                } else {
                    sender.sendMessage(msg(TextFormatting.GOLD, "Регионы (" + town.regions.size() + "):"));
                    for (Region r : town.regions) {
                        sender.sendMessage(new TextComponentString(
                                TextFormatting.AQUA + " " + r.name
                              + TextFormatting.GRAY + " — " + zone(r.x1, r.z1, r.x2, r.z2)));
                    }
                }
                break;
            }

            case "region":
                executeRegion(server, sender, args);
                break;

            case "member":
                executeMember(server, sender, args);
                break;

            default:
                sendUsage(sender);
        }
    }

    // ── /town member ... ──────────────────────────────────────────────────────

    private void executeMember(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length < 2) {
            sendMemberUsage(sender);
            return;
        }

        TownManager tm = TownManager.INSTANCE;

        switch (args[1].toLowerCase()) {

            case "add": {
                if (!checkAdmin(sender, "добавлять жителей")) return;
                if (args.length < 3) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Использование: /town member add <игрок> [город]"));
                    return;
                }
                Town town = resolveTown(tm, sender, args, 3,
                        "/town member add <игрок> <город>");
                if (town == null) return;

                EntityPlayerMP target = getPlayer(server, sender, args[2]);
                if (town.isMember(target.getUniqueID())) {
                    sender.sendMessage(msg(TextFormatting.YELLOW,
                            target.getName() + " уже житель города " + town.name + "."));
                    return;
                }
                tm.addMember(town, target.getUniqueID().toString(), target.getName());
                sender.sendMessage(msg(TextFormatting.GREEN,
                        target.getName() + " теперь житель города " + town.name + "."));
                target.sendMessage(msg(TextFormatting.GOLD,
                        "Ты теперь житель города " + TextFormatting.YELLOW + town.name
                        + TextFormatting.GOLD + "! Можешь создавать там свои регионы."));
                break;
            }

            case "remove": {
                if (!checkAdmin(sender, "удалять жителей")) return;
                if (args.length < 3) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Использование: /town member remove <игрок> [город]"));
                    return;
                }
                Town town = resolveTown(tm, sender, args, 3,
                        "/town member remove <игрок> <город>");
                if (town == null) return;

                // Ищем по сохранённому имени — работает и для офлайн-игроков
                String uuid = null, properName = null;
                for (Map.Entry<String, String> en : town.members.entrySet()) {
                    if (en.getValue().equalsIgnoreCase(args[2])) {
                        uuid = en.getKey();
                        properName = en.getValue();
                        break;
                    }
                }
                if (uuid == null) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            args[2] + " не житель города " + town.name + "."));
                    return;
                }
                tm.removeMember(town, uuid);
                sender.sendMessage(msg(TextFormatting.GREEN,
                        properName + " больше не житель города " + town.name + "."));
                break;
            }

            case "list": {
                Town town = resolveTown(tm, sender, args, 2, "/town member list <город>");
                if (town == null) return;
                if (town.members.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY,
                            "В городе " + town.name + " пока нет жителей."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GOLD,
                        "--- Жители города " + town.name + " (" + town.members.size() + ") ---"));
                sender.sendMessage(msg(TextFormatting.WHITE,
                        String.join(", ", town.members.values())));
                break;
            }

            default:
                sendMemberUsage(sender);
        }
    }

    // ── /town region ... ──────────────────────────────────────────────────────

    private void executeRegion(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length < 2) {
            sendRegionUsage(sender);
            return;
        }

        TownManager tm = TownManager.INSTANCE;

        switch (args[1].toLowerCase()) {

            case "create": {
                if (args.length < 3) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Использование: /town region create <название>"));
                    return;
                }
                int[] sel = getSelection(tm, sender);
                if (sel == null) return;
                EntityPlayerMP player = (EntityPlayerMP) sender; // getSelection гарантирует игрока
                String name = args[2];

                // Город определяется автоматически по выделению
                Town town = tm.getTownAt(sel[4], sel[0], sel[1]);
                if (town == null) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Выделение вне города. Регионы можно создавать только внутри города."));
                    return;
                }
                if (sel[2] > town.x2 || sel[3] > town.z2) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Регион должен быть полностью внутри города "
                            + town.name + " " + zone(town.x1, town.z1, town.x2, town.z2) + "."));
                    return;
                }
                if (!isAdmin(sender) && !town.isMember(player.getUniqueID())) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Ты не житель города " + town.name + ". Жителей добавляет админ: "
                            + "/town member add " + player.getName() + " " + town.name));
                    return;
                }
                if (town.getRegion(name) != null) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Регион '" + name + "' уже есть в городе " + town.name + "."));
                    return;
                }
                for (Region r : town.regions) {
                    if (r.intersects(sel[0], sel[1], sel[2], sel[3])) {
                        sender.sendMessage(msg(TextFormatting.RED,
                                "Зона пересекается с регионом '" + r.name + "'."));
                        return;
                    }
                }

                tm.createRegion(town, name, sel[0], sel[1], sel[2], sel[3]);
                tm.clearSelection(player.getUniqueID());
                sender.sendMessage(msg(TextFormatting.GREEN,
                        "Регион " + TextFormatting.AQUA + name + TextFormatting.GREEN
                        + " создан в городе " + town.name + "."));
                break;
            }

            case "delete": {
                if (args.length < 3) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Использование: /town region delete <название> [город]"));
                    return;
                }
                String name = args[2];

                Town town;
                if (args.length >= 4) {
                    town = requireTown(tm, sender, args[3]);
                    if (town == null) return;
                } else {
                    town = townAt(tm, sender);
                    if (town == null) {
                        sender.sendMessage(msg(TextFormatting.RED,
                                "Ты не в городе. Укажи город: /town region delete <название> <город>"));
                        return;
                    }
                }

                Region region = town.getRegion(name);
                if (region == null) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Регион '" + name + "' не найден в городе " + town.name + "."));
                    return;
                }
                boolean member = sender instanceof EntityPlayerMP
                        && town.isMember(((EntityPlayerMP) sender).getUniqueID());
                if (!member && !isAdmin(sender)) {
                    sender.sendMessage(msg(TextFormatting.RED,
                            "Удалять регионы могут только жители города " + town.name + "."));
                    return;
                }

                tm.deleteRegion(town, name);
                sender.sendMessage(msg(TextFormatting.GREEN, "Регион '" + name + "' удалён."));
                break;
            }

            case "list": {
                Town town;
                if (args.length >= 3) {
                    town = requireTown(tm, sender, args[2]);
                    if (town == null) return;
                } else {
                    town = townAt(tm, sender);
                    if (town == null) {
                        sender.sendMessage(msg(TextFormatting.RED,
                                "Ты не в городе. Используй /town region list <город>"));
                        return;
                    }
                }
                if (town.regions.isEmpty()) {
                    sender.sendMessage(msg(TextFormatting.GRAY,
                            "В городе " + town.name + " нет регионов."));
                    return;
                }
                sender.sendMessage(msg(TextFormatting.GOLD,
                        "--- Регионы города " + town.name + " ---"));
                for (Region r : town.regions) {
                    sender.sendMessage(new TextComponentString(
                            TextFormatting.AQUA + " " + r.name
                          + TextFormatting.GRAY + " — " + zone(r.x1, r.z1, r.x2, r.z2)));
                }
                break;
            }

            default:
                sendRegionUsage(sender);
        }
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        TownManager tm = TownManager.INSTANCE;

        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args,
                    "pos1", "pos2", "point", "create", "delete", "list", "info", "region", "member");
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("info")) {
                return getListOfStringsMatchingLastWord(args, tm.getTownNames());
            }
            if (args[0].equalsIgnoreCase("point")) {
                return getListOfStringsMatchingLastWord(args, "add", "undo", "clear", "list");
            }
            if (args[0].equalsIgnoreCase("region")) {
                return getListOfStringsMatchingLastWord(args, "create", "delete", "list");
            }
            if (args[0].equalsIgnoreCase("member")) {
                return getListOfStringsMatchingLastWord(args, "add", "remove", "list");
            }
        }
        if (args[0].equalsIgnoreCase("region")) {
            String sub = args[1].toLowerCase();
            if (args.length == 3) {
                switch (sub) {
                    case "delete":
                        return getListOfStringsMatchingLastWord(args, regionNamesAt(tm, sender));
                    case "list":
                        return getListOfStringsMatchingLastWord(args, tm.getTownNames());
                }
            }
            if (args.length == 4 && sub.equals("delete")) {
                return getListOfStringsMatchingLastWord(args, tm.getTownNames());
            }
        }
        if (args[0].equalsIgnoreCase("member")) {
            String sub = args[1].toLowerCase();
            if (args.length == 3) {
                switch (sub) {
                    case "add":
                        return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                    case "remove": {
                        Town town = townAt(tm, sender);
                        if (town != null && !town.members.isEmpty()) {
                            return getListOfStringsMatchingLastWord(args, town.members.values());
                        }
                        return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                    }
                    case "list":
                        return getListOfStringsMatchingLastWord(args, tm.getTownNames());
                }
            }
            if (args.length == 4 && (sub.equals("add") || sub.equals("remove"))) {
                return getListOfStringsMatchingLastWord(args, tm.getTownNames());
            }
        }
        return Collections.emptyList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Админ = консоль, либо ОП с активным /opmode.
     * ОП в режиме игрока считается обычным игроком.
     */
    private static boolean isAdmin(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) return true;
        EntityPlayerMP p = (EntityPlayerMP) sender;
        return p.canUseCommand(2, "town")
                && OpModeManager.INSTANCE.isInOpMode(p.getUniqueID());
    }

    /** Проверка с сообщением: false = действие запрещено, игроку уже отправлена причина. */
    private boolean checkAdmin(ICommandSender sender, String action) {
        if (isAdmin(sender)) return true;
        if (sender instanceof EntityPlayerMP
                && ((EntityPlayerMP) sender).canUseCommand(2, "town")) {
            sender.sendMessage(msg(TextFormatting.RED,
                    "Ты в режиме игрока. Включи /opmode чтобы " + action + "."));
        } else {
            sender.sendMessage(msg(TextFormatting.RED,
                    "Только администратор может " + action + "."));
        }
        return false;
    }

    /** Город, в котором игрок стоит сейчас, или null. */
    @Nullable
    private static Town townAt(TownManager tm, ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) return null;
        EntityPlayerMP p = (EntityPlayerMP) sender;
        return tm.getTownAt(p.dimension, MathHelper.floor(p.posX), MathHelper.floor(p.posZ));
    }

    private static List<String> regionNamesAt(TownManager tm, ICommandSender sender) {
        Town town = townAt(tm, sender);
        if (town == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (Region r : town.regions) names.add(r.name);
        return names;
    }

    /**
     * Возвращает выделение игрока как {x1, z1, x2, z2, dim} (нормализовано)
     * или null с сообщением об ошибке.
     */
    @Nullable
    private int[] getSelection(TownManager tm, ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(msg(TextFormatting.RED, "Команда только для игроков."));
            return null;
        }
        UUID uuid = ((EntityPlayerMP) sender).getUniqueID();
        int[] p1 = tm.getPos(uuid, true);
        int[] p2 = tm.getPos(uuid, false);
        if (p1 == null || p2 == null) {
            sender.sendMessage(msg(TextFormatting.RED,
                    "Сначала выдели зону: встань в углы и используй /town pos1 и /town pos2."));
            return null;
        }
        if (p1[2] != p2[2]) {
            sender.sendMessage(msg(TextFormatting.RED,
                    "Точки выделения в разных мирах (" + p1[2] + " и " + p2[2] + ")."));
            return null;
        }
        return new int[]{
                Math.min(p1[0], p2[0]), Math.min(p1[1], p2[1]),
                Math.max(p1[0], p2[0]), Math.max(p1[1], p2[1]),
                p1[2]
        };
    }

    @Nullable
    private Town requireTown(TownManager tm, ICommandSender sender, String name) {
        Town town = tm.getTown(name);
        if (town == null) {
            sender.sendMessage(msg(TextFormatting.RED, "Город '" + name + "' не найден."));
        }
        return town;
    }

    /**
     * Город из аргумента args[idx], если он указан, иначе город, в котором
     * стоит игрок. null = город не определён, сообщение уже отправлено.
     */
    @Nullable
    private Town resolveTown(TownManager tm, ICommandSender sender, String[] args, int idx, String hint) {
        if (args.length > idx) {
            return requireTown(tm, sender, args[idx]);
        }
        Town town = townAt(tm, sender);
        if (town == null) {
            sender.sendMessage(msg(TextFormatting.RED, "Ты не в городе. Укажи город: " + hint));
        }
        return town;
    }

    private String zone(int x1, int z1, int x2, int z2) {
        return "(" + x1 + ", " + z1 + ") — (" + x2 + ", " + z2 + ")";
    }

    private void sendUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.RED, "Использование: " + getUsage(sender)));
        sender.sendMessage(msg(TextFormatting.GRAY,
                "/town pos1, /town pos2 — выделить углы зоны (прямоугольник)\n"
              + "/town point add|undo|clear|list — разметка города по точкам (полигон, ≥3)\n"
              + "/town create <название> — создать город из выделения/точек (админ)\n"
              + "/town delete <название> — удалить город (админ)\n"
              + "/town list — список городов\n"
              + "/town info <название> — информация о городе\n"
              + "/town region ... — регионы внутри города\n"
              + "/town member ... — жители города"));
    }

    private void sendRegionUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.GRAY,
                "/town region create <название> — создать регион из выделения (в своём городе)\n"
              + "/town region delete <название> [город] — удалить регион\n"
              + "/town region list [город] — список регионов"));
    }

    private void sendMemberUsage(ICommandSender sender) {
        sender.sendMessage(msg(TextFormatting.GRAY,
                "/town member add <игрок> [город] — добавить жителя (админ)\n"
              + "/town member remove <игрок> [город] — убрать жителя (админ)\n"
              + "/town member list [город] — список жителей"));
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
