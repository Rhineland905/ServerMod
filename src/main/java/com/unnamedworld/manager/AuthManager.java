package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class AuthManager {

    public static final AuthManager INSTANCE = new AuthManager();

    private final Map<UUID, String>   hashes    = new HashMap<>();
    private final Set<UUID>           authed    = new HashSet<>();
    // UUID -> spawn pos; contains only non-authenticated online players
    private final Map<UUID, double[]> freezePos = new HashMap<>();

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int authTick = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "auth.json");
        load();
    }

    // --- Public API ---

    public boolean isRegistered(UUID uuid)    { return hashes.containsKey(uuid); }
    public boolean isAuthenticated(UUID uuid) { return authed.contains(uuid); }

    public boolean register(UUID uuid, String password) {
        if (isRegistered(uuid)) return false;
        hashes.put(uuid, hash(uuid, password));
        save();
        return true;
    }

    public boolean login(UUID uuid, String password) {
        String stored = hashes.get(uuid);
        if (stored == null || !stored.equals(hash(uuid, password))) return false;
        authed.add(uuid);
        freezePos.remove(uuid); // unfreeze
        return true;
    }

    // --- Events ---

    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID uuid = player.getUniqueID();
        freezePos.put(uuid, new double[]{ player.posX, player.posY, player.posZ });

        if (!isRegistered(uuid)) {
            player.sendMessage(msg(TextFormatting.YELLOW,
                    "Добро пожаловать! Придумай пароль: /register <пароль>"));
        } else {
            player.sendMessage(msg(TextFormatting.YELLOW, "Введи пароль: /login <пароль>"));
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerLoggedOutEvent event) {
        UUID uuid = event.player.getUniqueID();
        authed.remove(uuid);
        freezePos.remove(uuid);
    }

    // Runs on server tick — iterates ONLY non-authenticated players (freezePos).
    // Fast-path: skips entirely when everyone is logged in.
    // Runs every 4 ticks (5x/sec) instead of every tick — 75% less work.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++authTick % 4 != 0) return;
        if (freezePos.isEmpty()) return; // everyone is authenticated

        boolean remind = (authTick % 100 == 0);
        if (authTick >= 100) authTick = 0;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        for (Map.Entry<UUID, double[]> entry : freezePos.entrySet()) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(entry.getKey());
            if (player == null) continue;
            double[] pos = entry.getValue();

            player.motionX = 0; player.motionY = 0; player.motionZ = 0;
            if (player.getDistanceSq(pos[0], pos[1], pos[2]) > 0.5) {
                player.setPositionAndUpdate(pos[0], pos[1], pos[2]);
            }

            if (remind) {
                player.sendMessage(isRegistered(entry.getKey())
                        ? msg(TextFormatting.YELLOW, "Введи пароль: /login <пароль>")
                        : msg(TextFormatting.YELLOW, "Зарегистрируйся: /register <пароль>"));
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ServerChatEvent event) {
        if (freezePos.isEmpty()) return; // все авторизованы — быстрый выход
        if (authed.contains(event.getPlayer().getUniqueID())) return;
        event.setCanceled(true);
        event.getPlayer().sendMessage(msg(TextFormatting.RED, "Сначала войди в аккаунт."));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        if (freezePos.isEmpty()) return; // все авторизованы — быстрый выход
        if (!(event.getSender() instanceof EntityPlayerMP)) return;
        UUID uuid = ((EntityPlayerMP) event.getSender()).getUniqueID();
        if (authed.contains(uuid)) return;

        String name = event.getCommand().getName();
        if ("login".equals(name) || "register".equals(name)) return;

        event.setCanceled(true);
        event.getSender().sendMessage(msg(TextFormatting.RED, "Сначала войди: /login <пароль>"));
    }

    // Пока игрок не вошёл (стоит на логине):
    //  1) он неуязвим — ни падение, ни мобы, ни огонь, ни голод не наносят урона;
    //  2) он сам никого не может бить — ни мобов, ни других игроков (ближний бой,
    //     стрелы и любой косвенный урон от него отменяются).
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(LivingAttackEvent event) {
        if (freezePos.isEmpty()) return; // все авторизованы — урон обрабатывается как обычно

        // 1. Незалогиненная ЖЕРТВА неуязвима.
        if (event.getEntity() instanceof EntityPlayerMP
                && !authed.contains(event.getEntity().getUniqueID())) {
            event.setCanceled(true);
            return;
        }

        // 2. Незалогиненный АТАКУЮЩИЙ не наносит урона никому.
        Entity src = event.getSource().getTrueSource();
        if (src instanceof EntityPlayerMP && !authed.contains(src.getUniqueID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (freezePos.isEmpty()) return; // все авторизованы — быстрый выход
        if (!(event.getPlayer() instanceof EntityPlayerMP)) return;
        if (!authed.contains(event.getPlayer().getUniqueID())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (freezePos.isEmpty()) return; // все авторизованы — быстрый выход
        if (!(event.getPlayer() instanceof EntityPlayerMP)) return;
        if (!authed.contains(event.getPlayer().getUniqueID())) event.setCanceled(true);
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try { hashes.put(UUID.fromString(e.getKey()), e.getValue().getAsString()); }
                catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load auth data", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            hashes.forEach((uuid, hash) -> obj.addProperty(uuid.toString(), hash));
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save auth data", e);
        }
    }

    // --- Helpers ---

    private String hash(UUID uuid, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(uuid.toString().getBytes(StandardCharsets.UTF_8));
            byte[] bytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private TextComponentString msg(TextFormatting color, String text) {
        return new TextComponentString(color + text);
    }
}
