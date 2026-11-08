package dictionary.ui;

import dictionary.model.SearchResult;
import dictionary.model.SearchType;
import dictionary.model.SlangEntry;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Màn hình tìm kiếm: theo slang (chính xác + gợi ý theo prefix) hoặc theo definition (từ khoá). */
public class SearchPage extends Page {
    private final Ui.RoundField field = new Ui.RoundField("Type a slang word or a keyword…").withSearchIcon();
    private final Ui.Segmented mode = new Ui.Segmented("By slang", "By definition");
    private final Ui.Pill searchBtn = new Ui.Pill("Search", Ui.Kind.GOLD, Art.Icon.SEARCH);
    private final JLabel info = Ui.label(" ", Font.BOLD, 13f, Theme.muted());
    private final JLabel hint = Ui.label(" ", Font.ITALIC, 12f, Theme.muted());
    /** Model nạp hàng loạt: chỉ phát MỘT sự kiện cho cả danh sách (addElement từng phần tử với 40.000 kết quả làm EDT đứng gần nửa giây). */
    private static final class ResultModel extends AbstractListModel<SlangEntry> {
        private List<SlangEntry> items = List.of();
        @Override public int getSize() { return items.size(); }
        @Override public SlangEntry getElementAt(int i) { return items.get(i); }
        int size() { return items.size(); }
        void setAll(List<SlangEntry> entries) {
            int oldSize = items.size();
            items = new ArrayList<>(entries);
            if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1);
            if (!items.isEmpty()) fireIntervalAdded(this, 0, items.size() - 1);
        }
        void clear() { setAll(List.of()); }
    }

    private final ResultModel model = new ResultModel();
    private final JList<SlangEntry> list = new JList<>(model);
    private final JEditorPane detail = EntryView.createPane();
    private final JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final CardLayout rightCards = new CardLayout();
    private final JPanel rightHost = new JPanel(rightCards);
    private final Timer debounce;
    private int seq = 0;
    private String highlight = "";

    public SearchPage(MainFrame app) {
        super(app);
        add(buildHero(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        debounce = new Timer(180, e -> live());
        debounce.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        field.addActionListener(e -> commit());
        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { field.setText(""); }
                else if (e.getKeyCode() == KeyEvent.VK_DOWN && model.size() > 0) { list.requestFocusInWindow(); }
            }
        });
        searchBtn.addActionListener(e -> commit());
        mode.onChange(() -> { seq++; debounce.stop(); updateHint(); live(); if (field.getText().trim().isEmpty()) refreshChips(); });
        updateHint();
        showIdle();
    }

    private JComponent buildHero() {
        Ui.RoundPanel hero = new Ui.RoundPanel(Theme::cream, 30);
        hero.setLayout(new BorderLayout(0, 12));
        hero.setBorder(BorderFactory.createEmptyBorder(20, 28, 18, 28));

        JLabel prompt = Ui.label("What word piques your interest?", Font.BOLD, 26f, Theme.text());
        prompt.setFont(Theme.display(26f));
        JPanel row = new JPanel(new BorderLayout(14, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(searchBtn, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setOpaque(false);
        bottom.add(mode, BorderLayout.WEST);
        bottom.add(hint, BorderLayout.CENTER);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        chipRow.setOpaque(false);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        row.setAlignmentX(LEFT_ALIGNMENT);
        bottom.setAlignmentX(LEFT_ALIGNMENT);
        chipRow.setAlignmentX(LEFT_ALIGNMENT);
        col.add(row);
        col.add(Box.createVerticalStrut(14));
        col.add(bottom);
        col.add(Box.createVerticalStrut(12));
        col.add(chipRow);

        hero.add(prompt, BorderLayout.NORTH);
        hero.add(col, BorderLayout.CENTER);
        return hero;
    }

    private int chipSeq;

    /** Hàng gợi ý: "Try:" + vài từ để bấm thử. By slang -> slang ngẫu nhiên; By definition -> từ khóa phổ biến trong definition. */
    private void refreshChips() {
        final int my = ++chipSeq;
        if (app.service().repository().isEmpty()) { showChips("Try:", java.util.List.of(), "", SearchType.SLANG); return; }
        if (slangMode()) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (int i = 0; i < 60 && seen.size() < 5; i++) {
                String w = app.service().randomEntry().getSlang();
                if (w.length() >= 3 && w.length() <= 9 && w.matches("[A-Za-z]+")) seen.add(w);
            }
            showChips("Try:", new java.util.ArrayList<>(seen), "", SearchType.SLANG);
        } else {
            Tasks.run(() -> app.service().popularDefinitionWords(5), words -> {
                if (my == chipSeq && !slangMode()) showChips("Try:", words, "", SearchType.DEFINITION);
            }, err -> { });
        }
    }

    /** Khi tìm theo definition và đang gõ: gợi ý hoàn thành từ đang gõ (từ cuối), phổ biến nhất trước. */
    private void updateChips() {
        String text = field.getText();
        if (text.trim().isEmpty()) { refreshChips(); return; }
        if (slangMode()) return;
        int sp = text.lastIndexOf(' ');
        final String head = sp >= 0 ? text.substring(0, sp + 1) : "";
        final String token = text.substring(sp + 1).trim();
        if (token.length() < 2) return;
        final int my = ++chipSeq;
        Tasks.run(() -> app.service().suggestDefinitionWords(token, 5), words -> {
            if (my == chipSeq && !slangMode() && !words.isEmpty()) showChips("Keywords:", words, head, SearchType.DEFINITION);
        }, err -> { });
    }

    private void showChips(String label, java.util.List<String> words, String head, SearchType type) {
        chipRow.removeAll();
        JLabel t = Ui.label(label, Font.BOLD, 14f, Theme.accentDark());
        t.setFont(Theme.heavy(14f));
        chipRow.add(t);
        int tone = 0;
        for (String w : words) {
            Ui.Chip c = new Ui.Chip(w, tone++);
            c.addActionListener(e -> searchFor(type, head + w));
            chipRow.add(c);
        }
        chipRow.revalidate();
        chipRow.repaint();
    }

    private JComponent buildBody() {
        // danh sách kết quả
        Ui.RoundPanel left = new Ui.RoundPanel(Theme::card, 26);
        left.setLayout(new BorderLayout());
        left.setBorder(BorderFactory.createEmptyBorder(16, 14, 14, 14));
        info.setBorder(BorderFactory.createEmptyBorder(0, 8, 10, 0));
        left.add(info, BorderLayout.NORTH);

        list.setFixedCellHeight(72);
        // Có bề rộng ô cố định thì Swing KHÔNG phải gọi renderer cho từng dòng để đo (40.000 kết quả = đứng ~0,5 s).
        // Giá trị nhỏ nên danh sách vẫn tự giãn theo khung nhìn (ô được vẽ đủ bề rộng danh sách) và không sinh thanh cuộn ngang.
        list.setFixedCellWidth(120);
        list.setBackground(Theme.card());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Cell());
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(list.getSelectedValue()); });
        JScrollPane sp = new JScrollPane(list);
        Ui.slimScroll(sp);
        left.add(sp, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(380, 100));

        // chi tiết (hoặc tranh minh hoạ khi chưa gõ gì)
        Ui.RoundPanel right = new Ui.RoundPanel(Theme::card, 26);
        right.setLayout(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JScrollPane ds = new JScrollPane(detail);
        Ui.slimScroll(ds);

        JPanel idle = new JPanel();
        idle.setOpaque(false);
        idle.setLayout(new BoxLayout(idle, BoxLayout.Y_AXIS));
        idle.add(Box.createVerticalGlue());
        Art.SceneView scene = new Art.SceneView(Art.Scene.SEARCH, 460, 300);
        scene.setAlignmentX(CENTER_ALIGNMENT);
        scene.setMaximumSize(new Dimension(460, 300));
        JLabel t = Ui.label("Start typing", Font.BOLD, 24f, Theme.text());
        t.setFont(Theme.display(26f));
        JLabel st = new JLabel("<html><div style='text-align:center; width:380px'>Look up a slang word, or switch to “By definition” to find a word from its meaning.</div></html>");
        st.setFont(Theme.font(Font.PLAIN, 14f));
        st.setForeground(Theme.muted());
        t.setAlignmentX(CENTER_ALIGNMENT); st.setAlignmentX(CENTER_ALIGNMENT);
        idle.add(scene); idle.add(t); idle.add(Box.createVerticalStrut(6)); idle.add(st);
        idle.add(Box.createVerticalGlue());

        rightHost.setOpaque(false);
        rightHost.add(idle, "idle");
        rightHost.add(ds, "detail");
        right.add(rightHost, BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);
        body.add(left, BorderLayout.WEST);
        body.add(right, BorderLayout.CENTER);
        return body;
    }

    private void setDetail(String html) {
        detail.setText(html);
        detail.setCaretPosition(0);
        rightCards.show(rightHost, "detail");
    }

    private boolean slangMode() { return mode.getSelected() == 0; }

    private void updateHint() {
        hint.setText(slangMode()
                ? "Press Enter for an exact match. Suggestions appear while you type."
                : "Finds every meaning that contains your keyword. Press Enter to save it to history.");
    }

    public void focusField() { field.requestFocusInWindow(); field.selectAll(); }

    @Override public void onShow() {
        if (field.getText().isEmpty()) refreshChips();
        SwingUtilities.invokeLater(() -> field.requestFocusInWindow());
    }

    /** Gọi từ History / Discover. */
    public void searchFor(SearchType type, String query) {
        mode.setSelectedSilently(type == SearchType.SLANG ? 0 : 1);
        updateHint();
        debounce.stop();
        field.setText(query);
        commit();
    }

    // ---------- trạng thái ----------

    private void showIdle() {
        seq++;
        model.clear();
        info.setText("Results");
        highlight = "";
        rightCards.show(rightHost, "idle");
    }

    private void fill(List<SlangEntry> entries, String infoText, String kw) {
        highlight = kw;
        model.setAll(entries);
        info.setText(infoText);
        if (!entries.isEmpty()) list.setSelectedIndex(0);
    }

    private void showDetail(SlangEntry e) {
        if (e == null) return;
        setDetail(EntryView.entry(e, highlight));
    }

    // ---------- tìm kiếm ----------

    /** Tìm khi đang gõ (không ghi history). */
    private void live() {
        seq++; // invalidate any previous definition worker even when switching to slang
        String q = field.getText().trim();
        if (q.isEmpty()) { showIdle(); refreshChips(); return; }
        updateChips();
        if (slangMode()) {
            final int mySeq = seq;
            Tasks.run(() -> app.service().suggest(q, 200), r -> {
                if (mySeq != seq) return; // đã có truy vấn mới hơn: bỏ kết quả cũ
                if (r.entries().isEmpty()) {
                    fill(r.entries(), "No suggestions", "");
                    setDetail(EntryView.message("No match yet", "Nothing starts with “" + q + "”. Press Enter to search anyway."));
                } else {
                    fill(r.entries(), String.format("%,d suggestion%s · %.2f ms", r.entries().size(), r.entries().size() == 1 ? "" : "s", r.millis()), "");
                }
            }, err -> { if (mySeq == seq) app.toast("Search failed: " + Tasks.message(err), true); });
        } else {
            runDefinition(q, false);
        }
    }

    /** Enter / nút Search: tìm chính thức và ghi history. */
    private void commit() {
        seq++; // invalidate all previous background searches
        debounce.stop();
        String q = field.getText().trim();
        if (q.isEmpty()) { app.toast("Please type something to search for.", true); showIdle(); return; }
        if (slangMode()) {
            final int mySeq = seq;
            info.setText("Searching…");
            Tasks.run(() -> {
                SearchResult found = app.service().searchSlang(q, true);
                SearchResult similar = found.entries().isEmpty() ? app.service().suggest(q, 30) : null;
                return new SearchResult[]{found, similar};
            }, both -> {
                if (mySeq != seq) return;
                SearchResult r = both[0];
                if (r.entries().isEmpty()) {
                    SearchResult suggestions = both[1];
                    fill(suggestions.entries(), suggestions.entries().isEmpty() ? "Not found" : "Did you mean…", "");
                    setDetail(EntryView.message("“" + q + "” was not found",
                            suggestions.entries().isEmpty() ? "No slang word matches. Try “By definition”, or add it in Manage words."
                                    : "No exact match. Similar words are listed on the left."));
                } else {
                    fill(r.entries(), String.format("%,d result%s · %.3f ms", r.entries().size(), r.entries().size() == 1 ? "" : "s", r.millis()), "");
                }
                app.updateCount();
            }, err -> { if (mySeq == seq) app.toast("Search failed: " + Tasks.message(err), true); });
        } else {
            runDefinition(q, true);
        }
    }

    private void runDefinition(String q, boolean record) {
        final int mySeq = ++seq;
        info.setText("Searching…");
        Tasks.run(() -> app.service().searchDefinition(q, record), r -> {
            if (mySeq != seq) return; // đã có truy vấn mới hơn
            if (r.entries().isEmpty()) {
                fill(r.entries(), "No results", "");
                setDetail(EntryView.message("No definition contains “" + q + "”", "Try a shorter or different keyword."));
            } else {
                fill(r.entries(), String.format("%,d result%s · %.1f ms", r.entries().size(), r.entries().size() == 1 ? "" : "s", r.millis()), q);
            }
            if (record) app.updateCount();
        }, err -> { if (mySeq == seq) app.toast("Search failed: " + Tasks.message(err), true); });
    }

    // ---------- hiển thị một dòng kết quả ----------

    private static final class Cell extends JPanel implements ListCellRenderer<SlangEntry> {
        private final JLabel slang = new JLabel();
        private final JLabel def = new JLabel();
        private boolean selected;
        private int index;
        private String initial = "?";

        Cell() {
            setLayout(new GridLayout(2, 1, 0, 0));
            setBorder(BorderFactory.createEmptyBorder(13, 70, 13, 14));
            slang.setFont(Theme.heavy(16f));
            def.setFont(Theme.font(Font.PLAIN, 12.5f));
            add(slang);
            add(def);
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            g.setColor(Theme.card());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (selected) {
                Art.sticker(g, new java.awt.geom.RoundRectangle2D.Float(2, 4, getWidth() - 8, getHeight() - 10, 22, 22), Theme.cream(), 2.2f, 3);
            }
            Ui.badge(g, 14, (getHeight() - 42) / 2, 42, initial, Theme.rainbow(index));
            g.dispose();
        }

        @Override public Component getListCellRendererComponent(JList<? extends SlangEntry> l, SlangEntry v, int i, boolean sel, boolean focus) {
            slang.setText(v.getSlang());
            def.setText(v.getDefinition().replace('|', '·'));
            selected = sel;
            index = i;
            String sl = v.getSlang().trim();
            initial = sl.isEmpty() ? "?" : sl.substring(0, sl.offsetByCodePoints(0, 1)).toUpperCase();
            slang.setForeground(Theme.text());
            def.setForeground(Theme.muted());
            return this;
        }
    }
}
