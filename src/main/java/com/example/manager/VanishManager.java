package com.example.manager;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.network.play.server.SPacketDestroyEntities;
import net.minecraft.network.play.server.SPacketEntityMetadata;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketSpawnPlayer;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class VanishManager {

    public static final VanishManager INSTANCE = new VanishManager();

    private final Set<UUID> vanished = new HashSet<>();
    private int hideTick = 0;

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    // --- Vanish / Unvanish ---

    public void vanish(EntityPlayerMP player, MinecraftServer server) {
        UUID uuid = player.getUniqueID();
        if (vanished.contains(uuid)) return;
        vanished.add(uuid);

        // Amplitude 1 also hides armor and held items
        player.addPotionEffect(
                new PotionEffect(MobEffects.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));

        hideFromAll(player, server);
    }

    public void unvanish(EntityPlayerMP player, MinecraftServer server) {
        UUID uuid = player.getUniqueID();
        if (!vanished.contains(uuid)) return;
        vanished.remove(uuid);

        player.removePotionEffect(MobEffects.INVISIBILITY);

        // Re-add tab list entry first, then spawn entity, then send metadata.
        // tabAdd goes to everyone including self (restores own tab entry).
        // spawnPacket / metaPacket only go to others — sending spawn to self crashes the client.
        SPacketPlayerListItem tabAdd = new SPacketPlayerListItem(
                SPacketPlayerListItem.Action.ADD_PLAYER, player);
        SPacketSpawnPlayer spawnPacket = new SPacketSpawnPlayer(player);
        SPacketEntityMetadata metaPacket = new SPacketEntityMetadata(
                player.getEntityId(), player.getDataManager(), true);

        for (EntityPlayerMP other : server.getPlayerList().getPlayers()) {
            other.connection.sendPacket(tabAdd);           // restore tab for everyone
            if (other.getUniqueID().equals(uuid)) continue; // don't spawn self for self
            other.connection.sendPacket(spawnPacket);
            other.connection.sendPacket(metaPacket);
        }
    }

    // --- Periodic re-hide ---
    // Vanilla sends UPDATE_LATENCY (with all online players) every 20 ticks,
    // which re-adds vanished players to the tab list.
    // ServerTickEvent.END fires AFTER that broadcast, so we can undo it here.
    // SPacketDestroyEntities is also re-sent to handle entity tracker chunk updates.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (vanished.isEmpty()) return;
        if (++hideTick % 20 != 0) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        for (UUID uid : new HashSet<>(vanished)) {
            EntityPlayerMP vp = server.getPlayerList().getPlayerByUUID(uid);
            if (vp == null) { vanished.remove(uid); continue; }
            hideFromAll(vp, server);
        }
    }

    // --- Events ---

    // When a new player joins, hide vanished players from them after login packets
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        if (vanished.isEmpty()) return;

        EntityPlayerMP newPlayer = (EntityPlayerMP) event.player;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        Set<UUID> snapshot = new HashSet<>(vanished);
        server.addScheduledTask(() -> {
            for (UUID uid : snapshot) {
                EntityPlayerMP vp = server.getPlayerList().getPlayerByUUID(uid);
                if (vp == null) { vanished.remove(uid); continue; }
                newPlayer.connection.sendPacket(new SPacketPlayerListItem(
                        SPacketPlayerListItem.Action.REMOVE_PLAYER, vp));
                newPlayer.connection.sendPacket(
                        new SPacketDestroyEntities(vp.getEntityId()));
            }
        });
    }

    // Clean up when a vanished player disconnects
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        vanished.remove(event.player.getUniqueID());
    }

    // Mobs cannot target vanished players
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSetAttackTarget(LivingSetAttackTargetEvent event) {
        if (vanished.isEmpty()) return;
        if (!(event.getTarget() instanceof EntityPlayerMP)) return;
        if (!vanished.contains(((EntityPlayerMP) event.getTarget()).getUniqueID())) return;
        if (event.getEntityLiving() instanceof EntityLiving) {
            ((EntityLiving) event.getEntityLiving()).setAttackTarget(null);
        }
    }

    // --- Helper ---

    // Hides player from everyone — including themselves so they don't see
    // their own name in tab (confirming they are in vanish mode).
    // SPacketDestroyEntities is NOT sent to self — destroying your own entity
    // would break the client. tabRemove to self is safe.
    private void hideFromAll(EntityPlayerMP player, MinecraftServer server) {
        UUID uuid = player.getUniqueID();
        SPacketPlayerListItem tabRemove = new SPacketPlayerListItem(
                SPacketPlayerListItem.Action.REMOVE_PLAYER, player);
        SPacketDestroyEntities destroyPacket =
                new SPacketDestroyEntities(player.getEntityId());

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP other = players.get(i);
            other.connection.sendPacket(tabRemove);        // hide from tab for everyone
            if (other.getUniqueID().equals(uuid)) continue; // don't destroy self entity
            other.connection.sendPacket(destroyPacket);
        }
    }
}
