package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

public class LogCollectorTest {
    @Test public void outputAboveApplicationDoesNotHideLogsOrCollectOwnReports() throws Exception {
        Path output=temp.getRoot().toPath();Path source=Files.createDirectories(output.resolve("ares2n/Log"));
        write(source.resolve("app.log"),"source content");
        write(output.resolve("diagnostic-20261013-123456-abcdef12.zip"),"old report");
        Path oldStaging=Files.createDirectory(output.resolve(".diagnostic-staging-old"));write(oldStaging.resolve("secret.log"),"old staging");
        Path staging=Files.createDirectory(output.resolve(".diagnostic-staging-current"));
        CollectorConfiguration c=new CollectorConfiguration();c.outputDirectory=output;
        CollectorConfiguration.LogDirectory spec=new CollectorConfiguration.LogDirectory();spec.path=output;spec.modifiedWithinHours=0;c.logs.add(spec);
        List<DiagnosticReportGenerator.LogResult> results=new LogCollector().collect(c,staging);
        Assert.assertEquals(1,results.stream().filter(r->r.status.equals("COPIED")).count());
        Assert.assertTrue(Files.isRegularFile(staging.resolve("logs/01/ares2n/Log/app.log")));
        // Also test the actual default layout: source is under the output root.
        c.logs.clear();spec.path=source;spec.destination="app";c.logs.add(spec);
        results=new LogCollector().collect(c,staging);Assert.assertEquals(1,results.size());
        Assert.assertTrue(Files.isRegularFile(staging.resolve("logs/app/app.log")));
    }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void appliesFiltersRecursionAndExcludesOwnOutputWithoutChangingSources() throws Exception {
        Path root = temp.getRoot().toPath(); Path source = Files.createDirectory(root.resolve("logs"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Path accepted = write(nested.resolve("app.LOG"), "active content");
        String before = FileSupport.sha256(accepted);
        write(source.resolve("not-a-log.xml"), "xml");
        Path old = write(source.resolve("old.log"), "old"); Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 3 * 3600 * 1000));
        Files.write(source.resolve("large.log"), new byte[20]);
        CollectorConfiguration c = new CollectorConfiguration(); c.outputDirectory = Files.createDirectory(source.resolve("reports"));
        write(c.outputDirectory.resolve("previous.log"), "exclude");
        Path staging = Files.createDirectory(c.outputDirectory.resolve("staging"));
        CollectorConfiguration.LogDirectory spec = new CollectorConfiguration.LogDirectory(); spec.path = source; spec.extensions.add(".log"); spec.modifiedWithinHours = 1; spec.maxFileSizeBytes = 16; c.logs.add(spec);
        List<DiagnosticReportGenerator.LogResult> results = new LogCollector().collect(c, staging);
        Assert.assertEquals(1, results.stream().filter(r -> r.status.equals("COPIED")).count());
        Assert.assertEquals(1, results.stream().filter(r -> r.status.equals("SKIPPED_SIZE")).count());
        Assert.assertEquals(before, FileSupport.sha256(accepted));
        Assert.assertEquals(before, FileSupport.sha256(staging.resolve("logs/01/nested/app.LOG")));
        Assert.assertFalse(Files.exists(staging.resolve("logs/01/reports")));
    }
    @Test public void nonRecursiveAndMissingDirectoryAreHandled() throws Exception {
        Path root = temp.getRoot().toPath(); Path source = Files.createDirectory(root.resolve("source"));
        write(source.resolve("app.log"), "top"); write(Files.createDirectory(source.resolve("nested")).resolve("nested.log"), "nested");
        CollectorConfiguration c = new CollectorConfiguration(); c.outputDirectory = Files.createDirectory(root.resolve("output"));
        Path staging = Files.createDirectory(c.outputDirectory.resolve("staging"));
        CollectorConfiguration.LogDirectory spec = new CollectorConfiguration.LogDirectory(); spec.path = source; spec.recursive = false; c.logs.add(spec);
        CollectorConfiguration.LogDirectory absent = new CollectorConfiguration.LogDirectory(); absent.path = root.resolve("missing"); c.logs.add(absent);
        List<DiagnosticReportGenerator.LogResult> results = new LogCollector().collect(c, staging);
        Assert.assertEquals(1, results.stream().filter(r -> r.status.equals("COPIED")).count()); Assert.assertEquals(1, results.stream().filter(r -> r.status.equals("FAILED")).count());
    }
    @Test public void snapshotDoesNotChaseGrowingFileAndHandlesTruncation() throws Exception {
        Path root = temp.getRoot().toPath(); Path source = write(root.resolve("active.log"), "1234567890");
        Assert.assertEquals(5, LogCollector.copySnapshot(source, root.resolve("snapshot.log"), 5));
        Assert.assertEquals("12345", new String(Files.readAllBytes(root.resolve("snapshot.log")), StandardCharsets.UTF_8));
        Assert.assertEquals(10, LogCollector.copySnapshot(source, root.resolve("truncated.log"), 100));
        Assert.assertEquals(10, Files.size(source));
    }
    private Path write(Path path, String content) throws Exception { return Files.write(path, content.getBytes(StandardCharsets.UTF_8)); }
    @Test public void copiesSingleConfigFileAndPreservesPreviousCopyOnDestinationCollision() throws Exception {
        Path root = temp.getRoot().toPath();
        Path a = write(Files.createDirectory(root.resolve("a")).resolve("settings.xml"), "first");
        Path b = write(Files.createDirectory(root.resolve("b")).resolve("settings.xml"), "second");
        Files.setLastModifiedTime(a, FileTime.fromMillis(1));
        CollectorConfiguration c = new CollectorConfiguration(); c.outputDirectory = root.resolve("output");
        Path staging = Files.createDirectories(c.outputDirectory.resolve("staging"));
        for (Path source : Arrays.asList(a, b)) {
            CollectorConfiguration.LogDirectory spec = new CollectorConfiguration.LogDirectory();
            spec.path = source; spec.destination = "KA/conf"; spec.modifiedWithinHours = 0; c.logs.add(spec);
        }
        List<DiagnosticReportGenerator.LogResult> results = new LogCollector().collect(c, staging);
        Assert.assertEquals("COPIED", results.get(0).status); Assert.assertEquals("FAILED", results.get(1).status);
        Assert.assertTrue(results.get(1).error.contains("DESTINATION_COLLISION"));
        Assert.assertEquals("first", new String(Files.readAllBytes(staging.resolve("logs/KA/conf/settings.xml")), StandardCharsets.UTF_8));
        Assert.assertEquals("second", new String(Files.readAllBytes(b), StandardCharsets.UTF_8));
        Assert.assertEquals(1, Files.getLastModifiedTime(a).toMillis());
    }
}
