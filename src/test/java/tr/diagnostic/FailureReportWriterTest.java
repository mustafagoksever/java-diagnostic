package tr.diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.*;

public class FailureReportWriterTest {
    private Path root;
    @Before public void create() throws Exception { root = Files.createTempDirectory("failure-report-test-"); }
    @After public void remove() throws Exception { FileSupport.deleteTree(root); }

    @Test public void preservesCauseAndSuppressedErrorsWhileRemovingCredentials() throws Exception {
        String user = "DOMAIN\\session-user", secret = "session-user\\\"secret";
        char[] archive = ZipEncryptionService.generatePassword();
        try {
            IOException error = new IOException("ZIP failed " + new String(archive), new IOException("WMI failed for " + user));
            error.addSuppressed(new IOException("helper password=" + secret + "; JSON=" + new com.google.gson.Gson().toJson(secret)));
            Path log = new FailureReportWriter(root.resolve("fallback")).write(root.resolve("output"), "Rapor oluşturma", error, user, secret, new String(archive));
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            Assert.assertTrue(text, text.contains("Rapor oluşturma") && text.contains("Zaman:") && text.contains("Caused by:") && text.contains("Suppressed:"));
            Assert.assertTrue(text, text.contains("FailureReportWriterTest.java"));
            Assert.assertFalse(text, text.contains(user) || text.contains(secret) || text.contains(new String(archive)) || text.contains(new com.google.gson.Gson().toJson(secret)));
            Assert.assertTrue(log.startsWith(root.resolve("output/collector-errors")));
        } finally { java.util.Arrays.fill(archive, '\0'); }
    }

    @Test public void blockedOutputUsesPersistentFallbackAndDoesNotOverwriteExistingLogs() throws Exception {
        Path blocked = root.resolve("blocked"); Files.write(blocked, new byte[]{1});
        FailureReportWriter writer = new FailureReportWriter(root.resolve("fallback"));
        Path first = writer.write(blocked, "Birinci işlem", new IOException("first"));
        Path second = writer.write(blocked, "İkinci işlem", new IOException("second"));
        Assert.assertNotEquals(first, second);
        Assert.assertTrue(first.startsWith(root.resolve("fallback")));
        Assert.assertTrue(new String(Files.readAllBytes(first), StandardCharsets.UTF_8).contains("first"));
        Assert.assertEquals(1, Files.readAllBytes(blocked)[0]);
    }

    @Test public void reportsFailureIfNeitherDirectoryCanBeWritten() throws Exception {
        Path blocked = root.resolve("blocked"); Files.write(blocked, new byte[]{1});
        try {
            new FailureReportWriter(blocked).write(blocked, "Rapor", new IOException("original"));
            Assert.fail("Both locations are files");
        } catch (IOException expected) { Assert.assertEquals(2, expected.getSuppressed().length); }
    }
}
