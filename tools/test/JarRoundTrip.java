import aowtool.*;
import java.io.*;
import java.nio.file.*;

/** Opens the real jar, edits buildings of map 1, writes a new jar. Usage: JarRoundTrip <in.jar> <out.jar> <Maps.class> */
public class JarRoundTrip {
    public static void main(String[] a) throws Exception {
        JarProject p = new JarProject(new FileInputStream(a[0]), Files.readAllBytes(Paths.get(a[2])));
        AowMap m = p.maps.get(0).map;
        System.out.println("before: " + m.buildings.size() + " buildings, spawns " + m.spawns().size());
        String[] err = new String[1];
        int i = m.addBuilding(4, 12, 28, 1, err);          // player building next to the start
        System.out.println("add -> " + i + " " + err[0]);
        int[] first = m.buildings.get(0);                   // move the player HQ two cells right
        System.out.println("move check -> " + m.checkBuilding(first[0], first[1] + 2, first[2], 0));
        first[1] += 2;
        m.buildings.remove(m.buildings.size() - 2);         // delete one enemy building
        FileOutputStream fo = new FileOutputStream(a[1]);
        p.write(fo);
        fo.close();
        JarProject q = new JarProject(new FileInputStream(a[1]), Files.readAllBytes(Paths.get(a[2])));
        AowMap r = q.maps.get(0).map;
        System.out.println("after: " + r.buildings.size() + " buildings, spawns " + r.spawns().size());
        for (int k = 1; k < p.maps.size(); k++)
            if (!java.util.Arrays.equals(p.maps.get(k).map.toBytes(), q.maps.get(k).map.toBytes())) System.out.println("MAP " + k + " DIFFERS");
    }
}
