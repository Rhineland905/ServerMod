package com.unnamedworld.manager;

import java.util.Locale;

/**
 * Разбор и вывод длительностей для модерских команд (мут, бан…).
 *
 * Формат времени: голое число = МИНУТЫ, либо суффикс s/m/h/d (10s, 30m, 2h, 1d),
 * либо «perm»/«permanent»/«forever»/«навсегда»/«0»/«-1» = навсегда.
 */
public final class Durations {

    private Durations() {}

    /** Возвращается из {@link #parse(String)}, если строка вообще не похожа на время. */
    public static final long NOT_DURATION = Long.MIN_VALUE;

    /**
     * @return длительность в секундах; 0 — навсегда; {@link #NOT_DURATION} — это не время.
     */
    public static long parse(String s) {
        if (s == null || s.isEmpty()) return NOT_DURATION;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.equals("perm") || t.equals("permanent") || t.equals("forever")
                || t.equals("навсегда") || t.equals("-1") || t.equals("0")) return 0L;

        char unit = t.charAt(t.length() - 1);
        long mult;
        String num;
        switch (unit) {
            case 's': mult = 1L;     num = t.substring(0, t.length() - 1); break;
            case 'm': mult = 60L;    num = t.substring(0, t.length() - 1); break;
            case 'h': mult = 3600L;  num = t.substring(0, t.length() - 1); break;
            case 'd': mult = 86400L; num = t.substring(0, t.length() - 1); break;
            default:  mult = 60L;    num = t; break; // голое число = минуты
        }
        if (num.isEmpty()) return NOT_DURATION;
        try {
            long value = Long.parseLong(num);
            if (value <= 0) return NOT_DURATION;
            return value * mult;
        } catch (NumberFormatException e) {
            return NOT_DURATION;
        }
    }

    /** Человекочитаемая длительность: «1д 2ч 3м 4с». */
    public static String format(long seconds) {
        if (seconds <= 0) return "0с";
        long d = seconds / 86400; seconds %= 86400;
        long h = seconds / 3600;  seconds %= 3600;
        long mm = seconds / 60;   long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (mm > 0) sb.append(mm).append("м ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("с");
        return sb.toString().trim();
    }
}
