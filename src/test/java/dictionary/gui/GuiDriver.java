package dictionary.gui;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Công cụ điều khiển giao diện Swing thật từ test: tìm component, bấm nút, đợi điều kiện, xử lý hộp thoại modal. */
final class GuiDriver {
    private GuiDriver() { }

    static <T> T onEdt(Supplier<T> s) {
        if (SwingUtilities.isEventDispatchThread()) return s.get();
        AtomicReference<T> r = new AtomicReference<>();
        try { SwingUtilities.invokeAndWait(() -> r.set(s.get())); }
        catch (InterruptedException | InvocationTargetException e) { throw new RuntimeException(e); }
        return r.get();
    }

    static void onEdt(Runnable r) { onEdt(() -> { r.run(); return null; }); }

    static <T> T waitFor(String what, long timeoutMs, Supplier<T> cond) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            T v = cond.get();
            if (v != null && !Boolean.FALSE.equals(v)) return v;
            try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Timed out after " + timeoutMs + " ms waiting for: " + what);
    }

    static <T extends Component> List<T> findAll(Container root, Class<T> type, Predicate<T> filter, boolean onlyShowing) {
        List<T> out = new ArrayList<>();
        collect(root, type, filter, onlyShowing, out);
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, Predicate<T> f, boolean showing, List<T> out) {
        if (showing && !c.isShowing()) return;
        if (type.isInstance(c) && f.test(type.cast(c))) out.add(type.cast(c));
        if (c instanceof Container k) for (Component child : k.getComponents()) collect(child, type, f, showing, out);
    }

    static <T extends Component> T find(Container root, Class<T> type, Predicate<T> filter) {
        return onEdt(() -> {
            List<T> l = findAll(root, type, filter, true);
            return l.isEmpty() ? null : l.get(0);
        });
    }

    static AbstractButton button(Container root, String text) {
        return waitFor("button '" + text + "'", 8000, () -> find(root, AbstractButton.class, b -> text.equals(b.getText())));
    }

    /** Đợi nút được bật (giao diện bật lại nút sau khi lưu xong) rồi bấm. */
    static void click(AbstractButton b) {
        waitFor("button '" + b.getText() + "' to be enabled", 8000, () -> onEdt(b::isEnabled));
        onEdt(() -> b.doClick(0));
    }

    static void clickOld(AbstractButton b) { onEdt(() -> { if (!b.isEnabled()) throw new IllegalStateException(b.getText() + " is disabled"); b.doClick(0); }); }

    /** Bấm nút KHÔNG chờ: dùng khi nút mở hộp thoại modal (EDT sẽ bị giữ trong vòng lặp phụ cho tới khi hộp thoại đóng). */
    static void clickAsync(AbstractButton b) {
        waitFor("button '" + b.getText() + "' to be enabled", 8000, () -> onEdt(b::isEnabled));
        SwingUtilities.invokeLater(() -> b.doClick(0));
    }


    static JDialog visibleDialog() {
        return onEdt(() -> {
            for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isShowing() && d.isModal()) return d;
            return null;
        });
    }

    /** Thực hiện trigger (mở hộp thoại), đợi hộp thoại xuất hiện, chạy handler rồi đợi hộp thoại đóng. */
    static void withDialog(Runnable trigger, Consumer<JDialog> handler) {
        trigger.run();
        JDialog d = waitFor("a modal dialog", 8000, GuiDriver::visibleDialog);
        handler.accept(d);
        waitFor("dialog to close", 8000, () -> !onEdt(d::isShowing));
    }



    static void setText(javax.swing.text.JTextComponent c, String s) { onEdt(() -> c.setText(s)); }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** Đo "độ đứng" của EDT: khoảng cách lớn nhất giữa hai nhịp timer 10 ms. */
    static final class EdtProbe {
        private volatile long last;
        private volatile long maxGapMs;
        private Timer timer;

        void start() {
            onEdt(() -> {
                last = System.nanoTime();
                maxGapMs = 0;
                timer = new Timer(10, e -> {
                    long now = System.nanoTime();
                    maxGapMs = Math.max(maxGapMs, (now - last) / 1_000_000);
                    last = now;
                });
                timer.start();
            });
        }

        long stopAndGetMaxGapMs() {
            onEdt(() -> timer.stop());
            return maxGapMs;
        }
    }
}
