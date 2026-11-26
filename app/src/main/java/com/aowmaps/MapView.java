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

import aowtool.AowMap;

/**
 * Pan/zoom view that paints the map with the game's own artwork (cells are 30x20 like in the game)
 * and lets you paint terrain and place decoration objects. Falls back to flat colours when the jar's
 * art could not be decoded.
 */
public class MapView extends View {
    public static final int PAN = 0, PAINT = 1, OBJ_ADD = 2, OBJ_DEL = 3, PICK = 4;

    public interface Listener {
        void onPick(int tile);
        void onInfo(String text);
    }

    private static final float ASPECT = 2f / 3f;      // cell height / width (20/30)
    private static final float MIN_CELL = 3f, MAX_CELL = 120f;
    private static final int MAX_BITMAP_CELLS = 7000;  // above this, tiles are drawn as their average colour

    private AowMap map;
    private ArtCache art;
    private Listener listener;
    private int mode = PAN;
    private int tile = 0, objType = 0;

    private float cell = 12f, offX = 0f, offY = 0f;    // cell = width of one cell in pixels
    private boolean fitted = false;
    private float lastX, lastY;
    private boolean multi = false;

    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint();
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final ArrayList<int[]> visible = new ArrayList<int[]>();
    private final Comparator<int[]> byRow = new Comparator<int[]>() {
        public int compare(int[] a, int[] b) { return a[2] != b[2] ? a[2] - b[2] : a[1] - b[1]; }
    };
    private final ScaleGestureDetector scaler;

    private static class Snap {
        int[] tiles;
        ArrayList<int[]> objs = new ArrayList<int[]>();
    }
    private final ArrayList<Snap> undo = new ArrayList<Snap>();

    public MapView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0x33000000);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        dot.setColor(0xFFFFEB3B);
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
        undo.clear();
        requestLayout();
        invalidate();
    }

    public void setArt(ArtCache a) { art = a; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void setMode(int m) { mode = m; invalidate(); }
    public void setTile(int t) { tile = t; }
    public void setObjType(int t) { objType = t; }

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
        invalidate();
        return true;
    }

    private void pushUndo() {
        Snap s = new Snap();
        s.tiles = map.tiles.clone();
        for (int[] o : map.objects) s.objs.add(o.clone());
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
    }

    private String objectsAt(int cx, int cy) {
        StringBuilder sb = new StringBuilder();
        for (int[] o : map.objects) {
            if (o[1] == cx && o[2] == cy) sb.append(sb.length() == 0 ? "" : ",").append(o[0]);
        }
        return sb.length() == 0 ? "" : "  objek=" + sb;
    }

    private void apply(float px, float py) {
        if (map == null) return;
        int cx = (int) Math.floor((px - offX) / cell);
        int cy = (int) Math.floor((py - offY) / (cell * ASPECT));
        if (cx < 0 || cy < 0 || cx >= map.width || cy >= map.height) return;
        if (listener != null) {
            listener.onInfo("x=" + cx + " y=" + cy + "  tile=" + map.get(cx, cy) + objectsAt(cx, cy));
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
            default:
                break;
        }
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
                if (mode == PAINT || mode == OBJ_ADD || mode == OBJ_DEL) pushUndo();
                apply(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
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
                    if (mode != PICK) apply(e.getX(), e.getY());
                }
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
