package com.aowmaps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;

import aowtool.AowMap;

/** Pan/zoom grid view that paints terrain tiles and places decoration objects. */
public class MapView extends View {
    public static final int PAN = 0, PAINT = 1, OBJ_ADD = 2, OBJ_DEL = 3, PICK = 4;

    public interface Listener {
        void onPick(int tile);
        void onInfo(String text);
    }

    private AowMap map;
    private Listener listener;
    private int mode = PAN;
    private int tile = 0, objType = 0;

    private float cell = 12f, offX = 0f, offY = 0f;
    private boolean fitted = false;
    private float lastX, lastY;
    private boolean multi = false;

    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float nc = Math.max(3f, Math.min(90f, cell * f));
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

    public void setListener(Listener l) { listener = l; }
    public void setMode(int m) { mode = m; }
    public void setTile(int t) { tile = t; }
    public void setObjType(int t) { objType = t; }

    public void zoom(float factor) {
        float nc = Math.max(3f, Math.min(90f, cell * factor));
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
            cell = Math.max(4f, Math.min((float) w / map.width, (float) h / map.height));
            offX = (w - cell * map.width) / 2f;
            offY = (h - cell * map.height) / 2f;
            fitted = true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF222222);
        if (map == null) return;
        if (!fitted && getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        int x0 = Math.max(0, (int) Math.floor(-offX / cell));
        int y0 = Math.max(0, (int) Math.floor(-offY / cell));
        int x1 = Math.min(map.width, (int) Math.ceil((getWidth() - offX) / cell));
        int y1 = Math.min(map.height, (int) Math.ceil((getHeight() - offY) / cell));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                fill.setColor(Palette.color(map.tiles[y * map.width + x]));
                c.drawRect(offX + x * cell, offY + y * cell, offX + (x + 1) * cell, offY + (y + 1) * cell, fill);
            }
        }
        if (cell >= 10f) {
            for (int x = x0; x <= x1; x++) c.drawLine(offX + x * cell, offY + y0 * cell, offX + x * cell, offY + y1 * cell, line);
            for (int y = y0; y <= y1; y++) c.drawLine(offX + x0 * cell, offY + y * cell, offX + x1 * cell, offY + y * cell, line);
        }
        text.setTextSize(Math.max(8f, cell * 0.38f));
        for (int[] o : map.objects) {
            if (o[1] < x0 || o[1] >= x1 || o[2] < y0 || o[2] >= y1) continue;
            float l = offX + o[1] * cell, t = offY + o[2] * cell;
            fill.setColor(0xFF000000);
            c.drawRect(l + cell * 0.2f, t + cell * 0.2f, l + cell * 0.8f, t + cell * 0.8f, fill);
            fill.setColor(Palette.color(o[0] + 90));
            c.drawRect(l + cell * 0.28f, t + cell * 0.28f, l + cell * 0.72f, t + cell * 0.72f, fill);
            if (cell >= 18f) c.drawText(String.valueOf(o[0]), l + cell / 2f, t + cell * 0.63f, text);
        }
    }

    private void apply(float px, float py) {
        if (map == null) return;
        int cx = (int) Math.floor((px - offX) / cell);
        int cy = (int) Math.floor((py - offY) / cell);
        if (cx < 0 || cy < 0 || cx >= map.width || cy >= map.height) return;
        if (listener != null) {
            listener.onInfo("x=" + cx + " y=" + cy + "  tile=" + map.get(cx, cy));
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
