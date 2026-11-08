package dictionary.repository;

/** Dữ liệu đã lưu không nhất quán hoặc hỏng (checksum, index lệch...). */
public class CorruptDataException extends Exception {
    public CorruptDataException(String message) { super(message); }
}
