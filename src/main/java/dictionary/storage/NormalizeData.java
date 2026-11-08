package dictionary.storage;

import dictionary.model.ParseResult;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Công cụ chạy một lần: slang.raw.txt -> slang.normalized.txt + malformed-report.txt (file raw không bị đụng).
 * Dùng: java -cp SlangDictionary.jar dictionary.storage.NormalizeData [thư mục chứa data/original]
 */
public final class NormalizeData {
    private NormalizeData() { }

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        FileStorage st = new FileStorage(home);
        java.nio.file.Files.deleteIfExists(st.normalizedFile());
        ParseResult res = st.normalizeRaw();
        System.out.println("Lines: " + res.totalLines() + ", entries: " + res.entries().size()
                + ", issues: " + res.issues().size() + ", quarantined: " + res.count(dictionary.model.ParseIssue.Action.QUARANTINED));
    }
}
