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
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static dictionary.gui.GuiDriver.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * P2: bộ dữ liệu 100.000 từ trong giao diện thật. Đo "độ đứng" lớn nhất của luồng giao diện (EDT)
 * khi hiện danh sách kết quả rất lớn, nạp bảng Manage, lọc, và khi đang lưu.
 */
@Tag("gui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuiLargeDataTest {
    /** Giao diện được coi là "không đứng" nếu EDT không bị chặn quá ngần này (máy 1 CPU của sandbox; máy thật thường < 100 ms). */
    static final long MAX_FREEZE_MS = Long.getLong("gui.maxFreezeMs", 400);

    Path tmp;
    MainFrame frame;
    DictionaryService service;

    @BeforeAll void open() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display (use xvfb-run)");
        tmp = Files.createTempDirectory("slang-gui-big");
        Path orig = tmp.resolve("data/original");
        Files.createDirectories(orig);
        String[] words = {"cool", "happy", "friend", "money", "angry", "sleep", "party", "music", "food", "great", "tired", "love"};
        Random rnd = new Random(1);
        try (BufferedWriter w = Files.newBufferedWriter(orig.resolve("slang.raw.txt"), StandardCharsets.UTF_8)) {
            for (int i = 1; i <= 100_000; i++) {
                StringBuilder def = new StringBuilder();
                for (int k = 0; k < 6; k++) def.append(words[rnd.nextInt(words.length)]).append(' ').append(rnd.nextInt(1000)).append(' ');
                w.write("slang" + Integer.toString(i, 36) + rnd.nextInt(100) + "`" + def.toString().trim());
                w.newLine();
            }
        }
        service = new DictionaryService(new FileStorage(tmp));
        service.load();     // lần khởi động đầu (cold) xong trước khi mở cửa sổ; cửa sổ sẽ nạp lại theo kiểu "warm"
        service = new DictionaryService(new FileStorage(tmp));
        onEdt(() -> { frame = new MainFrame(service); frame.setSize(1180, 780); frame.setVisible(true); frame.start(); });
        waitFor("window loaded with 100,000 entries", 40000, () -> find(frame, Ui.RoundField.class, f -> true));
        assertEquals(100_000, service.repository().size());
    }

    @AfterAll void close() {
        if (frame != null) onEdt(() -> { frame.setVisible(false); frame.dispose(); });
        Tasks.restart();
        GuiAcceptanceTest.deleteTree(tmp);
    }

    @SuppressWarnings("unchecked") private JList<SlangEntry> list() { return find(frame, JList.class, l -> true); }
    private int listSize() { return onEdt(() -> list().getModel().getSize()); }
    private Ui.RoundField field() { return find(frame, Ui.RoundField.class, f -> true); }

    @Test @Order(1) void hugeDefinitionResultListDoesNotFreezeTheWindow() {
        onEdt(() -> frame.show(MainFrame.SEARCH));
        sleep(300);
        Ui.Segmented mode = find(frame, Ui.Segmented.class, s -> true);
        onEdt(() -> mode.setSelected(1));
        sleep(200);
        EdtProbe probe = new EdtProbe();
        probe.start();
        long t0 = System.nanoTime();
        setText(field(), "love");
        onEdt(() -> field().postActionEvent());
        int n = waitFor("huge result list", 30000, () -> { int s = listSize(); return s > 10_000 ? s : null; });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        sleep(500);
        long gap = probe.stopAndGetMaxGapMs();
        System.out.printf("        [gui-large] definition 'love': %,d results shown in %d ms, longest EDT freeze %d ms%n", n, ms, gap);
        assertTrue(gap <= MAX_FREEZE_MS, "window froze for " + gap + " ms while showing " + n + " results");
        // cuộn xuống cuối danh sách và chọn một phần tử cuối: vẫn phải mượt
        probe.start();
        onEdt(() -> { JList<SlangEntry> l = list(); l.setSelectedIndex(l.getModel().getSize() - 1); l.ensureIndexIsVisible(l.getModel().getSize() - 1); });
        sleep(400);
        long gap2 = probe.stopAndGetMaxGapMs();
        System.out.printf("        [gui-large] jump to last of %,d results: longest EDT freeze %d ms%n", n, gap2);
        assertTrue(gap2 <= MAX_FREEZE_MS, "window froze for " + gap2 + " ms jumping to the end of a huge list");
        onEdt(() -> mode.setSelected(0));
    }

    @Test @Order(2) void typingWhileHugeSuggestionListsAreProducedStaysSmooth() {
        onEdt(() -> frame.show(MainFrame.SEARCH));
        sleep(300);
        EdtProbe probe = new EdtProbe();
        probe.start();
        for (String q : new String[]{"s", "sl", "sla", "slan", "slang", "slang1", "slang1a", "slang1ab"}) {
            setText(field(), q);
            sleep(60);
        }
        sleep(800);
        long gap = probe.stopAndGetMaxGapMs();
        System.out.printf("        [gui-large] live prefix typing on 100,000 entries: longest EDT freeze %d ms%n", gap);
        assertTrue(gap <= MAX_FREEZE_MS, "typing froze the window for " + gap + " ms");
        setText(field(), "");
    }

    @Test @Order(3) void manageTableWith100kRowsLoadsFiltersAndScrollsWithoutFreezing() {
        EdtProbe probe = new EdtProbe();
        probe.start();
        long t0 = System.nanoTime();
        onEdt(() -> frame.show(MainFrame.MANAGE));
        JTable table = waitFor("manage table", 10000, () -> find(frame, JTable.class, t -> true));
        waitFor("100,000 rows", 30000, () -> onEdt(() -> table.getRowCount()) == 100_000);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        sleep(500);
        long gapLoad = probe.stopAndGetMaxGapMs();
        System.out.printf("        [gui-large] Manage: 100,000 rows after %d ms, longest EDT freeze %d ms%n", loadMs, gapLoad);
        assertTrue(gapLoad <= MAX_FREEZE_MS, "Manage froze for " + gapLoad + " ms while loading");

        probe.start();
        setText(find(frame, Ui.RoundField.class, f -> true), "slang1a");
        waitFor("filtered rows", 10000, () -> { int r = onEdt(() -> table.getRowCount()); return r > 0 && r < 100_000 ? r : null; });
        sleep(500);
        long gapFilter = probe.stopAndGetMaxGapMs();
        System.out.printf("        [gui-large] Manage filter: longest EDT freeze %d ms%n", gapFilter);
        assertTrue(gapFilter <= MAX_FREEZE_MS, "Manage froze for " + gapFilter + " ms while filtering");
        setText(find(frame, Ui.RoundField.class, f -> true), "");
        waitFor("all rows again", 10000, () -> onEdt(() -> table.getRowCount()) == 100_000);
    }

    @Test @Order(4) void addingAWordOn100kEntriesKeepsTheWindowResponsiveWhileSaving() throws Exception {
        onEdt(() -> frame.show(MainFrame.MANAGE));
        sleep(300);
        EdtProbe probe = new EdtProbe();
        probe.start();
        withDialog(() -> clickAsync(button(frame, "Add word")), d -> {
            Ui.RoundField f = find(d, Ui.RoundField.class, x -> true);
            JTextArea a = find(d, JTextArea.class, x -> true);
            onEdt(() -> { f.setText("ZZBIGADD"); a.setText("added while 100k loaded"); });
            click(button(d, "Save"));
        });
        long t0 = System.nanoTime();
        waitFor("saved", 15000, () -> service.countForSlang("ZZBIGADD") == 1);
        waitFor("UI idle again", 15000, () -> onEdt(() -> button(frame, "Add word").isEnabled()));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        sleep(500);
        long gap = probe.stopAndGetMaxGapMs();
        System.out.printf("        [gui-large] Add on 100,000 entries: UI enabled again after %d ms, longest EDT freeze %d ms%n", ms, gap);
        assertTrue(gap <= MAX_FREEZE_MS, "window froze for " + gap + " ms during save");
        DictionaryService again = new DictionaryService(new FileStorage(tmp));
        again.load();
        assertEquals(1, again.countForSlang("ZZBIGADD"));
    }
}
