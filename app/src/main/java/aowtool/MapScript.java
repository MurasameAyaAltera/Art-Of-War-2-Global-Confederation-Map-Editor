package aowtool;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only look into the part of a map that follows the building list ("rest").
 * Layout (verified on all 15 stock maps, and mirrored from the game's loader e.a(int)):
 *   hC  2 x 3 lists   { u8 n; n x 4 bytes }          AI zones
 *   hD  3 lists       { u8 n; n x 4 bytes }
 *   hE  3 lists       { u8 n; n x 4 bytes }
 *   3 x               { u8 f; u8 n; n bytes; f+1 bytes }   attack-wave unit tables
 *   iJ  3 lists      { u8 n; n x 2 bytes }
 *   hH  3 lists      { u8 n; n x 4 bytes }
 *   u8 count, count x 6 bytes   mission script {op, x, y, a, b, group}
 * Script op 27 spawns a wave of units at (x,y); a = composition code, b = delay, group >= 3 = player side.
 */
public final class MapScript {
    private MapScript() {}

    /** Spawn commands as {x, y, code, delay, group}; empty when the data does not parse. */
    public static List<int[]> spawns(byte[] r) {
        List<int[]> out = new ArrayList<int[]>();
        try {
            int p = 0;
            for (int i = 0; i < 6; i++) { int c = r[p++] & 255; p += 4 * c; }      // hC
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hD
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hE
            for (int i = 0; i < 3; i++) {
                int f = r[p++] & 255, c = r[p++] & 255;
                p += c + f + 1;
            }
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 2 * c; }      // iJ
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hH
            int n = r[p++] & 255;
            if (p + 6 * n > r.length) return out;
            for (int i = 0; i < n; i++) {
                if ((r[p] & 255) == 27) {
                    out.add(new int[]{r[p + 1] & 255, r[p + 2] & 255, r[p + 3] & 255, r[p + 4] & 255, r[p + 5] & 255});
                }
                p += 6;
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /**
     * Wall zones (the game's hE lists): rectangles {group, x, y, w, h} that the game fills with enemy walls
     * (cell value 1101 = enemy wall level 0) on cells that are still free. They are NOT in the building list, which
     * is why walls that exist in the game were invisible in the editor. Empty when the data does not parse.
     */
    public static List<int[]> wallRects(byte[] r) {
        List<int[]> out = new ArrayList<int[]>();
        try {
            int p = 0;
            for (int i = 0; i < 6; i++) { int c = r[p++] & 255; p += 4 * c; }      // hC
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hD
            for (int i = 0; i < 3; i++) {                                          // hE
                int c = r[p++] & 255;
                if (p + 4 * c > r.length) { out.clear(); return out; }
                for (int k = 0; k < c; k++) {
                    out.add(new int[]{i, r[p] & 255, r[p + 1] & 255, r[p + 2] & 255, r[p + 3] & 255});
                    p += 4;
                }
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /**
     * AI base rectangles {group, x, y, w, h}: the first three of the game's hC lists. Empty when the data does not parse.
     */
    public static List<int[]> baseZones(byte[] r) {
        List<int[]> out = new ArrayList<int[]>();
        try {
            int p = 0;
            for (int i = 0; i < 3; i++) {
                int c = r[p++] & 255;
                for (int k = 0; k < c; k++) {
                    out.add(new int[]{i, r[p] & 255, r[p + 1] & 255, r[p + 2] & 255, r[p + 3] & 255});
                    p += 4;
                }
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    // ------------------------------------------------------------------ attack paths + script (read AND write)

    /** Max waypoints per group: the game keeps them in byte[3][10] (count at [8]), so only 4 points fit. */
    public static final int MAX_PATH_POINTS = 4;
    /** Max script events: the game keeps them in byte[6][100]. */
    public static final int MAX_EVENTS = 100;

    /**
     * Offsets inside "rest": {start of the iJ lists (attack path), start of the hH lists, position of the script
     * event count, first byte after the script}. Null when the data does not parse.
     */
    static int[] layout(byte[] r) {
        try {
            int p = 0;
            for (int i = 0; i < 6; i++) { int c = r[p++] & 255; p += 4 * c; }      // hC
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hD
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hE
            for (int i = 0; i < 3; i++) {
                int f = r[p++] & 255, c = r[p++] & 255;
                p += c + f + 1;
            }
            int iJ = p;
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 2 * c; }      // iJ
            int hH = p;
            for (int i = 0; i < 3; i++) { int c = r[p++] & 255; p += 4 * c; }      // hH
            int sc = p;
            int n = r[p++] & 255;
            p += 6 * n;
            if (p > r.length) return null;
            return new int[]{iJ, hH, sc, p};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when attack paths and script can be read and rewritten. */
    public static boolean editable(byte[] r) { return layout(r) != null; }

    /** The 3 attack-path lists (one per AI group 0..2), each point {x, y}. Empty lists when unreadable. */
    public static List<List<int[]>> attackPaths(byte[] r) {
        List<List<int[]>> out = new ArrayList<List<int[]>>();
        for (int g = 0; g < 3; g++) out.add(new ArrayList<int[]>());
        int[] L = layout(r);
        if (L == null) return out;
        int p = L[0];
        for (int g = 0; g < 3; g++) {
            int c = r[p++] & 255;
            for (int k = 0; k < c; k++) { out.get(g).add(new int[]{r[p] & 255, r[p + 1] & 255}); p += 2; }
        }
        return out;
    }

    /** Returns a copy of {@code r} whose attack paths are {@code paths} (3 lists of {x,y}); r itself if unreadable. */
    public static byte[] withAttackPaths(byte[] r, List<List<int[]>> paths) {
        int[] L = layout(r);
        if (L == null || paths.size() != 3) return r;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(r, 0, L[0]);
        for (int g = 0; g < 3; g++) {
            List<int[]> pts = paths.get(g);
            int n = Math.min(pts.size(), MAX_PATH_POINTS);
            o.write(n);
            for (int k = 0; k < n; k++) { o.write(pts.get(k)[0]); o.write(pts.get(k)[1]); }
        }
        o.write(r, L[1], r.length - L[1]);
        return o.toByteArray();
    }

    /** All script events {op, x, y, a, b, group} in execution order. Empty when unreadable. */
    public static List<int[]> events(byte[] r) {
        List<int[]> out = new ArrayList<int[]>();
        int[] L = layout(r);
        if (L == null) return out;
        int p = L[2];
        int n = r[p++] & 255;
        for (int i = 0; i < n; i++) {
            out.add(new int[]{r[p] & 255, r[p + 1] & 255, r[p + 2] & 255, r[p + 3] & 255, r[p + 4] & 255, r[p + 5] & 255});
            p += 6;
        }
        return out;
    }

    /** Returns a copy of {@code r} with the script replaced by {@code ev}; r itself if unreadable. */
    public static byte[] withEvents(byte[] r, List<int[]> ev) {
        int[] L = layout(r);
        if (L == null) return r;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(r, 0, L[2]);
        int n = Math.min(ev.size(), MAX_EVENTS);
        o.write(n);
        for (int i = 0; i < n; i++) for (int k = 0; k < 6; k++) o.write(ev.get(i)[k]);
        o.write(r, L[3], r.length - L[3]);
        return o.toByteArray();
    }

    /**
     * Name of a script op for the editor. The game runs the script strictly in order: an event fires once
     * {@code b * 10} ticks have passed since the previous one (see h.A()). Only ops whose behaviour was read from the
     * game code get a description; the others are shown by number.
     */
    public static String opName(int op) {
        switch (op) {
            case 20: return "AI: atur unit tier (a = tier*30 + unit)";
            case 21: return "AI: atur mode grup (a)";
            case 22: return "Label loop";
            case 23: return "Ulang ke label (a kali, 255 = terus)";
            case 24: return "Awal: posisi kamera (x,y) + mode a";
            case 27: return "Spawn unit di (x,y): a = kode, grup >= 3 = pemain";
            default: return "Op " + op + " (arti belum dipastikan)";
        }
    }
}
