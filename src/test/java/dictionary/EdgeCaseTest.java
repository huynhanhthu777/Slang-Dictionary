package dictionary;

import dictionary.model.QuizQuestion;
import dictionary.model.QuizType;
import dictionary.model.SlangEntry;
import dictionary.repository.DictionaryRepository;
import dictionary.service.DictionaryService;
import dictionary.service.QuizService;
import dictionary.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static dictionary.Fixtures.parse;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cac truong hop dac biet ma giao vien hay thu: tu dien rong / rat it du lieu,
 * slang trung nhau, quiz khong du 4 dap an hop le, file bi mat.
 * Dung du lieu nho (vai dong) nen chay rat nhanh.
 */
class EdgeCaseTest {

    // ---------- ham dung chung ----------

    /** Tao thu muc app moi chi co file raw nho (moi dong dang slang`definition). */
    private static Path tinyApp(Path tmp, String rawText) throws IOException {
        Path original = tmp.resolve("data/original");
        Files.createDirectories(original);
        Files.writeString(original.resolve("slang.raw.txt"), rawText, StandardCharsets.UTF_8);
        return tmp;
    }

    private static DictionaryService open(Path dir) throws IOException {
        DictionaryService s = new DictionaryService(new FileStorage(dir));
        s.load();
        return s;
    }

    private static DictionaryRepository repoOf(String text) throws Exception {
        DictionaryRepository r = new DictionaryRepository();
        r.loadEntries(parse(text).entries());
        return r;
    }

    private static Set<String> definitionsOf(DictionaryService s, String slang) {
        Set<String> out = new TreeSet<>();
        for (SlangEntry e : s.repository().findBySlang(slang)) out.add(e.getDefinition());
        return out;
    }

    private static long idWithDefinition(DictionaryService s, String slang, String definition) {
        for (SlangEntry e : s.repository().findBySlang(slang)) {
            if (e.getDefinition().equals(definition)) return e.getId();
        }
        throw new AssertionError("no entry " + slang + " / " + definition);
    }

    /** Mot cau hoi hop le: dung 4 lua chon khac nhau, dung 1 dap an dung. */
    private static void assertValid(DictionaryRepository repo, QuizQuestion q) {
        assertEquals(4, q.options().size(), "must have 4 options");
        Set<String> lower = new HashSet<>();
        for (String o : q.options()) lower.add(o.toLowerCase());
        assertEquals(4, lower.size(), "options must be different");

        int correctCount = 0;
        if (q.type() == QuizType.SLANG_TO_MEANING) {
            Set<String> meaningsOfPrompt = new HashSet<>();
            for (SlangEntry e : repo.findBySlang(q.prompt())) meaningsOfPrompt.add(e.getDefinition().toLowerCase());
            for (String option : q.options()) if (meaningsOfPrompt.contains(option.toLowerCase())) correctCount++;
        } else {
            for (String option : q.options()) {
                for (SlangEntry e : repo.findBySlang(option)) {
                    if (e.getDefinition().equalsIgnoreCase(q.prompt())) { correctCount++; break; }
                }
            }
        }
        assertEquals(1, correctCount, "exactly one correct answer for: " + q.prompt());
        assertTrue(q.isCorrect(q.correctIndex()));
        assertFalse(q.isCorrect((q.correctIndex() + 1) % 4));
    }

    // ---------- Quiz (muc 9 va 10): du lieu vua du / khong du ----------

    @Test void quizWorksWithExactlyFourDifferentEntries() throws Exception {
        var repo = repoOf("A`one\nB`two\nC`three\nD`four\n");
        var quiz = new QuizService(repo, new Random(1));
        for (QuizType t : QuizType.values()) {
            for (int i = 0; i < 100; i++) assertValid(repo, quiz.next(t));
        }
    }

    @Test void slangToMeaningRefusesWhenThereAreFewerThanFourDifferentDefinitions() throws Exception {
        // 4 entry nhung chi co 3 nghia khac nhau -> khong the co 4 dap an khac nhau
        var repo = repoOf("A`same\nB`same\nC`other\nD`another\n");
        var quiz = new QuizService(repo, new Random(2));
        var ex = assertThrows(IllegalStateException.class, () -> quiz.next(QuizType.SLANG_TO_MEANING));
        assertFalse(ex.getMessage().isBlank(), "message must explain the problem");
    }

    @Test void meaningToSlangNeverUsesAnAnswerThatHasTheSameMeaning() throws Exception {
        // A va B cung nghia "same": neu hoi "same" thi chi con C, D la nhieu -> khong du 4, nen khong bao gio hoi cau do
        var repo = repoOf("A`same\nB`same\nC`other\nD`another\n");
        var quiz = new QuizService(repo, new Random(3));
        for (int i = 0; i < 200; i++) {
            QuizQuestion q = quiz.next(QuizType.MEANING_TO_SLANG);
            assertNotEquals("same", q.prompt());
            assertValid(repo, q);
        }
    }

    @Test void quizReportsClearlyWhenEveryEntryHasTheSameDefinition() throws Exception {
        var repo = repoOf("A`x\nB`x\nC`x\nD`x\nE`x\nF`x\n");
        var quiz = new QuizService(repo, new Random(4));
        long start = System.nanoTime();
        for (QuizType t : QuizType.values()) {
            var ex = assertThrows(IllegalStateException.class, () -> quiz.next(t));
            assertFalse(ex.getMessage().isBlank());
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000, "must not hang");
    }

    @Test void oneSlangWithTwoMeaningsNeverShowsBothMeaningsAsAnswers() throws Exception {
        var repo = repoOf("A`one\nA`uno\nB`two\nC`three\n");
        var quiz = new QuizService(repo, new Random(5));
        for (int i = 0; i < 200; i++) {
            QuizQuestion q = quiz.next(QuizType.SLANG_TO_MEANING);
            assertNotEquals("A", q.prompt(), "A has 2 meanings, so only 3 different answers exist");
            assertValid(repo, q);
        }
        // chi co 3 slang khac nhau -> khong the co 4 slang lam dap an
        assertThrows(IllegalStateException.class, () -> quiz.next(QuizType.MEANING_TO_SLANG));
    }

    @Test void manySlangsWithTheSameDefinitionNeverGiveTwoCorrectAnswers() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10; i++) text.append("S").append(i).append("`shared meaning\n");
        text.append("P`pp\nQ`qq\nR`rr\n");
        var repo = repoOf(text.toString());
        var quiz = new QuizService(repo, new Random(6));
        boolean sawSharedMeaning = false;
        for (int i = 0; i < 300; i++) {
            QuizQuestion q = quiz.next(QuizType.MEANING_TO_SLANG);
            assertValid(repo, q);
            if (q.prompt().equals("shared meaning")) {
                // 10 slang cung nghia: chi 1 slang la dap an dung, 3 dap an nhieu phai la P, Q, R
                sawSharedMeaning = true;
                for (int k = 0; k < 4; k++) {
                    if (k != q.correctIndex()) assertTrue(Set.of("P", "Q", "R").contains(q.options().get(k)));
                }
            }
        }
        assertTrue(sawSharedMeaning, "this case should be tested at least once");
        for (int i = 0; i < 300; i++) assertValid(repo, quiz.next(QuizType.SLANG_TO_MEANING));
    }

    @Test void quizStopsAndStartsAgainWhenEntriesAreDeletedAndAdded() throws Exception {
        var repo = repoOf("A`one\nB`two\nC`three\nD`four\n");
        var quiz = new QuizService(repo, new Random(7));
        assertValid(repo, quiz.next(QuizType.SLANG_TO_MEANING));
        repo.delete(repo.findBySlang("D").get(0).getId());
        assertThrows(IllegalStateException.class, () -> quiz.next(QuizType.SLANG_TO_MEANING));
        assertThrows(IllegalStateException.class, () -> quiz.next(QuizType.MEANING_TO_SLANG));
        repo.add("E", "five");
        assertValid(repo, quiz.next(QuizType.SLANG_TO_MEANING));
        assertValid(repo, quiz.next(QuizType.MEANING_TO_SLANG));
    }

    // ---------- Random (muc 8): rong va rat it du lieu ----------

    @Test void randomOnEmptyDictionaryFailsClearlyAndRecoversAfterReset(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`one\nB`two\nC`three\n");
        var s = open(dir);
        for (SlangEntry e : s.repository().all()) s.delete(e.getId());
        assertTrue(s.repository().isEmpty());
        assertThrows(NoSuchElementException.class, s::randomEntry);
        assertThrows(NoSuchElementException.class, s::slangOfTheDay);
        assertTrue(s.searchSlang("A", false).entries().isEmpty());
        assertTrue(s.searchDefinition("one", false).entries().isEmpty());

        // mo lai ung dung voi tu dien rong van phai chay duoc (khong tu dong reset)
        var again = open(dir);
        assertTrue(again.repository().isEmpty());

        again.resetToOriginal();
        assertEquals(3, again.repository().size());
        assertNotNull(again.randomEntry());
        assertNotNull(again.slangOfTheDay());
    }

    @Test void randomWithOnlyOneEntryAlwaysReturnsIt(@TempDir Path tmp) throws Exception {
        var s = open(tinyApp(tmp, "ONLY`just one\n"));
        for (int i = 0; i < 50; i++) assertEquals("ONLY", s.randomEntry().getSlang());
        assertEquals("ONLY", s.slangOfTheDay().getSlang());
        assertThrows(IllegalStateException.class, () -> new QuizService(s.repository()).next(QuizType.SLANG_TO_MEANING));
    }

    // ---------- Add (muc 4): Overwrite / Keep both sau khi mo lai ----------

    @Test void overwriteAndKeepBothAreStillThereAfterReopening(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\nB`3\nC`4\n");
        var s = open(dir);

        s.overwrite("b", "only");                       // Overwrite: B chi con 1 nghia
        assertEquals(Set.of("only"), definitionsOf(open(dir), "B"));

        var s2 = open(dir);
        s2.add("B", "extra");                           // Keep both: B co 2 nghia
        var s3 = open(dir);
        assertEquals(Set.of("extra", "only"), definitionsOf(s3, "B"));
        assertEquals(4, s3.repository().size());

        s3.overwrite("NEWWORD", "brand new");           // Overwrite slang chua ton tai = them moi
        assertEquals(Set.of("brand new"), definitionsOf(open(dir), "newword"));
    }

    // ---------- Edit (muc 5): sua thanh slang da co ----------

    @Test void editingIntoAnExistingSlangUpdatesTheIndexAndSurvivesRestart(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\nB`3\nC`4\n");
        var s = open(dir);
        long idA = idWithDefinition(s, "A", "1");

        s.update(idA, "b", "moved");                    // A -> B (da co 2 nghia)
        assertFalse(s.slangExists("A"));
        assertEquals(3, s.countForSlang("B"));
        assertTrue(s.repository().findByPrefix("a", 10).isEmpty(), "prefix index must forget A");
        assertEquals(1, s.repository().findByDefinition("moved").size());

        var again = open(dir);
        assertFalse(again.slangExists("A"));
        assertEquals(Set.of("2", "3", "moved"), definitionsOf(again, "B"));
        assertEquals(4, again.repository().size());

        // sua nguoc lai
        long idMoved = idWithDefinition(again, "B", "moved");
        again.update(idMoved, "A", "1");
        var last = open(dir);
        assertEquals(Set.of("1"), definitionsOf(last, "A"));
        assertEquals(Set.of("2", "3"), definitionsOf(last, "B"));
    }

    @Test void editChangesOnlyTheChosenEntryAmongDuplicates(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "B`2\nB`3\nB`4\n");
        var s = open(dir);
        s.update(idWithDefinition(s, "B", "3"), "B", "changed");
        assertEquals(Set.of("2", "changed", "4"), definitionsOf(open(dir), "B"));
    }

    // ---------- Delete (muc 6): xoa dung entry khi co nhieu slang trung ----------

    @Test void deleteRemovesExactlyTheChosenDuplicateAndSurvivesRestart(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "B`2\nB`3\nB`4\nA`1\n");
        var s = open(dir);
        s.delete(idWithDefinition(s, "B", "3"));
        assertEquals(Set.of("2", "4"), definitionsOf(s, "B"));

        var again = open(dir);
        assertEquals(Set.of("2", "4"), definitionsOf(again, "B"));
        assertEquals(3, again.repository().size());
        assertEquals(Set.of("1"), definitionsOf(again, "A"));

        // xoa not cac entry con lai cua B thi B bien mat khoi index, A van con
        again.delete(idWithDefinition(again, "B", "2"));
        again.delete(idWithDefinition(again, "B", "4"));
        var last = open(dir);
        assertFalse(last.slangExists("B"));
        assertTrue(last.slangExists("A"));
    }

    @Test void deletingAnEntryThatNoLongerExistsGivesAClearErrorAndChangesNothing(@TempDir Path tmp) throws Exception {
        var s = open(tinyApp(tmp, "A`1\nB`2\n"));
        long id = idWithDefinition(s, "A", "1");
        s.delete(id);
        assertThrows(IllegalArgumentException.class, () -> s.delete(id));
        assertEquals(1, s.repository().size());
    }

    // ---------- Reset (muc 7): sau Reset va khoi dong lai ----------

    @Test void afterResetAndRestartTheOriginalDataIsBackExactly(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\nB`3\nC`4\n");
        var s = open(dir);
        List<String> original = Fixtures.fingerprint(s);

        s.add("NEW", "added");
        s.delete(idWithDefinition(s, "C", "4"));
        s.overwrite("B", "changed");
        s.resetToOriginal();
        assertEquals(original, Fixtures.fingerprint(s));

        assertEquals(original, Fixtures.fingerprint(open(dir)), "same after restart");
        var again = open(dir);
        again.add("AFTER", "reset");                    // van them duoc, khong bi trung ID
        assertEquals(original.size() + 1, open(dir).repository().size());
    }

    // ---------- Java IO (muc 11): mat file ----------

    @Test void missingSnapshotIsRebuiltFromTheReadableWorkingText(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\n");
        var s = open(dir);
        s.add("USER", "my own word");
        List<String> expected = Fixtures.fingerprint(s);

        Files.delete(new FileStorage(dir).snapshotFile());
        assertEquals(expected, Fixtures.fingerprint(open(dir)));
    }

    @Test void missingWorkingTextIsRebuiltFromTheSnapshot(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\n");
        var s = open(dir);
        s.add("USER", "my own word");
        List<String> expected = Fixtures.fingerprint(s);

        Files.delete(new FileStorage(dir).workingTextFile());
        assertEquals(expected, Fixtures.fingerprint(open(dir)));
        assertTrue(Files.exists(new FileStorage(dir).workingTextFile()), "working text is written again");
    }

    @Test void missingWholeWorkingFolderStartsFromTheOriginalWithoutCrashing(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\n");
        var s = open(dir);
        s.add("USER", "my own word");
        var storage = new FileStorage(dir);
        try (var walk = Files.walk(storage.workingDir())) {
            List<Path> files = new ArrayList<>();
            walk.forEach(files::add);
            for (int i = files.size() - 1; i >= 0; i--) Files.delete(files.get(i));
        }
        var again = open(dir);
        assertEquals(2, again.repository().size());
        assertFalse(again.slangExists("USER"));
    }

    @Test void missingNormalizedFileIsCreatedAgainFromTheRawFile(@TempDir Path tmp) throws Exception {
        Path dir = tinyApp(tmp, "A`1\nB`2\n");
        open(dir);
        var storage = new FileStorage(dir);
        Files.delete(storage.normalizedFile());
        var s = open(dir);
        assertTrue(Files.exists(storage.normalizedFile()));
        s.add("X", "y");
        s.resetToOriginal();
        assertEquals(2, s.repository().size());
    }
}
