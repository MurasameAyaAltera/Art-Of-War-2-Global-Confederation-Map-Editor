package aowtool;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Minimal class-file patcher. Rewrites aow2.startApp so that
 *     new crk.canvas().start()          (invokevirtual crk/canvas.start:()V)
 * becomes
 *     crk.Maps.run(canvas)              (invokestatic  crk/Maps.run:(Lcrk/canvas;)V)
 * Both are 3-byte instructions with the same stack effect (pop one reference, push nothing),
 * so no offsets, exception tables or StackMap frames change. Only new constant-pool entries are appended.
 */
public class ClassPatcher {

    public static boolean isPatched(byte[] cls) {
        return indexOf(cls, "crk/Maps".getBytes()) >= 0;
    }

    public static byte[] patchAow2(byte[] cls) throws IOException {
        if (isPatched(cls)) return cls;
        int cpCount = u16(cls, 8);
        int[] off = new int[cpCount];
        int[] tag = new int[cpCount];
        int p = 10;
        for (int i = 1; i < cpCount; i++) {
            off[i] = p; tag[i] = cls[p] & 255;
            switch (tag[i]) {
                case 1: p += 3 + u16(cls, p + 1); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: p += 5; break;
                case 5: case 6: p += 9; i++; break;
                case 7: case 8: case 16: p += 3; break;
                case 15: p += 4; break;
                default: throw new IOException("unknown constant tag " + tag[i]);
            }
        }
        int cpEnd = p;
        // find Methodref crk/canvas.start:()V
        int target = -1;
        for (int i = 1; i < cpCount && target < 0; i++) {
            if (tag[i] != 10) continue;
            int ci = u16(cls, off[i] + 1), nt = u16(cls, off[i] + 3);
            if (!utf(cls, off, u16(cls, off[ci] + 1)).equals("crk/canvas")) continue;
            if (utf(cls, off, u16(cls, off[nt] + 1)).equals("start")
                    && utf(cls, off, u16(cls, off[nt] + 3)).equals("()V")) target = i;
        }
        if (target < 0) throw new IOException("crk/canvas.start()V not referenced - is this the expected jar?");
        int hit = -1;
        for (int i = cpEnd; i + 2 < cls.length; i++) {
            if ((cls[i] & 255) == 0xB6 && u16(cls, i + 1) == target) {
                if (hit >= 0) throw new IOException("ambiguous call site");
                hit = i;
            }
        }
        if (hit < 0) throw new IOException("call site not found");

        int n = cpCount;
        ByteArrayOutputStream add = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(add);
        d.writeByte(1); d.writeUTF("crk/Maps");              // n
        d.writeByte(7); d.writeShort(n);                     // n+1 Class
        d.writeByte(1); d.writeUTF("run");                   // n+2
        d.writeByte(1); d.writeUTF("(Lcrk/canvas;)V");       // n+3
        d.writeByte(12); d.writeShort(n + 2); d.writeShort(n + 3); // n+4
        d.writeByte(10); d.writeShort(n + 1); d.writeShort(n + 4); // n+5 Methodref
        byte[] a = add.toByteArray();

        byte[] out = new byte[cls.length + a.length];
        System.arraycopy(cls, 0, out, 0, cpEnd);
        System.arraycopy(a, 0, out, cpEnd, a.length);
        System.arraycopy(cls, cpEnd, out, cpEnd + a.length, cls.length - cpEnd);
        out[8] = (byte) ((n + 6) >> 8); out[9] = (byte) (n + 6);
        int h = hit + a.length;
        out[h] = (byte) 0xB8; out[h + 1] = (byte) ((n + 5) >> 8); out[h + 2] = (byte) (n + 5);
        return out;
    }

    /** Marks a class as Java 5 (49) so old verifiers do not demand a StackMapTable. */
    public static byte[] asVersion49(byte[] cls) {
        byte[] c = cls.clone();
        c[6] = 0; c[7] = 49;
        return c;
    }

    static String utf(byte[] c, int[] off, int idx) {
        int o = off[idx];
        return new String(c, o + 3, u16(c, o + 1));
    }
    static int u16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o + 1] & 255); }
    static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }
}
