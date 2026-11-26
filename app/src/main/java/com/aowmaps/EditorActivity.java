package com.aowmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import aowtool.AowMap;
import aowtool.JarProject;
import aowtool.MapScript;
import aowtool.Passability;

public class EditorActivity extends Activity implements MapView.Listener {
    private JarProject.Entry entry;
    private MapView view;
    private TextView info, title;
    private LinearLayout palette;
    private static final int REQ_EXPORT = 11;
    private static final int[] PATH_COLORS = {0xFFE53935, 0xFF8E24AA, 0xFFFB8C00};
    private final Button[] modeBtns = new Button[MapView.MODE_COUNT];
    private final Button[] pathBtns = new Button[3];
    private Button btnScriptList;
    private int roundTrip = 2;
    private Button btnUnits;
    private int mode = MapView.PAN;
    private int tile = 0, objType = 0, bldKind = 0;
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
        roundTrip = m.roundTrip();
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
        for (int i = 0; i < names.length; i++) addMode(modes, names[i], i);
        root.addView(modes);

        LinearLayout modes2 = new LinearLayout(this);
        String[] names2 = {"Bangun+", "Bangun-", "Pindah", "Edit"};
        for (int i = 0; i < names2.length; i++) addMode(modes2, names2[i], MapView.BLD_ADD + i);
        btnUnits = new Button(this);
        btnUnits.setText("Unit");
        btnUnits.setTextSize(11);
        btnUnits.setAllCaps(false);
        btnUnits.setBackgroundColor(0xFFCCCCCC);
        btnUnits.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleUnits(); }
        });
        modes2.addView(btnUnits, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(modes2);

        LinearLayout modes3 = new LinearLayout(this);
        addMode(modes3, "Isi (ember)", MapView.FILL);
        addMode(modes3, "Hapus", MapView.ERASE);
        addMode(modes3, "Jalur serang", MapView.PATH);
        addMode(modes3, "Skrip", MapView.SCRIPT);
        root.addView(modes3);

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
        addBtn(bottom, "Undo", new View.OnClickListener() { public void onClick(View v) { view.undo(); updateTitle(); } });
        addBtn(bottom, "Redo", new View.OnClickListener() { public void onClick(View v) { view.redo(); updateTitle(); } });
        addBtn(bottom, "Zoom +", new View.OnClickListener() { public void onClick(View v) { view.zoom(1.3f); } });
        addBtn(bottom, "Zoom -", new View.OnClickListener() { public void onClick(View v) { view.zoom(1f / 1.3f); } });
        addBtn(bottom, "Pas", new View.OnClickListener() { public void onClick(View v) { view.fit(); } });
        root.addView(bottom);

        LinearLayout bottom2 = new LinearLayout(this);
        addBtn(bottom2, "Nama", new View.OnClickListener() { public void onClick(View v) { rename(); } });
        btnPalette = addBtn(bottom2, "Semua", new View.OnClickListener() {
            public void onClick(View v) {
                showAll = !showAll;
                btnPalette.setText(showAll ? "Dipakai" : "Semua");
                buildPalette();
            }
        });
        btnPalette.setEnabled(art != null);
        addBtn(bottom2, "Ekspor map", new View.OnClickListener() { public void onClick(View v) { exportMap(); } });
        addBtn(bottom2, "Selesai", new View.OnClickListener() { public void onClick(View v) { finish(); } });
        root.addView(bottom2);

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

    private void addMode(LinearLayout row, String label, final int modeId) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setTextSize(11);
        bt.setAllCaps(false);
        bt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setMode(modeId); }
        });
        modeBtns[modeId] = bt;
        row.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void toggleUnits() {
        boolean on = !view.isShowUnits();
        view.setShowUnits(on);
        btnUnits.setBackgroundColor(on ? 0xFF2E7D32 : 0xFFCCCCCC);
        btnUnits.setTextColor(on ? Color.WHITE : Color.BLACK);
        info.setText(on
                ? view.spawnCount() + " titik spawn unit dari skrip peta (biru = pemain, merah = musuh). Ketuk sel untuk detail. Hanya dibaca, tidak bisa diedit."
                : "Penanda unit disembunyikan.");
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
                + "  tileset " + entry.map.tileset + "  objek " + entry.map.objects.size()
                + "  bangunan " + entry.map.buildings.size()
                + (roundTrip == 0 ? "  round-trip byte-identik \u2713"
                        : roundTrip == 1 ? "  round-trip isi sama \u2713 (byte berbeda)" : "  round-trip BERBEDA \u26a0"));
    }

    private void setMode(int m) {
        mode = m;
        view.setMode(m);
        for (int i = 0; i < modeBtns.length; i++) {
            if (modeBtns[i] == null) continue;
            modeBtns[i].setBackgroundColor(i == m ? 0xFF2E7D32 : 0xFFCCCCCC);
            modeBtns[i].setTextColor(i == m ? Color.WHITE : Color.BLACK);
        }
        buildPalette();
        String t;
        switch (m) {
            case MapView.PAN: t = "Geser dengan satu jari, cubit untuk zoom. Ketuk sel untuk info."; break;
            case MapView.PAINT: t = "Pilih tile di atas, lalu sentuh/seret di peta."; break;
            case MapView.OBJ_ADD: t = "Pilih tipe objek, lalu ketuk sel untuk menaruh."; break;
            case MapView.OBJ_DEL: t = "Ketuk sel untuk menghapus objek di sana."; break;
            case MapView.BLD_ADD: t = "Pilih bangunan di atas, lalu ketuk peta untuk menaruhnya. Biru = pemain, merah = musuh."; break;
            case MapView.BLD_DEL: t = "Ketuk bangunan untuk menghapusnya."; break;
            case MapView.BLD_MOVE: t = "Sentuh bangunan lalu seret untuk memindahkan (hanya ke posisi yang muat)."; break;
            case MapView.BLD_EDIT: t = "Ketuk bangunan untuk mengubah tipe, posisi, atau grup AI."; break;
            case MapView.FILL: t = "Isi (ember): pilih tile di atas, lalu ketuk sel. Semua sel terrain yang menyambung dengan jenis sama ikut terisi."; break;
            case MapView.ERASE: t = "Hapus: ketuk sel untuk menghapus bangunan di sana, atau objek bila tidak ada bangunan."; break;
            case MapView.PATH: t = "Jalur serang: pilih grup AI, lalu ketuk sel untuk menambah titik (maks " + AowMap.MAX_PATH_POINTS + " per grup). Ketuk titik yang ada untuk menghapusnya."; break;
            case MapView.SCRIPT: t = "Skrip: ketuk sel kosong untuk menambah event di sana, ketuk penanda untuk mengubahnya. Skrip jalan berurutan."; break;
            default: t = "Ketuk sel untuk mengambil tile-nya."; break;
        }
        info.setText(t);
    }

    /** Palette cell: game art (or flat colour as fallback) with the id in the corner. */
    private class Thumb extends View {
        private final ArtCache.Spr spr;
        private final int color, id;
        private final boolean selected, isObj, isBld;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect r = new Rect();

        Thumb(int id, boolean isObj, boolean isBld, boolean selected) {
            super(EditorActivity.this);
            this.id = id; this.isObj = isObj; this.isBld = isBld; this.selected = selected;
            ArtCache.Spr sp = null;
            if (art != null && art.isReady(tileset)) {
                sp = isBld ? art.building(id) : isObj ? art.obj(tileset, id) : art.tile(tileset, id);
            }
            spr = sp;
            color = isBld ? (AowMap.owner(id) == 0 ? 0xFF1565C0 : 0xFFB71C1C) : Palette.color(isObj ? id + 90 : id);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(isBld ? (AowMap.owner(id) == 0 ? 0xFF24476B : 0xFF6B2A2A) : isObj ? 0xFF4A5A40 : 0xFF303030);
            int ah = isBld ? h - dp(24) : h;       // buildings keep the bottom strip for name + HP
            if (spr != null) {
                float sc = Math.min((w - dp(6)) / (float) spr.w, (ah - dp(6)) / (float) spr.h);
                if (isObj || isBld) sc = Math.min(sc, 2f);
                int dw = Math.max(1, Math.round(spr.w * sc)), dh = Math.max(1, Math.round(spr.h * sc));
                r.set((w - dw) / 2, (ah - dh) / 2, (w - dw) / 2 + dw, (ah - dh) / 2 + dh);
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
            if (isBld) {
                p.setTextSize(dp(9));
                c.drawText(AowMap.buildingName(id), dp(3), h - dp(13), p);
                int hp = AowMap.buildingHp(id);
                if (hp > 0) c.drawText("HP " + hp, dp(3), h - dp(3), p);
            }
            p.clearShadowLayer();
            if (selected) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(0xFF43A047);
                c.drawRect(dp(1), dp(1), w - dp(1), h - dp(1), p);
            }
        }
    }

    private void applyViewSelection() {
        view.setTile(tile);
        view.setObjType(objType);
        view.setBuildingKind(bldKind);
    }

    private void buildPalette() {
        palette.removeAllViews();
        if (mode == MapView.PATH) { buildPathPalette(); applyViewSelection(); return; }
        if (mode == MapView.SCRIPT) { buildScriptPalette(); applyViewSelection(); return; }
        if (mode == MapView.ERASE) { applyViewSelection(); return; }
        if (mode == MapView.OBJ_ADD) {
            Button raw = new Button(this);
            raw.setText("ID 0-95...");
            raw.setTextSize(11);
            raw.setAllCaps(false);
            raw.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { rawObjectDialog(); } });
            palette.addView(raw, new LinearLayout.LayoutParams(dp(84), dp(48)));
        }
        final boolean bldMode = mode >= MapView.BLD_ADD && mode <= MapView.BLD_EDIT;
        final boolean objMode = mode == MapView.OBJ_ADD || mode == MapView.OBJ_DEL;
        int[] ids;
        if (bldMode) {
            ids = AowMap.PALETTE.clone();
        } else {
            ids = objMode ? (showAll ? allObjIds : objIds) : (showAll ? allTileIds : tileIds);
        }
        int sel = bldMode ? bldKind : objMode ? objType : tile;
        for (final int id : ids) {
            Thumb t = new Thumb(id, objMode, bldMode, id == sel);
            t.setAlpha(id == sel ? 1f : 0.8f);
            LinearLayout.LayoutParams lp = bldMode ? new LinearLayout.LayoutParams(dp(104), dp(70))
                    : new LinearLayout.LayoutParams(dp(64), dp(48));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (bldMode) {
                        bldKind = id; view.setBuildingKind(id);
                        int[] fp = AowMap.footprint(id);
                        if (mode != MapView.BLD_ADD) setMode(MapView.BLD_ADD);
                        String ru = AowMap.buildingNameRu(id);
                        int hp = AowMap.buildingHp(id);
                        info.setText(AowMap.kindName(id) + (ru.length() > 0 ? " / " + ru : "") + " ("
                                + (AowMap.owner(id) == 0 ? "pemain" : "musuh")
                                + ", " + fp[0] + "x" + fp[1] + " sel" + (hp > 0 ? ", HP " + hp : "")
                                + "). Ketuk peta untuk menaruh.");
                    } else if (objMode) {
                        objType = id; view.setObjType(id);
                        if (art != null) {
                            int[] fp = art.footprint(tileset, id);
                            info.setText("objek " + id + "  (jejak " + fp[0] + "x" + fp[1] + " sel)");
                        }
                    } else {
                        tile = id; view.setTile(id);
                        if (mode != MapView.PAINT && mode != MapView.FILL) setMode(MapView.PAINT);
                    }
                    buildPalette();
                }
            });
            palette.addView(t, lp);
        }
        applyViewSelection();
    }

    // ------------------------------------------------------------------ path / script / export

    private void rawObjectDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(objType));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Tipe objek mentah (0-95)")
                .setView(et)
                .setPositiveButton("Pakai", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int v = Math.max(0, Math.min(95, num(et, objType)));
                        objType = v;
                        view.setObjType(v);
                        buildPalette();
                        info.setText("objek " + v + " dipilih. Ketuk peta untuk menaruh.");
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void buildPathPalette() {
        for (int g = 0; g < 3; g++) {
            final int grp = g;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setTextSize(11);
            pathBtns[g] = b;
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    view.setPathGroup(grp);
                    refreshToolLabels();
                    info.setText("Jalur serang grup " + grp + ": " + entry.map.attackPath(grp).size() + " titik. Ketuk sel untuk menambah.");
                }
            });
            palette.addView(b, new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        Button clear = new Button(this);
        clear.setAllCaps(false);
        clear.setTextSize(11);
        clear.setText("Kosongkan grup");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AowMap m = entry.map;
                int g = view.getPathGroup();
                if (m.attackPath(g).isEmpty()) { info.setText("Grup " + g + " sudah kosong."); return; }
                view.checkpoint();
                String err = m.setAttackPath(g, new ArrayList<int[]>());
                if (err != null) { view.discardCheckpoint(); info.setText(err); return; }
                view.reload();
                refreshToolLabels();
                info.setText("Semua titik jalur grup " + g + " dihapus.");
            }
        });
        palette.addView(clear, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshToolLabels();
    }

    private void buildScriptPalette() {
        btnScriptList = new Button(this);
        btnScriptList.setAllCaps(false);
        btnScriptList.setTextSize(11);
        btnScriptList.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showScriptList(); } });
        palette.addView(btnScriptList, new LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT));
        Button add = new Button(this);
        add.setAllCaps(false);
        add.setTextSize(11);
        add.setText("Tambah event");
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { newEventDialog(entry.map.width / 2, entry.map.height / 2); } });
        palette.addView(add, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshToolLabels();
    }

    /** Keeps the counters on the path / script palette buttons current. */
    private void refreshToolLabels() {
        if (mode == MapView.PATH) {
            for (int g = 0; g < 3; g++) {
                Button b = pathBtns[g];
                if (b == null) continue;
                boolean sel = g == view.getPathGroup();
                b.setText("Grup " + g + " (" + entry.map.attackPath(g).size() + "/" + AowMap.MAX_PATH_POINTS + ")");
                b.setBackgroundColor(sel ? PATH_COLORS[g] : 0xFFCCCCCC);
                b.setTextColor(sel ? Color.WHITE : Color.BLACK);
            }
        } else if (mode == MapView.SCRIPT && btnScriptList != null) {
            btnScriptList.setText("Daftar skrip (" + entry.map.script().size() + "/" + AowMap.MAX_EVENTS + ")");
        }
    }

    private void showScriptList() {
        final AowMap m = entry.map;
        if (!m.scriptEditable()) { Toast.makeText(this, "Skrip peta ini tidak dikenali", Toast.LENGTH_LONG).show(); return; }
        List<int[]> ev = m.script();
        CharSequence[] items = new CharSequence[ev.size()];
        for (int i = 0; i < ev.size(); i++) {
            int[] e = ev.get(i);
            items[i] = "#" + (i + 1) + "  op " + e[0] + "  (" + e[1] + "," + e[2] + ")  a=" + e[3] + "  jeda=" + e[4] + "  grup=" + e[5];
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Skrip peta (" + ev.size() + "/" + AowMap.MAX_EVENTS + ")");
        if (ev.isEmpty()) {
            b.setMessage("Belum ada event.");
        } else {
            b.setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    List<int[]> now = m.script();
                    if (which < now.size()) showEventDialog(which, now.get(which));
                }
            });
        }
        b.setPositiveButton("Tambah di akhir", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { newEventDialog(m.width / 2, m.height / 2); }
        });
        b.setNegativeButton("Tutup", null);
        b.show();
    }

    private void newEventDialog(int x, int y) {
        if (entry.map.script().size() >= AowMap.MAX_EVENTS) {
            Toast.makeText(this, "Batas " + AowMap.MAX_EVENTS + " event skrip tercapai", Toast.LENGTH_LONG).show();
            return;
        }
        showEventDialog(-1, new int[]{27, x, y, 32, 0, 0});
    }

    @Override
    public void onScriptCell(int x, int y) {
        final AowMap m = entry.map;
        if (!m.scriptEditable()) { Toast.makeText(this, "Skrip peta ini tidak dikenali", Toast.LENGTH_LONG).show(); return; }
        final List<int[]> ev = m.script();
        final ArrayList<Integer> here = new ArrayList<Integer>();
        for (int i = 0; i < ev.size(); i++) {
            int[] e = ev.get(i);
            if (MapView.eventHasPlace(e) && e[1] == x && e[2] == y) here.add(i);
        }
        if (here.isEmpty()) { newEventDialog(x, y); return; }
        if (here.size() == 1) { view.selectEvent(here.get(0)); showEventDialog(here.get(0), ev.get(here.get(0))); return; }
        CharSequence[] items = new CharSequence[here.size()];
        for (int k = 0; k < here.size(); k++) {
            int i = here.get(k);
            items[k] = "#" + (i + 1) + "  op " + ev.get(i)[0] + "  a=" + ev.get(i)[3] + "  grup=" + ev.get(i)[5];
        }
        new AlertDialog.Builder(this)
                .setTitle("Event di x=" + x + " y=" + y)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int i = here.get(which);
                        view.selectEvent(i);
                        showEventDialog(i, ev.get(i));
                    }
                })
                .setNeutralButton("Event baru di sini", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        newEventDialog(ev.get(here.get(0))[1], ev.get(here.get(0))[2]);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Add (index = -1) or edit one script event. The script runs strictly in order, so the position matters. */
    private void showEventDialog(final int index, int[] e0) {
        final AowMap m = entry.map;
        final int n = m.script().size();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        TextView hint = new TextView(this);
        hint.setText("Skrip berjalan berurutan: tiap event menunggu (Jeda x 10 tik) sesudah event sebelumnya. "
                + "Op 20-39. Yang sudah diketahui: 24 = posisi kamera awal, 27 = spawn unit di (x,y) "
                + "(A = kode, grup 0-2 = musuh, 3+ = pemain), 22/23 = label dan ulangan loop (A = jumlah, 255 = terus), "
                + "20/21 = perintah AI per grup. Op lain: arti belum dipastikan, ubah dengan hati-hati.");
        hint.setTextSize(12);
        form.addView(hint);
        final EditText fOp = numField(form, "Op (20-39)", e0[0]);
        final EditText fX = numField(form, "X", e0[1]);
        final EditText fY = numField(form, "Y", e0[2]);
        final EditText fA = numField(form, "A (kode / parameter)", e0[3]);
        final EditText fB = numField(form, "Jeda (x10 tik)", e0[4]);
        final EditText fG = numField(form, "Grup", e0[5]);
        final int maxPos = index < 0 ? n + 1 : n;
        final EditText fPos = numField(form, "Urutan (1-" + maxPos + ")", index < 0 ? n + 1 : index + 1);
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? "Event skrip baru" : "Event #" + (index + 1) + ": " + MapScript.opName(e0[0]))
                .setView(sv)
                .setPositiveButton("Terapkan", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int[] v = {num(fOp, 27), num(fX, 0), num(fY, 0), num(fA, 0), num(fB, 0), num(fG, 0)};
                        int pos = num(fPos, maxPos);
                        if (v[0] < 20 || v[0] > 39) { toast("Op harus 20-39"); return; }
                        for (int k = 1; k < 6; k++) if (v[k] < 0 || v[k] > 255) { toast("X, Y, A, Jeda, Grup harus 0-255"); return; }
                        if ((v[0] == 27 || v[0] == 24) && (v[1] >= m.width || v[2] >= m.height)) { toast("Posisi di luar peta"); return; }
                        if (pos < 1 || pos > maxPos) { toast("Urutan harus 1-" + maxPos); return; }
                        List<int[]> ev = m.script();
                        if (index >= 0) ev.remove(index);
                        ev.add(pos - 1, v);
                        view.checkpoint();
                        String err = m.setScript(ev);
                        if (err != null) { view.discardCheckpoint(); toast(err); return; }
                        view.reload();
                        view.selectEvent(pos - 1);
                        refreshToolLabels();
                        info.setText(MapView.describeEvent(pos - 1, v));
                    }
                })
                .setNegativeButton("Batal", null);
        if (index >= 0) {
            b.setNeutralButton("Hapus", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    List<int[]> ev = m.script();
                    if (index >= ev.size()) return;
                    ev.remove(index);
                    view.checkpoint();
                    String err = m.setScript(ev);
                    if (err != null) { view.discardCheckpoint(); toast(err); return; }
                    view.reload();
                    view.selectEvent(-1);
                    refreshToolLabels();
                    info.setText("Event #" + (index + 1) + " dihapus.");
                }
            });
        }
        b.show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    /** Writes the current map as a raw map file (same bytes the JAR would contain for it). */
    private void exportMap() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, entry.file);
        startActivityForResult(i, REQ_EXPORT);
    }

    private byte[] mapBytes() {
        JarProject p = Session.project;
        byte[] d0 = p == null ? null : p.resource("d0"), ml = p == null ? null : p.resource("ml");
        if (d0 != null && ml != null) {
            try { entry.map.syncPassability(new Passability(d0, ml)); } catch (IOException ex) { /* keep the bits as they are */ }
        }
        return entry.map.toBytes();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final byte[] bytes;
        try {
            bytes = mapBytes();
        } catch (RuntimeException ex) {
            toast("Gagal menyiapkan map: " + ex);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(bytes);
                    out.close();
                    msg = "File map tersimpan (" + bytes.length + " byte).";
                } catch (Exception ex) {
                    msg = "Gagal menyimpan file map: " + ex;
                }
                final String m = msg;
                runOnUiThread(new Runnable() { public void run() { toast(m); } });
            }
        }).start();
    }

    // ------------------------------------------------------------------ building dialog

    private EditText numField(LinearLayout parent, String label, int value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(value));
        et.setSelectAllOnFocus(true);
        parent.addView(et);
        return et;
    }

    private static int num(EditText et, int def) {
        try { return Integer.parseInt(et.getText().toString().trim()); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public void onBuilding(final int index) {
        final AowMap m = entry.map;
        if (index < 0 || index >= m.buildings.size()) return;
        final int[] b = m.buildings.get(index);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        TextView hint = new TextView(this);
        hint.setText("Tipe 0-7 = bangunan pemain (biru; 7 = peluncur rudal), 8-14 = bangunan musuh (merah). Tipe 15-19 = tembok pemain, 25-29 = tembok musuh (1x1). Grup AI 0-2 menentukan zona/gelombang AI yang memakai bangunan ini.");
        hint.setTextSize(12);
        form.addView(hint);
        final EditText fKind = numField(form, "Tipe (0-14, 15-29 tembok)", b[0]);
        final EditText fX = numField(form, "X", b[1]);
        final EditText fY = numField(form, "Y", b[2]);
        final EditText fG = numField(form, "Grup AI (0-2)", b[3]);
        new AlertDialog.Builder(this)
                .setTitle((AowMap.isWall(b[0]) ? "Tembok #" : "Bangunan #") + index + "  " + AowMap.footprint(b[0])[0] + "x" + AowMap.footprint(b[0])[1])
                .setView(form)
                .setPositiveButton("Terapkan", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int k = num(fKind, b[0]), x = num(fX, b[1]), y = num(fY, b[2]), g = num(fG, b[3]);
                        if (g < 0 || g > 2) { Toast.makeText(EditorActivity.this, "Grup AI harus 0-2", Toast.LENGTH_LONG).show(); return; }
                        boolean moved = k != b[0] || x != b[1] || y != b[2];
                        if (moved) {
                            String why = m.checkBuilding(k, x, y, index);
                            if (why != null) { Toast.makeText(EditorActivity.this, why, Toast.LENGTH_LONG).show(); return; }
                        }
                        if (!moved && g == b[3]) return;
                        view.checkpoint();
                        b[0] = k; b[1] = x; b[2] = y; b[3] = g;
                        view.select(index);
                        info.setText(MapView.describeBuilding(b));
                    }
                })
                .setNeutralButton("Hapus", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        view.checkpoint();
                        m.buildings.remove(index);
                        view.select(-1);
                        updateTitle();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
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
        refreshToolLabels();
    }
}
