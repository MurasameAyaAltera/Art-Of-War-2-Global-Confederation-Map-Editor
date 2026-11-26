package com.aowmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.TreeSet;

import aowtool.AowMap;
import aowtool.JarProject;

public class EditorActivity extends Activity implements MapView.Listener {
    private JarProject.Entry entry;
    private MapView view;
    private TextView info, title;
    private LinearLayout palette;
    private final Button[] modeBtns = new Button[5];
    private int mode = MapView.PAN;
    private int tile = 0, objType = 0;
    private int[] tileIds, objIds;            // ids used by maps of this tileset
    private int[] allTileIds, allObjIds;      // every id the tileset defines (needs the game art)
    private boolean showAll = false;
    private Button btnPalette;
    private ArtCache art;
    private int tileset;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        JarProject p = Session.project;
        int idx = getIntent().getIntExtra("idx", -1);
        if (p == null || idx < 0 || idx >= p.maps.size()) { finish(); return; }
        entry = p.maps.get(idx);
        AowMap m = entry.map;

        TreeSet<Integer> tiles = new TreeSet<Integer>(), objs = new TreeSet<Integer>();
        for (JarProject.Entry e : p.maps) {
            if (e.map.tileset != m.tileset) continue;
            for (int t : e.map.tiles) tiles.add(t);
            for (int[] o : e.map.objects) objs.add(o[0]);
        }
        tileset = m.tileset;
        art = Session.art();
        tileIds = toArray(canonTiles(tiles));
        objIds = toArray(objs);
        allTileIds = tileIds;
        allObjIds = objIds;
        if (art != null && art.tileCount(tileset) > 0) {
            TreeSet<Integer> at = new TreeSet<Integer>(), ao = new TreeSet<Integer>();
            for (int i = 0; i < art.tileCount(tileset); i++) at.add(i);
            allTileIds = toArray(canonTiles(at));
            for (int i = 0; i < art.objectCount(tileset); i++) ao.add(i);
            allObjIds = toArray(ao);
        }
        tile = tileIds.length > 0 ? tileIds[0] : 0;
        objType = objIds.length > 0 ? objIds[0] : 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(20), 0, 0);

        title = new TextView(this);
        title.setPadding(dp(8), dp(2), dp(8), dp(2));
        title.setTextSize(14);
        root.addView(title);

        LinearLayout modes = new LinearLayout(this);
        String[] names = {"Geser", "Kuas", "Objek+", "Objek-", "Pipet"};
        for (int i = 0; i < names.length; i++) {
            final int mi = i;
            Button bt = new Button(this);
            bt.setText(names[i]);
            bt.setTextSize(11);
            bt.setAllCaps(false);
            bt.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setMode(mi); }
            });
            modeBtns[i] = bt;
            modes.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(modes);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        palette = new LinearLayout(this);
        hs.addView(palette);
        root.addView(hs);

        view = new MapView(this);
        view.setListener(this);
        view.setMap(m);
        view.setArt(art);
        root.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        info = new TextView(this);
        info.setPadding(dp(8), dp(2), dp(8), dp(2));
        info.setTextSize(12);
        root.addView(info);

        LinearLayout bottom = new LinearLayout(this);
        addBtn(bottom, "Undo", new View.OnClickListener() { public void onClick(View v) { view.undo(); } });
        addBtn(bottom, "Zoom +", new View.OnClickListener() { public void onClick(View v) { view.zoom(1.3f); } });
        addBtn(bottom, "Zoom -", new View.OnClickListener() { public void onClick(View v) { view.zoom(1f / 1.3f); } });
        addBtn(bottom, "Nama", new View.OnClickListener() { public void onClick(View v) { rename(); } });
        btnPalette = addBtn(bottom, "Semua", new View.OnClickListener() {
            public void onClick(View v) {
                showAll = !showAll;
                btnPalette.setText(showAll ? "Dipakai" : "Semua");
                buildPalette();
            }
        });
        btnPalette.setEnabled(art != null);
        addBtn(bottom, "Selesai", new View.OnClickListener() { public void onClick(View v) { finish(); } });
        root.addView(bottom);

        setContentView(root);
        updateTitle();
        setMode(MapView.PAN);
        warmArt();
    }

    /** Decodes the game's images for this tileset off the UI thread, then refreshes palette + map. */
    private void warmArt() {
        if (art == null || art.isReady(tileset)) return;
        final ArtCache a = art;
        final int ts = tileset;
        new Thread(new Runnable() {
            public void run() {
                try { a.warm(ts); } catch (Throwable t) { /* stay on flat colours */ }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        buildPalette();
                        view.invalidate();
                    }
                });
            }
        }).start();
    }

    /** The game re-rolls ids inside its "random variant" groups at load time, so show one entry per group. */
    private TreeSet<Integer> canonTiles(TreeSet<Integer> in) {
        if (art == null) return in;
        int[] g = art.randomGroupStarts(tileset);
        TreeSet<Integer> out = new TreeSet<Integer>();
        for (int id : in) {
            int c = id;
            for (int i = 0; i < 5; i++) if (id >= g[i] && id < g[i + 1]) { c = g[i]; break; }
            out.add(c);
        }
        return out;
    }

    private static int[] toArray(TreeSet<Integer> s) {
        int[] a = new int[s.size()];
        int i = 0;
        for (int v : s) a[i++] = v;
        return a;
    }

    private Button addBtn(LinearLayout row, String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setTextSize(11);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        row.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return bt;
    }

    private void updateTitle() {
        title.setText(entry.name + "  " + entry.map.width + "x" + entry.map.height
                + "  tileset " + entry.map.tileset + "  objek " + entry.map.objects.size());
    }

    private void setMode(int m) {
        mode = m;
        view.setMode(m);
        for (int i = 0; i < modeBtns.length; i++) {
            modeBtns[i].setBackgroundColor(i == m ? 0xFF2E7D32 : 0xFFCCCCCC);
            modeBtns[i].setTextColor(i == m ? Color.WHITE : Color.BLACK);
        }
        buildPalette();
        info.setText(m == MapView.PAN ? "Geser dengan satu jari, cubit untuk zoom."
                : m == MapView.PAINT ? "Pilih tile di atas, lalu sentuh/seret di peta."
                : m == MapView.OBJ_ADD ? "Pilih tipe objek, lalu ketuk sel untuk menaruh."
                : m == MapView.OBJ_DEL ? "Ketuk sel untuk menghapus objek di sana."
                : "Ketuk sel untuk mengambil tile-nya.");
    }

    /** Palette cell: game art (or flat colour as fallback) with the id in the corner. */
    private class Thumb extends View {
        private final ArtCache.Spr spr;
        private final int color, id;
        private final boolean selected, isObj;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect r = new Rect();

        Thumb(int id, boolean isObj, boolean selected) {
            super(EditorActivity.this);
            this.id = id; this.isObj = isObj; this.selected = selected;
            ArtCache.Spr sp = null;
            if (art != null && art.isReady(tileset)) sp = isObj ? art.obj(tileset, id) : art.tile(tileset, id);
            spr = sp;
            color = Palette.color(isObj ? id + 90 : id);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(isObj ? 0xFF4A5A40 : 0xFF303030);
            if (spr != null) {
                float sc = Math.min((w - dp(6)) / (float) spr.w, (h - dp(6)) / (float) spr.h);
                if (isObj) sc = Math.min(sc, 2f);
                int dw = Math.max(1, Math.round(spr.w * sc)), dh = Math.max(1, Math.round(spr.h * sc));
                r.set((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
                p.setFilterBitmap(true);
                c.drawBitmap(spr.bmp, null, r, p);
            } else {
                c.drawColor(color);
            }
            p.setTextSize(dp(10));
            p.setStyle(Paint.Style.FILL);
            p.setShadowLayer(dp(2), 0, 0, Color.BLACK);
            p.setColor(Color.WHITE);
            c.drawText(String.valueOf(id), dp(3), dp(11), p);
            p.clearShadowLayer();
            if (selected) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(0xFF43A047);
                c.drawRect(dp(1), dp(1), w - dp(1), h - dp(1), p);
            }
        }
    }

    private void buildPalette() {
        palette.removeAllViews();
        final boolean objMode = mode == MapView.OBJ_ADD || mode == MapView.OBJ_DEL;
        int[] ids = objMode ? (showAll ? allObjIds : objIds) : (showAll ? allTileIds : tileIds);
        int sel = objMode ? objType : tile;
        for (final int id : ids) {
            Thumb t = new Thumb(id, objMode, id == sel);
            t.setAlpha(id == sel ? 1f : 0.8f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(48));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (objMode) {
                        objType = id; view.setObjType(id);
                        if (art != null) {
                            int[] fp = art.footprint(tileset, id);
                            info.setText("objek " + id + "  (jejak " + fp[0] + "x" + fp[1] + " sel)");
                        }
                    } else {
                        tile = id; view.setTile(id);
                        if (mode != MapView.PAINT) setMode(MapView.PAINT);
                    }
                    buildPalette();
                }
            });
            palette.addView(t, lp);
        }
        view.setTile(tile);
        view.setObjType(objType);
    }

    private void rename() {
        final EditText et = new EditText(this);
        et.setText(entry.name);
        new AlertDialog.Builder(this)
                .setTitle("Nama map (maks 24, ASCII)")
                .setView(et)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        StringBuilder sb = new StringBuilder();
                        String s = et.getText().toString();
                        for (int i = 0; i < s.length() && sb.length() < 24; i++) {
                            char c = s.charAt(i);
                            sb.append(c >= 32 && c < 127 ? c : '?');
                        }
                        if (sb.toString().trim().length() > 0) entry.name = sb.toString().trim();
                        updateTitle();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    public void onPick(int t) {
        tile = t;
        setMode(MapView.PAINT);
    }

    @Override
    public void onInfo(String text) {
        info.setText(text);
        updateTitle();
    }
}
