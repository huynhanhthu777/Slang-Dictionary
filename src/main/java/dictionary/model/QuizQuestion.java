package dictionary.model;

import java.util.List;

/** Một câu đố: đúng 4 đáp án, đúng một đáp án chính xác. */
public record QuizQuestion(QuizType type, String prompt, List<String> options, int correctIndex, SlangEntry source) {
    public boolean isCorrect(int index) { return index == correctIndex; }
    public String correctAnswer() { return options.get(correctIndex); }
}
