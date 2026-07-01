package com.unnamedworld.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сервер → клиент: масштаб модели для одного или нескольких игроков.
 * Формат провода должен совпадать с одноимённым пакетом в клиентском моде UnnamedWorld.
 */
public class MessageSize implements IMessage {

    public final Map<UUID, Float> scales = new LinkedHashMap<>();

    public MessageSize() {}

    public MessageSize(UUID uuid, float scale) {
        scales.put(uuid, scale);
    }

    public MessageSize(Map<UUID, Float> all) {
        scales.putAll(all);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            UUID uuid = new UUID(buf.readLong(), buf.readLong());
            scales.put(uuid, buf.readFloat());
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(scales.size());
        for (Map.Entry<UUID, Float> e : scales.entrySet()) {
            buf.writeLong(e.getKey().getMostSignificantBits());
            buf.writeLong(e.getKey().getLeastSignificantBits());
            buf.writeFloat(e.getValue());
        }
    }

    // Сервер этот пакет не принимает; пустой обработчик нужен только для registerMessage.
    public static class Handler implements IMessageHandler<MessageSize, IMessage> {
        @Override
        public IMessage onMessage(MessageSize message, MessageContext ctx) {
            return null;
        }
    }
}
