package dictionary;

import dictionary.model.QuizType;
import dictionary.repository.DictionaryRepository;
import dictionary.service.QuizService;
import dictionary.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchAndQuizTest {
    @Test void everyQuestionHasFourDistinctOptionsAndExactlyOneCorrect(@TempDir Path tmp) throws Exception {
        var st = new FileStorage(tempApp(tmp));
        st.ensureOriginal();
        var r = new DictionaryRepository();
        r.loadEntries(st.readNormalized().entries());
        var quiz = new QuizService(r, new Random(7));
        for (QuizType t : QuizType.values()) {
            for (int i = 0; i < 500; i++) {
                var q = quiz.next(t);
                assertEquals(4, q.options().size());
                assertEquals(4, new HashSet<>(q.options().stream().map(String::toLowerCase).toList()).size(), "distinct options");
                if (t == QuizType.SLANG_TO_MEANING) {
                    var defs = new HashSet<String>();
                    for (var e : r.findBySlang(q.prompt())) defs.add(e.getDefinition().toLowerCase());
                    long correct = q.options().stream().filter(o -> defs.contains(o.toLowerCase())).count();
                    assertEquals(1, correct, "exactly one correct meaning for " + q.prompt());
                } else {
                    for (int k = 0; k < 4; k++) {
                        if (k == q.correctIndex()) continue;
                        for (var e : r.findBySlang(q.options().get(k))) {
                            assertFalse(e.getDefinition().equalsIgnoreCase(q.prompt()), "distractor is also correct");
                        }
                    }
                }
            }
        }
    }

    @Test void quizRefusesToRunWithFewerThanFourEntries() throws Exception {
        var r = new DictionaryRepository();
        r.loadEntries(parse("A`1\nB`2\nC`3\n").entries());
        assertThrows(IllegalStateException.class, () -> new QuizService(r).next(QuizType.SLANG_TO_MEANING));
    }

    @Test void searchSeesChangesImmediately() throws Exception {
        var r = sample();
        var e = r.add("NEWW", "brand new meaning");
        assertEquals(1, r.findByDefinition("brand new").size());
        r.update(e.getId(), "NEWW", "completely different");
        assertEquals(0, r.findByDefinition("brand new").size());
        assertEquals(1, r.findByDefinition("different").size());
    }

    @Test void randomWordOfTheDayIsStableWithinADay(@TempDir Path tmp) throws Exception {
        var s = new dictionary.service.DictionaryService(new FileStorage(tempApp(tmp)));
        s.load();
        assertEquals(s.slangOfTheDay().getId(), s.slangOfTheDay().getId());
        assertNotNull(s.randomEntry());
    }
}
