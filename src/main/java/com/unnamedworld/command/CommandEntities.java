package com.unnamedworld.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Диагностика сущностей на сервере — что и где наспавнено (поиск лагающих ферм/хлама).
 *
 *   /entities                — сводка: всего + категории + топ типов + по измерениям
 *   /entities &lt;dim&gt;         — топ типов в конкретном измерении (0 — обычный, -1 — Ад, 1 — Энд)
 *   /entities top [N]        — чанки с наибольшим числом сущностей (по умолч. 10)
 *   /entities here [радиус]  — типы сущностей вокруг тебя (радиус в чанках/кубах по X/Z/Y, по умолч. 4)
 */
public class CommandEntities extends CommandBase {

    private static final int TOP_TYPES = 12;

    @Override public String getName() { return "entities"; }
    @Override public List<String> getAliases() { return Arrays.asList("ents", "uwentities"); }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/entities [<dim> | top [N] | here [радиус]]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) { summary(server, sender); return; }

        String a = args[0].toLowerCase();
        switch (a) {
            case "top":
                topChunks(server, sender, args.length >= 2 ? parseInt(args[1], 1) : 10);
                return;
            case "here":
                here(sender, args.length >= 2 ? parseInt(args[1], 0) : 4);
                return;
            default:
                int dim;
                try { dim = Integer.parseInt(args[0]); }
                catch (NumberFormatException e) { reply(sender, TextFormatting.RED, "Использование: " + getUsage(sender)); return; }
                WorldServer w = server.getWorld(dim);
                if (w == null) { reply(sender, TextFormatting.RED, "Нет загруженного измерения " + dim + "."); return; }
                dimBreakdown(sender, w);
        }
    }

    // --- /entities ---

    private void summary(MinecraftServer server, ICommandSender sender) {
        Map<String, Integer> types = new HashMap<>();
        int total = 0, items = 0, xp = 0, mobs = 0, players = 0;

        reply(sender, TextFormatting.GOLD, "=== Сущности по измерениям ===");
        for (WorldServer w : server.worlds) {
            int dimTotal = w.loadedEntityList.size();
            if (dimTotal == 0) continue;
            for (Entity e : w.loadedEntityList) {
                total++;
                if (e instanceof EntityItem)       items++;
                else if (e instanceof EntityXPOrb) xp++;
                else if (e instanceof EntityPlayerMP) players++;
                else if (e instanceof EntityLiving) mobs++;
                types.merge(typeKey(e), 1, Integer::sum);
            }
            reply(sender, TextFormatting.GRAY, " dim " + w.provider.getDimension()
                    + " (" + dimName(w) + "): " + TextFormatting.WHITE + dimTotal);
        }

        reply(sender, TextFormatting.AQUA, "Всего: " + TextFormatting.WHITE + total
                + TextFormatting.AQUA + "  |  мобы: " + TextFormatting.WHITE + mobs
                + TextFormatting.AQUA + "  предметы: " + TextFormatting.WHITE + items
                + TextFormatting.AQUA + "  опыт: " + TextFormatting.WHITE + xp
                + TextFormatting.AQUA + "  игроки: " + TextFormatting.WHITE + players);

        reply(sender, TextFormatting.GOLD, "Топ типов:");
        printTop(sender, types, TOP_TYPES, total);
    }

    // --- /entities <dim> ---

    private void dimBreakdown(ICommandSender sender, WorldServer w) {
        Map<String, Integer> types = new HashMap<>();
        for (Entity e : w.loadedEntityList) types.merge(typeKey(e), 1, Integer::sum);
        reply(sender, TextFormatting.GOLD, "Измерение " + w.provider.getDimension()
                + " (" + dimName(w) + "): " + TextFormatting.WHITE + w.loadedEntityList.size()
                + TextFormatting.GOLD + " сущностей. Топ типов:");
        printTop(sender, types, 20, w.loadedEntityList.size());
    }

    // --- /entities top [N] ---

    private void topChunks(MinecraftServer server, ICommandSender sender, int n) {
        // ключ "dim|cx|cz" -> кол-во и доминирующий тип
        Map<String, int[]> count = new HashMap<>();          // -> {count}
        Map<String, Map<String, Integer>> byType = new HashMap<>();

        for (WorldServer w : server.worlds) {
            int dim = w.provider.getDimension();
            for (Entity e : w.loadedEntityList) {
                String key = dim + "|" + e.chunkCoordX + "|" + e.chunkCoordZ;
                count.computeIfAbsent(key, k -> new int[1])[0]++;
                byType.computeIfAbsent(key, k -> new HashMap<>()).merge(typeKey(e), 1, Integer::sum);
            }
        }

        List<Map.Entry<String, int[]>> list = new ArrayList<>(count.entrySet());
        list.sort((x, y) -> y.getValue()[0] - x.getValue()[0]);

        reply(sender, TextFormatting.GOLD, "Чанки с наибольшим числом сущностей (топ " + n + "):");
        int shown = 0;
        for (Map.Entry<String, int[]> e : list) {
            if (shown++ >= n) break;
            String[] p = e.getKey().split("\\|");
            int dim = Integer.parseInt(p[0]);
            int cx = Integer.parseInt(p[1]), cz = Integer.parseInt(p[2]);
            Map.Entry<String, Integer> dom = topEntry(byType.get(e.getKey()));
            reply(sender, TextFormatting.WHITE, " " + e.getValue()[0]
                    + TextFormatting.GRAY + " — dim " + dim + " чанк " + cx + "," + cz
                    + " (центр X=" + (cx * 16 + 8) + " Z=" + (cz * 16 + 8) + ")"
                    + (dom != null ? TextFormatting.DARK_GRAY + "  [" + shortName(dom.getKey()) + " ×" + dom.getValue() + "]" : ""));
        }
        if (shown == 0) reply(sender, TextFormatting.GRAY, "Сущностей не найдено.");
    }

    // --- /entities here [радиус] ---

    private void here(ICommandSender sender, int radius) {
        if (!(sender instanceof EntityPlayerMP)) {
            reply(sender, TextFormatting.RED, "Эта подкоманда — только для игрока.");
            return;
        }
        EntityPlayerMP p = (EntityPlayerMP) sender;
        WorldServer w = (WorldServer) p.world;
        int pcx = p.chunkCoordX, pcz = p.chunkCoordZ;
        // Куб/чанк по высоте (16 блоков) — на CubicChunks вертикаль огромная, без этой
        // проверки сущность в 200 блоках над/под тобой засчиталась бы как "рядом".
        int pcy = net.minecraft.util.math.MathHelper.floor(p.posY) >> 4;

        Map<String, Integer> types = new HashMap<>();
        int total = 0;
        for (Entity e : w.loadedEntityList) {
            if (Math.abs(e.chunkCoordX - pcx) > radius || Math.abs(e.chunkCoordZ - pcz) > radius) continue;
            int ecy = net.minecraft.util.math.MathHelper.floor(e.posY) >> 4;
            if (Math.abs(ecy - pcy) > radius) continue;
            types.merge(typeKey(e), 1, Integer::sum);
            total++;
        }
        reply(sender, TextFormatting.GOLD, "Вокруг тебя (±" + radius + " чанк, dim " + w.provider.getDimension()
                + "): " + TextFormatting.WHITE + total + TextFormatting.GOLD + " сущностей:");
        printTop(sender, types, 20, total);
    }

    // --- Вспомогательное ---

    private static String typeKey(Entity e) {
        ResourceLocation rl = EntityList.getKey(e);
        if (rl != null) return rl.toString();
        if (e instanceof EntityPlayerMP) return "minecraft:player";
        return e.getClass().getSimpleName();
    }

    /** Убирает префикс "minecraft:" для краткости. */
    private static String shortName(String key) {
        return key.startsWith("minecraft:") ? key.substring(10) : key;
    }

    private static String dimName(WorldServer w) {
        int d = w.provider.getDimension();
        if (d == 0)  return "обычный";
        if (d == -1) return "Ад";
        if (d == 1)  return "Энд";
        return "id " + d;
    }

    private static <K> Map.Entry<K, Integer> topEntry(Map<K, Integer> m) {
        Map.Entry<K, Integer> best = null;
        if (m != null) for (Map.Entry<K, Integer> e : m.entrySet())
            if (best == null || e.getValue() > best.getValue()) best = e;
        return best;
    }

    private void printTop(ICommandSender sender, Map<String, Integer> types, int limit, int total) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(types.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        int shown = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (shown++ >= limit) break;
            int pct = total > 0 ? e.getValue() * 100 / total : 0;
            reply(sender, TextFormatting.WHITE, "  " + e.getValue()
                    + TextFormatting.GRAY + " (" + pct + "%)  " + shortName(e.getKey()));
        }
        if (shown == 0) reply(sender, TextFormatting.GRAY, "  пусто");
    }

    private void reply(ICommandSender sender, TextFormatting color, String msg) {
        sender.sendMessage(new TextComponentString(color + msg));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "top", "here", "0", "-1", "1");
        return Collections.emptyList();
    }
}
