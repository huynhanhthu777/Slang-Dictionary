package dictionary.storage;

import dictionary.model.SlangEntry;

import java.util.List;

/** Nội dung file slang.working.txt đã đọc: version ID ghi trong dòng đầu (-1 nếu không có) và các entry. */
public record WorkingText(long dataVersion, int declaredEntries, List<SlangEntry> entries) { }
