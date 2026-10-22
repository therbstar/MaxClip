package com.maxclip.app;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MaxClip Keyboard.
 *  - Auto-saves everything copied in any app (Android lets the active keyboard read the clipboard).
 *  - Tap a clip to type it into the current field in full. Text is streamed in small chunks
 *    straight through the InputConnection, so the system clipboard's ~1 MB limit never applies.
 */
public class MaxClipIME extends InputMethodService {
    private static final int CHUNK = 16_000;       // chars per commitText call
    private static final int BG = 0xFF111827, ROW = 0xFF1F2937, FG = 0xFFF9FAFB, DIM = 0xFF9CA3AF, ACCENT = 0xFF10B981;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<File> clips = new ArrayList<>();
    private final List<String> previews = new ArrayList<>();
    private final AtomicBoolean inserting = new AtomicBoolean(false);
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private ClipboardManager cm;
    private ClipAdapter adapter;
    private TextView status;

    // ------------------------------------------------------------ Auto-capture

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = this::captureClipboard;

    @Override
    public void onCreate() {
        super.onCreate();
        cm = getSystemService(ClipboardManager.class);
        cm.addPrimaryClipChangedListener(clipListener);
    }

    @Override
    public void onDestroy() {
        cm.removePrimaryClipChangedListener(clipListener);
        io.shutdownNow();
        super.onDestroy();
    }

    private void captureClipboard() {
        ClipData clip;
        try {
            clip = cm.getPrimaryClip();
        } catch (Exception e) {
            return;
        }
        if (clip == null || clip.getItemCount() == 0) return;
        ClipDescription d = clip.getDescription();
        if (d != null) {
            CharSequence label = d.getLabel();
            if (label != null && label.toString().startsWith("MaxClip")) return; // our own copies
            if (Build.VERSION.SDK_INT >= 33 && d.getExtras() != null
                    && d.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) return; // passwords
        }
        io.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    CharSequence t = clip.getItemAt(i).coerceToText(this);
                    if (t != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(t);
                    }
                }
                if (sb.length() == 0 || isDuplicateOfNewest(sb)) return;
                Store.saveText(this, sb);
                loadClips();
            } catch (Throwable ignored) {
            }
        });
    }

    private boolean isDuplicateOfNewest(CharSequence text) {
        List<File> all = Store.list(this);
        if (all.isEmpty()) return false;
        File newest = all.get(0);
        if (newest.length() != text.toString().getBytes(StandardCharsets.UTF_8).length) return false;
        String head = Store.preview(newest, 4096);
        return text.toString().startsWith(head);
    }

    // ------------------------------------------------------------ Keyboard UI

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView key(String label, float weight, View.OnClickListener l) {
        TextView k = new TextView(this);
        k.setText(label);
        k.setTextColor(FG);
        k.setTextSize(16);
        k.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ROW);
        bg.setCornerRadius(dp(8));
        k.setBackground(bg);
        k.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        k.setLayoutParams(lp);
        return k;
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(4), dp(4), dp(6));

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(12);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setOnClickListener(v -> { if (inserting.get()) cancel.set(true); });
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new ClipAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> insert(clips.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(-1, dp(230)));

        LinearLayout keys = new LinearLayout(this);
        keys.addView(key("⌨", 1, v -> switchKeyboard()));
        keys.addView(key("space", 3, v -> commit(" ")));
        keys.addView(key("⌫", 1, v -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)));
        keys.addView(key("↵", 1, v -> sendKeyChar('\n')));
        root.addView(keys);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (!inserting.get()) setStatus(null);
        loadClips();
    }

    private void setStatus(String s) {
        if (status == null) return;
        status.setText(s != null ? s : "MaxClip · tap a clip to insert it in full");
        status.setTextColor(s != null ? ACCENT : DIM);
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    private void switchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.showInputMethodPicker();
    }

    private void loadClips() {
        io.execute(() -> {
            List<File> fs = Store.list(this);
            List<String> ps = new ArrayList<>();
            for (File f : fs) {
                String p = Store.preview(f, 120).replaceAll("\\s+", " ").trim();
                ps.add(p.isEmpty() ? "(blank)" : p);
            }
            ui.post(() -> {
                clips.clear();
                clips.addAll(fs);
                previews.clear();
                previews.addAll(ps);
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });
    }

    final class ClipAdapter extends BaseAdapter {
        @Override public int getCount() { return clips.isEmpty() ? 1 : clips.size(); }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return !clips.isEmpty(); }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            TextView t = v instanceof TextView ? (TextView) v : new TextView(MaxClipIME.this);
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
            t.setTextSize(14);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            if (clips.isEmpty()) {
                t.setTextColor(DIM);
                t.setText("No clips yet. Copy something in any app — it's saved here automatically.");
                t.setBackground(null);
                return t;
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ROW);
            bg.setCornerRadius(dp(8));
            t.setBackground(bg);
            t.setTextColor(FG);
            t.setText(previews.get(i) + "  ·  "
                    + Formatter.formatShortFileSize(MaxClipIME.this, clips.get(i).length()));
            t.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
            return t;
        }
    }

    // ------------------------------------------------------------ Unlimited insert

    private void insert(File f) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!inserting.compareAndSet(false, true)) return;
        cancel.set(false);
        final long totalBytes = Math.max(1, f.length());
        setStatus("Inserting… 0%  (tap here to stop)");
        io.execute(() -> {
            long doneBytes = 0;
            boolean ok = true;
            try (Reader r = Store.reader(f)) {
                char[] buf = new char[CHUNK + 1];
                int carry = 0; // pending high surrogate kept for the next chunk
                int n;
                while (!cancel.get() && (n = r.read(buf, carry, CHUNK)) != -1) {
                    int len = carry + n;
                    carry = 0;
                    if (len > 0 && Character.isHighSurrogate(buf[len - 1])) { carry = 1; len--; }
                    String chunk = new String(buf, 0, len);
                    InputConnection cur = getCurrentInputConnection();
                    if (cur == null || !cur.commitText(chunk, 1)) { ok = false; break; }
                    // Flow control: a synchronous call waits until the target app has processed
                    // the chunk, so its binder buffer never overflows.
                    cur.getTextBeforeCursor(1, 0);
                    if (carry == 1) buf[0] = buf[len];
                    doneBytes += chunk.getBytes(StandardCharsets.UTF_8).length;
                    int pct = (int) Math.min(100, doneBytes * 100 / totalBytes);
                    ui.post(() -> setStatus("Inserting… " + pct + "%  (tap here to stop)"));
                }
                if (carry == 1 && !cancel.get()) {
                    InputConnection cur = getCurrentInputConnection();
                    if (cur != null) cur.commitText(String.valueOf(buf[0]), 1);
                }
            } catch (Throwable t) {
                ok = false;
            }
            final boolean success = ok && !cancel.get();
            final boolean stopped = cancel.get();
            ui.post(() -> {
                inserting.set(false);
                setStatus(success ? "Inserted " + Formatter.formatShortFileSize(this, totalBytes)
                        : stopped ? "Stopped" : "Insert interrupted (field closed or rejected the text)");
            });
        });
    }
}
