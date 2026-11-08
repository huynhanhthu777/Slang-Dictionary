package dictionary.ui;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Mô hình luồng của ứng dụng:
 * <ul>
 *   <li><b>EDT</b>: chỉ cập nhật Swing component. Mọi callback ok/fail đều chạy ở EDT.</li>
 *   <li><b>read pool</b> ({@link #run}): tìm kiếm, nạp bảng, benchmark... không thay đổi dữ liệu từ điển.</li>
 *   <li><b>writer</b> ({@link #write}): MỘT luồng duy nhất cho Add/Edit/Delete/Reset/xóa history,
 *       nên thay đổi được áp dụng và lưu đúng thứ tự người dùng thao tác.</li>
 * </ul>
 */
public final class Tasks {
    private Tasks() { }

    private static volatile ExecutorService READ = Executors.newFixedThreadPool(3, daemon("dictionary-read"));
    private static volatile ExecutorService WRITE = Executors.newSingleThreadExecutor(daemon("dictionary-write"));

    private static ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }

    /** Việc nặng chỉ ĐỌC dữ liệu. */
    public static <T> void run(Callable<T> work, Consumer<T> ok, Consumer<Throwable> fail) { submit(READ, work, ok, fail); }

    /** Việc THAY ĐỔI dữ liệu: tuần tự trên một luồng ghi. */
    public static <T> void write(Callable<T> work, Consumer<T> ok, Consumer<Throwable> fail) { submit(WRITE, work, ok, fail); }

    private static <T> void submit(ExecutorService pool, Callable<T> work, Consumer<T> ok, Consumer<Throwable> fail) {
        pool.execute(() -> {
            T result;
            try {
                result = work.call();
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> fail.accept(t));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                try { ok.accept(result); } catch (RuntimeException e) { fail.accept(e); }
            });
        });
    }

    /**
     * Khi đóng app: chờ (tối đa 5 giây) cho các thao tác ghi đang chạy/đang xếp hàng hoàn tất để không bỏ dở việc lưu.
     * Gọi nhiều lần cũng được (windowClosing và shutdown hook đều gọi).
     */
    public static synchronized void shutdownAndWait() {
        WRITE.shutdown();
        try { WRITE.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        READ.shutdownNow();
    }

    /** Dành cho test: dựng lại các luồng sau {@link #shutdownAndWait()}. */
    public static synchronized void restart() {
        shutdownAndWait();
        READ = Executors.newFixedThreadPool(3, daemon("dictionary-read"));
        WRITE = Executors.newSingleThreadExecutor(daemon("dictionary-write"));
    }

    public static String message(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
