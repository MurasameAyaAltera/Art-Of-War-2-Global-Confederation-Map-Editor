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
}
