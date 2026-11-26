import aowtool.Art;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

public class ArtTest {
    public static void main(String[] a) throws Exception {
        String jar = a[0], out = a[1];
        new File(out).mkdirs();
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
        PrintWriter meta = new PrintWriter(out + "/meta.txt");
        long t0 = System.currentTimeMillis();
        for (int ts = 0; ts < art.tilesetCount(); ts++) {
            meta.println("ts " + ts + " tiles " + art.tileCount(ts) + " objs " + art.objectCount(ts));
            for (int t = 0; t < art.tileCount(ts); t++) {
                Art.Img im = art.tile(ts, t);
                write(im, out + "/t_" + ts + "_" + t + ".png");
                meta.println("t " + ts + " " + t + " " + (im == null ? "null" : im.w + " " + im.h + " " + im.ox + " " + im.oy));
            }
            for (int o = 0; o < art.objectCount(ts); o++) {
                Art.Img im = art.object(ts, o);
                int[] fp = art.footprint(ts, o);
                write(im, out + "/o_" + ts + "_" + o + ".png");
                meta.println("o " + ts + " " + o + " " + (im == null ? "null" : im.w + " " + im.h + " " + im.ox + " " + im.oy) + " fp " + fp[0] + " " + fp[1]);
            }
        }
        meta.close();
        System.out.println("rendered in " + (System.currentTimeMillis() - t0) + " ms");
    }
    static void write(Art.Img im, String f) throws IOException {
        if (im == null) return;
        BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, im.w, im.h, im.px, 0, im.w);
        ImageIO.write(b, "png", new File(f));
    }
}
