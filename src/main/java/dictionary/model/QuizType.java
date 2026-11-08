package dictionary.model;

public enum QuizType {
    SLANG_TO_MEANING("Guess the meaning"),
    MEANING_TO_SLANG("Guess the slang");

    private final String label;
    QuizType(String label) { this.label = label; }
    public String label() { return label; }
}
