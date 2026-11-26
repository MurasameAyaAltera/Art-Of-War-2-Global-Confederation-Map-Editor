import aowtool.*;
import java.nio.file.*;
import java.util.*;

/**
 * Runs the random generator on the 15 stock maps with many seeds and checks the results independently.
 * args: [extracted-jar-dir] [seeds]
 */
public class RandomGenTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; if (fails < 60) System.out.println("FAIL: " + what); } }
    static Passability pass;

    static boolean[] blocked(AowMap m, boolean buildings) {
        AowMap c = m.copy();
        c.syncPassability(pass);
        boolean[] b = new boolean[m.width * m.height];
        for (int i = 0; i < b.length; i++) b[i] = c.isBlocked(i % m.width, i / m.width);
        if (buildings) for (int[] bd : m.buildings) {
            int[] f = AowMap.footprint(bd[0]);
            for (int y = bd[2]; y < bd[2] + f[1]; y++) for (int x = bd[1]; x < bd[1] + f[0]; x++) b[y * m.width + x] = true;
        }
        return b;
    }

    static int[] comps(boolean[] b, int w) {
        int[] c = new int[b.length];
        Arrays.fill(c, -1);
        ArrayDeque<Integer> q = new ArrayDeque<Integer>();
        for (int s = 0; s < b.length; s++) {
            if (b[s] || c[s] >= 0) continue;
            q.add(s); c[s] = s;
            while (!q.isEmpty()) {
                int i = q.poll();
                int[] n = {i % w > 0 ? i - 1 : -1, i % w < w - 1 ? i + 1 : -1, i - w, i + w};
                for (int j : n) if (j >= 0 && j < b.length && !b[j] && c[j] < 0) { c[j] = s; q.add(j); }
            }
        }
        return c;
    }

    /** Pairs of scripted spawns that can walk to each other. */
    static Set<String> spawnLinks(AowMap m, boolean buildings) {
        boolean[] b = blocked(m, buildings);
        int[] c = comps(b, m.width);
        Set<String> out = new HashSet<String>();
        List<int[]> sp = m.spawns();
        for (int i = 0; i < sp.size(); i++) for (int j = i + 1; j < sp.size(); j++) {
            int a = sp.get(i)[1] * m.width + sp.get(i)[0], z = sp.get(j)[1] * m.width + sp.get(j)[0];
            if (c[a] >= 0 && c[a] == c[z]) out.add(i + "-" + j);
        }
        return out;
    }

    static void verify(AowMap orig, AowMap m, RandomGen.Options o, String tag) {
        check(spawnLinks(m, o.buildings).containsAll(spawnLinks(orig, false)), tag + " spawns got disconnected");
        boolean[] ground = blocked(m, false);
        for (int[] s : m.spawns()) {
            boolean was = blocked(orig, false)[s[1] * orig.width + s[0]];
            check(was || !ground[s[1] * m.width + s[0]], tag + " spawn blocked at " + s[0] + "," + s[1]);
        }
        check(m.countOwner(0) <= 50 && m.countOwner(1) <= 50, tag + " building limit");
        for (int i = 0; i < m.buildings.size(); i++) {
            int[] b = m.buildings.get(i);
            int[] f = AowMap.footprint(b[0]);
            check(b[1] + f[0] <= m.width && b[2] + f[1] <= m.height, tag + " building outside");
            boolean isNew = !contains(orig.buildings, b);
            if (isNew) check(m.checkBuilding(b[0], b[1], b[2], i) == null, tag + " new building overlaps " + Arrays.toString(b));
            if (isNew || o.terrain || o.objects)
                for (int y = b[2]; y < b[2] + f[1]; y++) for (int x = b[1]; x < b[1] + f[0]; x++) {
                    if (!isNew && blocked(orig, false)[y * m.width + x]) continue;
                    check(!ground[y * m.width + x], tag + " building on blocked ground " + Arrays.toString(b) + " new=" + isNew);
                }
        }
        if (o.paths) for (int g = 0; g < 3; g++) {
            boolean producer = false;
            for (int[] b : m.buildings) if ((b[0] == 10 || b[0] == 11) && b[3] == g) producer = true;
            if (!producer) continue;
            List<int[]> pts = m.attackPath(g);
            check(pts.size() >= 1 && pts.size() <= 4, tag + " path count " + pts.size());
            boolean[] all = blocked(m, true);
            for (int[] p : pts) check(!all[p[1] * m.width + p[0]], tag + " rally point blocked " + p[0] + "," + p[1]);
        }
        AowMap r = AowMap.parse(withSync(m));
        check(Arrays.equals(r.tiles, m.tiles) && r.objects.size() == m.objects.size()
                && r.buildings.size() == m.buildings.size(), tag + " reparse");
        check(r.script().size() == orig.script().size(), tag + " script changed");
    }

    static byte[] withSync(AowMap m) { AowMap c = m.copy(); c.syncPassability(pass); return c.toBytes(); }

    static boolean contains(List<int[]> l, int[] b) { for (int[] x : l) if (Arrays.equals(x, b)) return true; return false; }

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "jar";
        int seeds = a.length > 1 ? Integer.parseInt(a[1]) : 5;
        pass = new Passability(Files.readAllBytes(Paths.get(dir, "d0")), Files.readAllBytes(Paths.get(dir, "ml")));
        int runs = 0, refused = 0, wanted = 0, got = 0;
        long worst = 0;
        for (int i = 1; i <= 15; i++) {
            byte[] raw = Files.readAllBytes(Paths.get(dir, "map" + i));
            for (int s = 0; s < seeds; s++) {
                for (int combo = 0; combo < 9; combo++) {
                    AowMap orig = AowMap.parse(raw), m = AowMap.parse(raw);
                    RandomGen.Options o = new RandomGen.Options();
                    o.seed = s * 1000 + i * 31 + combo;
                    Random r = new Random(o.seed);
                    o.terrain = combo == 0 || combo == 4 || combo == 5;
                    o.objects = combo == 1 || combo == 4 || combo == 6;
                    o.buildings = combo == 2 || combo == 4 || combo >= 7;
                    o.paths = combo == 3 || combo == 4;
                    o.water = r.nextInt(41); o.dirt = r.nextInt(41); o.forest = r.nextInt(41); o.rock = r.nextInt(21);
                    o.replaceObjects = combo != 6;
                    o.buildingCount = 5 + r.nextInt(46);
                    o.pathPoints = 1 + r.nextInt(4); o.pathDistance = r.nextInt(3);
                    if (combo == 7) { o.side = 0; o.replaceBuildings = false; o.buildingCount = 6; }
                    if (combo == 8) o.area = new int[]{r.nextInt(orig.width - 20), r.nextInt(orig.height - 20), 20, 20};
                    String tag = "map" + i + " seed " + o.seed + " combo " + combo;
                    long t = System.currentTimeMillis();
                    try {
                        RandomGen.generate(m, pass, o);
                    } catch (IllegalStateException e) {
                        refused++;
                        check(m.isUnmodified(), tag + " refused but modified");
                        System.out.println("refused " + tag + ": " + e.getMessage());
                        continue;
                    } catch (RuntimeException e) {
                        check(false, tag + " " + e);
                        e.printStackTrace();
                        continue;
                    }
                    worst = Math.max(worst, System.currentTimeMillis() - t);
                    runs++;
                    if (combo == 2 || combo == 4) {
                        wanted += o.buildingCount;
                        int n = 0;
                        for (int[] b : m.buildings) if (!AowMap.isWall(b[0]) && AowMap.owner(b[0]) == 1) n++;
                        got += n;
                    }
                    verify(orig, m, o, tag);
                    if (s == 0) {
                        AowMap again = AowMap.parse(raw);
                        RandomGen.generate(again, pass, o);
                        check(Arrays.equals(withSync(again), withSync(m)), tag + " not deterministic");
                    }
                }
            }
        }
        System.out.println("runs " + runs + ", refused " + refused + ", buildings placed " + got + "/" + wanted + ", slowest " + worst + " ms");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
