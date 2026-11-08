package dictionary.ui;

import dictionary.model.SlangEntry;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.util.List;

/** Hiển thị chi tiết một entry: từ lớn + từng nghĩa trong thẻ pastel có viền mực. */
public final class EntryView {
    private EntryView() { }

    public static JEditorPane createPane() {
        JEditorPane p = new JEditorPane();
        p.setEditorKit(new HTMLEditorKit());
        p.setEditable(false);
        p.setOpaque(true);
        p.setBackground(Theme.card());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return p;
    }

    public static String page(String body) {
        return "<html><body style='font-family:" + Theme.bodyFamily() + "; color:" + Theme.hex(Theme.text())
                + "; margin:6px'>" + body + "</body></html>";
    }

    public static String message(String title, String text) {
        return page("<div style='margin-top:60px; text-align:center'><div style='font-family:" + Theme.displayFamily()
                + "; font-size:22px; font-weight:bold; color:" + Theme.hex(Theme.accentDark()) + "'>" + Ui.esc(title)
                + "</div><p style='color:" + Theme.hex(Theme.muted()) + "; font-size:14px'>" + Ui.esc(text) + "</p></div>");
    }

    /** @param highlight từ khoá cần tô sáng trong nghĩa (có thể rỗng) */
    public static String entry(SlangEntry e, String highlight) {
        List<String> meanings = e.meanings();
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:").append(Theme.displayFamily()).append("; font-size:40px; font-weight:bold; color:")
                .append(Theme.hex(Theme.text())).append("'>").append(Ui.esc(e.getSlang())).append("</div>");
        sb.append("<div style='color:").append(Theme.hex(Theme.muted())).append("; font-size:13px'>")
                .append(meanings.size() == 1 ? "1 meaning" : meanings.size() + " meanings").append("</div><br>");
        int i = 1;
        for (String m : meanings) {
            String tone = Theme.hex(i % 2 == 1 ? Theme.cream() : Theme.mint());
            // bảng lồng nhau: ô ngoài màu mực làm viền dày, ô trong là thẻ màu pastel
            sb.append("<table width='100%' cellpadding='2' cellspacing='0'><tr><td bgcolor='").append(Theme.hex(Theme.ink())).append("'>")
                    .append("<table width='100%' cellpadding='12' cellspacing='0'><tr><td bgcolor='").append(tone).append("'>")
                    .append("<span style='font-family:").append(Theme.displayFamily()).append("; color:").append(Theme.hex(Theme.accentDark()))
                    .append("; font-weight:bold'>").append(meanings.size() > 1 ? "Meaning " + i : "Meaning").append("</span><br>")
                    .append("<span style='font-size:16px'>").append(highlight(m, highlight)).append("</span>")
                    .append("</td></tr></table></td></tr></table><div style='font-size:6px'>&nbsp;</div>");
            i++;
        }
        return page(sb.toString());
    }

    static String highlight(String text, String keyword) {
        if (keyword == null || keyword.isBlank()) return Ui.esc(text);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String k = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int from = 0, idx;
        while ((idx = lower.indexOf(k, from)) >= 0) {
            sb.append(Ui.esc(text.substring(from, idx)));
            sb.append("<span style='background-color:#FFE08A; color:#2C2A5A'><b>")
                    .append(Ui.esc(text.substring(idx, idx + k.length()))).append("</b></span>");
            from = idx + k.length();
        }
        sb.append(Ui.esc(text.substring(from)));
        return sb.toString();
    }
}
