package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;

public class TownManager {

    public static final TownManager INSTANCE = new TownManager();

    // ── Data classes ──────────────────────────────────────────────────────────

    // Регион — просто именованная зона внутри города, без владельца и привата
    public static class Region {
        public final String name;
        public final int x1, z1, x2, z2; // нормализованы: x1<=x2, z1<=z2

        Region(String name, int x1, int z1, int x2, int z2) {
            this.name = name;
            this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
            this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
        }

        public boolean contains(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        public boolean intersects(int ax1, int az1, int ax2, int az2) {
            return ax1 <= x2 && ax2 >= x1 && az1 <= z2 && az2 >= z1;
        }
    }

    public static class Town {
        public final String name;
        public final int dim;
        public final int x1, z1, x2, z2; // bounding box (нормализованы)
        public final List<int[]> points = new ArrayList<>(); // вершины полигона {x,z}; пусто = обычный прямоугольник
        public final List<Region> regions = new ArrayList<>();
        public final Map<String, String> members = new LinkedHashMap<>(); // uuid -> имя

        // Прямоугольный город (2 угла / старый формат)
        Town(String name, int dim, int x1, int z1, int x2, int z2) {
            this.name = name;
            this.dim = dim;
            this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
            this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
        }

        // Полигональный город: границы задаются списком точек (>=3), bbox считается по ним
        Town(String name, int dim, List<int[]> pts) {
            this.name = name;
            this.dim = dim;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] p : pts) {
                points.add(new int[]{ p[0], p[1] });
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
            }
            this.x1 = minX; this.z1 = minZ; this.x2 = maxX; this.z2 = maxZ;
        }

        public boolean contains(int x, int z) {
            if (x < x1 || x > x2 || z < z1 || z > z2) return false; // быстрый отсев по bbox
            if (points.size() < 3) return true;                     // прямоугольник = bbox
            return pointInPolygon(x, z);
        }

        // Ray casting: нечётное число пересечений луча вправо = точка внутри
        private boolean pointInPolygon(int x, int z) {
            boolean inside = false;
            int n = points.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                int xi = points.get(i)[0], zi = points.get(i)[1];
                int xj = points.get(j)[0], zj = points.get(j)[1];
                boolean cross = ((zi > z) != (zj > z))
                        && (x < (double) (xj - xi) * (z - zi) / (double) (zj - zi) + xi);
                if (cross) inside = !inside;
            }
            return inside;
        }

        public boolean intersects(int ax1, int az1, int ax2, int az2) {
            return ax1 <= x2 && ax2 >= x1 && az1 <= z2 && az2 >= z1; // по bbox
        }

        @Nullable
        public Region getRegion(String name) {
            for (Region r : regions) {
                if (r.name.equalsIgnoreCase(name)) return r;
            }
            return null;
        }

        @Nullable
        public Region getRegionAt(int x, int z) {
            for (Region r : regions) {
                if (r.contains(x, z)) return r;
            }
            return null;
        }

        public boolean isMember(UUID uuid) {
            return members.containsKey(uuid.toString());
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final List<Town> towns = new ArrayList<>();

    // Выделение точек pos1/pos2 командой /town; UUID -> {x, z, dim}
    private final Map<UUID, int[]> pos1 = new HashMap<>();
    private final Map<UUID, int[]> pos2 = new HashMap<>();

    // Полигональная разметка: UUID -> список вершин {x, z, dim}
    private final Map<UUID, List<int[]>> polyPoints = new HashMap<>();

    // Где игрок находится сейчас (для титулов входа/выхода)
    private final Map<UUID, String> currentTown   = new HashMap<>();
    private final Map<UUID, String> currentRegion = new HashMap<>(); // "город/регион"

    // Босс-бар сверху экрана с названием города, пока игрок внутри
    private final Map<UUID, BossInfoServer> bars     = new HashMap<>();
    private final Map<UUID, String>         barTexts = new HashMap<>();

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tickCounter = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "towns.json");
        load();
    }

    // ── Public API: towns ─────────────────────────────────────────────────────

    public List<Town> getTowns() {
        return Collections.unmodifiableList(towns);
    }

    public List<String> getTownNames() {
        List<String> names = new ArrayList<>();
        for (Town t : towns) names.add(t.name);
        return names;
    }

    @Nullable
    public Town getTown(String name) {
        for (Town t : towns) {
            if (t.name.equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    @Nullable
    public Town getTownAt(int dim, int x, int z) {
        for (Town t : towns) {
            if (t.dim == dim && t.contains(x, z)) return t;
        }
        return null;
    }

    /** Возвращает город, пересекающийся с заданной зоной, или null. */
    @Nullable
    public Town findOverlapping(int dim, int x1, int z1, int x2, int z2) {
        for (Town t : towns) {
            if (t.dim == dim && t.intersects(Math.min(x1, x2), Math.min(z1, z2),
                                             Math.max(x1, x2), Math.max(z1, z2))) return t;
        }
        return null;
    }

    public Town createTown(String name, int dim, int x1, int z1, int x2, int z2) {
        Town town = new Town(name, dim, x1, z1, x2, z2);
        towns.add(town);
        save();
        return town;
    }

    /** Создать город-полигон по списку вершин {x,z} (>=3). */
    public Town createTownPoly(String name, int dim, List<int[]> pts) {
        Town town = new Town(name, dim, pts);
        towns.add(town);
        save();
        return town;
    }

    public boolean deleteTown(String name) {
        Iterator<Town> it = towns.iterator();
        while (it.hasNext()) {
            if (it.next().name.equalsIgnoreCase(name)) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    // ── Public API: regions ───────────────────────────────────────────────────

    public Region createRegion(Town town, String name, int x1, int z1, int x2, int z2) {
        Region region = new Region(name, x1, z1, x2, z2);
        town.regions.add(region);
        save();
        return region;
    }

    public boolean deleteRegion(Town town, String name) {
        Iterator<Region> it = town.regions.iterator();
        while (it.hasNext()) {
            if (it.next().name.equalsIgnoreCase(name)) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    // ── Public API: members ───────────────────────────────────────────────────

    public void addMember(Town town, String uuid, String name) {
        town.members.put(uuid, name);
        save();
    }

    public void removeMember(Town town, String uuid) {
        if (town.members.remove(uuid) != null) save();
    }

    // ── Public API: selection ─────────────────────────────────────────────────

    public void setPos(UUID uuid, boolean first, int x, int z, int dim) {
        (first ? pos1 : pos2).put(uuid, new int[]{ x, z, dim });
    }

    @Nullable
    public int[] getPos(UUID uuid, boolean first) {
        return (first ? pos1 : pos2).get(uuid);
    }

    public void clearSelection(UUID uuid) {
        pos1.remove(uuid);
        pos2.remove(uuid);
    }

    // ── Public API: полигональная разметка ────────────────────────────────────

    public void addPolyPoint(UUID uuid, int x, int z, int dim) {
        polyPoints.computeIfAbsent(uuid, k -> new ArrayList<>()).add(new int[]{ x, z, dim });
    }

    public List<int[]> getPolyPoints(UUID uuid) {
        List<int[]> l = polyPoints.get(uuid);
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
    }

    @Nullable
    public int[] undoPolyPoint(UUID uuid) {
        List<int[]> l = polyPoints.get(uuid);
        if (l == null || l.isEmpty()) return null;
        int[] removed = l.remove(l.size() - 1);
        if (l.isEmpty()) polyPoints.remove(uuid);
        return removed;
    }

    public void clearPolyPoints(UUID uuid) {
        polyPoints.remove(uuid);
    }

    // ── Events: вход/выход из зон (титулы) ───────────────────────────────────

    // Каждые 10 тиков (2 раза в секунду) проверяем, в каком городе/регионе игроки.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter % 10 != 0) return;
        if (towns.isEmpty()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUniqueID();
            int x = MathHelper.floor(player.posX);
            int z = MathHelper.floor(player.posZ);
            int dim = player.dimension;

            Town town = getTownAt(dim, x, z);
            String townName = town == null ? null : town.name;
            String prevTown = currentTown.get(uuid);

            if (!Objects.equals(townName, prevTown)) {
                if (townName == null) {
                    if (prevTown != null) {
                        sendActionBar(player,
                                TextFormatting.GRAY + "Вы покинули город " + prevTown);
                    }
                    currentTown.remove(uuid);
                } else {
                    currentTown.put(uuid, townName);
                }
            }

            Region region = town == null ? null : town.getRegionAt(x, z);
            String regionKey = region == null ? null : townName + "/" + region.name;
            String prevRegion = currentRegion.get(uuid);

            if (!Objects.equals(regionKey, prevRegion)) {
                if (region != null) {
                    sendActionBar(player,
                            TextFormatting.AQUA + "Регион: "
                          + TextFormatting.WHITE + region.name);
                } else if (prevRegion != null) {
                    int slash = prevRegion.indexOf('/');
                    sendActionBar(player, TextFormatting.GRAY
                            + "Вы покинули регион " + prevRegion.substring(slash + 1));
                }
                if (regionKey == null) currentRegion.remove(uuid);
                else currentRegion.put(uuid, regionKey);
            }

            updateBar(player, town, region);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerLoggedOutEvent event) {
        UUID uuid = event.player.getUniqueID();
        currentTown.remove(uuid);
        currentRegion.remove(uuid);
        pos1.remove(uuid);
        pos2.remove(uuid);
        polyPoints.remove(uuid);
        BossInfoServer bar = bars.remove(uuid);
        if (bar != null && event.player instanceof EntityPlayerMP) {
            bar.removePlayer((EntityPlayerMP) event.player);
        }
        barTexts.remove(uuid);
    }

    // ── Boss bar (полоса сверху экрана с названием города) ───────────────────

    private void updateBar(EntityPlayerMP player, @Nullable Town town, @Nullable Region region) {
        UUID uuid = player.getUniqueID();

        if (town == null) {
            BossInfoServer bar = bars.remove(uuid);
            if (bar != null) bar.removePlayer(player);
            barTexts.remove(uuid);
            return;
        }

        String text = TextFormatting.GOLD.toString() + TextFormatting.BOLD
                + "✦ " + town.name + " ✦";
        if (region != null) {
            text += TextFormatting.RESET.toString() + TextFormatting.AQUA
                  + "  » " + region.name;
        }

        BossInfoServer bar = bars.get(uuid);
        if (bar == null) {
            bar = new BossInfoServer(new TextComponentString(text),
                    BossInfo.Color.YELLOW, BossInfo.Overlay.PROGRESS);
            bar.setPercent(1.0F);
            bars.put(uuid, bar);
            barTexts.put(uuid, text);
            bar.addPlayer(player);
        } else if (!text.equals(barTexts.get(uuid))) {
            bar.setName(new TextComponentString(text));
            barTexts.put(uuid, text);
        }
    }

    // ── Titles ────────────────────────────────────────────────────────────────

    private void sendActionBar(EntityPlayerMP player, String text) {
        player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.ACTIONBAR,
                new TextComponentString(text)));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null || !obj.has("towns")) return;
            for (JsonElement elem : obj.getAsJsonArray("towns")) {
                JsonObject to = elem.getAsJsonObject();
                String tname = to.get("name").getAsString();
                int tdim = to.get("dim").getAsInt();
                Town town;
                if (to.has("points")) {
                    List<int[]> pts = new ArrayList<>();
                    for (JsonElement pe : to.getAsJsonArray("points")) {
                        JsonArray pa = pe.getAsJsonArray();
                        pts.add(new int[]{ pa.get(0).getAsInt(), pa.get(1).getAsInt() });
                    }
                    town = new Town(tname, tdim, pts);
                } else {
                    town = new Town(tname, tdim,
                            to.get("x1").getAsInt(), to.get("z1").getAsInt(),
                            to.get("x2").getAsInt(), to.get("z2").getAsInt());
                }
                if (to.has("regions")) {
                    for (JsonElement re : to.getAsJsonArray("regions")) {
                        JsonObject ro = re.getAsJsonObject();
                        town.regions.add(new Region(
                                ro.get("name").getAsString(),
                                ro.get("x1").getAsInt(), ro.get("z1").getAsInt(),
                                ro.get("x2").getAsInt(), ro.get("z2").getAsInt()));
                    }
                }
                if (to.has("members")) {
                    for (JsonElement me : to.getAsJsonArray("members")) {
                        JsonObject mo = me.getAsJsonObject();
                        town.members.put(mo.get("uuid").getAsString(),
                                         mo.get("name").getAsString());
                    }
                }
                towns.add(town);
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load towns", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonArray arr = new JsonArray();
            for (Town t : towns) {
                JsonObject to = new JsonObject();
                to.addProperty("name", t.name);
                to.addProperty("dim",  t.dim);
                to.addProperty("x1", t.x1); to.addProperty("z1", t.z1);
                to.addProperty("x2", t.x2); to.addProperty("z2", t.z2);
                if (!t.points.isEmpty()) {
                    JsonArray pts = new JsonArray();
                    for (int[] p : t.points) {
                        JsonArray pa = new JsonArray();
                        pa.add(p[0]); pa.add(p[1]);
                        pts.add(pa);
                    }
                    to.add("points", pts);
                }
                JsonArray regs = new JsonArray();
                for (Region r : t.regions) {
                    JsonObject ro = new JsonObject();
                    ro.addProperty("name", r.name);
                    ro.addProperty("x1", r.x1); ro.addProperty("z1", r.z1);
                    ro.addProperty("x2", r.x2); ro.addProperty("z2", r.z2);
                    regs.add(ro);
                }
                to.add("regions", regs);
                JsonArray mems = new JsonArray();
                for (Map.Entry<String, String> en : t.members.entrySet()) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("uuid", en.getKey());
                    mo.addProperty("name", en.getValue());
                    mems.add(mo);
                }
                to.add("members", mems);
                arr.add(to);
            }
            JsonObject obj = new JsonObject();
            obj.add("towns", arr);
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save towns", e);
        }
    }
}
