package dictionary;

import dictionary.service.DictionaryService;
import dictionary.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** P0: dữ liệu đầu vào không bao giờ được làm hỏng file dữ liệu hoặc đọc lại khác lúc ghi. */
class ValidationTest {
    @TempDir Path tmp;
    FileStorage st;
    DictionaryService s;

    @BeforeEach void setUp() throws Exception {
        st = new FileStorage(tempAppSmall(tmp));
        s = new DictionaryService(st);
        s.load();
    }

    // ---------- slang: chặn xuống dòng và ký tự điều khiển ----------

    @ParameterizedTest(name = "[{index}] rejected: {0}")
    @ValueSource(strings = {
            "HELLO\nWORLD", "HELLO\rWORLD", "HELLO\r\nWORLD", "HELLO\n", "\nHELLO", "HELLO\tWORLD",
            "HELLO\u0000WORLD", "HELLO\u0007", "HELLO\u001BWORLD", "HELLO\u007FWORLD",
            "HELLO\u0085WORLD", "HELLO\u2028WORLD", "HELLO\u2029WORLD"})
    void slangWithLineBreakOrControlCharIsRejected(String bad) throws Exception {
        assertNotNull(DictionaryService.validateSlang(bad));
        int n = s.repository().size();
        long v = s.dataVersion();
        assertThrows(IllegalArgumentException.class, () -> s.add(bad, "meaning"));
        assertThrows(IllegalArgumentException.class, () -> s.overwrite(bad, "meaning"));
        assertEquals(n, s.repository().size(), "rejected input must not reach memory");
        assertEquals(v, s.dataVersion(), "rejected input must not trigger a save");
    }

    @Test void editingAnExistingSlangIntoAMultilineOneIsRejectedAndKeepsOldValue() throws Exception {
        var e = s.add("EDITME", "old");
        assertThrows(IllegalArgumentException.class, () -> s.update(e.getId(), "EDIT\nME", "new"));
        assertEquals("EDITME", s.repository().get(e.getId()).getSlang());
        assertEquals("old", s.repository().get(e.getId()).getDefinition());
    }

    @Test void emptyAndBlankSlangAreRejected() {
        assertNotNull(DictionaryService.validateSlang(null));
        assertNotNull(DictionaryService.validateSlang(""));
        assertNotNull(DictionaryService.validateSlang("    "));
        assertNotNull(DictionaryService.validateSlang("\u2003\u2003"), "Unicode spaces only");
        assertThrows(IllegalArgumentException.class, () -> s.add("", "x"));
    }

    @Test void backtickLengthAndBrokenUnicodeAreRejected() {
        assertNotNull(DictionaryService.validateSlang("a`b"));
        assertNotNull(DictionaryService.validateSlang("x".repeat(101)));
        assertNull(DictionaryService.validateSlang("x".repeat(100)));
        assertNotNull(DictionaryService.validateSlang("bad\uD83D"), "lone high surrogate");
        assertNotNull(DictionaryService.validateSlang("bad\uDE00x"), "lone low surrogate");
    }

    // ---------- Unicode hợp lệ phải được chấp nhận và đọc lại y hệt ----------

    static final List<String> UNICODE_SLANGS = List.of(
            "café", "naïve", "ÅNGSTRÖM", "日本語", "한국어", "שלום", "مرحبا", "Здравствуй", "ʕ•ᴥ•ʔ", "¯\\_(ツ)_/¯",
            "😀", "👍🏽", "🇻🇳", "e\u0301", "a|b", "#hashtag", "@mention", "x y z", "ĐỒ ĂN", "100%", "<b>", "\"quoted\"",
            "back\\slash", "tab-free", "#SLANGDICT-WORKING|fake=header");

    @Test void unicodeAndSpecialSlangsAreAcceptedAndSurviveRestart() throws Exception {
        for (String slang : UNICODE_SLANGS) {
            assertNull(DictionaryService.validateSlang(slang), slang);
            s.add(slang, "meaning of " + slang + " | second `part` \"q\" \\ end");
        }
        DictionaryService again = new DictionaryService(st);
        assertEquals(DictionaryService.Source.SAVED_STRUCTURES, again.load().source());
        for (String slang : UNICODE_SLANGS) {
            var found = again.searchSlang(slang, false).entries();
            assertEquals(1, found.size(), slang);
            assertEquals(slang.trim(), found.get(0).getSlang(), "exactly the same characters after reload");
            assertEquals("meaning of " + slang + " | second `part` \"q\" \\ end", found.get(0).getDefinition());
        }
    }

    @Test void sameDataComesBackFromTheReadableWorkingTextAfterSnapshotLoss() throws Exception {
        for (String slang : UNICODE_SLANGS) s.add(slang, "def " + slang);
        Files.write(st.snapshotFile(), new byte[]{9, 9, 9});
        Files.deleteIfExists(st.backupFile());
        DictionaryService rebuilt = new DictionaryService(st);
        assertEquals(DictionaryService.Source.WORKING_TEXT, rebuilt.load().source());
        for (String slang : UNICODE_SLANGS) {
            var f = rebuilt.searchSlang(slang, false).entries();
            assertEquals(1, f.size(), slang);
            assertEquals(slang, f.get(0).getSlang());
            assertEquals("def " + slang, f.get(0).getDefinition());
        }
        assertEquals(s.repository().size(), rebuilt.repository().size());
    }

    @Test void everyEntryIsIdenticalBeforeAndAfterSaveAndReload() throws Exception {
        for (String slang : UNICODE_SLANGS) s.add(slang, "x " + slang);
        List<String> before = fingerprint(s);
        assertEquals(before, fingerprint(openFresh()), "snapshot path");
        Files.write(st.snapshotFile(), new byte[]{1});
        assertEquals(before, fingerprint(openFresh()), "working-text path");
    }

    private DictionaryService openFresh() throws IOException {
        DictionaryService d = new DictionaryService(new FileStorage(st.baseDir()));
        d.load();
        return d;
    }

    // ---------- slang trùng ----------

    @Test void duplicateSlangIsKeptAsSeparateEntriesAndCaseInsensitive() throws Exception {
        s.add("DUPE", "one");
        s.add("dupe", "two");
        s.add(" Dupe ", "three");
        assertEquals(3, s.countForSlang("DUPE"));
        assertTrue(s.slangExists("dUpE"));
        assertEquals(3, openFresh().countForSlang("dupe"));
    }

    // ---------- definition ----------

    @Test void multilineDefinitionIsNormalisedToOneLineNotRejected() throws Exception {
        var e = s.add("MULTI", "line one\nline two\r\n\tline three");
        assertEquals("line one line two line three", e.getDefinition());
        assertEquals("line one line two line three", openFresh().searchSlang("multi", false).entries().get(0).getDefinition());
    }

    @Test void definitionRulesEmptyBacktickLengthAndBrokenUnicode() {
        assertNotNull(DictionaryService.validateDefinition(null));
        assertNotNull(DictionaryService.validateDefinition("  \n\t "));
        assertNull(DictionaryService.validateDefinition("has ` backtick and | pipe"));
        assertNotNull(DictionaryService.validateDefinition("y".repeat(2001)));
        assertNotNull(DictionaryService.validateDefinition("broken\uD83D"));
        assertEquals("a b", DictionaryService.cleanDefinition("a\u2028 b"));
    }

    @Test void definitionWithBacktickIsNotSplitWrongOnReload() throws Exception {
        s.add("BT", "x ` y ` z");
        assertEquals("x ` y ` z", openFresh().searchSlang("bt", false).entries().get(0).getDefinition());
    }
}
