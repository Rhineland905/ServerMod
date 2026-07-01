package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import com.unnamedworld.network.MessageCosmetic;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.io.*;
import java.util.*;

/**
 * Хранит лорную косметику игроков (плащ/рога) как битовую маску, сохраняет в
 * cosmetics.json (переживает рестарт) и рассылает клиентам. Рисует косметику
 * клиентский мод UnnamedWorld по этим флагам (а не по предмету в слоте).
 */
public class LoreCosmeticManager {

    public static final LoreCosmeticManager INSTANCE = new LoreCosmeticManager();

    // Биты косметики — должны совпадать с клиентским модом UnnamedWorld
    public static final int CAPE      = 1;
    public static final int HORNS     = 2;
    public static final int PROPELLER = 4;
    // Крылья демона. Не выдаются командой /loreitem — их включает раса demon
    // (AbilityManager) через setDynamicBit. Поэтому НЕ в IDS и не в bitForId.
    public static final int WINGS     = 8;
    public static final int CATEARS   = 16;

    /** id косметики из команды → бит. */
    public static int bitForId(String id) {
        if ("cape".equalsIgnoreCase(id))      return CAPE;
        if ("horns".equalsIgnoreCase(id))     return HORNS;
        if ("propeller".equalsIgnoreCase(id)) return PROPELLER;
        if ("catears".equalsIgnoreCase(id))   return CATEARS;
        return 0;
    }

    public static final String[] IDS = { "cape", "horns", "propeller", "catears" };

    private static final String CHANNEL_NAME = "uw_cosmetic";
    private SimpleNetworkWrapper channel;

    private final Map<UUID, Integer> masks = new HashMap<>();      // выданная косметика (сохраняется на диск)
    private final Map<UUID, Integer> dynamic = new HashMap<>();    // расовые биты (крылья) — НЕ сохраняются
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(File configDir) {
        saveFile = new File(configDir, "cosmetics.json");
        load();
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(MessageCosmetic.Handler.class, MessageCosmetic.class, 0, Side.CLIENT);
    }

    // --- API ---

    public int getMask(UUID uuid) {
        Integer m = masks.get(uuid);
        return m == null ? 0 : m;
    }

    public boolean has(UUID uuid, int bit) {
        return (getMask(uuid) & bit) != 0;
    }

    /** Маска, которая реально уходит клиентам: выданная косметика + расовые биты (крылья). */
    private int effectiveMask(UUID uuid) {
        Integer d = dynamic.get(uuid);
        return getMask(uuid) | (d == null ? 0 : d);
    }

    /**
     * Включает/выключает динамический расовый бит (например, {@link #WINGS}) и рассылает
     * клиентам. На диск не пишется: после рестарта раса заново выставит бит на логине.
     */
    public void setDynamicBit(UUID uuid, int bit, boolean on) {
        int cur  = dynamic.getOrDefault(uuid, 0);
        int next = on ? (cur | bit) : (cur & ~bit);
        if (next == cur) return;
        if (next == 0) dynamic.remove(uuid);
        else dynamic.put(uuid, next);
        broadcast(uuid);
    }

    /** Возвращает true, если флаг реально изменился. */
    public boolean give(EntityPlayerMP target, int bit) {
        UUID uuid = target.getUniqueID();
        int mask = getMask(uuid);
        if ((mask & bit) != 0) return false;
        setMask(uuid, mask | bit);
        return true;
    }

    /** Возвращает true, если флаг реально изменился. */
    public boolean remove(EntityPlayerMP target, int bit) {
        UUID uuid = target.getUniqueID();
        int mask = getMask(uuid);
        if ((mask & bit) == 0) return false;
        setMask(uuid, mask & ~bit);
        return true;
    }

    private void setMask(UUID uuid, int mask) {
        if (mask == 0) masks.remove(uuid);
        else masks.put(uuid, mask);
        save();
        broadcast(uuid);
    }

    // --- Networking ---

    private void broadcast(UUID uuid) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        MessageCosmetic msg = new MessageCosmetic(uuid, effectiveMask(uuid));
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
            channel.sendTo(msg, p);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // 1. Отправляем зашедшему всю таблицу косметики (выданная + расовые биты)
        Map<UUID, Integer> table = new HashMap<>();
        for (UUID u : masks.keySet())   table.put(u, effectiveMask(u));
        for (UUID u : dynamic.keySet()) table.put(u, effectiveMask(u));
        if (!table.isEmpty()) {
            channel.sendTo(new MessageCosmetic(table), player);
        }

        // 2. Сообщаем всем косметику зашедшего игрока (если есть)
        if (effectiveMask(player.getUniqueID()) != 0) {
            broadcast(player.getUniqueID());
        }
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try { masks.put(UUID.fromString(e.getKey()), e.getValue().getAsInt()); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load cosmetics", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            masks.forEach((uuid, mask) -> obj.addProperty(uuid.toString(), mask));
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save cosmetics", e);
        }
    }
}
