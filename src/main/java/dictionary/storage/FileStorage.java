package dictionary.storage;

import dictionary.model.ParseResult;
import dictionary.model.SearchRecord;
import dictionary.model.SearchType;
import dictionary.model.SlangEntry;
import dictionary.repository.Snapshot;

import java.io.*;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Tầng lưu trữ: MỌI thao tác file nằm ở đây (UI và service không đụng file).
 *
 * <pre>
 *  data/original/slang.raw.txt          file gốc của đề, GIỮ NGUYÊN, app không bao giờ ghi
 *  data/original/slang.normalized.txt   dữ liệu chuẩn hóa MỘT LẦN từ file raw; app không bao giờ ghi
 *  data/original/malformed-report.txt   báo cáo các dòng đã sửa / cách ly khi chuẩn hóa
 *  data/working/dictionary.dat          snapshot (HashMap + index) có magic, version ID và CRC32
 *  data/working/dictionary.dat.bak      snapshot hợp lệ liền trước
 *  data/working/slang.working.txt       bản text đọc được, cùng version ID (nguồn phục hồi)
 *  data/working/history.tsv             lịch sử tìm kiếm
 * </pre>
 *
 * <b>Một lần lưu (commit)</b> = (1) ghi working text version V, (2) xoay backup, (3) ghi snapshot version V.
 * Snapshot là "dấu xác nhận": khi mở app, snapshot và working text cùng version V thì dùng snapshot (không
 * index lại); lệch version thì chọn bản MỚI HƠN còn hợp lệ. Mọi file ghi theo kiểu file tạm -> fsync -> đổi tên.
 */
public class FileStorage {
    public static final String RAW_RESOURCE = "/data/slang.raw.txt";
    public static final String NORMALIZED_RESOURCE = "/data/slang.normalized.txt";
    public static final String REPORT_RESOURCE = "/data/malformed-report.txt";
    static final String WORKING_HEADER_PREFIX = "#SLANGDICT-WORKING|";
    private static final int MAGIC = 0x53444943; // "SDIC"
    private static final String FILTER = "maxdepth=10;maxarray=50000000;"
            + "dictionary.repository.Snapshot;dictionary.model.SlangEntry;"
            + "java.util.HashMap;java.util.ArrayList;java.lang.Long;java.lang.Number;java.lang.String;java.lang.Object;"
            + "java.util.Map$Entry;!*";

    private final Path baseDir;
    private final Path originalDir;
    private final Path workingDir;
    /** true khi ta biết chắc file snapshot chính trên đĩa hợp lệ (vừa đọc/ghi thành công) -> được phép xoay thành .bak. */
    private volatile boolean primaryKnownGood;

    public FileStorage(Path baseDir) {
        this.baseDir = baseDir;
        this.originalDir = baseDir.resolve("data").resolve("original");
        this.workingDir = baseDir.resolve("data").resolve("working");
    }

    public Path baseDir() { return baseDir; }
    public Path originalDir() { return originalDir; }
    public Path workingDir() { return workingDir; }
    public Path rawFile() { return originalDir.resolve("slang.raw.txt"); }
    public Path normalizedFile() { return originalDir.resolve("slang.normalized.txt"); }
    public Path reportFile() { return originalDir.resolve("malformed-report.txt"); }
    public Path snapshotFile() { return workingDir.resolve("dictionary.dat"); }
    public Path backupFile() { return workingDir.resolve("dictionary.dat.bak"); }
    public Path corruptFile() { return workingDir.resolve("dictionary.dat.corrupt"); }
    public Path workingTextFile() { return workingDir.resolve("slang.working.txt"); }
    public Path historyFile() { return workingDir.resolve("history.tsv"); }

    /** Thư mục chứa "data": -Ddictionary.home, hoặc cạnh file JAR, hoặc thư mục người dùng nếu không ghi được. */
    public static Path resolveHome() {
        String prop = System.getProperty("dictionary.home");
        if (prop != null && !prop.isBlank()) return Paths.get(prop).toAbsolutePath();
        Path candidate = null;
        try {
            Path loc = Paths.get(FileStorage.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            candidate = Files.isRegularFile(loc) ? loc.getParent() : Paths.get("").toAbsolutePath();
        } catch (Exception ignored) { }
        if (candidate == null) candidate = Paths.get("").toAbsolutePath();
        return isWritable(candidate) ? candidate : Paths.get(System.getProperty("user.home"), ".slang-dictionary");
    }

    private static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir.resolve("data"));
            Path t = Files.createTempFile(dir.resolve("data"), "w", ".tmp");
            Files.delete(t);
            return true;
        } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ dữ liệu gốc

    /**
     * Bảo đảm có file gốc + file chuẩn hóa. Chuẩn hóa chỉ xảy ra MỘT LẦN: nếu slang.normalized.txt đã có thì không đụng tới.
     * Thiếu file raw thì lấy bản đóng gói trong JAR; thiếu normalized thì tạo từ raw (không bao giờ ghi đè raw).
     */
    public void ensureOriginal() throws IOException {
        Files.createDirectories(originalDir);
        if (!Files.exists(rawFile())) copyResourceIfPresent(RAW_RESOURCE, rawFile());
        if (!Files.exists(normalizedFile())) {
            if (Files.exists(rawFile())) {
                normalizeRaw();
            } else if (!copyResourceIfPresent(NORMALIZED_RESOURCE, normalizedFile())) {
                throw new FileNotFoundException("Original data not found: " + rawFile() + " (and no bundled copy)");
            }
        }
        if (!Files.exists(reportFile())) copyResourceIfPresent(REPORT_RESOURCE, reportFile());
    }

    /** raw -> normalized + report. Dùng cho lần đầu và cho công cụ NormalizeData. */
    public ParseResult normalizeRaw() throws IOException {
        ParseResult res = parseFile(rawFile(), SlangParser.Mode.SOURCE);
        if (res.entries().isEmpty()) throw new IOException("Raw data contains no valid entries");
        writeCanonical(normalizedFile(), res.entries(), null);
        atomicWrite(reportFile(), out -> out.write(res.toReportText().getBytes(StandardCharsets.UTF_8)));
        return res;
    }

    private boolean copyResourceIfPresent(String resource, Path target) throws IOException {
        try (InputStream in = FileStorage.class.getResourceAsStream(resource)) {
            if (in == null) return false;
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }

    public ParseResult readNormalized() throws IOException {
        return parseFile(normalizedFile(), SlangParser.Mode.SOURCE);
    }

    private static ParseResult parseFile(Path p, SlangParser.Mode mode) throws IOException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), dec), 1 << 16)) {
            return SlangParser.parse(r, mode);
        }
    }

    public List<String> readQualityReport() {
        try {
            if (Files.exists(reportFile())) return Files.readAllLines(reportFile(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------ working data

    public boolean hasAnyWorkingData() {
        return Files.exists(snapshotFile()) || Files.exists(backupFile()) || Files.exists(workingTextFile());
    }

    /** Đọc snapshot chính; ném IOException nếu thiếu/hỏng. */
    public Snapshot readPrimarySnapshot() throws IOException {
        try {
            Snapshot s = readSnapshot(snapshotFile());
            primaryKnownGood = true;
            return s;
        } catch (IOException | RuntimeException e) {
            primaryKnownGood = false;
            throw e;
        }
    }

    /** Đánh dấu snapshot chính là không đáng tin (vd. index lệch) để nó không bị sao thành .bak. */
    public void markPrimaryUnverified() { primaryKnownGood = false; }

    public Snapshot readBackupSnapshot() throws IOException { return readSnapshot(backupFile()); }

    private Snapshot readSnapshot(Path p) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not a dictionary data file");
            int format = in.readInt();
            if (format != Snapshot.FORMAT_VERSION) throw new IOException("unsupported format " + format);
            long version = in.readLong();
            long len = in.readLong();
            long expected = in.readLong();
            if (len < 0 || len > Integer.MAX_VALUE - 16) throw new IOException("bad length");
            byte[] payload = in.readNBytes((int) len);
            if (payload.length != len) throw new IOException("file is truncated");
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expected) throw new IOException("checksum mismatch");
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload))) {
                ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(FILTER));
                Object o = ois.readObject();
                if (!(o instanceof Snapshot s)) throw new IOException("unexpected content");
                if (s.dataVersion != version) throw new IOException("version id in header does not match content");
                return s;
            } catch (ClassNotFoundException e) {
                throw new IOException("unknown class " + e.getMessage());
            }
        }
    }

    /** Chỉ đọc dòng đầu của working text để biết version (rất nhanh); -1 nếu thiếu/không đọc được. */
    public long peekWorkingTextVersion() {
        try (BufferedReader r = Files.newBufferedReader(workingTextFile(), StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null || !first.startsWith(WORKING_HEADER_PREFIX)) return -1;
            for (String part : first.substring(WORKING_HEADER_PREFIX.length()).split("\\|")) {
                if (part.startsWith("version=")) return Long.parseLong(part.substring(8));
            }
        } catch (IOException | RuntimeException ignored) { }
        return -1;
    }

    /** Đọc working text: kiểm tra version trong dòng đầu và số entry khai báo (phát hiện file bị cắt). */
    public WorkingText readWorkingText() throws IOException {
        Path p = workingTextFile();
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), dec), 1 << 16)) {
            String first = r.readLine();
            if (first == null || !first.startsWith(WORKING_HEADER_PREFIX)) throw new IOException("working text has no version header");
            long version = -1;
            int declared = -1;
            for (String part : first.substring(WORKING_HEADER_PREFIX.length()).split("\\|")) {
                try {
                    if (part.startsWith("version=")) version = Long.parseLong(part.substring(8));
                    else if (part.startsWith("entries=")) declared = Integer.parseInt(part.substring(8));
                } catch (NumberFormatException e) { throw new IOException("bad working text header"); }
            }
            if (version < 0 || declared < 0) throw new IOException("incomplete working text header");
            ParseResult res = SlangParser.parse(r, SlangParser.Mode.WORKING);
            if (!res.quarantined().isEmpty()) throw new IOException("working text has " + res.quarantined().size() + " unreadable lines");
            if (res.entries().size() != declared) {
                throw new IOException("working text has " + res.entries().size() + " entries but declares " + declared + " (truncated?)");
            }
            return new WorkingText(version, declared, res.entries());
        }
    }

    /**
     * Lưu một phiên bản dữ liệu: working text -> xoay backup -> snapshot (dấu xác nhận). Chỉ trả về bình thường
     * khi CẢ HAI file đã nằm trên đĩa; lỗi ở bất kỳ bước nào ném IOException (service sẽ hoàn tác trong bộ nhớ).
     */
    public void commit(Snapshot snap) throws IOException {
        Files.createDirectories(workingDir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (ObjectOutputStream oos = new ObjectOutputStream(bytes)) { oos.writeObject(snap); }
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);

        writeCanonical(workingTextFile(), snap.entries, WORKING_HEADER_PREFIX + "format=1|version=" + snap.dataVersion
                + "|entries=" + snap.entries.size());
        verifyWorkingText(snap.dataVersion, snap.entries.size());
        rotateBackup();
        final long payloadCrc = crc.getValue();
        atomicWrite(snapshotFile(), raw -> {
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(MAGIC);
            out.writeInt(Snapshot.FORMAT_VERSION);
            out.writeLong(snap.dataVersion);
            out.writeLong(payload.length);
            out.writeLong(payloadCrc);
            out.write(payload);
            out.flush();
        });
        verifySnapshotFile(snapshotFile(), snap.dataVersion, payload.length, payloadCrc);
        primaryKnownGood = true;
    }

    /** Đọc lại working text vừa ghi: đúng version và đúng số dòng dữ liệu (phát hiện ghi thiếu/hỏng ngay lúc lưu). */
    private void verifyWorkingText(long version, int entries) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(workingTextFile(), StandardCharsets.UTF_8)) {
            String first = r.readLine();
            String expectedHeader = WORKING_HEADER_PREFIX + "format=1|version=" + version + "|entries=" + entries;
            if (!expectedHeader.equals(first)) throw new IOException("verification failed: working text header mismatch");
            int n = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.indexOf(SlangParser.DELIMITER) < 0) throw new IOException("verification failed: unreadable line in working text");
                n++;
            }
            if (n != entries) throw new IOException("verification failed: working text has " + n + " lines, expected " + entries);
        } catch (java.nio.charset.MalformedInputException e) {
            throw new IOException("verification failed: working text is not valid UTF-8 (unpaired surrogate in data?)", e);
        }
    }

    /** Đọc lại snapshot vừa ghi: header đúng version/độ dài và CRC32 của payload khớp (không cần giải tuần tự hóa). */
    private void verifySnapshotFile(Path p, long version, long length, long expectedCrc) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != Snapshot.FORMAT_VERSION) throw new IOException("verification failed: bad snapshot header");
            if (in.readLong() != version) throw new IOException("verification failed: snapshot version mismatch");
            if (in.readLong() != length || in.readLong() != expectedCrc) throw new IOException("verification failed: snapshot header mismatch");
            CRC32 c = new CRC32();
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int k;
            while ((k = in.read(buf)) > 0) { c.update(buf, 0, k); total += k; }
            if (total != length || c.getValue() != expectedCrc) throw new IOException("verification failed: snapshot content does not match checksum");
        }
    }

    /** Chỉ sao snapshot chính thành .bak khi biết chắc nó hợp lệ: không bao giờ để bản hỏng đè bản backup tốt. */
    private void rotateBackup() throws IOException {
        Path primary = snapshotFile();
        if (!primaryKnownGood || !Files.exists(primary)) return;
        atomicWrite(backupFile(), out -> Files.copy(primary, out), false);
    }

    /** Giữ lại bản snapshot chính đang hỏng để người dùng/giảng viên kiểm tra. */
    public void preserveCorruptPrimary() {
        try {
            if (Files.exists(snapshotFile())) Files.copy(snapshotFile(), corruptFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    /** Dời toàn bộ data/working sang thư mục riêng (khi người dùng đồng ý bắt đầu lại). Không xóa gì. */
    public Path quarantineWorkingDir() throws IOException {
        if (!Files.exists(workingDir)) return null;
        Path target = baseDir.resolve("data").resolve("working-damaged-" + System.currentTimeMillis());
        Files.move(workingDir, target);
        primaryKnownGood = false;
        return target;
    }

    private void writeCanonical(Path target, List<SlangEntry> entries, String headerLine) throws IOException {
        atomicWrite(target, raw -> {
            Writer w = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8), 1 << 16);
            if (headerLine != null) { w.write(headerLine); w.write('\n'); }
            for (SlangEntry e : entries) {
                w.write(e.getSlang()); w.write(SlangParser.DELIMITER); w.write(e.getDefinition()); w.write('\n');
            }
            w.flush();
        });
    }

    // ------------------------------------------------------------------ history

    public List<SearchRecord> loadHistory() {
        List<SearchRecord> out = new ArrayList<>();
        Path p = historyFile();
        if (!Files.exists(p)) return out;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", 4);
                if (f.length < 4) continue;
                try {
                    out.add(new SearchRecord(LocalDateTime.parse(f[0]), SearchType.valueOf(f[1]),
                            f[3].replace("\\t", "\t").replace("\\\\", "\\"), Integer.parseInt(f[2])));
                } catch (RuntimeException badLine) { /* history là dữ liệu phụ: dòng hỏng không ảnh hưởng dữ liệu chính */ }
            }
        } catch (IOException e) { /* lỗi đọc history không được làm app dừng */ }
        return out;
    }

    public void saveHistory(List<SearchRecord> list) throws IOException {
        atomicWrite(historyFile(), raw -> {
            Writer w = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8));
            for (SearchRecord s : list) {
                w.write(s.time() + "\t" + s.type().name() + "\t" + s.resultCount() + "\t"
                        + s.keyword().replace("\\", "\\\\").replace("\t", "\\t").replace("\n", " ") + "\n");
            }
            w.flush();
        }, false);
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface public interface IOWriter { void write(OutputStream out) throws IOException; }

    /** Điểm móc cho test mô phỏng lỗi/crash. 1) trước khi ghi bất cứ thứ gì vào file đích. */
    protected void beforeWrite(Path target) throws IOException { }

    /** 2) file tạm đã ghi xong + fsync nhưng CHƯA đổi tên (crash ở đây để lại file .tmp, file đích còn nguyên). */
    protected void beforeRename(Path target, Path tmp) throws IOException { }

    /** 3) đã đổi tên xong (test có thể phá file ở đây để kiểm tra bước xác minh sau khi lưu). */
    protected void afterWrite(Path target) throws IOException { }

    /** Ghi file tạm -> fsync -> đổi tên: mất điện giữa chừng không bao giờ để lại file ghi dở ở vị trí chính. */
    protected void atomicWrite(Path target, IOWriter writer) throws IOException { atomicWrite(target, writer, true); }

    /**
     * {@code sync=false} cho dữ liệu phụ (history, bản sao backup): vẫn "file tạm -> đổi tên" nên không bao giờ ghi dở,
     * nhưng bỏ fsync (trên macOS fsync rất chậm). Mất điện có thể mất vài lần tìm kiếm gần nhất, không hỏng file.
     */
    protected void atomicWrite(Path target, IOWriter writer, boolean sync) throws IOException {
        Files.createDirectories(target.getParent());
        beforeWrite(target);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             BufferedOutputStream out = new BufferedOutputStream(fos, 1 << 16)) {
            writer.write(out);
            out.flush();
            if (sync) fos.getFD().sync();
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw e;
        }
        try {
            beforeRename(target, tmp);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw e;
        }
        moveReplace(tmp, target);
        afterWrite(target);
    }

    private static void moveReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
