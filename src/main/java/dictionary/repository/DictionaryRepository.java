package dictionary.repository;

import dictionary.model.SlangEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * Kho dữ liệu trong bộ nhớ + các index để tìm kiếm nhanh.
 *
 * <pre>
 *  entriesById : HashMap&lt;Long, SlangEntry&gt;          O(1) truy cập theo ID
 *  idsBySlang  : HashMap&lt;String, ArrayList&lt;Long&gt;&gt; O(1) trung bình tìm theo slang (hỗ trợ nhiều entry trùng)
 *  orderedIds  : ArrayList&lt;Long&gt; + positions         random O(1), xoá O(1) bằng swap-with-last
 *  sortedKeys  : ArrayList&lt;String&gt; (lazy)            gợi ý theo prefix bằng binary search O(log n)
 * </pre>
 * Mô hình đồng bộ DUY NHẤT của dự án: mọi phương thức public đều synchronized (monitor của repository).
 * Việc GHI chỉ đi qua DictionaryService (một luồng ghi, xem Tasks.write); đọc/tìm kiếm chạy ở luồng nền.
 */
public class DictionaryRepository {
    // Dung HashMap de tim entry theo ID nhanh (sua, xoa)
    private final Map<Long, SlangEntry> entriesById = new HashMap<>();
    // Dung HashMap de tim slang nhanh hon. Vi mot slang co the co nhieu nghia nen luu danh sach ID
    private final Map<String, ArrayList<Long>> idsBySlang = new HashMap<>();
    // Danh sach ID theo thu tu them vao: dung de random va hien thi
    private final ArrayList<Long> orderedIds = new ArrayList<>();
    // Vi tri cua moi ID trong orderedIds: de xoa nhanh
    private final Map<Long, Integer> positions = new HashMap<>();
    // Danh sach slang da sap xep de goi y theo prefix. null = chua dung hoac da loi thoi, se dung lai khi can
    private ArrayList<String> sortedKeys;
    private long nextId = 1;
    private int version = 0;

    // ---------- nạp / xuất ----------

    /** Nạp từ danh sách entry đã parse: dựng toàn bộ index. */
    public synchronized void loadEntries(List<SlangEntry> entries) {
        clear();
        long max = 0;
        for (SlangEntry e : entries) {
            insert(e);
            max = Math.max(max, e.getId());
        }
        nextId = max + 1;
        version++;
    }

    /**
     * Kiểm tra snapshot có nhất quán không (entry, index, ID) mà KHÔNG thay đổi repository.
     * Nạp snapshot hỏng sẽ làm sai mọi chức năng nên phải kiểm tra trước khi dùng.
     */
    public static void validate(Snapshot s) throws CorruptDataException {
        if (s == null) throw new CorruptDataException("missing snapshot");
        if (s.formatVersion != Snapshot.FORMAT_VERSION) throw new CorruptDataException("unknown format version " + s.formatVersion);
        if (s.entries == null || s.idsBySlang == null) throw new CorruptDataException("missing structures");
        Map<Long, SlangEntry> byId = new HashMap<>(Math.max(16, s.entries.size() * 2));
        long max = 0;
        for (SlangEntry e : s.entries) {
            if (e == null || e.getSlang() == null || e.getDefinition() == null) throw new CorruptDataException("null entry");
            if (byId.put(e.getId(), e) != null) throw new CorruptDataException("duplicate id " + e.getId());
            max = Math.max(max, e.getId());
        }
        if (s.nextId <= max) throw new CorruptDataException("nextId is not greater than the largest id");
        int indexed = 0;
        for (Map.Entry<String, ArrayList<Long>> ix : s.idsBySlang.entrySet()) {
            if (ix.getValue() == null || ix.getValue().isEmpty()) throw new CorruptDataException("empty index list");
            for (Long id : ix.getValue()) {
                SlangEntry e = byId.get(id);
                if (e == null || !e.key().equals(ix.getKey())) throw new CorruptDataException("index points to a wrong entry");
            }
            indexed += ix.getValue().size();
        }
        if (indexed != byId.size()) throw new CorruptDataException("index size mismatch");
    }

    /** Nạp từ snapshot đã lưu: dùng lại index có sẵn, không index lại. Snapshot được kiểm tra trước. */
    public synchronized void loadSnapshot(Snapshot s) throws CorruptDataException {
        validate(s);
        clear();
        for (SlangEntry e : s.entries) {
            entriesById.put(e.getId(), e);
            positions.put(e.getId(), orderedIds.size());
            orderedIds.add(e.getId());
        }
        idsBySlang.putAll(s.idsBySlang);
        nextId = s.nextId;
        version++;
    }

    // ------------------------------------------------------------------ từ khóa trong definition (gợi ý khi tìm theo nghĩa)

    private int vocabVersion = -1;
    private String[] vocabWords = new String[0];     // đã sắp xếp
    private int[] vocabCounts = new int[0];          // số entry chứa từ đó

    /** Dựng (hoặc dựng lại khi dữ liệu đổi) danh sách từ xuất hiện trong definition. Chỉ chạy lại khi version đổi. */
    private void ensureVocabulary() {
        if (vocabVersion == version) return;
        Map<String, int[]> counts = new HashMap<>();
        for (SlangEntry e : entriesById.values()) {
            String d = e.lowerDefinition();
            java.util.HashSet<String> inEntry = new java.util.HashSet<>();
            int start = -1;
            for (int i = 0; i <= d.length(); i++) {
                boolean letter = i < d.length() && Character.isLetterOrDigit(d.charAt(i));
                if (letter && start < 0) start = i;
                if (!letter && start >= 0) {
                    if (i - start >= 3) inEntry.add(d.substring(start, i));
                    start = -1;
                }
            }
            for (String w : inEntry) counts.computeIfAbsent(w, k -> new int[1])[0]++;
        }
        String[] words = counts.keySet().toArray(new String[0]);
        java.util.Arrays.sort(words);
        int[] c = new int[words.length];
        for (int i = 0; i < words.length; i++) c[i] = counts.get(words[i])[0];
        vocabWords = words;
        vocabCounts = c;
        vocabVersion = version;
    }

    /** Gợi ý hoàn thành từ khóa: các từ trong definition bắt đầu bằng {@code prefix}, phổ biến nhất trước. */
    public synchronized List<String> suggestDefinitionWords(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        if (prefix == null || limit <= 0) return out;
        String p = prefix.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty()) return out;
        ensureVocabulary();
        int lo = 0, hi = vocabWords.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (vocabWords[mid].compareTo(p) < 0) lo = mid + 1; else hi = mid; }
        java.util.PriorityQueue<int[]> best = new java.util.PriorityQueue<>((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(b[0], a[0]));
        for (int i = lo; i < vocabWords.length && vocabWords[i].startsWith(p); i++) {
            if (vocabWords[i].length() == p.length()) continue;          // chính từ đang gõ thì không cần gợi ý lại
            best.add(new int[]{i, vocabCounts[i]});
            if (best.size() > limit) best.poll();
        }
        List<int[]> picked = new ArrayList<>(best);
        picked.sort((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
        for (int[] x : picked) out.add(vocabWords[x[0]]);
        return out;
    }

    /** Vài từ khóa phổ biến, có thật trong definition, để gợi ý "Try:" ở chế độ tìm theo nghĩa. */
    public synchronized List<String> popularDefinitionWords(int count, Random rnd) {
        List<String> out = new ArrayList<>();
        ensureVocabulary();
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < vocabWords.length; i++) {
            String w = vocabWords[i];
            if (vocabCounts[i] >= 5 && w.length() >= 4 && w.length() <= 10 && w.chars().allMatch(Character::isLetter)) pool.add(i);
        }
        if (pool.isEmpty()) {
            for (int i = 0; i < vocabWords.length && pool.size() < 50; i++) pool.add(i);
        }
        Collections.shuffle(pool, rnd);
        for (int i = 0; i < pool.size() && out.size() < count; i++) out.add(vocabWords[pool.get(i)]);
        return out;
    }

    /** Tính sẵn cache chữ thường để lần tìm definition đầu tiên không chậm hơn các lần sau. */
    public synchronized void warmUp() {
        for (SlangEntry e : entriesById.values()) { e.key(); e.lowerDefinition(); }
    }

    public synchronized Snapshot toSnapshot(long dataVersion) {
        ArrayList<SlangEntry> list = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) list.add(entriesById.get(id));
        HashMap<String, ArrayList<Long>> copy = new HashMap<>(idsBySlang.size() * 2);
        for (Map.Entry<String, ArrayList<Long>> e : idsBySlang.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return new Snapshot(dataVersion, nextId, list, copy);
    }

    private void clear() {
        entriesById.clear();
        idsBySlang.clear();
        orderedIds.clear();
        positions.clear();
        sortedKeys = null;
    }

    private void insert(SlangEntry e) {
        entriesById.put(e.getId(), e);
        positions.put(e.getId(), orderedIds.size());
        orderedIds.add(e.getId());
        ArrayList<Long> ids = idsBySlang.get(e.key());
        if (ids == null) {
            ids = new ArrayList<>(1);
            idsBySlang.put(e.key(), ids);
            sortedKeys = null;
        }
        ids.add(e.getId());
    }

    private void remove(SlangEntry e) {
        entriesById.remove(e.getId());
        // swap-with-last để xoá khỏi orderedIds trong O(1)
        int pos = positions.remove(e.getId());
        int last = orderedIds.size() - 1;
        Long lastId = orderedIds.get(last);
        if (pos != last) {
            orderedIds.set(pos, lastId);
            positions.put(lastId, pos);
        }
        orderedIds.remove(last);
        ArrayList<Long> ids = idsBySlang.get(e.key());
        if (ids != null) {
            ids.remove(Long.valueOf(e.getId()));
            if (ids.isEmpty()) {
                idsBySlang.remove(e.key());
                sortedKeys = null;
            }
        }
    }

    // ---------- truy vấn ----------

    public synchronized int size() { return orderedIds.size(); }
    public synchronized int version() { return version; }
    public synchronized int distinctSlangCount() { return idsBySlang.size(); }
    public synchronized SlangEntry get(long id) { return entriesById.get(id); }
    public synchronized boolean isEmpty() { return orderedIds.isEmpty(); }

    /** Tìm chính xác theo slang (không phân biệt hoa/thường). O(1) trung bình. */
    public synchronized List<SlangEntry> findBySlang(String slang) {
        ArrayList<Long> ids = idsBySlang.get(SlangEntry.normalize(slang));
        if (ids == null) return new ArrayList<>();
        List<SlangEntry> out = new ArrayList<>(ids.size());
        for (Long id : ids) out.add(entriesById.get(id));
        return out;
    }

    public synchronized boolean slangExists(String slang) {
        return idsBySlang.containsKey(SlangEntry.normalize(slang));
    }

    /** Gợi ý theo prefix: binary search trên danh sách key đã sắp xếp. */
    public synchronized List<SlangEntry> findByPrefix(String prefix, int limit) {
        String p = SlangEntry.normalize(prefix);
        List<SlangEntry> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        List<String> keys = sortedKeys();
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).compareTo(p) < 0) lo = mid + 1; else hi = mid;
        }
        for (int i = lo; i < keys.size() && out.size() < limit; i++) {
            String k = keys.get(i);
            if (!k.startsWith(p)) break;
            for (Long id : idsBySlang.get(k)) {
                out.add(entriesById.get(id));
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private List<String> sortedKeys() {
        if (sortedKeys == null) {
            ArrayList<String> k = new ArrayList<>(idsBySlang.keySet());
            Collections.sort(k);
            sortedKeys = k;
        }
        return sortedKeys;
    }

    /**
     * Tìm mọi entry có definition chứa keyword (substring, không phân biệt hoa/thường).
     * Quét tuyến tính trên chuỗi lowercase đã cache: ~vài chục ms cho 100.000 entry.
     * Luôn đúng cho substring bất kỳ (ví dụ "app" tìm ra "happy").
     */
    public synchronized List<SlangEntry> findByDefinition(String keyword) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        List<SlangEntry> out = new ArrayList<>();
        if (k.isEmpty()) return out;
        // Quet tung entry va kiem tra definition co chua keyword hay khong
        for (int i = 0, n = orderedIds.size(); i < n; i++) {
            SlangEntry e = entriesById.get(orderedIds.get(i));
            if (e.lowerDefinition().contains(k)) out.add(e);
        }
        return out;
    }

    /** Tất cả entry theo thứ tự chèn. */
    public synchronized List<SlangEntry> all() {
        List<SlangEntry> out = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) out.add(entriesById.get(id));
        return out;
    }

    /** Entry ngẫu nhiên, O(1). */
    public synchronized SlangEntry random(Random rnd) {
        if (orderedIds.isEmpty()) throw new NoSuchElementException("Dictionary is empty");
        return entriesById.get(orderedIds.get(rnd.nextInt(orderedIds.size())));
    }

    // ---------- thay đổi ----------

    public synchronized SlangEntry add(String slang, String definition) {
        SlangEntry e = new SlangEntry(nextId++, slang, definition);
        insert(e);
        version++;
        return e;
    }

    /** Chèn lại entry với ID cũ (dùng khi hoàn tác sau lỗi lưu). */
    public synchronized void restore(SlangEntry e) {
        if (entriesById.containsKey(e.getId())) remove(entriesById.get(e.getId()));
        insert(e);
        nextId = Math.max(nextId, e.getId() + 1);
        version++;
    }

    /** Sửa entry theo ID: gỡ index cũ rồi thêm index mới. */
    public synchronized SlangEntry update(long id, String slang, String definition) {
        SlangEntry old = entriesById.get(id);
        if (old == null) throw new NoSuchElementException("No entry with id " + id);
        // Giu nguyen vi tri trong orderedIds; chi thay entry va cap nhat lai index slang
        entriesById.put(id, new SlangEntry(id, slang, definition));
        SlangEntry fresh = entriesById.get(id);
        ArrayList<Long> oldIds = idsBySlang.get(old.key());
        if (oldIds != null) {
            oldIds.remove(Long.valueOf(id));
            if (oldIds.isEmpty()) { idsBySlang.remove(old.key()); sortedKeys = null; }
        }
        ArrayList<Long> newIds = idsBySlang.get(fresh.key());
        if (newIds == null) {
            newIds = new ArrayList<>(1);
            idsBySlang.put(fresh.key(), newIds);
            sortedKeys = null;
        }
        newIds.add(id);
        version++;
        return fresh;
    }

    public synchronized SlangEntry delete(long id) {
        SlangEntry e = entriesById.get(id);
        if (e == null) throw new NoSuchElementException("No entry with id " + id);
        remove(e);
        version++;
        return e;
    }
}
