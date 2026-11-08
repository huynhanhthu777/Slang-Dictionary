package dictionary;

import dictionary.model.ParseResult;
import dictionary.repository.DictionaryRepository;
import dictionary.storage.FileStorage;
import dictionary.storage.SlangParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Hàm dùng chung cho test: thư mục app tạm chứa bản sao file raw thật của đề. */
public final class Fixtures {
    private Fixtures() { }

    public static final Path REAL_DATA = Path.of("data", "original");

    static ParseResult parse(String text) throws IOException {
        return SlangParser.parse(new BufferedReader(new StringReader(text)));
    }

    static ParseResult parseWorking(String text) throws IOException {
        return SlangParser.parse(new BufferedReader(new StringReader(text)), SlangParser.Mode.WORKING);
    }

    static DictionaryRepository sample() throws IOException {
        DictionaryRepository r = new DictionaryRepository();
        r.loadEntries(parse("LOL`Laughing out loud\nBRB`Be right back\nAMA`Ask me anything\nAMA`Against medical advice\n"
                + "GOAT`Greatest of all time\nTTYL`Talk to you later\n").entries());
        return r;
    }

    /** Thư mục app mới với file raw thật; file normalized/report sẽ được app tạo ở lần chạy đầu. */
    static Path tempApp(Path tmp) throws IOException {
        Path orig = tmp.resolve("data/original");
        Files.createDirectories(orig);
        Files.copy(REAL_DATA.resolve("slang.raw.txt"), orig.resolve("slang.raw.txt"), StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }

    /** Như tempApp nhưng chỉ ~300 dòng đầu của file raw thật: để test lặp nhiều lần chạy nhanh. */
    static Path tempAppSmall(Path tmp) throws IOException {
        Path orig = tmp.resolve("data/original");
        Files.createDirectories(orig);
        List<String> lines = Files.readAllLines(REAL_DATA.resolve("slang.raw.txt"), java.nio.charset.StandardCharsets.UTF_8);
        Files.write(orig.resolve("slang.raw.txt"), lines.subList(0, 300), java.nio.charset.StandardCharsets.UTF_8);
        return tmp;
    }

    /** Toàn bộ nội dung từ điển dưới dạng danh sách "slang`definition" đã sắp xếp: dùng để so sánh trạng thái. */
    static List<String> fingerprint(dictionary.service.DictionaryService s) {
        List<String> out = new ArrayList<>();
        for (var e : s.repository().all()) out.add(e.getSlang() + "`" + e.getDefinition());
        Collections.sort(out);
        return out;
    }

    /** FileStorage cho phép mô phỏng lỗi ghi: ném IOException khi ghi vào file có tên khớp. */
    static class FlakyStorage extends FileStorage {
        volatile String failOn;       // tên file đích sẽ bị lỗi, null = không lỗi
        volatile int failuresLeft = Integer.MAX_VALUE;
        FlakyStorage(Path base) { super(base); }
        @Override protected void beforeWrite(Path target) throws IOException {
            if (failOn != null && target.getFileName().toString().equals(failOn) && failuresLeft-- > 0) {
                throw new IOException("simulated write failure on " + target.getFileName());
            }
        }
    }

    /** Mô phỏng tiến trình bị giết đột ngột: là Error nên KHÔNG đi qua code hoàn tác của service (bộ nhớ coi như mất). */
    static final class SimulatedCrash extends Error {
        SimulatedCrash(String m) { super(m); }
    }

    /**
     * Đánh số mọi "điểm ghi" (trước khi ghi / trước khi đổi tên / sau khi đổi tên của từng file) để test có thể
     * crash hoặc báo lỗi đúng tại điểm thứ k, hoặc phá hỏng file ngay sau khi ghi để thử bước xác minh.
     */
    static class FaultStorage extends FileStorage {
        int calls;
        int crashAt = -1;
        int failAt = -1;
        boolean failPersistently;
        String tamperFile;                 // tên file sẽ bị phá ngay sau khi ghi
        java.util.function.Consumer<Path> tamper;
        final List<String> log = new ArrayList<>();
        FaultStorage(Path base) { super(base); }

        private void point(String kind, Path target) throws IOException {
            calls++;
            log.add(calls + ":" + kind + ":" + target.getFileName());
            if (calls == crashAt) throw new SimulatedCrash("crash at " + log.get(log.size() - 1));
            if (failAt > 0 && (calls == failAt || (failPersistently && calls > failAt))) {
                throw new IOException("simulated disk failure at " + log.get(log.size() - 1));
            }
        }
        @Override protected void beforeWrite(Path t) throws IOException { point("before", t); }
        @Override protected void beforeRename(Path t, Path tmp) throws IOException { point("rename", t); }
        @Override protected void afterWrite(Path t) throws IOException {
            point("after", t);
            if (tamper != null && t.getFileName().toString().equals(tamperFile)) tamper.accept(t);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
