package dictionary.model;

/** Một vấn đề phát hiện khi đọc file dữ liệu. Không dòng nào bị bỏ qua âm thầm: mọi vấn đề đều được báo cáo. */
public record ParseIssue(int lineNumber, Kind kind, Action action, String rawLine, String message) {
    public enum Kind { HEADER, BLANK_LINE, MISSING_DELIMITER, EMPTY_SLANG, EMPTY_DEFINITION,
        WHITESPACE, ENCODING, CONTROL_CHARS, EXACT_DUPLICATE }

    /** IGNORED: không phải dữ liệu. AUTO_REPAIRED: đã sửa theo quy tắc được xác minh.
     *  KEPT: nạp nguyên vẹn (chỉ để thông báo). QUARANTINED: không nạp, giữ nguyên văn trong báo cáo. */
    public enum Action { IGNORED, AUTO_REPAIRED, KEPT, QUARANTINED }

    @Override public String toString() {
        return "line " + lineNumber + " [" + kind + " -> " + action + "] " + message + "  | raw: " + rawLine;
    }
}
