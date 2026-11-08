package dictionary.ui;

import dictionary.model.SlangEntry;
import dictionary.ui.Dialogs.Choice;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Quản lý dữ liệu: xem toàn bộ, lọc, thêm, sửa, xoá. Mọi thay đổi được lưu xuống file ngay. */
public class ManagePage extends Page {
    private record Loaded(List<SlangEntry> sorted, List<SlangEntry> filtered, int version) { }

    private final RowModel model = new RowModel();
    private final JTable table = new JTable(model);
    private final Ui.RoundField filter = new Ui.RoundField("Filter by word or meaning…").withSearchIcon();
    private final JLabel count = Ui.label(" ", Font.PLAIN, 13f, Theme.muted());
    private final Ui.Pill addBtn = new Ui.Pill("Add word", Ui.Kind.GOLD, Art.Icon.PLUS);
    private final Ui.Pill editBtn = new Ui.Pill("Edit", Ui.Kind.PRIMARY, Art.Icon.EDIT);
    private final Ui.Pill delBtn = new Ui.Pill("Delete", Ui.Kind.DANGER, Art.Icon.TRASH);
    private final Timer debounce;
    private List<SlangEntry> sortedCache = new ArrayList<>();
    private int loadedVersion = -1;
    private int seq = 0;
    private boolean busy = false;

    public ManagePage(MainFrame app) {
        super(app);
        add(header("Manage words", "Add, edit or delete entries. Every change is saved to disk immediately.", Art.Icon.BOOKS, 2), BorderLayout.NORTH);

        Ui.RoundPanel card = new Ui.RoundPanel(Theme::card, 26);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel tools = new JPanel(new BorderLayout(12, 0));
        tools.setOpaque(false);
        tools.add(filter, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        btns.add(addBtn); btns.add(editBtn); btns.add(delBtn);
        tools.add(btns, BorderLayout.EAST);
        card.add(tools, BorderLayout.NORTH);

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(0).setMaxWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(600);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setFillsViewportHeight(true);
        JScrollPane sp = new JScrollPane(table);
        Ui.slimScroll(sp);
        card.add(sp, BorderLayout.CENTER);
        count.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        card.add(count, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);

        debounce = new Timer(250, e -> reload(null));
        debounce.setRepeats(false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        addBtn.addActionListener(e -> add());
        editBtn.addActionListener(e -> edit());
        delBtn.addActionListener(e -> delete());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) edit(); }
        });
        table.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) delete();
                else if (e.getKeyCode() == KeyEvent.VK_ENTER) { edit(); e.consume(); }
            }
        });
        updateButtons();
    }

    @Override public void onShow() {
        if (loadedVersion != app.service().repository().version()) reload(null);
    }

    private void updateButtons() {
        boolean sel = table.getSelectedRow() >= 0 && !busy;
        editBtn.setEnabled(sel);
        delBtn.setEnabled(sel);
        addBtn.setEnabled(!busy);
    }

    private SlangEntry selected() {
        int r = table.getSelectedRow();
        return r < 0 ? null : model.rows.get(r);
    }

    /** Nạp lại bảng (sắp xếp + lọc) ở luồng nền; sau đó chọn entry có id = selectId nếu có. */
    private void reload(Long selectId) {
        final int mySeq = ++seq;
        final String q = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        final boolean needSort = loadedVersion != app.service().repository().version();
        final List<SlangEntry> cache = sortedCache;
        count.setText("Loading…");
        Tasks.run(() -> {
            int version = app.service().repository().version();
            List<SlangEntry> sorted = needSort ? app.service().allSorted() : cache;
            List<SlangEntry> out;
            if (q.isEmpty()) out = sorted;
            else {
                out = new ArrayList<>();
                for (SlangEntry e : sorted) if (e.key().contains(q) || e.lowerDefinition().contains(q)) out.add(e);
            }
            return new Loaded(sorted, out, version);
        }, r -> {
            if (mySeq != seq) return;
            sortedCache = r.sorted();
            loadedVersion = r.version();
            model.rows = r.filtered();
            model.fireTableDataChanged();
            count.setText(q.isEmpty()
                    ? String.format("%,d entries", r.filtered().size())
                    : String.format("%,d of %,d entries match", r.filtered().size(), r.sorted().size()));
            if (selectId != null) selectById(selectId);
            updateButtons();
            app.updateCount();
        }, err -> { if (mySeq == seq) count.setText("Could not load the list"); app.toast("Could not load the list: " + Tasks.message(err), true); });
    }

    private void selectById(long id) {
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i).getId() == id) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    // ---------- thêm / sửa / xoá ----------

    private void setBusy(boolean b) { busy = b; if (b) count.setText("Saving…"); updateButtons(); table.setCursor(Cursor.getPredefinedCursor(b ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR)); }

    private void add() {
        String[] in = Dialogs.editEntry(this, "Add a word", "", "");
        if (in == null) return;
        String slang = in[0], def = in[1];
        int existing = app.service().countForSlang(slang);
        int choice = 1; // 1 = giữ cả hai (thêm mới), 0 = ghi đè
        if (existing > 0) {
            choice = Dialogs.ask(this, "“" + slang + "” already exists",
                    "This word already has " + existing + (existing == 1 ? " meaning" : " meanings") + ".\n\n"
                            + "Overwrite: replace the existing meaning(s) with the new one.\n"
                            + "Keep both: add the new meaning next to the existing one(s).",
                    new Choice("Overwrite", Ui.Kind.DANGER), new Choice("Keep both", Ui.Kind.PRIMARY), new Choice("Cancel", Ui.Kind.GHOST));
            if (choice != 0 && choice != 1) return;
        }
        final boolean overwrite = choice == 0 && existing > 0;
        setBusy(true);
        Tasks.write(() -> overwrite ? app.service().overwrite(slang, def) : app.service().add(slang, def),
                e -> {
                    setBusy(false);
                    app.toast(overwrite ? "Overwrote “" + e.getSlang() + "”." : "Added “" + e.getSlang() + "”.");
                    reload(e.getId());
                },
                err -> { setBusy(false); app.toast("Could not add the word: " + Tasks.message(err), true); });
    }

    private void edit() {
        SlangEntry cur = selected();
        if (cur == null || busy) return;
        String[] in = Dialogs.editEntry(this, "Edit word", cur.getSlang(), cur.getDefinition());
        if (in == null) return;
        if (in[0].equals(cur.getSlang()) && in[1].equals(cur.getDefinition())) return;
        setBusy(true);
        Tasks.write(() -> app.service().update(cur.getId(), in[0], in[1]),
                e -> { setBusy(false); app.toast("Saved changes to “" + e.getSlang() + "”."); reload(e.getId()); },
                err -> { setBusy(false); app.toast("Could not save: " + Tasks.message(err), true); });
    }

    private void delete() {
        SlangEntry cur = selected();
        if (cur == null || busy) return;
        if (!Dialogs.confirm(this, "Delete this word?",
                "“" + cur.getSlang() + "”\n" + cur.getDefinition() + "\n\nThis cannot be undone (use Settings → Reset to restore the original data).",
                "Delete", true)) return;
        setBusy(true);
        Tasks.write(() -> { app.service().delete(cur.getId()); return cur; },
                e -> { setBusy(false); app.toast("Deleted “" + e.getSlang() + "”."); reload(null); },
                err -> { setBusy(false); app.toast("Could not delete: " + Tasks.message(err), true); });
    }

    // ---------- model ----------

    private static final class RowModel extends AbstractTableModel {
        List<SlangEntry> rows = new ArrayList<>();
        private final String[] cols = {"#", "Slang", "Definition"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            SlangEntry e = rows.get(r);
            return switch (c) { case 0 -> r + 1; case 1 -> e.getSlang(); default -> e.getDefinition(); };
        }
    }
}
