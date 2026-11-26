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
 *   fog  height x ceil(width/32) x 4 bytes  (visibility masks, kept as is)
 *   u8   number of buildings
 *   blds x { u8 kind; u8 x; u8 y; u8 group }   placed buildings (kind 0..7 = player, 8..14 = enemy)
 *   ...  rest: AI zones, attack waves, mission script, briefing text. Kept byte-for-byte; only the script is
 *        read (see MapScript) to show where units spawn.
 */
public class AowMap {
    public int base = 0xFF;
    public int width, height, tileset;
    public int[] tiles;                       // width*height
    public final List<int[]> objects = new ArrayList<int[]>();   // {type,x,y}
    public byte[] fog = new byte[0];
    /** Placed buildings {kind, x, y, group}. */
    public final List<int[]> buildings = new ArrayList<int[]>();
    public byte[] rest = new byte[0];
    /** False when the building block could not be located (building features are then disabled). */
    public boolean structured = true;
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
        int fogLen = m.height * ((m.width - 1) / 32 + 1) * 4;
        boolean ok = p + fogLen + 1 <= file.length;
        int q = p + fogLen;
        int nb = 0;
        if (ok) { nb = file[q] & 255; ok = q + 1 + nb * 4 <= file.length; }
        if (ok) {
            m.fog = new byte[fogLen];
            System.arraycopy(file, p, m.fog, 0, fogLen);
            q++;
            for (int i = 0; i < nb; i++) {
                m.buildings.add(new int[]{file[q] & 255, file[q + 1] & 255, file[q + 2] & 255, file[q + 3] & 255});
                q += 4;
            }
            m.rest = new byte[file.length - q];
            System.arraycopy(file, q, m.rest, 0, m.rest.length);
        } else {
            m.structured = false;
            m.fog = new byte[0];
            m.rest = new byte[file.length - p];
            System.arraycopy(file, p, m.rest, 0, m.rest.length);
        }
        m.raw = file.clone();
        return m;
    }

    // ------------------------------------------------------------------ buildings

    /** Footprints {w,h} of building kinds 0..14 (from the game's d0 table gz[18]/gz[19]). */
    private static final int[][] FOOT = {{4, 4}, {3, 3}, {3, 3}, {4, 3}, {3, 3}, {2, 2}, {2, 2}, {2, 2},
            {3, 4}, {3, 3}, {3, 3}, {3, 3}, {3, 3}, {2, 2}, {2, 2}};
    public static final int KINDS = FOOT.length;          // 15
    public static final int MAX_PER_OWNER = 50;           // the game's slot limit per side

    public static int[] footprint(int kind) {
        return kind >= 0 && kind < FOOT.length ? new int[]{FOOT[kind][0], FOOT[kind][1]} : new int[]{1, 1};
    }

    /** 0 = player, 1 = enemy. */
    public static int owner(int kind) { return kind / 8; }

    public int countOwner(int owner) {
        int n = 0;
        for (int[] b : buildings) if (owner(b[0]) == owner) n++;
        return n;
    }

    /** Index of the building whose footprint covers the cell, or -1 (the one drawn on top wins). */
    public int buildingAt(int x, int y) {
        int best = -1;
        for (int i = 0; i < buildings.size(); i++) {
            int[] b = buildings.get(i);
            int[] f = footprint(b[0]);
            if (x >= b[1] && x < b[1] + f[0] && y >= b[2] && y < b[2] + f[1]) {
                if (best < 0 || b[2] >= buildings.get(best)[2]) best = i;
            }
        }
        return best;
    }

    /**
     * Checks a building could stand at (x,y). {@code ignore} is an index to leave out of the overlap test
     * (the building being moved / retyped) or -1. Returns null when fine, otherwise a reason.
     */
    public String checkBuilding(int kind, int x, int y, int ignore) {
        if (!structured) return "Format bangunan peta ini tidak dikenali";
        if (kind < 0 || kind >= KINDS) return "Tipe bangunan tidak valid";
        int[] f = footprint(kind);
        if (x < 0 || y < 0 || x + f[0] > width || y + f[1] > height) return "Bangunan keluar dari peta";
        for (int i = 0; i < buildings.size(); i++) {
            if (i == ignore) continue;
            int[] b = buildings.get(i);
            int[] g = footprint(b[0]);
            if (x < b[1] + g[0] && b[1] < x + f[0] && y < b[2] + g[1] && b[2] < y + f[1])
                return "Menimpa bangunan lain";
        }
        int own = owner(kind);
        int n = 0;
        for (int i = 0; i < buildings.size(); i++) if (i != ignore && owner(buildings.get(i)[0]) == own) n++;
        if (n >= MAX_PER_OWNER) return "Batas " + MAX_PER_OWNER + " bangunan per pihak tercapai";
        if (buildings.size() - (ignore >= 0 ? 1 : 0) >= 255) return "Terlalu banyak bangunan";
        return null;
    }

    /** Adds a building (after checkBuilding); returns its index or -1 with the reason in err[0]. */
    public int addBuilding(int kind, int x, int y, int group, String[] err) {
        String e = checkBuilding(kind, x, y, -1);
        if (e != null) { if (err != null) err[0] = e; return -1; }
        buildings.add(new int[]{kind, x, y, group & 255});
        return buildings.size() - 1;
    }

    /** Script-driven unit spawns (read only); empty if the script could not be read. */
    public List<int[]> spawns() {
        return MapScript.spawns(rest);
    }

    private static boolean sameObjects(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) return false;
        List<String> x = new ArrayList<String>(), y = new ArrayList<String>();
        for (int[] o : a) x.add(o[0] + "," + o[1] + "," + o[2]);
        for (int[] o : b) y.add(o[0] + "," + o[1] + "," + o[2]);
        Collections.sort(x); Collections.sort(y);
        return x.equals(y);
    }

    private static boolean sameBuildings(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    /** True when nothing differs from the bytes this map was parsed from. */
    public boolean isUnmodified() {
        if (raw == null) return false;
        AowMap o = parse(raw);
        return o.base == base && o.width == width && o.height == height && o.tileset == tileset
                && java.util.Arrays.equals(o.tiles, tiles) && java.util.Arrays.equals(o.rest, rest)
                && java.util.Arrays.equals(o.fog, fog) && sameBuildings(o.buildings, buildings)
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
        if (structured) {
            body.write(fog, 0, fog.length);
            body.write(buildings.size());
            for (int[] bd : buildings) { body.write(bd[0]); body.write(bd[1]); body.write(bd[2]); body.write(bd[3]); }
        }
        body.write(rest, 0, rest.length);
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
        m.fog = fog.clone();
        for (int[] b : buildings) m.buildings.add(b.clone());
        m.rest = rest.clone();
        m.structured = structured;
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
