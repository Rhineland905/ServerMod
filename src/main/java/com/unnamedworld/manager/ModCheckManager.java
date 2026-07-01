package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Проверка модов клиента при входе на сервер.
 *
 * Forge при подключении обменивается списком модов (FML-handshake), и сервер его видит.
 * При входе игрока пишем в КОНСОЛЬ полный список его модов, а при совпадении с
 * «чёрным списком» (modcheck.json) — отдельное предупреждение в консоль. В чат ОПам
 * НЕ спамим — всё только в лог сервера.
 *
 * ВАЖНО (честно про ограничения): так ловятся только честные Forge-моды. Настоящие
 * чит-клиенты и xray-ресурспаки сервер НЕ видит — они либо прячут/подделывают modid,
 * либо вообще не Forge. Это «отсев ленивых», а не полноценный античит.
 *
 * Чёрный список редактируется: файлом modcheck.json или командой /modcheck.
 */
public class ModCheckManager {

    public static final ModCheckManager INSTANCE = new ModCheckManager();

    // modid'ы, которые считаем подозрительными (нижний регистр). Редактируемый.
    private final Set<String> blacklist = new LinkedHashSet<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Reflection на приватное поле NetworkDispatcher со списком модов клиента.
    private static final Field MODLIST_FIELD;
    static {
        Field f = null;
        try {
            f = NetworkDispatcher.class.getDeclaredField("modList");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) { /* подберём в getClientModList() запасным сканом */ }
        MODLIST_FIELD = f;
    }

    public void init(File configDir) {
        saveFile = new File(configDir, "modcheck.json");
        load();
    }

    // --- Чёрный список (редактируемый) ---

    public Set<String> getBlacklist() { return Collections.unmodifiableSet(blacklist); }

    public boolean block(String modid) {
        boolean added = blacklist.add(modid.toLowerCase());
        if (added) save();
        return added;
    }

    public boolean unblock(String modid) {
        boolean removed = blacklist.remove(modid.toLowerCase());
        if (removed) save();
        return removed;
    }

    public void reload() {
        blacklist.clear();
        load();
    }

    /**
     * Подозрительные modid из списка — режим «СОДЕРЖИТ»: modid считается палевным, если в нём
     * встречается хотя бы один шаблон из чёрного списка. Т.е. запись «xray» ловит и
     * «advancedxray», и «xrayultimate». Без учёта регистра.
     */
    public List<String> findSuspicious(Collection<String> modids) {
        List<String> hits = new ArrayList<>();
        if (blacklist.isEmpty()) return hits;
        for (String id : modids) {
            String low = id.toLowerCase();
            for (String pat : blacklist) {
                if (!pat.isEmpty() && low.contains(pat)) { hits.add(id); break; }
            }
        }
        return hits;
    }

    // --- Событие входа ---

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        String name = player.getName();

        Map<String, String> mods = getClientModList(player);
        if (mods == null || mods.isEmpty()) {
            ServerMod.LOGGER.info("[ModCheck] {}: список модов недоступен (ванила/локальный клиент?)", name);
            TelegramManager.INSTANCE.notifyJoin(name, null, Collections.emptyList());
            return;
        }

        List<String> ids = new ArrayList<>(mods.keySet());
        Collections.sort(ids);
        ServerMod.LOGGER.info("[ModCheck] {}: {} модов: {}", name, ids.size(), String.join(", ", ids));

        List<String> hits = findSuspicious(ids);
        if (!hits.isEmpty()) {
            // WARN-уровень — в консоли Pterodactyl подсвечивается (жёлтым/красным).
            ServerMod.LOGGER.warn("[ModCheck] >>> ВНИМАНИЕ: у игрока {} подозрительные моды: {}",
                    name, String.join(", ", hits));
        }
        TelegramManager.INSTANCE.notifyJoin(name, ids, hits);
    }

    // --- Чтение списка модов клиента ---

    /** modid -> версия, как прислал клиент в FML-handshake. null, если недоступно. */
    @SuppressWarnings("unchecked")
    public Map<String, String> getClientModList(EntityPlayerMP player) {
        try {
            if (player.connection == null) return null;
            NetworkManager nm = player.connection.netManager;
            if (nm == null || nm.channel() == null) return null;
            NetworkDispatcher disp = nm.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            if (disp == null) return null;

            if (MODLIST_FIELD != null) {
                Object v = MODLIST_FIELD.get(disp);
                if (v instanceof Map) return (Map<String, String>) v;
            }
            // Запасной вариант: ищем непустое Map-поле со строковыми ключами.
            for (Field f : NetworkDispatcher.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(disp);
                if (v instanceof Map && !((Map<?, ?>) v).isEmpty()
                        && ((Map<?, ?>) v).keySet().iterator().next() instanceof String) {
                    return (Map<String, String>) v;
                }
            }
        } catch (Exception e) {
            ServerMod.LOGGER.warn("[ModCheck] не удалось прочитать список модов у {}", player.getName(), e);
        }
        return null;
    }

    // --- Persistence ---

    private void load() {
        if (saveFile == null) return;
        if (!saveFile.exists()) { addDefaults(); save(); return; }
        try (Reader r = new FileReader(saveFile)) {
            JsonObject o = gson.fromJson(r, JsonObject.class);
            if (o != null && o.has("blacklist")) {
                for (JsonElement el : o.getAsJsonArray("blacklist")) blacklist.add(el.getAsString().toLowerCase());
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("[ModCheck] не удалось загрузить modcheck.json", e);
        }
    }

    /** Стартовый набор. Litematica и Schematica НЕ включаем — они разрешены. */
    private void addDefaults() {
        blacklist.addAll(Arrays.asList("advancedxray", "xray", "xraymod", "fullbright"));
    }

    private void save() {
        if (saveFile == null) return;
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String s : blacklist) arr.add(s);
            o.add("blacklist", arr);
            gson.toJson(o, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[ModCheck] не удалось сохранить modcheck.json", e);
        }
    }
}
