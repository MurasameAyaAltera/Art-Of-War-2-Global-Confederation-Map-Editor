package com.aowmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private int[] tileIds, objIds;

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
        tileIds = toArray(tiles);
        objIds = toArray(objs);
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
        addBtn(bottom, "Selesai", new View.OnClickListener() { public void onClick(View v) { finish(); } });
        root.addView(bottom);

        setContentView(root);
        updateTitle();
        setMode(MapView.PAN);
    }

    private static int[] toArray(TreeSet<Integer> s) {
        int[] a = new int[s.size()];
        int i = 0;
        for (int v : s) a[i++] = v;
        return a;
    }

    private void addBtn(LinearLayout row, String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setTextSize(11);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        row.addView(bt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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

    private void buildPalette() {
        palette.removeAllViews();
        final boolean objMode = mode == MapView.OBJ_ADD || mode == MapView.OBJ_DEL;
        int[] ids = objMode ? objIds : tileIds;
        int sel = objMode ? objType : tile;
        for (final int id : ids) {
            TextView t = new TextView(this);
            t.setText(String.valueOf(id));
            t.setTextSize(id == sel ? 16 : 12);
            t.setTextColor(Color.WHITE);
            t.setShadowLayer(2f, 1f, 1f, Color.BLACK);
            t.setGravity(android.view.Gravity.CENTER);
            t.setBackgroundColor(Palette.color(objMode ? id + 90 : id));
            t.setAlpha(id == sel ? 1f : 0.6f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (objMode) { objType = id; view.setObjType(id); }
                    else { tile = id; view.setTile(id); if (mode != MapView.PAINT) setMode(MapView.PAINT); }
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
