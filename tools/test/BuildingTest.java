import aowtool.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Round-trips every stock map and exercises the building editing API. Usage: BuildingTest <unzipped jar dir> */
public class BuildingTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) throws Exception {
        Method enc = AowMap.class.getDeclaredMethod("encode");
        enc.setAccessible(true);
        for (int n = 1; n <= 15; n++) {
            byte[] raw = Files.readAllBytes(Paths.get(a[0], "map" + n));
            AowMap m = AowMap.parse(raw);
            check(m.structured, "map" + n + " structured");
            check(Arrays.equals(m.toBytes(), raw), "map" + n + " untouched -> identical");
            byte[] re = (byte[]) enc.invoke(m);
            AowMap b = AowMap.parse(re);
            check(b.buildings.size() == m.buildings.size(), "map" + n + " building count after re-encode");
            check(Arrays.equals(b.rest, m.rest) && Arrays.equals(b.fog, m.fog), "map" + n + " fog/rest kept");
            for (int i = 0; i < m.buildings.size(); i++)
                check(Arrays.equals(b.buildings.get(i), m.buildings.get(i)), "map" + n + " building " + i);
            // every stock building must fit its own footprint rules
            for (int i = 0; i < m.buildings.size(); i++) {
                int[] bd = m.buildings.get(i);
                String why = m.checkBuilding(bd[0], bd[1], bd[2], i);
                if (why != null) System.out.println("note: map" + n + " stock building " + i + " " + Arrays.toString(bd) + " -> " + why + " (already so in the original game)");
            }
            System.out.printf("map%-2d %3dx%-3d buildings=%2d (player %d / enemy %d) spawns=%d%n", n, m.width, m.height,
                    m.buildings.size(), m.countOwner(0), m.countOwner(1), m.spawns().size());
        }
        // editing: add / move / delete / retype and full re-parse
        byte[] raw = Files.readAllBytes(Paths.get(a[0], "map1"));
        AowMap m = AowMap.parse(raw);
        int before = m.buildings.size();
        String[] err = new String[1];
        int idx = m.addBuilding(1, 10, 20, 2, err);
        check(idx >= 0, "add building: " + err[0]);
        check(m.addBuilding(1, 10, 20, 0, err) < 0, "overlap must be refused");
        check(m.addBuilding(0, 38, 38, 0, err) < 0, "out of map must be refused");
        check(!m.isUnmodified(), "modified detected");
        AowMap r = AowMap.parse(m.toBytes());
        check(r.buildings.size() == before + 1, "re-parsed count");
        check(Arrays.equals(r.buildings.get(before), new int[]{1, 10, 20, 2}), "new building data");
        check(Arrays.equals(r.rest, m.rest), "rest kept after add");
        check(r.buildingAt(11, 21) == before, "buildingAt inside footprint");
        check(r.buildingAt(13, 20) < 0, "buildingAt outside footprint");
        AowMap c = r.copy();
        check(c.buildings.size() == r.buildings.size(), "copy keeps buildings");
        c.buildings.remove(0);
        check(AowMap.parse(c.toBytes()).buildings.size() == r.buildings.size() - 1, "delete");
        // walls (kind 15..29, 1x1) and the player's missile launcher (kind 7)
        AowMap w = AowMap.parse(raw);
        int nb = w.buildings.size();
        check(w.addBuilding(17, 5, 5, 0, err) >= 0, "add player wall: " + err[0]);
        check(w.addBuilding(27, 6, 5, 0, err) >= 0, "add enemy wall: " + err[0]);
        check(w.addBuilding(17, 5, 5, 0, err) < 0, "wall on wall must be refused");
        check(w.addBuilding(7, 10, 30, 0, err) >= 0, "add missile launcher: " + err[0]);
        check(w.addBuilding(35, 1, 1, 0, err) < 0, "kind 35 must be refused");
        check(AowMap.owner(17) == 0 && AowMap.owner(27) == 1 && AowMap.owner(7) == 0 && AowMap.owner(14) == 1, "owner()");
        check(w.countWalls() == 2, "countWalls");
        // walls never use the 50 building slots of a side
        AowMap full = AowMap.parse(raw);
        while (full.countOwner(0) < AowMap.MAX_PER_OWNER) {
            boolean placed = false;
            for (int yy = 0; yy < full.height - 1 && !placed; yy++)
                for (int xx = 0; xx < full.width - 1 && !placed; xx++)
                    placed = full.addBuilding(5, xx, yy, 0, null) >= 0;
            if (!placed) break;
        }
        check(full.countOwner(0) == AowMap.MAX_PER_OWNER, "filled the player slots");
        boolean wallPlaced = false;
        for (int yy = 0; yy < full.height && !wallPlaced; yy++)
            for (int xx = 0; xx < full.width && !wallPlaced; xx++)
                wallPlaced = full.addBuilding(15, xx, yy, 0, null) >= 0;
        check(wallPlaced, "wall allowed although the player's 50 building slots are used");
        AowMap wr = AowMap.parse(w.toBytes());
        check(wr.buildings.size() == nb + 3, "walls survive save/parse");
        check(wr.buildingAt(5, 5) >= 0 && wr.buildings.get(wr.buildingAt(5, 5))[0] == 17, "wall kept at 5,5");
        check(Arrays.equals(wr.rest, w.rest), "rest kept with walls");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
