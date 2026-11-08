package dictionary.gui;

import dictionary.model.SlangEntry;
import dictionary.service.DictionaryService;
import dictionary.storage.FileStorage;
import dictionary.ui.MainFrame;
import dictionary.ui.Tasks;
import dictionary.ui.Ui;
import org.junit.jupiter.api.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static dictionary.Fixtures.REAL_DATA;
import static dictionary.gui.GuiDriver.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Nghiệm thu GUI thật (cần màn hình: trên máy không có màn hình dùng xvfb-run). Duyệt đủ 10 chức năng, rồi thao tác
 * nhanh liên tục, kết quả cũ không được đè kết quả mới. Mỗi test dùng chung một cửa sổ, chạy theo thứ tự.
 */
@Tag("gui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuiAcceptanceTest {
    Path tmp;
    MainFrame frame;
    DictionaryService service;
    FileStorage storage;

    @BeforeAll void openWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display (use xvfb-run)");
        tmp = Files.createTempDirectory("slang-gui");
        Path orig = tmp.resolve("data/original");
        Files.createDirectories(orig);
        Files.copy(REAL_DATA.resolve("slang.raw.txt"), orig.resolve("slang.raw.txt"));
        storage = new FileStorage(tmp);
        service = new DictionaryService(storage);
        onEdt(() -> { frame = new MainFrame(service); frame.setSize(1180, 780); frame.setVisible(true); frame.start(); });
        waitFor("main window to finish loading", 20000, () -> find(frame, Ui.RoundField.class, f -> true));
    }

    @AfterAll void closeWindow() {
        if (frame != null) onEdt(() -> { frame.setVisible(false); frame.dispose(); });
        Tasks.restart();
        deleteTree(tmp);
    }

    static void deleteTree(Path p) {
        if (p == null) return;
        try (var w = Files.walk(p)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); } catch (Exception ignored) { }
    }

    /** Mở lại dữ liệu từ đĩa như một lần khởi động mới. */
    private DictionaryService fresh() throws Exception { DictionaryService d = new DictionaryService(new FileStorage(tmp)); d.load(); return d; }

    private Ui.RoundField searchField() { return find(frame, Ui.RoundField.class, f -> true); }

    @SuppressWarnings("unchecked") private JList<SlangEntry> resultList() { return find(frame, JList.class, l -> l.getModel().getSize() >= 0); }

    private int listSize() { return onEdt(() -> resultList().getModel().getSize()); }

    private String listSlang(int i) { return onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(i)).getSlang()); }

    private void go(String page) { onEdt(() -> frame.show(page)); waitFor("page " + page, 5000, () -> true); sleep(150); }

    // ------------------------------------------------------------------ 1-2: tìm kiếm

    @Test @Order(1) void f1_searchBySlang() {
        go(MainFrame.SEARCH);
        setText(searchField(), "lol");
        onEdt(() -> searchField().postActionEvent());
        waitFor("exact result LOL", 5000, () -> listSize() == 1 && listSlang(0).equalsIgnoreCase("lol"));
        setText(searchField(), "ama");
        onEdt(() -> searchField().postActionEvent());
        waitFor("AMA with merged meanings", 5000, () -> listSize() == 1 && listSlang(0).equals("AMA"));
        assertTrue(onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(0)).getDefinition()).contains("Against Medical Advice"));
    }

    @Test @Order(2) void f2_searchByDefinition() {
        go(MainFrame.SEARCH);
        Ui.Segmented mode = find(frame, Ui.Segmented.class, s -> true);
        onEdt(() -> mode.setSelected(1));
        setText(searchField(), "laughing");
        onEdt(() -> searchField().postActionEvent());
        waitFor("definition results for 'laughing'", 6000, () -> listSize() > 5
                && onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(0)).getDefinition()).toLowerCase().contains("laughing"));
        sleep(600);   // không có kết quả muộn nào được ghi đè
        for (int i = 0; i < Math.min(listSize(), 40); i++) {
            int idx = i;
            String d0 = onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(idx)).getDefinition());
            assertTrue(d0.toLowerCase().contains("laughing"), "entry " + idx + " of " + listSize() + ": " + listSlang(idx) + " / " + d0);
        }
        onEdt(() -> mode.setSelected(0));
    }

    @Test @Order(3) void rapidTypingAndModeSwitchingNeverLetsOldResultsOverwriteNewOnes() {
        go(MainFrame.SEARCH);
        Ui.Segmented mode = find(frame, Ui.Segmented.class, s -> true);
        String[] fragments = {"a", "lo", "lol", "ama", "laugh", "the", "z", "ba", "bomb", "sleep", "love", "happy", "xq", "co", "cool"};
        Random rnd = new Random(2026);
        for (int i = 0; i < 400; i++) {
            final int m = rnd.nextInt(2);
            final String q = fragments[rnd.nextInt(fragments.length)];
            onEdt(() -> { if (rnd.nextInt(4) == 0) mode.setSelected(m); searchField().setText(q); if (rnd.nextInt(3) == 0) searchField().postActionEvent(); });
            sleep(rnd.nextInt(25));
        }
        // câu hỏi cuối cùng: slang chính xác
        onEdt(() -> mode.setSelected(0));
        setText(searchField(), "bomb");
        onEdt(() -> searchField().postActionEvent());
        waitFor("final exact result BOMB", 6000, () -> listSize() == 1 && listSlang(0).equals("BOMB"));
        sleep(1500);   // mọi tìm kiếm cũ (debounce, nền) đã có thời gian trả về
        assertEquals(1, listSize(), "late results of earlier queries overwrote the final one");
        assertEquals("BOMB", listSlang(0));
        // rồi theo định nghĩa
        onEdt(() -> mode.setSelected(1));
        setText(searchField(), "laughing");
        onEdt(() -> searchField().postActionEvent());
        waitFor("final definition results", 6000, () -> listSize() > 5
                && onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(0)).getDefinition()).toLowerCase().contains("laughing"));
        sleep(1500);
        for (int i = 0; i < listSize(); i++) {
            int idx = i;
            assertTrue(onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(idx)).getDefinition()).toLowerCase().contains("laughing"),
                    "stale result at " + i + ": " + listSlang(i));
        }
        onEdt(() -> mode.setSelected(0));
    }

    @Test @Order(3) void tryChipsAreSlangWordsInSlangModeAndKeywordsInDefinitionMode() {
        go(MainFrame.SEARCH);
        Ui.Segmented mode = find(frame, Ui.Segmented.class, s -> true);
        setText(searchField(), "");
        onEdt(() -> mode.setSelected(0));
        // đợi hàng gợi ý ổn định (sau khi xóa ô tìm kiếm, hàng "Keywords:" cũ phải được thay bằng slang)
        waitFor("slang chips that are real slang words", 4000, () -> {
            var c = chipTexts();
            return !c.isEmpty() && c.stream().allMatch(w -> !service.searchSlang(w, false).entries().isEmpty()) ? c : null;
        });

        onEdt(() -> mode.setSelected(1));
        waitFor("keyword chips (not slang words)", 4000, () -> { var c = chipTexts(); return !c.isEmpty() && c.stream().allMatch(w -> !service.repository().findByDefinition(w).isEmpty()) ? c : null; });
        // gõ dở một từ -> gợi ý hoàn thành
        setText(searchField(), "laug");
        waitFor("completions of 'laug'", 4000, () -> { var c = chipTexts(); return !c.isEmpty() && c.stream().allMatch(w -> w.startsWith("laug")) ? c : null; });
        // bấm một gợi ý -> tìm theo definition với đúng từ đó
        String pick = chipTexts().get(0);
        click(find(frame, Ui.Chip.class, c -> c.getText().equals(pick)));
        waitFor("definition results for the chosen keyword", 6000, () -> listSize() >= 1
                && onEdt(() -> ((SlangEntry) resultList().getModel().getElementAt(0)).getDefinition()).toLowerCase().contains(pick));
        assertEquals(pick, onEdt(() -> searchField().getText()));
        onEdt(() -> mode.setSelected(0));
        setText(searchField(), "");
    }

    private java.util.List<String> chipTexts() {
        return onEdt(() -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Ui.Chip c : findAll(frame, Ui.Chip.class, x -> true, true)) out.add(c.getText());
            return out;
        });
    }

    // ------------------------------------------------------------------ 4-6: thêm / sửa / xóa

    private JTable manageTable() { return find(frame, JTable.class, t -> true); }

    private int rows() { return onEdt(() -> manageTable().getRowCount()); }

    private void filterManage(String text) {
        setText(find(frame, Ui.RoundField.class, f -> true), text);
        waitFor("filter result", 6000, () -> true);
        sleep(500);
    }

    private void fillDialog(JDialog d, String slang, String def) {
        Ui.RoundField f = find(d, Ui.RoundField.class, x -> true);
        JTextArea a = find(d, JTextArea.class, x -> true);
        onEdt(() -> { f.setText(slang); a.setText(def); });
    }

    @Test @Order(4) void f4_addWordThenDuplicateOverwriteAndKeepBoth() throws Exception {
        go(MainFrame.MANAGE);
        withDialog(() -> clickAsync(button(frame, "Add word")), d -> {
            fillDialog(d, "ZZGUIADD", "first meaning");
            click(button(d, "Save"));
        });
        waitFor("service has the new word", 6000, () -> service.countForSlang("ZZGUIADD") == 1);
        assertEquals(1, fresh().searchSlang("zzguiadd", false).entries().size(),
                "persisted on disk");

        // slang trùng -> hỏi: Keep both
        withDialog(() -> clickAsync(button(frame, "Add word")), d -> {
            fillDialog(d, "zzguiadd", "second meaning");
            click(button(d, "Save"));
        });
        withDialog(() -> { }, d -> click(button(d, "Keep both")));
        waitFor("two meanings", 6000, () -> service.countForSlang("ZZGUIADD") == 2);

        // lần nữa -> Overwrite
        withDialog(() -> clickAsync(button(frame, "Add word")), d -> {
            fillDialog(d, "ZZGUIADD", "only meaning");
            click(button(d, "Save"));
        });
        withDialog(() -> { }, d -> click(button(d, "Overwrite")));
        waitFor("overwritten", 6000, () -> service.countForSlang("ZZGUIADD") == 1);
        assertEquals("only meaning", service.searchSlang("ZZGUIADD", false).entries().get(0).getDefinition());
    }


    @Test @Order(5) void f5_editWord() throws Exception {
        go(MainFrame.MANAGE);
        filterManage("ZZGUIADD");
        waitFor("one row", 6000, () -> rows() == 1);
        onEdt(() -> manageTable().setRowSelectionInterval(0, 0));
        withDialog(() -> clickAsync(button(frame, "Edit")), d -> {
            fillDialog(d, "ZZGUIEDIT", "edited meaning\nsecond line");
            click(button(d, "Save"));
        });
        waitFor("edited in service", 6000, () -> service.countForSlang("ZZGUIEDIT") == 1 && service.countForSlang("ZZGUIADD") == 0);
        assertEquals("edited meaning second line", service.searchSlang("ZZGUIEDIT", false).entries().get(0).getDefinition());
        assertEquals(1, fresh().searchSlang("zzguiedit", false).entries().size());
    }

    @Test @Order(6) void f6_deleteWordWithConfirmation() {
        go(MainFrame.MANAGE);
        filterManage("ZZGUIEDIT");
        waitFor("one row", 6000, () -> rows() == 1);
        onEdt(() -> manageTable().setRowSelectionInterval(0, 0));
        // Cancel không xóa
        withDialog(() -> clickAsync(button(frame, "Delete")), d -> click(button(d, "Cancel")));
        assertEquals(1, service.countForSlang("ZZGUIEDIT"));
        withDialog(() -> clickAsync(button(frame, "Delete")), d -> click(button(d, "Delete")));
        waitFor("deleted", 6000, () -> service.countForSlang("ZZGUIEDIT") == 0);
    }

    @Test @Order(7) void f_invalidSlangIsRejectedInTheDialogAndNothingIsSaved() {
        go(MainFrame.MANAGE);
        long v = service.dataVersion();
        withDialog(() -> clickAsync(button(frame, "Add word")), d -> {
            Ui.RoundField f = find(d, Ui.RoundField.class, x -> true);
            JTextArea a = find(d, JTextArea.class, x -> true);
            onEdt(() -> { f.setText("HELLO\rWORLD"); a.setText("x"); });   // JTextField tự lọc \n khi dán, nhưng \r vẫn lọt vào
            click(button(d, "Save"));
            assertNotNull(waitFor("dialog still open with an error", 3000, GuiDriver::visibleDialog), "dialog must stay open");
            JLabel err = find(d, JLabel.class, l -> l.getText() != null && l.getText().contains("single line"));
            assertNotNull(err, "an error message must be shown");
            click(button(d, "Cancel"));
        });
        assertEquals(v, service.dataVersion());
        assertEquals(0, service.countForSlang("HELLO\rWORLD"));
    }

    // ------------------------------------------------------------------ 3, 8, 9, 10: history, random, quiz

    @Test @Order(8) void f3_historyListsSearchesAndCanBeCleared() {
        go(MainFrame.HISTORY);
        assertTrue(waitFor("history rows", 5000, () -> { JTable t = manageTable(); return t != null && onEdt(() -> t.getRowCount()) >= 2; }));
        withDialog(() -> clickAsync(button(frame, "Clear history")), d -> click(button(d, "Clear")));
        waitFor("history cleared", 5000, () -> service.history().isEmpty());
    }

    @Test @Order(9) void f8_randomAndSlangOfTheDay() {
        go(MainFrame.DISCOVER);
        click(button(frame, "Surprise me"));
        click(button(frame, "Slang of the day"));
        click(button(frame, "Open in Search"));
        waitFor("search page opened with a result", 5000, () -> listSize() >= 1);
    }

    private void answerQuiz(int questions) throws Exception {
        Robot robot = new Robot();
        for (int qi = 0; qi < questions; qi++) {
            final int q = qi;
            Component opt = waitFor("quiz option", 5000, () -> {
                java.util.List<Component> l = onEdt(() -> new java.util.ArrayList<>(findAll(frame, JComponent.class,
                        c -> c.getClass().getSimpleName().equals("Option"), true)));
                return l.size() == 4 ? l.get(new Random(q).nextInt(4)) : null;
            });
            Point p = onEdt(() -> { Point s = opt.getLocationOnScreen(); return new Point(s.x + opt.getWidth() / 2, s.y + opt.getHeight() / 2); });
            robot.mouseMove(p.x, p.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            sleep(250);
            click(button(frame, "Next question"));
            sleep(150);
        }
    }

    @Test @Order(10) void f9_f10_bothQuizModes() throws Exception {
        go(MainFrame.QUIZ);
        Ui.Segmented sw = find(frame, Ui.Segmented.class, s -> true);
        onEdt(() -> sw.setSelected(0));
        sleep(300);
        answerQuiz(5);
        onEdt(() -> sw.setSelected(1));
        sleep(300);
        answerQuiz(5);
        assertNotNull(find(frame, JLabel.class, l -> l.getText() != null && l.getText().contains("WHICH SLANG HAS THIS MEANING")));
    }

    // ------------------------------------------------------------------ 7: reset + đóng/mở lại

    @Test @Order(11) void f7_resetRestoresOriginalAndSurvivesRestart() throws Exception {
        service.add("ZZBEFORERESET", "x");
        go(MainFrame.SETTINGS);
        withDialog(() -> clickAsync(button(frame, "Reset to original data")), d -> click(button(d, "Reset")));
        waitFor("reset done", 10000, () -> service.countForSlang("ZZBEFORERESET") == 0 && service.repository().size() == 7656);
        DictionaryService again = new DictionaryService(new FileStorage(tmp));
        try { again.load(); } catch (Exception e) { fail(e); }
        assertEquals(7656, again.repository().size());
        assertEquals(0, again.countForSlang("ZZBEFORERESET"));
    }

    @Test @Order(12) void closeAndReopenKeepsEveryChange() throws Exception {
        service.add("ZZREOPEN", "kept");
        onEdt(() -> { frame.setVisible(false); frame.dispose(); });
        Tasks.restart();
        DictionaryService s2 = new DictionaryService(new FileStorage(tmp));
        s2.load();
        MainFrame f2 = onEdt(() -> { MainFrame f = new MainFrame(s2); f.setSize(1180, 780); f.setVisible(true); f.start(); return f; });
        waitFor("reopened window loaded", 20000, () -> find(f2, Ui.RoundField.class, x -> true));
        assertEquals(1, s2.countForSlang("ZZREOPEN"));
        onEdt(() -> { f2.setVisible(false); f2.dispose(); });
        frame = null;
    }

    /** Đóng cửa sổ đúng lúc đang lưu (JVM riêng, đường thoát thật EXIT_ON_CLOSE): thay đổi vẫn phải nằm trên đĩa, file không hỏng. */
    @Test @Order(13) void closingTheWindowWhileASaveIsRunningKeepsTheChange() throws Exception {
        Path dir = Files.createTempDirectory("slang-close");
        try {
            Path orig = dir.resolve("data/original");
            Files.createDirectories(orig);
            Files.copy(REAL_DATA.resolve("slang.raw.txt"), orig.resolve("slang.raw.txt"));
            long delay = 1500;
            ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classPathForChild(), "dictionary.gui.CloseDuringSaveHarness", dir.toString(), String.valueOf(delay));
            pb.redirectErrorStream(true);
            long t0 = System.nanoTime();
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean exited = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(exited, "application did not exit after the window was closed\n" + output);
            assertEquals(0, p.exitValue(), output);
            assertTrue(output.contains("CLOSING-NOW"), output);
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= delay, "the close really overlapped the slow save");
            DictionaryService after = new DictionaryService(new FileStorage(dir));
            var report = after.load();
            assertNull(report.warning(), "no recovery needed: " + report.warning());
            assertEquals(1, after.countForSlang("ZZCLOSEWHILESAVING"), "the change being saved while closing must not be lost");
            assertEquals(dictionary.service.DictionaryService.Source.SAVED_STRUCTURES, report.source());
        } finally {
            deleteTree(dir);
        }
    }

    /** Class path cho JVM con: thư mục test + main (đúng cả khi chạy bằng Maven lẫn JUnit Console) + class path hiện tại. */
    private static String classPathForChild() throws Exception {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        for (Class<?> c : new Class<?>[]{CloseDuringSaveHarness.class, MainFrame.class}) {
            parts.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        parts.add(System.getProperty("java.class.path"));
        return String.join(java.io.File.pathSeparator, parts);
    }
}
