package com.unnamedworld.manager;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.network.play.server.SPacketDestroyEntities;
import net.minecraft.network.play.server.SPacketEntityMetadata;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketSpawnPlayer;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Iterator;
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

        // Broadcast fake "left the game" — other players think the OP disconnected
        ITextComponent leaveMsg = new TextComponentTranslation(
                "multiplayer.player.left", player.getDisplayName());
        leaveMsg.getStyle().setColor(TextFormatting.YELLOW);
        server.getPlayerList().sendMessage(leaveMsg);
    }

    public void unvanish(EntityPlayerMP player, MinecraftServer server) {
        UUID uuid = player.getUniqueID();
        if (!vanished.contains(uuid)) return;
        vanished.remove(uuid);

        player.removePotionEffect(MobEffects.INVISIBILITY);

        // Restore tab entry for everyone including self, then re-spawn for others.
        // ADD_PLAYER to self restores their own name in their own tab.
        // Never send SPacketSpawnPlayer to self — that crashes the client.
        SPacketPlayerListItem tabAdd = new SPacketPlayerListItem(
                SPacketPlayerListItem.Action.ADD_PLAYER, player);
        SPacketSpawnPlayer spawnPacket = new SPacketSpawnPlayer(player);
        SPacketEntityMetadata metaPacket = new SPacketEntityMetadata(
                player.getEntityId(), player.getDataManager(), true);

        for (EntityPlayerMP other : server.getPlayerList().getPlayers()) {
            other.connection.sendPacket(tabAdd);             // restore tab for everyone
            if (other.getUniqueID().equals(uuid)) continue;  // don't spawn own entity
            other.connection.sendPacket(spawnPacket);
            other.connection.sendPacket(metaPacket);
        }

        // Broadcast fake "joined the game" — other players see the OP appear
        ITextComponent joinMsg = new TextComponentTranslation(
                "multiplayer.player.joined", player.getDisplayName());
        joinMsg.getStyle().setColor(TextFormatting.YELLOW);
        server.getPlayerList().sendMessage(joinMsg);
    }

    // --- Periodic re-hide ---
    // Vanilla sends UPDATE_LATENCY (with all online players) every 600 ticks,
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

        Iterator<UUID> it = vanished.iterator();
        while (it.hasNext()) {
            UUID uid = it.next();
            EntityPlayerMP vp = server.getPlayerList().getPlayerByUUID(uid);
            if (vp == null) { it.remove(); continue; }
            hideFromAll(vp, server);
        }
    }

    // --- Events ---

    // When a new player joins:
    // 1. Hide existing vanished players from them
    // 2. Auto-vanish the new player if they are OP (level >= 2) — broadcasts fake leave
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;

        EntityPlayerMP newPlayer = (EntityPlayerMP) event.player;
        UUID newUuid = newPlayer.getUniqueID();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // Snapshot before the task so we don't race with other modifications
        if (vanished.isEmpty()) return;
        Set<UUID> snapshot = new HashSet<>(vanished);

        server.addScheduledTask(() -> {
            // Hide currently vanished players from the new player
            for (UUID uid : snapshot) {
                if (uid.equals(newUuid)) continue;
                EntityPlayerMP vp = server.getPlayerList().getPlayerByUUID(uid);
                if (vp == null) { vanished.remove(uid); continue; }
                newPlayer.connection.sendPacket(new SPacketPlayerListItem(
                        SPacketPlayerListItem.Action.REMOVE_PLAYER, vp));
                newPlayer.connection.sendPacket(new SPacketDestroyEntities(vp.getEntityId()));
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

    // Hides player from everyone, including themselves (so they don't see their own
    // name in tab while vanished). SPacketDestroyEntities is NOT sent to self —
    // destroying your own entity crashes the client. REMOVE_PLAYER to self is safe:
    // on a dedicated server the tab header/footer still renders even with 0 entries.
    private void hideFromAll(EntityPlayerMP player, MinecraftServer server) {
        UUID uuid = player.getUniqueID();
        SPacketPlayerListItem tabRemove = new SPacketPlayerListItem(
                SPacketPlayerListItem.Action.REMOVE_PLAYER, player);
        SPacketDestroyEntities destroyPacket =
                new SPacketDestroyEntities(player.getEntityId());

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP other = players.get(i);
            other.connection.sendPacket(tabRemove);          // remove from tab for everyone
            if (other.getUniqueID().equals(uuid)) continue;  // don't destroy own entity
            other.connection.sendPacket(destroyPacket);
        }
    }
}
