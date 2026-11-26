package aowtool;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Reads an AoW2 GC .jar, lets you edit/add maps, and writes a patched .jar. */
public class JarProject {
    public static class Entry {
        public int slot, id;
        public String name, file;
        public AowMap map;
    }

    static final String[] STOCK = {"Ford", "Encircled", "Crater", "Survive!", "Six Bridges", "Warpath", "Breakout",
            "Small Cross", "Two Shores", "Enemy Gates", "Op. Delta", "Save Rogers", "Spider Pass", "Skull Island", "Two Paths"};

    public static final int MAX_SLOTS = 100;

    private final LinkedHashMap<String, byte[]> files = new LinkedHashMap<String, byte[]>();
    public final List<Entry> maps = new ArrayList<Entry>();
    public int version = 0;
    private final byte[] helperClass;

    /** @param helperClass compiled crk/Maps.class (shipped with the app) */
    public JarProject(InputStream jar, byte[] helperClass) throws IOException {
        this.helperClass = helperClass;
        ZipInputStream zi = new ZipInputStream(jar);
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zi.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int n;
            while ((n = zi.read(buf)) > 0) bo.write(buf, 0, n);
            files.put(e.getName(), bo.toByteArray());
        }
        if (!files.containsKey("aow2.class") || !files.containsKey("crk/canvas.class"))
            throw new IOException("Not an Art of War 2 GC jar with the crk/canvas loader");
        loadIndex();
    }

    private void loadIndex() throws IOException {
        byte[] idx = files.get("maps.idx");
        if (idx != null) {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(idx));
            version = in.readInt();
            int n = in.readShort();
            for (int i = 0; i < n; i++) {
                Entry e = new Entry();
                e.slot = in.readShort(); e.id = in.readShort(); e.name = in.readUTF(); e.file = in.readUTF();
                maps.add(e);
            }
        } else {
            for (int i = 1; i <= 15; i++) {
                if (!files.containsKey("map" + i)) continue;
                Entry e = new Entry();
                e.slot = i - 1; e.id = i; e.file = "map" + i;
                e.name = i <= STOCK.length ? STOCK[i - 1] : "Map " + i;
                maps.add(e);
            }
        }
        for (Entry e : maps) e.map = AowMap.parse(files.get(e.file));
    }

    public Entry add(String name, AowMap map) {
        boolean[] used = new boolean[MAX_SLOTS];
        for (Entry e : maps) used[e.slot] = true;
        int slot = -1;
        for (int i = 0; i < MAX_SLOTS; i++) if (!used[i]) { slot = i; break; }
        if (slot < 0) throw new IllegalStateException("All 100 map slots are used");
        if (name.length() > 24) name = name.substring(0, 24);
        Entry e = new Entry();
        e.slot = slot; e.id = slot + 1; e.name = name; e.file = "map" + (slot + 1); e.map = map;
        maps.add(e);
        return e;
    }

    public void write(OutputStream out) throws IOException {
        LinkedHashMap<String, byte[]> o = new LinkedHashMap<String, byte[]>(files);
        o.put("aow2.class", ClassPatcher.patchAow2(files.get("aow2.class")));
        o.put("crk/Maps.class", ClassPatcher.asVersion49(helperClass));
        ByteArrayOutputStream ib = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ib);
        d.writeInt(version + 1);
        d.writeShort(maps.size());
        for (Entry e : maps) {
            d.writeShort(e.slot); d.writeShort(e.id); d.writeUTF(e.name); d.writeUTF(e.file);
            o.put(e.file, e.map.toBytes());
        }
        o.put("maps.idx", ib.toByteArray());
        ZipOutputStream zo = new ZipOutputStream(out);
        // manifest first, as the spec asks
        if (o.containsKey("META-INF/MANIFEST.MF")) put(zo, "META-INF/MANIFEST.MF", o.remove("META-INF/MANIFEST.MF"));
        for (Map.Entry<String, byte[]> en : o.entrySet()) put(zo, en.getKey(), en.getValue());
        zo.finish();
        zo.flush();
    }

    private static void put(ZipOutputStream z, String n, byte[] b) throws IOException {
        ZipEntry e = new ZipEntry(n);
        z.putNextEntry(e); z.write(b); z.closeEntry();
    }
}
