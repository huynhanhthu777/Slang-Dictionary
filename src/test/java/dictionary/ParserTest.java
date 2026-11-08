package dictionary;

import dictionary.model.ParseIssue.Action;
import dictionary.model.ParseIssue.Kind;
import dictionary.model.ParseResult;
import dictionary.storage.FileStorage;
import dictionary.storage.SlangParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    @Test void parsesSlangDefinitionAndKeepsPipeInDefinition() throws Exception {
        var r = parse("A`one| two\n");
        assertEquals(1, r.entries().size());
        assertEquals("one| two", r.entries().get(0).getDefinition());
    }

    @Test void splitsOnFirstBacktickOnly() throws Exception {
        assertEquals("x`y", parse("A`x`y\n").entries().get(0).getDefinition());
    }

    @Test void blankLinesAreReportedNotLoaded() throws Exception {
        var r = parse("\nA`b\n\n");
        assertEquals(1, r.entries().size());
        assertEquals(2, r.issues().stream().filter(i -> i.kind() == Kind.BLANK_LINE).count());
    }

    @Test void headerRowIsReportedNotLoaded() throws Exception {
        var r = parse("Slag`Meaning\nA`b\n");
        assertEquals(1, r.entries().size());
        assertEquals(1, r.issues().stream().filter(i -> i.kind() == Kind.HEADER).count());
    }

    @Test void whitespaceIsTrimmedAndReported() throws Exception {
        var r = parse(" HK `Hong Kong \n");
        assertEquals("HK", r.entries().get(0).getSlang());
        assertEquals("Hong Kong", r.entries().get(0).getDefinition());
        assertEquals(1, r.count(Action.AUTO_REPAIRED));
    }

    @Test void verifiedContinuationIsMergedIntoPreviousDefinitionAndReported() throws Exception {
        var r = parse("AMA`Ask me anything\nAgainst Medical Advice\n");
        assertEquals(1, r.entries().size());
        assertEquals("Ask me anything| Against Medical Advice", r.entries().get(0).getDefinition());
        assertEquals(1, r.count(Action.AUTO_REPAIRED));
        assertEquals(2, r.issues().get(0).lineNumber());
    }

    @Test void unknownMissingSeparatorLineIsQuarantinedNeverMisattributed() throws Exception {
        var r = parse("LOL`Laugh out loud\nBRB Be Right Back\n");
        assertEquals(1, r.entries().size(), "must not invent a meaning for LOL");
        assertEquals("Laugh out loud", r.entries().get(0).getDefinition());
        assertEquals(1, r.count(Action.QUARANTINED));
        assertTrue(r.issues().get(0).rawLine().contains("BRB Be Right Back"), "raw line kept in the report");
    }

    @Test void verifiedTextUnderTheWrongSlangIsNotRepaired() throws Exception {
        var r = parse("LOL`Laugh out loud\nAgainst Medical Advice\n");
        assertEquals(1, r.count(Action.QUARANTINED));
        assertEquals("Laugh out loud", r.entries().get(0).getDefinition());
    }

    @Test void emptySlangAndEmptyDefinitionAreQuarantined() throws Exception {
        var r = parse("A`b\n`nothing\nC`\n");
        assertEquals(1, r.entries().size());
        assertEquals(2, r.count(Action.QUARANTINED));
    }

    @Test void firstLineWithoutSeparatorIsQuarantined() throws Exception {
        assertEquals(1, parse("justtext\nA`b\n").count(Action.QUARANTINED));
    }

    @Test void knownEncodingFixOnlyWhenDefinitionConfirms() throws Exception {
        assertEquals("\u00AE", parse("\u00C6`Registered Trademark Symbol\n").entries().get(0).getSlang());
        assertEquals("\u00C6", parse("\u00C6`Something else\n").entries().get(0).getSlang());
    }

    @Test void exactDuplicatesAreKeptAndReported() throws Exception {
        var r = parse("GOAT`Greatest\nGOAT`Greatest\n");
        assertEquals(2, r.entries().size());
        assertEquals(1, r.issues().stream().filter(i -> i.kind() == Kind.EXACT_DUPLICATE).count());
    }

    @Test void workingModeNeverAltersUserData() throws Exception {
        var r = parseWorking("Slag`Meaning\n\u00C6`Registered Trademark Symbol\nAMA`x\nAgainst Medical Advice\n");
        assertEquals("Slag", r.entries().get(0).getSlang(), "header-looking row is user data in working mode");
        assertEquals(3, r.entries().size());
        assertEquals("\u00C6", r.entries().get(1).getSlang(), "no encoding fix in working mode");
        assertEquals(1, r.count(Action.QUARANTINED), "continuation is not merged in working mode");
    }

    // ---------- dữ liệu thật của đề ----------

    @Test void realRawFile_everyLineIsAccountedFor(@TempDir Path tmp) throws Exception {
        ParseResult r = SlangParser.parse(Files.newBufferedReader(REAL_DATA.resolve("slang.raw.txt"), StandardCharsets.UTF_8));
        long merged = r.issues().stream().filter(i -> i.kind() == Kind.MISSING_DELIMITER && i.action() == Action.AUTO_REPAIRED).count();
        long headers = r.issues().stream().filter(i -> i.kind() == Kind.HEADER).count();
        long blanks = r.issues().stream().filter(i -> i.kind() == Kind.BLANK_LINE).count();
        assertEquals(7662, r.totalLines());
        assertEquals(r.totalLines(), r.entries().size() + merged + headers + blanks + r.count(Action.QUARANTINED),
                "entries + merged + header + blank + quarantined must equal total lines");
        assertEquals(7656, r.entries().size());
        assertEquals(5, merged);
        assertEquals(1, headers);
        assertEquals(0, r.count(Action.QUARANTINED));
    }

    @Test void realRawFile_fiveAbnormalLinesAreRepairedToTheRightSlang() throws Exception {
        ParseResult r = SlangParser.parse(Files.newBufferedReader(REAL_DATA.resolve("slang.raw.txt"), StandardCharsets.UTF_8));
        var repo = new dictionary.repository.DictionaryRepository();
        repo.loadEntries(r.entries());
        String[][] expected = {
                {"AMA", "Ask Me Anything| Against Medical Advice"},
                {"BOMB", "Something really bad| Something really good"},
                {"C4C", "Comment for Comment| Cam for Cam"},
                {"ER", "Emergency Room| Indicating hesitation"},
                {"HOODIE", "Hooded sweatshirt| Person wearing a hooded sweatshirt"}};
        for (String[] e : expected) {
            var found = repo.findBySlang(e[0]);
            assertEquals(1, found.size(), e[0]);
            assertEquals(e[1], found.get(0).getDefinition(), e[0]);
        }
        assertEquals("\u00AE", repo.findBySlang("\u00AE").get(0).getSlang());
        assertEquals("\u2122", repo.findBySlang("\u2122").get(0).getSlang());
        assertTrue(repo.findBySlang("\u00C6").isEmpty() && repo.findBySlang("\u00F4").isEmpty());
    }

    @Test void normalizedFileIsIdempotentAndHasNoQuarantine(@TempDir Path tmp) throws Exception {
        Path app = tempApp(tmp);
        FileStorage st = new FileStorage(app);
        st.ensureOriginal();
        byte[] raw = Files.readAllBytes(st.rawFile());
        byte[] normalized = Files.readAllBytes(st.normalizedFile());
        ParseResult again = st.readNormalized();
        assertEquals(0, again.count(Action.QUARANTINED));
        assertEquals(0, again.count(Action.AUTO_REPAIRED), "second pass must find nothing left to repair");
        assertEquals(7656, again.entries().size());
        // chuẩn hóa lần nữa từ raw cho đúng cùng kết quả (xác định, lặp lại được)
        Files.delete(st.normalizedFile());
        st.ensureOriginal();
        assertArrayEquals(normalized, Files.readAllBytes(st.normalizedFile()));
        assertArrayEquals(raw, Files.readAllBytes(st.rawFile()), "raw file is never modified");
    }

    @Test void normalizationRunsOnlyOnce(@TempDir Path tmp) throws Exception {
        FileStorage st = new FileStorage(tempApp(tmp));
        st.ensureOriginal();
        Files.writeString(st.normalizedFile(), "MINE`my edit\n");
        st.ensureOriginal();
        assertEquals("MINE`my edit\n", Files.readString(st.normalizedFile()), "existing normalized file must not be regenerated");
    }

    @Test void reportListsEveryRepairedLine(@TempDir Path tmp) throws Exception {
        FileStorage st = new FileStorage(tempApp(tmp));
        st.ensureOriginal();
        List<String> report = st.readQualityReport();
        for (int line : new int[]{413, 908, 1112, 2004, 2882}) {
            assertTrue(report.stream().anyMatch(l -> l.startsWith("line " + line + " [MISSING_DELIMITER -> AUTO_REPAIRED]")), "line " + line);
        }
    }
}
