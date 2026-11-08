package dictionary.ui;

import dictionary.service.DictionaryService;
import dictionary.storage.FileStorage;

import javax.swing.*;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Điểm vào của ứng dụng. Thư mục data: -Ddictionary.home, hoặc cạnh file JAR. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        Path base = args.length > 0 ? Paths.get(args[0]).toAbsolutePath() : FileStorage.resolveHome();
        Runtime.getRuntime().addShutdownHook(new Thread(Tasks::shutdownAndWait, "dictionary-shutdown"));
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
            if (args.length > 1 && args[1].equals("dark")) Theme.setDark(true);
            MainFrame frame = new MainFrame(new DictionaryService(new FileStorage(base)));
            frame.setVisible(true);
            frame.start();
        });
    }
}
