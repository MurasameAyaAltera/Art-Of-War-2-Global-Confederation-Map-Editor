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
 *   blds x { u8 kind; u8 x; u8 y; u8 group }   placed buildings (kind 0..7 = player, 8..14 = enemy).
 *        kind >= 15 is a 1x1 wall (the loader e.a(int) stores it in the wall grid as 1001 + 100*side + 20*level):
 *        15..19 = player walls (level 0..4), 25..29 = enemy walls (20..24 / 30..34 are accepted as aliases)
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
    /** Terrain and objects as they were when the map was read; used to find which cells the user changed. */
    private int[] baseTiles;
    private List<int[]> baseObjects;
    private byte[] baseFog;

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
        m.baseTiles = m.tiles.clone();
        m.baseFog = m.fog.clone();
        m.baseObjects = new ArrayList<int[]>();
        for (int[] o : m.objects) m.baseObjects.add(o.clone());
        return m;
    }

    // ------------------------------------------------------------------ buildings

    /** Footprints {w,h} of building kinds 0..14 (from the game's d0 table gz[18]/gz[19]); walls are 1x1. */
    private static final int[][] FOOT = {{4, 4}, {3, 3}, {3, 3}, {4, 3}, {3, 3}, {2, 2}, {2, 2}, {2, 2},
            {3, 4}, {3, 3}, {3, 3}, {3, 3}, {3, 3}, {2, 2}, {2, 2}};
    public static final int KINDS = FOOT.length;          // 15 real buildings (kind 0..14)
    public static final int WALL_FIRST = 15, WALL_LAST = 34;
    public static final int MAX_PER_OWNER = 50;           // the game's slot limit per side (real buildings only)

    /** Kinds offered in the palette: buildings 0..14, player walls 15..19, enemy walls 25..29. */
    public static final int[] PALETTE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 25, 26, 27, 28, 29};

    public static boolean isWall(int kind) { return kind >= WALL_FIRST && kind <= WALL_LAST; }

    /** True for any kind the game's loader understands (building or wall). */
    public static boolean validKind(int kind) { return (kind >= 0 && kind < KINDS) || isWall(kind); }

    public static int[] footprint(int kind) {
        return kind >= 0 && kind < FOOT.length ? new int[]{FOOT[kind][0], FOOT[kind][1]} : new int[]{1, 1};
    }

    /** 0 = player, 1 = enemy. */
    public static int owner(int kind) {
        if (kind < KINDS) return kind / 8;
        return (kind - WALL_FIRST) / 10;
    }

    /** Hit points of building kinds 0..14, read from the game's resource "a" (offset 3091). */
    private static final int[] HP = {250, 120, 120, 150, 100, 250, 120, 160, 250, 120, 150, 120, 120, 180, 160};
    private static final String[] NAME_ID = {"Pusat Komando", "Generator", "Pusat Infanteri", "Pabrik Kendaraan",
            "Pusat Teknologi", "Bunker", "Lokator / Radar", "Peluncur Rudal", "Markas", "Pembangkit Listrik", "Barak",
            "Pabrik", "Laboratorium", "Bunker", "Peluncur Rudal"};
    /** Russian in-game names; "" where the name was not confirmed. */
    private static final String[] NAME_RU = {"\u041a\u043e\u043c\u0430\u043d\u0434\u043d\u044b\u0439 \u0446\u0435\u043d\u0442\u0440",
            "\u0413\u0435\u043d\u0435\u0440\u0430\u0442\u043e\u0440",
            "\u041f\u0435\u0445\u043e\u0442\u043d\u044b\u0439 \u0446\u0435\u043d\u0442\u0440",
            "\u0417\u0430\u0432\u043e\u0434 \u0442\u0435\u0445\u043d\u0438\u043a\u0438",
            "\u0426\u0435\u043d\u0442\u0440 \u0442\u0435\u0445\u043d\u043e\u043b\u043e\u0433\u0438\u0439",
            "\u0411\u0443\u043d\u043a\u0435\u0440", "\u041b\u043e\u043a\u0430\u0442\u043e\u0440",
            "\u0420\u0430\u043a\u0435\u0442\u043d\u0430\u044f \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043a\u0430",
            "\u0428\u0442\u0430\u0431",
            "\u042d\u043b\u0435\u043a\u0442\u0440\u043e\u0441\u0442\u0430\u043d\u0446\u0438\u044f",
            "\u041a\u0430\u0437\u0430\u0440\u043c\u0430", "\u0417\u0430\u0432\u043e\u0434", "", "", ""};

    /** Indonesian name of a building kind; "Tembok" for walls. */
    public static String buildingName(int kind) {
        if (kind >= 0 && kind < NAME_ID.length) return NAME_ID[kind];
        return isWall(kind) ? "Tembok" : "Tipe " + kind;
    }

    /** Russian in-game name, or "" when unknown / not a building. */
    public static String buildingNameRu(int kind) {
        return kind >= 0 && kind < NAME_RU.length ? NAME_RU[kind] : "";
    }

    /** Hit points of a building kind, or -1 for walls / unknown kinds. */
    public static int buildingHp(int kind) { return kind >= 0 && kind < HP.length ? HP[kind] : -1; }

    /** Short description of a kind, e.g. "Peluncur Rudal (tipe 7)" or "Tembok tipe 27". */
    public static String kindName(int kind) {
        if (isWall(kind)) return "Tembok tipe " + kind;
        if (kind >= 0 && kind < NAME_ID.length) return NAME_ID[kind] + " (tipe " + kind + ")";
        return "Bangunan tipe " + kind;
    }

    /** Number of real buildings (not walls) of a side; walls do not use the game's 50 slots. */
    public int countOwner(int owner) {
        int n = 0;
        for (int[] b : buildings) if (!isWall(b[0]) && owner(b[0]) == owner) n++;
        return n;
    }

    public int countWalls() {
        int n = 0;
        for (int[] b : buildings) if (isWall(b[0])) n++;
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
        if (!validKind(kind)) return "Tipe bangunan tidak valid (0-14 bangunan, 15-29 tembok)";
        int[] f = footprint(kind);
        if (x < 0 || y < 0 || x + f[0] > width || y + f[1] > height) return "Bangunan keluar dari peta";
        for (int i = 0; i < buildings.size(); i++) {
            if (i == ignore) continue;
            int[] b = buildings.get(i);
            int[] g = footprint(b[0]);
            if (x < b[1] + g[0] && b[1] < x + f[0] && y < b[2] + g[1] && b[2] < y + f[1])
                return "Menimpa bangunan lain";
        }
        if (!isWall(kind)) {
            int own = owner(kind);
            int n = 0;
            for (int i = 0; i < buildings.size(); i++) {
                int[] o = buildings.get(i);
                if (i != ignore && !isWall(o[0]) && owner(o[0]) == own) n++;
            }
            if (n >= MAX_PER_OWNER) return "Batas " + MAX_PER_OWNER + " bangunan per pihak tercapai";
        }
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

    /** Wall zones {group, x, y, w, h} stored after the building list (read only), see {@link MapScript#wallRects}. */
    public List<int[]> wallZones() {
        return MapScript.wallRects(rest);
    }

    // ------------------------------------------------------------------ attack paths + script (editable)

    public static final int MAX_PATH_POINTS = MapScript.MAX_PATH_POINTS, MAX_EVENTS = MapScript.MAX_EVENTS;

    /** True when the attack paths and script of this map can be edited. */
    public boolean scriptEditable() { return structured && MapScript.editable(rest); }

    /** Attack path waypoints {x,y} of AI group 0..2 (a fresh list; edit it and pass it to setAttackPath). */
    public List<int[]> attackPath(int group) {
        return MapScript.attackPaths(rest).get(Math.max(0, Math.min(2, group)));
    }

    /** Replaces the attack path of a group. Returns null when done, otherwise the reason it was refused. */
    public String setAttackPath(int group, List<int[]> pts) {
        if (!scriptEditable()) return "Data jalur serang peta ini tidak dikenali";
        if (group < 0 || group > 2) return "Grup harus 0-2";
        if (pts.size() > MAX_PATH_POINTS) return "Maksimal " + MAX_PATH_POINTS + " titik per grup";
        for (int[] q : pts) if (q[0] < 0 || q[1] < 0 || q[0] >= width || q[1] >= height) return "Titik di luar peta";
        List<List<int[]>> all = MapScript.attackPaths(rest);
        all.set(group, new ArrayList<int[]>(pts));
        rest = MapScript.withAttackPaths(rest, all);
        return null;
    }

    /** Script events {op,x,y,a,b,group} in execution order (a fresh list). */
    public List<int[]> script() { return MapScript.events(rest); }

    /** Replaces the whole script. Returns null when done, otherwise the reason it was refused. */
    public String setScript(List<int[]> ev) {
        if (!scriptEditable()) return "Skrip peta ini tidak dikenali";
        if (ev.size() > MAX_EVENTS) return "Maksimal " + MAX_EVENTS + " event skrip";
        for (int[] e : ev) {
            if (e.length != 6) return "Event tidak valid";
            for (int v : e) if (v < 0 || v > 255) return "Nilai event harus 0-255";
        }
        rest = MapScript.withEvents(rest, ev);
        return null;
    }

    /**
     * Result of reading this map and writing it again: 0 = exactly the same bytes, 1 = different bytes but the same
     * content (the game reads both the same; e.g. an unusual run split in the original), 2 = content differs.
     */
    public int roundTrip() {
        if (raw == null) return 2;
        try {
            byte[] o = parse(raw).encode();
            if (java.util.Arrays.equals(o, raw)) return 0;
            return parse(raw).sameContent(parse(o)) ? 1 : 2;
        } catch (RuntimeException e) {
            return 2;
        }
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
        return sameContent(parse(raw));
    }

    private boolean sameContent(AowMap o) {
        return o.base == base && o.width == width && o.height == height && o.tileset == tileset
                && java.util.Arrays.equals(o.tiles, tiles) && java.util.Arrays.equals(o.rest, rest)
                && java.util.Arrays.equals(o.fog, fog) && sameBuildings(o.buildings, buildings)
                && sameObjects(o.objects, objects);
    }

    // ------------------------------------------------------------------ passability

    private int bitAt(byte[] f, int x, int y) {
        int per = (width - 1) / 32 + 1;
        int word = (y * per + x / 32) * 4;
        int v = ((f[word] & 255) << 24) | ((f[word + 1] & 255) << 16) | ((f[word + 2] & 255) << 8) | (f[word + 3] & 255);
        return (v >>> (31 - (x % 32))) & 1;
    }

    /** Bit 1 = blocked for ground units (see {@link Passability}). */
    public boolean isBlocked(int x, int y) {
        if (fog.length == 0 || x < 0 || y < 0 || x >= width || y >= height) return false;
        return bitAt(fog, x, y) != 0;
    }

    public void setBlocked(int x, int y, boolean b) {
        if (fog.length == 0 || x < 0 || y < 0 || x >= width || y >= height) return;
        int per = (width - 1) / 32 + 1;
        int word = (y * per + x / 32) * 4;
        int bit = 31 - (x % 32);
        int v = ((fog[word] & 255) << 24) | ((fog[word + 1] & 255) << 16) | ((fog[word + 2] & 255) << 8) | (fog[word + 3] & 255);
        v = b ? (v | (1 << bit)) : (v & ~(1 << bit));
        fog[word] = (byte) (v >>> 24); fog[word + 1] = (byte) (v >>> 16); fog[word + 2] = (byte) (v >>> 8); fog[word + 3] = (byte) v;
    }

    private boolean[] cover(List<int[]> objs, Passability p) {
        boolean[] c = new boolean[width * height];
        for (int[] o : objs) {
            int[] f = p.footprint(tileset, o[0]);
            for (int yy = o[2]; yy < o[2] + f[1] && yy < height; yy++)
                for (int xx = o[1]; xx < o[1] + f[0] && xx < width; xx++)
                    if (xx >= 0 && yy >= 0) c[yy * width + xx] = true;
        }
        return c;
    }

    /**
     * Brings the walkability bits in line with what the user changed: for every cell whose tile changed, or whose
     * object coverage changed, since the map was read, bit = water/cliff tile OR under an object footprint.
     * Cells the user did not touch keep their original bits (the stock maps have hand-tuned blocks).
     *
     * @return number of cells whose bit was changed
     */
    public int syncPassability(Passability p) {
        if (!structured || fog.length == 0 || baseTiles == null || p == null) return 0;
        boolean[] was = cover(baseObjects, p), now = cover(objects, p);
        int changed = 0;
        for (int i = 0; i < tiles.length; i++) {
            int x = i % width, y = i / width;
            boolean untouched = tiles[i] == baseTiles[i] && was[i] == now[i];
            // untouched cells go back to their original (possibly hand-tuned) bit, so reverting an edit is exact
            boolean want = untouched ? bitAt(baseFog, x, y) != 0 : (now[i] || p.tileBlocked(tileset, tiles[i]));
            if (isBlocked(x, y) != want) { setBlocked(x, y, want); changed++; }
        }
        return changed;
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
        if (baseTiles != null) {
            m.baseTiles = baseTiles.clone();
            m.baseFog = baseFog.clone();
            m.baseObjects = new ArrayList<int[]>();
            for (int[] o : baseObjects) m.baseObjects.add(o.clone());
        }
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
