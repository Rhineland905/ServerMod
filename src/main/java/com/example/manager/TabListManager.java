package com.example.manager;

import com.example.SampleMod112;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketPlayerListHeaderFooter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;
import java.util.List;

public class TabListManager {

    public static final TabListManager INSTANCE = new TabListManager();

    private static final int UPDATE_INTERVAL = 20;
    private int tickCounter = 0;

    private Field packetHeaderField;
    private Field packetFooterField;
    private ITextComponent cachedHeader;

    public void init() {
        for (Field f : SPacketPlayerListHeaderFooter.class.getDeclaredFields()) {
            if (!ITextComponent.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            if (packetHeaderField == null) packetHeaderField = f;
            else                           packetFooterField = f;
        }
        if (packetHeaderField == null || packetFooterField == null) {
            SampleMod112.LOGGER.error("Could not find ITextComponent fields in SPacketPlayerListHeaderFooter");
        }
        cachedHeader = new TextComponentString(
                TextFormatting.GOLD + "" + TextFormatting.BOLD + "[ " + SampleMod112.SERVER_NAME + " ]");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        if (packetHeaderField == null || packetFooterField == null) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        double tps = calculateTPS(server);
        TextFormatting tpsColor = tps >= 18.0 ? TextFormatting.GREEN : tps >= 15.0 ? TextFormatting.YELLOW : TextFormatting.RED;
        String tpsPart = TextFormatting.GRAY + "TPS: " + tpsColor + String.format("%.1f", tps)
                + TextFormatting.DARK_GRAY + " / 20.0";

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get(i);
            int ping = player.ping;
            TextFormatting pingColor = ping < 80 ? TextFormatting.GREEN : ping < 200 ? TextFormatting.YELLOW : TextFormatting.RED;
            String pingPart = TextFormatting.GRAY + "Ping: " + pingColor + ping + TextFormatting.GRAY + " ms";

            ITextComponent footer = new TextComponentString(
                    pingPart + TextFormatting.DARK_GRAY + "  |  " + tpsPart);
            try {
                SPacketPlayerListHeaderFooter packet = new SPacketPlayerListHeaderFooter();
                packetHeaderField.set(packet, cachedHeader);
                packetFooterField.set(packet, footer);
                player.connection.sendPacket(packet);
            } catch (Exception e) {
                SampleMod112.LOGGER.error("Failed to send tab list packet to {}", player.getName(), e);
            }
        }
    }

    private double calculateTPS(MinecraftServer server) {
        long[] tickTimes = server.tickTimeArray;
        if (tickTimes == null || tickTimes.length == 0) return 20.0;
        long sum = 0;
        for (long t : tickTimes) sum += t;
        double meanMs = (sum / (double) tickTimes.length) * 1.0e-6;
        return Math.min(1000.0 / meanMs, 20.0);
    }
}
