package dictionary;

import dictionary.model.SlangEntry;
import dictionary.repository.CorruptDataException;
import dictionary.repository.DictionaryRepository;
import dictionary.repository.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import static dictionary.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    @Test void slangLookupIsCaseInsensitiveAndTrims() throws Exception {
        var r = sample();
        assertEquals(1, r.findBySlang("  lol ").size());
        assertEquals(0, r.findBySlang("nothing").size());
    }

    @Test void duplicateSlangReturnsAllMeanings() throws Exception {
        assertEquals(2, sample().findBySlang("ama").size());
    }

    @Test void definitionSearchIsSubstringAndCaseInsensitive() throws Exception {
        var r = sample();
        assertEquals(1, r.findByDefinition("LATER").size());
        assertEquals(1, r.findByDefinition("ight").size(), "substring inside 'right'");
        assertEquals(0, r.findByDefinition("  ").size());
        assertEquals(0, r.findByDefinition(null).size());
    }

    @Test void prefixSuggestionsAreOrderedAndLimited() throws Exception {
        var r = sample();
        assertEquals("AMA", r.findByPrefix("am", 10).get(0).getSlang());
        assertEquals(1, r.findByPrefix("am", 1).size());
        assertEquals(0, r.findByPrefix("zzz", 5).size());
        assertEquals(0, r.findByPrefix("", 5).size());
    }

    @Test void addUpdateDeleteKeepAllIndexesConsistent() throws Exception {
        var r = sample();
        int n = r.size();
        var e = r.add("YOLO", "You only live once");
        assertEquals(n + 1, r.size());
        assertEquals(1, r.findBySlang("yolo").size());
        r.update(e.getId(), "YOLOO", "changed");
        assertEquals(0, r.findBySlang("yolo").size(), "old key removed");
        assertEquals(1, r.findBySlang("yoloo").size());
        assertEquals(1, r.findByPrefix("yoloo", 5).size(), "prefix index updated");
        r.delete(e.getId());
        assertEquals(n, r.size());
        assertEquals(0, r.findBySlang("yoloo").size());
        assertNull(r.get(e.getId()));
    }

    @Test void deletingOneOfTwoDuplicatesKeepsTheOther() throws Exception {
        var r = sample();
        var list = r.findBySlang("ama");
        r.delete(list.get(0).getId());
        assertEquals(1, r.findBySlang("ama").size());
        assertEquals(list.get(1).getId(), r.findBySlang("ama").get(0).getId());
    }

    @Test void randomStaysValidAfterManyDeletes() throws Exception {
        var r = sample();
        Random rnd = new Random(1);
        for (var e : r.all()) if (r.size() > 2) r.delete(e.getId());
        for (int i = 0; i < 50; i++) assertNotNull(r.get(r.random(rnd).getId()));
    }

    @Test void snapshotRoundTripKeepsDataAndIndex() throws Exception {
        var r = sample();
        var r2 = new DictionaryRepository();
        r2.loadSnapshot(r.toSnapshot(7));
        assertEquals(r.size(), r2.size());
        assertEquals(2, r2.findBySlang("ama").size());
        assertTrue(r2.add("NEW", "x").getId() > 6, "next id continues");
    }

    // ---------- kiểm tra tính nhất quán khi nạp ----------

    private static Snapshot snap(ArrayList<SlangEntry> entries, HashMap<String, ArrayList<Long>> idx, long nextId) {
        return new Snapshot(1, nextId, entries, idx);
    }

    private static HashMap<String, ArrayList<Long>> index(SlangEntry... es) {
        var m = new HashMap<String, ArrayList<Long>>();
        for (var e : es) m.computeIfAbsent(e.key(), k -> new ArrayList<>()).add(e.getId());
        return m;
    }

    @Test void validateAcceptsConsistentSnapshot() throws Exception {
        var a = new SlangEntry(1, "A", "x");
        DictionaryRepository.validate(snap(new ArrayList<>(java.util.List.of(a)), index(a), 2));
    }

    @Test void validateRejectsIndexPointingToWrongEntry() {
        var a = new SlangEntry(1, "A", "x");
        var idx = new HashMap<String, ArrayList<Long>>();
        idx.put("b", new ArrayList<>(java.util.List.of(1L)));
        assertThrows(CorruptDataException.class, () -> DictionaryRepository.validate(snap(new ArrayList<>(java.util.List.of(a)), idx, 2)));
    }

    @Test void validateRejectsMissingIndexEntryDuplicateIdAndBadNextId() {
        var a = new SlangEntry(1, "A", "x");
        var b = new SlangEntry(2, "B", "y");
        assertThrows(CorruptDataException.class, () -> DictionaryRepository.validate(snap(new ArrayList<>(java.util.List.of(a, b)), index(a), 3)), "index size mismatch");
        assertThrows(CorruptDataException.class, () -> DictionaryRepository.validate(snap(new ArrayList<>(java.util.List.of(a, a)), index(a, a), 3)), "duplicate id");
        assertThrows(CorruptDataException.class, () -> DictionaryRepository.validate(snap(new ArrayList<>(java.util.List.of(a, b)), index(a, b), 2)), "nextId too small");
    }

    @Test void invalidSnapshotDoesNotTouchTheLiveRepository() throws Exception {
        var r = sample();
        int n = r.size();
        var a = new SlangEntry(1, "A", "x");
        assertThrows(CorruptDataException.class, () -> r.loadSnapshot(snap(new ArrayList<>(java.util.List.of(a)), new HashMap<>(), 2)));
        assertEquals(n, r.size(), "failed load must leave existing data intact");
    }

    // ---------- gợi ý từ khóa khi tìm theo definition ----------

    @Test void definitionKeywordSuggestionsCompleteThePrefixMostCommonFirst() throws Exception {
        var r = new DictionaryRepository();
        r.loadEntries(parse("A`laughing out loud\nB`laughing hard\nC`laughter and joy\nD`Laughing| laugh\nE`lamp\n").entries());
        var s = r.suggestDefinitionWords("laug", 5);
        assertEquals("laughing", s.get(0), "most common first: " + s);
        assertTrue(s.containsAll(java.util.List.of("laughter", "laugh")), s.toString());
        assertFalse(s.contains("lamp"));
        assertTrue(r.suggestDefinitionWords("LAUGHING", 5).isEmpty(), "the typed word itself is not suggested again");
        assertEquals(1, r.suggestDefinitionWords("laug", 1).size());
        assertTrue(r.suggestDefinitionWords("", 5).isEmpty());
        assertTrue(r.suggestDefinitionWords("zzz", 5).isEmpty());
    }

    @Test void keywordIndexFollowsEditsAndDeletes() throws Exception {
        var r = new DictionaryRepository();
        r.loadEntries(parse("A`flibbertigibbet person\n").entries());
        assertEquals(java.util.List.of("flibbertigibbet"), r.suggestDefinitionWords("flib", 5));
        var e = r.findBySlang("A").get(0);
        r.update(e.getId(), "A", "something else");
        assertTrue(r.suggestDefinitionWords("flib", 5).isEmpty(), "stale words must disappear after an edit");
        assertEquals(java.util.List.of("something"), r.suggestDefinitionWords("some", 5));
        r.add("B", "flibbertigibbet again");
        assertEquals(java.util.List.of("flibbertigibbet"), r.suggestDefinitionWords("flib", 5));
    }

    @Test void popularKeywordsAreRealWordsFromDefinitions() throws Exception {
        var r = new DictionaryRepository();
        var sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("S").append(i).append("`friendly person ").append(i % 2 == 0 ? "happy" : "sleepy").append("\n");
        r.loadEntries(parse(sb.toString()).entries());
        var words = r.popularDefinitionWords(3, new Random(1));
        assertFalse(words.isEmpty());
        for (String w : words) assertFalse(r.findByDefinition(w).isEmpty(), "'" + w + "' must find something");
    }
}
