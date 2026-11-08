package dictionary;

import dictionary.service.DataCorruptedException;
import dictionary.service.DictionaryService;
import dictionary.service.DictionaryService.Source;
import dictionary.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P0: ứng dụng bị dừng / ổ đĩa lỗi sau TỪNG bước ghi (working text, backup, snapshot) thì khi mở lại
 * phải đọc được một phiên bản hợp lệ nguyên vẹn (trước hoặc sau thao tác, không bao giờ trộn) hoặc báo lỗi rõ ràng.
 * Không có file "manifest" riêng: version ID + số entry nằm trong header của working text và snapshot,
 * nên các bước ghi file kể trên là toàn bộ các bước ghi của một lần lưu.
 */
class PersistenceFaultTest {
    @TempDir Path tmp;

    /** Các thao tác cần thử. Mỗi thao tác chạy trên trạng thái có sẵn mục ZZTARGET. */
    private enum Op {
        ADD((s, id) -> s.add("ZZNEW", "new meaning")),
        UPDATE((s, id) -> s.update(id, "ZZTARGET2", "edited meaning")),
        DELETE((s, id) -> s.delete(id)),
        OVERWRITE((s, id) -> s.overwrite("ZZTARGET", "overwritten")),
        RESET((s, id) -> s.resetToOriginal());

        interface Action { void run(DictionaryService s, long id) throws Exception; }
        final Action action;
        Op(Action a) { action = a; }
    }

    /** Dựng thư mục "trạng thái nền": đã nạp, đã có thay đổi của người dùng (nên có đủ snapshot + backup + text). */
    private Path baseState(String name) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        tempAppSmall(dir);
        DictionaryService s = new DictionaryService(new FileStorage(dir));
        s.load();
        s.add("ZZEARLIER", "an earlier change");
        s.add("ZZTARGET", "target meaning");
        return dir;
    }

    private static long targetId(DictionaryService s) { return s.searchSlang("ZZTARGET", false).entries().get(0).getId(); }

    private static DictionaryService open(Path dir) throws IOException {
        DictionaryService s = new DictionaryService(new FileStorage(dir));
        s.load();
        return s;
    }

    private static List<String> fp(Path dir) throws IOException { return fingerprint(open(dir)); }

    private Path copyOf(Path base, String name) throws IOException {
        Path d = tmp.resolve(name);
        Files.createDirectories(d);
        copyTree(base, d);
        return d;
    }

    /** Số điểm ghi của một thao tác + trạng thái "sau" khi thao tác thành công. */
    private record Reference(int points, List<String> pre, List<String> post) { }

    private Reference reference(Op op, Path base) throws Exception {
        Path d = copyOf(base, "ref-" + op + "-" + System.nanoTime());
        List<String> pre = fp(d);
        FaultStorage fs = new FaultStorage(d);
        DictionaryService s = new DictionaryService(fs);
        s.load();
        fs.calls = 0;
        op.action.run(s, targetId(s));
        return new Reference(fs.calls, pre, fingerprint(s));
    }

    // ------------------------------------------------------------------ crash tại từng điểm ghi

    @Test void processKilledAtEveryWritePointLeavesAWholeValidVersion() throws Exception {
        Path base = baseState("base-crash");
        for (Op op : Op.values()) {
            Reference ref = reference(op, base);
            assertTrue(ref.points() >= 6, op + " should have several write points, had " + ref.points());
            assertNotEquals(ref.pre(), ref.post(), op + " must actually change something");
            for (int k = 1; k <= ref.points(); k++) {
                Path d = copyOf(base, "crash-" + op + "-" + k);
                FaultStorage fs = new FaultStorage(d);
                DictionaryService s = new DictionaryService(fs);
                s.load();
                fs.calls = 0;
                fs.crashAt = k;
                long id = targetId(s);
                assertThrows(Fixtures.SimulatedCrash.class, () -> op.action.run(s, id), op + " k=" + k);

                // "khởi động lại": bộ nhớ mất, chỉ còn file trên đĩa
                DictionaryService restarted = new DictionaryService(new FileStorage(d));
                restarted.load();      // không được ném lỗi và không được tự reset
                List<String> now = fingerprint(restarted);
                assertTrue(now.equals(ref.pre()) || now.equals(ref.post()),
                        op + " crashed at point " + k + " (" + fs.log.get(k - 1) + "): state is neither the version before nor after the change");

                // sau khi phục hồi, ứng dụng phải ghi tiếp được và dữ liệu bền
                restarted.add("ZZAFTERCRASH", "still works");
                assertEquals(1, open(d).searchSlang("ZZAFTERCRASH", false).entries().size(), op + " k=" + k);
            }
        }
    }

    @Test void crashBeforeRenameLeavesTempFileThatIsIgnoredAndOverwrittenLater() throws Exception {
        Path base = baseState("base-tmp");
        Path d = copyOf(base, "tmpcase");
        FaultStorage fs = new FaultStorage(d);
        DictionaryService s = new DictionaryService(fs);
        s.load();
        fs.calls = 0;
        fs.crashAt = 2;   // rename point của working text (điểm 1 = before, 2 = rename)
        assertThrows(Fixtures.SimulatedCrash.class, () -> s.add("ZZTMP", "x"));
        assertTrue(Files.exists(fs.workingTextFile().resolveSibling("slang.working.txt.tmp")), "half-finished temp file left behind");
        DictionaryService r = new DictionaryService(new FileStorage(d));
        assertEquals(Source.SAVED_STRUCTURES, r.load().source(), "previous complete version is used");
        assertEquals(0, r.searchSlang("ZZTMP", false).entries().size());
        r.add("ZZTMP", "x");
        assertEquals(1, open(d).searchSlang("ZZTMP", false).entries().size());
    }

    // ------------------------------------------------------------------ lỗi ổ đĩa (có báo lỗi) tại từng điểm ghi

    @Test void transientDiskErrorAtEveryWritePointIsReportedRolledBackAndDiskMatchesMemory() throws Exception {
        Path base = baseState("base-io");
        for (Op op : Op.values()) {
            Reference ref = reference(op, base);
            for (int k = 1; k <= ref.points(); k++) {
                Path d = copyOf(base, "io-" + op + "-" + k);
                FaultStorage fs = new FaultStorage(d);
                DictionaryService s = new DictionaryService(fs);
                s.load();
                fs.calls = 0;
                fs.failAt = k;                      // đúng một lần lỗi, sau đó đĩa "tốt lại"
                long id = targetId(s);
                long versionBefore = s.dataVersion();
                assertThrows(IOException.class, () -> op.action.run(s, id), op + " k=" + k);
                assertEquals(ref.pre(), fingerprint(s), op + " k=" + k + ": memory must be rolled back");
                assertEquals(versionBefore <= s.dataVersion(), true);
                assertEquals(ref.pre(), fp(d), op + " k=" + k + ": disk must match memory after a reported failure");
            }
        }
    }

    @Test void persistentDiskFailureNeverCorruptsAndNeverSilentlyResets() throws Exception {
        Path base = baseState("base-dead");
        for (Op op : Op.values()) {
            Reference ref = reference(op, base);
            for (int k = 1; k <= ref.points(); k++) {
                Path d = copyOf(base, "dead-" + op + "-" + k);
                FaultStorage fs = new FaultStorage(d);
                DictionaryService s = new DictionaryService(fs);
                s.load();
                fs.calls = 0;
                fs.failAt = k;
                fs.failPersistently = true;         // đĩa chết hẳn từ điểm k, kể cả lúc cố ghi lại để hoàn tác
                long id = targetId(s);
                assertThrows(IOException.class, () -> op.action.run(s, id), op + " k=" + k);
                assertEquals(ref.pre(), fingerprint(s), op + " k=" + k + ": memory rolled back");
                List<String> onDisk = fp(d);        // đĩa "sống lại": phải mở được
                assertTrue(onDisk.equals(ref.pre()) || onDisk.equals(ref.post()), op + " k=" + k + ": whole version expected on disk");
            }
        }
    }

    // ------------------------------------------------------------------ bước xác minh sau khi ghi

    @Test void writeThatLandsCorruptOnDiskIsDetectedImmediatelyNotAtNextStartup() throws Exception {
        Consumer<Path> flip = p -> {
            try { byte[] b = Files.readAllBytes(p); b[b.length / 2] ^= 0x55; Files.write(p, b); }
            catch (IOException e) { throw new RuntimeException(e); }
        };
        Consumer<Path> truncate = p -> {
            try { byte[] b = Files.readAllBytes(p); Files.write(p, java.util.Arrays.copyOf(b, b.length - 25)); }
            catch (IOException e) { throw new RuntimeException(e); }
        };
        Object[][] cases = {{"dictionary.dat", flip}, {"dictionary.dat", truncate}, {"slang.working.txt", truncate}};
        for (Object[] c : cases) {
            Path d = copyOf(baseState("base-verify-" + c[0] + System.nanoTime()), "verify-" + System.nanoTime());
            FaultStorage fs = new FaultStorage(d);
            DictionaryService s = new DictionaryService(fs);
            s.load();
            List<String> pre = fingerprint(s);
            fs.tamperFile = (String) c[0];
            @SuppressWarnings("unchecked") Consumer<Path> t = (Consumer<Path>) c[1];
            fs.tamper = t;
            IOException ex = assertThrows(IOException.class, () -> s.add("ZZVERIFY", "x"));
            assertTrue(ex.getMessage().contains("verification failed"), ex.getMessage());
            assertEquals(pre, fingerprint(s), "memory rolled back");
            fs.tamper = null;
            assertEquals(pre, fp(d), "after the failed save the disk was rewritten consistently");
        }
    }

    // ------------------------------------------------------------------ file đã nằm trên đĩa bị hỏng theo mọi kiểu

    private enum Damage {
        ZERO_LENGTH, HALF_TRUNCATED, GARBAGE, FLIPPED_BYTE, DELETED;
        void apply(Path p) throws IOException {
            byte[] b = Files.readAllBytes(p);
            switch (this) {
                case ZERO_LENGTH -> Files.write(p, new byte[0]);
                case HALF_TRUNCATED -> Files.write(p, java.util.Arrays.copyOf(b, b.length / 2));
                case GARBAGE -> { byte[] g = new byte[b.length]; new Random(5).nextBytes(g); Files.write(p, g); }
                case FLIPPED_BYTE -> { b[b.length * 3 / 4] ^= 0x01; Files.write(p, b); }
                case DELETED -> Files.delete(p);
            }
        }
    }

    @Test void anySingleDamagedFileNeverLosesAnyData() throws Exception {
        Path base = baseState("base-single");
        List<String> current = fp(base);
        FileStorage ref = new FileStorage(base);
        for (Path victim : List.of(ref.snapshotFile(), ref.workingTextFile(), ref.backupFile())) {
            for (Damage dmg : Damage.values()) {
                Path d = copyOf(base, "single-" + victim.getFileName() + "-" + dmg);
                dmg.apply(d.resolve(base.relativize(victim)));
                assertEquals(current, fp(d), victim.getFileName() + " " + dmg + ": all data must still be there");
            }
        }
    }

    @Test void twoDamagedFilesFallBackToTheRemainingOneAndSayIfSomethingMayBeMissing() throws Exception {
        Path base = baseState("base-pair");
        FileStorage ref = new FileStorage(base);
        List<String> current = fp(base);
        // bản backup = phiên bản liền trước (chưa có ZZTARGET)
        List<String> previous = fingerprint(copyStorage(base, "prev", ref.backupFile()));

        for (Damage dmg : new Damage[]{Damage.GARBAGE, Damage.ZERO_LENGTH, Damage.HALF_TRUNCATED}) {
            Path a = copyOf(base, "pair-a-" + dmg);          // hỏng snapshot + bak -> còn working text (mới nhất)
            dmg.apply(a.resolve(base.relativize(ref.snapshotFile())));
            dmg.apply(a.resolve(base.relativize(ref.backupFile())));
            assertEquals(current, fp(a));

            Path b = copyOf(base, "pair-b-" + dmg);          // hỏng text + bak -> còn snapshot (mới nhất)
            dmg.apply(b.resolve(base.relativize(ref.workingTextFile())));
            dmg.apply(b.resolve(base.relativize(ref.backupFile())));
            assertEquals(current, fp(b));

            Path c = copyOf(base, "pair-c-" + dmg);          // hỏng snapshot + text -> chỉ còn backup: có thể thiếu thay đổi cuối
            dmg.apply(c.resolve(base.relativize(ref.snapshotFile())));
            dmg.apply(c.resolve(base.relativize(ref.workingTextFile())));
            DictionaryService s = new DictionaryService(new FileStorage(c));
            var report = s.load();
            assertEquals(Source.BACKUP_SNAPSHOT, report.source());
            assertNotNull(report.warning(), "user must be told recent changes may be missing");
            assertTrue(report.warning().contains("backup"));
            assertEquals(previous, fingerprint(s), "the backup is a complete earlier version");
        }
    }

    private DictionaryService copyStorage(Path base, String name, Path bakFile) throws Exception {
        // dựng service chỉ từ file backup để biết "phiên bản liền trước" chính xác là gì
        Path d = tmp.resolve("only-" + name);
        Files.createDirectories(d.resolve("data/working"));
        tempAppSmall(d);
        Files.copy(bakFile, d.resolve("data/working/dictionary.dat"));
        DictionaryService s = new DictionaryService(new FileStorage(d));
        s.load();
        return s;
    }

    @Test void allThreeFilesDamagedRaisesClearErrorAndTouchesNothing() throws Exception {
        Path d = baseState("base-all");
        FileStorage st = new FileStorage(d);
        for (Path p : List.of(st.snapshotFile(), st.workingTextFile(), st.backupFile())) Damage.GARBAGE.apply(p);
        byte[] snap = Files.readAllBytes(st.snapshotFile());
        byte[] text = Files.readAllBytes(st.workingTextFile());
        DataCorruptedException ex = assertThrows(DataCorruptedException.class, () -> new DictionaryService(new FileStorage(d)).load());
        assertTrue(ex.getMessage().contains("unreadable"), ex.getMessage());
        assertArrayEquals(snap, Files.readAllBytes(st.snapshotFile()));
        assertArrayEquals(text, Files.readAllBytes(st.workingTextFile()));
    }
}
