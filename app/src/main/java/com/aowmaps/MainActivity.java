package com.aowmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import aowtool.JarProject;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1, REQ_SAVE = 2;

    private TextView status;
    private ArrayAdapter<String> adapter;
    private Button btnNew, btnSave;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(24), dp(12), dp(12));

        status = new TextView(this);
        status.setTextSize(15);
        status.setText("Pilih file Art_Of_War_2_-_Global_Confederation.jar");
        root.addView(status);

        Button open = new Button(this);
        open.setText("1. Buka JAR");
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN);
            }
        });
        root.addView(open);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Intent i = new Intent(MainActivity.this, EditorActivity.class);
                i.putExtra("idx", pos);
                startActivity(i);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        btnNew = new Button(this);
        btnNew.setText("2. Map baru (salin dari map lain)");
        btnNew.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newMap(); }
        });
        root.addView(btnNew);

        btnSave = new Button(this);
        btnSave.setText("3. Simpan JAR hasil edit");
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/java-archive");
                i.putExtra(Intent.EXTRA_TITLE, "AoW2_GC_modded.jar");
                startActivityForResult(i, REQ_SAVE);
            }
        });
        root.addView(btnSave);

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        adapter.clear();
        JarProject p = Session.project;
        boolean ok = p != null;
        btnNew.setEnabled(ok);
        btnSave.setEnabled(ok);
        if (!ok) return;
        for (JarProject.Entry e : p.maps) {
            String s = (e.slot + 1) + ". " + e.name + "  (" + e.map.width + "x" + e.map.height + ")";
            if (e.slot >= 15) s += "  [baru]";
            adapter.add(s);
        }
        status.setText(p.maps.size() + " map. Ketuk map untuk mengedit.");
    }

    private String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 24; i++) {
            char c = s.charAt(i);
            sb.append(c >= 32 && c < 127 ? c : '?');
        }
        String r = sb.toString().trim();
        return r.length() == 0 ? "New Map" : r;
    }

    private void newMap() {
        final JarProject p = Session.project;
        if (p == null) return;
        String[] names = new String[p.maps.size()];
        for (int i = 0; i < names.length; i++) {
            JarProject.Entry e = p.maps.get(i);
            names[i] = e.name + " (" + e.map.width + "x" + e.map.height + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Salin dari map...")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { askName(which); }
                })
                .show();
    }

    private void askName(final int template) {
        final JarProject p = Session.project;
        final EditText et = new EditText(this);
        et.setText("My Map");
        new AlertDialog.Builder(this)
                .setTitle("Nama map baru")
                .setView(et)
                .setPositiveButton("Buat", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            JarProject.Entry e = p.add(sanitize(et.getText().toString()), p.maps.get(template).map.copy());
                            refresh();
                            Intent i = new Intent(MainActivity.this, EditorActivity.class);
                            i.putExtra("idx", p.maps.indexOf(e));
                            startActivity(i);
                        } catch (Exception ex) {
                            Toast.makeText(MainActivity.this, ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_OPEN) {
            status.setText("Membaca JAR...");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        JarProject p = new JarProject(in, readAsset("Maps.class"));
                        in.close();
                        Session.project = p;
                        runOnUiThread(new Runnable() { public void run() { refresh(); } });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                status.setText("Gagal membuka JAR: " + e);
                            }
                        });
                    }
                }
            }).start();
        } else if (req == REQ_SAVE && Session.project != null) {
            final JarProject p = Session.project;
            new Thread(new Runnable() {
                public void run() {
                    String msg;
                    try {
                        OutputStream out = getContentResolver().openOutputStream(uri);
                        p.write(out);
                        out.close();
                        msg = "Tersimpan. Pasang JAR ini di J2ME Loader.";
                    } catch (Exception e) {
                        msg = "Gagal menyimpan: " + e;
                    }
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        public void run() { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); }
                    });
                }
            }).start();
        }
    }
}
