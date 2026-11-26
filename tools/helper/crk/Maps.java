package crk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import javax.microedition.rms.RecordStore;

/**
 * Data-driven map loader for Art of War 2 - Global Confederation.
 * Called instead of crk.canvas.start() (same-size bytecode patch in aow2.startApp).
 *
 * /maps.idx (written by the editor):
 *   int  version
 *   short count
 *   count x { short slot; short id; UTF name; UTF file }
 * Each file is a normal map resource ([short length][map bytes]) = one RMS slot record.
 *
 * CLDC 1.0 safe: no generics, no StringBuilder, no String.split/format, no floats.
 */
public class Maps {

    public static void run(canvas c) {
        c.start();                 // original behaviour, untouched
        try {
            apply();
        } catch (Throwable t) {
            System.out.println(t);
        }
    }

    static byte[] readAll(String path) throws Exception {
        InputStream in = Maps.class.getResourceAsStream(path);
        if (in == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static String slotStore(int slot) {
        StringBuffer sb = new StringBuffer();
        sb.append("aow21");
        if (slot < 10) sb.append('0');
        sb.append(slot);
        return sb.toString();
    }

    static void apply() throws Exception {
        byte[] idx = readAll("/maps.idx");
        if (idx == null) return;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(idx));
        int version = in.readInt();

        RecordStore mod = RecordStore.openRecordStore("aow2mod", true);
        if (mod.getNumRecords() > 0) {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(mod.getRecord(1)));
            if (d.readInt() == version) { mod.closeRecordStore(); return; }
        }

        int count = in.readShort();
        int[] slots = new int[count];
        int[] ids = new int[count];
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            slots[i] = in.readShort();
            ids[i] = in.readShort();
            names[i] = in.readUTF();
            String file = in.readUTF();
            if (!file.startsWith("/")) file = "/" + file;
            byte[] data = readAll(file);
            if (data == null || slots[i] < 0 || slots[i] > 99) { slots[i] = -1; continue; }
            RecordStore rs = RecordStore.openRecordStore(slotStore(slots[i]), true);
            if (rs.getNumRecords() == 0) rs.addRecord(data, 0, data.length);
            else rs.setRecord(1, data, 0, data.length);
            rs.closeRecordStore();
        }

        // slot table = record 1 of "aow212": 21 header bytes, 100 x {short id [, byte len, name]}, rest
        RecordStore st = RecordStore.openRecordStore("aow212", true);
        byte[] old = st.getRecord(1);
        DataInputStream di = new DataInputStream(new ByteArrayInputStream(old));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bo);
        byte[] head = new byte[21];
        di.readFully(head);
        dos.write(head, 0, 21);
        for (int s = 0; s < 100; s++) {
            int v = di.readShort();
            String nm = null;
            if (v >= 0) {
                int len = di.readByte();
                byte[] nb = new byte[len];
                di.readFully(nb);
                nm = new String(nb);
            }
            for (int i = 0; i < count; i++) {
                if (slots[i] == s) { v = ids[i]; nm = names[i]; }
            }
            dos.writeShort(v);
            if (v >= 0) {
                dos.writeByte(nm.length());
                for (int k = 0; k < nm.length(); k++) dos.writeByte(nm.charAt(k));
            }
        }
        byte[] rest = new byte[di.available()];
        di.readFully(rest);
        dos.write(rest, 0, rest.length);
        byte[] out = bo.toByteArray();
        st.setRecord(1, out, 0, out.length);
        st.closeRecordStore();

        ByteArrayOutputStream mb = new ByteArrayOutputStream();
        new DataOutputStream(mb).writeInt(version);
        byte[] m = mb.toByteArray();
        if (mod.getNumRecords() == 0) mod.addRecord(m, 0, m.length); else mod.setRecord(1, m, 0, m.length);
        mod.closeRecordStore();
    }
}
