package dictionary.ui;

import dictionary.model.SearchType;
import dictionary.service.DictionaryService;
import dictionary.service.QuizService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cửa sổ chính: sidebar điều hướng + các màn hình + thanh thông báo. */
public class MainFrame extends JFrame {
    public static final String SEARCH = "Search", MANAGE = "Manage", HISTORY = "History",
            DISCOVER = "Discover", QUIZ = "Quiz", SETTINGS = "Settings";

    private final DictionaryService service;
    private QuizService quizService;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, NavButton> navButtons = new LinkedHashMap<>();
    private final JLabel countLabel = new JLabel();
    private final JLabel toast = new JLabel(" ");
    private Timer toastTimer;
    private String current = SEARCH;
    private boolean ready = false;

    public MainFrame(DictionaryService service) {
        super("Slang Dictionary");
        this.service = service;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        // Đóng cửa sổ khi đang lưu: chờ việc ghi hoàn tất rồi mới thoát (EXIT_ON_CLOSE chạy SAU các listener này).
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                Tasks.shutdownAndWait();
                if (!service.flushHistory()) {
                    // Đang đóng app nên không mở hộp thoại chặn; ghi rõ ra stderr rằng history CHƯA được lưu.
                    var err = service.historySaveError();
                    System.err.println("Search history could not be saved: " + (err == null ? "unknown error" : err.getMessage()));
                }
            }
        });
        setMinimumSize(new Dimension(1000, 680));
        setSize(1200, 780);
        setLocationRelativeTo(null);
        showLoading();
    }

    public DictionaryService service() { return service; }
    public QuizService quiz() { return quizService; }

    // ---------- khởi động ----------

    private void showLoading() {
        Ui.Paper p = new Ui.Paper();
        p.setLayout(new GridBagLayout());
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        Art.SceneView scene = new Art.SceneView(Art.Scene.WELCOME, 460, 300);
        JLabel t = Ui.label("Slang Dictionary", Font.BOLD, 34f, Theme.text());
        t.setFont(Theme.display(38f));
        JLabel s = Ui.label("Opening the dictionary…", Font.PLAIN, 15f, Theme.muted());
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setBorderPainted(false);
        bar.setForeground(Theme.gold());
        bar.setBackground(Theme.cream());
        bar.setPreferredSize(new Dimension(260, 10));
        scene.setAlignmentX(CENTER_ALIGNMENT); t.setAlignmentX(CENTER_ALIGNMENT); s.setAlignmentX(CENTER_ALIGNMENT); bar.setAlignmentX(CENTER_ALIGNMENT);
        box.add(scene); box.add(t); box.add(Box.createVerticalStrut(6)); box.add(s); box.add(Box.createVerticalStrut(16)); box.add(bar);
        p.add(box);
        setContentPane(p);
    }

    public void start() {
        Tasks.run(service::load, this::onLoaded, this::onLoadFailed);
    }

    private void onLoaded(DictionaryService.LoadReport report) {
        quizService = new QuizService(service.repository());
        ready = true;
        service.setHistoryErrorListener(err -> SwingUtilities.invokeLater(
                () -> toast("Could not save search history: " + err.getMessage(), true)));
        buildUi();
        show(SEARCH);
        if (report.warning() != null) toast(report.warning(), true);
    }

    private void onLoadFailed(Throwable err) {
        if (err instanceof dictionary.service.DataCorruptedException) {
            // Không bao giờ tự reset: chỉ làm khi người dùng đồng ý, và file hỏng được dời đi chứ không xóa.
            int choice = JOptionPane.showOptionDialog(this,
                    "Your saved dictionary and its backup cannot be read:\n\n" + Tasks.message(err)
                            + "\n\nNothing has been deleted. You can start again from the original dictionary; the damaged files\n"
                            + "will be moved to a separate folder (data/working-damaged-…) so you can recover them manually.",
                    "Slang Dictionary", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    new String[]{"Start again from original data", "Quit (keep files as they are)"}, "Quit (keep files as they are)");
            if (choice == 0) {
                Tasks.write(service::quarantineDamagedDataAndReload, this::onLoaded, e2 -> {
                    JOptionPane.showMessageDialog(this, "Could not start again:\n" + Tasks.message(e2), "Slang Dictionary", JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                });
                return;
            }
            System.exit(1);
        }
        JOptionPane.showMessageDialog(this, "Cannot load the dictionary:\n" + Tasks.message(err)
                + "\n\nMake sure data/original/slang.raw.txt exists next to the application (or run it from the folder that contains the data folder).",
                "Slang Dictionary", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    // ---------- dựng giao diện ----------

    private void buildUi() {
        pages.clear();
        navButtons.clear();
        cardHost.removeAll();
        cardHost.setOpaque(false);

        pages.put(SEARCH, new SearchPage(this));
        pages.put(MANAGE, new ManagePage(this));
        pages.put(HISTORY, new HistoryPage(this));
        pages.put(DISCOVER, new DiscoverPage(this));
        pages.put(QUIZ, new QuizPage(this));
        pages.put(SETTINGS, new SettingsPage(this));
        pages.forEach((k, v) -> cardHost.add(v, k));

        Ui.Paper root = new Ui.Paper();
        root.setLayout(new BorderLayout());
        root.add(buildSidebar(), BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(cardHost, BorderLayout.CENTER);
        toast.setFont(Theme.heavy(13f));
        toast.setBorder(BorderFactory.createEmptyBorder(4, 34, 12, 30));
        toast.setForeground(Theme.muted());
        center.add(toast, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        setContentPane(root);
        setJMenuBar(buildMenuBar());
        installShortcuts(root);
        updateCount();
        root.revalidate();
        root.repaint();
    }

    /** Thanh bên xanh dương pastel, có họa tiết doodle mờ và đường viền mực bên phải. */
    private static final class Sidebar extends JPanel {
        Sidebar() { setOpaque(true); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            Art.paintPaper(g, getWidth(), getHeight(), Theme.sidebar(), Theme.isDark() ? new Color(0xFFB3D1) : Color.WHITE, Theme.isDark() ? 26 : 85);
            g.setColor(Theme.ink());
            g.fillRect(getWidth() - 3, 0, 3, getHeight());
            g.dispose();
        }
    }

    /** Logo: "Slang" + nhãn "Dictionary" dạng sticker hồng nghiêng nhẹ. */
    private static final class Brand extends JComponent {
        Brand() { setOpaque(false); setPreferredSize(new Dimension(210, 96)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 96)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            g.setFont(Theme.display(40f));
            g.setColor(Theme.ink());
            g.drawString("Slang", 4, 40);
            Graphics2D t = (Graphics2D) g.create();
            t.translate(4, 52);
            t.rotate(Math.toRadians(-3));
            g.setFont(Theme.display(24f));
            int w = g.getFontMetrics().stringWidth("Dictionary") + 26;
            Art.sticker(t, new java.awt.geom.RoundRectangle2D.Float(0, 0, w, 36, 18, 18), Theme.gold(), 2.4f, 3);
            t.setFont(Theme.display(24f));
            t.setColor(Theme.onGold());
            t.drawString("Dictionary", 13, 27);
            t.dispose();
            Art.sparkles(g, 168, 18, 9);
            g.dispose();
        }
    }

    private JComponent buildSidebar() {
        JPanel side = new Sidebar();
        side.setLayout(new BorderLayout());
        side.setPreferredSize(new Dimension(250, 0));
        side.setBorder(BorderFactory.createEmptyBorder(26, 18, 16, 20));

        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        Brand brand = new Brand();
        brand.setAlignmentX(LEFT_ALIGNMENT);
        nav.add(brand);
        nav.add(Box.createVerticalStrut(18));
        Object[][] items = {{SEARCH, "Search", Art.Icon.SEARCH}, {MANAGE, "Manage words", Art.Icon.BOOKS},
                {HISTORY, "History", Art.Icon.CLOCK}, {DISCOVER, "Discover", Art.Icon.SPARK},
                {QUIZ, "Quiz", Art.Icon.QUIZ}, {SETTINGS, "Settings", Art.Icon.GEAR}};
        for (Object[] it : items) {
            NavButton b = new NavButton((String) it[1], (Art.Icon) it[2]);
            b.addActionListener(e -> show((String) it[0]));
            b.setAlignmentX(LEFT_ALIGNMENT);
            navButtons.put((String) it[0], b);
            nav.add(b);
            nav.add(Box.createVerticalStrut(8));
        }
        side.add(nav, BorderLayout.NORTH);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        Art.SceneView mascot = new Art.SceneView(Art.Scene.SIDEBAR, 210, 170);
        mascot.setAlignmentX(LEFT_ALIGNMENT);
        bottom.add(mascot);
        Ui.RoundPanel stats = new Ui.RoundPanel(Theme::card, 18);
        stats.setLayout(new BorderLayout());
        stats.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 10));
        countLabel.setFont(Theme.font(Font.PLAIN, 12f));
        countLabel.setForeground(Theme.text());
        stats.add(countLabel, BorderLayout.CENTER);
        stats.setAlignmentX(LEFT_ALIGNMENT);
        bottom.add(stats);
        side.add(bottom, BorderLayout.SOUTH);
        // cửa sổ thấp thì ẩn mascot để không đè lên menu
        side.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { mascot.setVisible(side.getHeight() >= 735); }
        });
        return side;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenu go = new JMenu("Go");
        String[] names = {SEARCH, MANAGE, HISTORY, DISCOVER, QUIZ, SETTINGS};
        for (int i = 0; i < names.length; i++) {
            final String page = names[i];
            JMenuItem mi = new JMenuItem(page);
            mi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, mask));
            mi.addActionListener(e -> show(page));
            go.add(mi);
        }
        JMenu file = new JMenu("File");
        JMenuItem find = new JMenuItem("Find word…");
        find.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, mask));
        find.addActionListener(e -> focusSearch());
        JMenuItem dark = new JMenuItem("Toggle dark mode");
        dark.addActionListener(e -> toggleTheme());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        file.add(find); file.add(dark); file.addSeparator(); file.add(exit);
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> Dialogs.info(this, "Slang Dictionary",
                "Search, manage and practise English slang.\nPure Java Swing, data stored in local files.\n\n"
                        + "Shortcuts: Ctrl/Cmd+K find, Ctrl/Cmd+1..6 switch screens."));
        help.add(about);
        bar.add(file); bar.add(go); bar.add(help);
        return bar;
    }

    private void installShortcuts(JComponent root) {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_K, mask), "find");
        root.getActionMap().put("find", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { focusSearch(); }
        });
    }

    // ---------- điều hướng ----------

    public void show(String page) {
        if (!ready) return;
        current = page;
        cards.show(cardHost, page);
        navButtons.forEach((k, b) -> b.setActive(k.equals(page)));
        pages.get(page).onShow();
        updateCount();
    }

    public void focusSearch() {
        show(SEARCH);
        ((SearchPage) pages.get(SEARCH)).focusField();
    }

    /** Dùng khi bấm "Search again" trong History hoặc "Look up" trong Discover. */
    public void searchFor(SearchType type, String query) {
        show(SEARCH);
        ((SearchPage) pages.get(SEARCH)).searchFor(type, query);
    }

    public void updateCount() {
        countLabel.setText("<html><span style='font-family:" + Theme.displayFamily() + "; font-size:17px; font-weight:bold'>"
                + String.format("%,d", service.repository().size()) + "</span> entries<br>"
                + String.format("%,d", service.repository().distinctSlangCount()) + " different words</html>");
    }

    public void toggleTheme() {
        Theme.setDark(!Theme.isDark());
        String keep = current;
        buildUi();
        show(keep);
    }

    public void toast(String message, boolean error) {
        toast.setForeground(error ? Theme.bad() : Theme.accentDark());
        toast.setText(message);
        if (toastTimer != null) toastTimer.stop();
        toastTimer = new Timer(5000, e -> toast.setText(" "));
        toastTimer.setRepeats(false);
        toastTimer.start();
    }

    public void toast(String message) { toast(message, false); }

    // ---------- nút điều hướng ----------

    static final class NavButton extends JButton {
        private boolean active, hover;
        private final Art.Icon icon;

        NavButton(String text, Art.Icon icon) {
            super(text);
            this.icon = icon;
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setHorizontalAlignment(LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
            setPreferredSize(new Dimension(210, 50));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        void setActive(boolean a) { active = a; repaint(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            int w = getWidth() - 5, h = getHeight() - 5;
            java.awt.geom.RoundRectangle2D.Float face = new java.awt.geom.RoundRectangle2D.Float(1, 1, w - 1, h - 1, h, h);
            if (active) {
                Art.sticker(g, face, Theme.sidebarHi(), 2.4f, 4);
            } else if (hover) {
                g.setColor(new Color(255, 255, 255, Theme.isDark() ? 28 : 130));
                g.fill(face);
            }
            // ô icon tròn
            int d = 30, cy = (h - d) / 2;
            if (active) Art.sticker(g, new java.awt.geom.Ellipse2D.Float(9, cy, d, d), Theme.gold(), 2f, 0);
            Art.icon(g, icon, 9 + d / 2.0, cy + d / 2.0, 17, active ? Theme.onGold() : Theme.sidebarText(), 2.2f);
            g.setFont(Theme.heavy(15f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(active ? Theme.text() : Theme.sidebarText());
            g.drawString(getText(), 50, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }
}
