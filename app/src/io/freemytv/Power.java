package io.freemytv;

import android.content.Context;

import java.io.IOException;

/**
 * The screensaver and sleep timeouts (Settings, Screensaver), set through the TV's own ADB (`settings put`), as Android
 * TV's Settings would, and read back. TCL's own Settings keeps each "when inactive" choice 61 s short (15 min is
 * 839 000 ms) and reads either form as that choice: on TCL the choices are TCL's and written its way, so both Settings
 * show the same. Its 4 h auto-off (attentive_timeout) comes first anyway, so they stop at 4 h. Blocking: never on the
 * main thread.
 */
final class Power {
    static final class Timeouts {
        int screensaver;       // seconds idle
        int sleep;             // seconds idle (the screensaver counts); -1: never
        boolean stayOnPlugged; // Android keeps the screen on while plugged in (stay_on_while_plugged_in)
    }

    /** Seconds idle before the screensaver starts: 30 s to 30 min. */
    static final int[] SCREENSAVER = {30, 60, 120, 300, 600, 900, 1800};
    /** Seconds idle before the TV sleeps: 5 min to 4 h, or never; on TCL, TCL's own choices. */
    static final int[] SLEEP = RemoteKeys.TCL ? new int[] {900, 1200, 3600, 7200, 14400}
            : new int[] {300, 600, 900, 1800, 3600, 7200, 14400, -1};
    /** How much shorter TCL writes its choices. */
    private static final long TCL_SHORT_MS = 61_000;

    private Power() {}

    static Timeouts timeouts(Context c) throws IOException {
        try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
            return timeouts(adb);
        }
    }

    static Timeouts setScreensaver(Context c, int seconds) throws IOException {
        return set(c, "system screen_off_timeout", seconds * 1000L);
    }

    static Timeouts setSleep(Context c, int seconds) throws IOException {
        long ms = seconds < 0 ? -1 : seconds * 1000L - (RemoteKeys.TCL ? TCL_SHORT_MS : 0);
        return set(c, "secure sleep_timeout", ms);
    }

    private static Timeouts set(Context c, String setting, long ms) throws IOException {
        try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
            adb.shell("settings put " + setting + " " + ms);
            AppLog.w("power: " + setting + " = " + ms);
            return timeouts(adb);
        }
    }

    private static Timeouts timeouts(Adb adb) throws IOException {
        Timeouts t = new Timeouts();
        t.screensaver = seconds(adb.shell("settings get system screen_off_timeout"));
        t.sleep = sleepSeconds(adb.shell("settings get secure sleep_timeout"));
        t.stayOnPlugged = !adb.shell("settings get global stay_on_while_plugged_in").trim().equals("0");
        return t;
    }

    /** Milliseconds as `settings get` prints them, in seconds; -1 for never (negative, or unset: "null"). */
    private static int seconds(String ms) {
        try {
            long v = Long.parseLong(ms.trim());
            return v < 0 ? -1 : (int) Math.round(v / 1000.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The sleep timeout in seconds; TCL's 61 s short form read as its choice. */
    private static int sleepSeconds(String ms) {
        int s = seconds(ms);
        if (RemoteKeys.TCL && s > 0) {
            for (int choice : SLEEP) if (choice * 1000L - TCL_SHORT_MS == s * 1000L) return choice;
        }
        return s;
    }

    /** A timeout in seconds, as the settings show it: 30 s, 5 min, 2 h. */
    static String seconds(int s) {
        if (s < 60) return s + (Ui.ru ? " с" : " s");
        return duration(Math.round(s / 60f));
    }

    /** A length of time: 30 min, 2 h, 1 h 30 min. */
    static String duration(int minutes) {
        String min = Ui.ru ? " мин" : " min", h = Ui.ru ? " ч" : " h";
        if (minutes < 60) return minutes + min;
        return minutes % 60 == 0 ? (minutes / 60) + h : (minutes / 60) + h + " " + (minutes % 60) + min;
    }
}
