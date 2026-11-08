package dictionary.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Hộp thoại đồng bộ giao diện với theme của app. */
public final class Dialogs {
    private Dialogs() { }

    public record Choice(String label, Ui.Kind kind) { }

    /** @return chỉ số nút được chọn, hoặc -1 nếu đóng hộp thoại / nhấn Esc. */
    public static int ask(Component parent, String title, String message, Choice... choices) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        int[] result = {-1};

        Ui.Paper root = new Ui.Paper();
        root.setLayout(new BorderLayout(0, 18));
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));

        JLabel t = Ui.label(title, Font.BOLD, 20f, Theme.text());
        t.setFont(Theme.display(24f));
        JLabel m = new JLabel("<html><div style='width:360px'>" + Ui.esc(message).replace("\n", "<br>") + "</div></html>");
        m.setFont(Theme.font(Font.PLAIN, 14f));
        m.setForeground(Theme.text());
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(t, BorderLayout.NORTH);
        top.add(m, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        Ui.Pill safe = null;
        for (int i = 0; i < choices.length; i++) {
            final int idx = i;
            Ui.Pill b = new Ui.Pill(choices[i].label(), choices[i].kind());
            b.addActionListener(e -> { result[0] = idx; d.dispose(); });
            buttons.add(b);
            if (safe == null && choices[i].kind() != Ui.Kind.DANGER) safe = b; // nút mặc định luôn là nút an toàn
        }
        root.add(top, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        final Ui.Pill defaultBtn = safe;
        if (defaultBtn != null) {
            d.getRootPane().setDefaultButton(defaultBtn);
            d.addWindowListener(new WindowAdapter() {
                @Override public void windowOpened(WindowEvent e) { defaultBtn.requestFocusInWindow(); }
            });
        }
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }

    public static void info(Component parent, String title, String message) {
        ask(parent, title, message, new Choice("OK", Ui.Kind.PRIMARY));
    }

    public static boolean confirm(Component parent, String title, String message, String yes, boolean danger) {
        return ask(parent, title, message,
                new Choice(yes, danger ? Ui.Kind.DANGER : Ui.Kind.PRIMARY),
                new Choice("Cancel", Ui.Kind.GHOST)) == 0;
    }

    /** Hộp thoại hiển thị văn bản nhiều dòng (báo cáo, benchmark). */
    public static void text(Component parent, String title, String body) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        Ui.Paper root = new Ui.Paper();
        root.setLayout(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));
        { JLabel tt = Ui.label(title, Font.BOLD, 20f, Theme.text()); tt.setFont(Theme.display(24f)); root.add(tt, BorderLayout.NORTH); }

        JTextArea area = new JTextArea(body);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setSelectionColor(Theme.gold());
        area.setBackground(Theme.cream());
        area.setForeground(Theme.text());
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        area.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(area);
        Ui.slimScroll(sp);
        sp.getViewport().setBackground(Theme.cream());
        sp.setPreferredSize(new Dimension(640, 300));
        sp.setBorder(BorderFactory.createLineBorder(Theme.ink(), 2));
        root.add(sp, BorderLayout.CENTER);

        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bp.setOpaque(false);
        Ui.Pill ok = new Ui.Pill("Close", Ui.Kind.PRIMARY);
        ok.addActionListener(e -> d.dispose());
        bp.add(ok);
        root.add(bp, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /** Hộp thoại thêm/sửa entry. @return {slang, definition} hoặc null nếu huỷ. */
    public static String[] editEntry(Component parent, String title, String slang, String definition) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        String[] result = {null, null};

        Ui.Paper root = new Ui.Paper();
        root.setLayout(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));
        { JLabel tt = Ui.label(title, Font.BOLD, 20f, Theme.text()); tt.setFont(Theme.display(24f)); root.add(tt, BorderLayout.NORTH); }

        Ui.RoundField slangField = new Ui.RoundField("e.g. YOLO");
        slangField.setText(slang);
        JTextArea defArea = new JTextArea(definition, 5, 34);
        defArea.setLineWrap(true);
        defArea.setWrapStyleWord(true);
        defArea.setFont(Theme.font(Font.PLAIN, 14f));
        defArea.setForeground(Theme.text());
        defArea.setBackground(Theme.inputBg());
        defArea.setCaretColor(Theme.accent());
        defArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane defScroll = new JScrollPane(defArea);
        defScroll.setBorder(BorderFactory.createLineBorder(Theme.ink(), 2));
        defScroll.getViewport().setBackground(Theme.inputBg());

        JLabel err = Ui.label(" ", Font.PLAIN, 12f, Theme.bad());

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JLabel l1 = Ui.label("Slang word", Font.BOLD, 13f, Theme.muted());
        JLabel l2 = Ui.label("Definition (use | to separate several meanings)", Font.BOLD, 13f, Theme.muted());
        for (JComponent c : new JComponent[]{l1, slangField, l2, defScroll, err}) c.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(l1); form.add(Box.createVerticalStrut(6)); form.add(slangField);
        form.add(Box.createVerticalStrut(14)); form.add(l2); form.add(Box.createVerticalStrut(6)); form.add(defScroll);
        form.add(Box.createVerticalStrut(6)); form.add(err);
        root.add(form, BorderLayout.CENTER);

        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bp.setOpaque(false);
        Ui.Pill save = new Ui.Pill("Save", Ui.Kind.PRIMARY);
        Ui.Pill cancel = new Ui.Pill("Cancel", Ui.Kind.GHOST);
        bp.add(cancel); bp.add(save);
        root.add(bp, BorderLayout.SOUTH);

        Runnable doSave = () -> {
            String e = dictionary.service.DictionaryService.validateSlang(slangField.getText());
            if (e == null) e = dictionary.service.DictionaryService.validateDefinition(defArea.getText());
            if (e != null) { err.setText(e); return; }
            result[0] = dictionary.service.DictionaryService.cleanSlang(slangField.getText());
            result[1] = dictionary.service.DictionaryService.cleanDefinition(defArea.getText());
            d.dispose();
        };
        save.addActionListener(e -> doSave.run());
        cancel.addActionListener(e -> d.dispose());
        slangField.addActionListener(e -> defArea.requestFocusInWindow());
        defArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK), "save");
        defArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.META_DOWN_MASK), "save");
        defArea.getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { doSave.run(); }
        });
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setContentPane(root);
        d.pack();
        d.setMinimumSize(new Dimension(520, d.getHeight()));
        d.setLocationRelativeTo(owner);
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { slangField.requestFocusInWindow(); }
        });
        d.setVisible(true);
        return result[0] == null ? null : result;
    }
}
