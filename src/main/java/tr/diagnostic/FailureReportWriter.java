package tr.diagnostic;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.Arrays;

/** Keeps failed GUI operations available for support, independently of report staging. */
class FailureReportWriter {
    private final Path fallbackDirectory;

    FailureReportWriter() {
        this(Paths.get(System.getProperty("user.home"), ".java-diagnostic", "errors"));
    }
    FailureReportWriter(Path fallbackDirectory) { this.fallbackDirectory = fallbackDirectory; }

    Path write(Path output, String operation, Throwable error, String... sensitiveValues) throws IOException {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String text = "Zaman: " + ZonedDateTime.now() + "\nİşlem: " + operation
            + "\nCollector Java: " + System.getProperty("java.version") + "\n\n" + trace;
        text = redact(text, sensitiveValues);
        IOException failure = new IOException("Hata kaydı çıktı ve kullanıcı klasörlerine yazılamadı.");
        for (Path directory : Arrays.asList(output.resolve("collector-errors"), fallbackDirectory)) {
            Path file = null;
            try {
                Files.createDirectories(directory);
                FileSupport.restrictDirectory(directory);
                file = Files.createTempFile(directory, "collector-error-", ".txt");
                Files.write(file, text.getBytes(StandardCharsets.UTF_8));
                return file.toAbsolutePath();
            } catch (IOException | SecurityException e) {
                failure.addSuppressed(e);
                if (file != null) try { Files.deleteIfExists(file); } catch (IOException | SecurityException cleanup) { failure.addSuppressed(cleanup); }
            }
        }
        throw failure;
    }

    static String redact(String text, String... sensitiveValues) {
        // Longest first: a username may also be part of a password.
        String[] values = sensitiveValues.clone();
        Arrays.sort(values, (left, right) -> Integer.compare(right == null ? 0 : right.length(), left == null ? 0 : left.length()));
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            String escaped = new com.google.gson.Gson().toJson(value);
            text = text.replace(escaped.substring(1, escaped.length() - 1), "[gizlendi]").replace(value, "[gizlendi]");
        }
        return text;
    }
}
