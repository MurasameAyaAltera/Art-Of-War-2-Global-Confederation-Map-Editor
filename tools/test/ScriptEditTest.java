import aowtool.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Checks attack-path / script editing on the 15 stock maps: no-op edits are byte-identical, real edits re-parse. */
public class ScriptEditTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "jar";
        for (int i = 1; i <= 15; i++) {
            byte[] raw = Files.readAllBytes(Paths.get(dir, "map" + i));
            AowMap m = AowMap.parse(raw);
            check(m.roundTrip() <= 1, "map" + i + " round trip"); if (m.roundTrip() == 1) System.out.println("note: map" + i + " re-encodes to different bytes with same content");
            check(m.scriptEditable(), "map" + i + " editable");
            for (int g = 0; g < 3; g++) check(m.setAttackPath(g, m.attackPath(g)) == null, "map" + i + " set same path");
            check(m.setScript(m.script()) == null, "map" + i + " set same script");
            check(m.isUnmodified(), "map" + i + " untouched after same-value edits");
            check(Arrays.equals(m.toBytes(), raw), "map" + i + " bytes");

            int tail0 = m.rest.length;
            int nev = m.script().size();
            List<int[]> p = m.attackPath(0);
            int np = p.size();
            if (np < AowMap.MAX_PATH_POINTS) {
                p.add(new int[]{m.width - 1, m.height - 1});
                check(m.setAttackPath(0, p) == null, "map" + i + " add point");
            }
            List<int[]> ev = m.script();
            ev.add(new int[]{27, 3, 4, 32, 5, 3});
            check(m.setScript(ev) == null, "map" + i + " add event");
            AowMap r = AowMap.parse(m.toBytes());
            check(r.attackPath(0).size() == Math.min(np + 1, AowMap.MAX_PATH_POINTS), "map" + i + " path count after reparse");
            check(r.script().size() == nev + 1, "map" + i + " script count after reparse");
            int[] last = r.script().get(nev);
            check(Arrays.equals(last, new int[]{27, 3, 4, 32, 5, 3}), "map" + i + " last event");
            check(r.rest.length == tail0 + (np < 4 ? 2 : 0) + 6, "map" + i + " rest length (tail kept)");
            // groups 1/2 and the hH block untouched
            for (int g = 1; g < 3; g++) {
                check(equalsPts(r.attackPath(g), AowMap.parse(raw).attackPath(g)), "map" + i + " group " + g + " untouched");
            }
            // limits
            List<int[]> big = new ArrayList<int[]>();
            for (int k = 0; k < 5; k++) big.add(new int[]{1, 1});
            check(m.setAttackPath(1, big) != null, "5 points refused");
            check(m.setAttackPath(0, Arrays.asList(new int[]{m.width, 0})) != null, "outside refused");
            List<int[]> many = new ArrayList<int[]>();
            for (int k = 0; k < 101; k++) many.add(new int[]{20, 0, 0, 0, 0, 0});
            check(m.setScript(many) != null, "101 events refused");
            check(m.setScript(Arrays.asList(new int[]{20, 0, 0, 256, 0, 0})) != null, "256 refused");
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean equalsPts(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }
}
