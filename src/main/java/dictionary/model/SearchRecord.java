package dictionary.model;

import java.time.LocalDateTime;

/** Một lần tìm kiếm trong history. */
public record SearchRecord(LocalDateTime time, SearchType type, String keyword, int resultCount) { }
