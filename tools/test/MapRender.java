import aowtool.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

public class MapRender {
    public static void main(String[] a) throws Exception {
        String jar = a[0]; String mapFile = a[1]; String out = a[2];
        Art.Png dec = new Art.Png() {
            public int[] decode(byte[] d, int off, int len, int[] wh) throws IOException {
                BufferedImage im = ImageIO.read(new ByteArrayInputStream(d, off, len));
                wh[0] = im.getWidth(); wh[1] = im.getHeight();
                int[] px = new int[wh[0] * wh[1]];
                im.getRGB(0, 0, wh[0], wh[1], px, 0, wh[0]);
                return px;
            }
        };
        Art art = new Art(Files.readAllBytes(Paths.get(jar, "i0")), Files.readAllBytes(Paths.get(jar, "d0")),
                Files.readAllBytes(Paths.get(jar, "ml")), dec);
        AowMap m = AowMap.parse(Files.readAllBytes(Paths.get(jar, mapFile)));
        int x0 = a.length > 3 ? Integer.parseInt(a[3]) : 0, y0 = a.length > 4 ? Integer.parseInt(a[4]) : 0;
        int cw = a.length > 5 ? Integer.parseInt(a[5]) : m.width, ch = a.length > 6 ? Integer.parseInt(a[6]) : m.height;
        BufferedImage img = new BufferedImage(cw * 30, ch * 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int y = y0; y < Math.min(m.height, y0 + ch); y++) for (int x = x0; x < Math.min(m.width, x0 + cw); x++) {
            Art.Img t = art.tile(m.tileset, m.get(x, y));
            if (t == null) continue;
            BufferedImage b = new BufferedImage(t.w, t.h, BufferedImage.TYPE_INT_ARGB);
            b.setRGB(0, 0, t.w, t.h, t.px, 0, t.w);
            g.drawImage(b, (x - x0) * 30 - t.ox, (y - y0) * 20 - t.oy, null);
        }
        List<int[]> objs = new ArrayList<int[]>(m.objects);
        Collections.sort(objs, new Comparator<int[]>() { public int compare(int[] p, int[] q) { return p[2] != q[2] ? p[2] - q[2] : p[1] - q[1]; } });
        int drawn = 0;
        for (int[] o : objs) {
            Art.Img t = art.object(m.tileset, o[0]);
            if (t == null) continue;
            BufferedImage b = new BufferedImage(t.w, t.h, BufferedImage.TYPE_INT_ARGB);
            b.setRGB(0, 0, t.w, t.h, t.px, 0, t.w);
            g.drawImage(b, (o[1] - x0) * 30 - t.ox, (o[2] - y0) * 20 - t.oy, null);
            drawn++;
        }
        List<int[]> blds = new ArrayList<int[]>(m.buildings);
        Collections.sort(blds, new Comparator<int[]>() { public int compare(int[] p, int[] q) { return p[2] != q[2] ? p[2] - q[2] : p[1] - q[1]; } });
        for (int[] bd : blds) {
            Art.Img t = art.kindImage(bd[0]);
            int[] fp = AowMap.footprint(bd[0]);
            g.setColor(bd[0] < 8 ? new Color(0, 160, 255, 90) : new Color(255, 60, 60, 90));
            g.fillRect((bd[1] - x0) * 30, (bd[2] - y0) * 20, fp[0] * 30, fp[1] * 20);
            if (t == null) continue;
            BufferedImage b = new BufferedImage(t.w, t.h, BufferedImage.TYPE_INT_ARGB);
            b.setRGB(0, 0, t.w, t.h, t.px, 0, t.w);
            g.drawImage(b, (bd[1] - x0) * 30 - t.ox, (bd[2] - y0) * 20 - t.oy, null);
        }
        g.setColor(Color.YELLOW);
        for (int[] sp : m.spawns()) { g.fillOval((sp[0] - x0) * 30 + 8, (sp[1] - y0) * 20 + 4, 14, 14); }
        System.out.println("buildings " + blds.size());
        ImageIO.write(img, "png", new File(out));
        System.out.println(m.width + "x" + m.height + " ts" + m.tileset + " objs " + objs.size() + " drawn " + drawn);
    }
}
