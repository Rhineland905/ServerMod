package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketPlayerListHeaderFooter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

public class TabListManager {

    public static final TabListManager INSTANCE = new TabListManager();

    private static final int UPDATE_INTERVAL = 20;
    private int tickCounter = 0;

    private String cachedHeaderJson;

    public void init() {
        ITextComponent header = new TextComponentString(
                TextFormatting.GOLD + "" + TextFormatting.BOLD + "[ " + ServerMod.SERVER_NAME + " ]");
        cachedHeaderJson = ITextComponent.Serializer.componentToJson(header);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        double tps = calculateTPS(server);
        TextFormatting tpsColor = tps >= 18.0 ? TextFormatting.GREEN
                                : tps >= 15.0 ? TextFormatting.YELLOW
                                : TextFormatting.RED;
        String tpsText = TextFormatting.GRAY + "TPS: " + tpsColor + String.format("%.1f", tps)
                + TextFormatting.DARK_GRAY + " / 20.0";

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            EntityPlayerMP player = players.get(i);
            int ping = player.ping;
            TextFormatting pingColor = ping < 80  ? TextFormatting.GREEN
                                     : ping < 200 ? TextFormatting.YELLOW
                                     : TextFormatting.RED;
            String pingText = TextFormatting.GRAY + "Ping: " + pingColor + ping + TextFormatting.GRAY + " ms";

            ITextComponent footer = new TextComponentString(
                    pingText + TextFormatting.DARK_GRAY + "  |  " + tpsText);

            SPacketPlayerListHeaderFooter packet = buildPacket(cachedHeaderJson, footer);
            if (packet != null) player.connection.sendPacket(packet);
        }
    }

    // Send header/footer immediately when a player joins (before the first tick fires)
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        ITextComponent footer = new TextComponentString(
                TextFormatting.GRAY + "Добро пожаловать на " + TextFormatting.GOLD + ServerMod.SERVER_NAME);
        SPacketPlayerListHeaderFooter packet = buildPacket(cachedHeaderJson, footer);
        if (packet != null) player.connection.sendPacket(packet);
    }

    // --- Helpers ---

    /**
     * Builds SPacketPlayerListHeaderFooter without reflection.
     * We serialise the two components into a PacketBuffer and let the packet
     * read itself back — same path the client uses when receiving from the network.
     */
    private SPacketPlayerListHeaderFooter buildPacket(String headerJson, ITextComponent footer) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            buf.writeString(headerJson);
            buf.writeString(ITextComponent.Serializer.componentToJson(footer));
            SPacketPlayerListHeaderFooter packet = new SPacketPlayerListHeaderFooter();
            packet.readPacketData(buf);
            return packet;
        } catch (Exception e) {
            ServerMod.LOGGER.error("TabListManager: failed to build header/footer packet", e);
            return null;
        } finally {
            buf.release();
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
