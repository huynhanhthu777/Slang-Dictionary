package dictionary.ui;

import dictionary.service.Benchmark;
import dictionary.service.DictionaryService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Giao diện sáng/tối, thông tin dữ liệu, khôi phục dữ liệu gốc, đo hiệu năng. */
public class SettingsPage extends Page {
    private final JLabel stats = new JLabel();

    public SettingsPage(MainFrame app) {
        super(app);
        add(header("Settings", "Appearance, data and maintenance.", Art.Icon.GEAR, 4), BorderLayout.NORTH);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        // giao diện
        Ui.Pill themeBtn = new Ui.Pill(Theme.isDark() ? "Switch to light theme" : "Switch to dark theme", Ui.Kind.GOLD, Theme.isDark() ? Art.Icon.SUN : Art.Icon.MOON);
        themeBtn.addActionListener(e -> app.toggleTheme());
        col.add(section("Appearance", "Choose the colour theme of the application.", buttonRow(themeBtn)));
        col.add(Box.createVerticalStrut(14));

        // dữ liệu
        stats.setFont(Theme.font(Font.PLAIN, 13f));
        stats.setForeground(Theme.text());
        Ui.Pill report = new Ui.Pill("View data report", Ui.Kind.GHOST);
        report.addActionListener(e -> showReport());
        JPanel dataBody = new JPanel(new BorderLayout(0, 10));
        dataBody.setOpaque(false);
        dataBody.add(stats, BorderLayout.CENTER);
        dataBody.add(buttonRow(report), BorderLayout.SOUTH);
        col.add(section("Data", "Where your dictionary comes from and how it was loaded.", dataBody));
        col.add(Box.createVerticalStrut(14));

        // bảo trì
        Ui.Pill reset = new Ui.Pill("Reset to original data", Ui.Kind.DANGER, Art.Icon.TRASH);
        reset.addActionListener(e -> reset());
        Ui.Pill bench = new Ui.Pill("Run 100,000-entry benchmark (3 rounds)", Ui.Kind.GHOST, Art.Icon.BOLT);
        bench.addActionListener(e -> benchmark(bench));
        JComponent mb = buttonRow(reset, bench);
        col.add(section("Maintenance", "Reset throws away your changes and rebuilds the dictionary from the normalized original file (slang.raw.txt is never modified). "
                + "The benchmark uses generated data and never touches yours.", mb));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(col, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrapper);
        Ui.slimScroll(sp);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getViewport().setOpaque(false);
        sp.getViewport().setBackground(Theme.bg());
        add(sp, BorderLayout.CENTER);
        onShow();
    }

    private static JComponent buttonRow(JComponent... buttons) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(10));
            p.add(buttons[i]);
        }
        p.add(Box.createHorizontalGlue());
        return p;
    }

    private JComponent section(String title, String subtitle, JComponent body) {
        Ui.RoundPanel p = new Ui.RoundPanel(Theme::card, 26);
        p.setLayout(new BorderLayout(0, 12));
        p.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel t = Ui.label(title, Font.BOLD, 20f, Theme.accentDark());
        t.setFont(Theme.display(21f));
        JLabel s = new JLabel("<html><div style='width:620px'>" + Ui.esc(subtitle) + "</div></html>");
        s.setFont(Theme.font(Font.PLAIN, 13f));
        s.setForeground(Theme.muted());
        t.setAlignmentX(LEFT_ALIGNMENT); s.setAlignmentX(LEFT_ALIGNMENT);
        head.add(t); head.add(Box.createVerticalStrut(3)); head.add(s);
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    @Override public void onShow() {
        DictionaryService.LoadReport r = app.service().lastReport();
        var st = app.service().storage();
        StringBuilder sb = new StringBuilder("<html><table cellspacing='4'>");
        row(sb, "Entries", String.format("%,d (%,d different words)", app.service().repository().size(), app.service().repository().distinctSlangCount()));
        if (r != null) {
            row(sb, "Loaded from", r.sourceLabel());
            row(sb, "Start-up time", r.loadMillis() + " ms  (read " + r.readMillis() + " ms, index " + r.indexMillis() + " ms"
                    + (r.indexReused() ? ", saved index reused)" : ")"));
            row(sb, "Data version ID", String.valueOf(app.service().dataVersion()) + "   (shared by snapshot, backup and working text)");
            row(sb, "Source lines repaired", r.repaired() + "   (listed in the data report)");
            row(sb, "Source lines set aside", String.valueOf(r.quarantined()));
        }
        row(sb, "Normalized original", shorten(st.normalizedFile().toAbsolutePath().normalize().toString()));
        row(sb, "Working data", shorten(st.snapshotFile().toAbsolutePath().normalize().toString()));
        sb.append("</table></html>");
        stats.setText(sb.toString());
        revalidate();
        repaint();
    }

    /** Đường dẫn dài được rút gọn ở đầu để không tràn khung. */
    private static String shorten(String p) { return p.length() <= 58 ? p : "…" + p.substring(p.length() - 57); }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><td valign='top'><b>").append(Ui.esc(k)).append("</b></td><td>").append(Ui.esc(v)).append("</td></tr>");
    }

    private void showReport() {
        List<String> lines = app.service().storage().readQualityReport();
        Dialogs.text(this, "Data report",
                lines.isEmpty() ? "No report available." : String.join("\n", lines));
    }

    private void reset() {
        if (!Dialogs.confirm(this, "Reset to original data?",
                "All words you added, edited or deleted will be lost and the dictionary will be rebuilt from the original file.\n"
                        + "Your search history is kept.", "Reset", true)) return;
        app.toast("Resetting…");
        Tasks.write(() -> { app.service().resetToOriginal(); return app.service().repository().size(); },
                n -> { app.toast("Dictionary reset: " + String.format("%,d", n) + " entries restored."); app.updateCount(); onShow(); },
                err -> app.toast("Reset failed, your data is unchanged: " + Tasks.message(err), true));
    }

    private void benchmark(Ui.Pill button) {
        button.setEnabled(false);
        app.toast("Running 3 benchmark rounds (about 30 seconds)…");
        Tasks.run(() -> Benchmark.runRepeated(100_000, 3), lines -> {
            button.setEnabled(true);
            app.toast("Benchmark finished.");
            Dialogs.text(this, "Benchmark (100,000 generated entries, temporary folder)", String.join("\n", lines));
        }, err -> { button.setEnabled(true); app.toast("Benchmark failed: " + Tasks.message(err), true); });
    }
}
