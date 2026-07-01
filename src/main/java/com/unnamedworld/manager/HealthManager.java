package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;

import java.io.*;
import java.util.*;

/**
 * Задаёт игрокам максимальное здоровье (атрибут MAX_HEALTH), сохраняет в health.json
 * (переживает рестарт) и переприменяет при заходе и возрождении. Трогаем только тех
 * игроков, кому размер здоровья выставлен явно — чужие атрибуты не клобберим.
 */
public class HealthManager {

    public static final HealthManager INSTANCE = new HealthManager();

    public static final float DEFAULT = 20.0f;   // 10 сердец
    public static final float MIN     = 1.0f;    // полсердца — меньше нельзя (0 = моментальная смерть)
    public static final float MAX     = 1024.0f; // потолок атрибута в ваниле

    private final Map<UUID, Float> health = new HashMap<>();
    // Здоровье игрока непосредственно ДО входа в мир (логин/респавн/смена измерения).
    private final Map<UUID, Float> preJoinHealth = new HashMap<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(File configDir) {
        saveFile = new File(configDir, "health.json");
        load();
    }

    // --- API ---

    public float getMaxHealth(UUID uuid) {
        Float h = health.get(uuid);
        return h == null ? DEFAULT : h;
    }

    public static float clamp(float h) {
        if (h < MIN) return MIN;
        if (h > MAX) return MAX;
        return h;
    }

    /** Задаёт макс. здоровье, применяет, сохраняет. */
    public void setMaxHealth(EntityPlayerMP player, float value) {
        value = clamp(value);
        UUID uuid = player.getUniqueID();
        if (value == DEFAULT) health.remove(uuid);
        else health.put(uuid, value);
        save();
        apply(player, value);
    }

    private void apply(EntityPlayerMP player, float max) {
        IAttributeInstance attr = player.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(max);
        if (player.getHealth() > max) player.setHealth(max); // обрезаем текущее HP под новый максимум
    }

    // --- Events: переприменяем сохранённое значение ---

    // Некоторые моды (напр. Vampirism) на EntityJoinWorldEvent безусловно лечат игрока
    // в его ТЕКУЩИЙ максимум (player.setHealth(player.getMaxHealth())) — например, вампиров
    // при каждом входе в мир. Событие стреляет РАНЬШЕ PlayerLoggedInEvent/PlayerRespawnEvent,
    // то есть раньше, чем мы успеваем переприменить свой урезанный максимум. В итоге игрок
    // лечится по ещё дефолтному (большему) максимуму, и урезанное HP "восстанавливается".
    // Чиним без привязки к конкретному моду: запоминаем HP ДО входа в мир (HIGHEST — раньше
    // всех остальных подписчиков) и возвращаем его обратно ПОСЛЕ (LOWEST — позже всех),
    // одновременно переприменяя наш урезанный максимум.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onJoinWorldPre(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        UUID uuid = event.getEntity().getUniqueID();
        if (!health.containsKey(uuid)) return; // трогаем только тех, у кого урезан максимум
        preJoinHealth.put(uuid, ((EntityPlayerMP) event.getEntity()).getHealth());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onJoinWorldPost(EntityJoinWorldEvent event) {
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntity();
        Float before = preJoinHealth.remove(player.getUniqueID());
        if (before == null) return;
        Float max = health.get(player.getUniqueID());
        if (max != null) apply(player, max);                       // переприменить наш максимум...
        player.setHealth(Math.min(before, player.getMaxHealth())); // ...и вернуть HP как было
    }

    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Float max = health.get(player.getUniqueID());
        if (max != null) apply(player, max);
    }

    @SubscribeEvent
    public void onRespawn(PlayerRespawnEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Float max = health.get(player.getUniqueID());
        if (max != null) apply(player, max);
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try { health.put(UUID.fromString(e.getKey()), e.getValue().getAsFloat()); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load health data", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            health.forEach((uuid, h) -> obj.addProperty(uuid.toString(), h));
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save health data", e);
        }
    }
}
