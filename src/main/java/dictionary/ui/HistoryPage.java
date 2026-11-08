package dictionary.ui;

import dictionary.model.SearchRecord;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Lịch sử tìm kiếm, mới nhất trước. Có thể tìm lại hoặc xoá. */
public class HistoryPage extends Page {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel count = Ui.label(" ", Font.PLAIN, 13f, Theme.muted());
    private final Ui.Pill again = new Ui.Pill("Search again", Ui.Kind.PRIMARY, Art.Icon.SEARCH);
    private final Ui.Pill clear = new Ui.Pill("Clear history", Ui.Kind.DANGER, Art.Icon.TRASH);

    public HistoryPage(MainFrame app) {
        super(app);
        add(header("Search history", "Everything you searched, newest first. Double-click a row to search again.", Art.Icon.CLOCK, 1), BorderLayout.NORTH);

        Ui.RoundPanel card = new Ui.RoundPanel(Theme::card, 26);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(0).setMaxWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setMaxWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setMaxWidth(100);
        JScrollPane sp = new JScrollPane(table);
        Ui.slimScroll(sp);
        card.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(count, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        btns.add(again); btns.add(clear);
        bottom.add(btns, BorderLayout.EAST);
        card.add(bottom, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);

        again.addActionListener(e -> searchAgain());
        clear.addActionListener(e -> clearAll());
        table.getSelectionModel().addListSelectionListener(e -> again.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) searchAgain(); }
        });
        again.setEnabled(false);
    }

    @Override public void onShow() {
        model.rows = new ArrayList<>(app.service().history());
        model.fireTableDataChanged();
        count.setText(model.rows.isEmpty() ? "No searches yet." : String.format("%,d searches", model.rows.size()));
        clear.setEnabled(!model.rows.isEmpty());
        again.setEnabled(table.getSelectedRow() >= 0);
    }

    private void searchAgain() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        SearchRecord s = model.rows.get(r);
        app.searchFor(s.type(), s.keyword());
    }

    private void clearAll() {
        if (!Dialogs.confirm(this, "Clear history?", "All saved searches will be removed.", "Clear", true)) return;
        Tasks.write(() -> { app.service().clearHistory(); return true; },
                ok -> { onShow(); app.toast("History cleared."); },
                err -> app.toast("Could not clear history: " + Tasks.message(err), true));
    }

    private static final class Model extends AbstractTableModel {
        List<SearchRecord> rows = new ArrayList<>();
        private final String[] cols = {"Time", "Type", "Keyword", "Results"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            SearchRecord s = rows.get(r);
            return switch (c) { case 0 -> FMT.format(s.time()); case 1 -> s.type().label(); case 2 -> s.keyword(); default -> s.resultCount(); };
        }
    }
}
