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
    @Test public void includesApplicationFailuresWithoutFlaggingSuccessfulLicenseOrConnections() throws Exception {
        Path staging = temp.getRoot().toPath(), log = Files.createDirectories(staging.resolve("logs")).resolve("app.log");
        String failures = "Your LuciadMap license is not valid for this host, sorry\n"
            + "Invalid license\nLicense expired\nhaberlesme arizali Low Band Front End 2\n"
            + "HABERLEŞME ARIZALI Low Band Front End 3\nBağlantı kurulamadı\nKontrol İşlemci ile bağlantı koptu\n";
        String successes = "License is valid for this host\nDeployment copy licensed to Aselsan.\nBaglanti kuruldu\n";
        Files.write(log, (failures + successes).getBytes(StandardCharsets.UTF_8));
        String before = FileSupport.sha256(log);
        new LogErrorAnalyzer().write(staging);
        String summary = new String(Files.readAllBytes(staging.resolve("log-hata-ozeti.txt")), StandardCharsets.UTF_8);
        Assert.assertTrue(summary.contains("toplam eşleşme: 7"));
        Assert.assertTrue(summary.contains("not valid for this host")); Assert.assertTrue(summary.contains("HABERLEŞME ARIZALI"));
        Assert.assertFalse(summary.contains("License is valid")); Assert.assertFalse(summary.contains("Baglanti kuruldu"));
        Assert.assertEquals(before, FileSupport.sha256(log));
    }
    @Test public void repeatedCommunicationErrorsDoNotPushTheLicenseFailureOutOfTheSummary() throws Exception {
        Path staging = temp.getRoot().toPath(), log = Files.createDirectories(staging.resolve("logs")).resolve("app.log");
        StringBuilder content = new StringBuilder("Your LuciadMap license is not valid for this host\n");
        for (int i = 0; i < 40; i++) content.append("haberlesme arizali Low Band Front End 2\n");
        Files.write(log, content.toString().getBytes(StandardCharsets.UTF_8));
        new LogErrorAnalyzer().write(staging);
        String summary = new String(Files.readAllBytes(staging.resolve("log-hata-ozeti.txt")), StandardCharsets.UTF_8);
        Assert.assertTrue(summary.contains("satır 1: Your LuciadMap license is not valid"));
        Assert.assertTrue(summary.contains("satır 41: haberlesme arizali"));
        Assert.assertFalse(summary.contains("satır 2: haberlesme arizali"));
        Assert.assertEquals(20, java.util.Arrays.stream(summary.split("\n")).filter(s -> s.startsWith("  satır ")).count());
        Assert.assertTrue(summary.contains("toplam eşleşme: 41"));
    }
}
