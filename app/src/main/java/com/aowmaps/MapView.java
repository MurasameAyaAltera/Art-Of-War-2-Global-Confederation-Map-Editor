package com.aowmaps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import aowtool.AowMap;

/**
 * Pan/zoom view that paints the map with the game's own artwork (cells are 30x20 like in the game)
 * and lets you paint terrain, place decoration objects and place / move / delete / edit buildings.
 * Unit spawns of the map script can be shown as read-only markers. Falls back to flat colours when
 * the jar's art could not be decoded.
 */
public class MapView extends View {
    public static final int PAN = 0, PAINT = 1, OBJ_ADD = 2, OBJ_DEL = 3, PICK = 4,
            BLD_ADD = 5, BLD_DEL = 6, BLD_MOVE = 7, BLD_EDIT = 8;

    public interface Listener {
        void onPick(int tile);
        void onInfo(String text);
        /** BLD_EDIT: the person tapped building {@code index} (already selected). */
        void onBuilding(int index);
    }

    private static final float ASPECT = 2f / 3f;      // cell height / width (20/30)
    private static final float MIN_CELL = 3f, MAX_CELL = 120f;
    private static final int MAX_BITMAP_CELLS = 7000;  // above this, tiles are drawn as their average colour
    private static final int PLAYER_COLOR = 0xFF29B6F6, ENEMY_COLOR = 0xFFEF5350;

    private AowMap map;
    private ArtCache art;
    private Listener listener;
    private int mode = PAN;
    private int tile = 0, objType = 0, bldKind = 0, bldGroup = 0;
    private boolean showUnits = false;
    private int selected = -1;                         // selected building index
    private int moving = -1, grabX, grabY;             // BLD_MOVE state
    private List<int[]> spawns = new ArrayList<int[]>();

    private float cell = 12f, offX = 0f, offY = 0f;    // cell = width of one cell in pixels
    private boolean fitted = false;
    private float lastX, lastY;
    private boolean multi = false;

    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint();
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final ArrayList<int[]> visible = new ArrayList<int[]>();
    private final ArrayList<int[]> visibleB = new ArrayList<int[]>();
    private final Comparator<int[]> byRow = new Comparator<int[]>() {
        public int compare(int[] a, int[] b) { return a[2] != b[2] ? a[2] - b[2] : a[1] - b[1]; }
    };
    private final ScaleGestureDetector scaler;

    private static class Snap {
        int[] tiles;
        ArrayList<int[]> objs = new ArrayList<int[]>();
        ArrayList<int[]> blds = new ArrayList<int[]>();
    }
    private final ArrayList<Snap> undo = new ArrayList<Snap>();

    public MapView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0x33000000);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        dot.setColor(0xFFFFEB3B);
        box.setStyle(Paint.Style.STROKE);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float nc = Math.max(MIN_CELL, Math.min(MAX_CELL, cell * f));
                f = nc / cell;
                offX = d.getFocusX() - (d.getFocusX() - offX) * f;
                offY = d.getFocusY() - (d.getFocusY() - offY) * f;
                cell = nc;
                invalidate();
                return true;
            }
        });
    }

    public void setMap(AowMap m) {
        map = m;
        fitted = false;
        selected = -1;
        moving = -1;
        undo.clear();
        spawns = m == null ? new ArrayList<int[]>() : m.spawns();
        requestLayout();
        invalidate();
    }

    public AowMap getMap() { return map; }
    public int spawnCount() { return spawns.size(); }
    public void setArt(ArtCache a) { art = a; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void setMode(int m) { mode = m; moving = -1; invalidate(); }
    public void setTile(int t) { tile = t; }
    public void setObjType(int t) { objType = t; }
    public void setBuildingKind(int k) { bldKind = k; }
    public void setBuildingGroup(int g) { bldGroup = g; }
    public void setShowUnits(boolean on) { showUnits = on; invalidate(); }
    public boolean isShowUnits() { return showUnits; }
    public int getSelected() { return selected; }

    public void select(int index) {
        selected = map != null && index >= 0 && index < map.buildings.size() ? index : -1;
        invalidate();
    }

    public void zoom(float factor) {
        float nc = Math.max(MIN_CELL, Math.min(MAX_CELL, cell * factor));
        float f = nc / cell;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        offX = cx - (cx - offX) * f;
        offY = cy - (cy - offY) * f;
        cell = nc;
        invalidate();
    }

    public boolean undo() {
        if (undo.isEmpty() || map == null) return false;
        Snap s = undo.remove(undo.size() - 1);
        System.arraycopy(s.tiles, 0, map.tiles, 0, s.tiles.length);
        map.objects.clear();
        map.objects.addAll(s.objs);
        map.buildings.clear();
        map.buildings.addAll(s.blds);
        selected = -1;
        moving = -1;
        invalidate();
        return true;
    }

    /** Saves the current state so the next change can be undone (also used by the building dialog). */
    public void checkpoint() {
        if (map == null) return;
        Snap s = new Snap();
        s.tiles = map.tiles.clone();
        for (int[] o : map.objects) s.objs.add(o.clone());
        for (int[] b : map.buildings) s.blds.add(b.clone());
        undo.add(s);
        if (undo.size() > 25) undo.remove(0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (map != null && !fitted && w > 0 && h > 0) {
            float ch = (float) h / map.height;
            cell = Math.max(MIN_CELL, Math.min((float) w / map.width, ch / ASPECT));
            offX = (w - cell * map.width) / 2f;
            offY = (h - cell * ASPECT * map.height) / 2f;
            fitted = true;
        }
    }

    private boolean buildingMode() { return mode >= BLD_ADD && mode <= BLD_EDIT; }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF222222);
        if (map == null) return;
        if (!fitted && getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);

        final float cw = cell, chh = cell * ASPECT;
        final int ts = map.tileset;
        final boolean useArt = art != null && art.isReady(ts);
        int x0 = Math.max(0, (int) Math.floor(-offX / cw));
        int y0 = Math.max(0, (int) Math.floor(-offY / chh));
        int x1 = Math.min(map.width, (int) Math.ceil((getWidth() - offX) / cw));
        int y1 = Math.min(map.height, (int) Math.ceil((getHeight() - offY) / chh));
        final float s = cw / 30f;
        final boolean bitmaps = useArt && (long) (x1 - x0) * (y1 - y0) <= MAX_BITMAP_CELLS;
        bmpPaint.setFilterBitmap(cw < 30f);

        // terrain
        for (int y = y0; y < y1; y++) {
            int ct = Math.round(offY + y * chh), cb = Math.round(offY + (y + 1) * chh);
            for (int x = x0; x < x1; x++) {
                int cl = Math.round(offX + x * cw), cr = Math.round(offX + (x + 1) * cw);
                int id = map.tiles[y * map.width + x];
                ArtCache.Spr t = useArt ? art.tile(ts, id) : null;
                if (t != null && bitmaps) {
                    dst.set(cl - Math.round(t.ox * s), ct - Math.round(t.oy * s),
                            cr + Math.round((t.w - t.ox - 30) * s), cb + Math.round((t.h - t.oy - 20) * s));
                    c.drawBitmap(t.bmp, null, dst, bmpPaint);
                } else {
                    fill.setColor(t != null ? t.avg : Palette.color(id));
                    c.drawRect(cl, ct, cr, cb, fill);
                }
            }
        }
        if (cw >= 20f) {
            for (int x = x0; x <= x1; x++) {
                float px = offX + x * cw;
                c.drawLine(px, offY + y0 * chh, px, offY + y1 * chh, line);
            }
            for (int y = y0; y <= y1; y++) {
                float py = offY + y * chh;
                c.drawLine(offX + x0 * cw, py, offX + x1 * cw, py, line);
            }
        }

        // objects: sprites reach up to ~4 cells right and ~3.5 cells down from their anchor cell
        visible.clear();
        for (int[] o : map.objects) {
            if (o[1] >= x0 - 5 && o[1] <= x1 && o[2] >= y0 - 4 && o[2] <= y1) visible.add(o);
        }
        Collections.sort(visible, byRow);
        text.setTextSize(Math.max(8f, cw * 0.38f));
        final boolean objMode = mode == OBJ_ADD || mode == OBJ_DEL;
        for (int[] o : visible) {
            float l = offX + o[1] * cw, t = offY + o[2] * chh;
            ArtCache.Spr sp = useArt ? art.obj(ts, o[0]) : null;
            if (sp != null) {
                int dl = Math.round(l - sp.ox * s), dt = Math.round(t - sp.oy * s);
                dst.set(dl, dt, dl + Math.max(1, Math.round(sp.w * s)), dt + Math.max(1, Math.round(sp.h * s)));
                c.drawBitmap(sp.bmp, null, dst, bmpPaint);
            } else if (o[1] >= x0 && o[1] < x1 && o[2] >= y0 && o[2] < y1) {
                // art unavailable or invisible object: coloured marker like before
                fill.setColor(0xFF000000);
                c.drawRect(l + cw * 0.2f, t + chh * 0.2f, l + cw * 0.8f, t + chh * 0.8f, fill);
                fill.setColor(Palette.color(o[0] + 90));
                c.drawRect(l + cw * 0.28f, t + chh * 0.28f, l + cw * 0.72f, t + chh * 0.72f, fill);
                if (cw >= 18f) c.drawText(String.valueOf(o[0]), l + cw / 2f, t + chh * 0.66f, text);
            }
            if (objMode) c.drawCircle(l + cw / 2f, t + chh / 2f, Math.max(2f, chh * 0.13f), dot);
        }

        drawBuildings(c, x0, y0, x1, y1, cw, chh, s, useArt);
        if (showUnits) drawSpawns(c, x0, y0, x1, y1, cw, chh);
    }

    private void drawBuildings(Canvas c, int x0, int y0, int x1, int y1, float cw, float chh, float s, boolean useArt) {
        visibleB.clear();
        for (int i = 0; i < map.buildings.size(); i++) {
            int[] b = map.buildings.get(i);
            int[] f = AowMap.footprint(b[0]);
            if (b[1] + f[0] >= x0 - 1 && b[1] <= x1 + 1 && b[2] + f[1] >= y0 - 1 && b[2] <= y1 + 6) {
                visibleB.add(new int[]{b[0], b[1], b[2], b[3], i});
            }
        }
        Collections.sort(visibleB, byRow);
        final boolean showBox = buildingMode();
        box.setStrokeWidth(Math.max(1.5f, cw * 0.08f));
        for (int[] b : visibleB) {
            int[] f = AowMap.footprint(b[0]);
            float l = offX + b[1] * cw, t = offY + b[2] * chh;
            float r = l + f[0] * cw, bt = t + f[1] * chh;
            ArtCache.Spr sp = useArt ? art.building(b[0]) : null;
            if (sp != null) {
                int dl = Math.round(l - sp.ox * s), dt = Math.round(t - sp.oy * s);
                dst.set(dl, dt, dl + Math.max(1, Math.round(sp.w * s)), dt + Math.max(1, Math.round(sp.h * s)));
                c.drawBitmap(sp.bmp, null, dst, bmpPaint);
            } else {
                fill.setColor(AowMap.owner(b[0]) == 0 ? 0xFF1565C0 : 0xFFB71C1C);
                c.drawRect(l, t, r, bt, fill);
                text.setTextSize(Math.max(8f, cw * 0.6f));
                c.drawText(String.valueOf(b[0]), (l + r) / 2f, (t + bt) / 2f + cw * 0.2f, text);
            }
            if (showBox || b[4] == selected) {
                boolean sel = b[4] == selected;
                box.setColor(sel ? 0xFFFFEB3B : (AowMap.owner(b[0]) == 0 ? PLAYER_COLOR : ENEMY_COLOR));
                if (sel) box.setStrokeWidth(Math.max(3f, cw * 0.14f));
                c.drawRect(l, t, r, bt, box);
                box.setStrokeWidth(Math.max(1.5f, cw * 0.08f));
            }
        }
    }

    private void drawSpawns(Canvas c, int x0, int y0, int x1, int y1, float cw, float chh) {
        text.setTextSize(Math.max(8f, Math.min(cw, chh * 1.4f) * 0.7f));
        for (int[] sp : spawns) {
            if (sp[0] < x0 - 1 || sp[0] > x1 || sp[1] < y0 - 1 || sp[1] > y1) continue;
            float cx = offX + (sp[0] + 0.5f) * cw, cy = offY + (sp[1] + 0.5f) * chh;
            float r = Math.max(4f, Math.min(cw, chh * 1.5f) * 0.5f);
            fill.setColor(sp[4] >= 3 ? 0xCC0277BD : 0xCCC62828);
            c.drawCircle(cx, cy, r, fill);
            box.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r, box);
            c.drawText("U", cx, cy + r * 0.35f, text);
        }
    }

    // ------------------------------------------------------------------ info text

    private String objectsAt(int cx, int cy) {
        StringBuilder sb = new StringBuilder();
        for (int[] o : map.objects) {
            if (o[1] == cx && o[2] == cy) sb.append(sb.length() == 0 ? "" : ",").append(o[0]);
        }
        return sb.length() == 0 ? "" : "  objek=" + sb;
    }

    public static String describeBuilding(int[] b) {
        int[] f = AowMap.footprint(b[0]);
        return "Bangunan tipe " + b[0] + " (" + (AowMap.owner(b[0]) == 0 ? "pemain" : "musuh") + ", "
                + f[0] + "x" + f[1] + ") di x=" + b[1] + " y=" + b[2] + ", grup " + b[3];
    }

    public static String describeSpawn(int[] sp) {
        return "Spawn unit (skrip) di x=" + sp[0] + " y=" + sp[1] + ", kode " + sp[2] + ", jeda " + sp[3]
                + ", " + (sp[4] >= 3 ? "pihak pemain" : "pihak musuh") + " (grup " + sp[4] + ")";
    }

    private String extraInfo(int cx, int cy) {
        StringBuilder sb = new StringBuilder();
        int bi = map.buildingAt(cx, cy);
        if (bi >= 0) sb.append("\n").append(describeBuilding(map.buildings.get(bi)));
        if (showUnits) {
            for (int[] sp : spawns) if (sp[0] == cx && sp[1] == cy) sb.append("\n").append(describeSpawn(sp));
        }
        return sb.toString();
    }

    private void say(String s) { if (listener != null) listener.onInfo(s); }

    // ------------------------------------------------------------------ touch

    private int cellX(float px) { return (int) Math.floor((px - offX) / cell); }
    private int cellY(float py) { return (int) Math.floor((py - offY) / (cell * ASPECT)); }

    private void apply(float px, float py, boolean down) {
        if (map == null) return;
        int cx = cellX(px), cy = cellY(py);
        if (cx < 0 || cy < 0 || cx >= map.width || cy >= map.height) return;
        if (listener != null && (down || !buildingMode())) {
            say("x=" + cx + " y=" + cy + "  tile=" + map.get(cx, cy) + objectsAt(cx, cy) + extraInfo(cx, cy));
        }
        switch (mode) {
            case PAINT:
                if (map.get(cx, cy) != tile) { map.set(cx, cy, tile); invalidate(); }
                break;
            case OBJ_ADD: {
                boolean exists = false;
                for (int[] o : map.objects) if (o[1] == cx && o[2] == cy && o[0] == objType) { exists = true; break; }
                if (!exists) { map.addObject(objType, cx, cy); invalidate(); }
                break;
            }
            case OBJ_DEL:
                if (map.removeObjectsAt(cx, cy) > 0) invalidate();
                break;
            case PICK:
                tile = map.get(cx, cy);
                if (listener != null) listener.onPick(tile);
                break;
            case BLD_ADD:
                if (down) addBuildingAt(cx, cy);
                break;
            case BLD_DEL:
                if (down) {
                    int bi = map.buildingAt(cx, cy);
                    if (bi >= 0) {
                        String d = describeBuilding(map.buildings.get(bi));
                        map.buildings.remove(bi);
                        selected = -1;
                        invalidate();
                        say("Dihapus: " + d);
                    } else {
                        say("Tidak ada bangunan di x=" + cx + " y=" + cy);
                    }
                }
                break;
            case BLD_MOVE:
                if (down) {
                    int bi = map.buildingAt(cx, cy);
                    if (bi >= 0) {
                        moving = bi;
                        selected = bi;
                        grabX = cx - map.buildings.get(bi)[1];
                        grabY = cy - map.buildings.get(bi)[2];
                        say(describeBuilding(map.buildings.get(bi)) + "\nSeret untuk memindahkan.");
                    } else {
                        moving = -1;
                    }
                    invalidate();
                } else if (moving >= 0 && moving < map.buildings.size()) {
                    int[] b = map.buildings.get(moving);
                    int nx = cx - grabX, ny = cy - grabY;
                    if ((nx != b[1] || ny != b[2]) && map.checkBuilding(b[0], nx, ny, moving) == null) {
                        b[1] = nx;
                        b[2] = ny;
                        say(describeBuilding(b));
                        invalidate();
                    }
                }
                break;
            case BLD_EDIT:
                if (down) {
                    select(map.buildingAt(cx, cy));
                    if (selected < 0) say("Tidak ada bangunan di x=" + cx + " y=" + cy);
                }
                break;
            default:
                break;
        }
    }

    /** Puts the chosen building kind with the touched cell near its centre. */
    private void addBuildingAt(int cx, int cy) {
        int[] f = AowMap.footprint(bldKind);
        int x = Math.max(0, Math.min(map.width - f[0], cx - (f[0] - 1) / 2));
        int y = Math.max(0, Math.min(map.height - f[1], cy - (f[1] - 1) / 2));
        String[] err = new String[1];
        int idx = map.addBuilding(bldKind, x, y, bldGroup, err);
        if (idx < 0) {
            say("Tidak bisa menaruh bangunan: " + err[0]);
        } else {
            selected = idx;
            say(describeBuilding(map.buildings.get(idx)));
            invalidate();
        }
    }

    private boolean changesMapOnDown() {
        return mode == PAINT || mode == OBJ_ADD || mode == OBJ_DEL || mode == BLD_ADD || mode == BLD_DEL
                || mode == BLD_MOVE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        int pc = e.getPointerCount();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multi = false;
                lastX = e.getX();
                lastY = e.getY();
                if (changesMapOnDown()) {
                    // moving only changes something once a building is grabbed, but a spare snapshot is harmless
                    if (mode != BLD_MOVE || (map != null && map.buildingAt(cellX(lastX), cellY(lastY)) >= 0)) checkpoint();
                }
                apply(e.getX(), e.getY(), true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                moving = -1;
                lastX = centroidX(e, -1);
                lastY = centroidY(e, -1);
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int skip = e.getActionIndex();
                lastX = centroidX(e, skip);
                lastY = centroidY(e, skip);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (pc >= 2 || mode == PAN) {
                    float cx = centroidX(e, -1), cy = centroidY(e, -1);
                    offX += cx - lastX;
                    offY += cy - lastY;
                    lastX = cx;
                    lastY = cy;
                    invalidate();
                } else if (!multi) {
                    if (mode != PICK && mode != BLD_ADD && mode != BLD_DEL && mode != BLD_EDIT) {
                        apply(e.getX(), e.getY(), false);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mode == BLD_EDIT && !multi && selected >= 0 && listener != null) listener.onBuilding(selected);
                moving = -1;
                break;
            default:
                break;
        }
        return true;
    }

    private static float centroidX(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getX(i); n++; }
        return n == 0 ? 0 : s / n;
    }

    private static float centroidY(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getY(i); n++; }
        return n == 0 ? 0 : s / n;
    }
}
