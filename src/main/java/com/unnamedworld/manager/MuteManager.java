package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Муты игроков — действуют ОДНОВРЕМЕННО на чат и на голосовой чат (Simple Voice Chat).
 *
 *  • Чат глушится здесь, через {@link ServerChatEvent}.
 *  • Голос глушится в {@link com.unnamedworld.voice.VoiceMutePlugin} — тот спрашивает
 *    {@link #isMuted(UUID)} на каждый пакет микрофона и отменяет пакеты замученного.
 *
 * Хранится по UUID (надёжно и для чата, и для войса), плюс последнее известное имя
 * для красивого вывода. Поддерживает временные муты (с авто-снятием) и причину.
 *
 * Потокобезопасно: команды и тик трогают карту в главном потоке, голосовой поток
 * только ЧИТАЕТ через {@link #isMuted(UUID)} (ConcurrentHashMap, без записи из войса).
 */
public class MuteManager {

    public static final MuteManager INSTANCE = new MuteManager();

    /** Одна запись мута. until = 0 — навсегда; иначе epoch-millis окончания. */
    public static final class Mute {
        public final String name;   // последнее известное имя (для вывода)
        public final long   until;  // 0 = перманент, иначе System.currentTimeMillis() окончания
        public final String reason; // "" если без причины
        public final String by;     // кто замутил

        Mute(String name, long until, String reason, String by) {
            this.name = name; this.until = until;
            this.reason = reason == null ? "" : reason;
            this.by = by == null ? "" : by;
        }
        boolean expired() { return until != 0 && System.currentTimeMillis() >= until; }
    }

    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tick = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "mutes.json");
        load();
    }

    // --- API ---

    /** Замутить (чат + войс). durationSeconds <= 0 — навсегда. Возвращает запись. */
    public Mute mute(UUID uuid, String name, long durationSeconds, String reason, String by) {
        long until = durationSeconds <= 0 ? 0L : System.currentTimeMillis() + durationSeconds * 1000L;
        Mute m = new Mute(name, until, reason, by);
        mutes.put(uuid, m);
        save();
        return m;
    }

    /** Снять мут. true — если игрок был замучен. */
    public boolean unmute(UUID uuid) {
        boolean had = mutes.remove(uuid) != null;
        if (had) save();
        return had;
    }

    /** Быстрая проверка без изменения карты — зовётся в т.ч. из голосового потока на каждый пакет. */
    public boolean isMuted(UUID uuid) {
        if (uuid == null || mutes.isEmpty()) return false;
        Mute m = mutes.get(uuid);
        return m != null && !m.expired();
    }

    /** Текущий активный мут или null (просрочённые считаются снятыми). */
    public Mute get(UUID uuid) {
        Mute m = mutes.get(uuid);
        return (m != null && !m.expired()) ? m : null;
    }

    /** Все активные муты (просрочённые отфильтрованы), отсортированы по имени. */
    public List<Map.Entry<UUID, Mute>> list() {
        List<Map.Entry<UUID, Mute>> out = new ArrayList<>();
        for (Map.Entry<UUID, Mute> e : mutes.entrySet())
            if (!e.getValue().expired()) out.add(e);
        out.sort(Comparator.comparing(e -> e.getValue().name.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Остаток мута в секундах: -1 — навсегда, 0 — не замучен/просрочен. */
    public long remainingSeconds(UUID uuid) {
        Mute m = get(uuid);
        if (m == null) return 0;
        if (m.until == 0) return -1;
        return Math.max(0, (m.until - System.currentTimeMillis()) / 1000L);
    }

    // --- События ---

    /** Глушим чат замученного. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ServerChatEvent event) {
        if (mutes.isEmpty()) return;
        EntityPlayerMP player = event.getPlayer();
        if (player == null) return;
        Mute m = get(player.getUniqueID());
        if (m == null) return;
        event.setCanceled(true);
        player.sendMessage(new TextComponentString(
                TextFormatting.RED + "Тебе выдан мут — писать в чат нельзя. " + muteTail(m)));
    }

    /** Раз в ~5 сек чистим просрочённые муты (в главном потоке) и сохраняем при изменениях. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mutes.isEmpty()) return;
        if (++tick % 100 != 0) return;

        boolean changed = false;
        for (Iterator<Map.Entry<UUID, Mute>> it = mutes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Mute> e = it.next();
            if (e.getValue().expired()) {
                it.remove();
                changed = true;
                notifyUnmuted(e.getKey(), e.getValue().name, true);
            }
        }
        if (changed) save();
    }

    // --- Вспомогательное ---

    /** «Причина: …. Осталось: …» — хвост для сообщений. */
    public String muteTail(Mute m) {
        StringBuilder sb = new StringBuilder();
        if (!m.reason.isEmpty()) sb.append("Причина: ").append(m.reason).append(". ");
        sb.append("Осталось: ").append(m.until == 0 ? "навсегда"
                : formatDuration(Math.max(0, (m.until - System.currentTimeMillis()) / 1000L)));
        return sb.toString();
    }

    /** Человекочитаемая длительность: «1д 2ч 3м 4с». */
    public static String formatDuration(long seconds) {
        return Durations.format(seconds);
    }

    private void notifyUnmuted(UUID uuid, String name, boolean expired) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(uuid);
        if (p != null)
            p.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + (expired ? "Срок твоего мута истёк — чат и войс снова доступны."
                               : "С тебя сняли мут — чат и войс снова доступны.")));
    }

    /** Зовётся командой /unmute, чтобы уведомить игрока. */
    public void announceUnmuted(UUID uuid, String name) {
        notifyUnmuted(uuid, name, false);
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    JsonObject o = e.getValue().getAsJsonObject();
                    String name   = o.has("name")   ? o.get("name").getAsString()   : "?";
                    long   until  = o.has("until")  ? o.get("until").getAsLong()    : 0L;
                    String reason = o.has("reason") ? o.get("reason").getAsString() : "";
                    String by     = o.has("by")     ? o.get("by").getAsString()     : "";
                    Mute m = new Mute(name, until, reason, by);
                    if (!m.expired()) mutes.put(uuid, m); // просрочённые не подгружаем
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load mutes", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, Mute> e : mutes.entrySet()) {
                Mute m = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("name", m.name);
                o.addProperty("until", m.until);
                o.addProperty("reason", m.reason);
                o.addProperty("by", m.by);
                obj.add(e.getKey().toString(), o);
            }
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save mutes", e);
        }
    }
}
