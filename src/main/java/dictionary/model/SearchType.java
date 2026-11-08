package dictionary.model;

public enum SearchType {
    SLANG("Slang"), DEFINITION("Definition");

    private final String label;
    SearchType(String label) { this.label = label; }
    public String label() { return label; }
}
