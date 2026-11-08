package dictionary.ui;

import dictionary.model.QuizQuestion;
import dictionary.model.QuizType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Trò chơi trắc nghiệm 4 đáp án, hai chế độ, có điểm số và chuỗi trả lời đúng liên tiếp. */
public class QuizPage extends Page {
    private final Ui.Segmented modeSwitch = new Ui.Segmented(QuizType.SLANG_TO_MEANING.label(), QuizType.MEANING_TO_SLANG.label());
    private final ScoreBar score = new ScoreBar();
    private final JLabel kicker = Ui.label(" ", Font.BOLD, 13f, Theme.accentDark());
    private final JLabel prompt = new JLabel();
    private final JLabel feedback = Ui.label(" ", Font.BOLD, 16f, Theme.muted());
    private final Ui.Pill nextBtn = new Ui.Pill("Next question", Ui.Kind.GOLD, Art.Icon.BOLT);
    private final Ui.Pill restartBtn = new Ui.Pill("Restart", Ui.Kind.GHOST, Art.Icon.CLOCK);
    private final List<Option> options = new ArrayList<>();
    private QuizQuestion current;
    private boolean answered = false;
    private int correct = 0, total = 0, streak = 0, best = 0;

    public QuizPage(MainFrame app) {
        super(app);
        add(header("Quiz", "Pick the right answer. Keys 1–4 answer, Enter goes to the next question.", Art.Icon.QUIZ, 4), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.add(modeSwitch, BorderLayout.WEST);
        top.add(score, BorderLayout.EAST);
        top.setAlignmentX(LEFT_ALIGNMENT);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        body.add(top);
        body.add(Box.createVerticalStrut(14));

        Ui.RoundPanel qcard = new Ui.RoundPanel(Theme::cream, 30);
        qcard.setLayout(new BorderLayout(10, 8));
        qcard.setBorder(BorderFactory.createEmptyBorder(16, 28, 16, 12));
        prompt.setFont(Theme.display(26f));
        prompt.setForeground(Theme.text());
        kicker.setFont(Theme.heavy(13f));
        qcard.add(kicker, BorderLayout.NORTH);
        qcard.add(prompt, BorderLayout.CENTER);
        qcard.add(new Art.SceneView(Art.Scene.QUIZ, 250, 180), BorderLayout.EAST);
        qcard.setAlignmentX(LEFT_ALIGNMENT);
        qcard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        qcard.setPreferredSize(new Dimension(300, 190));
        body.add(qcard);
        body.add(Box.createVerticalStrut(14));

        for (int i = 0; i < 4; i++) {
            Option o = new Option(i);
            options.add(o);
            o.setAlignmentX(LEFT_ALIGNMENT);
            body.add(o);
            body.add(Box.createVerticalStrut(10));
        }

        JPanel foot = new JPanel(new BorderLayout(12, 0));
        foot.setOpaque(false);
        foot.add(feedback, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        btns.add(restartBtn); btns.add(nextBtn);
        foot.add(btns, BorderLayout.EAST);
        foot.setAlignmentX(LEFT_ALIGNMENT);
        foot.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        body.add(foot);

        JScrollPane sp = new JScrollPane(body);
        Ui.slimScroll(sp);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getViewport().setOpaque(false);
        sp.getViewport().setBackground(Theme.bg());
        sp.getViewport().setOpaque(false);
        add(sp, BorderLayout.CENTER);

        modeSwitch.onChange(() -> { resetScore(); nextQuestion(); });
        nextBtn.addActionListener(e -> nextQuestion());
        restartBtn.addActionListener(e -> { resetScore(); nextQuestion(); });
        nextBtn.setEnabled(false);
        bindKeys();
        updateScore();
    }

    private void bindKeys() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, 0), "pick" + i);
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD1 + i, 0), "pick" + i);
            am.put("pick" + i, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { if (isShowing()) answer(idx); }
            });
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next");
        am.put("next", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { if (isShowing() && answered) nextQuestion(); }
        });
    }

    @Override public void onShow() {
        if (current == null) nextQuestion();
    }

    /** Dấu | trong dữ liệu gốc ngăn cách các nghĩa: hiển thị gọn hơn cho người đọc. */
    static String show(String s) { return s.replaceAll("\\s*\\|\\s*", " / "); }

    private QuizType type() { return modeSwitch.getSelected() == 0 ? QuizType.SLANG_TO_MEANING : QuizType.MEANING_TO_SLANG; }

    private void resetScore() { correct = 0; total = 0; streak = 0; updateScore(); }

    private void updateScore() { score.set(correct, total, streak, best); }

    private void nextQuestion() {
        try {
            current = app.quiz().next(type());
        } catch (IllegalStateException ex) {
            current = null;
            prompt.setText("<html>" + Ui.esc(ex.getMessage()) + "</html>");
            kicker.setText("QUIZ UNAVAILABLE");
            for (Option o : options) o.reset("", false);
            nextBtn.setEnabled(false);
            feedback.setText(" ");
            return;
        }
        answered = false;
        boolean toMeaning = current.type() == QuizType.SLANG_TO_MEANING;
        kicker.setText(toMeaning ? "WHAT DOES THIS SLANG MEAN?" : "WHICH SLANG HAS THIS MEANING?");
        String text = Ui.esc(show(current.prompt()));
        prompt.setText("<html><div style='width:440px'>" + (toMeaning ? "“" + text + "”" : text) + "</div></html>");
        prompt.setFont(Theme.display(toMeaning ? 34f : current.prompt().length() > 120 ? 17f : 21f));
        for (int i = 0; i < 4; i++) options.get(i).reset(current.options().get(i), true);
        feedback.setText("Choose one answer.");
        feedback.setForeground(Theme.muted());
        nextBtn.setEnabled(false);
        repaint();
    }

    private void answer(int index) {
        if (current == null || answered) return;
        answered = true;
        total++;
        boolean ok = current.isCorrect(index);
        if (ok) { correct++; streak++; best = Math.max(best, streak); } else streak = 0;
        for (int i = 0; i < 4; i++) {
            Option o = options.get(i);
            if (i == current.correctIndex()) o.state = State.CORRECT;
            else if (i == index) o.state = State.WRONG;
            else o.state = State.DIM;
            o.repaint();
        }
        if (ok) {
            burst(streak);
            feedback.setText(streak >= 3 ? "Correct! " + streak + " in a row — you're on fire!" : "Correct!");
            feedback.setForeground(Theme.good());
        } else {
            feedback.setText("Not quite. The answer is: " + show(current.correctAnswer()));
            feedback.setForeground(Theme.bad());
        }
        updateScore();
        nextBtn.setEnabled(true);
        nextBtn.requestFocusInWindow();
    }

    // ---------- một đáp án ----------

    private enum State { IDLE, CORRECT, WRONG, DIM }

    private final class Option extends JComponent {
        private final int index;
        private String text = "";
        private State state = State.IDLE;
        private boolean hover, active;

        Option(int index) {
            this.index = index;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(300, 80));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { if (active) answer(Option.this.index); }
            });
        }

        void reset(String t, boolean enabled) {
            text = QuizPage.show(t); state = State.IDLE; active = enabled;
            setToolTipText(text.isEmpty() ? null : text);
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            Color bg = Theme.card(), fg = Theme.text();
            boolean lift = hover && active && state == State.IDLE;
            int sh = lift ? 5 : 4;
            switch (state) {
                case CORRECT -> bg = Theme.goodBg();
                case WRONG -> bg = Theme.badBg();
                case DIM -> { fg = Theme.muted(); sh = 0; }
                default -> { if (lift) bg = Theme.cream(); }
            }
            int w = getWidth() - 6, h = getHeight() - 6;
            g.translate(lift ? -1 : 0, lift ? -1 : 0);
            Art.sticker(g, new java.awt.geom.RoundRectangle2D.Float(1, 1, w - 1, h - 1, 28, 28), bg,
                    state == State.CORRECT || state == State.WRONG ? 3.2f : 2.4f, sh);
            if (state == State.CORRECT) { g.setColor(Theme.good()); g.setStroke(new BasicStroke(3.2f)); g.draw(new java.awt.geom.RoundRectangle2D.Float(1, 1, w - 1, h - 1, 28, 28)); }
            if (state == State.WRONG) { g.setColor(Theme.bad()); g.setStroke(new BasicStroke(3.2f)); g.draw(new java.awt.geom.RoundRectangle2D.Float(1, 1, w - 1, h - 1, 28, 28)); }

            int d = 40, cy = (h - d) / 2;
            Color badge = state == State.CORRECT ? Theme.good() : state == State.WRONG ? Theme.bad() : Theme.rainbow(index);
            Art.sticker(g, new java.awt.geom.Ellipse2D.Float(18, cy, d, d), badge, 2.4f, 0);
            String letter = String.valueOf((char) ('A' + index));
            if (state == State.CORRECT) Art.icon(g, Art.Icon.CHECK, 18 + d / 2.0, cy + d / 2.0, 22, Color.WHITE, 3.4f);
            else if (state == State.WRONG) Art.icon(g, Art.Icon.CROSS, 18 + d / 2.0, cy + d / 2.0, 20, Color.WHITE, 3.4f);
            else {
                g.setFont(Theme.display(19f));
                FontMetrics lm = g.getFontMetrics();
                g.setColor(Theme.isDark() ? Color.BLACK : Theme.ink());
                g.drawString(letter, 18 + (d - lm.stringWidth(letter)) / 2, cy + (d - lm.getHeight()) / 2 + lm.getAscent());
            }

            g.setFont(Theme.semi(16f));
            g.setColor(fg);
            FontMetrics fm = g.getFontMetrics();
            int x = 18 + d + 18, tw = w - x - 22;
            List<String> lines = wrap(text, fm, tw, 3);
            int lineH = fm.getHeight();
            int y = (h - lineH * lines.size()) / 2 + fm.getAscent();
            for (String l : lines) { g.drawString(l, x, y); y += lineH; }
            g.dispose();
        }
    }

    // ---------- thanh điểm: 3 viên sticker ----------

    private static final class ScoreBar extends JComponent {
        private int score, total, streak, best;
        ScoreBar() { setOpaque(false); setPreferredSize(new Dimension(420, 44)); }
        void set(int score, int total, int streak, int best) { this.score = score; this.total = total; this.streak = streak; this.best = best; repaint(); }

        private int chip(Graphics2D g, int xRight, String label, String val, Color fill, Art.Icon ic) {
            g.setFont(Theme.font(Font.BOLD, 12f));
            FontMetrics f1 = g.getFontMetrics();
            Font vf = Theme.display(18f);
            int w = 52 + Math.max(f1.stringWidth(label), g.getFontMetrics(vf).stringWidth(val)) + (ic != null ? 8 : 0);
            int x = xRight - w;
            Art.sticker(g, new java.awt.geom.RoundRectangle2D.Float(x, 2, w - 4, 36, 36, 36), fill, 2.2f, 3);
            Art.icon(g, ic, x + 22, 20, 20, Theme.isDark() ? Color.BLACK : Theme.ink(), 2.2f);
            g.setColor(Theme.isDark() ? Color.BLACK : Theme.ink());
            g.setFont(Theme.font(Font.BOLD, 11f));
            g.drawString(label, x + 40, 15);
            g.setFont(vf);
            g.drawString(val, x + 40, 33);
            return x - 10;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Ui.aa(g);
            int x = getWidth() - 4;
            x = chip(g, x, "BEST", String.valueOf(best), Theme.lilac(), Art.Icon.STAR);
            x = chip(g, x, "STREAK", String.valueOf(streak), streak >= 3 ? Theme.coral() : Theme.butter(), Art.Icon.FIRE);
            chip(g, x, "SCORE", score + " / " + total, Theme.sky(), Art.Icon.CHECK);
            g.dispose();
        }
    }

    // ---------- confetti khi trả lời đúng ----------

    private static final class Bit { double x, y, vx, vy, rot, vr; Color c; int kind; }
    private final List<Bit> confetti = new ArrayList<>();
    private Timer confettiTimer;

    private void burst(int streak) {
        int n = 36 + Math.min(streak, 10) * 6;
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) {
            Bit b = new Bit();
            b.x = getWidth() * (0.25 + r.nextDouble() * 0.5);
            b.y = getHeight() * 0.55;
            double a = -Math.PI / 2 + (r.nextDouble() - 0.5) * 2.2, v = 6 + r.nextDouble() * 10;
            b.vx = Math.cos(a) * v; b.vy = Math.sin(a) * v;
            b.rot = r.nextDouble() * 6; b.vr = (r.nextDouble() - 0.5) * 0.5;
            b.c = Theme.rainbow(r.nextInt(5));
            b.kind = r.nextInt(3);
            confetti.add(b);
        }
        if (confettiTimer == null) {
            confettiTimer = new Timer(16, e -> {
                for (java.util.Iterator<Bit> it = confetti.iterator(); it.hasNext(); ) {
                    Bit b = it.next();
                    b.x += b.vx; b.y += b.vy; b.vy += 0.35; b.vx *= 0.985; b.rot += b.vr;
                    if (b.y > getHeight() + 20) it.remove();
                }
                if (confetti.isEmpty()) confettiTimer.stop();
                repaint();
            });
        }
        confettiTimer.start();
    }

    @Override public void paint(Graphics g0) {
        super.paint(g0);
        if (confetti.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        Ui.aa(g);
        for (Bit b : confetti) {
            Graphics2D t = (Graphics2D) g.create();
            t.translate(b.x, b.y);
            t.rotate(b.rot);
            Shape sh = b.kind == 0 ? new java.awt.geom.Rectangle2D.Float(-6, -3, 12, 6)
                    : b.kind == 1 ? new java.awt.geom.Ellipse2D.Float(-4, -4, 8, 8) : Art.star(0, 0, 7, 3, 5);
            t.setColor(b.c); t.fill(sh);
            t.setColor(Theme.ink()); t.setStroke(new BasicStroke(1.4f)); t.draw(sh);
            t.dispose();
        }
        g.dispose();
    }

    /** Ngắt dòng theo độ rộng; quá maxLines thì cắt bằng dấu … */
    static List<String> wrap(String text, FontMetrics fm, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String trial = line.length() == 0 ? w : line + " " + w;
            if (fm.stringWidth(trial) <= width || line.length() == 0) {
                line = new StringBuilder(trial);
            } else {
                out.add(line.toString());
                line = new StringBuilder(w);
                if (out.size() == maxLines - 1) {
                    StringBuilder rest = new StringBuilder(w);
                    for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                    out.add(ellipsize(rest.toString(), fm, width));
                    return out;
                }
            }
        }
        if (line.length() > 0) out.add(ellipsize(line.toString(), fm, width));
        return out;
    }

    private static String ellipsize(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
