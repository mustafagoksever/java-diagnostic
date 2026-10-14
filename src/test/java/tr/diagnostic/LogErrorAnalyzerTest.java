package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class LogErrorAnalyzerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void identifiesErrorsWithLineNumbersWithoutChangingCopiedFiles() throws Exception {
        Path staging = temp.getRoot().toPath(); Path logs = Files.createDirectories(staging.resolve("logs/KA/log"));
        Path log = logs.resolve("app.log");
        Files.write(log, "INFO normal\nERROR failed\njava.lang.IllegalStateException: detail\nrequest timeout\n".getBytes(StandardCharsets.UTF_8));
        String hash = FileSupport.sha256(log);
        Files.write(logs.resolve("ignored.bin"), "ERROR binary".getBytes(StandardCharsets.UTF_8));
        new LogErrorAnalyzer().write(staging);
        String summary = new String(Files.readAllBytes(staging.resolve("log-hata-ozeti.txt")), StandardCharsets.UTF_8);
        Assert.assertTrue(summary.contains("satır 2: ERROR failed"));
        Assert.assertTrue(summary.contains("toplam eşleşme: 3")); Assert.assertFalse(summary.contains("ERROR binary"));
        Assert.assertEquals(hash, FileSupport.sha256(log));
    }
}
