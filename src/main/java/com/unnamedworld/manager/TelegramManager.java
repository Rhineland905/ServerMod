package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Отправка уведомлений в Telegram через Bot API (sendMessage).
 *
 * При входе игрока {@link ModCheckManager} вызывает {@link #notifyJoin}: уходит сообщение
 * с ником, числом модов, списком модов и подсветкой подозрительных (варнов).
 *
 * Сеть дёргается в ОТДЕЛЬНОМ потоке (демон), чтобы не блокировать тик сервера.
 * Настройки — telegram.json:
 *   enabled            — мастер-выключатель
 *   botToken           — токен бота от @BotFather
 *   chatId             — id чата/группы/канала (узнать через @userinfobot или getUpdates)
 *   notifyOnJoin       — слать сообщение на каждый вход
 *   notifyOnlySuspicious — слать ТОЛЬКО когда найдены подозрительные моды (заглушает обычные входы)
 *   includeModList     — добавлять в сообщение полный список модов
 */
public class TelegramManager {

    public static final TelegramManager INSTANCE = new TelegramManager();

    // --- Настройки (сохраняются) ---
    private boolean enabled = true;
    private String  botToken = "";
    private String  chatId = "";
    private boolean notifyOnJoin = true;
    private boolean notifyOnlySuspicious = false;
    private boolean includeModList = true;
    private boolean notifyReports = true;
    private boolean notifyResourcePacks = true;

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Однопоточный демон-исполнитель: сетевые вызовы не должны блокировать сервер.
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerMod-Telegram");
        t.setDaemon(true);
        return t;
    });

    public void init(File configDir) {
        saveFile = new File(configDir, "telegram.json");
        load();
    }

    public boolean isConfigured() { return enabled && !botToken.isEmpty() && !chatId.isEmpty(); }

    public void reload() { load(); }

    public String status() {
        return "Telegram: " + (enabled ? "вкл" : "выкл")
                + ", токен " + (botToken.isEmpty() ? "НЕ задан" : "задан")
                + ", чат " + (chatId.isEmpty() ? "НЕ задан" : chatId)
                + ", режим: " + (notifyOnlySuspicious ? "только подозрительные" : "все входы")
                + ", список модов: " + (includeModList ? "да" : "нет")
                + ", репорты: " + (notifyReports ? "да" : "нет")
                + ", ресурспаки: " + (notifyResourcePacks ? "да" : "нет") + ".";
    }

    // --- Уведомление о входе ---

    /** mods == null → список модов недоступен. suspicious — найденные подозрительные modid. */
    public void notifyJoin(String player, List<String> mods, List<String> suspicious) {
        if (!isConfigured()) return;
        boolean hasSus = suspicious != null && !suspicious.isEmpty();

        // Обычные входы можно глушить; подозрительные (варны) шлём всегда.
        if (!hasSus) {
            if (!notifyOnJoin) return;
            if (notifyOnlySuspicious) return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hasSus ? "⚠️ ВНИМАНИЕ — вход с подозрительными модами\n"
                         : "🟢 Вход на сервер\n");
        sb.append("Игрок: ").append(player).append("\n");

        if (mods == null) {
            sb.append("Моды: список недоступен (ванила/локальный клиент)");
        } else {
            sb.append("Модов: ").append(mods.size());
            if (hasSus) sb.append("\n⚠️ Подозрительные: ").append(String.join(", ", suspicious));
            if (includeModList) sb.append("\nСписок: ").append(String.join(", ", mods));
        }
        sendAsync(sb.toString());
    }

    /** Уведомление о новом репорте от игрока. */
    public void notifyReport(int id, String player, String message) {
        if (!isConfigured() || !notifyReports) return;
        sendAsync("📣 Репорт #" + id + "\nИгрок: " + player + "\nСообщение: " + message);
    }

    /** Список ресурспаков игрока (прислан клиентским модом). suspicious — совпавшие с чёрным списком. */
    public void notifyResourcePacks(String player, List<String> packs, List<String> suspicious) {
        if (!isConfigured() || !notifyResourcePacks) return;
        boolean hasSus = suspicious != null && !suspicious.isEmpty();
        if (!hasSus && notifyOnlySuspicious) return; // тихий режим: только варны

        StringBuilder sb = new StringBuilder();
        sb.append(hasSus ? "⚠️ ВНИМАНИЕ — подозрительные ресурспаки\n" : "🎨 Ресурспаки\n");
        sb.append("Игрок: ").append(player).append("\n");
        sb.append("Паков: ").append(packs.size());
        if (hasSus) sb.append("\n⚠️ Подозрительные: ").append(String.join(", ", suspicious));
        if (!packs.isEmpty()) sb.append("\nСписок: ").append(String.join(", ", packs));
        sendAsync(sb.toString());
    }

    /** Уведомление о кике (проверка клиента не пройдена). Шлётся всегда, как варн. */
    public void notifyKick(String player, String reason) {
        if (!isConfigured()) return;
        sendAsync("⛔ Кик\nИгрок: " + player + "\nПричина: " + reason);
    }

    /** Тестовое сообщение (для проверки настроек). true — если задача поставлена в очередь. */
    public boolean sendTest() {
        if (!isConfigured()) return false;
        sendAsync("✅ ServerMod: тест Telegram-уведомлений. Если ты это видишь — всё работает.");
        return true;
    }

    // --- Отправка ---

    private void sendAsync(String text) {
        final String msg = text.length() > 3900 ? text.substring(0, 3900) + "…" : text;
        try {
            sender.submit(() -> sendNow(msg));
        } catch (Exception e) {
            ServerMod.LOGGER.warn("[Telegram] не удалось поставить сообщение в очередь", e);
        }
    }

    private void sendNow(String text) {
        HttpURLConnection con = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + botToken + "/sendMessage");
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8")
                    + "&disable_web_page_preview=true"
                    + "&text=" + URLEncoder.encode(text, "UTF-8");
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code != 200) {
                ServerMod.LOGGER.warn("[Telegram] sendMessage вернул HTTP {} — {}", code, readStream(con.getErrorStream()));
            } else {
                drain(con.getInputStream());
            }
        } catch (Exception e) {
            ServerMod.LOGGER.warn("[Telegram] ошибка отправки: {}", e.toString());
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String readStream(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try { byte[] buf = new byte[1024]; while (in.read(buf) != -1) { /* выкачиваем и закрываем */ } in.close(); }
        catch (Exception ignored) {}
    }

    // --- Persistence ---

    private void load() {
        if (saveFile == null) return;
        if (!saveFile.exists()) { save(); return; }
        try (Reader r = new FileReader(saveFile)) {
            JsonObject o = gson.fromJson(r, JsonObject.class);
            if (o == null) return;
            if (o.has("enabled"))              enabled              = o.get("enabled").getAsBoolean();
            if (o.has("botToken"))             botToken             = o.get("botToken").getAsString().trim();
            if (o.has("chatId"))               chatId               = o.get("chatId").getAsString().trim();
            if (o.has("notifyOnJoin"))         notifyOnJoin         = o.get("notifyOnJoin").getAsBoolean();
            if (o.has("notifyOnlySuspicious")) notifyOnlySuspicious = o.get("notifyOnlySuspicious").getAsBoolean();
            if (o.has("includeModList"))       includeModList       = o.get("includeModList").getAsBoolean();
            if (o.has("notifyReports"))        notifyReports        = o.get("notifyReports").getAsBoolean();
            if (o.has("notifyResourcePacks"))  notifyResourcePacks  = o.get("notifyResourcePacks").getAsBoolean();
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Telegram] не удалось загрузить telegram.json", e);
        }
    }

    private void save() {
        if (saveFile == null) return;
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("botToken", botToken);
            o.addProperty("chatId", chatId);
            o.addProperty("notifyOnJoin", notifyOnJoin);
            o.addProperty("notifyOnlySuspicious", notifyOnlySuspicious);
            o.addProperty("includeModList", includeModList);
            o.addProperty("notifyReports", notifyReports);
            o.addProperty("notifyResourcePacks", notifyResourcePacks);
            gson.toJson(o, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("[Telegram] не удалось сохранить telegram.json", e);
        }
    }
}
