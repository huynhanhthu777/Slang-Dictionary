package dictionary.repository;

import dictionary.model.SlangEntry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Trạng thái đầy đủ của repository để lưu xuống đĩa (kể cả index idsBySlang), nhờ đó các lần chạy sau
 * KHÔNG phải index lại. {@code dataVersion} là version ID dùng chung cho snapshot, backup và working text.
 */
public final class Snapshot implements Serializable {
    private static final long serialVersionUID = 2L;
    public static final int FORMAT_VERSION = 2;

    public final int formatVersion = FORMAT_VERSION;
    public final long dataVersion;
    public final long nextId;
    public final long createdAtMillis;
    public final ArrayList<SlangEntry> entries;
    public final HashMap<String, ArrayList<Long>> idsBySlang;

    public Snapshot(long dataVersion, long nextId, ArrayList<SlangEntry> entries, HashMap<String, ArrayList<Long>> idsBySlang) {
        this.dataVersion = dataVersion;
        this.nextId = nextId;
        this.createdAtMillis = System.currentTimeMillis();
        this.entries = entries;
        this.idsBySlang = idsBySlang;
    }
}
