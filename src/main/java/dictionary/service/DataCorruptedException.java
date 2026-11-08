package dictionary.service;

import java.io.IOException;

/** Dữ liệu làm việc và mọi bản phục hồi đều không đọc được. File được giữ nguyên, KHÔNG tự reset. */
public class DataCorruptedException extends IOException {
    public DataCorruptedException(String message, Throwable cause) { super(message, cause); }
}
