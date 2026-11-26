import java.io.*;
import java.util.*;
import javax.microedition.rms.RecordStore;

/** Runs the REAL crk.canvas (original crack loader) + crk.Maps against an in-memory RMS. */
public class Sim {
    static byte[] res(String n) throws Exception { return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../sim/" + n)); }

    static void dumpTable(String label) throws Exception {
        byte[] t = RecordStore.openRecordStore("aow212", false).getRecord(1);
        DataInputStream di = new DataInputStream(new ByteArrayInputStream(t));
        di.skipBytes(21);
        List<String> used = new ArrayList<String>();
        for (int s = 0; s < 100; s++) {
            int v = di.readShort();
            if (v >= 0) { byte[] nb = new byte[di.readByte()]; di.readFully(nb); used.add(s + ":" + v + ":" + new String(nb)); }
        }
        System.out.println(label + " table entries=" + used.size() + " rest-bytes=" + di.available() + " byte14=" + t[14]);
        System.out.println("   " + used);
    }

    public static void main(String[] a) throws Exception {
        // fresh game state: aow212 as the game would create it: 21 header, 100 x -1, 30 trailing bytes
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream d = new DataOutputStream(bo);
        for (int i = 0; i < 21; i++) d.writeByte(0x40 + i);
        for (int i = 0; i < 100; i++) d.writeShort(-1);
        for (int i = 0; i < 30; i++) d.writeByte(0x70 + i);
        byte[] seed = bo.toByteArray();
        RecordStore s = RecordStore.openRecordStore("aow212", true); s.addRecord(seed, 0, seed.length);

        Class<?> cv = Class.forName("crk.canvas"), mp = Class.forName("crk.Maps");
        Object canvas = cv.getDeclaredConstructor().newInstance();
        mp.getMethod("run", cv).invoke(null, canvas);
        dumpTable("after 1st run:");
        for (int slot : new int[]{0, 14, 15}) {
            String n = "aow21" + (slot < 10 ? "0" : "") + slot;
            byte[] r = RecordStore.openRecordStore(n, false).getRecord(1);
            byte[] f = res("map" + (slot + 1));
            System.out.println("   store " + n + " matches /map" + (slot + 1) + ": " + Arrays.equals(r, f));
        }
        byte[] t = RecordStore.openRecordStore("aow212", false).getRecord(1);
        boolean tailKept = true;
        for (int i = 0; i < 30; i++) tailKept &= t[t.length - 30 + i] == (byte) (0x70 + i);
        boolean headKept = true;
        for (int i = 0; i < 21; i++) if (i != 14) headKept &= t[i] == (byte) (0x40 + i);
        System.out.println("   header kept (except crack's byte14): " + headKept + ", trailing game data kept: " + tailKept);

        // second launch: must be a no-op for maps
        byte[] before = RecordStore.openRecordStore("aow212", false).getRecord(1);
        mp.getMethod("run", cv).invoke(null, canvas);
        System.out.println("2nd run idempotent: " + Arrays.equals(before, RecordStore.openRecordStore("aow212", false).getRecord(1)));
        System.out.println("stores: " + new TreeSet<String>(RecordStore.all().keySet()));
    }
}
