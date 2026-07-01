package com.unnamedworld.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сервер → клиент: битовая маска косметики (плащ/рога) для игроков.
 * Формат провода должен совпадать с одноимённым пакетом в клиентском моде UnnamedWorld.
 */
public class MessageCosmetic implements IMessage {

    public final Map<UUID, Integer> masks = new LinkedHashMap<>();

    public MessageCosmetic() {}

    public MessageCosmetic(UUID uuid, int mask) {
        masks.put(uuid, mask);
    }

    public MessageCosmetic(Map<UUID, Integer> all) {
        masks.putAll(all);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            UUID uuid = new UUID(buf.readLong(), buf.readLong());
            masks.put(uuid, buf.readInt());
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(masks.size());
        for (Map.Entry<UUID, Integer> e : masks.entrySet()) {
            buf.writeLong(e.getKey().getMostSignificantBits());
            buf.writeLong(e.getKey().getLeastSignificantBits());
            buf.writeInt(e.getValue());
        }
    }

    // Сервер этот пакет не принимает; пустой обработчик нужен только для registerMessage.
    public static class Handler implements IMessageHandler<MessageCosmetic, IMessage> {
        @Override
        public IMessage onMessage(MessageCosmetic message, MessageContext ctx) {
            return null;
        }
    }
}
