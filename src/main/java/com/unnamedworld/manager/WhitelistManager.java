package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;

import java.io.*;
import java.util.*;

/**
 * Свой вайтлист сервера (отдельно от ванильного /whitelist).
 * Хранит имена игроков (нижний регистр) и флаг включения в whitelist.json.
 * ОПы проходят всегда — чтобы нельзя было залочить администрацию.
 * По умолчанию выключен.
 */
public class WhitelistManager {

    public static final WhitelistManager INSTANCE = new WhitelistManager();

    private final Set<String> names = new HashSet<>(); // имена в нижнем регистре
    private boolean enabled = false;

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(File configDir) {
        saveFile = new File(configDir, "whitelist.json");
        load();
    }

    // --- API ---

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        save();
        if (enabled) kickNonWhitelisted(); // выгнать тех, кого нет в списке
    }

    public boolean isWhitelisted(String name) {
        return name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    /** true, если игрок реально добавлен (его ещё не было). */
    public boolean add(String name) {
        boolean changed = names.add(name.toLowerCase(Locale.ROOT));
        if (changed) save();
        return changed;
    }

    /** true, если игрок реально убран. Если онлайн и вайтлист включён — кикаем. */
    public boolean remove(String name) {
        boolean changed = names.remove(name.toLowerCase(Locale.ROOT));
        if (changed) {
            save();
            kickIfOnline(name);
        }
        return changed;
    }

    public List<String> getNames() {
        List<String> list = new ArrayList<>(names);
        Collections.sort(list);
        return list;
    }

    // --- События ---

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoggedInEvent event) {
        if (!enabled) return;
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (isOp(player) || isWhitelisted(player.getName())) return;
        player.connection.disconnect(notWhitelistedMsg());
    }

    // --- Вспомогательное ---

    private boolean isOp(EntityPlayerMP player) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null
                && server.getPlayerList().getOppedPlayers().getEntry(player.getGameProfile()) != null;
    }

    private TextComponentString notWhitelistedMsg() {
        return new TextComponentString(
                TextFormatting.RED + "Тебя нет в вайтлисте сервера " + ServerMod.SERVER_NAME + ".");
    }

    /** Выгоняет всех онлайн, кого нет в вайтлисте (и кто не ОП). Вызывается при включении. */
    private void kickNonWhitelisted() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        // копия списка: disconnect меняет playerList
        for (EntityPlayerMP p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (isOp(p) || isWhitelisted(p.getName())) continue;
            p.connection.disconnect(notWhitelistedMsg());
        }
    }

    private void kickIfOnline(String name) {
        if (!enabled) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUsername(name);
        if (p == null || isOp(p)) return;
        p.connection.disconnect(new TextComponentString(
                TextFormatting.RED + "Тебя убрали из вайтлиста сервера " + ServerMod.SERVER_NAME + "."));
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            if (obj.has("enabled")) enabled = obj.get("enabled").getAsBoolean();
            if (obj.has("names")) {
                for (JsonElement el : obj.getAsJsonArray("names")) {
                    try { names.add(el.getAsString().toLowerCase(Locale.ROOT)); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load whitelist", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonArray arr = new JsonArray();
            for (String n : getNames()) arr.add(n);
            obj.add("names", arr);
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save whitelist", e);
        }
    }
}
