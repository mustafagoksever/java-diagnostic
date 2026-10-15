package tr.diagnostic;

import com.google.gson.JsonParser;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

public class ReportClockTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void connectionSetupDelayIsNotMisreportedAsClockSkew() {
        ReportClock clock = new ReportClock("2026-10-14T10:00:00Z");
        clock.remote(JsonParser.parseString("{serverStartedAtUtc:'2026-10-14T10:10:00Z'}").getAsJsonObject(), Instant.parse("2026-10-14T10:20:00Z"));
        Assert.assertEquals(Long.valueOf(-600), clock.serverClockAheadMinSeconds);
        Assert.assertEquals(Long.valueOf(600), clock.serverClockAheadMaxSeconds);
        Assert.assertFalse(clock.clockMismatch());
    }
    @Test public void clockMismatchIsDetectedInEitherDirection() {
        for (String server : new String[]{"2015-04-18T02:29:02Z", "2030-01-01T00:00:00Z"}) {
            ReportClock clock = new ReportClock("2026-10-14T10:00:00Z");
            clock.remote(JsonParser.parseString("{serverStartedAtUtc:'" + server + "'}").getAsJsonObject(), Instant.parse("2026-10-14T10:20:00Z"));
            Assert.assertTrue(clock.clockMismatch());
        }
    }
    @Test public void serverLocalClockIsPreservedAndReadableFileIsInManifestInventory() throws Exception {
        DiagnosticReportGenerator.Manifest m = new DiagnosticReportGenerator.Manifest(); m.mode = "remote"; m.targetHost = "test-server";
        m.timing.remote(JsonParser.parseString("{serverStartedAtUtc:'2015-04-18T02:29:02Z',serverStartedAtLocal:'2015-04-18T05:29:02+03:00',serverCompletedAtUtc:'2015-04-18T02:39:24Z',serverCompletedAtLocal:'2015-04-18T05:39:24+03:00',serverTimeZoneId:'Turkey Standard Time',serverElapsedMillis:622000}").getAsJsonObject(), Instant.now());
        m.completedAt = Instant.now().toString();
        Path staging = temp.getRoot().toPath(); new DiagnosticReportGenerator().write(m, staging);
        String text = new String(Files.readAllBytes(staging.resolve("rapor-zamanlari.txt")), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("2015-04-18T05:29:02+03:00")); Assert.assertTrue(text.contains("Turkey Standard Time"));
        Assert.assertTrue(text.contains("Collector JVM açılışı")); Assert.assertTrue(text.contains("ZIP öncesi"));
        Assert.assertTrue(m.timing.clientElapsedMillisBeforeZip >= 0);
        DiagnosticReportGenerator.FileRecord file = m.files.stream().filter(f -> f.path.equals("rapor-zamanlari.txt")).findFirst().get();
        Assert.assertEquals(FileSupport.sha256(staging.resolve(file.path)), file.sha256);
    }
    @Test public void unavailableServerTimeStaysUnknownInsteadOfUsingClientClock() throws Exception {
        DiagnosticReportGenerator.Manifest m = new DiagnosticReportGenerator.Manifest(); m.mode = "remote";
        m.timing.remote(JsonParser.parseString("{serverStartedAtUtc:{bad:true},serverElapsedMillis:{bad:true}}").getAsJsonObject(), Instant.now());
        Assert.assertNull(m.timing.serverStartedAtUtc); Assert.assertNull(m.timing.serverElapsedMillis); Assert.assertFalse(m.timing.clockMismatch());
        Path staging = temp.getRoot().toPath(); new DiagnosticReportGenerator().write(m, staging);
        String text = new String(Files.readAllBytes(staging.resolve("rapor-zamanlari.txt")), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("Toplama başlangıcı (sunucu UTC): Bilinmiyor"));
    }
}
