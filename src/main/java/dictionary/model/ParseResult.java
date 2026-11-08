package dictionary.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Các entry đọc được từ một file dữ liệu cùng mọi vấn đề phát hiện trong lúc đọc. */
public record ParseResult(List<SlangEntry> entries, List<ParseIssue> issues, int totalLines) {

    public int count(ParseIssue.Action a) {
        int n = 0;
        for (ParseIssue i : issues) if (i.action() == a) n++;
        return n;
    }

    public List<ParseIssue> quarantined() {
        List<ParseIssue> out = new ArrayList<>();
        for (ParseIssue i : issues) if (i.action() == ParseIssue.Action.QUARANTINED) out.add(i);
        return out;
    }

    public String toReportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA QUALITY REPORT\n===================\n");
        sb.append("Lines read      : ").append(totalLines).append('\n');
        sb.append("Entries loaded  : ").append(entries.size()).append('\n');
        sb.append("Issues found    : ").append(issues.size()).append("\n\n");
        Map<ParseIssue.Action, Integer> byAction = new EnumMap<>(ParseIssue.Action.class);
        for (ParseIssue i : issues) byAction.merge(i.action(), 1, Integer::sum);
        sb.append("By action: ").append(byAction).append("\n\n");
        sb.append("Policy: no line is skipped silently.\n");
        sb.append("  AUTO_REPAIRED = fixed by a verified rule (original text shown).\n");
        sb.append("  QUARANTINED   = NOT loaded; the raw line is kept here so nothing is lost.\n");
        sb.append("  KEPT          = loaded unchanged (for your information).\n\n");
        for (ParseIssue i : issues) sb.append(i).append('\n');
        return sb.toString();
    }
}
