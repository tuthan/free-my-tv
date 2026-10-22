package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The drawer's System card: CPU load and clock, memory, and the network (connection, Wi-Fi signal and speed, traffic,
 * address, internet). Only what any app may read: Android 8+ keeps /proc/stat from apps, so the CPU load comes from
 * the cores' idle time in sysfs (the same figure within a point, measured on the TCL); memory from ActivityManager;
 * the network from ConnectivityManager, WifiManager and TrafficStats. No temperature: apps can't read it.
 * Read every 2 s on its own thread, only while the card shows (start and stop, from HomeView).
 */
final class SystemStats {
    static final int NONE = 0, WIFI = 1, ETHERNET = 2, OTHER = 3;
    private static final String CPU = "/sys/devices/system/cpu/";
    private static final long EVERY_MS = 2000;

    interface Listener { void onStats(); }

    /** One reading, handed to the UI thread whole. */
    static final class Sample {
        long at;                        // 0: nothing read yet
        float cpu = Float.NaN;          // 0 to 1; NaN on the first reading (it takes two) or without idle counters
        float ghz = Float.NaN;          // the fastest core's clock now
        long memUsed, memTotal;
        int net = NONE;
        boolean internet;               // Android's own check passed (the network is "validated")
        int bars = -1;                  // Wi-Fi signal, 0 to 4
        int mhz, linkMbps;
        String ip = "";
        float rxBits = Float.NaN, txBits = Float.NaN;   // per second
    }

    private final Context app;
    private final ActivityManager activities;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "stats"));
    private final Runnable tick = this::tick;
    private Listener listener;
    private boolean on;
    private int generation;
    Sample last = new Sample();

    // The previous reading, for the rates (the IO thread only)
    private long[] idleBefore = new long[0];
    private long wallBefore, trafficAt, rxBefore = -1, txBefore = -1;

    SystemStats(Context c) {
        app = c.getApplicationContext();
        activities = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    void start(Listener l) {
        if (on) return;
        on = true;
        listener = l;
        int gen = ++generation;
        IO.execute(() -> read(gen, true));     // memory and the network right away; CPU and traffic need a second
        main.postDelayed(tick, 1000);
    }

    void stop() {
        if (!on) return;
        on = false;
        generation++;
        main.removeCallbacks(tick);
    }

    private void tick() {
        if (!on) return;
        int gen = generation;
        IO.execute(() -> read(gen, false));
        main.postDelayed(tick, EVERY_MS);
    }

    /** On the IO thread. `fresh`: the rates start over (the card was away). */
    private void read(int gen, boolean fresh) {
        Sample s = new Sample();
        try {
            readCpu(s, fresh);
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            activities.getMemoryInfo(m);
            s.memTotal = m.totalMem;
            s.memUsed = m.totalMem - m.availMem;
            readNetwork(s, fresh);
        } catch (RuntimeException e) {
            AppLog.w("system stats: " + e);
        }
        s.at = SystemClock.uptimeMillis();
        main.post(() -> {
            if (gen != generation || !on) return;
            last = s;
            listener.onStats();
        });
    }

    private void readCpu(Sample s, boolean fresh) {
        boolean[] online = cores(text(CPU + "online"));
        long wall = SystemClock.elapsedRealtimeNanos() / 1000;
        long[] idle = new long[online.length];
        float busy = 0, khz = 0;
        int counted = 0;
        for (int i = 0; i < online.length; i++) {
            idle[i] = -1;
            if (!online[i]) continue;
            khz = Math.max(khz, number(CPU + "cpu" + i + "/cpufreq/scaling_cur_freq"));
            long sum = 0;
            boolean any = false;
            for (int j = 0; j < 10; j++) {
                String f = CPU + "cpu" + i + "/cpuidle/state" + j + "/time";
                if (!new File(f).exists()) break;
                long t = number(f);
                if (t < 0) break;
                sum += t;
                any = true;
            }
            if (!any) continue;
            idle[i] = sum;
            // A core counts only if it was online last time too (an offline core's counters stand still).
            if (!fresh && i < idleBefore.length && idleBefore[i] >= 0 && wall > wallBefore) {
                long d = Math.max(0, Math.min(wall - wallBefore, sum - idleBefore[i]));
                busy += 1 - d / (float) (wall - wallBefore);
                counted++;
            }
        }
        idleBefore = idle;
        wallBefore = wall;
        if (counted > 0) s.cpu = Math.max(0, Math.min(1, busy / counted));
        if (khz > 0) s.ghz = khz / 1e6f;
    }

    @SuppressWarnings("deprecation")   // WifiManager.getConnectionInfo: still the one that works on Android 9
    private void readNetwork(Sample s, boolean fresh) {
        Network n = connectivity.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : connectivity.getNetworkCapabilities(n);
        if (caps != null) {
            s.net = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? WIFI
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? ETHERNET : OTHER;
            s.internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            LinkProperties lp = connectivity.getLinkProperties(n);
            if (lp != null) {
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) {
                        s.ip = a.getAddress().getHostAddress();
                        break;
                    }
                }
            }
        }
        if (s.net == WIFI && wifi != null) {
            WifiInfo w = wifi.getConnectionInfo();
            if (w != null && w.getFrequency() > 0) {     // not the network id: Android 10+ hides it without location
                s.bars = WifiManager.calculateSignalLevel(w.getRssi(), 5);
                s.mhz = w.getFrequency();
                s.linkMbps = w.getLinkSpeed();
            }
        }
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        if (!fresh && rx >= 0 && rxBefore >= 0 && rx >= rxBefore && tx >= txBefore && now > trafficAt) {
            float seconds = (now - trafficAt) / 1000f;
            s.rxBits = (rx - rxBefore) * 8 / seconds;
            s.txBits = (tx - txBefore) * 8 / seconds;
        }
        rxBefore = rx;
        txBefore = tx;
        trafficAt = now;
    }

    // MARK: - Text, for the card and TalkBack

    static String cpu(Sample s, boolean full) {
        String load = Float.isNaN(s.cpu) ? "—" : NumberFormat.getPercentInstance(Ui.locale()).format(s.cpu);
        return full && !Float.isNaN(s.ghz) ? load + " · " + String.format(Ui.locale(), "%.1f GHz", s.ghz) : load;
    }

    static String memory(Sample s, boolean full) {
        if (s.memTotal <= 0) return "—";
        if (!full) return NumberFormat.getPercentInstance(Ui.locale()).format(s.memUsed / (double) s.memTotal);
        return String.format(Ui.locale(), "%.1f / %.1f GB", s.memUsed / 1e9, s.memTotal / 1e9);
    }

    static String netLabel(Sample s) {
        return s.net == WIFI ? "Wi-Fi" : s.net == ETHERNET ? "Ethernet" : Ui.tr("Rede", "Network");
    }

    static String netValue(Sample s, boolean full) {
        if (s.at == 0) return "—";
        if (s.net == NONE) return Ui.tr("Sem conexão", "Offline");
        if (s.net != WIFI) return Ui.tr("Conectado", "Connected");
        String speed = s.linkMbps > 0 ? s.linkMbps + " Mb/s" : "";
        if (!full || s.mhz <= 0) return speed;
        String band = s.mhz < 3000 ? String.format(Ui.locale(), "%.1f GHz", 2.4) : s.mhz < 5925 ? "5 GHz" : "6 GHz";
        return speed.isEmpty() ? band : band + " · " + speed;
    }

    static String traffic(Sample s) {
        return "↓ " + rate(s.rxBits) + "   ↑ " + rate(s.txBits);
    }

    static String rate(float bits) {
        if (Float.isNaN(bits)) return "—";
        if (bits < 1e6f) return Math.round(bits / 1e3f) + " kb/s";
        if (bits < 1e8f) return String.format(Ui.locale(), "%.1f Mb/s", bits / 1e6f);
        return Math.round(bits / 1e6f) + " Mb/s";
    }

    /** "Internet OK", "No internet" or "Offline"; empty before the first reading. */
    static String status(Sample s) {
        if (s.at == 0) return "";
        return s.net == NONE ? Ui.tr("Sem conexão", "Offline")
                : s.internet ? Ui.tr("Internet OK", "Internet OK") : Ui.tr("Sem internet", "No internet");
    }

    // MARK: - Files

    /** "0-3" or "0,2-3" → which cores are online. */
    private static boolean[] cores(String list) {
        int max = -1;
        boolean[] out = new boolean[64];
        for (String part : list.trim().split(",")) {
            if (part.isEmpty()) continue;
            try {
                String[] r = part.split("-");
                int a = Integer.parseInt(r[0].trim()), b = r.length > 1 ? Integer.parseInt(r[1].trim()) : a;
                for (int i = a; i <= b && i < out.length; i++) {
                    out[i] = true;
                    max = Math.max(max, i);
                }
            } catch (NumberFormatException ignored) {
                // a list we don't understand: those cores aren't counted
            }
        }
        boolean[] trimmed = new boolean[max + 1];
        System.arraycopy(out, 0, trimmed, 0, max + 1);
        return trimmed;
    }

    private static long number(String path) {
        try {
            return Long.parseLong(text(path).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String text(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }
}
