package dictionary.ui;

import dictionary.model.SearchType;
import dictionary.model.SlangEntry;

import javax.swing.*;
import java.awt.*;

/** "Slang of the day" cố định theo ngày + nút chọn ngẫu nhiên. */
public class DiscoverPage extends Page {
    private final JLabel kicker = Ui.label("", Font.BOLD, 13f, Theme.accentDark());
    private final JEditorPane view = EntryView.createPane();
    private final Ui.Pill randomBtn = new Ui.Pill("Surprise me", Ui.Kind.GOLD, Art.Icon.BOLT);
    private final Ui.Pill todayBtn = new Ui.Pill("Slang of the day", Ui.Kind.GHOST, Art.Icon.SUN);
    private final Ui.Pill lookup = new Ui.Pill("Open in Search", Ui.Kind.PRIMARY, Art.Icon.SEARCH);
    private SlangEntry shown;
    private int shownVersion = -1;

    public DiscoverPage(MainFrame app) {
        super(app);
        add(header("Discover", "Learn one new word at a time.", Art.Icon.SPARK, 3), BorderLayout.NORTH);

        Ui.RoundPanel hero = new Ui.RoundPanel(Theme::card, 30);
        hero.setLayout(new BorderLayout(18, 10));
        hero.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));
        kicker.setFont(Theme.heavy(13f));
        hero.add(kicker, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(view);
        Ui.slimScroll(sp);
        hero.add(sp, BorderLayout.CENTER);
        Art.SceneView scene = new Art.SceneView(Art.Scene.DISCOVER, 340, 240);
        JPanel west = new JPanel(new GridBagLayout());
        west.setOpaque(false);
        west.add(scene);
        hero.add(west, BorderLayout.WEST);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        btns.setOpaque(false);
        btns.add(randomBtn); btns.add(todayBtn); btns.add(lookup);
        hero.add(btns, BorderLayout.SOUTH);
        add(hero, BorderLayout.CENTER);

        randomBtn.addActionListener(e -> showEntry(app.service().randomEntry(), "RANDOM SLANG"));
        todayBtn.addActionListener(e -> showEntry(app.service().slangOfTheDay(), "SLANG OF THE DAY"));
        lookup.addActionListener(e -> { if (shown != null) app.searchFor(SearchType.SLANG, shown.getSlang()); });
    }

    @Override public void onShow() {
        boolean empty = app.service().repository().isEmpty();
        randomBtn.setEnabled(!empty);
        todayBtn.setEnabled(!empty);
        lookup.setEnabled(!empty);
        if (empty) {
            shown = null;
            kicker.setText("");
            view.setText(EntryView.message("The dictionary is empty", "Add some words in “Manage words” first."));
            return;
        }
        boolean stale = shown == null || app.service().repository().get(shown.getId()) == null
                || shownVersion != app.service().repository().version();
        if (stale && (shown == null || app.service().repository().get(shown.getId()) == null)) {
            showEntry(app.service().slangOfTheDay(), "SLANG OF THE DAY");
        }
    }

    private void showEntry(SlangEntry e, String label) {
        shown = e;
        shownVersion = app.service().repository().version();
        kicker.setText(label);
        view.setText(EntryView.entry(e, ""));
        view.setCaretPosition(0);
    }
}
