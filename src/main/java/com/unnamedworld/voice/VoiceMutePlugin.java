package com.unnamedworld.voice;

import com.unnamedworld.manager.MuteManager;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

import java.util.UUID;

/**
 * Плагин Simple Voice Chat: глушит ГОЛОС замученных игроков.
 *
 * Discovery полностью на стороне voicechat — он сканирует мод-джары на аннотацию
 * {@link ForgeVoicechatPlugin} (Forge ASMDataTable) и сам создаёт этот класс через
 * пустой конструктор. ServerMod НИГДЕ на него не ссылается, поэтому:
 *   • если мода voicechat нет — класс просто не грузится, краша не будет;
 *   • если есть — плагин подхватывается автоматически.
 *
 * Логика мута общая с чатом — единый источник правды {@link MuteManager}.
 */
@ForgeVoicechatPlugin
public class VoiceMutePlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "unnamedworld";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // ничего не нужно — состояние мутов живёт в MuteManager
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    /** Вызывается на каждый голосовой пакет (НЕ в главном потоке) — отменяем у замученных. */
    private void onMicrophone(MicrophonePacketEvent event) {
        if (!event.isCancellable()) return;
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;
        ServerPlayer player = sender.getPlayer();
        if (player == null) return;

        UUID uuid = player.getUuid();
        if (MuteManager.INSTANCE.isMuted(uuid)) {
            event.cancel(); // пакет не пойдёт другим игрокам — голос замучен
        }
    }
}
