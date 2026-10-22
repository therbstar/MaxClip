package com.maxclip.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Disk-backed clip storage. Each clip is one UTF-8 file, so size is limited only by free storage. */
final class Store {
    private Store() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "clips");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static synchronized File newFile(Context c) {
        long t = System.currentTimeMillis();
        File f;
        while ((f = new File(dir(c), t + ".txt")).exists()) t++;
        return f;
    }

    /** Newest first. */
    static List<File> list(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".txt"));
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> Long.compare(stamp(b), stamp(a)));
        return new ArrayList<>(Arrays.asList(fs));
    }

    static long stamp(File f) {
        try {
            return Long.parseLong(f.getName().replace(".txt", ""));
        } catch (NumberFormatException e) {
            return f.lastModified();
        }
    }

    static File saveText(Context c, CharSequence text) throws IOException {
        File f = newFile(c);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            int n = text.length(), step = 64 * 1024;
            for (int i = 0; i < n; i += step) w.append(text, i, Math.min(n, i + step));
        }
        return f;
    }

    static File saveStream(Context c, InputStream in) throws IOException {
        File f = newFile(c);
        try (InputStream src = in; OutputStream out = new FileOutputStream(f)) {
            copy(src, out);
        }
        return f;
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }

    static Reader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 64 * 1024);
    }

    static String readAll(File f) throws IOException {
        StringBuilder sb = new StringBuilder((int) Math.min(f.length(), Integer.MAX_VALUE - 8));
        try (Reader r = reader(f)) {
            char[] buf = new char[64 * 1024];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** Reads up to {@code len} chars starting at char offset {@code start}. */
    static String readRange(File f, long start, int len) throws IOException {
        try (Reader r = reader(f)) {
            long toSkip = start;
            while (toSkip > 0) {
                long s = r.skip(toSkip);
                if (s <= 0) break;
                toSkip -= s;
            }
            StringBuilder sb = new StringBuilder(len);
            char[] buf = new char[Math.min(len, 64 * 1024)];
            int n;
            while (sb.length() < len && (n = r.read(buf, 0, Math.min(buf.length, len - sb.length()))) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    static String preview(File f, int chars) {
        try {
            return readRange(f, 0, chars);
        } catch (IOException e) {
            return "";
        }
    }

    static long countChars(File f) throws IOException {
        long total = 0;
        try (Reader r = reader(f)) {
            char[] buf = new char[64 * 1024];
            int n;
            while ((n = r.read(buf)) != -1) total += n;
        }
        return total;
    }
}
