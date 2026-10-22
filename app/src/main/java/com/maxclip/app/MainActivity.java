package com.maxclip.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    /** Clips up to this many bytes go on the clipboard as plain text; larger ones go as a content URI. */
    static final long DIRECT_BYTES = 150_000;
    /** Size of each part for "Copy in parts" (always safe as plain text). */
    static final int PART_CHARS = 100_000;
    static final int REQ_IMPORT = 1, REQ_EXPORT = 2;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Entry> entries = new ArrayList<>();
    private Adapter adapter;
    private TextView emptyView;
    private TextView banner;
    private File pendingExport;

    static final class Entry {
        final File file;
        final String preview;
        Entry(File f, String p) { file = f; preview = p; }
    }

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showActions(entries.get(pos).file));
        list.setOnItemLongClickListener((p, v, pos, id) -> { copy(entries.get(pos).file); return true; });

        emptyView = new TextView(this);
        emptyView.setText("No clips yet.\n\nPaste from clipboard, import a text file, "
                + "or share text into MaxClip from any app.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);
        emptyView.setAlpha(0.6f);

        banner = new TextView(this);
        banner.setPadding(pad + pad / 2, pad, pad + pad / 2, pad);
        banner.setTextSize(14);
        banner.setTextColor(0xFFFFFFFF);
        banner.setBackgroundColor(0xFF059669);
        banner.setOnClickListener(v -> onBannerTap());
        root.addView(banner);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(emptyView, new LinearLayout.LayoutParams(-1, -1));
        content.addView(list, new LinearLayout.LayoutParams(-1, -1));
        list.setEmptyView(emptyView);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(pad, pad / 2, pad, pad);
        bar.addView(button("Paste", v -> pasteFromClipboard()), weight());
        bar.addView(button("Import", v -> importFile()), weight());
        bar.addView(button("New", v -> newClip()), weight());
        root.addView(bar);

        setContentView(root);
        refresh();
        handleIncoming(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBanner();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateBanner(); // picker dialog closed
    }

    private boolean keyboardEnabled() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private boolean keyboardActive() {
        String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return cur != null && cur.startsWith(getPackageName() + "/");
    }

    private void updateBanner() {
        if (banner == null) return;
        if (!keyboardEnabled()) {
            banner.setText("Step 1: Tap to enable MaxClip Keyboard — paste clips of any size into any app and auto-save everything you copy.");
            banner.setVisibility(View.VISIBLE);
        } else if (!keyboardActive()) {
            banner.setText("Step 2: Tap to switch to MaxClip Keyboard. It auto-saves copies only while it's your active keyboard.");
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    private void onBannerTap() {
        if (!keyboardEnabled()) {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } else {
            getSystemService(InputMethodManager.class).showInputMethodPicker();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    private Button button(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private String size(File f) { return Formatter.formatShortFileSize(this, f.length()); }

    private void refresh() {
        IO.execute(() -> {
            List<Entry> fresh = new ArrayList<>();
            for (File f : Store.list(this)) {
                String p = Store.preview(f, 160).replaceAll("\\s+", " ").trim();
                fresh.add(new Entry(f, p.isEmpty() ? "(blank)" : p));
            }
            ui.post(() -> {
                entries.clear();
                entries.addAll(fresh);
                adapter.notifyDataSetChanged();
                setTitle(fresh.isEmpty() ? "MaxClip" : "MaxClip · " + fresh.size());
            });
        });
    }

    final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            Entry e = entries.get(i);
            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);
            t1.setMaxLines(2);
            t1.setText(e.preview);
            t2.setText(size(e.file) + "  ·  "
                    + DateFormat.format("d MMM, HH:mm", new Date(Store.stamp(e.file))));
            t2.setAlpha(0.6f);
            return v;
        }
    }

    // ---------------------------------------------------------------- Saving

    private void saved(File f) {
        toast("Saved " + size(f));
        refresh();
    }

    private void fail(Throwable t) { ui.post(() -> toast("Error: " + t.getMessage())); }

    private void saveTextAsync(CharSequence text) {
        if (text == null || text.length() == 0) { toast("Nothing to save"); return; }
        IO.execute(() -> {
            try {
                File f = Store.saveText(this, text);
                ui.post(() -> saved(f));
            } catch (Throwable t) { fail(t); }
        });
    }

    private void saveUriAsync(Uri uri) {
        IO.execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IllegalStateException("Cannot open file");
                File f = Store.saveStream(this, in);
                ui.post(() -> saved(f));
            } catch (Throwable t) { fail(t); }
        });
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("Clipboard is empty"); return; }
        // Resolve text off the main thread: a URI clip may be a large stream.
        IO.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    CharSequence t = clip.getItemAt(i).coerceToText(this);
                    if (t != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(t);
                    }
                }
                ui.post(() -> saveTextAsync(sb));
            } catch (Throwable t) { fail(t); }
        });
    }

    private void importFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void newClip() {
        EditText et = new EditText(this);
        et.setMinLines(6);
        et.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("New clip")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> saveTextAsync(et.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleIncoming(Intent in) {
        if (in == null) return;
        String action = in.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            Uri stream = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) saveUriAsync(stream);
            else saveTextAsync(text);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            saveTextAsync(in.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        }
        setIntent(new Intent()); // don't re-import on rotation
    }

    // ---------------------------------------------------------------- Actions on a clip

    private void showActions(File f) {
        String[] items = {"Copy", "Copy in parts", "View", "Share", "Save to file", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(size(f))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: copy(f); break;
                        case 1: copyInParts(f); break;
                        case 2: view(f); break;
                        case 3: share(f); break;
                        case 4: export(f); break;
                        case 5: confirmDelete(f); break;
                    }
                })
                .show();
    }

    /** Small clips: plain text. Large clips: content URI the pasting app streams in full. */
    private void copy(File f) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (f.length() > DIRECT_BYTES) {
            ClipData cd = new ClipData("MaxClip", new String[]{"text/plain"},
                    new ClipData.Item(ClipProvider.uriFor(f)));
            cm.setPrimaryClip(cd);
            toast("Copied " + size(f) + " as a large clip. Standard text fields paste it in full; "
                    + "if an app pastes nothing, use Copy in parts.");
            return;
        }
        IO.execute(() -> {
            try {
                String text = Store.readAll(f);
                ui.post(() -> {
                    cm.setPrimaryClip(ClipData.newPlainText("MaxClip", text));
                    toast("Copied " + text.length() + " characters");
                });
            } catch (Throwable t) { fail(t); }
        });
    }

    private void copyInParts(File f) {
        IO.execute(() -> {
            try {
                long total = Store.countChars(f);
                int parts = (int) Math.max(1, (total + PART_CHARS - 1) / PART_CHARS);
                ui.post(() -> showPart(f, total, parts, 0));
            } catch (Throwable t) { fail(t); }
        });
    }

    private void showPart(File f, long total, int parts, int idx) {
        long start = (long) idx * PART_CHARS;
        long end = Math.min(total, start + PART_CHARS);
        new AlertDialog.Builder(this)
                .setTitle("Part " + (idx + 1) + " of " + parts)
                .setMessage("Characters " + (start + 1) + "–" + end + " of " + total
                        + "\n\nCopy this part, paste it where you need it, then come back for the next.")
                .setCancelable(false)
                .setPositiveButton("Copy part " + (idx + 1), (d, w) -> IO.execute(() -> {
                    try {
                        String chunk = Store.readRange(f, start, PART_CHARS);
                        ui.post(() -> {
                            getSystemService(ClipboardManager.class)
                                    .setPrimaryClip(ClipData.newPlainText("MaxClip part " + (idx + 1), chunk));
                            toast("Part " + (idx + 1) + "/" + parts + " copied");
                            if (idx + 1 < parts) showPart(f, total, parts, idx + 1);
                        });
                    } catch (Throwable t) { fail(t); }
                }))
                .setNeutralButton(idx > 0 ? "Back" : null,
                        idx > 0 ? (d, w) -> showPart(f, total, parts, idx - 1) : null)
                .setNegativeButton("Close", null)
                .show();
    }

    private void view(File f) {
        final int limit = 20_000;
        IO.execute(() -> {
            String head = Store.preview(f, limit + 1);
            boolean more = head.length() > limit;
            String shown = more ? head.substring(0, limit) + "\n\n… (preview only — full clip is " + size(f) + ")" : head;
            ui.post(() -> {
                TextView tv = new TextView(this);
                tv.setText(shown);
                tv.setTextIsSelectable(true);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(13);
                tv.setPadding(dp(20), dp(12), dp(20), dp(12));
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this)
                        .setView(sv)
                        .setPositiveButton("Copy all", (d, w) -> copy(f))
                        .setNegativeButton("Close", null)
                        .show();
            });
        });
    }

    private void share(File f) {
        Uri uri = ClipProvider.uriFor(f);
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_STREAM, uri);
        s.setClipData(ClipData.newRawUri("clip", uri));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (f.length() <= DIRECT_BYTES) {
            s.putExtra(Intent.EXTRA_TEXT, Store.preview(f, (int) DIRECT_BYTES));
        }
        startActivity(Intent.createChooser(s, "Share clip"));
    }

    private void export(File f) {
        pendingExport = f;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "clip-" + f.getName());
        startActivityForResult(i, REQ_EXPORT);
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setMessage("Delete this clip (" + size(f) + ")?")
                .setPositiveButton("Delete", (d, w) -> {
                    if (f.delete()) refresh();
                    else toast("Could not delete");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            saveUriAsync(uri);
        } else if (req == REQ_EXPORT && pendingExport != null) {
            File src = pendingExport;
            pendingExport = null;
            IO.execute(() -> {
                try (InputStream in = new java.io.FileInputStream(src);
                     OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Cannot write file");
                    Store.copy(in, out);
                    ui.post(() -> toast("Saved to file"));
                } catch (Throwable t) { fail(t); }
            });
        }
    }
}
