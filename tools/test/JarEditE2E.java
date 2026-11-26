import aowtool.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Edits a path + script event in a real JAR, writes it, reads it back. args: game.jar Maps.class */
public class JarEditE2E {
    public static void main(String[] a) throws Exception {
        byte[] helper = Files.readAllBytes(Paths.get(a[1]));
        JarProject p = new JarProject(new FileInputStream(a[0]), helper);
        JarProject.Entry e = p.maps.get(9);   // map10
        AowMap m = e.map;
        int before = m.script().size();
        List<int[]> pts = m.attackPath(1);
        pts.add(new int[]{5, 5});
        if (m.setAttackPath(1, pts) != null) throw new RuntimeException("path refused");
        List<int[]> ev = m.script();
        ev.add(0, new int[]{27, 6, 7, 32, 0, 3});
        if (m.setScript(ev) != null) throw new RuntimeException("script refused");
        m.set(0, 0, m.get(1, 1));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        p.write(bo);
        JarProject q = new JarProject(new ByteArrayInputStream(bo.toByteArray()), helper);
        AowMap r = q.maps.get(9).map;
        System.out.println("script " + before + " -> " + r.script().size() + ", first=" + Arrays.toString(r.script().get(0)));
        System.out.println("path1 = " + r.attackPath(1).size() + " points, last=" + Arrays.toString(r.attackPath(1).get(r.attackPath(1).size() - 1)));
        System.out.println("spawns now " + r.spawns().size() + " (was " + AowMap.parse(Files.readAllBytes(Paths.get("jar/map10"))).spawns().size() + ")");
        boolean otherSame = true;
        for (int i = 0; i < p.maps.size(); i++) if (i != 9) otherSame &= Arrays.equals(q.maps.get(i).map.toBytes(), Files.readAllBytes(Paths.get("jar/map" + (i + 1))));
        System.out.println("other 14 maps byte-identical: " + otherSame);
        boolean ok = r.script().size() == before + 1 && r.attackPath(1).size() == 2 && otherSame;
        System.out.println(ok ? "E2E OK" : "E2E FAIL");
        System.exit(ok ? 0 : 1);
    }
}
