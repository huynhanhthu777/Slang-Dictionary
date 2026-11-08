package dictionary;

import dictionary.model.SearchType;
import dictionary.service.DataCorruptedException;
import dictionary.service.DictionaryService;
import dictionary.service.DictionaryService.Source;
import dictionary.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryServiceTest {
    @TempDir Path tmp;
    Path dir;

    @BeforeEach void setUp() throws Exception { dir = tempApp(tmp); }

    private DictionaryService open() throws IOException {
        DictionaryService s = new DictionaryService(new FileStorage(dir));
        s.load();
        return s;
    }

    private static void garble(Path p) throws IOException { Files.write(p, new byte[]{1, 2, 3, 4}); }

    // ---------- khởi động ----------

    @Test void firstLaunchBuildsFromNormalizedThenSecondReusesSavedIndex() throws Exception {
        var r1 = new DictionaryService(new FileStorage(dir)).load();
        assertEquals(Source.ORIGINAL, r1.source());
        assertFalse(r1.indexReused());
        var r2 = new DictionaryService(new FileStorage(dir)).load();
        assertEquals(Source.SAVED_STRUCTURES, r2.source());
        assertTrue(r2.indexReused());
        assertEquals(0, r2.indexMillis(), "no re-indexing on warm start");
        assertEquals(r1.entries(), r2.entries());
        assertEquals(7656, r2.entries());
        assertEquals(5, r2.repaired() >= 5 ? 5 : r2.repaired(), "report counts are available");
        assertEquals(0, r2.quarantined());
    }

    @Test void addEditDeleteSurviveRestartAndOriginalFilesAreUntouched() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        byte[] raw = Files.readAllBytes(st.rawFile()), norm = Files.readAllBytes(st.normalizedFile());
        int n = s.repository().size();
        var e = s.add("zzTest", "my test meaning");
        s.update(e.getId(), "zzTest2", "second");
        var s2 = open();
        assertEquals(n + 1, s2.repository().size());
        assertEquals("second", s2.searchSlang("ZZTEST2", false).entries().get(0).getDefinition());
        s2.delete(e.getId());
        assertEquals(n, open().repository().size());
        assertArrayEquals(raw, Files.readAllBytes(st.rawFile()));
        assertArrayEquals(norm, Files.readAllBytes(st.normalizedFile()));
    }

    @Test void overwriteReplacesAllDuplicatesKeepBothAddsAnother() throws Exception {
        var s = open();
        s.add("DUPW", "zzone");
        s.add("DUPW", "zztwo");
        assertEquals(2, s.countForSlang("dupw"));
        s.overwrite("dupw", "only meaning");
        assertEquals(1, s.countForSlang("DUPW"));
        s.add("DUPW", "second meaning");
        assertEquals(2, s.countForSlang("DUPW"));
        assertEquals(2, open().countForSlang("DUPW"), "persisted");
    }

    @Test void resetRestoresNormalizedDataAndDropsUserChanges() throws Exception {
        var s = open();
        int n = s.repository().size();
        s.add("temp1", "x");
        s.delete(s.searchSlang("LOL", false).entries().get(0).getId());
        s.resetToOriginal();
        assertEquals(n, s.repository().size());
        assertEquals(0, s.searchSlang("temp1", false).entries().size());
        assertEquals(1, s.searchSlang("LOL", false).entries().size());
        assertEquals(n, open().repository().size(), "reset is persisted");
    }

    @Test void historyIsRecordedPersistedNewestFirstAndClearable() throws Exception {
        var s = open();
        s.searchSlang("lol", true);
        s.searchDefinition("later", true);
        s.searchSlang("   ", true);
        s.suggest("lo", 5);
        s.flushHistory();
        var h = s.history();
        assertEquals(2, h.size());
        assertEquals(SearchType.DEFINITION, h.get(0).type());
        var s2 = open();
        assertEquals(2, s2.history().size());
        s2.clearHistory();
        s2.flushHistory();
        assertEquals(0, s2.history().size());
        assertEquals(0, open().history().size());
    }

    // ---------- version ID chung ----------

    @Test void everySuccessfulSaveBumpsOneSharedVersionIdOnAllFiles() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        long v0 = s.dataVersion();
        s.add("V1", "x");
        assertEquals(v0 + 1, s.dataVersion());
        assertEquals(s.dataVersion(), st.readPrimarySnapshot().dataVersion);
        assertEquals(s.dataVersion(), st.readWorkingText().dataVersion());
        assertEquals(s.dataVersion() - 1, st.readBackupSnapshot().dataVersion);
    }

    // ---------- lỗi khi lưu: hoàn tác, không báo thành công giả ----------

    @Test void failedSaveRollsBackMemoryAndReportsError() throws Exception {
        var st = new FlakyStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        int n = s.repository().size();
        long v = s.dataVersion();
        st.failOn = "dictionary.dat";
        assertThrows(IOException.class, () -> s.add("ZZNOPE", "x"));
        assertEquals(n, s.repository().size(), "memory rolled back");
        assertEquals(0, s.searchSlang("zznope", false).entries().size());
        assertEquals(v, s.dataVersion(), "failed save is not acknowledged");
        st.failOn = null;
        assertEquals(n, open().repository().size(), "disk does not contain the failed change");
    }

    @Test void failureBetweenWorkingTextAndSnapshotDoesNotLeaveAHiddenChange() throws Exception {
        var st = new FlakyStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        st.failOn = "dictionary.dat";
        st.failuresLeft = 1;                         // chỉ lần ghi snapshot đầu tiên (sau working text) bị lỗi
        assertThrows(IOException.class, () -> s.add("HIDDEN", "should not appear"));
        var restarted = open();
        assertEquals(0, restarted.searchSlang("hidden", false).entries().size(),
                "the working text written before the failure must have been rewritten to match memory");
        s.add("AFTER", "ok");
        assertEquals(1, open().searchSlang("after", false).entries().size(), "service still works after a failure");
    }

    @Test void failedWorkingTextWriteAlsoRollsBack() throws Exception {
        var st = new FlakyStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        int n = s.repository().size();
        st.failOn = "slang.working.txt";
        assertThrows(IOException.class, () -> s.delete(s.searchSlang("LOL", false).entries().get(0).getId()));
        assertEquals(n, s.repository().size());
        assertEquals(1, s.searchSlang("LOL", false).entries().size());
    }

    @Test void failedResetKeepsCurrentData() throws Exception {
        var st = new FlakyStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        s.add("KEEPME", "x");
        st.failOn = "dictionary.dat";
        assertThrows(IOException.class, s::resetToOriginal);
        assertEquals(1, s.searchSlang("keepme", false).entries().size(), "reset failure must not lose current data");
        st.failOn = null;
        assertEquals(1, open().searchSlang("keepme", false).entries().size());
    }

    // ---------- phục hồi ----------

    @Test void corruptSnapshotRecoversFromWorkingTextWithoutLosingUserWords() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        s.add("BACKUP_TEST", "saved");
        s.add("LATER_TEST", "latest");
        garble(st.snapshotFile());
        var rec = new DictionaryService(st);
        var report = rec.load();
        assertEquals(Source.WORKING_TEXT, report.source());
        assertNotNull(report.warning());
        assertEquals(1, rec.searchSlang("LATER_TEST", false).entries().size(), "newest change kept");
        assertEquals(1, rec.searchSlang("BACKUP_TEST", false).entries().size());
        assertTrue(Files.exists(st.corruptFile()), "damaged copy preserved");
        rec.add("AFTER_RECOVERY", "x");
        assertEquals(1, open().searchSlang("AFTER_RECOVERY", false).entries().size(), "writable after recovery");
        assertEquals(Source.SAVED_STRUCTURES, new DictionaryService(st).load().source(), "primary snapshot repaired");
    }

    @Test void bitFlipInSnapshotIsCaughtByCrcAndRecovered() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        s.add("FLIP", "x");
        byte[] b = Files.readAllBytes(st.snapshotFile());
        b[b.length / 2] ^= 0x7F;
        Files.write(st.snapshotFile(), b);
        var rec = new DictionaryService(st);
        assertEquals(Source.WORKING_TEXT, rec.load().source());
        assertEquals(1, rec.searchSlang("flip", false).entries().size());
    }

    @Test void crashAfterWorkingTextBeforeSnapshotLoadsTheNewerWorkingText() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        s.add("ZZONE", "1");
        // mô phỏng crash: working text version mới đã ghi, snapshot vẫn là version cũ
        byte[] oldSnapshot = Files.readAllBytes(st.snapshotFile());
        s.add("ZZTWO", "2");
        Files.write(st.snapshotFile(), oldSnapshot);
        var rec = new DictionaryService(st);
        var report = rec.load();
        assertEquals(Source.WORKING_TEXT, report.source());
        assertEquals(1, rec.searchSlang("zztwo", false).entries().size(), "the newer version wins, never a mixed state");
        assertEquals(1, rec.searchSlang("zzone", false).entries().size());
    }

    @Test void staleWorkingTextIsRefreshedFromNewerSnapshot() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        byte[] oldText = Files.readAllBytes(st.workingTextFile());
        s.add("NEWER", "x");
        Files.write(st.workingTextFile(), oldText);       // working text bị tụt phiên bản
        var rec = new DictionaryService(st);
        var report = rec.load();
        assertEquals(Source.SAVED_STRUCTURES, report.source());
        assertEquals(1, rec.searchSlang("newer", false).entries().size());
        assertEquals(rec.dataVersion(), st.readWorkingText().dataVersion(), "versions realigned");
    }

    @Test void snapshotAndWorkingTextBothBadFallsBackToBackup() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        s.add("INBACKUP", "x");     // bak = trước khi thêm, primary = sau
        s.add("LOST", "y");         // bak = có INBACKUP
        garble(st.snapshotFile());
        garble(st.workingTextFile());
        var rec = new DictionaryService(st);
        var report = rec.load();
        assertEquals(Source.BACKUP_SNAPSHOT, report.source());
        assertNotNull(report.warning());
        assertEquals(1, rec.searchSlang("inbackup", false).entries().size());
        assertEquals(0, rec.searchSlang("lost", false).entries().size(), "only the newest change is lost, and the user is warned");
    }

    @Test void everythingCorruptRefusesToResetAndPreservesFiles() throws Exception {
        var st = new FileStorage(dir);
        new DictionaryService(st).load();
        garble(st.snapshotFile());
        garble(st.workingTextFile());
        Files.deleteIfExists(st.backupFile());
        assertThrows(DataCorruptedException.class, () -> new DictionaryService(st).load());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(st.snapshotFile()), "damaged file untouched");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(st.workingTextFile()));
    }

    @Test void consentedRestartMovesDamagedFilesAndRebuildsFromOriginal() throws Exception {
        var st = new FileStorage(dir);
        new DictionaryService(st).load();
        garble(st.snapshotFile());
        garble(st.workingTextFile());
        Files.deleteIfExists(st.backupFile());
        var s = new DictionaryService(st);
        assertThrows(DataCorruptedException.class, s::load);
        var report = s.quarantineDamagedDataAndReload();
        assertEquals(Source.ORIGINAL, report.source());
        assertEquals(7656, s.repository().size());
        try (var dirs = Files.list(dir.resolve("data"))) {
            assertTrue(dirs.anyMatch(p -> p.getFileName().toString().startsWith("working-damaged-")), "damaged files kept");
        }
    }

    @Test void inconsistentIndexInsideValidCrcIsRejected() throws Exception {
        var st = new FileStorage(dir);
        var repo = sample();
        var snap = repo.toSnapshot(9);
        snap.idsBySlang.remove("lol");                 // CRC sẽ hợp lệ nhưng index lệch dữ liệu
        st.commit(snap);
        var s = new DictionaryService(st);
        var report = s.load();
        assertEquals(Source.WORKING_TEXT, report.source(), "inconsistent index must not be trusted");
        assertEquals(1, s.searchSlang("lol", false).entries().size());
    }

    @Test void historyFileDamageNeverBlocksStartup() throws Exception {
        var st = new FileStorage(dir);
        var s = new DictionaryService(st);
        s.load();
        s.searchSlang("lol", true);
        s.flushHistory();
        garble(st.historyFile());
        var s2 = open();
        assertEquals(0, s2.history().size());
        assertEquals(7656, s2.repository().size());
    }

    @Test void concurrentSearchesWhileWritingStayConsistent() throws Exception {
        var s = open();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 400; i++) {
                try { s.searchDefinition("love", false); s.suggest("a", 20); s.searchSlang("lol", false); }
                catch (RuntimeException e) { failures.incrementAndGet(); }
            }
        });
        reader.start();
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) ids.add(s.add("CONC" + i, "m" + i).getId());
        for (long id : ids) s.delete(id);
        reader.join();
        assertEquals(0, failures.get());
        assertEquals(7656, open().repository().size());
    }

    @Test void searchReturnsWithoutWaitingForTheHistoryFileToBeWritten() throws Exception {
        var slow = new FlakyStorage(dir) {
            @Override protected void beforeWrite(java.nio.file.Path t) throws IOException {
                if (t.getFileName().toString().equals("history.tsv")) { try { Thread.sleep(600); } catch (InterruptedException ignored) { } }
            }
        };
        var s = new DictionaryService(slow);
        s.load();
        long t0 = System.nanoTime();
        s.searchSlang("lol", true);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 300, "the search must not wait for the disk (took " + ms + " ms)");
        s.flushHistory();
        assertEquals(1, open().history().size(), "history still reaches the disk");
    }

    @Test void clearedHistoryIsNeverResurrectedByAPendingBackgroundSave() throws Exception {
        var s = open();
        for (int i = 0; i < 30; i++) s.searchSlang("lol", true);
        s.clearHistory();
        s.flushHistory();
        assertEquals(0, open().history().size());
    }
}
