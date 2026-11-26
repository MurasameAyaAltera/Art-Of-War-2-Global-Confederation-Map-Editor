package com.aowmaps;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

import aowtool.Art;
import aowtool.JarProject;

/** Android side of {@link Art}: turns the decoded game images into Bitmaps and caches them. */
final class ArtCache {
    /** One drawable piece of art. (ox,oy) = where the map cell's top-left corner is inside the bitmap. */
    static final class Spr {
        final Bitmap bmp;
        final int w, h, ox, oy, avg;
        Spr(Bitmap b, int w, int h, int ox, int oy, int avg) {
            this.bmp = b; this.w = w; this.h = h; this.ox = ox; this.oy = oy; this.avg = avg;
        }
    }

    private static final Spr NONE = new Spr(null, 0, 0, 0, 0, 0);

    private final Art art;
    private final HashMap<Integer, Spr> tiles = new HashMap<Integer, Spr>();
    private final HashMap<Integer, Spr> objs = new HashMap<Integer, Spr>();
    private final HashMap<Integer, Spr> blds = new HashMap<Integer, Spr>();
    private final HashSet<Integer> ready = new HashSet<Integer>();

    private ArtCache(Art art) { this.art = art; }

    static ArtCache create(JarProject p) {
        try {
            byte[] i0 = p.resource("i0"), d0 = p.resource("d0"), ml = p.resource("ml");
            if (i0 == null || d0 == null || ml == null) return null;
            return new ArtCache(new Art(i0, d0, ml, new Art.Png() {
                public int[] decode(byte[] d, int off, int len, int[] wh) throws IOException {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPremultiplied = false;
                    o.inScaled = false;
                    Bitmap b = BitmapFactory.decodeByteArray(d, off, len, o);
                    if (b == null) throw new IOException("PNG decode failed");
                    int w = b.getWidth(), h = b.getHeight();
                    int[] px = new int[w * h];
                    b.getPixels(px, 0, w, 0, 0, w, h);
                    b.recycle();
                    wh[0] = w; wh[1] = h;
                    return px;
                }
            }));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Spr wrap(Art.Img im) {
        if (im == null) return NONE;
        Bitmap b = Bitmap.createBitmap(im.px, im.w, im.h, Bitmap.Config.ARGB_8888);
        return new Spr(b, im.w, im.h, im.ox, im.oy, im.average());
    }

    /** Terrain tile art, or null when the id has none. */
    synchronized Spr tile(int ts, int id) {
        Integer k = Integer.valueOf(ts * 1000 + id);
        Spr s = tiles.get(k);
        if (s == null) {
            s = wrap(art.tile(ts, id));
            tiles.put(k, s);
        }
        return s == NONE ? null : s;
    }

    /** Decoration object art, or null when invisible / unknown. */
    synchronized Spr obj(int ts, int type) {
        Integer k = Integer.valueOf(ts * 1000 + type);
        Spr s = objs.get(k);
        if (s == null) {
            s = wrap(art.object(ts, type));
            objs.put(k, s);
        }
        return s == NONE ? null : s;
    }

    /** Building art for kind 0..14 (0..7 player, 8..14 enemy), or null when unavailable. */
    synchronized Spr building(int kind) {
        Integer k = Integer.valueOf(kind);
        Spr s = blds.get(k);
        if (s == null) {
            s = wrap(art.kindImage(kind));
            blds.put(k, s);
        }
        return s == NONE ? null : s;
    }

    int tileCount(int ts) { return art.tileCount(ts); }
    int objectCount(int ts) { return art.objectCount(ts); }
    int[] randomGroupStarts(int ts) { return art.randomGroupStarts(ts); }
    int[] footprint(int ts, int type) { return art.footprint(ts, type); }

    /** Decodes every tile and object of a tileset (call from a background thread). */
    void warm(int ts) {
        int nt = tileCount(ts), no = objectCount(ts);
        for (int i = 0; i < nt; i++) tile(ts, i);
        for (int i = 0; i < no; i++) obj(ts, i);
        for (int i = 0; i < aowtool.AowMap.KINDS; i++) building(i);
        synchronized (this) { ready.add(Integer.valueOf(ts)); }
    }

    synchronized boolean isReady(int ts) { return ready.contains(Integer.valueOf(ts)); }
}
