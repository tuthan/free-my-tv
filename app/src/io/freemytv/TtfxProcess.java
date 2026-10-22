package io.freemytv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One run of ttfx (the Terminal screensaver's effects, native/): the unmodified binary, shipped as
 * lib/armeabi-v7a/libttfx.so and started from the app's native library directory as a child process. It takes the text
 * on stdin and writes every frame to stdout (--parity-dump: "<bytes>\n", the rows, "\n") on its own clock of 1/60 s a
 * frame, only as fast as they are read. A crash ends the child, never the app: the reader sees the pipe close.
 * One thread reads and parses (TerminalLook's); kill() may come from any thread.
 */
final class TtfxProcess {
    /** The colour of a cell ttfx gives no colour (only with --no-color): the terminal's usual light grey. */
    static final int DEFAULT_FG = 0xFFCCCCCC;

    static File binary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libttfx.so");
    }

    /** Only in builds made after native/build.sh (build.sh adds it when it's there). */
    static boolean available(Context c) {
        return binary(c).canExecute();
    }

    private final Process process;
    private final InputStream out;
    private final File errors;
    private byte[] frame = new byte[64 * 1024], next = new byte[64 * 1024];
    private int frameLength;
    private int frames;     // read so far

    TtfxProcess(Context c, List<String> args, String text) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(binary(c).getPath());
        command.addAll(args);
        errors = new File(c.getCacheDir(), "ttfx-errors.txt");
        // Its errors and the closing frames=N, read only when it fails (a pipe would need a thread of its own).
        process = new ProcessBuilder(command).redirectError(errors).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(text.getBytes(StandardCharsets.UTF_8));   // a few hundred bytes: never fills the pipe
        }
        out = new BufferedInputStream(process.getInputStream(), 64 * 1024);
    }

    int frames() {
        return frames;
    }

    /** Reads up to n frames and keeps the last one; fewer than n means the effect has ended (or the child died). */
    int read(int n) throws IOException {
        int got = 0;
        while (got < n) {
            int length = header();
            if (length < 0) break;
            if (next.length < length) next = new byte[length + length / 2];
            for (int at = 0; at < length; ) {
                int r = out.read(next, at, length - at);
                if (r < 0) throw new IOException("frame cut short");
                at += r;
            }
            if (out.read() != '\n') throw new IOException("frame not closed");
            byte[] swap = frame;     // only a whole frame replaces the last one
            frame = next;
            next = swap;
            frameLength = length;
            frames++;
            got++;
        }
        return got;
    }

    /** The frame's length from its "<bytes>\n" line; -1 at the end of the output. */
    private int header() throws IOException {
        int length = 0, digits = 0;
        for (int b; (b = out.read()) != '\n'; digits++) {
            if (b < 0 && digits == 0) return -1;
            if (b < '0' || b > '9' || digits > 8) throw new IOException("bad frame header");
            length = length * 10 + (b - '0');
        }
        if (digits == 0) throw new IOException("bad frame header");
        return length;
    }

    /**
     * The last frame read, into three ints per cell (row by row): the codepoint (0 = nothing), the foreground and the
     * background as ARGB (0 = none). ttfx writes each coloured cell as ESC[38;2;r;g;bm (and 48 for a background),
     * the symbol, ESC[0m; other attributes are ignored. Cells past cols or rows are dropped.
     */
    void cells(int cols, int rows, int[] cells) throws IOException {
        java.util.Arrays.fill(cells, 0);
        byte[] f = frame;
        int row = 0, col = 0, fg = DEFAULT_FG, bg = 0;
        for (int i = 0; i < frameLength; ) {
            int b = f[i] & 0xFF;
            if (b == '\n') {
                row++;
                col = 0;
                fg = DEFAULT_FG;
                bg = 0;
                i++;
                continue;
            }
            if (b == 0x1B) {
                if (i + 1 >= frameLength || f[i + 1] != '[') throw new IOException("escape at " + i);
                int end = i + 2;
                while (end < frameLength && (f[end] < 0x40 || f[end] > 0x7E)) end++;
                if (end >= frameLength) throw new IOException("unfinished escape at " + i);
                if (f[end] == 'm') {
                    int[] p = params(f, i + 2, end);
                    for (int k = 0; k < p.length; k++) {
                        if (p[k] == 0) {
                            fg = DEFAULT_FG;
                            bg = 0;
                        } else if ((p[k] == 38 || p[k] == 48) && k + 4 < p.length && p[k + 1] == 2) {
                            int argb = 0xFF000000 | (p[k + 2] & 255) << 16 | (p[k + 3] & 255) << 8 | (p[k + 4] & 255);
                            if (p[k] == 38) fg = argb; else bg = argb;
                            k += 4;
                        } else if (p[k] == 39) {
                            fg = DEFAULT_FG;
                        } else if (p[k] == 49) {
                            bg = 0;
                        }
                    }
                }
                i = end + 1;
                continue;
            }
            // One UTF-8 character: one cell.
            int cp, size;
            if (b < 0x80) { cp = b; size = 1; }
            else if (b >= 0xF0) { cp = b & 0x07; size = 4; }
            else if (b >= 0xE0) { cp = b & 0x0F; size = 3; }
            else if (b >= 0xC0) { cp = b & 0x1F; size = 2; }
            else throw new IOException("bad UTF-8 at " + i);
            if (i + size > frameLength) throw new IOException("cut UTF-8 at " + i);
            for (int k = 1; k < size; k++) cp = cp << 6 | (f[i + k] & 0x3F);
            i += size;
            if (row < rows && col < cols) {
                int at = (row * cols + col) * 3;
                cells[at] = cp == ' ' ? 0 : cp;
                cells[at + 1] = fg;
                cells[at + 2] = bg;
            }
            col++;
        }
    }

    private static int[] params(byte[] f, int from, int to) {
        int n = 1;
        for (int i = from; i < to; i++) if (f[i] == ';') n++;
        int[] p = new int[n];
        int k = 0;
        for (int i = from; i < to; i++) {
            if (f[i] == ';') k++;
            else if (f[i] >= '0' && f[i] <= '9') p[k] = Math.min(100_000, p[k] * 10 + (f[i] - '0'));
        }
        return p;
    }

    /** Ends the child at once (SIGKILL), from any thread; a read in progress then sees the end of the output. */
    void kill() {
        process.destroyForcibly();
    }

    /** Waits for the child to be gone (no zombie) and returns its exit code. */
    int waitFor() {
        while (true) {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                // keep waiting: the child is already ending
            }
        }
    }

    /** The first line ttfx wrote to stderr (its error), for the log. */
    String error() {
        try (InputStream in = new FileInputStream(errors)) {
            byte[] b = new byte[300];
            int n = Math.max(0, in.read(b));
            String s = new String(b, 0, n, StandardCharsets.UTF_8).trim();
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        } catch (IOException e) {
            return "";
        }
    }
}
