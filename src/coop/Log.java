package coop;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Tiny logger; output goes to coop\logs\<tag>.log (the launcher redirects stdout). */
public final class Log {
    private Log() {}

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static synchronized void info(String msg) {
        System.out.println(LocalTime.now().format(F) + " [coop] " + msg);
        System.out.flush();
    }

    public static synchronized void error(String where, Throwable t) {
        System.out.println(LocalTime.now().format(F) + " [coop] ERROR in " + where + ": " + t);
        t.printStackTrace(System.out);
        System.out.flush();
    }

    private static long lastRate;

    /** Logs at most a few lines per second (for per-frame code paths). */
    public static void rare(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastRate > 2000) {
            lastRate = now;
            info(msg);
        }
    }
}
