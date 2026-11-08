package dictionary.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

/** Các widget tuỳ biến theo phong cách sticker: viền mực dày, bóng cứng, nút nhún khi bấm. */
public final class Ui {
    private Ui() { }

    public static final int SHADOW = 4;

    public static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------- panel: thẻ sticker (viền mực + bóng cứng) hoặc nền phẳng ----------
    public static class RoundPanel extends JPanel {
        private final Supplier<Color> fill;
        private final int radius;
        private final boolean outline;
        private final int shadow;

        /** outline=true → thẻ sticker có bóng; outline=false → nền phẳng không viền. */
        public RoundPanel(Supplier<Color> fill, int radius, boolean outline) {
            this.fill = fill; this.radius = radius; this.outline = outline;
            this.shadow = outline && radius > 0 ? SHADOW : 0;
            setOpaque(false);
            setBorder(null);
        }
        public RoundPanel(Supplier<Color> fill, int radius) { this(fill, radius, true); }

        /** Chừa chỗ cho bóng ở phải/dưới để nội dung không đè lên. */
        @Override public void setBorder(javax.swing.border.Border b) {
            if (shadow == 0 || b == null && false) { super.setBorder(b); return; }
            javax.swing.border.Border pad = BorderFactory.createEmptyBorder(0, 0, shadow, shadow);
            super.setBorder(b == null ? pad : BorderFactory.createCompoundBorder(pad, b));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            float w = getWidth() - 1 - shadow, h = getHeight() - 1 - shadow;
            RoundRectangle2D.Float face = new RoundRectangle2D.Float(1, 1, w - 1, h - 1, radius, radius);
            if (outline) {
                Art.sticker(g, face, fill.get(), 2.4f, shadow);
            } else {
                g.setColor(fill.get());
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), radius, radius));
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Nền giấy hồng có họa tiết doodle. */
    public static class Paper extends JPanel {
        public Paper() { setOpaque(true); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            Art.paintPaper(g, getWidth(), getHeight(), Theme.bg(), Theme.doodle(), Theme.isDark() ? 34 : 46);
            g.dispose();
        }
    }

    // ---------- nút ----------
    public enum Kind { PRIMARY, GOLD, GHOST, DANGER }

    public static class Pill extends JButton {
        private final Kind kind;
        private Art.Icon icon;
        private boolean hover, press;

        public Pill(String text, Kind kind) { this(text, kind, null); }

        public Pill(String text, Kind kind, Art.Icon icon) {
            super(text);
            this.kind = kind;
            this.icon = icon;
            setFont(Theme.heavy(14f));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; press = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { press = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { press = false; repaint(); }
            });
        }

        public void setIcon(Art.Icon icon) { this.icon = icon; repaint(); }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(getText()) + 44 + (icon != null ? 24 : 0);
            return new Dimension(w + 3, 44);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            Color bg, fg;
            switch (kind) {
                case GOLD -> { bg = Theme.gold(); fg = Theme.onGold(); }
                case GHOST -> { bg = Theme.card(); fg = Theme.text(); }
                case DANGER -> { bg = Theme.bad(); fg = Color.WHITE; }
                default -> { bg = Theme.accent(); fg = Theme.onAccent(); }
            }
            boolean on = isEnabled();
            if (!on) { bg = Theme.border(); fg = Theme.muted(); }
            int sh = !on ? 0 : press ? 1 : hover ? 4 : 3;
            int off = !on ? 3 : press ? 2 : hover ? -1 : 0;
            int w = getWidth() - 4, h = getHeight() - 4;
            Graphics2D b = (Graphics2D) g.create();
            b.translate(off, off);
            RoundRectangle2D.Float face = new RoundRectangle2D.Float(1, 1, w - 1, h - 1, h, h);
            if (on) {
                Art.sticker(b, face, bg, 2.4f, sh);
                b.setColor(new Color(255, 255, 255, hover ? 70 : 40));
                b.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                b.drawArc(10, 5, w - 20, h - 10, 100, 70);
            } else {
                b.setColor(bg); b.fill(face);
                b.setColor(Theme.muted()); b.setStroke(new BasicStroke(1.6f)); b.draw(face);
            }
            b.setFont(getFont());
            FontMetrics fm = b.getFontMetrics();
            int iw = icon != null ? 24 : 0;
            int tw = fm.stringWidth(getText());
            int x0 = (w - tw - iw) / 2;
            if (icon != null) Art.icon(b, icon, x0 + 9, h / 2.0 + 0.5, 18, fg, 2.2f);
            b.setColor(fg);
            b.drawString(getText(), x0 + iw, (h - fm.getHeight()) / 2 + fm.getAscent());
            b.dispose();
            if (isFocusOwner()) {
                g.setColor(Theme.ink());
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f));
                g.draw(new RoundRectangle2D.Float(4, 4, getWidth() - 12, getHeight() - 12, getHeight() - 8, getHeight() - 8));
            }
            g.dispose();
        }
    }

    // ---------- ô nhập liệu bo tròn (có icon kính lúp tuỳ chọn) ----------
    public static class RoundField extends JTextField {
        private final String placeholder;
        private boolean searchIcon;

        public RoundField(String placeholder) {
            this.placeholder = placeholder;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
            setFont(Theme.semi(16f));
            setForeground(Theme.text());
            setCaretColor(Theme.ink());
            setSelectionColor(Theme.gold());
            setSelectedTextColor(Theme.ink());
        }

        public RoundField withSearchIcon() {
            searchIcon = true;
            setBorder(BorderFactory.createEmptyBorder(8, 50, 8, 20));
            return this;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(super.getPreferredSize().width, 48); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int h = getHeight() - 4, w = getWidth() - 4;
            RoundRectangle2D.Float face = new RoundRectangle2D.Float(1, 1, w - 1, h - 1, h, h);
            boolean f = isFocusOwner();
            if (f) {
                g.setColor(Theme.gold());
                g.setStroke(new BasicStroke(7f));
                g.draw(new RoundRectangle2D.Float(1, 1, w - 1, h - 1, h, h));
            }
            Art.sticker(g, face, Theme.inputBg(), 2.4f, f ? 0 : 3);
            if (searchIcon) Art.icon(g, Art.Icon.SEARCH, 27, h / 2.0, 22, f ? Theme.accentDark() : Theme.muted(), 2.4f);
            g.dispose();
            super.paintComponent(g0);
            if (getText().isEmpty()) {
                Graphics2D p = (Graphics2D) g0.create();
                aa(p);
                p.setColor(Theme.muted());
                p.setFont(Theme.font(Font.PLAIN, 16f));
                Insets in = getInsets();
                p.drawString(placeholder, in.left, (h - p.getFontMetrics().getHeight()) / 2 + p.getFontMetrics().getAscent());
                p.dispose();
            }
        }
    }

    // ---------- chuyển đổi 2 chế độ (segmented) ----------
    public static class Segmented extends JPanel {
        private final String[] labels;
        private int selected = 0;
        private final java.util.List<Runnable> listeners = new java.util.ArrayList<>();

        public Segmented(String... labels) {
            this.labels = labels;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int w = (getWidth() - 4) / Segmented.this.labels.length;
                    setSelected(Math.min(Segmented.this.labels.length - 1, e.getX() / Math.max(1, w)));
                }
            });
        }
        public void onChange(Runnable r) { listeners.add(r); }
        public int getSelected() { return selected; }
        public void setSelected(int i) {
            if (i == selected) return;
            selected = i; repaint();
            listeners.forEach(Runnable::run);
        }
        public void setSelectedSilently(int i) { selected = i; repaint(); }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.heavy(14f));
            int w = 0;
            for (String l : labels) w = Math.max(w, fm.stringWidth(l) + 44);
            return new Dimension(w * labels.length + 4, 44);
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int h = getHeight() - 4, w = getWidth() - 4, n = labels.length;
            Art.sticker(g, new RoundRectangle2D.Float(1, 1, w - 1, h - 1, h, h), Theme.card(), 2.4f, 3);
            int sw = (w - 8) / n;
            Art.sticker(g, new RoundRectangle2D.Float(5 + selected * sw, 5, sw - 1, h - 9, h, h), Theme.gold(), 2f, 0);
            g.setFont(Theme.heavy(14f));
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < n; i++) {
                g.setColor(i == selected ? Theme.onGold() : Theme.muted());
                g.drawString(labels[i], 5 + i * sw + (sw - fm.stringWidth(labels[i])) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            g.dispose();
        }
    }

    // ---------- chip gợi ý (bấm được) ----------
    public static class Chip extends JButton {
        private boolean hover;
        private final int tone;

        public Chip(String text, int tone) {
            super(text);
            this.tone = tone;
            setFont(Theme.heavy(13f));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        @Override public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 32, 34);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int off = hover ? -1 : 0;
            g.translate(off, off);
            Art.sticker(g, new RoundRectangle2D.Float(1, 1, getWidth() - 6, getHeight() - 6, getHeight(), getHeight()),
                    Theme.rainbow(tone), 2f, hover ? 3 : 2);
            g.setFont(getFont());
            g.setColor(Theme.isDark() ? Color.BLACK : Theme.ink());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (getWidth() - 3 - fm.stringWidth(getText())) / 2, (getHeight() - 3 - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Huy hiệu tròn có chữ cái đầu (dùng ở danh sách kết quả). */
    public static void badge(Graphics2D g, int x, int y, int d, String letter, Color fill) {
        Art.sticker(g, new java.awt.geom.Ellipse2D.Float(x, y, d, d), fill, 2.2f, 0);
        g.setFont(Theme.display(d * 0.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Theme.isDark() ? Color.BLACK : Theme.ink());
        g.drawString(letter, x + (d - fm.stringWidth(letter)) / 2f, y + (d - fm.getHeight()) / 2f + fm.getAscent());
    }

    // ---------- thanh cuộn mỏng ----------
    public static void slimScroll(JScrollPane sp) {
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setOpaque(true);
        sp.getViewport().setBackground(Theme.card());
        sp.getVerticalScrollBar().setUI(new SlimBar());
        sp.getHorizontalScrollBar().setUI(new SlimBar());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
        sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 12));
        sp.getVerticalScrollBar().setOpaque(false);
        sp.getHorizontalScrollBar().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.setOpaque(false);
    }

    static class SlimBar extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(Theme.gold());
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 10, 10);
            g.setColor(Theme.ink());
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(r.x + 2, r.y + 2, r.width - 5, r.height - 5, 10, 10);
            g.dispose();
        }
    }

    // ---------- nhãn tiện ích ----------
    public static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(style, size));
        l.setForeground(color);
        return l;
    }

    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.display(30f));
        l.setForeground(Theme.text());
        return l;
    }

    public static Border pad(int t, int l, int b, int r) { return new Border(t, l, b, r); }

    /** Viết gọn cho BorderFactory.createEmptyBorder. */
    public static final class Border extends javax.swing.border.EmptyBorder {
        Border(int t, int l, int b, int r) { super(t, l, b, r); }
    }

    /** Viền đường chấm (nét "đứt" như vở kẻ) quanh một vùng. */
    public static class DashedBorder extends AbstractBorder {
        private final Supplier<Color> color;
        public DashedBorder(Supplier<Color> color) { this.color = color; }
        @Override public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(color.get());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 7f}, 0f));
            g.drawRoundRect(x + 1, y + 1, w - 3, h - 3, 18, 18);
            g.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(4, 4, 4, 4); }
    }

    // ---------- bảng ----------
    public static void styleTable(JTable t) {
        t.setRowHeight(40);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFont(Theme.font(Font.PLAIN, 14f));
        t.setForeground(Theme.text());
        t.setBackground(Theme.card());
        t.setSelectionBackground(Theme.creamDeep());
        t.setSelectionForeground(Theme.text());
        t.setFillsViewportHeight(true);
        t.setBorder(BorderFactory.createEmptyBorder());
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(0, 42));
        t.getTableHeader().setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setFont(Theme.heavy(13f));
                l.setForeground(Theme.onAccent());
                l.setBackground(Theme.accent());
                l.setOpaque(true);
                l.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 2, 0, Theme.ink()),
                        BorderFactory.createEmptyBorder(0, 14, 0, 12)));
                return l;
            }
        });
        t.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, sel, false, r, c);
                l.setFont(Theme.font(Font.PLAIN, 14f));
                l.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 12));
                l.setBackground(sel ? Theme.creamDeep() : (r % 2 == 0 ? Theme.card() : Theme.bg()));
                l.setForeground(Theme.text());
                l.setToolTipText(v == null ? null : v.toString());
                return l;
            }
        });
    }
}
