package dictionary.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Toàn bộ hình minh hoạ được vẽ bằng Java2D (không cần file ảnh): nền doodle, icon nét vẽ tay,
 * hai nhân vật mascot (Mia & Leo) và các "cảnh" dùng cho trạng thái trống / màn hình chào.
 */
public final class Art {
    private Art() { }

    static final Color INK = new Color(0x2C2A5A);
    private static final Color SKIN_A = new Color(0xF6C3A0);
    private static final Color SKIN_B = new Color(0xA86B45);
    private static final Color HAIR = new Color(0x3A2F6B);

    private static BasicStroke stroke(float w) { return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); }

    // =====================================================================
    // Nền doodle (sách, bút chì, kính, bong bóng chat…) — lát gạch, vẽ một lần rồi cache
    // =====================================================================

    private enum D { BOOK, NOTEBOOK, PENCIL, GLASSES, STAR, SPARKLE, BUBBLE, HEART, BULB, CAP, ABC, SQUIGGLE }

    private record Item(int x, int y, double rot, double s, D type, String text) { }

    private static final int TW = 760, TH = 600;
    private static final Item[] ITEMS = {
            new Item(80, 80, -12, 1.35, D.BOOK, null), new Item(270, 46, 18, 1.0, D.PENCIL, null),
            new Item(440, 96, -8, 1.25, D.NOTEBOOK, null), new Item(640, 70, 8, 1.05, D.BUBBLE, "LOL"),
            new Item(170, 230, 8, 1.15, D.GLASSES, null), new Item(345, 215, -20, 1.0, D.STAR, null),
            new Item(540, 250, 6, 1.35, D.BOOK, null), new Item(710, 220, 0, 0.9, D.SPARKLE, null),
            new Item(70, 380, 14, 1.15, D.BULB, null), new Item(250, 395, -10, 1.25, D.NOTEBOOK, null),
            new Item(420, 360, 10, 1.05, D.BUBBLE, "OMG"), new Item(610, 410, -6, 1.1, D.CAP, null),
            new Item(150, 535, -14, 1.0, D.HEART, null), new Item(340, 520, 10, 1.15, D.BOOK, null),
            new Item(540, 548, -18, 1.0, D.PENCIL, null), new Item(700, 470, 0, 0.8, D.STAR, null),
            new Item(30, 230, 0, 0.8, D.SPARKLE, null), new Item(470, 462, 0, 1.0, D.ABC, null),
            new Item(300, 300, 6, 0.9, D.SQUIGGLE, null), new Item(700, 340, -8, 0.95, D.BUBBLE, "no cap"),
    };

    private static final Map<String, TexturePaint> TILES = new HashMap<>();

    private static TexturePaint tile(Color c, int alpha) {
        String key = c.getRGB() + ":" + alpha;
        return TILES.computeIfAbsent(key, k -> {
            BufferedImage img = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            Ui.aa(g);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
            g.setStroke(stroke(2.2f));
            for (Item it : ITEMS) {
                Graphics2D t = (Graphics2D) g.create();
                t.translate(it.x, it.y);
                t.rotate(Math.toRadians(it.rot));
                t.scale(it.s, it.s);
                doodle(t, it);
                t.dispose();
            }
            g.dispose();
            return new TexturePaint(img, new Rectangle(0, 0, TW, TH));
        });
    }

    /** Vẽ giấy + họa tiết doodle phủ kín vùng (0,0,w,h). */
    public static void paintPaper(Graphics2D g, int w, int h, Color base, Color line, int alpha) {
        g.setColor(base);
        g.fillRect(0, 0, w, h);
        g.setPaint(tile(line, alpha));
        g.fillRect(0, 0, w, h);
    }

    private static void doodle(Graphics2D g, Item it) {
        switch (it.type) {
            case BOOK -> openBook(g);
            case NOTEBOOK -> notebook(g);
            case PENCIL -> pencil(g);
            case GLASSES -> glasses(g);
            case STAR -> g.draw(star(0, 0, 13, 5.5, 5));
            case SPARKLE -> g.draw(sparkle(0, 0, 14, 4));
            case BUBBLE -> {
                g.draw(new RoundRectangle2D.Float(-30, -16, 60, 32, 18, 18));
                Path2D tail = new Path2D.Float();
                tail.moveTo(-12, 16); tail.lineTo(-18, 27); tail.lineTo(-2, 16);
                g.draw(tail);
                g.setFont(Theme.display(it.text.length() > 3 ? 13f : 16f));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(it.text, -fm.stringWidth(it.text) / 2f, 6);
            }
            case HEART -> g.draw(heart(0, 0, 15));
            case BULB -> bulb(g);
            case CAP -> {
                Path2D p = new Path2D.Float();
                p.moveTo(-30, 0); p.lineTo(0, -16); p.lineTo(30, 0); p.lineTo(0, 16); p.closePath();
                g.draw(p);
                g.draw(new Arc2D.Float(-15, 4, 30, 24, 180, 180, Arc2D.OPEN));
                g.draw(new Line2D.Float(30, 0, 30, 20));
                g.draw(new Ellipse2D.Float(27, 20, 6, 8));
            }
            case ABC -> {
                g.draw(new RoundRectangle2D.Float(-24, -24, 48, 48, 12, 12));
                g.setFont(Theme.display(22f));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("Aa", -fm.stringWidth("Aa") / 2f, 8);
            }
            case SQUIGGLE -> {
                Path2D p = new Path2D.Float();
                p.moveTo(-40, 0);
                for (int i = 0; i < 4; i++) p.quadTo(-40 + i * 20 + 5, i % 2 == 0 ? -10 : 10, -40 + i * 20 + 10, 0);
                for (int i = 0; i < 4; i++) p.quadTo(-30 + i * 20 + 5, i % 2 == 0 ? 10 : -10, -30 + i * 20 + 10, 0);
                g.draw(p);
            }
        }
    }

    private static void openBook(Graphics2D g) {
        for (int sx = -1; sx <= 1; sx += 2) {
            Path2D p = new Path2D.Float();
            p.moveTo(0, -12);
            p.curveTo(sx * 8, -20, sx * 20, -20, sx * 32, -14);
            p.lineTo(sx * 32, 18);
            p.curveTo(sx * 20, 12, sx * 8, 12, 0, 20);
            p.closePath();
            g.draw(p);
            for (int i = 0; i < 3; i++) {
                Path2D l = new Path2D.Float();
                l.moveTo(sx * 6, -6 + i * 7); l.curveTo(sx * 12, -9 + i * 7, sx * 20, -9 + i * 7, sx * 27, -6 + i * 7);
                g.draw(l);
            }
        }
        g.draw(new Line2D.Float(-30, 24, 30, 24));
        g.draw(new Line2D.Float(-26, 29, 26, 29));
    }

    private static void notebook(Graphics2D g) {
        g.draw(new RoundRectangle2D.Float(-18, -26, 36, 52, 6, 6));
        for (int i = 0; i < 5; i++) g.draw(new Ellipse2D.Float(-23, -19 + i * 9, 9, 5));
        g.draw(new Rectangle2D.Float(-4, -16, 16, 11));
        g.draw(new Line2D.Float(-4, 6, 10, 6));
        g.draw(new Line2D.Float(-4, 13, 10, 13));
    }

    private static void pencil(Graphics2D g) {
        g.draw(new Rectangle2D.Float(-5, -30, 10, 44));
        Path2D tip = new Path2D.Float();
        tip.moveTo(-5, 14); tip.lineTo(0, 30); tip.lineTo(5, 14);
        g.draw(tip);
        g.draw(new Line2D.Float(-5, -22, 5, -22));
        g.draw(new Line2D.Float(0, -30, 0, 14));
    }

    private static void glasses(Graphics2D g) {
        g.draw(new Ellipse2D.Float(-26, -10, 22, 22));
        g.draw(new Ellipse2D.Float(4, -10, 22, 22));
        g.draw(new Arc2D.Float(-6, -8, 12, 10, 0, 180, Arc2D.OPEN));
        g.draw(new Line2D.Float(-26, 0, -34, -6));
        g.draw(new Line2D.Float(26, 0, 34, -6));
    }

    private static void bulb(Graphics2D g) {
        g.draw(new Ellipse2D.Float(-13, -26, 26, 26));
        g.draw(new RoundRectangle2D.Float(-8, 0, 16, 12, 4, 4));
        g.draw(new Line2D.Float(-5, 5, 5, 5));
        for (int a = -60; a <= 60; a += 30) {
            double r = Math.toRadians(a - 90);
            g.draw(new Line2D.Double(Math.cos(r) * 20, -13 + Math.sin(r) * 20, Math.cos(r) * 27, -13 + Math.sin(r) * 27));
        }
    }

    // ---------- hình cơ bản ----------

    static Path2D star(double cx, double cy, double ro, double ri, int n) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < n * 2; i++) {
            double r = i % 2 == 0 ? ro : ri, a = Math.PI / n * i - Math.PI / 2;
            double x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    static Path2D sparkle(double cx, double cy, double r, double waist) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy - r);
        p.quadTo(cx + waist, cy - waist, cx + r, cy);
        p.quadTo(cx + waist, cy + waist, cx, cy + r);
        p.quadTo(cx - waist, cy + waist, cx - r, cy);
        p.quadTo(cx - waist, cy - waist, cx, cy - r);
        p.closePath();
        return p;
    }

    static Path2D heart(double cx, double cy, double s) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy + s * 0.9);
        p.curveTo(cx - s * 1.6, cy - s * 0.2, cx - s * 0.7, cy - s * 1.3, cx, cy - s * 0.45);
        p.curveTo(cx + s * 0.7, cy - s * 1.3, cx + s * 1.6, cy - s * 0.2, cx, cy + s * 0.9);
        p.closePath();
        return p;
    }

    /** Cụm 2 ngôi sao lấp lánh nhỏ (dùng ở logo). */
    public static void sparkles(Graphics2D g, double x, double y, double r) {
        Shape a = AffineTransform.getTranslateInstance(x, y).createTransformedShape(sparkle(0, 0, r, r * 0.3));
        Shape b = AffineTransform.getTranslateInstance(x + r * 1.9, y - r * 0.9).createTransformedShape(sparkle(0, 0, r * 0.6, r * 0.18));
        g.setColor(Theme.butter()); g.fill(a); g.fill(b);
        g.setColor(Theme.ink()); g.setStroke(stroke(1.8f)); g.draw(a); g.draw(b);
    }

    // =====================================================================
    // Icon nét vẽ (dùng cho sidebar, tiêu đề trang, nút)
    // =====================================================================

    public enum Icon { SEARCH, BOOKS, CLOCK, SPARK, QUIZ, GEAR, PLUS, CHECK, CROSS, FIRE, STAR, TRASH, EDIT, BOLT, SUN, MOON }

    /** Vẽ icon trong khung vuông cạnh {@code size}, tâm (cx,cy). */
    public static void icon(Graphics2D g0, Icon ic, double cx, double cy, double size, Color color, float lw) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.translate(cx, cy);
        double k = size / 24.0;
        g.scale(k, k);
        g.setColor(color);
        g.setStroke(stroke(lw / (float) k));
        switch (ic) {
            case SEARCH -> {
                g.draw(new Ellipse2D.Double(-9, -9, 14, 14));
                g.draw(new Line2D.Double(2, 2, 9, 9));
            }
            case BOOKS -> {
                g.draw(new RoundRectangle2D.Double(-9, -9, 6, 18, 2, 2));
                g.draw(new RoundRectangle2D.Double(-2, -9, 6, 18, 2, 2));
                Path2D p = new Path2D.Double();
                p.moveTo(6, -8); p.lineTo(10, -7); p.lineTo(7, 9); p.lineTo(4, 8);
                g.draw(p);
            }
            case CLOCK -> {
                g.draw(new Ellipse2D.Double(-10, -10, 20, 20));
                Path2D p = new Path2D.Double();
                p.moveTo(0, -5); p.lineTo(0, 0); p.lineTo(5, 3);
                g.draw(p);
            }
            case SPARK -> {
                g.draw(sparkle(-1, 1, 10, 2.6));
                g.draw(sparkle(7, -8, 4, 1.2));
            }
            case QUIZ -> {
                g.draw(new RoundRectangle2D.Double(-10, -10, 20, 16, 8, 8));
                Path2D t = new Path2D.Double();
                t.moveTo(-4, 6); t.lineTo(-6, 11); t.lineTo(1, 6);
                g.draw(t);
                Path2D q = new Path2D.Double();
                q.moveTo(-3.5, -4); q.curveTo(-3.5, -8, 3.5, -8, 3.5, -4); q.curveTo(3.5, -1.5, 0, -1.5, 0, 1);
                g.draw(q);
                g.fill(new Ellipse2D.Double(-0.9, 2.2, 1.8, 1.8));
            }
            case GEAR -> {
                g.draw(new Ellipse2D.Double(-4, -4, 8, 8));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(Math.cos(a) * 7.5, Math.sin(a) * 7.5, Math.cos(a) * 10.5, Math.sin(a) * 10.5));
                }
                g.draw(new Ellipse2D.Double(-7.5, -7.5, 15, 15));
            }
            case PLUS -> { g.draw(new Line2D.Double(-8, 0, 8, 0)); g.draw(new Line2D.Double(0, -8, 0, 8)); }
            case CHECK -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, 1); p.lineTo(-2, 7); p.lineTo(9, -7);
                g.draw(p);
            }
            case CROSS -> { g.draw(new Line2D.Double(-7, -7, 7, 7)); g.draw(new Line2D.Double(-7, 7, 7, -7)); }
            case FIRE -> {
                Path2D p = new Path2D.Double();
                p.moveTo(0, -11); p.curveTo(2, -5, 9, -3, 8, 4); p.curveTo(7, 10, -7, 10, -8, 3);
                p.curveTo(-8.5, -1, -4, -3, -4, -7); p.curveTo(-2, -6, -1, -8, 0, -11);
                g.draw(p);
            }
            case STAR -> g.draw(star(0, 0, 10, 4.5, 5));
            case TRASH -> {
                g.draw(new Line2D.Double(-8, -6, 8, -6));
                g.draw(new Line2D.Double(-3, -6, -3, -9)); g.draw(new Line2D.Double(3, -6, 3, -9));
                g.draw(new Line2D.Double(-3, -9, 3, -9));
                Path2D p = new Path2D.Double();
                p.moveTo(-6, -4); p.lineTo(-5, 9); p.lineTo(5, 9); p.lineTo(6, -4);
                g.draw(p);
            }
            case EDIT -> {
                Path2D p = new Path2D.Double();
                p.moveTo(-8, 8); p.lineTo(-7, 3); p.lineTo(5, -9); p.lineTo(9, -5); p.lineTo(-3, 7); p.closePath();
                g.draw(p);
                g.draw(new Line2D.Double(2, -6, 6, -2));
            }
            case BOLT -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, -10); p.lineTo(-6, 2); p.lineTo(0, 2); p.lineTo(-3, 10); p.lineTo(7, -3); p.lineTo(1, -3); p.closePath();
                g.draw(p);
            }
            case SUN -> {
                g.draw(new Ellipse2D.Double(-5, -5, 10, 10));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(Math.cos(a) * 8, Math.sin(a) * 8, Math.cos(a) * 11, Math.sin(a) * 11));
                }
            }
            case MOON -> {
                Area a = new Area(new Ellipse2D.Double(-9, -9, 18, 18));
                a.subtract(new Area(new Ellipse2D.Double(-2, -12, 18, 18)));
                g.draw(a);
            }
        }
        g.dispose();
    }

    // =====================================================================
    // Hộp "sticker": nền phẳng + viền mực + bóng cứng
    // =====================================================================

    public static void sticker(Graphics2D g, Shape s, Color fill, float lw, int shadow) {
        if (shadow > 0) {
            Graphics2D sh = (Graphics2D) g.create();
            sh.translate(shadow, shadow);
            sh.setColor(Theme.shadow());
            sh.fill(s);
            sh.dispose();
        }
        g.setColor(fill);
        g.fill(s);
        g.setColor(Theme.ink());
        g.setStroke(stroke(lw));
        g.draw(s);
    }

    /** Bong bóng thoại có đuôi, chữ Fredoka. */
    public static void bubble(Graphics2D g, double x, double y, String text, Color fill, boolean tailLeft, float fs) {
        g.setFont(Theme.display(fs));
        FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(text) + 30, h = fm.getHeight() + 10;
        Path2D tail = new Path2D.Double();
        double tx = tailLeft ? x + 18 : x + w - 38;
        tail.moveTo(tx, y + h - 2); tail.lineTo(tailLeft ? tx - 8 : tx + 30, y + h + 16); tail.lineTo(tx + 20, y + h - 2);
        Area a = new Area(new RoundRectangle2D.Double(x, y, w, h, h, h));
        a.add(new Area(tail));
        sticker(g, a, fill, 2.6f, 3);
        g.setColor(INK);
        g.drawString(text, (float) (x + 15), (float) (y + 5 + fm.getAscent()));
    }

    // =====================================================================
    // Nhân vật
    // =====================================================================

    public enum Pose { BOOK, WAVE, MAGNIFY, PHONE, THINK, THUMBS }

    private static void limb(Graphics2D g, double x1, double y1, double x2, double y2, Color c, double th) {
        g.setColor(INK);
        g.setStroke(new BasicStroke((float) (th + 6), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x1, y1, x2, y2));
        g.setColor(c);
        g.setStroke(new BasicStroke((float) th, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static void hand(Graphics2D g, double x, double y, Color skin) {
        g.setColor(skin);
        g.fill(new Ellipse2D.Double(x - 10, y - 10, 20, 20));
        g.setColor(INK);
        g.setStroke(stroke(3f));
        g.draw(new Ellipse2D.Double(x - 10, y - 10, 20, 20));
    }

    private static void outlined(Graphics2D g, Shape s, Color fill) {
        g.setColor(fill);
        g.fill(s);
        g.setColor(INK);
        g.setStroke(stroke(3f));
        g.draw(s);
    }

    private static void face(Graphics2D g, double cy, boolean open) {
        g.setColor(INK);
        g.fill(new Ellipse2D.Double(-15, cy - 6, 6, 9));
        g.fill(new Ellipse2D.Double(9, cy - 6, 6, 9));
        g.setColor(new Color(255, 120, 160, 150));
        g.fill(new Ellipse2D.Double(-27, cy + 4, 12, 8));
        g.fill(new Ellipse2D.Double(15, cy + 4, 12, 8));
        g.setColor(INK);
        g.setStroke(stroke(2.6f));
        if (open) {
            Path2D m = new Path2D.Double();
            m.moveTo(-8, cy + 9); m.quadTo(0, cy + 22, 8, cy + 9); m.closePath();
            g.setColor(new Color(0x8A2A4A)); g.fill(m);
            g.setColor(INK); g.draw(m);
        } else {
            g.draw(new Arc2D.Double(-7, cy + 5, 14, 10, 200, 140, Arc2D.OPEN));
        }
    }

    /** Mia: tóc búi, áo len vàng, quần xanh. Gốc toạ độ = giữa bàn chân; cao khoảng 235 đơn vị. */
    public static void mia(Graphics2D g0, double x, double y, double s, Pose pose) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.translate(x, y);
        g.scale(s, s);
        Color sweater = new Color(0xFF9EC4), pants = new Color(0x7DB6FF);
        // chân + giày
        for (int sx = -1; sx <= 1; sx += 2) {
            outlined(g, new RoundRectangle2D.Double(sx * 22 - 15, -42, 30, 40, 12, 12), pants);
            outlined(g, new RoundRectangle2D.Double(sx * 22 - 18 + (sx > 0 ? 3 : -3), -8, 36, 14, 12, 12), Color.WHITE);
        }
        // thân
        Path2D body = new Path2D.Double();
        body.moveTo(-58, -34); body.curveTo(-62, -110, -54, -138, -20, -142);
        body.lineTo(20, -142); body.curveTo(54, -138, 62, -110, 58, -34); body.closePath();
        outlined(g, body, sweater);
        g.setColor(new Color(0x0C3B30, false));
        g.setStroke(stroke(3f));
        g.draw(new Line2D.Double(-56, -44, 56, -44));
        // tóc sau
        outlined(g, new Ellipse2D.Double(-40, -214, 80, 86), HAIR);
        outlined(g, new Ellipse2D.Double(-16, -240, 32, 32), HAIR);
        // cổ + đầu
        outlined(g, new RoundRectangle2D.Double(-10, -152, 20, 22, 8, 8), SKIN_A);
        outlined(g, new Ellipse2D.Double(-33, -202, 66, 70), SKIN_A);
        // mái
        Path2D hair = new Path2D.Double();
        hair.moveTo(-35, -168); hair.curveTo(-40, -216, 40, -216, 35, -168);
        hair.curveTo(28, -186, 12, -190, 4, -194); hair.curveTo(-8, -184, -24, -180, -35, -168); hair.closePath();
        outlined(g, hair, HAIR);
        face(g, -168, pose == Pose.WAVE || pose == Pose.THUMBS);
        // kính
        if (pose == Pose.BOOK || pose == Pose.MAGNIFY) {
            g.setColor(INK);
            g.setStroke(stroke(2.4f));
            g.draw(new Ellipse2D.Double(-23, -178, 20, 20));
            g.draw(new Ellipse2D.Double(3, -178, 20, 20));
            g.draw(new Line2D.Double(-3, -169, 3, -169));
        }
        // tay + đạo cụ
        switch (pose) {
            case BOOK -> {
                limb(g, -46, -126, -42, -78, sweater, 22);
                limb(g, 46, -126, 42, -78, sweater, 22);
                Path2D l = new Path2D.Double();
                l.moveTo(0, -112); l.curveTo(-14, -122, -34, -122, -50, -114); l.lineTo(-50, -66); l.curveTo(-34, -74, -14, -74, 0, -62); l.closePath();
                Path2D r = new Path2D.Double();
                r.moveTo(0, -112); r.curveTo(14, -122, 34, -122, 50, -114); r.lineTo(50, -66); r.curveTo(34, -74, 14, -74, 0, -62); r.closePath();
                outlined(g, l, Color.WHITE); outlined(g, r, Color.WHITE);
                g.setColor(Theme.accent()); g.setStroke(stroke(2.2f));
                for (int i = 0; i < 3; i++) {
                    g.draw(new Line2D.Double(-42, -104 + i * 10, -10, -106 + i * 10));
                    g.draw(new Line2D.Double(10, -106 + i * 10, 42, -104 + i * 10));
                }
                hand(g, -48, -78, SKIN_A); hand(g, 48, -78, SKIN_A);
            }
            case WAVE -> {
                limb(g, -46, -126, -58, -62, sweater, 22); hand(g, -58, -58, SKIN_A);
                limb(g, 46, -126, 80, -180, sweater, 22); hand(g, 82, -186, SKIN_A);
                g.setColor(INK); g.setStroke(stroke(2.6f));
                g.draw(new Arc2D.Double(92, -208, 20, 20, -60, 120, Arc2D.OPEN));
                g.draw(new Arc2D.Double(98, -214, 30, 34, -60, 120, Arc2D.OPEN));
            }
            case MAGNIFY -> {
                limb(g, -46, -126, -58, -62, sweater, 22); hand(g, -58, -58, SKIN_A);
                limb(g, 46, -126, 70, -90, sweater, 22);
                g.setColor(INK); g.setStroke(stroke(7f));
                g.draw(new Line2D.Double(72, -92, 98, -130));
                g.setColor(Theme.coral()); g.setStroke(stroke(3.5f));
                g.draw(new Line2D.Double(72, -92, 98, -130));
                Ellipse2D lens = new Ellipse2D.Double(80, -176, 56, 56);
                outlined(g, lens, new Color(0xBFE8F7));
                g.setColor(new Color(255, 255, 255, 190)); g.setStroke(stroke(3f));
                g.draw(new Arc2D.Double(90, -166, 30, 30, 100, 80, Arc2D.OPEN));
                hand(g, 72, -90, SKIN_A);
            }
            default -> {
                limb(g, -46, -126, -58, -62, sweater, 22); hand(g, -58, -58, SKIN_A);
                limb(g, 46, -126, 58, -62, sweater, 22); hand(g, 58, -58, SKIN_A);
            }
        }
        g.dispose();
    }

    /** Leo: mũ lưỡi trai san hô, áo hoodie xanh trời. */
    public static void leo(Graphics2D g0, double x, double y, double s, Pose pose) {
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        g.translate(x, y);
        g.scale(s, s);
        Color hood = new Color(0xA8D4FF), pants = new Color(0x5C6FC2);
        for (int sx = -1; sx <= 1; sx += 2) {
            outlined(g, new RoundRectangle2D.Double(sx * 22 - 15, -42, 30, 40, 12, 12), pants);
            outlined(g, new RoundRectangle2D.Double(sx * 22 - 18 + (sx > 0 ? 3 : -3), -8, 36, 14, 12, 12), Theme.coral());
        }
        Path2D body = new Path2D.Double();
        body.moveTo(-60, -34); body.curveTo(-64, -108, -56, -138, -22, -142);
        body.lineTo(22, -142); body.curveTo(56, -138, 64, -108, 60, -34); body.closePath();
        outlined(g, body, hood);
        outlined(g, new RoundRectangle2D.Double(-30, -72, 60, 28, 14, 14), new Color(0x8BBFF2));
        g.setColor(INK); g.setStroke(stroke(3f));
        g.draw(new Line2D.Double(-8, -142, -8, -108)); g.draw(new Line2D.Double(8, -142, 8, -108));
        outlined(g, new RoundRectangle2D.Double(-10, -152, 20, 22, 8, 8), SKIN_B);
        outlined(g, new Ellipse2D.Double(-34, -204, 68, 72), SKIN_B);
        // tóc ngắn lòi dưới mũ
        outlined(g, new Ellipse2D.Double(-36, -190, 14, 26), HAIR);
        outlined(g, new Ellipse2D.Double(22, -190, 14, 26), HAIR);
        face(g, -166, true);
        // mũ
        Path2D cap = new Path2D.Double();
        cap.moveTo(-37, -178); cap.curveTo(-40, -226, 40, -226, 37, -178); cap.closePath();
        outlined(g, cap, Theme.coral());
        outlined(g, new RoundRectangle2D.Double(-2, -186, 58, 14, 14, 14), new Color(0xF2709C));
        g.setColor(INK); g.fill(new Ellipse2D.Double(-6, -222, 8, 8));
        switch (pose) {
            case PHONE -> {
                limb(g, -48, -126, -60, -62, hood, 22); hand(g, -60, -58, SKIN_B);
                limb(g, 48, -126, 56, -92, hood, 22);
                outlined(g, new RoundRectangle2D.Double(44, -138, 32, 54, 8, 8), new Color(0x1B2B28));
                g.setColor(new Color(0xFF9EC4));
                g.fill(new RoundRectangle2D.Double(48, -132, 24, 36, 4, 4));
                g.setColor(INK); g.setStroke(stroke(2.4f));
                g.draw(new Line2D.Double(52, -122, 68, -122)); g.draw(new Line2D.Double(52, -114, 64, -114));
                hand(g, 52, -92, SKIN_B);
            }
            case THINK -> {
                limb(g, -48, -126, -60, -62, hood, 22); hand(g, -60, -58, SKIN_B);
                limb(g, 48, -126, 56, -172, hood, 22); hand(g, 44, -192, SKIN_B);
            }
            case THUMBS -> {
                limb(g, -48, -126, -60, -62, hood, 22); hand(g, -60, -58, SKIN_B);
                limb(g, 48, -126, 74, -150, hood, 22);
                outlined(g, new RoundRectangle2D.Double(66, -178, 14, 28, 8, 8), SKIN_B);
                hand(g, 76, -146, SKIN_B);
            }
            case WAVE -> {
                limb(g, -48, -126, -60, -62, hood, 22); hand(g, -60, -58, SKIN_B);
                limb(g, 48, -126, 82, -178, hood, 22); hand(g, 84, -184, SKIN_B);
            }
            default -> {
                limb(g, -48, -126, -60, -62, hood, 22); hand(g, -60, -58, SKIN_B);
                limb(g, 48, -126, 60, -62, hood, 22); hand(g, 60, -58, SKIN_B);
            }
        }
        g.dispose();
    }

    // =====================================================================
    // Cảnh minh hoạ (component có hiệu ứng nhấp nhô nhẹ)
    // =====================================================================

    public enum Scene { SEARCH, DISCOVER, QUIZ, EMPTY, SIDEBAR, WELCOME }

    public static class SceneView extends JComponent {
        private final Scene scene;
        private final Timer timer;
        private final long t0 = System.nanoTime();

        public SceneView(Scene scene, int w, int h) {
            this.scene = scene;
            setPreferredSize(new Dimension(w, h));
            setOpaque(false);
            timer = new Timer(45, e -> { if (isShowing()) repaint(); });
        }

        @Override public void addNotify() { super.addNotify(); timer.start(); }
        @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            double t = (System.nanoTime() - t0) / 1e9;
            int w = getWidth(), h = getHeight();
            // thiết kế trên khung 460x300 rồi co giãn
            double dw = scene == Scene.SIDEBAR ? 240 : scene == Scene.QUIZ ? 330 : 460;
            double dh = scene == Scene.SIDEBAR ? 290 : 300;
            double s = Math.min(w / dw, h / dh);
            g.translate((w - dw * s) / 2, (h - dh * s) / 2);
            g.scale(s, s);
            draw(g, t);
            g.dispose();
        }

        private void blob(Graphics2D g, Color c, double cx, double cy, double rx, double ry) {
            Path2D p = new Path2D.Double();
            for (int i = 0; i <= 48; i++) {
                double a = Math.PI * 2 * i / 48, wob = 1 + 0.06 * Math.sin(a * 3 + 1) + 0.04 * Math.cos(a * 5);
                double px = cx + Math.cos(a) * rx * wob, py = cy + Math.sin(a) * ry * wob;
                if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            p.closePath();
            g.setColor(c);
            g.fill(p);
            g.setColor(Theme.ink());
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 9f}, 0f));
            g.draw(p);
        }

        private void sparkles(Graphics2D g, double t, double[][] pts) {
            for (int i = 0; i < pts.length; i++) {
                double sc = 0.75 + 0.25 * Math.sin(t * 2.4 + i * 1.7);
                Shape sp = AffineTransform.getTranslateInstance(pts[i][0], pts[i][1]).createTransformedShape(sparkle(0, 0, pts[i][2] * sc, pts[i][2] * 0.28 * sc));
                g.setColor(i % 2 == 0 ? Theme.butter() : new Color(0xFF9EC4));
                g.fill(sp);
                g.setColor(Theme.ink());
                g.setStroke(stroke(2f));
                g.draw(sp);
            }
        }

        private void draw(Graphics2D g, double t) {
            double bob = Math.sin(t * 2.2) * 3;
            switch (scene) {
                case SEARCH -> {
                    blob(g, Theme.mint(), 230, 160, 200, 118);
                    sparkles(g, t, new double[][]{{60, 60, 14}, {410, 78, 11}, {400, 230, 9}});
                    mia(g, 150, 276 + bob, 0.9, Pose.BOOK);
                    leo(g, 320, 276 - bob, 0.9, Pose.PHONE);
                    bubble(g, 60, 22 - bob * 0.5, "OMG!", new Color(0xFF9EC4), true, 20f);
                    bubble(g, 270, 12 + bob * 0.5, "no cap", Theme.lilac(), false, 20f);
                }
                case DISCOVER -> {
                    blob(g, Theme.sky(), 230, 160, 200, 120);
                    sparkles(g, t, new double[][]{{70, 70, 15}, {400, 60, 12}, {380, 220, 10}, {90, 230, 9}});
                    mia(g, 230, 286 + bob, 1.0, Pose.WAVE);
                    Graphics2D b = (Graphics2D) g.create();
                    b.translate(345, 62 + bob);
                    b.scale(1.5, 1.5);
                    Ellipse2D bulbShape = new Ellipse2D.Double(-14, -14, 28, 28);
                    b.setColor(Theme.butter()); b.fill(bulbShape);
                    outlined(b, new RoundRectangle2D.Double(-8, 12, 16, 12, 5, 5), Color.WHITE);
                    b.setColor(INK); b.setStroke(stroke(2f)); b.draw(bulbShape);
                    for (int a = -70; a <= 70; a += 35) {
                        double r = Math.toRadians(a - 90);
                        b.draw(new Line2D.Double(Math.cos(r) * 20, Math.sin(r) * 20, Math.cos(r) * 28, Math.sin(r) * 28));
                    }
                    b.dispose();
                }
                case QUIZ -> {
                    blob(g, Theme.lilac(), 165, 165, 150, 120);
                    sparkles(g, t, new double[][]{{30, 70, 12}, {305, 200, 10}});
                    leo(g, 165, 286 + bob, 0.95, Pose.THINK);
                    g.setFont(Theme.display(54f));
                    g.setColor(INK);
                    g.drawString("?", 52, (float) (110 + Math.sin(t * 3) * 6));
                    g.setColor(Theme.coral());
                    g.setFont(Theme.display(40f));
                    g.drawString("?", 262, (float) (90 + Math.cos(t * 3) * 6));
                    g.setColor(Theme.butter());
                    g.setFont(Theme.display(30f));
                    g.drawString("?", 280, (float) (150 + Math.sin(t * 2 + 1) * 5));
                }
                case EMPTY -> {
                    blob(g, Theme.mint(), 230, 165, 190, 112);
                    sparkles(g, t, new double[][]{{80, 70, 12}, {400, 70, 10}});
                    mia(g, 190, 280 + bob, 0.95, Pose.MAGNIFY);
                    bubble(g, 290, 40 - bob * 0.5, "hmm…", Theme.butter(), false, 20f);
                }
                case SIDEBAR -> {
                    // chồng sách + Mia vẫy tay
                    double[][] books = {{34, 262, 180, 26, 0xFF8FB1}, {48, 238, 156, 24, 0xFFE08A}, {30, 216, 170, 22, 0xA8D4FF}};
                    for (double[] b : books) {
                        outlined(g, new RoundRectangle2D.Double(b[0], b[1], b[2], b[3], 8, 8), new Color((int) b[4]));
                        g.setColor(INK); g.setStroke(stroke(2.4f));
                        g.draw(new Line2D.Double(b[0] + 14, b[1] + b[3] / 2, b[0] + 48, b[1] + b[3] / 2));
                    }
                    mia(g, 120, 216 + bob, 0.85, Pose.WAVE);
                }
                case WELCOME -> {
                    blob(g, Theme.mint(), 230, 160, 210, 125);
                    sparkles(g, t, new double[][]{{50, 70, 14}, {420, 60, 12}, {405, 235, 10}, {70, 240, 9}});
                    mia(g, 160, 278 + bob, 0.9, Pose.BOOK);
                    leo(g, 315, 278 - bob, 0.9, Pose.THUMBS);
                }
            }
        }
    }
}
