package aowtool;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One Art of War 2 - Global Confederation skirmish map ("mapN" resource / RMS slot record).
 *
 * File layout (all big-endian), verified against the game's loader (e.k()) and all 15 stock maps:
 *   u16  length of the rest of the file
 *   u8   base map selector (0xFF = none)
 *   u8   width, u8 height
 *   u8   tileset
 *   u16  number of terrain runs
 *   runs x { u8 count; u8 tile }      row-major fill, left to right then next row
 *   u16  number of objects
 *   objs x { u8 type; u8 x; u8 y }    decorations / obstacles
 *   ...  tail (fog masks, start positions, units ...) - kept byte-for-byte, not interpreted
 */
public class AowMap {
    public int base = 0xFF;
    public int width, height, tileset;
    public int[] tiles;                       // width*height
    public final List<int[]> objects = new ArrayList<int[]>();   // {type,x,y}
    public byte[] tail = new byte[0];
    private byte[] raw;                       // original bytes, returned untouched while the map is unmodified

    public static AowMap parse(byte[] file) {
        int len = u16(file, 0);
        if (len + 2 != file.length) throw new IllegalArgumentException("bad length prefix");
        int p = 2;
        AowMap m = new AowMap();
        m.base = file[p++] & 255;
        m.width = file[p++] & 255;
        m.height = file[p++] & 255;
        m.tileset = file[p++] & 255;
        int nruns = u16(file, p); p += 2;
        m.tiles = new int[m.width * m.height];
        int pos = 0;
        for (int i = 0; i < nruns; i++) {
            int c = file[p] & 255, t = file[p + 1] & 255; p += 2;
            for (int k = 0; k < c && pos < m.tiles.length; k++) m.tiles[pos++] = t;
        }
        int nobj = u16(file, p); p += 2;
        for (int i = 0; i < nobj; i++) {
            m.objects.add(new int[]{file[p] & 255, file[p + 1] & 255, file[p + 2] & 255});
            p += 3;
        }
        m.tail = new byte[file.length - p];
        System.arraycopy(file, p, m.tail, 0, m.tail.length);
        m.raw = file.clone();
        return m;
    }

    private static boolean sameObjects(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) return false;
        List<String> x = new ArrayList<String>(), y = new ArrayList<String>();
        for (int[] o : a) x.add(o[0] + "," + o[1] + "," + o[2]);
        for (int[] o : b) y.add(o[0] + "," + o[1] + "," + o[2]);
        Collections.sort(x); Collections.sort(y);
        return x.equals(y);
    }

    /** True when nothing differs from the bytes this map was parsed from. */
    public boolean isUnmodified() {
        if (raw == null) return false;
        AowMap o = parse(raw);
        return o.base == base && o.width == width && o.height == height && o.tileset == tileset
                && java.util.Arrays.equals(o.tiles, tiles) && java.util.Arrays.equals(o.tail, tail)
                && sameObjects(o.objects, objects);
    }

    public byte[] toBytes() {
        if (isUnmodified()) return raw.clone();
        return encode();
    }

    private byte[] encode() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(base); body.write(width); body.write(height); body.write(tileset);
        ByteArrayOutputStream runs = new ByteArrayOutputStream();
        int nruns = 0;
        int used = tiles.length;
        while (used > 0 && tiles[used - 1] == 0) used--;   // uncovered cells default to tile 0 in the loader
        for (int i = 0; i < used; ) {
            int t = tiles[i], c = 1;
            while (i + c < used && tiles[i + c] == t && c < 255) c++;
            runs.write(c); runs.write(t);
            nruns++; i += c;
        }
        if (nruns > 0xFFFF) throw new IllegalStateException("too many runs");
        body.write(nruns >> 8); body.write(nruns);
        byte[] r = runs.toByteArray();
        body.write(r, 0, r.length);
        List<int[]> sorted = new ArrayList<int[]>(objects);
        Collections.sort(sorted, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[2] != b[2] ? a[2] - b[2] : a[1] - b[1]; }
        });
        body.write(sorted.size() >> 8); body.write(sorted.size());
        for (int[] o : sorted) { body.write(o[0]); body.write(o[1]); body.write(o[2]); }
        body.write(tail, 0, tail.length);
        byte[] b = body.toByteArray();
        byte[] out = new byte[b.length + 2];
        out[0] = (byte) (b.length >> 8); out[1] = (byte) b.length;
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    public AowMap copy() {
        AowMap m = new AowMap();
        m.base = base; m.width = width; m.height = height; m.tileset = tileset;
        m.tiles = tiles.clone();
        for (int[] o : objects) m.objects.add(o.clone());
        m.tail = tail.clone();
        return m;
    }

    public int get(int x, int y) { return tiles[y * width + x]; }
    public void set(int x, int y, int t) { tiles[y * width + x] = t & 255; }

    /** Removes objects at a cell; returns how many were removed. */
    public int removeObjectsAt(int x, int y) {
        int n = 0;
        for (int i = objects.size() - 1; i >= 0; i--) {
            int[] o = objects.get(i);
            if (o[1] == x && o[2] == y) { objects.remove(i); n++; }
        }
        return n;
    }

    public void addObject(int type, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        objects.add(new int[]{type & 255, x, y});
    }

    static int u16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
}
