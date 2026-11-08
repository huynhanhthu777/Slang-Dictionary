package dictionary.gui;

import dictionary.service.DictionaryService;
import dictionary.storage.FileStorage;
import dictionary.ui.MainFrame;
import dictionary.ui.Tasks;

import javax.swing.*;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;

/** Chạy trong một JVM riêng: bấm lưu rồi đóng cửa sổ NGAY khi việc ghi (cố ý chậm) đang diễn ra. */
public final class CloseDuringSaveHarness {
    private CloseDuringSaveHarness() { }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long delayMs = Long.parseLong(args[1]);
        FileStorage slow = new FileStorage(dir) {
            @Override protected void beforeRename(Path target, Path tmp) throws IOException {
                if (target.getFileName().toString().equals("dictionary.dat")) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
        };
        DictionaryService service = new DictionaryService(slow);
        service.load();
        final MainFrame[] holder = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> { holder[0] = new MainFrame(service); holder[0].setVisible(true); holder[0].start(); });
        Thread.sleep(1500);   // cho cửa sổ nạp xong
        Tasks.write(() -> { service.add("ZZCLOSEWHILESAVING", "must not be lost"); return true; }, ok -> { }, err -> { });
        Thread.sleep(150);    // việc ghi đang chạy (đang nằm trong khoảng chờ cố ý)
        System.out.println("CLOSING-NOW");
        System.out.flush();
        SwingUtilities.invokeAndWait(() -> holder[0].dispatchEvent(new WindowEvent(holder[0], WindowEvent.WINDOW_CLOSING)));
        Thread.sleep(20_000);  // nếu tới đây nghĩa là KHÔNG thoát: test sẽ báo lỗi do quá giờ
    }
}
