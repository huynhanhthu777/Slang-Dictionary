package dictionary.service;

import dictionary.model.QuizQuestion;
import dictionary.model.QuizType;
import dictionary.model.SlangEntry;
import dictionary.repository.DictionaryRepository;

import java.util.*;

/**
 * Sinh câu đố 4 đáp án: đúng một đáp án đúng, các đáp án nhiễu khác nhau và KHÔNG
 * vô tình đúng (kể cả khi một slang có nhiều nghĩa hoặc nhiều slang có cùng nghĩa).
 */
public class QuizService {
    private static final int OPTIONS = 4;
    private static final int MAX_TRIES = 400;

    private final DictionaryRepository repo;
    private final Random random;

    public QuizService(DictionaryRepository repo) { this(repo, new Random()); }
    public QuizService(DictionaryRepository repo, Random random) { this.repo = repo; this.random = random; }

    /** Tao cau hoi moi. Neu du lieu khong du de co 4 dap an hop le thi bao loi ro rang (khong treo, khong tao cau sai). */
    public QuizQuestion next(QuizType type) {
        if (repo.size() < OPTIONS) {
            throw new IllegalStateException("The quiz needs at least " + OPTIONS + " entries in the dictionary. Please add more words first.");
        }
        // Thu toi da 50 lan voi cac slang ngau nhien; khong duoc thi bao loi
        for (int attempt = 0; attempt < 50; attempt++) {
            QuizQuestion q = type == QuizType.SLANG_TO_MEANING ? slangToMeaning() : meaningToSlang();
            if (q != null) return q;
        }
        throw new IllegalStateException("Not enough different words or meanings to build a question with 4 answers. Please add more words first.");
    }

    private QuizQuestion slangToMeaning() {
        SlangEntry src = repo.random(random);
        List<SlangEntry> sameSlang = repo.findBySlang(src.getSlang());
        // Moi nghia cua chinh slang nay khong duoc xuat hien trong dap an nhieu
        Set<String> forbidden = new HashSet<>();
        for (SlangEntry e : sameSlang) forbidden.add(norm(e.getDefinition()));

        List<String> options = new ArrayList<>();
        options.add(src.getDefinition());
        Set<String> used = new HashSet<>(forbidden);
        for (int t = 0; t < MAX_TRIES && options.size() < OPTIONS; t++) {
            SlangEntry d = repo.random(random);
            if (used.add(norm(d.getDefinition()))) options.add(d.getDefinition());
        }
        if (options.size() < OPTIONS) return null;
        return build(QuizType.SLANG_TO_MEANING, src.getSlang(), options, src.getDefinition(), src);
    }

    private QuizQuestion meaningToSlang() {
        SlangEntry src = repo.random(random);
        String def = norm(src.getDefinition());
        List<String> options = new ArrayList<>();
        options.add(src.getSlang());
        Set<String> used = new HashSet<>();
        used.add(src.key());
        for (int t = 0; t < MAX_TRIES && options.size() < OPTIONS; t++) {
            SlangEntry d = repo.random(random);
            if (used.contains(d.key())) continue;
            boolean sameMeaning = false; // slang khác nhưng cũng có đúng nghĩa này -> đáp án nhiễu sẽ gây nhầm
            for (SlangEntry e : repo.findBySlang(d.getSlang())) {
                if (norm(e.getDefinition()).equals(def)) { sameMeaning = true; break; }
            }
            if (sameMeaning) continue;
            used.add(d.key());
            options.add(d.getSlang());
        }
        if (options.size() < OPTIONS) return null;
        return build(QuizType.MEANING_TO_SLANG, src.getDefinition(), options, src.getSlang(), src);
    }

    private QuizQuestion build(QuizType type, String prompt, List<String> options, String correct, SlangEntry src) {
        Collections.shuffle(options, random);
        return new QuizQuestion(type, prompt, List.copyOf(options), options.indexOf(correct), src);
    }

    private static String norm(String s) { return s.trim().toLowerCase(Locale.ROOT); }
}
