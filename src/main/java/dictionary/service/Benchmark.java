package dictionary.service;

import dictionary.model.SlangEntry;
import dictionary.repository.DictionaryRepository;
import dictionary.storage.FileStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/** Đo hiệu năng trên dữ liệu giả lập trong thư mục tạm (không bao giờ đụng dữ liệu thật của người dùng). */
public final class Benchmark {
    private Benchmark() { }

    private static final String[] WORDS = {"cool", "happy", "friend", "money", "angry", "sleep", "party", "music", "food", "great",
            "tired", "online", "text", "love", "crazy", "work", "school", "funny", "fast", "slow"};

    /** Số đo (mili-giây, trừ khi ghi khác). Add/Edit/Delete ĐÃ BAO GỒM thời gian lưu xuống đĩa. */
    public record Result(int size, double indexMs, double coldStartMs, double warmStartMs, boolean warmReusedIndex,
                         double slangLookupMicros, double prefixMicros, double definitionAvgMs, double definitionWorstMs,
                         double addMs, double editMs, double deleteMs, double resetMs, List<String> lines) {
        public double worstMutationMs() { return Math.max(addMs, Math.max(editMs, deleteMs)); }
    }

    private static List<SlangEntry> generate(int size, Random rnd) {
        List<SlangEntry> data = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            StringBuilder def = new StringBuilder();
            for (int w = 0; w < 6; w++) def.append(WORDS[rnd.nextInt(WORDS.length)]).append(' ').append(rnd.nextInt(1000)).append(' ');
            data.add(new SlangEntry(i, "slang" + Integer.toString(i, 36) + rnd.nextInt(100), def.toString().trim()));
        }
        return data;
    }

    /** Chỉ đo trong bộ nhớ (nhanh). */
    public static List<String> run(int size) {
        Random rnd = new Random(42);
        List<SlangEntry> data = generate(size, rnd);
        DictionaryRepository repo = new DictionaryRepository();
        long t0 = System.nanoTime();
        repo.loadEntries(data);
        List<String> out = new ArrayList<>();
        out.add(String.format("Indexed %,d entries in %.0f ms", size, (System.nanoTime() - t0) / 1e6));
        measureSearch(repo, data, rnd, out);
        return out;
    }

    private static double[] measureSearch(DictionaryRepository repo, List<SlangEntry> data, Random rnd, List<String> out) {
        int size = data.size();
        repo.warmUp();
        int n = 2000, hits = 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) hits += repo.findBySlang(data.get(rnd.nextInt(size)).getSlang()).size();
        double perLookup = (System.nanoTime() - t1) / 1e3 / n;
        out.add(String.format("Slang lookup (HashMap): %.2f µs average over %,d lookups (%,d hits)", perLookup, n, hits));

        long t3 = System.nanoTime();
        int pref = 0;
        for (int i = 0; i < 1000; i++) pref += repo.findByPrefix("slang" + Integer.toString(rnd.nextInt(1000), 36), 50).size();
        double perPrefix = (System.nanoTime() - t3) / 1e3 / 1000;
        out.add(String.format("Prefix suggestions (binary search): %.1f µs average (%,d results)", perPrefix, pref));

        double total = 0, worst = 0;
        int found = 0, q = 20;
        for (int i = 0; i < q; i++) {
            long t2 = System.nanoTime();
            found += repo.findByDefinition(WORDS[i % WORDS.length] + " " + rnd.nextInt(10)).size();
            double ms = (System.nanoTime() - t2) / 1e6;
            total += ms;
            worst = Math.max(worst, ms);
        }
        out.add(String.format("Definition search (scan): %.1f ms average, %.1f ms worst over %d queries (%,d results)", total / q, worst, q, found));
        return new double[]{perLookup, perPrefix, total / q, worst};
    }

    /**
     * Đo đầy đủ: khởi động lần đầu (parse + index + lưu), khởi động lần sau (nạp index đã lưu),
     * tìm kiếm, và Add/Edit/Delete CÓ lưu xuống đĩa. {@code workDir} phải là thư mục tạm riêng.
     */
    public static Result runFull(int size, Path workDir) throws IOException {
        List<String> out = new ArrayList<>();
        Random rnd = new Random(42);
        List<SlangEntry> data = generate(size, rnd);

        Path rawFile = workDir.resolve("data").resolve("original").resolve("slang.raw.txt");
        Files.createDirectories(rawFile.getParent());
        try (var w = Files.newBufferedWriter(rawFile, StandardCharsets.UTF_8)) {
            for (SlangEntry e : data) { w.write(e.getSlang() + "`" + e.getDefinition()); w.newLine(); }
        }
        FileStorage st = new FileStorage(workDir);

        long t0 = System.nanoTime();
        DictionaryService cold = new DictionaryService(st);
        cold.load();
        double coldMs = (System.nanoTime() - t0) / 1e6;
        out.add(String.format("Cold start (normalize + parse + index + save) %,d entries: %.0f ms", size, coldMs));

        DictionaryRepository idx = new DictionaryRepository();
        t0 = System.nanoTime();
        idx.loadEntries(data);
        double indexMs = (System.nanoTime() - t0) / 1e6;
        out.add(String.format("Index build alone: %.0f ms", indexMs));

        t0 = System.nanoTime();
        DictionaryService warm = new DictionaryService(st);
        DictionaryService.LoadReport rep = warm.load();
        double warmMs = (System.nanoTime() - t0) / 1e6;
        out.add(String.format("Warm start (saved index reused=%b): %.0f ms", rep.indexReused(), warmMs));

        double[] s = measureSearch(warm.repository(), data, rnd, out);

        t0 = System.nanoTime();
        SlangEntry added = warm.add("benchword", "benchmark meaning");
        double addMs = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime();
        warm.update(added.getId(), "benchword2", "updated meaning");
        double editMs = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime();
        warm.delete(added.getId());
        double deleteMs = (System.nanoTime() - t0) / 1e6;
        out.add(String.format("Add (incl. save): %.0f ms | Edit (incl. save): %.0f ms | Delete (incl. save): %.0f ms", addMs, editMs, deleteMs));

        warm.add("benchword3", "to be dropped by reset");
        t0 = System.nanoTime();
        warm.resetToOriginal();
        double resetMs = (System.nanoTime() - t0) / 1e6;
        out.add(String.format("Reset to original (re-parse + re-index + save %,d entries): %.0f ms", warm.repository().size(), resetMs));
        return new Result(size, indexMs, coldMs, warmMs, rep.indexReused(), s[0], s[1], s[2], s[3], addMs, editMs, deleteMs, resetMs, out);
    }

    /** Chạy runFull trong thư mục tạm rồi dọn sạch. */
    public static Result runFullTemp(int size) throws IOException {
        Path dir = Files.createTempDirectory("slang-bench");
        try {
            return runFull(size, dir);
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            } catch (IOException | UncheckedIOException ignored) { }
        }
    }

    /** Đo nhiều lần (mỗi lần trong thư mục tạm mới) và tóm tắt min / trung vị / max cho từng chỉ số. */
    public static List<String> runRepeated(int size, int runs) throws IOException {
        List<Result> all = new ArrayList<>();
        for (int i = 0; i < runs; i++) all.add(runFullTemp(size));
        List<String> out = new ArrayList<>();
        out.add(String.format("Benchmark: %,d entries, %d runs (each in a fresh temporary folder). min / median / max", size, runs));
        row(out, "Cold start (first launch)", all, Result::coldStartMs, "ms");
        row(out, "Warm start (saved index reused)", all, Result::warmStartMs, "ms");
        row(out, "Slang lookup", all, Result::slangLookupMicros, "us");
        row(out, "Prefix suggestions", all, Result::prefixMicros, "us");
        row(out, "Definition search, average", all, Result::definitionAvgMs, "ms");
        row(out, "Definition search, worst", all, Result::definitionWorstMs, "ms");
        row(out, "Add (incl. save)", all, Result::addMs, "ms");
        row(out, "Edit (incl. save)", all, Result::editMs, "ms");
        row(out, "Delete (incl. save)", all, Result::deleteMs, "ms");
        row(out, "Reset to original (incl. save)", all, Result::resetMs, "ms");
        return out;
    }

    private static void row(List<String> out, String label, List<Result> all, java.util.function.ToDoubleFunction<Result> f, String unit) {
        double[] v = all.stream().mapToDouble(f).sorted().toArray();
        out.add(String.format("  %-34s %9.1f / %9.1f / %9.1f %s", label, v[0], v[v.length / 2], v[v.length - 1], unit));
    }
}
