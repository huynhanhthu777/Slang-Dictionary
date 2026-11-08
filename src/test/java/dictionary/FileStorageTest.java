package dictionary;

import dictionary.model.SlangEntry;
import dictionary.repository.DictionaryRepository;
import dictionary.repository.Snapshot;
import dictionary.storage.FileStorage;
import dictionary.storage.WorkingText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {
    @TempDir Path tmp;
    FlakyStorage st;
    DictionaryRepository repo;

    @BeforeEach void setUp() throws Exception {
        st = new FlakyStorage(tempApp(tmp));
        repo = sample();
    }

    private void corruptByte(Path p, int offsetFromEnd) throws IOException {
        byte[] b = Files.readAllBytes(p);
        b[b.length - offsetFromEnd] ^= 0x55;
        Files.write(p, b);
    }

    @Test void commitThenReadReturnsSameData() throws Exception {
        st.commit(repo.toSnapshot(5));
        Snapshot s = st.readPrimarySnapshot();
        assertEquals(5, s.dataVersion);
        assertEquals(6, s.entries.size());
        WorkingText t = st.readWorkingText();
        assertEquals(5, t.dataVersion());
        assertEquals(6, t.entries().size());
        assertEquals("LOL", t.entries().get(0).getSlang(), "order preserved in readable copy");
    }

    @Test void flippedByteIsDetectedByCrc() throws Exception {
        st.commit(repo.toSnapshot(1));
        corruptByte(st.snapshotFile(), 10);
        var ex = assertThrows(IOException.class, () -> st.readPrimarySnapshot());
        assertTrue(ex.getMessage().contains("checksum"), ex.getMessage());
    }

    @Test void truncatedFileIsDetected() throws Exception {
        st.commit(repo.toSnapshot(1));
        byte[] b = Files.readAllBytes(st.snapshotFile());
        Files.write(st.snapshotFile(), java.util.Arrays.copyOf(b, b.length / 2));
        assertThrows(IOException.class, () -> st.readPrimarySnapshot());
    }

    @Test void wrongMagicAndGarbageAreRejected() throws Exception {
        Files.createDirectories(st.workingDir());
        Files.write(st.snapshotFile(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(IOException.class, () -> st.readPrimarySnapshot());
        Files.write(st.snapshotFile(), new byte[0]);
        assertThrows(IOException.class, () -> st.readPrimarySnapshot());
    }

    @Test void workingTextTruncationIsDetectedByEntryCount() throws Exception {
        st.commit(repo.toSnapshot(3));
        List<String> lines = Files.readAllLines(st.workingTextFile());
        Files.write(st.workingTextFile(), lines.subList(0, lines.size() - 2));
        assertThrows(IOException.class, () -> st.readWorkingText());
    }

    @Test void workingTextWithoutHeaderIsRejected() throws Exception {
        Files.createDirectories(st.workingDir());
        Files.writeString(st.workingTextFile(), "A`b\n");
        assertThrows(IOException.class, () -> st.readWorkingText());
    }

    @Test void backupIsTheSnapshotBeforeTheLatestCommit() throws Exception {
        st.commit(repo.toSnapshot(1));
        st.readPrimarySnapshot();
        repo.add("ONE", "more");
        st.commit(repo.toSnapshot(2));
        assertEquals(1, st.readBackupSnapshot().dataVersion);
        assertEquals(2, st.readPrimarySnapshot().dataVersion);
    }

    @Test void damagedPrimaryNeverOverwritesAGoodBackup() throws Exception {
        st.commit(repo.toSnapshot(1));
        st.readPrimarySnapshot();
        st.commit(repo.toSnapshot(2));            // bak = v1, primary = v2
        corruptByte(st.snapshotFile(), 10);
        assertThrows(IOException.class, () -> st.readPrimarySnapshot());   // primary now known bad
        st.commit(repo.toSnapshot(3));            // must NOT copy the damaged primary onto .bak
        assertEquals(1, st.readBackupSnapshot().dataVersion, "good backup preserved");
    }

    @Test void failedWriteLeavesPreviousSnapshotIntactAndNoTempFile() throws Exception {
        st.commit(repo.toSnapshot(1));
        repo.add("NEW", "x");
        st.failOn = "dictionary.dat";
        assertThrows(IOException.class, () -> st.commit(repo.toSnapshot(2)));
        st.failOn = null;
        assertEquals(1, st.readPrimarySnapshot().dataVersion, "old snapshot untouched");
        assertFalse(Files.exists(st.snapshotFile().resolveSibling("dictionary.dat.tmp")), "no half-written temp file left");
    }

    @Test void historyRoundTripAndBadLinesAreIgnored() throws Exception {
        var list = new java.util.ArrayList<dictionary.model.SearchRecord>();
        list.add(new dictionary.model.SearchRecord(java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5),
                dictionary.model.SearchType.SLANG, "tab\there\\x", 3));
        st.saveHistory(list);
        Files.writeString(st.historyFile(), Files.readString(st.historyFile()) + "garbage line\n");
        var back = st.loadHistory();
        assertEquals(1, back.size());
        assertEquals("tab\there\\x", back.get(0).keyword());
    }

    @Test void quarantineMovesWorkingFolderWithoutDeletingAnything() throws Exception {
        st.commit(repo.toSnapshot(1));
        Path moved = st.quarantineWorkingDir();
        assertNotNull(moved);
        assertTrue(Files.exists(moved.resolve("dictionary.dat")));
        assertFalse(Files.exists(st.workingDir()));
    }

    @Test void resourceFallbackExtractsFilesWhenFolderIsEmpty(@TempDir Path empty) throws Exception {
        FileStorage fresh = new FileStorage(empty);
        fresh.ensureOriginal();   // dùng bản đóng gói trong JAR/classpath
        assertTrue(Files.exists(fresh.rawFile()));
        assertTrue(Files.exists(fresh.normalizedFile()));
        assertEquals(7656, fresh.readNormalized().entries().size());
    }
}
