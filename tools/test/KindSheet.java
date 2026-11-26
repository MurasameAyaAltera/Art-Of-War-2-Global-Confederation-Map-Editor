import aowtool.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

public class KindSheet {
    public static void main(String[] a) throws Exception {
        String jar = a[0]; String out = a[1];
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
        int n = Integer.parseInt(a[2]);
        int cols = 8, cell = 120;
        int rows = (n + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(70, 90, 60)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int k = 0; k < n; k++) {
            Art.Img im = art.kindImage(k);
            int cx = (k % cols) * cell, cy = (k / cols) * cell;
            g.setColor(Color.WHITE);
            int[] fp = art.kindFootprint(k);
            g.drawString(k + " " + fp[0] + "x" + fp[1] + (im == null ? " null" : ""), cx + 3, cy + 12);
            if (im == null) continue;
            BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_INT_ARGB);
            b.setRGB(0, 0, im.w, im.h, im.px, 0, im.w);
            double sc = Math.min(1.0, Math.min((cell - 6.0) / im.w, (cell - 20.0) / im.h));
            g.drawImage(b, cx + 3, cy + 16, (int) (im.w * sc), (int) (im.h * sc), null);
        }
        ImageIO.write(sheet, "png", new File(out));
        System.out.println("ok " + art.kindCount());
    }
}
