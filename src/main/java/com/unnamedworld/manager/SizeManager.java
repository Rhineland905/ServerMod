package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import com.unnamedworld.network.MessageSize;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.io.*;
import java.util.*;

/**
 * Хранит масштаб модели игроков, сохраняет в sizes.json (переживает рестарт)
 * и рассылает его клиентам по сети. Сам визуальный размер рисует клиентский
 * мод UnnamedWorld, получая эти пакеты.
 */
public class SizeManager {

    public static final SizeManager INSTANCE = new SizeManager();

    public static final float DEFAULT = 1.0f;
    public static final float MIN     = 0.05f; // нижний предел (защита от нулевого размера)
    public static final float MAX     = 5.0f;

    // Размеры обычного игрока: ширина × высота, высота глаз (камера)
    private static final float BASE_WIDTH  = 0.6f;
    private static final float BASE_HEIGHT = 1.8f;
    private static final float BASE_EYE    = 1.62f;

    // Игроки, которым мы изменили бокс/высоту глаз — чтобы корректно вернуть при сбросе
    private final Set<UUID> sized = new HashSet<>();

    // Канал должен совпадать с каналом в клиентском моде UnnamedWorld
    private static final String CHANNEL_NAME = "uw_size";
    private SimpleNetworkWrapper channel;

    private final Map<UUID, Float> scales = new HashMap<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(File configDir) {
        saveFile = new File(configDir, "sizes.json");
        load();
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(MessageSize.Handler.class, MessageSize.class, 0, Side.CLIENT);
    }

    // --- API ---

    public float getScale(UUID uuid) {
        Float s = scales.get(uuid);
        return s == null ? DEFAULT : s;
    }

    public static float clamp(float s) {
        if (s < MIN) return MIN;
        if (s > MAX) return MAX;
        return s;
    }

    /** Задаёт масштаб, сохраняет и рассылает всем онлайн-игрокам. */
    public void setScale(EntityPlayerMP target, float scale) {
        scale = clamp(scale);
        UUID uuid = target.getUniqueID();
        if (scale == DEFAULT) {
            scales.remove(uuid);
        } else {
            scales.put(uuid, scale);
        }
        save();
        broadcast(uuid, scale);
    }

    // --- Networking ---

    private void broadcast(UUID uuid, float scale) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        MessageSize msg = new MessageSize(uuid, scale);
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
            channel.sendTo(msg, p);
        }
    }

    // Подгоняем хитбокс и высоту глаз под размер каждый тик. Игроков обычного
    // размера не трогаем (чтобы не ломать ванильные позы), а тем, кому размер
    // сбросили в 1.0 — один раз возвращаем стандартные значения.
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.world.isRemote) return;
        if (scales.isEmpty() && sized.isEmpty()) return; // никого не масштабируют — пропускаем

        UUID id = player.getUniqueID();
        float scale = getScale(id);

        if (scale == DEFAULT) {
            if (sized.remove(id)) restore(player); // вернуть обычный размер после сброса
            return;
        }
        if (player.isPlayerSleeping()) return; // во сне ванильный бокс 0.2×0.2

        apply(player, scale);
        sized.add(id);
    }

    private void apply(EntityPlayerMP player, float scale) {
        float w = BASE_WIDTH * scale;
        float h = BASE_HEIGHT * scale;
        if (player.width != w || player.height != h) {
            player.width = w;
            player.height = h;
            double half = w / 2.0;
            player.setEntityBoundingBox(new AxisAlignedBB(
                    player.posX - half, player.posY, player.posZ - half,
                    player.posX + half, player.posY + h, player.posZ + half));
        }
        player.eyeHeight = BASE_EYE * scale;
    }

    private void restore(EntityPlayerMP player) {
        player.width = BASE_WIDTH;
        player.height = BASE_HEIGHT;
        player.eyeHeight = BASE_EYE;
        double half = BASE_WIDTH / 2.0;
        player.setEntityBoundingBox(new AxisAlignedBB(
                player.posX - half, player.posY, player.posZ - half,
                player.posX + half, player.posY + BASE_HEIGHT, player.posZ + half));
    }

    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // 1. Отправляем зашедшему игроку всю таблицу нестандартных размеров
        if (!scales.isEmpty()) {
            channel.sendTo(new MessageSize(scales), player);
        }

        // 2. Сообщаем всем размер зашедшего игрока (если он не стандартный)
        float mine = getScale(player.getUniqueID());
        if (mine != DEFAULT) {
            MessageSize msg = new MessageSize(player.getUniqueID(), mine);
            for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
                channel.sendTo(msg, p);
            }
        }
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try { scales.put(UUID.fromString(e.getKey()), e.getValue().getAsFloat()); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load sizes", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            scales.forEach((uuid, scale) -> obj.addProperty(uuid.toString(), scale));
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save sizes", e);
        }
    }
}
