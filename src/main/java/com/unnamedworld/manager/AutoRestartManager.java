package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.Calendar;

/**
 * Авто-рестарт сервера с предупреждением игроков.
 *
 *  • Раз в N минут (настраивается) или разово через команду сервер аккуратно
 *    сохраняется и останавливается — панель хостинга (Pterodactyl) поднимает его заново.
 *  • ИЛИ режим «только предупреждать»: к фиксированному времени суток (например 00:00,
 *    когда рестарт делает САМА панель) мод лишь предупреждает игроков, ничего не останавливая.
 *  • Игроков предупреждает заранее: за 5 мин, 1 мин, 30 с, 10 с и обратным отсчётом 5..1.
 *  • Настройки переживают рестарт (autorestart.json).
 *
 * Управляется командой /autorestart.
 */
public class AutoRestartManager {

    public static final AutoRestartManager INSTANCE = new AutoRestartManager();

    // На скольких секундах ДО рестарта предупреждать (по убыванию).
    private static final int[] WARN_SECONDS = { 300, 60, 30, 10, 5, 4, 3, 2, 1 };

    private int everyMinutes = 0;        // 0 = авто-рестарт модом выключен (сохраняется)
    private int warnDailyMinute = -1;    // минута суток для предупреждений перед рестартом ПАНЕЛИ (-1 = выкл, сохраняется)

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private transient long restartAt = 0;   // epoch-millis запланированного рестарта; 0 = нет
    private transient int  warnIndex = 0;    // индекс в WARN_SECONDS — что ещё не объявили
    private transient boolean booted = false;
    private transient int prevDailyDelta = -1; // сек до времени на прошлой проверке (детект пересечения порога)
    private transient int dailyTickCtr = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "autorestart.json");
        load();
    }

    // --- API для команды ---

    /** Включить периодический авто-рестарт каждые N минут (0 — выключить). */
    public void setEvery(int minutes) {
        everyMinutes = Math.max(0, minutes);
        save();
        if (everyMinutes > 0) scheduleSeconds(everyMinutes * 60);
        else cancel();
    }

    public void off()                { everyMinutes = 0; save(); cancel(); }
    public void scheduleIn(int min)  { scheduleSeconds(Math.max(1, min) * 60); }
    public void scheduleNow()        { scheduleSeconds(30); }   // «сейчас» = через 30 с с предупреждением
    public void cancel()             { restartAt = 0; }
    public boolean isScheduled()     { return restartAt > 0; }
    public int  getEveryMinutes()    { return everyMinutes; }

    /** Предупреждать игроков перед рестартом панели в эту минуту суток (-1 — выключить). */
    public void setWarnDaily(int minuteOfDay) {
        warnDailyMinute = minuteOfDay;
        prevDailyDelta = -1;
        save();
    }
    public int getWarnDailyMinute() { return warnDailyMinute; }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append(everyMinutes > 0 ? "Авто-рестарт модом: каждые " + fmt(everyMinutes * 60L)
                                   : "Авто-рестарт модом: выкл");
        if (restartAt > 0) {
            long sec = Math.max(0, (restartAt - System.currentTimeMillis()) / 1000);
            sb.append(", ближайший через ").append(fmt(sec));
        }
        if (warnDailyMinute >= 0) {
            sb.append(". Предупреждения к ").append(fmtHHMM(warnDailyMinute)).append(" (рестарт делает панель)");
            if (prevDailyDelta > 0) sb.append(", осталось ").append(fmt(prevDailyDelta));
        } else {
            sb.append(". Ежедневные предупреждения: выкл");
        }
        return sb.append(".").toString();
    }

    // --- Движок ---

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // На первом тике после старта — взвести периодический таймер заново.
        if (!booted) {
            booted = true;
            if (everyMinutes > 0) scheduleSeconds(everyMinutes * 60);
        }

        checkDailyWarn(server);   // предупреждения к фикс. времени суток (рестарт делает панель)

        if (restartAt == 0) return;

        long now = System.currentTimeMillis();
        int secLeft = (int) Math.ceil((restartAt - now) / 1000.0);

        // Объявляем все пройденные пороги (обычно один за тик-секунду).
        while (warnIndex < WARN_SECONDS.length && secLeft <= WARN_SECONDS[warnIndex]) {
            announce(server, WARN_SECONDS[warnIndex]);
            warnIndex++;
        }

        if (now >= restartAt) doRestart(server);
    }

    /** Раз в секунду проверяет, не пора ли предупредить о рестарте панели в warnDailyMinute. Сервер НЕ останавливает. */
    private void checkDailyWarn(MinecraftServer server) {
        if (warnDailyMinute < 0) return;
        if (++dailyTickCtr < 20) return;   // ~раз в секунду (20 тиков)
        dailyTickCtr = 0;

        Calendar c = Calendar.getInstance();
        int curSec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
        int delta = warnDailyMinute * 60 - curSec;
        if (delta <= 0) delta += 86400;    // до следующего наступления времени

        if (prevDailyDelta > 0) {          // объявляем порог в момент его пересечения (один раз)
            for (int t : WARN_SECONDS) {
                if (prevDailyDelta > t && delta <= t) announce(server, t);
            }
        }
        prevDailyDelta = delta;
    }

    private void scheduleSeconds(int sec) {
        restartAt = System.currentTimeMillis() + sec * 1000L;
        // Пропускаем пороги больше общего времени (например «5 минут» для рестарта через 2 мин).
        warnIndex = 0;
        while (warnIndex < WARN_SECONDS.length && WARN_SECONDS[warnIndex] > sec) warnIndex++;
    }

    private void announce(MinecraftServer server, int sec) {
        String text;
        if (sec >= 60) {
            int m = sec / 60;
            text = TextFormatting.YELLOW + "⚠ Сервер перезапустится через " + m + " " + minutesWord(m) + ".";
        } else if (sec > 5) {
            text = TextFormatting.YELLOW + "⚠ Перезапуск сервера через " + sec + " сек.";
        } else {
            text = TextFormatting.RED + "" + TextFormatting.BOLD + "Перезапуск через " + sec + "...";
        }
        broadcast(server, text);
    }

    private void doRestart(MinecraftServer server) {
        restartAt = 0;
        broadcast(server, TextFormatting.RED + "" + TextFormatting.BOLD
                + "Сервер перезапускается. Заходите через минуту!");
        ServerMod.LOGGER.info("[AutoRestart] Инициирую остановку сервера для авто-рестарта.");
        server.initiateShutdown(); // сохраняет миры и останавливает; панель поднимет заново
    }

    private void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().sendMessage(new TextComponentString(text));
    }

    // --- Вспомогательное ---

    private static String fmt(long sec) {
        if (sec < 60) return sec + " сек";
        long h = sec / 3600, m = (sec % 3600) / 60;
        if (h > 0) return h + " ч " + m + " мин";
        return m + " мин";
    }

    private static String fmtHHMM(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private static String minutesWord(int n) {
        int t = n % 100, o = n % 10;
        if (t >= 11 && t <= 14) return "минут";
        if (o == 1) return "минуту";
        if (o >= 2 && o <= 4) return "минуты";
        return "минут";
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject o = gson.fromJson(r, JsonObject.class);
            if (o == null) return;
            if (o.has("everyMinutes"))    everyMinutes    = o.get("everyMinutes").getAsInt();
            if (o.has("warnDailyMinute")) warnDailyMinute = o.get("warnDailyMinute").getAsInt();
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load autorestart config", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject o = new JsonObject();
            o.addProperty("everyMinutes", everyMinutes);
            o.addProperty("warnDailyMinute", warnDailyMinute);
            gson.toJson(o, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save autorestart config", e);
        }
    }
}
