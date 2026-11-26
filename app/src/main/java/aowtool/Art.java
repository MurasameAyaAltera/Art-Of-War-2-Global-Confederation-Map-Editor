package aowtool;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Re-creates the images the game itself draws for terrain tiles and decoration objects, straight from the
 * resources of the opened .jar (nothing is bundled with the editor).
 *
 * How the game does it (reverse engineered from class e / a):
 *   i0  : sequence of {u24 length, PNG}. Entry 2k = colour sheet k, entry 2k+1 = its alpha mask (blue channel).
 *   d0  : 26 byte[] (gz) + 18 short[] (gA) tables, each prefixed by a u16 length.
 *   ml  : per tileset { u16 n, n x u16 (el), 30 x u16 (em) }.  el[tile] = sprite id,  el[em[14] + objType] = object kind.
 *
 *   sprite id (gA0/gA1 range) -> entries k { gA2 = frame or sub-sprite, gA3 = dx, gA4 = dy, gz6 != 0 -> sub-sprite }
 *   frame -> sheet gz0, source rect gz1,gz2,gz3,gz4 (45..48 = primitive shapes, skipped here)
 *   object kind (gA14/gA15 range) -> entries k { gA16 = sprite/frame, gA17 = dx, gz20 = dy (signed byte),
 *                                               gz23 = 0 frame | 1 sprite | 2 effect, gz24 = layer, gz25 = direction }
 *   one map cell is 30 x 20 pixels, objects are anchored at the top-left of their cell.
 */
public class Art {
    public static final int CW = 30, CH = 20;

    /** Platform PNG decoder: returns non-premultiplied ARGB pixels and writes {w,h} to wh. */
    public interface Png {
        int[] decode(byte[] data, int off, int len, int[] wh) throws IOException;
    }

    /** Small ARGB image; (ox,oy) is where the map cell's top-left corner sits inside the image. */
    public static final class Img {
        public final int w, h, ox, oy;
        public final int[] px;
        Img(int w, int h, int ox, int oy, int[] px) { this.w = w; this.h = h; this.ox = ox; this.oy = oy; this.px = px; }

        /** Average colour of the visible pixels (ARGB, opaque); used for far-zoom drawing. */
        public int average() {
            long r = 0, g = 0, b = 0, n = 0;
            for (int p : px) {
                int a = p >>> 24;
                if (a < 128) continue;
                r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
            }
            if (n == 0) return 0;
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        }
    }

    private static final int KINDS = 146;
    private static final int SPR_MAX = 2227;

    private final Png png;
    private final byte[] i0;
    private final int[] entOff, entLen;
    private final int sheetCount;
    private final int[][] sheetPx;
    private final int[] sheetW, sheetH;
    private final boolean[] sheetDone;

    private final byte[][] gz = new byte[26][];
    private final short[][] gA = new short[18][];
    private final short[][] gC;
    private final short[][] gD;

    private final HashMap<Integer, Img> tileCache = new HashMap<Integer, Img>();
    private final HashMap<Integer, Img> objCache = new HashMap<Integer, Img>();

    public Art(byte[] i0, byte[] d0, byte[] ml, Png png) throws IOException {
        this.png = png;
        this.i0 = i0;

        List<int[]> ents = new ArrayList<int[]>();
        int p = 0;
        while (p + 3 <= i0.length) {
            int len = ((i0[p] & 255) << 16) | ((i0[p + 1] & 255) << 8) | (i0[p + 2] & 255);
            p += 3;
            if (len < 0 || p + len > i0.length) break;
            ents.add(new int[]{p, len});
            p += len;
        }
        sheetCount = ents.size() / 2;
        entOff = new int[ents.size()];
        entLen = new int[ents.size()];
        for (int i = 0; i < ents.size(); i++) { entOff[i] = ents.get(i)[0]; entLen[i] = ents.get(i)[1]; }
        sheetPx = new int[sheetCount][];
        sheetW = new int[sheetCount];
        sheetH = new int[sheetCount];
        sheetDone = new boolean[sheetCount];

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(d0));
        for (int i = 0; i < 26; i++) {
            int n = in.readUnsignedShort();
            gz[i] = new byte[n];
            in.readFully(gz[i]);
        }
        for (int i = 0; i < 18; i++) {
            int n = in.readUnsignedShort();
            gA[i] = new short[n];
            for (int k = 0; k < n; k++) gA[i][k] = in.readShort();
        }

        in = new DataInputStream(new ByteArrayInputStream(ml));
        int ts = in.readUnsignedByte();
        gC = new short[ts][];
        gD = new short[ts][30];
        for (int t = 0; t < ts; t++) {
            int n = in.readUnsignedShort();
            gC[t] = new short[n];
            for (int k = 0; k < n; k++) gC[t][k] = in.readShort();
            for (int k = 0; k < 30; k++) gD[t][k] = in.readShort();
        }
    }

    // ------------------------------------------------------------------ public queries

    public int tilesetCount() { return gC.length; }

    /** Number of terrain tile ids (0 .. n-1) the tileset defines. */
    public int tileCount(int ts) { return ts < 0 || ts >= gC.length ? 0 : Math.min(gD[ts][14], gC[ts].length); }

    /** Number of decoration object types (0 .. n-1) the tileset defines. */
    public int objectCount(int ts) {
        if (ts < 0 || ts >= gC.length) return 0;
        int n = 0;
        for (int i = gD[ts][14]; i < gC[ts].length; i++) { if (gC[ts][i] < KINDS) n = i - gD[ts][14] + 1; }
        return n;
    }

    /** First id of each "random variant" terrain group (the game re-rolls these ids at load time). */
    public int[] randomGroupStarts(int ts) {
        if (ts < 0 || ts >= gC.length) return new int[0];
        return new int[]{gD[ts][0], gD[ts][1], gD[ts][2], gD[ts][3], gD[ts][4], gD[ts][5]};
    }

    /** Footprint {w,h} in cells of an object type. */
    public int[] footprint(int ts, int type) {
        int kind = kindOf(ts, type);
        if (kind < 0) return new int[]{1, 1};
        return new int[]{Math.max(1, gz[18][kind] & 255), Math.max(1, gz[19][kind] & 255)};
    }

    public Img tile(int ts, int id) {
        if (id < 0 || id >= tileCount(ts)) return null;
        Integer key = Integer.valueOf(ts * 1000 + id);
        if (tileCache.containsKey(key)) return tileCache.get(key);
        Canvas c = new Canvas(80, 64, 24, 24);
        int spr = gC[ts][id];
        try { sprite(c, spr, 0, 0, 0); } catch (IOException e) { /* leave what was drawn */ }
        Img r = c.crop(true);
        tileCache.put(key, r);
        return r;
    }

    /** Returns null when the object has no visible art. */
    public Img object(int ts, int type) {
        int kind = kindOf(ts, type);
        if (kind < 0) return null;
        Integer key = Integer.valueOf(ts * 1000 + type);
        if (objCache.containsKey(key)) return objCache.get(key);
        Canvas c = new Canvas(384, 288, 96, 160);
        try { kind(c, kind, 0, 0); } catch (IOException e) { /* keep partial */ }
        Img r = c.crop(false);
        objCache.put(key, r);
        return r;
    }


    /** Art of a building / unit "kind" (the id the game uses directly for placed buildings; 0..14 = buildings). */
    public Img kindImage(int kind) {
        if (kind < 0 || kind >= KINDS || kind >= gA[14].length) return null;
        Integer key = Integer.valueOf(100000 + kind);
        if (objCache.containsKey(key)) return objCache.get(key);
        Canvas c = new Canvas(448, 352, 128, 192);
        try { kind(c, kind, 0, 0); } catch (IOException e) { /* keep partial */ }
        Img r = c.crop(false);
        objCache.put(key, r);
        return r;
    }

    /** Footprint {w,h} in cells of a building / unit kind. */
    public int[] kindFootprint(int kind) {
        if (kind < 0 || kind >= gz[18].length) return new int[]{1, 1};
        return new int[]{Math.max(1, gz[18][kind] & 255), Math.max(1, gz[19][kind] & 255)};
    }

    /** Number of kinds the game defines. */
    public int kindCount() { return Math.min(KINDS, gz[18].length); }

    private int kindOf(int ts, int type) {
        if (ts < 0 || ts >= gC.length || type < 0) return -1;
        int idx = gD[ts][14] + type;
        if (idx >= gC[ts].length) return -1;
        int k = gC[ts][idx];
        return k >= 0 && k < KINDS && k < gA[14].length ? k : -1;
    }

    // ------------------------------------------------------------------ sheets

    private void loadSheet(int i) throws IOException {
        if (sheetDone[i]) return;
        sheetDone[i] = true;
        if (entLen[2 * i] == 0) return;
        int[] wh = new int[2];
        int[] px = png.decode(i0, entOff[2 * i], entLen[2 * i], wh);
        if (px == null) return;
        int w = wh[0], h = wh[1];
        if (entLen[2 * i + 1] > 0) {
            int[] mw = new int[2];
            int[] m = png.decode(i0, entOff[2 * i + 1], entLen[2 * i + 1], mw);
            if (m != null && mw[0] == w && mw[1] == h) {
                for (int k = 0; k < px.length; k++) px[k] = ((m[k] & 255) << 24) | (px[k] & 0xFFFFFF);
            }
        }
        sheetPx[i] = px; sheetW[i] = w; sheetH[i] = h;
    }

    // ------------------------------------------------------------------ compositing

    private static final class Canvas {
        final int w, h, ox, oy;
        final int[] px;
        Canvas(int w, int h, int ox, int oy) { this.w = w; this.h = h; this.ox = ox; this.oy = oy; px = new int[w * h]; }

        void blend(int x, int y, int s) {
            x += ox; y += oy;
            if (x < 0 || y < 0 || x >= w || y >= h) return;
            int sa = s >>> 24;
            if (sa == 0) return;
            int i = y * w + x;
            int d = px[i];
            int da = d >>> 24;
            if (sa == 255 || da == 0) { px[i] = s; return; }
            int oa = sa * 255 + da * (255 - sa);           // alpha * 255
            int r = (((s >> 16) & 255) * sa * 255 + ((d >> 16) & 255) * da * (255 - sa)) / oa;
            int g = (((s >> 8) & 255) * sa * 255 + ((d >> 8) & 255) * da * (255 - sa)) / oa;
            int b = ((s & 255) * sa * 255 + (d & 255) * da * (255 - sa)) / oa;
            px[i] = (((oa + 127) / 255) << 24) | (r << 16) | (g << 8) | b;
        }

        /** Crops to the visible pixels (and, for tiles, to at least the 30x20 cell). */
        Img crop(boolean keepCell) {
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) != 0) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x;
                    if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
            }
            if (keepCell) {
                x0 = Math.min(x0, ox); y0 = Math.min(y0, oy);
                x1 = Math.max(x1, ox + CW - 1); y1 = Math.max(y1, oy + CH - 1);
            }
            if (x1 < x0 || y1 < y0) return null;
            int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
            int[] out = new int[cw * ch];
            for (int y = 0; y < ch; y++) System.arraycopy(px, (y0 + y) * w + x0, out, y * cw, cw);
            return new Img(cw, ch, ox - x0, oy - y0, out);
        }
    }

    private void frame(Canvas c, int fr, int x, int y) throws IOException {
        if (fr < 0 || fr >= gz[0].length) return;
        int sheet = gz[0][fr] & 255;
        if (sheet >= 45 && sheet <= 48) return;            // primitive shapes (shadows etc.)
        if (sheet >= sheetCount) return;
        loadSheet(sheet);
        int[] sp = sheetPx[sheet];
        if (sp == null) return;
        int sx = gz[1][fr] & 255, sy = gz[2][fr] & 255, w = gz[3][fr] & 255, h = gz[4][fr] & 255;
        int sw = sheetW[sheet], sh = sheetH[sheet];
        for (int yy = 0; yy < h; yy++) {
            int py = sy + yy;
            if (py >= sh) break;
            for (int xx = 0; xx < w; xx++) {
                int pxx = sx + xx;
                if (pxx >= sw) break;
                c.blend(x + xx, y + yy, sp[py * sw + pxx]);
            }
        }
    }

    /** The game's a(int id,int x,int y). */
    private void sprite(Canvas c, int id, int x, int y, int depth) throws IOException {
        if (depth > 6 || id < 0 || id >= SPR_MAX || id >= gA[0].length) return;
        int k0 = gA[0][id], k1 = gA[1][id];
        for (int k = k0; k <= k1 && k < gA[2].length; k++) {
            int fr = gA[2][k], dx = gA[3][k], dy = gA[4][k];
            if (gz[6][k] == 0) frame(c, fr, x + dx, y + dy);
            else sprite(c, fr, x + dx, y + dy, depth + 1);
        }
    }

    /**
     * The game's drawing routine for one "kind" (e.a(kind,x,y,mode,..)), as it looks for an intact, idle building.
     * Entries carry gz[24] = layer and gz[25] = damage stage:
     *   layer 0  base pass (mode 0), layer 1  top pass (mode 1, drawn above units/walls behind it),
     *   layer 2  animated parts of buildings (turret head = stage 2, activity light = stage 10, muzzle flash = stage 1),
     *   layer 5  padding entries without art.
     * Stage 5..7 are damaged looks; stage 4 is the intact look. Objects (kind >= 133 or decoration) ignore layers.
     */
    private void kind(Canvas c, int kind, int x, int y) throws IOException {
        int k0 = gA[14][kind], k1 = gA[15][kind];
        boolean layered = kind < 133;
        int state = 4;                                   // intact
        for (int pass = 0; pass < 3; pass++) {
            if (!layered && pass > 0) break;
            for (int k = k0; k <= k1 && k < gA[16].length; k++) {
                int layer = gz[24][k], dir = gz[25][k] & 255, t = gz[23][k];
                int id = gA[16][k], dx = gA[17][k], dy = gz[20][k];   // dy is a signed byte
                if (layered) {
                    if (layer != pass) continue;
                    if (pass == 2) {
                        // Only the turret head of buildings (stage 2, sprite) is part of how a building looks.
                        if (kind >= 15 || dir != 2 || t != 1) continue;
                    } else if (dir > 0) {
                        if (pass == 0 && dir != state) continue;
                        if (pass == 1 && dir > state) continue;
                    }
                } else if (dir > 0) {
                    continue;
                }
                if (t == 0) frame(c, id, x + dx, y + dy);
                else if (t == 1) sprite(c, id, x + dx, y + dy, 0);
            }
        }
    }
}
