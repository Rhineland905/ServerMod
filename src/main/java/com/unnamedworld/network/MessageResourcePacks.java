package com.unnamedworld.network;

import com.unnamedworld.ServerMod;
import com.unnamedworld.manager.ModCheckManager;
import com.unnamedworld.manager.TelegramManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Клиент → сервер: список включённых ресурспаков игрока (шлёт клиентский мод
 * UnnamedWorld при заходе). Логируем в консоль и дублируем в Telegram; имена паков
 * сверяем с чёрным списком ModCheck (ловит паки вида "XRay Ultimate.zip").
 * Формат провода должен совпадать с одноимённым пакетом в клиентском моде.
 */
public class MessageResourcePacks implements IMessage {

    // Защита от кривых/злонамеренных пакетов: не читаем больше разумного.
    private static final int MAX_PACKS    = 64;
    private static final int MAX_NAME_LEN = 128;

    public final List<String> packs = new ArrayList<>();

    public MessageResourcePacks() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = Math.max(0, Math.min(buf.readInt(), MAX_PACKS));
        for (int i = 0; i < n; i++) {
            String s = ByteBufUtils.readUTF8String(buf);
            packs.add(s.length() > MAX_NAME_LEN ? s.substring(0, MAX_NAME_LEN) : s);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(packs.size());
        for (String s : packs) ByteBufUtils.writeUTF8String(buf, s);
    }

    public static class Handler implements IMessageHandler<MessageResourcePacks, IMessage> {
        @Override
        public IMessage onMessage(MessageResourcePacks message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            List<String> packs = new ArrayList<>(message.packs);
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server == null) return null;
            // Обработчик выполняется в сетевом потоке — переносим работу на главный.
            server.addScheduledTask(() -> {
                String name = player.getName();
                List<String> hits = ModCheckManager.INSTANCE.findSuspicious(packs);
                ServerMod.LOGGER.info("[RPCheck] {}: {} ресурспаков: {}", name, packs.size(),
                        packs.isEmpty() ? "(нет включённых)" : String.join(", ", packs));
                if (!hits.isEmpty()) {
                    ServerMod.LOGGER.warn("[RPCheck] >>> ВНИМАНИЕ: у игрока {} подозрительные ресурспаки: {}",
                            name, String.join(", ", hits));
                }
                TelegramManager.INSTANCE.notifyResourcePacks(name, packs, hits);
            });
            return null;
        }
    }
}
