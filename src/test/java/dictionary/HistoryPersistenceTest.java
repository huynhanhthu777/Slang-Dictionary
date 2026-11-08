package dictionary;

import dictionary.model.SearchRecord;
import dictionary.service.DictionaryService;
import dictionary.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lưu history nền bằng revision counter: mọi thay đổi đều được ghi nhận, chỉ một tác vụ ghi chạy mỗi lúc,
 * thay đổi xen vào lúc đang ghi vẫn được lưu, flushHistory() chờ xong, và lỗi ghi không bị coi là đã lưu.
 */
class HistoryPersistenceTest {
    @TempDir Path tmp;
    Path dir;

    @BeforeEach void setUp() throws Exception { dir = tempAppSmall(tmp); }

    private DictionaryService open() throws IOException { return open(new FileStorage(dir)); }

    private static DictionaryService open(FileStorage st) throws IOException {
        DictionaryService s = new DictionaryService(st);
        s.load();
        return s;
    }

    private static List<String> keywords(DictionaryService s) {
        List<String> out = new ArrayList<>();
        for (SearchRecord r : s.history()) out.add(r.keyword());
        return out;
    }

    // ------------------------------------------------------------------ 1) tìm kiếm liên tục

    @Test void hundredBackToBackSearchesAreAllSaved() throws Exception {
        var s = open();
        List<String> expectedNewestFirst = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            s.searchSlang("kw" + i, true);
            expectedNewestFirst.add(0, "kw" + i);
        }
        assertTrue(s.flushHistory(), "flush must report that everything is saved");
        assertNull(s.historySaveError());
        assertEquals(expectedNewestFirst, keywords(s), "in memory");
        assertEquals(expectedNewestFirst, keywords(open()), "on disk after reopening: all 100, newest first, none lost");
    }

    @Test void searchesFromSeveralThreadsAreAllSaved() throws Exception {
        var s = open();
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            int id = t;
            ts[t] = new Thread(() -> { for (int i = 0; i < 50; i++) s.searchSlang("t" + id + "-" + i, true); });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertTrue(s.flushHistory());
        assertEquals(200, open().history().size());
        assertEquals(keywords(s), keywords(open()));
    }

    // ------------------------------------------------------------------ 2) tìm rồi đóng app ngay

    @Test void searchThenCloseImmediatelyKeepsTheLatestHistoryOnReopen() throws Exception {
        var slow = new FlakyStorage(dir) {
            @Override protected void beforeWrite(Path t) throws IOException {
                super.beforeWrite(t);
                if (t.getFileName().toString().equals("history.tsv")) { try { Thread.sleep(150); } catch (InterruptedException ignored) { } }
            }
        };
        var s = open(slow);
        for (int i = 0; i < 20; i++) s.searchSlang("quick" + i, true);
        assertTrue(s.flushHistory(), "closing the app must wait for the save to finish");   // = "đóng ngay"
        var reopened = open();
        assertEquals(20, reopened.history().size());
        assertEquals("quick19", reopened.history().get(0).keyword(), "latest search must be there");
    }

    @Test void searchMadeWhileAWriteIsInProgressIsStillSaved() throws Exception {
        var holder = new AtomicReference<DictionaryService>();
        var writes = new AtomicInteger();
        var slow = new FlakyStorage(dir) {
            @Override protected void beforeWrite(Path t) throws IOException {
                if (t.getFileName().toString().equals("history.tsv") && writes.incrementAndGet() == 1) {
                    holder.get().searchSlang("during-write", true);     // lịch sử đổi đúng lúc đang ghi
                }
            }
        };
        var s = open(slow);
        holder.set(s);
        s.searchSlang("first", true);
        assertTrue(s.flushHistory());
        assertTrue(writes.get() >= 2, "a second write must pick up the change made during the first");
        assertEquals(List.of("during-write", "first"), keywords(open()));
    }

    // ------------------------------------------------------------------ 3) lỗi ghi history.tsv

    @Test void failedHistoryWriteIsNeverReportedAsSavedAndIsRetriedLater() throws Exception {
        var st = new FlakyStorage(dir);
        var s = open(st);
        var notified = new AtomicReference<IOException>();
        s.setHistoryErrorListener(notified::set);

        s.searchSlang("ok-before", true);
        assertTrue(s.flushHistory());

        st.failOn = "history.tsv";                      // ổ đĩa bắt đầu lỗi
        s.searchSlang("lost-1", true);
        s.searchSlang("lost-2", true);
        assertFalse(s.flushHistory(), "must NOT claim success when the file could not be written");
        assertNotNull(s.historySaveError());
        assertTrue(s.historySaveError().getMessage().contains("history.tsv"));
        assertNotNull(notified.get(), "the error listener (UI) is told");
        assertEquals(List.of("lost-2", "lost-1", "ok-before"), keywords(s), "memory still has them");
        assertEquals(List.of("ok-before"), keywords(open()), "disk still holds only the last good save, not a half-written file");

        st.failOn = null;                               // ổ đĩa tốt lại: lần thay đổi kế tiếp lưu cả những lần chưa lưu được
        s.searchSlang("after-recovery", true);
        assertTrue(s.flushHistory());
        assertNull(s.historySaveError(), "error cleared after a successful save");
        assertEquals(List.of("after-recovery", "lost-2", "lost-1", "ok-before"), keywords(open()));
    }

    @Test void flushRetriesAfterABackgroundFailureWithoutNeedingAnotherSearch() throws Exception {
        var st = new FlakyStorage(dir);
        st.failOn = "history.tsv";
        st.failuresLeft = 1;                            // đúng một lần lỗi rồi tự hết
        var s = open(st);
        s.searchSlang("retry-me", true);
        assertTrue(s.flushHistory(), "flush retries the pending save and succeeds");
        assertEquals(List.of("retry-me"), keywords(open()));
    }

    @Test void historyFailureDoesNotAffectSearchResultsOrDictionaryData() throws Exception {
        var st = new FlakyStorage(dir);
        st.failOn = "history.tsv";
        var s = open(st);
        int before = s.repository().size();
        String existing = s.repository().all().get(0).getSlang();
        assertFalse(s.searchSlang(existing, true).entries().isEmpty(), "search results are unaffected by the history failure");
        s.add("ZZHISTFAIL", "dictionary saving is independent of history");
        assertEquals(before + 1, open().repository().size(), "dictionary data is still saved normally");
        assertFalse(s.flushHistory());
        assertFalse(Files.exists(new FileStorage(dir).historyFile()) && Files.size(new FileStorage(dir).historyFile()) > 0);
    }

    @Test void clearedHistoryStillWinsOverQueuedSaves() throws Exception {
        var s = open();
        for (int i = 0; i < 30; i++) s.searchSlang("x" + i, true);
        s.clearHistory();
        s.searchSlang("after-clear", true);
        assertTrue(s.flushHistory());
        assertEquals(List.of("after-clear"), keywords(open()));
    }
}
