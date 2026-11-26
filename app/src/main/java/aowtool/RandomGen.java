package aowtool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Random generator for the editable parts of a map: terrain, decoration objects, buildings and the AI rally points
 * (the editor's "jalur serang", the game's iJ lists).
 *
 * Rules every stage follows:
 *   - cells the mission needs (unit spawns, script positions, wall zones, buildings that stay) are kept free;
 *   - key points that could reach each other on foot before generating can still reach each other afterwards;
 *   - the map object is only changed when the whole generation succeeded.
 *
 * Pure Java (no Android classes) so it can be tested on the desktop, see tools/test/RandomGenTest.
 */
public final class RandomGen {

    /** What to generate and how. The same options + seed on the same map give the same result. */
    public static final class Options {
        public boolean terrain, objects, buildings, paths;
        public long seed;
        /** Terrain: share of water and bare soil, in percent of the map. */
        public int water = 15, dirt = 20;
        /** Objects: share of the free ground covered by forest and rock, in percent. */
        public int forest = 15, rock = 6;
        /** Objects: true = remove the old ones first, false = only add. */
        public boolean replaceObjects = true;
        /** Buildings: 0 = player, 1 = enemy. */
        public int side = 1;
        public int buildingCount = 25;
        public boolean replaceBuildings = true;
        /** Buildings: {x, y, w, h} to build in, or null = around the existing base(s). */
        public int[] area;
        /** Rally points per AI group (1..4) and how far from the base (0 near, 1 middle, 2 forward). */
        public int pathPoints = 2, pathDistance = 1;
    }

    // ------------------------------------------------------------------ tileset tables

    private static final int WATER = 0, DARK = 1, LIGHT = 2, SOIL = 3;

    /** Tile ids of one tileset. Transition tables are indexed by the corner mask (TL=1, TR=2, BL=4, BR=8). */
    private static final class Theme {
        int[] light, dark, water, forestFill, rockFill;
        int[][] soil;              // one or two kinds of soil
        int[][][] soilEdge;        // light <-> soil[k], mask = corners that are soil
        int[][] darkEdge;          // light <-> dark, mask = corners that are dark grass
        int[][] coast;             // dark <-> water, mask = corners that are water
    }

    private static final Theme[] THEMES = {theme0(), theme1()};

    private static int[] range(int a, int b) {
        int[] r = new int[b - a + 1];
        for (int i = 0; i < r.length; i++) r[i] = a + i;
        return r;
    }

    /** Builds a 16 entry table from the 12 mixed masks in the order 1,2,3,4,5,7,8,10,11,12,13,14. */
    private static int[][] edges(int[]... t) {
        int[] masks = {1, 2, 3, 4, 5, 7, 8, 10, 11, 12, 13, 14};
        int[][] out = new int[16][];
        for (int i = 0; i < masks.length; i++) out[masks[i]] = t[i];
        return out;
    }

    private static int[] a(int... v) { return v; }

    private static Theme theme0() {
        Theme t = new Theme();
        t.light = range(0, 9); t.dark = range(10, 17); t.water = range(26, 33);
        t.soil = new int[][]{range(19, 25)};
        t.darkEdge = edges(a(67), a(68), a(60, 61, 62), a(70), a(54, 55, 56), a(63), a(69), a(52, 53), a(64),
                a(57, 58, 59), a(66), a(65));
        t.soilEdge = new int[][][]{edges(a(91), a(92), a(79, 80, 81), a(90), a(74, 75, 76), a(85), a(89), a(77, 78),
                a(86), a(82, 83, 84), a(87), a(88))};
        t.coast = edges(a(108), a(109), a(99, 100, 101), a(107), a(93, 94), a(104), a(106), a(95, 96), a(105),
                a(97, 98), a(103), a(102));
        t.forestFill = a(236, 237, 240);
        t.rockFill = a(242, 243, 244);
        return t;
    }

    private static Theme theme1() {
        Theme t = new Theme();
        t.light = range(0, 9); t.dark = range(10, 17); t.water = range(33, 35);
        t.soil = new int[][]{range(26, 32), range(18, 21)};
        t.darkEdge = edges(a(64), a(65), a(57, 58, 59), a(67), a(51, 52, 53), a(60), a(66), a(49, 50), a(61),
                a(54, 55, 56), a(63), a(62));
        t.soilEdge = new int[][][]{
                edges(a(89), a(90), a(77, 78, 79), a(88), a(71, 72, 73), a(83), a(87), a(74, 75, 76), a(84),
                        a(80, 81, 82), a(85), a(86)),
                edges(a(112), a(113), a(100, 101, 102), a(111), a(94, 95, 96), a(106), a(110), a(97, 98, 99), a(107),
                        a(103, 104, 105), a(108), a(109))};
        t.coast = edges(a(130), a(131), a(121, 122, 123), a(129), a(114, 115), a(126), a(128), a(116, 117), a(127),
                a(118, 119, 120), a(125), a(124));
        t.forestFill = a(238, 239, 240, 242);
        t.rockFill = a(232, 233, 234, 235);
        return t;
    }

    // object types (identical numbering in both tilesets)
    private static final int[] TREE3 = {61, 62};
    private static final int[] TREE2 = {50, 51, 52, 53, 54, 55, 56, 57, 63, 64, 65, 66};
    private static final int[] TREE1 = {67, 68, 69, 70, 71, 72, 73, 74, 77, 78, 79, 81, 82, 83, 84, 85};
    private static final int[] ROCKS = {22, 37, 36, 18, 19, 20, 21, 34, 35, 15, 16, 17, 23, 24, 25, 1, 12, 13, 2, 29, 32};

    // ------------------------------------------------------------------ working state

    private final AowMap m;
    private final Passability pass;
    private final Options o;
    private final Random rnd;
    private final int w, h;
    private final Theme theme;
    private final List<String> report = new ArrayList<String>();
    /** Unit spawn cells and other script positions {x, y}. */
    private final List<int[]> scriptCells = new ArrayList<int[]>();
    /** Key points whose connections must survive: {x, y, component before generating}. */
    private final List<int[]> keys = new ArrayList<int[]>();

    private RandomGen(AowMap work, Passability p, Options opt) {
        m = work; pass = p; o = opt;
        rnd = new Random(opt.seed);
        w = work.width; h = work.height;
        theme = work.tileset >= 0 && work.tileset < THEMES.length ? THEMES[work.tileset] : null;
    }

    /**
     * Applies the generator to {@code map}.
     *
     * @return a short report (Indonesian) of what was done
     * @throws IllegalStateException with a readable reason when nothing was changed
     */
    public static String generate(AowMap map, Passability p, Options opt) {
        if (p == null) throw new IllegalStateException("Data d0/ml dari JAR tidak tersedia");
        if (!map.structured || map.fog.length == 0) throw new IllegalStateException("Format peta ini tidak dikenali");
        if (!opt.terrain && !opt.objects && !opt.buildings && !opt.paths)
            throw new IllegalStateException("Pilih minimal satu yang mau diacak");
        AowMap work = map.copy();
        RandomGen g = new RandomGen(work, p, opt);
        if ((opt.terrain || opt.objects) && g.theme == null)
            throw new IllegalStateException("Tileset " + map.tileset + " belum didukung untuk terrain/objek");
        g.run();
        System.arraycopy(work.tiles, 0, map.tiles, 0, map.tiles.length);
        map.objects.clear();
        map.objects.addAll(work.objects);
        map.buildings.clear();
        map.buildings.addAll(work.buildings);
        map.rest = work.rest;
        map.forced = work.forced;
        StringBuilder sb = new StringBuilder();
        for (String s : g.report) sb.append(sb.length() == 0 ? "" : "\n").append(s);
        return sb.toString();
    }

    private void run() {
        collectKeys();
        if (o.terrain) terrain();
        if (o.terrain || o.objects) objects();
        if (o.buildings) buildings();
        if (o.paths) rallyPoints();
    }

    // ------------------------------------------------------------------ grids and helpers

    private boolean in(int x, int y) { return x >= 0 && y >= 0 && x < w && y < h; }

    /** Cells ground units cannot enter, exactly as they will be saved (buildings not included). */
    private boolean[] blocked() {
        AowMap c = m.copy();
        c.syncPassability(pass);
        boolean[] b = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b[y * w + x] = c.isBlocked(x, y);
        return b;
    }

    private void addBuildingCells(boolean[] grid) {
        for (int[] b : m.buildings) {
            int[] f = AowMap.footprint(b[0]);
            mark(grid, b[1], b[2], f[0], f[1], 0);
        }
    }

    private void mark(boolean[] grid, int x, int y, int rw, int rh, int margin) {
        for (int yy = y - margin; yy < y + rh + margin; yy++)
            for (int xx = x - margin; xx < x + rw + margin; xx++)
                if (in(xx, yy)) grid[yy * w + xx] = true;
    }

    private boolean[] dilate(boolean[] g, int r) {
        boolean[] out = new boolean[w * h];
        for (int i = 0; i < g.length; i++) if (g[i]) mark(out, i % w, i / w, 1, 1, r);
        return out;
    }

    private static final int[][] N4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 4-connected components of the open cells (-1 = blocked). */
    private int[] components(boolean[] blk) {
        int[] c = new int[w * h];
        Arrays.fill(c, -1);
        int[] q = new int[w * h];
        int id = 0;
        for (int s = 0; s < c.length; s++) {
            if (blk[s] || c[s] >= 0) continue;
            int head = 0, tail = 0;
            q[tail++] = s; c[s] = id;
            while (head < tail) {
                int i = q[head++];
                for (int[] d : N4) {
                    int x = i % w + d[0], y = i / w + d[1];
                    if (!in(x, y)) continue;
                    int j = y * w + x;
                    if (!blk[j] && c[j] < 0) { c[j] = id; q[tail++] = j; }
                }
            }
            id++;
        }
        return c;
    }

    /** Walking distance from any source cell over open cells; -1 = unreachable. */
    private int[] distances(boolean[] blk, List<Integer> sources) {
        int[] dist = new int[w * h];
        Arrays.fill(dist, -1);
        int[] q = new int[w * h];
        int head = 0, tail = 0;
        for (int s : sources) if (!blk[s] && dist[s] < 0) { dist[s] = 0; q[tail++] = s; }
        while (head < tail) {
            int i = q[head++];
            for (int[] d : N4) {
                int x = i % w + d[0], y = i / w + d[1];
                if (!in(x, y)) continue;
                int j = y * w + x;
                if (!blk[j] && dist[j] < 0) { dist[j] = dist[i] + 1; q[tail++] = j; }
            }
        }
        return dist;
    }

    /** Nearest open cell within {@code r} of (x,y), or -1. */
    private int snap(boolean[] blk, int x, int y, int r) {
        int best = -1, bd = Integer.MAX_VALUE;
        for (int yy = y - r; yy <= y + r; yy++)
            for (int xx = x - r; xx <= x + r; xx++) {
                if (!in(xx, yy) || blk[yy * w + xx]) continue;
                int d = (xx - x) * (xx - x) + (yy - y) * (yy - y);
                if (d < bd) { bd = d; best = yy * w + xx; }
            }
        return best;
    }

    private void collectKeys() {
        for (int[] e : m.script()) {
            int op = e[0];
            if ((op == 27 || op == 24 || ((op == 35 || op == 36) && (e[1] != 0 || e[2] != 0))) && in(e[1], e[2]))
                scriptCells.add(new int[]{e[1], e[2]});
        }
        List<int[]> pts = new ArrayList<int[]>();
        for (int[] e : m.script()) if (e[0] == 27 && in(e[1], e[2])) pts.add(new int[]{e[1], e[2]});
        for (int[] b : m.buildings) {
            if (AowMap.isWall(b[0])) continue;
            int[] f = AowMap.footprint(b[0]);
            pts.add(new int[]{b[1] + f[0] / 2, b[2] + f[1]});
        }
        if (!o.paths) for (int g = 0; g < 3; g++) pts.addAll(m.attackPath(g));
        boolean[] blk = blocked();
        int[] comp = components(blk);
        for (int[] p : pts) {
            int c = snap(blk, p[0], p[1], 4);
            if (c >= 0) keys.add(new int[]{p[0], p[1], comp[c]});
        }
    }

    /** Takes the current state (buildings included) as the connections that must survive from now on. */
    private void rebaseKeys() {
        boolean[] blk = blocked();
        addBuildingCells(blk);
        int[] comp = components(blk);
        for (int i = keys.size() - 1; i >= 0; i--) {
            int c = snap(blk, keys.get(i)[0], keys.get(i)[1], 6);
            if (c < 0) keys.remove(i);
            else keys.get(i)[2] = comp[c];
        }
    }

    /** First pair of key points (indices) that were connected before but are not now, or null. */
    private int[] brokenLink(boolean[] blk) {
        int[] comp = components(blk);
        List<int[]> seen = new ArrayList<int[]>();      // {old component, new component, key index}
        for (int i = 0; i < keys.size(); i++) {
            int[] k = keys.get(i);
            int c = snap(blk, k[0], k[1], 6);
            int now = c < 0 ? -2 - i : comp[c];
            int[] first = null;
            for (int[] s : seen) if (s[0] == k[2]) { first = s; break; }
            if (first == null) seen.add(new int[]{k[2], now, i});
            else if (first[1] != now) return new int[]{first[2], i};
        }
        return null;
    }

    /** Cheapest 4-connected route between two cells; cost[i] < 0 = impassable. Null when there is none. */
    private List<Integer> route(int[] cost, int from, int to) {
        final int[] dist = new int[w * h];
        int[] prev = new int[w * h];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<int[]> q = new PriorityQueue<int[]>(64, new Comparator<int[]>() {
            public int compare(int[] p, int[] r) { return p[0] != r[0] ? (p[0] < r[0] ? -1 : 1) : p[1] - r[1]; }
        });
        dist[from] = 0;
        q.add(new int[]{0, from});
        while (!q.isEmpty()) {
            int[] t = q.poll();
            int i = t[1];
            if (t[0] > dist[i]) continue;
            if (i == to) break;
            for (int[] d : N4) {
                int x = i % w + d[0], y = i / w + d[1];
                if (!in(x, y)) continue;
                int j = y * w + x;
                if (cost[j] < 0) continue;
                int nd = dist[i] + cost[j];
                if (nd < dist[j]) { dist[j] = nd; prev[j] = i; q.add(new int[]{nd, j}); }
            }
        }
        if (dist[to] == Integer.MAX_VALUE) return null;
        List<Integer> path = new ArrayList<Integer>();
        for (int i = to; i >= 0; i = prev[i]) path.add(i);
        return path;
    }

    private int pick(int[] ids) { return ids[rnd.nextInt(ids.length)]; }

    /** Smooth random field in 0..1 on a gw x gh grid; {@code scale} is the feature size in cells. */
    private double[] noise(int gw, int gh, double scale) {
        double[] out = new double[gw * gh];
        double amp = 1;
        for (int oct = 0; oct < 3; oct++) {
            double s = Math.max(1.5, scale / (1 << oct));
            int lw = (int) (gw / s) + 3, lh = (int) (gh / s) + 3;
            double[] lat = new double[lw * lh];
            for (int i = 0; i < lat.length; i++) lat[i] = rnd.nextDouble();
            double ox = rnd.nextDouble(), oy = rnd.nextDouble();
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    double fx = x / s + ox, fy = y / s + oy;
                    int ix = (int) fx, iy = (int) fy;
                    double tx = fx - ix, ty = fy - iy;
                    tx = tx * tx * (3 - 2 * tx);
                    ty = ty * ty * (3 - 2 * ty);
                    double top = lat[iy * lw + ix] * (1 - tx) + lat[iy * lw + ix + 1] * tx;
                    double bot = lat[(iy + 1) * lw + ix] * (1 - tx) + lat[(iy + 1) * lw + ix + 1] * tx;
                    out[y * gw + x] += amp * (top * (1 - ty) + bot * ty);
                }
            }
            amp *= 0.45;
        }
        return out;
    }

    /** Value above which {@code percent} % of the selected entries lie. */
    private static double threshold(double[] v, boolean[] select, int percent) {
        double[] s = new double[v.length];
        int n = 0;
        for (int i = 0; i < v.length; i++) if (select == null || select[i]) s[n++] = v[i];
        int take = n * Math.max(0, Math.min(100, percent)) / 100;
        if (take <= 0) return Double.MAX_VALUE;
        Arrays.sort(s, 0, n);
        return s[n - take] - 1e-12;
    }

    // ------------------------------------------------------------------ terrain

    private void terrain() {
        int vw = w + 1, vh = h + 1;
        int[] lv = new int[vw * vh];
        double[] nd = noise(vw, vh, 7), ns = noise(vw, vh, 8), nw = noise(vw, vh, 13);
        double td = threshold(nd, null, 35), ts = threshold(ns, null, o.dirt), tw = threshold(nw, null, o.water);
        for (int i = 0; i < lv.length; i++) {
            lv[i] = LIGHT;
            if (nd[i] > td) lv[i] = DARK;
            else if (ns[i] > ts) lv[i] = SOIL;
            if (nw[i] > tw) lv[i] = WATER;
        }

        boolean[] flat = new boolean[w * h];       // plain land: buildings, spawns, wall zones
        boolean[] dry = new boolean[w * h];        // no water: AI base zones, chosen building area
        for (int[] b : m.buildings) {
            int[] f = AowMap.footprint(b[0]);
            mark(flat, b[1], b[2], f[0], f[1], 2);
        }
        for (int[] z : m.wallZones()) mark(flat, z[1], z[2], z[3], z[4], 1);
        for (int[] c : scriptCells) mark(flat, c[0], c[1], 1, 1, 3);
        for (int g = 0; g < 3; g++) for (int[] p : m.attackPath(g)) mark(flat, p[0], p[1], 1, 1, 1);
        for (int[] z : MapScript.baseZones(m.rest)) mark(dry, z[1], z[2], z[3], z[4], 0);
        if (o.buildings && o.area != null) mark(dry, o.area[0], o.area[1], o.area[2], o.area[3], 1);

        for (int i = 0; i < w * h; i++) {
            if (flat[i]) setCorners(lv, i, LIGHT, true);
        }
        boolean[] land = new boolean[w * h];
        for (int i = 0; i < w * h; i++) land[i] = flat[i] || dry[i];
        for (int i = 0; i < w * h; i++) if (land[i]) forceLand(lv, i);

        int carved = 0;
        smooth(lv, vw, vh);
        // water must not cut apart places that were connected before
        for (int round = 0; round < keys.size() + 1; round++) {
            boolean[] wet = new boolean[w * h];
            for (int i = 0; i < w * h; i++) wet[i] = hasWater(lv, i);
            int[] link = brokenLink(wet);
            if (link == null) break;
            int[] cost = new int[w * h];
            for (int i = 0; i < w * h; i++) cost[i] = wet[i] ? 12 : 2;
            int from = keyCell(keys.get(link[0])), to = keyCell(keys.get(link[1]));
            List<Integer> path = route(cost, from, to);
            if (path == null) break;
            boolean[] road = new boolean[w * h];
            for (int i : path) road[i] = true;
            road = dilate(road, 1);
            for (int i = 0; i < w * h; i++) if (road[i]) forceLand(lv, i);
            smooth(lv, vw, vh);
            carved++;
        }

        paintTiles(lv, vw, vh);
        m.forced = new boolean[w * h];
        Arrays.fill(m.forced, true);       // every cell is new, so its walkable bit must be recomputed
        int water = 0;
        for (int i = 0; i < w * h; i++) if (hasWater(lv, i)) water++;
        report.add("Terrain baru: air/pantai " + (100 * water / (w * h)) + "% sel"
                + (carved > 0 ? ", " + carved + " jalur darat dibuat agar area tetap tersambung" : ""));
    }

    private int keyCell(int[] k) { return Math.max(0, Math.min(h - 1, k[1])) * w + Math.max(0, Math.min(w - 1, k[0])); }

    private void setCorners(int[] lv, int cell, int level, boolean overwrite) {
        int x = cell % w, y = cell / w, vw = w + 1;
        int[] v = {y * vw + x, y * vw + x + 1, (y + 1) * vw + x, (y + 1) * vw + x + 1};
        for (int i : v) if (overwrite || lv[i] < level) lv[i] = level;
    }

    /** Raises the corners of a cell and of its neighbours to at least dark grass, so no coast can reach it. */
    private void forceLand(int[] lv, int cell) {
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int x = cell % w + dx, y = cell / w + dy;
                if (in(x, y)) setCorners(lv, y * w + x, DARK, false);
            }
    }

    private boolean hasWater(int[] lv, int cell) {
        int x = cell % w, y = cell / w, vw = w + 1;
        return lv[y * vw + x] == WATER || lv[y * vw + x + 1] == WATER || lv[(y + 1) * vw + x] == WATER
                || lv[(y + 1) * vw + x + 1] == WATER;
    }

    /**
     * Makes the corner levels paintable: neighbours differ by at most one step (water - dark - light - soil) and no
     * cell has a diagonal pattern (the tilesets have no tile for it).
     */
    private void smooth(int[] lv, int vw, int vh) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int y = 0; y < vh; y++) {
                for (int x = 0; x < vw; x++) {
                    int min = 3;
                    for (int yy = Math.max(0, y - 1); yy <= Math.min(vh - 1, y + 1); yy++)
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(vw - 1, x + 1); xx++)
                            min = Math.min(min, lv[yy * vw + xx]);
                    if (lv[y * vw + x] > min + 1) { lv[y * vw + x] = min + 1; changed = true; }
                }
            }
            if (changed) continue;
            for (int y = 0; y < vh - 1; y++) {
                for (int x = 0; x < vw - 1; x++) {
                    int tl = y * vw + x, tr = tl + 1, bl = tl + vw, br = bl + 1;
                    if (lv[tl] != lv[br] || lv[tr] != lv[bl] || lv[tl] == lv[tr]) continue;
                    int lo = Math.min(lv[tl], lv[tr]);
                    int[] c = {tl, tr, bl, br};
                    if (lo == WATER) {
                        for (int i : c) lv[i] = DARK;      // fill the water diagonal with land
                    } else {
                        for (int i : c) lv[i] = lo;
                    }
                    changed = true;
                }
            }
        }
    }

    private void paintTiles(int[] lv, int vw, int vh) {
        // one soil kind per connected soil patch
        int[] kind = new int[vw * vh];
        Arrays.fill(kind, -1);
        int[] q = new int[vw * vh];
        for (int s = 0; s < lv.length; s++) {
            if (lv[s] != SOIL || kind[s] >= 0) continue;
            int k = rnd.nextInt(theme.soil.length);
            int head = 0, tail = 0;
            q[tail++] = s; kind[s] = k;
            while (head < tail) {
                int i = q[head++];
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = i % vw + dx, y = i / vw + dy;
                        if (x < 0 || y < 0 || x >= vw || y >= vh) continue;
                        int j = y * vw + x;
                        if (lv[j] == SOIL && kind[j] < 0) { kind[j] = k; q[tail++] = j; }
                    }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tl = y * vw + x;
                int[] c = {tl, tl + 1, tl + vw, tl + vw + 1};
                int lo = 3, hi = 0, soil = 0;
                for (int i : c) {
                    lo = Math.min(lo, lv[i]);
                    hi = Math.max(hi, lv[i]);
                    if (lv[i] == SOIL) soil = kind[i];
                }
                int id;
                if (lo == hi) {
                    id = pick(lo == WATER ? theme.water : lo == DARK ? theme.dark : lo == LIGHT ? theme.light : theme.soil[soil]);
                } else {
                    // mask = corners of the "other" terrain of the pair
                    int other = lo == WATER ? WATER : lo == DARK ? DARK : SOIL;
                    int mask = 0;
                    for (int i = 0; i < 4; i++) if (lv[c[i]] == other) mask |= 1 << i;
                    int[][] table = lo == WATER ? theme.coast : lo == DARK ? theme.darkEdge : theme.soilEdge[soil];
                    id = pick(table[mask]);
                }
                m.set(x, y, id);
            }
        }
    }

    // ------------------------------------------------------------------ objects

    private static boolean has(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private void objects() {
        int removed = 0;
        if (o.objects && o.replaceObjects) {
            removed = m.objects.size();
            boolean[] hidden = new boolean[w * h];
            for (int[] ob : m.objects) {
                int[] f = pass.footprint(m.tileset, ob[0]);
                mark(hidden, ob[1], ob[2], f[0], f[1], 0);
            }
            boolean[] was = blocked();
            m.objects.clear();
            if (!o.terrain) clearFillTiles(hidden, was);
        } else if (o.terrain) {
            // new terrain, old objects kept: drop those that would now stand in water or on a needed cell
            boolean[] bad = new boolean[w * h];
            for (int i = 0; i < w * h; i++) bad[i] = has(theme.water, m.tiles[i]) || isCoast(m.tiles[i]);
            for (int[] c : scriptCells) mark(bad, c[0], c[1], 1, 1, 1);
            addBuildingCells(bad);
            for (int[] z : m.wallZones()) mark(bad, z[1], z[2], z[3], z[4], 0);
            for (int i = m.objects.size() - 1; i >= 0; i--) {
                int[] ob = m.objects.get(i);
                if (overlaps(bad, ob)) { m.objects.remove(i); removed++; }
            }
        }
        if (!o.objects) {
            reconnectByRemovingObjects(0, null);
            if (removed > 0) report.add(removed + " objek lama yang jatuh di air / titik penting dihapus");
            return;
        }

        boolean[] keep = new boolean[w * h];      // never place anything here
        for (int[] b : m.buildings) {
            int[] f = AowMap.footprint(b[0]);
            mark(keep, b[1], b[2], f[0], f[1], 2);
        }
        for (int[] z : m.wallZones()) mark(keep, z[1], z[2], z[3], z[4], 1);
        for (int[] c : scriptCells) mark(keep, c[0], c[1], 1, 1, 3);
        for (int g = 0; g < 3; g++) for (int[] p : m.attackPath(g)) mark(keep, p[0], p[1], 1, 1, 2);
        boolean[] base = new boolean[w * h];
        for (int[] z : MapScript.baseZones(m.rest)) mark(base, z[1], z[2], z[3], z[4], 0);
        if (o.buildings && o.area != null) mark(base, o.area[0], o.area[1], o.area[2], o.area[3], 0);

        boolean[] blk = blocked();
        boolean[] free = new boolean[w * h];
        double[] nf = noise(w, h, 7), nr = noise(w, h, 9);
        for (int i = 0; i < w * h; i++) {
            free[i] = !blk[i] && !keep[i];
            if (base[i]) { nf[i] -= 0.35; nr[i] -= 0.6; }      // keep room inside the bases
        }
        int firstNew = m.objects.size();
        boolean[] rock = new boolean[w * h], wood = new boolean[w * h];
        double tr = threshold(nr, free, o.rock), tf = threshold(nf, free, o.forest);
        for (int i = 0; i < w * h; i++) {
            if (!free[i]) continue;
            if (nr[i] > tr) rock[i] = true;
            else if (nf[i] > tf) wood[i] = true;
        }
        boolean[] used = new boolean[w * h];
        boolean[] rockUsed = fill(rock, used, ROCKS);
        int[] trees = concat(TREE3, TREE2, TREE2, TREE1);
        boolean[] woodUsed = fill(wood, used, trees);
        // a few lonely trees on the open ground
        if (o.forest > 0) {
            for (int i = 0; i < w * h; i++) {
                if (free[i] && !used[i] && !base[i] && rnd.nextInt(100) < 1 + o.forest / 20) {
                    m.addObject(pick(TREE1), i % w, i / w);
                    used[i] = true;
                }
            }
        }
        int[] under = m.tiles.clone();
        if (o.terrain || o.replaceObjects) {
            ground(rockUsed, theme.rockFill);
            ground(woodUsed, theme.forestFill);
        }
        int opened = reconnectByRemovingObjects(o.terrain || o.replaceObjects ? 0 : firstNew, under);
        report.add("Objek: " + (removed > 0 ? removed + " dihapus, " : "") + (m.objects.size() - firstNew) + " ditaruh"
                + (opened > 0 ? " (" + opened + " dibuang lagi untuk membuka jalan)" : ""));
    }

    private boolean isCoast(int tile) {
        for (int[] t : theme.coast) if (t != null && has(t, tile)) return true;
        return false;
    }

    private boolean overlaps(boolean[] grid, int[] ob) {
        int[] f = pass.footprint(m.tileset, ob[0]);
        for (int y = ob[2]; y < ob[2] + f[1]; y++)
            for (int x = ob[1]; x < ob[1] + f[0]; x++)
                if (in(x, y) && grid[y * w + x]) return true;
        return false;
    }

    /**
     * Forest / rock ground tiles are blocked on their own; turn them back into land when their objects go. Plain
     * land that was {@code hidden} under an old object is repainted too, so hard edges there get transition tiles.
     */
    private void clearFillTiles(boolean[] hidden, boolean[] was) {
        // all fill-like ids of the tileset, including the ones the generator itself does not paint
        int from = m.tileset == 0 ? 235 : 228, to = m.tileset == 0 ? 244 : 243;
        // read the corner levels of the surrounding tiles so that coasts and grass edges join up
        int vw = w + 1;
        int[] lv = new int[vw * (h + 1)], kind = new int[vw * (h + 1)];
        Arrays.fill(lv, -1);
        for (int i = 0; i < w * h; i++) {
            int[] c = cornersOf(m.tiles[i]);
            if (c == null) continue;
            int tl = i / w * vw + i % w;
            int[] v = {tl, tl + 1, tl + vw, tl + vw + 1};
            for (int k = 0; k < 4; k++) {
                if (lv[v[k]] < 0 || c[k] < lv[v[k]]) lv[v[k]] = c[k];
                if (c[k] == SOIL) kind[v[k]] = c[4];
            }
        }
        for (int i = 0; i < w * h; i++) {
            if (m.tiles[i] < from || m.tiles[i] > to) {
                int[] own = cornersOf(m.tiles[i]);
                boolean plainLand = own != null && own[0] != WATER && own[0] == own[1] && own[0] == own[2]
                        && own[0] == own[3];
                // only cells that were already blocked may change (they could turn into coast)
                if (!hidden[i] || !was[i] || !plainLand) continue;
            }
            int tl = i / w * vw + i % w;
            int[] v = {tl, tl + 1, tl + vw, tl + vw + 1};
            int[] c = new int[4];
            int lo = SOIL, soil = 0;
            for (int k = 0; k < 4; k++) {
                c[k] = lv[v[k]] < 0 ? DARK : lv[v[k]];
                lo = Math.min(lo, c[k]);
                if (c[k] == SOIL) soil = kind[v[k]];
            }
            // the pair this cell shows: the lowest level and the one above it (soil only borders light grass)
            int other = lo == WATER ? WATER : lo == DARK ? DARK : SOIL;
            int mask = 0;
            for (int k = 0; k < 4; k++) if (c[k] == other) mask |= 1 << k;
            int[][] table = lo == WATER ? theme.coast : lo == DARK ? theme.darkEdge : theme.soilEdge[soil];
            int id;
            if (mask == 15) id = pick(other == WATER ? theme.water : other == DARK ? theme.dark : theme.soil[soil]);
            else if (table[mask] != null) id = pick(table[mask]);
            else id = pick(lo == LIGHT ? theme.light : theme.dark);      // no matching corner or a diagonal
            m.tiles[i] = id;
        }
    }

    /** Corner levels {TL, TR, BL, BR, soil kind} of a terrain tile, or null for tiles without a known pattern. */
    private int[] cornersOf(int tile) {
        if (has(theme.water, tile)) return new int[]{WATER, WATER, WATER, WATER, 0};
        if (has(theme.dark, tile)) return new int[]{DARK, DARK, DARK, DARK, 0};
        if (has(theme.light, tile)) return new int[]{LIGHT, LIGHT, LIGHT, LIGHT, 0};
        for (int k = 0; k < theme.soil.length; k++)
            if (has(theme.soil[k], tile)) return new int[]{SOIL, SOIL, SOIL, SOIL, k};
        for (int mask = 1; mask < 15; mask++) {
            if (theme.coast[mask] != null && has(theme.coast[mask], tile)) return corners(mask, WATER, DARK, 0);
            if (theme.darkEdge[mask] != null && has(theme.darkEdge[mask], tile)) return corners(mask, DARK, LIGHT, 0);
            for (int k = 0; k < theme.soil.length; k++)
                if (theme.soilEdge[k][mask] != null && has(theme.soilEdge[k][mask], tile))
                    return corners(mask, SOIL, LIGHT, k);
        }
        return null;
    }

    private static int[] corners(int mask, int inside, int outside, int soil) {
        int[] c = {outside, outside, outside, outside, soil};
        for (int k = 0; k < 4; k++) if ((mask & (1 << k)) != 0) c[k] = inside;
        return c;
    }

    private static int[] concat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] out = new int[n];
        int k = 0;
        for (int[] p : parts) { System.arraycopy(p, 0, out, k, p.length); k += p.length; }
        return out;
    }

    /** Covers the masked cells with non-overlapping objects, larger ones first; returns the covered cells. */
    private boolean[] fill(boolean[] mask, boolean[] used, int[] types) {
        boolean[] done = new boolean[w * h];
        for (int minArea = 6; minArea >= 1; minArea = minArea == 6 ? 4 : minArea == 4 ? 2 : minArea == 2 ? 1 : 0) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!mask[y * w + x] || used[y * w + x]) continue;
                    List<Integer> fits = new ArrayList<Integer>();
                    for (int t : types) {
                        int[] f = pass.footprint(m.tileset, t);
                        if (f[0] * f[1] < minArea || x + f[0] > w || y + f[1] > h) continue;
                        boolean ok = true;
                        for (int yy = y; ok && yy < y + f[1]; yy++)
                            for (int xx = x; ok && xx < x + f[0]; xx++)
                                ok = mask[yy * w + xx] && !used[yy * w + xx];
                        if (ok) fits.add(t);
                    }
                    if (fits.isEmpty()) continue;
                    int t = fits.get(rnd.nextInt(fits.size()));
                    int[] f = pass.footprint(m.tileset, t);
                    m.addObject(t, x, y);
                    mark(used, x, y, f[0], f[1], 0);
                    mark(done, x, y, f[0], f[1], 0);
                }
            }
        }
        return done;
    }

    /** Paints forest / rock ground under cells that are surrounded by covered cells on all sides. */
    private void ground(boolean[] covered, int[] ids) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inner = true;
                for (int dy = -1; inner && dy <= 1; dy++)
                    for (int dx = -1; inner && dx <= 1; dx++)
                        inner = !in(x + dx, y + dy) || covered[(y + dy) * w + x + dx];
                if (inner) m.set(x, y, pick(ids));
            }
        }
    }

    /**
     * Removes objects (index {@code firstRemovable} and later) until every previous connection exists again.
     * {@code under} holds the tiles from before the forest / rock ground was painted.
     */
    private int reconnectByRemovingObjects(int firstRemovable, int[] under) {
        int opened = 0;
        for (int round = 0; round < keys.size() + 1; round++) {
            boolean[] blk = blocked();
            int[] link = brokenLink(blk);
            if (link == null) break;
            boolean[] removable = new boolean[w * h];
            for (int i = firstRemovable; i < m.objects.size(); i++) {
                int[] ob = m.objects.get(i);
                int[] f = pass.footprint(m.tileset, ob[0]);
                mark(removable, ob[1], ob[2], f[0], f[1], 0);
            }
            int[] cost = new int[w * h];
            for (int i = 0; i < w * h; i++) cost[i] = !blk[i] ? 1 : removable[i] ? 8 : -1;
            int from = snap(blk, keys.get(link[0])[0], keys.get(link[0])[1], 6);
            int to = snap(blk, keys.get(link[1])[0], keys.get(link[1])[1], 6);
            if (from < 0) from = keyCell(keys.get(link[0]));
            if (to < 0) to = keyCell(keys.get(link[1]));
            cost[from] = Math.max(cost[from], 1);
            cost[to] = Math.max(cost[to], 1);
            List<Integer> path = route(cost, from, to);
            if (path == null) break;
            boolean[] road = new boolean[w * h];
            for (int i : path) road[i] = true;
            road = dilate(road, 1);
            for (int i = m.objects.size() - 1; i >= firstRemovable; i--) {
                if (overlaps(road, m.objects.get(i))) { m.objects.remove(i); opened++; }
            }
            if (under != null)
                for (int i = 0; i < w * h; i++) if (road[i]) m.tiles[i] = under[i];
        }
        return opened;
    }

    // ------------------------------------------------------------------ buildings

    /** {kind, weight, max per base (0 = no limit)} */
    private static final int[][] ENEMY_MIX = {{8, 3, 1}, {9, 24, 0}, {10, 9, 4}, {11, 12, 6}, {12, 4, 1}, {13, 22, 0},
            {14, 26, 0}};
    private static final int[][] PLAYER_MIX = {{0, 3, 1}, {1, 30, 0}, {2, 10, 3}, {3, 10, 3}, {4, 4, 1}, {5, 22, 0},
            {6, 4, 1}, {7, 17, 0}};
    private static final int[] ENEMY_MUST = {10, 11, 9, 8, 14, 13};
    private static final int[] PLAYER_MUST = {0, 1, 2, 3};

    /** A base to fill: AI group, area {x, y, w, h}, number of buildings wanted. */
    private static final class Base {
        int group, count, share;
        int[] area;
    }

    private int[] clip(int x0, int y0, int x1, int y1) {
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(w, x1); y1 = Math.min(h, y1);
        return new int[]{x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)};
    }

    /** Bounding box {x0, y0, x1, y1} of the buildings of a side (and group, -1 = any); null if none. */
    private int[] bounds(List<int[]> list, int side, int group) {
        int[] r = null;
        for (int[] b : list) {
            if (AowMap.isWall(b[0]) || AowMap.owner(b[0]) != side || (group >= 0 && b[3] != group)) continue;
            int[] f = AowMap.footprint(b[0]);
            if (r == null) r = new int[]{b[1], b[2], b[1] + f[0], b[2] + f[1]};
            r[0] = Math.min(r[0], b[1]); r[1] = Math.min(r[1], b[2]);
            r[2] = Math.max(r[2], b[1] + f[0]); r[3] = Math.max(r[3], b[2] + f[1]);
        }
        return r;
    }

    /** Cells where a side's opponent is: its buildings, else its scripted unit spawns. */
    private List<int[]> presence(List<int[]> list, int side) {
        List<int[]> out = new ArrayList<int[]>();
        for (int[] b : list) if (!AowMap.isWall(b[0]) && AowMap.owner(b[0]) == side) out.add(new int[]{b[1], b[2]});
        if (out.isEmpty())
            for (int[] e : m.script())
                if (e[0] == 27 && (e[5] >= 3) == (side == 0) && in(e[1], e[2])) out.add(new int[]{e[1], e[2]});
        return out;
    }

    private static int[] centre(List<int[]> pts, int dx, int dy) {
        if (pts.isEmpty()) return new int[]{dx, dy};
        long x = 0, y = 0;
        for (int[] p : pts) { x += p[0]; y += p[1]; }
        return new int[]{(int) (x / pts.size()), (int) (y / pts.size())};
    }

    private List<Base> planBases(List<int[]> old) {
        List<Base> out = new ArrayList<Base>();
        int side = o.side;
        if (o.area != null) {
            Base b = new Base();
            b.area = clip(o.area[0], o.area[1], o.area[0] + o.area[2], o.area[1] + o.area[3]);
            int cx = b.area[0] + b.area[2] / 2, cy = b.area[1] + b.area[3] / 2, bd = Integer.MAX_VALUE;
            for (int[] x : old) {
                if (AowMap.isWall(x[0]) || AowMap.owner(x[0]) != side) continue;
                int d = (x[1] - cx) * (x[1] - cx) + (x[2] - cy) * (x[2] - cy);
                if (d < bd) { bd = d; b.group = x[3]; }
            }
            b.share = 1;
            out.add(b);
            return out;
        }
        if (side == 1) {
            List<int[]> zones = MapScript.baseZones(m.rest);
            for (int g = 0; g < 3; g++) {
                int[] bb = bounds(old, 1, g);
                int[] zone = null;
                for (int[] z : zones) if (z[0] == g) { zone = z; break; }
                if (bb == null && (zone == null || bounds(old, 1, -1) != null)) continue;
                Base b = new Base();
                b.group = g;
                if (bb == null) bb = new int[]{zone[1], zone[2], zone[1] + zone[3], zone[2] + zone[4]};
                else if (zone != null) bb = new int[]{Math.min(bb[0], zone[1]), Math.min(bb[1], zone[2]),
                        Math.max(bb[2], zone[1] + zone[3]), Math.max(bb[3], zone[2] + zone[4])};
                b.area = clip(bb[0] - 2, bb[1] - 2, bb[2] + 2, bb[3] + 2);
                for (int[] x : old) if (!AowMap.isWall(x[0]) && AowMap.owner(x[0]) == 1 && x[3] == g) b.share++;
                if (b.share == 0) b.share = 1;
                out.add(b);
            }
        } else {
            int[] bb = bounds(old, 0, -1);
            Base b = new Base();
            if (bb == null) {
                int[] c = centre(presence(old, 0), w / 2, h / 2);
                bb = new int[]{c[0] - 6, c[1] - 6, c[0] + 6, c[1] + 6};
            }
            b.area = clip(bb[0] - 6, bb[1] - 6, bb[2] + 6, bb[3] + 6);
            b.share = 1;
            out.add(b);
        }
        if (out.isEmpty()) {
            // no hint at all: take the corner of the map that is farthest from the opponent
            int[] foe = centre(presence(old, 1 - side), w / 2, h / 2);
            int aw = Math.min(w, 22), ah = Math.min(h, 22);
            Base b = new Base();
            b.area = new int[]{foe[0] < w / 2 ? w - aw : 0, foe[1] < h / 2 ? h - ah : 0, aw, ah};
            b.share = 1;
            out.add(b);
        }
        return out;
    }

    /** @param hqLeft one element: how many more headquarters (kind 0 / 8) may be built on the whole side */
    private List<Integer> composition(Base base, int[][] mix, int[] must, int[] hqLeft) {
        List<Integer> kinds = new ArrayList<Integer>();
        int[] have = new int[AowMap.KINDS];
        for (int[] b : m.buildings) if (!AowMap.isWall(b[0]) && b[3] == base.group) have[b[0]]++;
        for (int k : must) {
            if (kinds.size() >= base.count) break;
            if (isHq(k) && hqLeft[0] <= 0) continue;
            if (have[k] == 0) { kinds.add(k); have[k]++; if (isHq(k)) hqLeft[0]--; }
        }
        while (kinds.size() < base.count) {
            int total = 0;
            for (int[] e : mix) if (allowed(e, have, hqLeft)) total += e[1];
            int r = rnd.nextInt(total);
            for (int[] e : mix) {
                if (!allowed(e, have, hqLeft)) continue;
                r -= e[1];
                if (r < 0) { kinds.add(e[0]); have[e[0]]++; if (isHq(e[0])) hqLeft[0]--; break; }
            }
        }
        return kinds;
    }

    private static boolean isHq(int kind) { return kind == 0 || kind == 8; }

    private static boolean allowed(int[] entry, int[] have, int[] hqLeft) {
        return (entry[2] == 0 || have[entry[0]] < entry[2]) && (!isHq(entry[0]) || hqLeft[0] > 0);
    }

    private void buildings() {
        List<int[]> old = new ArrayList<int[]>();
        for (int[] b : m.buildings) old.add(b.clone());
        int side = o.side;
        int removed = 0;
        if (o.replaceBuildings) {
            for (int i = m.buildings.size() - 1; i >= 0; i--) {
                int[] b = m.buildings.get(i);
                if (!AowMap.isWall(b[0]) && AowMap.owner(b[0]) == side) { m.buildings.remove(i); removed++; }
            }
        }
        rebaseKeys();
        int room = Math.min(AowMap.MAX_PER_OWNER - m.countOwner(side), 255 - m.buildings.size());
        int total = Math.max(0, Math.min(o.buildingCount, room));
        if (total == 0) {
            if (o.replaceBuildings) throw new IllegalStateException("Jumlah bangunan harus minimal 1");
            report.add("Bangunan: sudah mencapai batas " + AowMap.MAX_PER_OWNER + " per pihak, tidak ada yang ditambah");
            return;
        }
        List<Base> bases = planBases(old);
        int shares = 0;
        for (Base b : bases) shares += b.share;
        int given = 0;
        for (Base b : bases) { b.count = total * b.share / shares; given += b.count; }
        for (int i = 0; given < total; i = (i + 1) % bases.size()) { bases.get(i).count++; given++; }

        int[] foe = centre(presence(old, 1 - side), w / 2, h / 2);
        int placed = 0, cleared = 0;
        int hqBefore = 0, hqKept = 0;
        for (int[] b : old) if (isHq(b[0]) && AowMap.owner(b[0]) == side) hqBefore++;
        for (int[] b : m.buildings) if (isHq(b[0]) && AowMap.owner(b[0]) == side) hqKept++;
        int[] hqLeft = {Math.max(1, hqBefore) - hqKept};
        for (Base base : bases) {
            List<Integer> kinds = composition(base, side == 1 ? ENEMY_MIX : PLAYER_MIX,
                    side == 1 ? ENEMY_MUST : PLAYER_MUST, hqLeft);
            // big buildings first while there is still room, but never drop the essential ones
            Collections.sort(kinds, new Comparator<Integer>() {
                public int compare(Integer p, Integer q) {
                    int[] a = AowMap.footprint(p), b = AowMap.footprint(q);
                    return b[0] * b[1] - a[0] * a[1];
                }
            });
            int ref = reference(base.area);
            for (int kind : kinds) {
                int r = -1;
                for (int grow = 0; r < 0 && grow <= 12; grow += 4) {
                    int[] ar = clip(base.area[0] - grow, base.area[1] - grow, base.area[0] + base.area[2] + grow,
                            base.area[1] + base.area[3] + grow);
                    if (grow > 0 && o.area != null) break;      // stay inside a hand-picked area
                    r = place(kind, base.group, ar, foe, ref);
                }
                if (r >= 0) { placed++; cleared += r; }
            }
        }
        if (placed == 0) throw new IllegalStateException("Tidak ada tempat kosong untuk bangunan di area itu");
        report.add("Bangunan " + (side == 1 ? "musuh" : "pemain") + ": " + (removed > 0 ? removed + " dihapus, " : "")
                + placed + " ditaruh" + (placed < total ? " dari " + total + " (area penuh)" : "")
                + (cleared > 0 ? ", " + cleared + " objek penghalang dibuang" : ""));
    }

    /** An open cell of the largest walkable region inside the area; new buildings must stay reachable from it. */
    private int reference(int[] ar) {
        boolean[] blk = blocked();
        addBuildingCells(blk);
        int[] comp = components(blk);
        int[] size = new int[w * h];
        for (int c : comp) if (c >= 0) size[c]++;
        int best = -1;
        long score = Long.MIN_VALUE;
        int cx = ar[0] + ar[2] / 2, cy = ar[1] + ar[3] / 2;
        for (int y = ar[1]; y < ar[1] + ar[3]; y++)
            for (int x = ar[0]; x < ar[0] + ar[2]; x++) {
                int c = comp[y * w + x];
                if (c < 0) continue;
                long s = size[c] * 100000L - ((x - cx) * (x - cx) + (y - cy) * (y - cy));
                if (s > score) { score = s; best = y * w + x; }
            }
        return best;
    }

    private static boolean isDefence(int kind) { return kind == 13 || kind == 14 || kind == 5 || kind == 7; }

    /** Places one building; returns how many objects had to be removed for it, or -1 when there is no spot. */
    private int place(final int kind, int group, int[] ar, int[] foe, int ref) {
        int[] f = AowMap.footprint(kind);
        boolean[] blk = blocked();
        boolean[] taken = new boolean[w * h];        // buildings with a one cell gap, zones, script cells
        for (int[] b : m.buildings) {
            int[] g = AowMap.footprint(b[0]);
            mark(taken, b[1], b[2], g[0], g[1], AowMap.isWall(b[0]) ? 0 : 1);
        }
        boolean[] never = new boolean[w * h];
        for (int[] z : m.wallZones()) mark(never, z[1], z[2], z[3], z[4], 0);
        for (int[] c : scriptCells) mark(never, c[0], c[1], 1, 1, 1);
        if (!o.paths) for (int g = 0; g < 3; g++) for (int[] p : m.attackPath(g)) mark(never, p[0], p[1], 1, 1, 0);
        if (ref >= 0) never[ref] = true;
        boolean[] object = new boolean[w * h];
        for (int[] ob : m.objects) {
            int[] g = pass.footprint(m.tileset, ob[0]);
            mark(object, ob[1], ob[2], g[0], g[1], 0);
        }

        List<double[]> spots = new ArrayList<double[]>();
        for (int y = ar[1]; y + f[1] <= ar[1] + ar[3]; y++) {
            for (int x = ar[0]; x + f[0] <= ar[0] + ar[2]; x++) {
                boolean ok = true;
                int onObject = 0;
                for (int yy = y; ok && yy < y + f[1]; yy++) {
                    for (int xx = x; ok && xx < x + f[0]; xx++) {
                        int i = yy * w + xx;
                        // blocked ground is only acceptable when an object (which can be removed) causes it
                        ok = !taken[i] && !never[i] && (!blk[i] || (object[i] && !pass.tileBlocked(m.tileset, m.tiles[i])));
                        if (object[i]) onObject++;
                    }
                }
                // factories need a free exit cell below their right corner (the default rally point)
                if (ok && (kind == 10 || kind == 11 || kind == 2 || kind == 3)) {
                    int ex = x + f[0] - 1, ey = y + f[1];
                    ok = in(ex, ey) && !blk[ey * w + ex] && !taken[ey * w + ex];
                }
                if (!ok) continue;
                double toFoe = Math.sqrt((x - foe[0]) * (x - foe[0]) + (y - foe[1]) * (y - foe[1]));
                int near = 40;
                for (int[] b : m.buildings) {
                    if (AowMap.owner(b[0]) != AowMap.owner(kind)) continue;
                    near = Math.min(near, Math.max(Math.abs(b[1] - x), Math.abs(b[2] - y)));
                }
                double score = rnd.nextDouble() * 9
                        + (isDefence(kind) ? -toFoe * 0.7 : toFoe * 0.3)     // turrets in front, the rest behind
                        - Math.max(0, near - 5) * 0.4 - onObject * 1.5;
                spots.add(new double[]{score, x, y});
            }
        }
        Collections.sort(spots, new Comparator<double[]>() {
            public int compare(double[] p, double[] q) { return Double.compare(q[0], p[0]); }
        });
        for (int n = 0; n < Math.min(spots.size(), 40); n++) {
            int x = (int) spots.get(n)[1], y = (int) spots.get(n)[2];
            if (m.checkBuilding(kind, x, y, -1) != null) continue;
            List<int[]> before = new ArrayList<int[]>(m.objects);
            boolean[] foot = new boolean[w * h];
            mark(foot, x, y, f[0], f[1], 0);
            for (int i = m.objects.size() - 1; i >= 0; i--) if (overlaps(foot, m.objects.get(i))) m.objects.remove(i);
            m.buildings.add(new int[]{kind, x, y, group});
            boolean[] now = blocked();
            boolean groundOk = true;
            for (int i = 0; i < w * h; i++) if (foot[i] && now[i]) groundOk = false;
            addBuildingCells(now);
            if (groundOk && brokenLink(now) == null && allReachable(now, ref, o.side)) return before.size() - m.objects.size();
            m.buildings.remove(m.buildings.size() - 1);
            m.objects.clear();
            m.objects.addAll(before);
        }
        return -1;
    }

    /** True when each building of the side that is near the reference cell's base can be approached from it. */
    private boolean allReachable(boolean[] blk, int ref, int side) {
        if (ref < 0) return true;
        int[] comp = components(blk);
        int c = comp[ref];
        if (c < 0) return false;
        int[] b = m.buildings.get(m.buildings.size() - 1);     // the newest one; earlier ones were checked already
        int[] f = AowMap.footprint(b[0]);
        boolean touches = false;
        for (int y = b[2] - 1; y <= b[2] + f[1]; y++)
            for (int x = b[1] - 1; x <= b[1] + f[0]; x++)
                if (in(x, y) && comp[y * w + x] == c) touches = true;
        if (!touches) return false;
        // the newest building must not wall in an older one of the same base
        for (int k = 0; k < m.buildings.size() - 1; k++) {
            int[] a = m.buildings.get(k);
            if (AowMap.isWall(a[0]) || AowMap.owner(a[0]) != side || a[3] != b[3]) continue;
            if (Math.abs(a[1] - b[1]) > 8 || Math.abs(a[2] - b[2]) > 8) continue;
            int[] g = AowMap.footprint(a[0]);
            boolean ok = false;
            for (int y = a[2] - 1; y <= a[2] + g[1]; y++)
                for (int x = a[1] - 1; x <= a[1] + g[0]; x++)
                    if (in(x, y) && comp[y * w + x] == c) ok = true;
            if (!ok) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ rally points ("jalur serang")

    private void rallyPoints() {
        if (!m.scriptEditable()) throw new IllegalStateException("Data jalur serang peta ini tidak dikenali");
        boolean[] blk = blocked();
        addBuildingCells(blk);
        List<Integer> targets = new ArrayList<Integer>();
        for (int[] t : presence(m.buildings, 0)) {
            int c = snap(blk, t[0], t[1], 5);
            if (c >= 0) targets.add(c);
        }
        int[] toPlayer = distances(blk, targets);
        int want = Math.max(1, Math.min(AowMap.MAX_PATH_POINTS, o.pathPoints));
        int[][] bands = {{2, 7}, {6, 15}, {14, 30}};
        int[] band = bands[Math.max(0, Math.min(2, o.pathDistance))];
        boolean[] used = new boolean[w * h];
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 3; g++) {
            List<Integer> exits = new ArrayList<Integer>();
            for (int[] b : m.buildings) {
                if ((b[0] != 10 && b[0] != 11) || b[3] != g) continue;
                int[] f = AowMap.footprint(b[0]);
                int c = snap(blk, b[1] + f[0] - 1, b[2] + f[1], 3);
                if (c >= 0) exits.add(c);
            }
            if (exits.isEmpty()) continue;
            int[] fromBase = distances(blk, exits);
            int baseToPlayer = Integer.MAX_VALUE;
            for (int c : exits) if (toPlayer[c] >= 0) baseToPlayer = Math.min(baseToPlayer, toPlayer[c]);
            List<double[]> spots = new ArrayList<double[]>();
            for (int pass2 = 0; spots.size() < want && pass2 < 3; pass2++) {
                spots.clear();
                for (int i = 0; i < w * h; i++) {
                    int d = fromBase[i];
                    if (d < 0 || used[i]) continue;
                    if (pass2 == 0 && (d < band[0] || d > band[1])) continue;
                    if (pass2 == 1 && d > band[1] + 10) continue;
                    int x = i % w, y = i / w, open = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++)
                            if (in(x + dx, y + dy) && !blk[(y + dy) * w + x + dx]) open++;
                    if (pass2 < 2 && open < 8) continue;       // units need some room to gather
                    double score = rnd.nextDouble() * 6;
                    if (toPlayer[i] >= 0 && baseToPlayer != Integer.MAX_VALUE) score += (baseToPlayer - toPlayer[i]) * 0.8;
                    spots.add(new double[]{score, i});
                }
            }
            Collections.sort(spots, new Comparator<double[]>() {
                public int compare(double[] p, double[] q) { return Double.compare(q[0], p[0]); }
            });
            List<int[]> pts = new ArrayList<int[]>();
            for (int gap = 5; pts.size() < want && gap >= 1; gap -= 2) {
                for (double[] s : spots) {
                    if (pts.size() >= want) break;
                    int x = (int) s[1] % w, y = (int) s[1] / w;
                    boolean far = true;
                    for (int[] p : pts) if (Math.max(Math.abs(p[0] - x), Math.abs(p[1] - y)) < gap) far = false;
                    if (far) { pts.add(new int[]{x, y}); used[y * w + x] = true; }
                }
            }
            if (pts.isEmpty()) continue;
            String err = m.setAttackPath(g, pts);
            if (err != null) throw new IllegalStateException(err);
            sb.append(sb.length() == 0 ? "" : ", ").append("grup ").append(g).append(": ").append(pts.size()).append(" titik");
        }
        report.add(sb.length() > 0 ? "Jalur serang (titik kumpul): " + sb
                : "Jalur serang: tidak diubah, tidak ada Barak/Pabrik musuh yang punya jalan keluar");
    }
}
