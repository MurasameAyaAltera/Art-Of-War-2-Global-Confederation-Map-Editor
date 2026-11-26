package aowtool;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * What the game treats as "blocked" for ground units.
 *
 * The map file stores, after the object list, one bit per cell (height x ceil(width/32) x 4 bytes, MSB first).
 * The old editor called this block "fog", but the game's loader (e.k()) uses it as the walkability layer:
 * it fills every cell with 124 ("blocked") and then resets the cells whose bit is 0 to 0 ("free").
 * Movement and building placement only accept cells equal to 0, so bit 1 = blocked, bit 0 = passable.
 * Changing a tile id (water -> grass) therefore does NOT make the cell walkable unless this bit is cleared too.
 *
 * Rule measured on the 15 stock maps (about 96 % of cells agree, the rest are hand-tuned blocks):
 *   blocked = tile id is a water/cliff id  OR  the cell lies under the footprint of a decoration object.
 */
public class Passability {
    /** Tile ids that never appear as free ground (outside object footprints) in any stock map, per tileset. */
    private static final int[][] BLOCKED_TILES = {
        {26, 27, 28, 29, 30, 31, 32, 33, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 108, 109, 110,
         111, 112, 115, 119, 137, 138, 139, 140, 141, 142, 147, 149, 154, 155, 156, 157, 220, 222, 223, 236, 237, 240,
         242, 243, 244},
        {33, 34, 35, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 130, 131, 132, 201, 216, 217,
         218, 229, 232, 233, 234, 235, 236, 237, 238, 240, 241}
    };

    private final byte[] gz18, gz19;
    private final short[][] el;
    private final short[][] em;
    private final Set<Integer>[] blocked;

    @SuppressWarnings("unchecked")
    public Passability(byte[] d0, byte[] ml) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(d0));
        byte[] a18 = null, a19 = null;
        for (int i = 0; i < 20; i++) {
            int n = in.readUnsignedShort();
            byte[] b = new byte[n];
            in.readFully(b);
            if (i == 18) a18 = b;
            if (i == 19) a19 = b;
        }
        gz18 = a18; gz19 = a19;
        in = new DataInputStream(new ByteArrayInputStream(ml));
        int ts = in.readUnsignedByte();
        el = new short[ts][];
        em = new short[ts][30];
        blocked = new Set[ts];
        for (int t = 0; t < ts; t++) {
            int n = in.readUnsignedShort();
            el[t] = new short[n];
            for (int k = 0; k < n; k++) el[t][k] = in.readShort();
            for (int k = 0; k < 30; k++) em[t][k] = in.readShort();
            blocked[t] = new HashSet<Integer>();
            if (t < BLOCKED_TILES.length) for (int id : BLOCKED_TILES[t]) blocked[t].add(id);
            for (int id = em[t][4]; id < em[t][5]; id++) blocked[t].add(id);   // the water group
        }
    }

    /** True when ground units cannot enter a cell painted with this tile id. */
    public boolean tileBlocked(int ts, int tile) {
        return ts >= 0 && ts < blocked.length && blocked[ts].contains(tile);
    }

    /** Footprint {w,h} of a decoration object type (same lookup the game's loader uses). */
    public int[] footprint(int ts, int type) {
        if (ts < 0 || ts >= el.length) return new int[]{1, 1};
        int idx = em[ts][14] + type;
        if (idx < 0 || idx >= el[ts].length) return new int[]{1, 1};
        int k = el[ts][idx];
        if (k < 0 || k >= gz18.length || k >= gz19.length) return new int[]{1, 1};
        return new int[]{Math.max(1, gz18[k] & 255), Math.max(1, gz19[k] & 255)};
    }
}
