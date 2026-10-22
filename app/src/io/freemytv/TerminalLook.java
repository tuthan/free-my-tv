package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Terminal screensaver look: the time in a block font with the date under it, rebuilt at each new minute by one of
 * ttfx's effects (TtfxProcess) in the TV's own colours, on black. DreamView hosts it: the same drift and ten-minute dim
 * as its other looks, and its big clock instead if anything here fails (no binary, exec refused, a child that dies or
 * writes a frame that doesn't parse). The UI thread decides and draws; one "ttfx" thread starts the child, reads and
 * parses, so the engine never runs on the UI thread. No child outlives stop().
 */
final class TerminalLook {
    /** 88 × 23 cells of 20 × 40 px at 1080p: 80 px around, so the text stays inside the overscan with the drift. */
    private static final int COLS = 88, ROWS = 23;
    private static final float CELL_W = 20, CELL_H = 40;
    /** Effects that end within 20 s at this size (note 12, Phase 0): not rings (23 s) or swarm (50 s). */
    private static final String[] EFFECTS = {"beams", "binarypath", "blackhole", "bouncyballs", "bubbles", "burn",
            "colorshift", "crumble", "decrypt", "errorcorrect", "expand", "fireworks", "highlight", "laseretch",
            "matrix", "middleout", "orbittingvolley", "overflow", "pour", "print", "rain", "randomsequence",
            "scattered", "slice", "slide", "smoke", "spotlights", "spray", "sweep", "synthgrid", "thunderstorm",
            "unstable", "vhstape", "waves", "wipe"};
    /** The TV's colours (deep sea, jade, mint: tools/make_art.py), dark to light like ttfx's own default. */
    private static final String[] STOPS = {"0E6294", "14B8A6", "6EE7B7"};
    private static final String[] ANCHORS = {"n", "ne", "e", "se", "s", "sw", "w", "nw", "c"};
    /** At most this many of ttfx's 60 frames a second per frame drawn: a late draw skips frames, a stall slows down. */
    private static final int MAX_STEP = 6;

    /** Digits 5 × 9, a pixel being two cells ("██"); the colon is one pixel wide. */
    private static final String[][] FONT = {
            {" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "},
            {"  #  ", " ##  ", "# #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####"},
            {" ### ", "#   #", "    #", "    #", "   # ", "  #  ", " #   ", "#    ", "#####"},
            {" ### ", "#   #", "    #", "    #", "  ## ", "    #", "    #", "#   #", " ### "},
            {"   # ", "  ## ", " # # ", "#  # ", "#  # ", "#####", "   # ", "   # ", "   # "},
            {"#####", "#    ", "#    ", "#### ", "    #", "    #", "    #", "#   #", " ### "},
            {" ### ", "#   #", "#    ", "#    ", "#### ", "#   #", "#   #", "#   #", " ### "},
            {"#####", "    #", "    #", "   # ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "},
            {" ### ", "#   #", "#   #", "#   #", " ### ", "#   #", "#   #", "#   #", " ### "},
            {" ### ", "#   #", "#   #", "#   #", " ####", "    #", "    #", "#   #", " ### "},
    };
    private static final String[] COLON = {" ", " ", "#", " ", " ", " ", "#", " ", " "};

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> new Thread(r, "ttfx"));
    private final Random random = new Random();
    private final Recording[] rows = new Recording[ROWS];
    private final boolean[] dirty = new boolean[ROWS];
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint block = new Paint(), shade = new Paint();
    private final char[] one = new char[2];
    private final long cycleMs;     // 60 s; shorter only for tests (SettingsActivity's ttfx_cycle_s extra)

    // UI thread only.
    private int[] cells = new int[COLS * ROWS * 3], spare = new int[COLS * ROWS * 3];
    private volatile TtfxProcess process;    // also killed from the UI thread
    private int generation, framesRead, lastEffect = -1;
    private long period = -1, firstUp;
    private boolean pulling, ended, failed, running, stopped;
    private int w, h;
    private float cw, ch, left, top, baseline;

    TerminalLook(Context context, long cycleMs) {
        this.context = context;
        this.cycleMs = cycleMs > 0 ? cycleMs : 60_000;
        for (int i = 0; i < ROWS; i++) {
            rows[i] = Recording.create("ttfx row " + i);
            rows[i].setHasOverlappingRendering(false);
        }
        glyph.setTypeface(Typeface.MONOSPACE);
        glyph.setTextAlign(Paint.Align.CENTER);
    }

    boolean failed() {
        return failed;
    }

    void setSize(int width, int height) {
        w = width;
        h = height;
        float k = w / 1920f;
        cw = CELL_W * k;
        ch = CELL_H * k;
        left = Math.round((w - COLS * cw) / 2);
        top = Math.round((h - ROWS * ch) / 2);
        // DroidSansMono's advance is 0.6 em: a glyph fills the cell's width.
        glyph.setTextSize(cw / 0.6f);
        Paint.FontMetrics m = glyph.getFontMetrics();
        baseline = (ch - (m.descent - m.ascent)) / 2 - m.ascent;
        for (int i = 0; i < ROWS; i++) {
            rows[i].setPosition(Math.round(left), Math.round(top + i * ch),
                    Math.round(left + COLS * cw), Math.round(top + (i + 1) * ch));
            dirty[i] = true;
        }
    }

    void start() {
        running = true;
        period = -1;
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        running = false;
        generation++;
        TtfxProcess p = process;
        process = null;
        if (p != null) p.kill();
        thread.execute(() -> {
            if (p != null) p.waitFor();
        });
        thread.shutdown();
    }

    /**
     * One frame drawn (20 a second): a new run at each new minute, the frames due since the run's first one, then the
     * grid, moved by the drift and dimmed over black.
     */
    void draw(Canvas c, long now, int dx, int dy, float dim) {
        long up = SystemClock.uptimeMillis();
        if (running && !failed && now / cycleMs != period) {
            period = now / cycleMs;
            begin(now);
        }
        if (running && !failed && !pulling && !ended) {
            long behind = Math.round((up - firstUp) * 60 / 1000.0) + 1 - framesRead;
            int due = framesRead == 0 ? 1 : (int) Math.min(MAX_STEP, behind);
            if (due > 0) pull(due);
        }
        for (int r = 0; r < ROWS; r++) {
            if (dirty[r]) record(r);
            rows[r].setTranslationX(dx);
            rows[r].setTranslationY(dy);
            rows[r].draw(c);
        }
        if (dim < 1) {
            block.setColor(Math.round((1 - dim) * 255) << 24);
            c.drawRect(0, 0, w, h, block);
        }
    }

    // MARK: - The runs

    private void begin(long now) {
        int gen = ++generation;
        TtfxProcess old = process;
        process = null;
        if (old != null) old.kill();    // its frame stays on screen until the new one's first frame
        framesRead = 0;
        ended = false;
        pulling = true;
        String text = text(Ui.clock(now), Ui.date(now));
        List<String> args = args();
        thread.execute(() -> {
            if (old != null) old.waitFor();
            TtfxProcess p;
            try {
                p = new TtfxProcess(context, args, text);
            } catch (IOException e) {
                post(gen, () -> fail("can't start ttfx: " + e.getMessage()));
                return;
            }
            post(gen, () -> {
                process = p;
                pulling = false;
            }, p::kill);    // a newer run or stop() came first (the system reaps the child)
        });
    }

    private List<String> args() {
        int e;
        do e = random.nextInt(EFFECTS.length); while (e == lastEffect);
        lastEffect = e;
        String effect = EFFECTS[e];
        String seed = Integer.toString(random.nextInt(Integer.MAX_VALUE));
        String anchor = ANCHORS[random.nextInt(ANCHORS.length)];
        List<String> a = new ArrayList<>(Arrays.asList("--parity-dump", "--seed", seed,
                "--canvas-width", Integer.toString(COLS), "--canvas-height", Integer.toString(ROWS),
                "--ignore-terminal-dimensions", "--anchor-canvas", "c", "--anchor-text", anchor, effect));
        if (effect.equals("synthgrid")) {     // the one effect without a final gradient: its grid and text instead
            a.add("--grid-gradient-stops");
            a.addAll(Arrays.asList(STOPS));
            a.add("--text-gradient-stops");
            a.addAll(Arrays.asList(STOPS));
        } else {
            a.add("--final-gradient-stops");
            a.addAll(Arrays.asList(STOPS));
            a.add("--final-gradient-direction");
            a.add("horizontal");    // vertical would leave the date, at the bottom, in the darkest colour
        }
        if (effect.equals("matrix")) a.addAll(Arrays.asList("--rain-time", "6"));   // 17 s instead of 26
        return a;
    }

    /** Reads the frames due on the ttfx thread and hands the newest one, parsed, to the UI thread. */
    private void pull(int n) {
        TtfxProcess p = process;
        if (p == null) return;
        int gen = generation;
        int[] into = spare;
        pulling = true;
        thread.execute(() -> {
            String error = null;
            int got = 0;
            boolean end;
            try {
                got = p.read(n);
                end = got < n;
                if (got > 0) p.cells(COLS, ROWS, into);
            } catch (IOException e) {
                error = "ttfx frame: " + e.getMessage();
                end = true;
            }
            if (end) {
                int code = p.waitFor();
                if (error == null && (code != 0 || p.frames() == 0)) {
                    error = "ttfx " + (p.frames() == 0 ? "ended before its first frame" : "died")
                            + " (exit " + code + "): " + p.error();
                }
            }
            final int frames = got;
            final boolean over = end;
            final String why = error;
            post(gen, () -> {
                pulling = false;
                if (why != null) {
                    fail(why);
                    return;
                }
                if (frames > 0) show(into, frames);
                ended = over;
            });
        });
    }

    /** Runs ui on the UI thread unless a newer run (or stop) came first; then stale instead, if any (also on it). */
    private void post(int gen, Runnable ui) {
        post(gen, ui, null);
    }

    private void post(int gen, Runnable ui, Runnable stale) {
        handler.post(() -> {
            if (gen == generation) ui.run();
            else if (stale != null) stale.run();
        });
    }

    private void fail(String why) {
        if (failed) return;
        failed = true;
        AppLog.w("screensaver: " + why + "; the big clock instead");
        stop();
    }

    private void show(int[] next, int frames) {
        if (framesRead == 0) firstUp = SystemClock.uptimeMillis();
        framesRead += frames;
        int[] old = cells;
        cells = next;
        spare = old;
        int stride = COLS * 3;
        for (int r = 0; r < ROWS; r++) {
            if (!dirty[r]) {
                for (int i = r * stride, end = i + stride; i < end; i++) {
                    if (old[i] != next[i]) {
                        dirty[r] = true;
                        break;
                    }
                }
            }
        }
    }

    // MARK: - Drawing a row

    private void record(int r) {
        dirty[r] = false;
        Canvas c = rows[r].beginRecording(Math.round(COLS * cw), Math.round(ch));
        try {
            int base = r * COLS * 3;
            for (int col = 0; col < COLS; col++) {
                int bg = cells[base + col * 3 + 2];
                if (bg == 0) continue;
                int run = col;
                while (run + 1 < COLS && cells[base + (run + 1) * 3 + 2] == bg) run++;
                block.setColor(bg);
                c.drawRect(x(col), 0, x(run + 1), Math.round(ch), block);
                col = run;
            }
            for (int col = 0; col < COLS; col++) {
                int cp = cells[base + col * 3];
                if (cp == 0) continue;
                int fg = cells[base + col * 3 + 1];
                if (!blockElement(c, cp, fg, cells[base + col * 3 + 2], x(col), x(col + 1), Math.round(ch))) {
                    glyph.setColor(fg);
                    int n = Character.toChars(cp, one, 0);
                    c.drawText(one, 0, n, (x(col) + x(col + 1)) / 2f, baseline, glyph);
                }
            }
        } finally {
            rows[r].endRecording();
        }
    }

    private int x(int col) {
        return Math.round(col * cw);
    }

    /**
     * U+2580–259F and ─ │ as rectangles that fill the cell exactly, as terminals draw them (note 12, S4): no seams, and
     * the TV's fonts have none of them at a fixed width. The shades ░ ▒ ▓ are the colour at 25, 50 and 75 % over the
     * background. False for any other character.
     */
    private boolean blockElement(Canvas c, int cp, int fg, int bg, int l, int r, int b) {
        int cwi = r - l, mid = b / 2, line = Math.max(2, Math.round(cwi / 8f));
        block.setColor(fg);
        if (cp == 0x2500) c.drawRect(l, mid - line / 2, r, mid - line / 2 + line, block);
        else if (cp == 0x2502) c.drawRect(l + (cwi - line) / 2, 0, l + (cwi - line) / 2 + line, b, block);
        else if (cp < 0x2580 || cp > 0x259F) return false;
        else if (cp == 0x2580) c.drawRect(l, 0, r, mid, block);
        else if (cp <= 0x2588) c.drawRect(l, b - Math.round(b * (cp - 0x2580) / 8f), r, b, block);      // ▁ to █
        else if (cp <= 0x258F) c.drawRect(l, 0, l + Math.round(cwi * (0x2590 - cp) / 8f), b, block);   // ▉ to ▏
        else if (cp == 0x2590) c.drawRect(l + cwi / 2, 0, r, b, block);
        else if (cp <= 0x2593) {
            float a = (cp - 0x2590) / 4f;
            int under = bg == 0 ? 0xFF000000 : bg;
            shade.setColor(mix(under, fg, a));
            c.drawRect(l, 0, r, b, shade);
        } else if (cp == 0x2594) c.drawRect(l, 0, r, Math.round(b / 8f), block);
        else if (cp == 0x2595) c.drawRect(r - Math.round(cwi / 8f), 0, r, b, block);
        else {
            // Quadrants ▖ to ▟, as bits: upper left 1, upper right 2, lower left 4, lower right 8.
            int q = new int[] {4, 8, 1, 13, 9, 7, 11, 2, 6, 14}[cp - 0x2596];
            int cx = l + cwi / 2;
            if ((q & 1) != 0) c.drawRect(l, 0, cx, mid, block);
            if ((q & 2) != 0) c.drawRect(cx, 0, r, mid, block);
            if ((q & 4) != 0) c.drawRect(l, mid, cx, b, block);
            if ((q & 8) != 0) c.drawRect(cx, mid, r, b, block);
        }
        return true;
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    // MARK: - The text

    /** "12:34" in the block font (other characters are left out), the date centred two rows under it. */
    static String text(String clock, String date) {
        StringBuilder out = new StringBuilder();
        int width = 0;
        String[] lines = new String[9];
        for (int row = 0; row < 9; row++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < clock.length(); i++) {
                char ch = clock.charAt(i);
                String[] glyph = ch >= '0' && ch <= '9' ? FONT[ch - '0'] : ch == ':' ? COLON : null;
                if (glyph == null) continue;
                if (line.length() > 0) line.append("  ");
                for (int p = 0; p < glyph[row].length(); p++) line.append(glyph[row].charAt(p) == '#' ? "██" : "  ");
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') end--;
            lines[row] = line.substring(0, end);
            width = Math.max(width, line.length());
        }
        for (String line : lines) out.append(line).append('\n');
        char[] pad = new char[Math.max(0, (width - date.length()) / 2)];
        Arrays.fill(pad, ' ');
        return out.append('\n').append(pad).append(date).append('\n').toString();
    }
}
