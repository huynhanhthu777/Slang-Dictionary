package dictionary.service;

import dictionary.model.*;
import dictionary.repository.CorruptDataException;
import dictionary.repository.DictionaryRepository;
import dictionary.repository.Snapshot;
import dictionary.storage.FileStorage;
import dictionary.storage.WorkingText;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Tầng nghiệp vụ: nối Repository với Storage, ghi history, và bảo đảm mỗi thay đổi
 * (dữ liệu + index + file trên đĩa) là MỘT thao tác: lưu lỗi thì hoàn tác trong bộ nhớ và báo lỗi.
 *
 * <p>Đồng bộ: repository tự khóa (synchronized). Thay đổi dữ liệu đi qua {@code writeMutex} (một người ghi mỗi lúc),
 * history có khóa riêng nên tìm kiếm không bao giờ phải chờ việc lưu dictionary.
 */
public class DictionaryService {
    public static final int MAX_HISTORY = 1000;
    public static final int MAX_SLANG_LEN = 100;
    public static final int MAX_DEF_LEN = 2000;
    private static final long HISTORY_FLUSH_TIMEOUT_SECONDS = 5;

    public enum Source {
        SAVED_STRUCTURES("Saved data structures (index reused, nothing re-indexed)"),
        WORKING_TEXT("Readable working copy (index rebuilt)"),
        BACKUP_SNAPSHOT("Backup snapshot"),
        ORIGINAL("Original normalized file (first launch, indexed and saved)");
        private final String label;
        Source(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Thông tin lần nạp dữ liệu (Settings + benchmark khởi động). */
    public record LoadReport(Source source, int entries, int distinctSlang, long loadMillis, long readMillis,
                             long indexMillis, boolean indexReused, int repaired, int quarantined,
                             long dataVersion, String warning) {
        public String sourceLabel() { return source.label(); }
    }

    private final FileStorage storage;
    private final DictionaryRepository repo = new DictionaryRepository();
    private final Object writeMutex = new Object();
    private final Object historyLock = new Object();
    /** Giữ cho đúng MỘT lần ghi history tại một thời điểm; lần ghi luôn lấy trạng thái mới nhất lúc ghi. */
    private final Object historyIoLock = new Object();
    private final java.util.concurrent.ExecutorService historyIO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dictionary-history");
        t.setDaemon(true);
        return t;
    });
    private final List<SearchRecord> history = new ArrayList<>();
    /**
     * Revision counter của history: tăng (dưới {@code historyLock}) mỗi khi history thay đổi.
     * {@code savedHistoryRevision} là revision mới nhất đã GHI XONG xuống đĩa; tác vụ lưu chỉ kết thúc khi hai số này bằng nhau.
     */
    private long historyRevision;
    private volatile long savedHistoryRevision;
    /** Đã có tác vụ lưu nền đang chạy/đã xếp hàng (chỉ đọc/ghi dưới {@code historyLock}). */
    private boolean historyWorkerActive;
    /** Lỗi của lần ghi history gần nhất (null = lần ghi gần nhất thành công hoặc chưa có lỗi). */
    private volatile IOException historySaveError;
    private volatile java.util.function.Consumer<IOException> historyErrorListener;
    private final Random random = new Random();
    /** Version ID của bản dữ liệu đã lưu thành công; versionCounter tăng theo mỗi lần THỬ lưu (không bao giờ dùng lại). */
    private volatile long dataVersion;
    private long versionCounter;
    private volatile LoadReport lastReport;

    public DictionaryService(FileStorage storage) { this.storage = storage; }

    public DictionaryRepository repository() { return repo; }
    public FileStorage storage() { return storage; }
    public LoadReport lastReport() { return lastReport; }
    public long dataVersion() { return dataVersion; }

    // ------------------------------------------------------------------ nạp dữ liệu

    /**
     * Thứ tự: chọn bản MỚI NHẤT còn hợp lệ trong {snapshot chính, working text, snapshot backup}.
     * Cùng version -> ưu tiên snapshot chính (nạp thẳng index). Không còn bản nào đọc được mà vẫn có file làm việc
     * -> ném {@link DataCorruptedException}, KHÔNG tự reset dữ liệu người dùng.
     */
    public LoadReport load() throws IOException {
        synchronized (writeMutex) {
            long t0 = System.nanoTime();
            storage.ensureOriginal();
            List<String> warnings = new ArrayList<>();
            Source source;
            long readMs, indexMs = 0;
            boolean reused = false;

            if (!storage.hasAnyWorkingData()) {
                long t = System.nanoTime();
                ParseResult pr = storage.readNormalized();
                readMs = ms(t);
                if (pr.entries().isEmpty()) throw new IOException("Original data contains no valid entries");
                t = System.nanoTime();
                repo.loadEntries(pr.entries());
                indexMs = ms(t);
                source = Source.ORIGINAL;
                versionCounter = 0;
                persist();
            } else {
                long t = System.nanoTime();
                Snapshot snap = tryPrimary(warnings);
                // Đường nhanh: chỉ đọc dòng header của working text để so version; chỉ parse đầy đủ khi cần phục hồi.
                long headerVersion = storage.peekWorkingTextVersion();
                WorkingText text = (snap == null || headerVersion > snap.dataVersion) ? tryText(warnings) : null;
                Snapshot bak = (snap == null) ? tryBackup(warnings) : null;
                readMs = ms(t);

                long vSnap = snap != null ? snap.dataVersion : -1;
                long vText = text != null ? text.dataVersion() : -1;
                long vBak = bak != null ? bak.dataVersion : -1;
                if (snap == null && text == null && bak == null) {
                    throw new DataCorruptedException("Your working data and its backup are unreadable. The files were left untouched. "
                            + String.join(" ", warnings), null);
                }
                long newest = Math.max(vSnap, Math.max(vText, vBak));
                boolean needRewrite = false;
                if (snap != null && vSnap == newest) {
                    loadSnapshotInto(snap);
                    source = Source.SAVED_STRUCTURES;
                    reused = true;
                    versionCounter = newest;
                    if (headerVersion != vSnap) {
                        needRewrite = true;
                        warnings.add("The readable working copy was missing or out of date; it was refreshed.");
                    }
                } else if (text != null && vText == newest) {
                    long ti = System.nanoTime();
                    repo.loadEntries(text.entries());
                    indexMs = ms(ti);
                    source = Source.WORKING_TEXT;
                    versionCounter = newest;
                    needRewrite = true;
                    warnings.add(snap == null
                            ? "The saved data structures were unusable; your dictionary was rebuilt from the readable working copy."
                            : "The saved data structures were older than the working copy; rebuilt from the working copy.");
                } else {
                    loadSnapshotInto(bak);
                    source = Source.BACKUP_SNAPSHOT;
                    versionCounter = newest;
                    needRewrite = true;
                    warnings.add("Main data was unreadable; loaded the backup. Recent changes may be missing.");
                }
                if (snap == null) storage.preserveCorruptPrimary();
                if (needRewrite) {
                    try { persist(); } catch (IOException e) { warnings.add("Could not re-save the recovered data: " + e.getMessage()); }
                } else {
                    dataVersion = newest;
                }
            }
            repo.warmUp();
            history.clear();
            history.addAll(storage.loadHistory());

            int[] counts = readReportCounts();
            lastReport = new LoadReport(source, repo.size(), repo.distinctSlangCount(), ms(t0), readMs, indexMs, reused,
                    counts[0], counts[1], dataVersion, warnings.isEmpty() ? null : String.join(" ", warnings));
            return lastReport;
        }
    }

    private Snapshot tryPrimary(List<String> warnings) {
        if (!java.nio.file.Files.exists(storage.snapshotFile())) return null;
        try {
            Snapshot s = storage.readPrimarySnapshot();
            DictionaryRepository.validate(s);
            return s;
        } catch (IOException | RuntimeException | CorruptDataException e) {
            storage.markPrimaryUnverified();
            warnings.add("dictionary.dat could not be used (" + e.getMessage() + ").");
            return null;
        }
    }

    private Snapshot tryBackup(List<String> warnings) {
        if (!java.nio.file.Files.exists(storage.backupFile())) return null;
        try {
            Snapshot s = storage.readBackupSnapshot();
            DictionaryRepository.validate(s);
            return s;
        } catch (IOException | RuntimeException | CorruptDataException e) {
            warnings.add("dictionary.dat.bak could not be used (" + e.getMessage() + ").");
            return null;
        }
    }

    private WorkingText tryText(List<String> warnings) {
        if (!java.nio.file.Files.exists(storage.workingTextFile())) return null;
        try {
            return storage.readWorkingText();
        } catch (IOException | RuntimeException e) {
            warnings.add("slang.working.txt could not be used (" + e.getMessage() + ").");
            return null;
        }
    }

    private void loadSnapshotInto(Snapshot s) throws IOException {
        try { repo.loadSnapshot(s); } catch (CorruptDataException e) { throw new DataCorruptedException(e.getMessage(), e); }
    }

    private static long ms(long fromNanos) { return (System.nanoTime() - fromNanos) / 1_000_000; }

    private int[] readReportCounts() {
        int repaired = 0, quarantined = 0;
        for (String line : storage.readQualityReport()) {
            if (line.contains(" -> AUTO_REPAIRED]")) repaired++;
            else if (line.contains(" -> QUARANTINED]")) quarantined++;
        }
        return new int[]{repaired, quarantined};
    }

    /**
     * Người dùng ĐỒNG Ý bắt đầu lại sau khi dữ liệu hỏng hoàn toàn: dời data/working sang thư mục
     * "working-damaged-…" (không xóa) rồi nạp lại từ file chuẩn hóa.
     */
    public LoadReport quarantineDamagedDataAndReload() throws IOException {
        synchronized (writeMutex) {
            Path moved = storage.quarantineWorkingDir();
            LoadReport r = load();
            String note = moved == null ? null : "Damaged files were moved to " + moved.getFileName() + ".";
            lastReport = new LoadReport(r.source(), r.entries(), r.distinctSlang(), r.loadMillis(), r.readMillis(), r.indexMillis(),
                    r.indexReused(), r.repaired(), r.quarantined(), r.dataVersion(), note);
            return lastReport;
        }
    }

    /** Khôi phục dữ liệu gốc: dựng lại từ file chuẩn hóa; chỉ thay dữ liệu đang dùng sau khi lưu thành công. */
    public void resetToOriginal() throws IOException {
        synchronized (writeMutex) {
            storage.ensureOriginal();
            ParseResult pr = storage.readNormalized();
            if (pr.entries().isEmpty()) throw new IOException("Original data contains no valid entries");
            Snapshot before = repo.toSnapshot(dataVersion);
            DictionaryRepository fresh = new DictionaryRepository();
            fresh.loadEntries(pr.entries());
            long v = ++versionCounter;
            try {
                storage.commit(fresh.toSnapshot(v));
            } catch (IOException | RuntimeException e) {
                rewriteAfterFailure(before);
                throw e;
            }
            try { repo.loadSnapshot(fresh.toSnapshot(v)); } catch (CorruptDataException e) { throw new IOException(e); }
            repo.warmUp();
            dataVersion = v;
            int[] counts = readReportCounts();
            LoadReport old = lastReport;
            lastReport = new LoadReport(Source.ORIGINAL, repo.size(), repo.distinctSlangCount(), old == null ? 0 : old.loadMillis(),
                    old == null ? 0 : old.readMillis(), 0, false, counts[0], counts[1], dataVersion, null);
        }
    }

    // ------------------------------------------------------------------ lưu

    /** Lưu trạng thái hiện tại với version ID mới. Chỉ khi thành công mới công nhận version đó. */
    private void persist() throws IOException {
        long v = ++versionCounter;
        storage.commit(repo.toSnapshot(v));
        dataVersion = v;
    }

    /** Sau khi lưu lỗi: hoàn tác bộ nhớ rồi cố làm cho đĩa khớp bộ nhớ (version mới hơn). Không bao giờ ném lỗi. */
    private void rewriteAfterFailure(Snapshot memoryState) {
        try {
            long v = ++versionCounter;
            Snapshot s = new Snapshot(v, memoryState.nextId, memoryState.entries, memoryState.idsBySlang);
            storage.commit(s);
            dataVersion = v;
        } catch (IOException | RuntimeException ignored) {
            // Đĩa đang lỗi thật sự: lần lưu thành công kế tiếp sẽ ghi đè với version cao hơn.
        }
    }

    private void persistOrUndo(Runnable undo) throws IOException {
        try {
            persist();
        } catch (IOException | RuntimeException e) {
            undo.run();
            try { rewriteAfterFailure(repo.toSnapshot(dataVersion)); } catch (RuntimeException ignored) { }
            if (e instanceof IOException io) throw io;
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ kiểm tra dữ liệu nhập

    /** Ký tự không được phép xuất hiện trong slang: ký tự điều khiển (kể cả \n, \r, \t, NEL) và U+2028/U+2029. */
    private static boolean isForbidden(char c) {
        return Character.isISOControl(c) || c == '\u2028' || c == '\u2029';
    }

    /** Surrogate lẻ không thể ghi ra UTF-8 (sẽ thành '?'), làm dữ liệu đọc lại khác dữ liệu đã lưu. */
    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++;
                else return true;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Slang chỉ được cắt khoảng trắng hai đầu; nội dung đã được {@link #validateSlang} đảm bảo sạch. */
    public static String cleanSlang(String s) {
        return s == null ? "" : s.trim();
    }

    /** Definition cho phép người dùng gõ nhiều dòng: xuống dòng/ký tự điều khiển được đổi thành một dấu cách. */
    public static String cleanDefinition(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(isForbidden(c) ? ' ' : c);
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    /** Kiểm tra slang NGUYÊN BẢN người dùng nhập (không tự sửa rồi cho qua). @return thông báo lỗi, hoặc null nếu hợp lệ. */
    public static String validateSlang(String slang) {
        if (slang == null || slang.isBlank()) return "Slang word must not be empty.";
        for (int i = 0; i < slang.length(); i++) {
            if (isForbidden(slang.charAt(i))) return "Slang word must be a single line: line breaks, tabs and control characters are not allowed.";
        }
        if (slang.indexOf('`') >= 0) return "Slang word must not contain the ` character.";
        if (hasUnpairedSurrogate(slang)) return "Slang word contains an invalid Unicode character.";
        if (slang.trim().length() > MAX_SLANG_LEN) return "Slang word is too long (max " + MAX_SLANG_LEN + ").";
        return null;
    }

    public static String validateDefinition(String def) {
        String d = cleanDefinition(def);
        if (d.isBlank()) return "Definition must not be empty.";
        if (hasUnpairedSurrogate(d)) return "Definition contains an invalid Unicode character.";
        if (d.length() > MAX_DEF_LEN) return "Definition is too long (max " + MAX_DEF_LEN + ").";
        return null;
    }

    private static void require(String slang, String def) {
        String err = validateSlang(slang);
        if (err == null) err = validateDefinition(def);
        if (err != null) throw new IllegalArgumentException(err);
    }

    // ------------------------------------------------------------------ tìm kiếm

    public SearchResult searchSlang(String query, boolean record) {
        long t0 = System.nanoTime();
        List<SlangEntry> found = repo.findBySlang(query);
        long dt = System.nanoTime() - t0;
        if (record) addHistory(SearchType.SLANG, query, found.size());
        return new SearchResult(found, dt);
    }

    public SearchResult suggest(String prefix, int limit) {
        long t0 = System.nanoTime();
        List<SlangEntry> found = repo.findByPrefix(prefix, limit);
        return new SearchResult(found, System.nanoTime() - t0);
    }

    public SearchResult searchDefinition(String keyword, boolean record) {
        long t0 = System.nanoTime();
        List<SlangEntry> found = repo.findByDefinition(keyword);
        long dt = System.nanoTime() - t0;
        if (record) addHistory(SearchType.DEFINITION, keyword, found.size());
        return new SearchResult(found, dt);
    }

    /** Gợi ý hoàn thành từ khóa khi tìm theo definition. */
    public List<String> suggestDefinitionWords(String prefix, int limit) { return repo.suggestDefinitionWords(prefix, limit); }

    /** Từ khóa mẫu cho hàng "Try:" ở chế độ tìm theo definition. */
    public List<String> popularDefinitionWords(int count) { return repo.popularDefinitionWords(count, random); }

    // ------------------------------------------------------------------ history (khóa riêng)

    private void addHistory(SearchType type, String keyword, int count) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) return;
        synchronized (historyLock) {
            history.add(new SearchRecord(LocalDateTime.now().withNano(0), type, k, count));
            while (history.size() > MAX_HISTORY) history.remove(0);
            historyRevision++;
        }
        scheduleHistorySave();      // KHÔNG ghi đĩa trong đường tìm kiếm: kết quả hiện ngay, history lưu nền
    }

    /** Bảo đảm có đúng một tác vụ nền đang lo việc lưu; nếu đã có thì tác vụ đó sẽ tự thấy revision mới và lưu tiếp. */
    private void scheduleHistorySave() {
        synchronized (historyLock) {
            if (historyWorkerActive) return;
            historyWorkerActive = true;
        }
        try {
            historyIO.execute(this::historyWorkerLoop);
        } catch (RuntimeException e) {
            synchronized (historyLock) { historyWorkerActive = false; }
            throw e;
        }
    }

    /**
     * Lưu lặp lại tới khi revision đã lưu == revision mới nhất. Việc kiểm tra "xong chưa" và hạ cờ active nằm trong
     * cùng một khối {@code historyLock} với việc tăng revision, nên một thay đổi xen vào không bao giờ bị bỏ sót.
     * Lỗi ghi: ghi nhận + báo listener rồi dừng (không quay vòng); lần thay đổi/flush kế tiếp sẽ thử lại với bản mới nhất.
     */
    private void historyWorkerLoop() {
        while (true) {
            synchronized (historyLock) {
                if (savedHistoryRevision >= historyRevision) { historyWorkerActive = false; return; }
            }
            try {
                saveHistoryNow();
            } catch (IOException | RuntimeException e) {
                synchronized (historyLock) { historyWorkerActive = false; }
                reportHistoryError(e);
                return;
            }
        }
    }

    /** Ghi bản history mới nhất. Chỉ khi ghi xong mới công nhận revision đó là đã lưu; thất bại thì ném lỗi. */
    private void saveHistoryNow() throws IOException {
        synchronized (historyIoLock) {
            List<SearchRecord> copy;
            long rev;
            synchronized (historyLock) { copy = new ArrayList<>(history); rev = historyRevision; }
            storage.saveHistory(copy);
            if (rev > savedHistoryRevision) savedHistoryRevision = rev;
            historySaveError = null;
        }
    }

    private void reportHistoryError(Exception e) {
        IOException io = e instanceof IOException x ? x : new IOException(e.getMessage(), e);
        historySaveError = io;
        var l = historyErrorListener;
        if (l != null) {
            try { l.accept(io); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Chờ cho tới khi history MỚI NHẤT đã nằm trên đĩa (gọi khi đóng app và trong test).
     * Nếu tác vụ nền đã lỗi, flush thử ghi lại một lần nữa.
     *
     * @return true nếu mọi thay đổi history đã được lưu; false nếu ghi thất bại hoặc quá thời gian chờ
     *         (khi đó {@link #historySaveError()} cho biết lý do và KHÔNG được coi là đã lưu).
     */
    public boolean flushHistory() {
        try {
            return historyIO.submit(() -> {
                while (true) {
                    synchronized (historyLock) {
                        if (savedHistoryRevision >= historyRevision) return true;
                    }
                    try { saveHistoryNow(); }
                    catch (IOException | RuntimeException e) { reportHistoryError(e); return false; }
                }
            }).get(HISTORY_FLUSH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            reportHistoryError(new IOException("Timed out while saving search history.", e));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            reportHistoryError(e);
            return false;
        }
    }

    /** Lỗi của lần ghi history gần nhất, hoặc null nếu lần gần nhất thành công. */
    public IOException historySaveError() { return historySaveError; }

    /** Đăng ký nơi nhận thông báo khi ghi history nền thất bại (chạy trên luồng nền, UI tự chuyển về EDT). */
    public void setHistoryErrorListener(java.util.function.Consumer<IOException> listener) { this.historyErrorListener = listener; }

    /** Mới nhất trước. */
    public List<SearchRecord> history() {
        synchronized (historyLock) {
            List<SearchRecord> copy = new ArrayList<>(history);
            Collections.reverse(copy);
            return copy;
        }
    }

    public void clearHistory() throws IOException {
        List<SearchRecord> backup;
        synchronized (historyLock) { backup = new ArrayList<>(history); history.clear(); historyRevision++; }
        try {
            saveHistoryNow();
        } catch (IOException e) {
            synchronized (historyLock) { history.addAll(0, backup); }
            throw e;
        }
    }

    // ------------------------------------------------------------------ thêm / sửa / xoá

    public boolean slangExists(String slang) { return repo.slangExists(slang); }

    public int countForSlang(String slang) { return repo.findBySlang(slang).size(); }

    /** Thêm entry mới (cho phép trùng slang: "giữ cả hai"). */
    public SlangEntry add(String slang, String definition) throws IOException {
        require(slang, definition);
        synchronized (writeMutex) {
            SlangEntry e = repo.add(cleanSlang(slang), cleanDefinition(definition));
            persistOrUndo(() -> repo.delete(e.getId()));
            return e;
        }
    }

    /** Ghi đè: mọi entry cùng slang được thay bằng đúng một entry với definition mới. */
    public SlangEntry overwrite(String slang, String definition) throws IOException {
        require(slang, definition);
        synchronized (writeMutex) {
            List<SlangEntry> existing = repo.findBySlang(slang);
            if (existing.isEmpty()) return add(slang, definition);
            SlangEntry first = existing.get(0);
            SlangEntry updated = repo.update(first.getId(), cleanSlang(slang), cleanDefinition(definition));
            List<SlangEntry> removed = new ArrayList<>();
            for (int i = 1; i < existing.size(); i++) removed.add(repo.delete(existing.get(i).getId()));
            persistOrUndo(() -> {
                repo.restore(first);
                for (SlangEntry r : removed) repo.restore(r);
            });
            return updated;
        }
    }

    public SlangEntry update(long id, String slang, String definition) throws IOException {
        require(slang, definition);
        synchronized (writeMutex) {
            SlangEntry old = repo.get(id);
            if (old == null) throw new IllegalArgumentException("This entry no longer exists.");
            SlangEntry fresh = repo.update(id, cleanSlang(slang), cleanDefinition(definition));
            persistOrUndo(() -> repo.update(id, old.getSlang(), old.getDefinition()));
            return fresh;
        }
    }

    public void delete(long id) throws IOException {
        synchronized (writeMutex) {
            SlangEntry e;
            try { e = repo.delete(id); }
            catch (java.util.NoSuchElementException ex) { throw new IllegalArgumentException("This entry no longer exists."); }
            persistOrUndo(() -> repo.restore(e));
        }
    }

    // ------------------------------------------------------------------ tiện ích

    public SlangEntry randomEntry() { return repo.random(random); }

    /** Slang của ngày: cố định trong một ngày. */
    public SlangEntry slangOfTheDay() {
        return repo.random(new Random(LocalDate.now().toEpochDay() * 2654435761L));
    }

    /** Danh sách sắp xếp A-Z (không phân biệt hoa/thường) cho trang quản lý. */
    public List<SlangEntry> allSorted() {
        List<SlangEntry> list = repo.all();
        list.sort(Comparator.comparing(SlangEntry::key).thenComparingLong(SlangEntry::getId));
        return list;
    }
}
