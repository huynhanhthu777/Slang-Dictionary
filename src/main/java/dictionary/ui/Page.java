package dictionary.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Lớp cơ sở cho mỗi màn hình trong CardLayout. */
public abstract class Page extends JPanel {
    protected final MainFrame app;

    protected Page(MainFrame app) {
        super();
        this.app = app;
        setOpaque(false);
        setLayout(new BorderLayout(0, 18));
        setBorder(BorderFactory.createEmptyBorder(22, 30, 12, 30));
    }

    /** Được gọi mỗi khi màn hình này được hiển thị. */
    public void onShow() { }

    protected JComponent header(String title, String subtitle, Art.Icon icon, int tone) {
        return new Header(title, subtitle, icon, tone);
    }

    /** Tiêu đề trang: ô icon sticker + chữ Fredoka + nét gạch chân uốn lượn màu hồng. */
    static final class Header extends JComponent {
        private final String title, subtitle;
        private final Art.Icon icon;
        private final int tone;

        Header(String title, String subtitle, Art.Icon icon, int tone) {
            this.title = title; this.subtitle = subtitle; this.icon = icon; this.tone = tone;
            setOpaque(false);
            setPreferredSize(new Dimension(300, 74));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            Art.sticker(g, new RoundRectangle2D.Float(1, 4, 54, 54, 20, 20), Theme.rainbow(tone), 2.4f, 4);
            Art.icon(g, icon, 28, 31, 28, Theme.isDark() ? Color.BLACK : Theme.ink(), 2.6f);
            g.setFont(Theme.display(30f));
            g.setColor(Theme.text());
            FontMetrics fm = g.getFontMetrics();
            int x = 74, base = 4 + fm.getAscent() - 4;
            // gạch chân uốn lượn
            int tw = fm.stringWidth(title);
            Path2D p = new Path2D.Float();
            p.moveTo(x, base + 9);
            for (int i = 0; i < tw / 16 + 1; i++) p.quadTo(x + i * 16 + 4, base + (i % 2 == 0 ? 14 : 4), x + i * 16 + 8, base + 9);
            g.setClip(x - 2, 0, tw + 6, getHeight());
            g.setColor(Theme.gold());
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
            g.setClip(null);
            g.setColor(Theme.text());
            g.drawString(title, x, base);
            g.setFont(Theme.font(Font.PLAIN, 14f));
            g.setColor(Theme.muted());
            g.drawString(subtitle, x, base + 30);
            g.dispose();
        }
    }
}
