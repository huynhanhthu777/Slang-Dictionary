package dictionary.storage;

import dictionary.model.ParseIssue;
import dictionary.model.ParseIssue.Action;
import dictionary.model.ParseIssue.Kind;
import dictionary.model.ParseResult;
import dictionary.model.SlangEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Đọc định dạng chuẩn  slang`definition  (một entry mỗi dòng; dấu ` ĐẦU TIÊN tách hai trường).
 *
 * <p>Hai chế độ:
 * <ul>
 *   <li>{@link Mode#SOURCE}: file gốc của đề. Có sửa lỗi nhưng CHỈ theo quy tắc đã xác minh:
 *       dòng tiêu đề, khoảng trắng, ký tự điều khiển, 2 ký tự hỏng encoding (xác nhận bằng phần nghĩa),
 *       và 5 dòng nghĩa tiếp nối (AMA, BOMB, C4C, ER, HOODIE) – ghép vào nghĩa của dòng trước.</li>
 *   <li>{@link Mode#WORKING}: file làm việc do chính app ghi ra. Không sửa gì (không đụng vào dữ liệu người dùng).</li>
 * </ul>
 * Dòng bất thường không khớp quy tắc nào được QUARANTINED: không nạp, giữ nguyên văn trong báo cáo,
 * tuyệt đối không tự gán nghĩa cho slang khác.
 */
public final class SlangParser {
    public static final char DELIMITER = '`';
    public enum Mode { SOURCE, WORKING }

    /** Slang đứng trước -> nghĩa tiếp nối đã xác minh bằng mắt trong file gốc của đề. */
    static final Map<String, String> VERIFIED_CONTINUATIONS = Map.of(
            "AMA", "Against Medical Advice",
            "BOMB", "Something really good",
            "C4C", "Cam for Cam",
            "ER", "Indicating hesitation",
            "HOODIE", "Person wearing a hooded sweatshirt");

    /** slang hỏng -> {slang đúng, cụm từ bắt buộc có trong nghĩa để chắc chắn}. */
    private static final Map<String, String[]> KNOWN_ENCODING_FIXES = Map.of(
            "\u00C6", new String[]{"\u00AE", "registered trademark"},
            "\u00F4", new String[]{"\u2122", "trademark"});

    private SlangParser() { }

    private static final class Row {
        final int line; final String raw; String slang; String definition;
        Row(int line, String raw, String slang, String definition) {
            this.line = line; this.raw = raw; this.slang = slang; this.definition = definition;
        }
    }

    public static ParseResult parse(Reader source) throws IOException { return parse(source, Mode.SOURCE); }

    public static ParseResult parse(Reader source, Mode mode) throws IOException {
        BufferedReader in = source instanceof BufferedReader b ? b : new BufferedReader(source);
        boolean src = mode == Mode.SOURCE;
        List<ParseIssue> issues = new ArrayList<>();
        List<Row> rows = new ArrayList<>(8192);
        int lineNo = 0;
        boolean previousWasData = false;
        String line;
        while ((line = in.readLine()) != null) {
            lineNo++;
            if (lineNo == 1 && !line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);

            if (line.isBlank()) {
                issues.add(new ParseIssue(lineNo, Kind.BLANK_LINE, Action.IGNORED, line, "blank line, no data"));
                previousWasData = false;
                continue;
            }
            String cleaned = stripControlChars(line);
            if (!cleaned.equals(line)) {
                issues.add(new ParseIssue(lineNo, Kind.CONTROL_CHARS, Action.AUTO_REPAIRED, line, "control characters replaced by spaces"));
            }
            if (line.indexOf('\uFFFD') >= 0) {
                issues.add(new ParseIssue(lineNo, Kind.ENCODING, Action.KEPT, line,
                        "contains undecodable bytes (shown as U+FFFD); kept as read, please review"));
            }
            int pos = cleaned.indexOf(DELIMITER);

            if (pos < 0) {
                Row prev = rows.isEmpty() ? null : rows.get(rows.size() - 1);
                String text = cleaned.trim();
                if (src && previousWasData && prev != null && text.equals(VERIFIED_CONTINUATIONS.get(prev.slang))) {
                    prev.definition = prev.definition + "| " + text;
                    issues.add(new ParseIssue(lineNo, Kind.MISSING_DELIMITER, Action.AUTO_REPAIRED, line,
                            "verified continuation: next meaning of the previous slang '" + prev.slang
                                    + "' (line was broken) -> merged into its definition"));
                } else {
                    issues.add(new ParseIssue(lineNo, Kind.MISSING_DELIMITER, Action.QUARANTINED, line,
                            "no '`' delimiter and not a verified continuation; NOT loaded, needs manual review"));
                    previousWasData = false;
                }
                continue;
            }

            if (src && lineNo == 1 && isHeader(cleaned, pos)) {
                issues.add(new ParseIssue(lineNo, Kind.HEADER, Action.IGNORED, line, "column header, not an entry"));
                previousWasData = false;
                continue;
            }

            String rawSlang = cleaned.substring(0, pos);
            String rawDef = cleaned.substring(pos + 1);
            String slang = rawSlang.trim();
            String def = rawDef.trim();

            if (slang.isEmpty()) {
                issues.add(new ParseIssue(lineNo, Kind.EMPTY_SLANG, Action.QUARANTINED, line, "slang part is empty; NOT loaded, kept in report"));
                previousWasData = false;
                continue;
            }
            if (def.isEmpty()) {
                issues.add(new ParseIssue(lineNo, Kind.EMPTY_DEFINITION, Action.QUARANTINED, line, "definition is empty; NOT loaded, kept in report"));
                previousWasData = false;
                continue;
            }
            if (!slang.equals(rawSlang) || !def.equals(rawDef)) {
                issues.add(new ParseIssue(lineNo, Kind.WHITESPACE, Action.AUTO_REPAIRED, line, "leading/trailing spaces trimmed"));
            }
            if (src) {
                String[] fix = KNOWN_ENCODING_FIXES.get(slang);
                if (fix != null && def.toLowerCase(Locale.ROOT).contains(fix[1])) {
                    issues.add(new ParseIssue(lineNo, Kind.ENCODING, Action.AUTO_REPAIRED, line,
                            "garbled character '" + slang + "' replaced by '" + fix[0] + "' (confirmed by definition: " + def + ")"));
                    slang = fix[0];
                }
            }
            rows.add(new Row(lineNo, line, slang, def));
            previousWasData = true;
        }

        List<SlangEntry> entries = new ArrayList<>(rows.size());
        Set<String> seen = src ? new HashSet<>(rows.size() * 2) : null;
        long id = 1;
        for (Row r : rows) {
            if (seen != null) {
                String sig = SlangEntry.normalize(r.slang) + '\u0000' + r.definition.toLowerCase(Locale.ROOT);
                if (!seen.add(sig)) {
                    issues.add(new ParseIssue(r.line, Kind.EXACT_DUPLICATE, Action.KEPT, r.raw,
                            "same slang AND same definition appears more than once; kept (delete it in Manage if unwanted)"));
                }
            }
            entries.add(new SlangEntry(id++, r.slang, r.definition));
        }
        issues.sort((a, b) -> Integer.compare(a.lineNumber(), b.lineNumber()));
        return new ParseResult(entries, issues, lineNo);
    }

    private static boolean isHeader(String line, int pos) {
        String a = line.substring(0, pos).trim().toLowerCase(Locale.ROOT);
        String b = line.substring(pos + 1).trim().toLowerCase(Locale.ROOT);
        return (a.equals("slag") || a.equals("slang")) && b.equals("meaning");
    }

    private static String stripControlChars(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c)) {
                if (sb == null) { sb = new StringBuilder(s.length()); sb.append(s, 0, i); }
                sb.append(' ');
            } else if (sb != null) sb.append(c);
        }
        return sb == null ? s : sb.toString();
    }
}
