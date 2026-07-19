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

    // Логотип в табе рисуется КАК КАРТИНКА через шрифт-ресурспак UnnamedWorld:
    // хедер — это сетка PUA-символов U+E000.., которым ресурспак сопоставил плитки
    // лого (страница textures/font/unicode_page_e0.png + правленый glyph_sizes.bin).
    // Сетка 20×5 = 100 плиток (уменьшена с 32×8). Сетка ДОЛЖНА совпадать с нарезкой
    // в ресурспаке. Без этого ресурспака клиент символы не отрисует.
    private static final int LOGO_COLS = 20;
    private static final int LOGO_ROWS = 5;
    private static final int LOGO_PUA_START = 0xE000;

    public void init() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < LOGO_ROWS; row++) {
            if (row > 0) sb.append('\n');
            for (int col = 0; col < LOGO_COLS; col++) {
                sb.append((char) (LOGO_PUA_START + row * LOGO_COLS + col));
            }
        }
        ITextComponent header = new TextComponentString(sb.toString());
        cachedHeaderJson = ITextComponent.Serializer.componentToJson(header);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return; // никого онлайн — не считаем TPS и не строим пакеты

        double tps = calculateTPS(server);
        TextFormatting tpsColor = tps >= 18.0 ? TextFormatting.GREEN
                                : tps >= 15.0 ? TextFormatting.YELLOW
                                : TextFormatting.RED;
        String tpsText = TextFormatting.GRAY + "TPS: " + tpsColor + String.format("%.1f", tps)
                + TextFormatting.DARK_GRAY + " / 20.0";

        // Один переиспользуемый буфер на весь проход. Раньше на каждого игрока каждую
        // секунду выделялся и освобождался отдельный Netty-буфер, в который заново
        // кодировался один и тот же большой header-лого — лишний мусор для GC.
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            for (int i = 0; i < players.size(); i++) {
                EntityPlayerMP player = players.get(i);
                int ping = player.ping;
                TextFormatting pingColor = ping < 80  ? TextFormatting.GREEN
                                         : ping < 200 ? TextFormatting.YELLOW
                                         : TextFormatting.RED;
                String pingText = TextFormatting.GRAY + "Ping: " + pingColor + ping + TextFormatting.GRAY + " ms";

                ITextComponent footer = new TextComponentString(
                        pingText + TextFormatting.DARK_GRAY + "  |  " + tpsText);

                SPacketPlayerListHeaderFooter packet = buildPacket(buf, cachedHeaderJson, footer);
                if (packet != null) player.connection.sendPacket(packet);
            }
        } finally {
            buf.release();
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

    // Одиночный вызов (вход игрока): свой буфер, освобождаем здесь же.
    private SPacketPlayerListHeaderFooter buildPacket(String headerJson, ITextComponent footer) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        try {
            return buildPacket(buf, headerJson, footer);
        } finally {
            buf.release();
        }
    }

    /**
     * Builds SPacketPlayerListHeaderFooter without reflection, переиспользуя переданный
     * буфер (clear() перед записью — владелец буфера освобождает его сам).
     * We serialise the two components into a PacketBuffer and let the packet
     * read itself back — same path the client uses when receiving from the network.
     */
    private SPacketPlayerListHeaderFooter buildPacket(PacketBuffer buf, String headerJson, ITextComponent footer) {
        buf.clear();
        try {
            buf.writeString(headerJson);
            buf.writeString(ITextComponent.Serializer.componentToJson(footer));
            SPacketPlayerListHeaderFooter packet = new SPacketPlayerListHeaderFooter();
            packet.readPacketData(buf);
            return packet;
        } catch (Exception e) {
            ServerMod.LOGGER.error("TabListManager: failed to build header/footer packet", e);
            return null;
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
