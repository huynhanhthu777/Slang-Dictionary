package dictionary.model;

import java.util.List;

/** Kết quả tìm kiếm kèm thời gian xử lý (nano giây). */
public record SearchResult(List<SlangEntry> entries, long nanos) {
    public double millis() { return nanos / 1_000_000.0; }
}
