import aowtool.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Check {
    public static void main(String[] a) throws Exception {
        byte[] helper = Files.readAllBytes(Paths.get("out/helper/crk/Maps.class"));
        JarProject p = new JarProject(new FileInputStream(a[0]), helper);
        System.out.println("maps in jar: " + p.maps.size());
        int identical = 0;
        for (JarProject.Entry e : p.maps) {
            byte[] orig = Files.readAllBytes(Paths.get("../x/" + e.file));
            byte[] re = e.map.toBytes();
            AowMap again = AowMap.parse(re);
            boolean sem = Arrays.equals(again.tiles, e.map.tiles) && Arrays.equals(again.rest, e.map.rest) && Arrays.equals(again.fog, e.map.fog) && again.buildings.size() == e.map.buildings.size()
                    && again.objects.size() == e.map.objects.size();
            // original map's object list is already y,x sorted?
            boolean same = Arrays.equals(orig, re);
            if (same) identical++;
            System.out.printf("%2d %-12s %3dx%-3d ts=%d objs=%4d tail=%4d  roundtrip-semantic=%s byte-identical=%s%n",
                    e.slot, e.name, e.map.width, e.map.height, e.map.tileset, e.map.objects.size(), e.map.rest.length, sem, same);
        }
        System.out.println("byte-identical: " + identical + "/" + p.maps.size());

        // new map = clone of "Ford" with edits
        AowMap m = p.maps.get(0).map.copy();
        for (int x = 10; x < 20; x++) for (int y = 10; y < 15; y++) m.set(x, y, m.get(0, 0));
        m.addObject(52, 5, 5);
        JarProject.Entry ne = p.add("My Test Map", m);
        System.out.println("new map slot=" + ne.slot + " id=" + ne.id + " file=" + ne.file);
        try (OutputStream o = new FileOutputStream(a[1])) { p.write(o); }
        System.out.println("written " + a[1] + " " + new File(a[1]).length() + " bytes");
    }
}
