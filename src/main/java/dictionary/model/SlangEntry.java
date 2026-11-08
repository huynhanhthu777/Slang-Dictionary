package dictionary.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Một bản ghi slang. Bất biến (immutable) và có ID duy nhất để thao tác chính xác
 * khi nhiều entry trùng cùng một slang word.
 */
public final class SlangEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String slang;
    private final String definition;
    private transient String lowerDefinition; // cache để tìm definition nhanh

    public SlangEntry(long id, String slang, String definition) {
        this.id = id;
        this.slang = slang;
        this.definition = definition;
    }

    public long getId() { return id; }
    public String getSlang() { return slang; }
    public String getDefinition() { return definition; }

    /** Khoá chuẩn hoá dùng cho HashMap: không phân biệt hoa/thường. */
    public String key() { return normalize(slang); }

    public String lowerDefinition() {
        String s = lowerDefinition;
        if (s == null) {
            s = definition.toLowerCase(Locale.ROOT);
            lowerDefinition = s;
        }
        return s;
    }

    /** Tách definition theo dấu '|' thành các nghĩa riêng để hiển thị (dữ liệu gốc không bị đổi). */
    public List<String> meanings() {
        List<String> out = new ArrayList<>();
        for (String part : definition.split("\\|")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) out.add(definition);
        return out;
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    @Override public boolean equals(Object o) {
        return o instanceof SlangEntry other && other.id == id;
    }
    @Override public int hashCode() { return Long.hashCode(id); }
    @Override public String toString() { return slang + " = " + definition; }
}
