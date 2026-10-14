package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import tr.diagnostic.DiagnosticReportGenerator.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real Windows test. Optional external PID tests never stop/restart that target. */
public class WindowsAttachIT {
    private Path root;
    private CollectorConfiguration config;
    @Before public void setup() throws Exception {
        Assume.assumeTrue("Windows required", PowerShell.isWindows());
        root = Files.createTempDirectory("diagnostic-windows-it-");
        config = new CollectorConfiguration(); config.outputDirectory = root.resolve("reports");
        config.commandTimeoutSeconds = 10;
        config.resourceMonitoringSeconds = 3;
        Path javaHome = Paths.get(System.getProperty("java.home"));
        config.jdkHomes.add(javaHome.getFileName().toString().equalsIgnoreCase("jre") ? javaHome.getParent() : javaHome);
        Assert.assertTrue("JDK jcmd required", new ToolResolver(config).find("jcmd", null).isPresent());
        Assert.assertTrue("JDK jstack required", new ToolResolver(config).find("jstack", null).isPresent());
    }
    @After public void cleanup() throws Exception { if (root != null) FileSupport.deleteTree(root); }

    @Test public void discoverAndCollectThreeDumpsFromRealJvm() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            ProcessScanner scanner = new ProcessScanner(config, new ProcessCommandExecutor());
            ProcessScanner.ScanResult scan = scanner.scan();
            Assert.assertTrue(scan.windowsDiscoverySucceeded);
            JavaProcessInfo p = findFixture(scan, false);
            Assert.assertNotNull(p.owner); Assert.assertNotNull(p.startTime); Assert.assertNotNull(p.sessionId);
            JvmReport report = new JvmReport(p);
            new ThreadDumpCollector(config, new ProcessCommandExecutor(), scanner).collect(report, root, s -> { });
            Assert.assertEquals(3, report.dumpCount);
            List<Operation> dumps = new ArrayList<>();
            for (Operation o : report.operations) if (o.type.equals("thread-dump") && o.success) dumps.add(o);
            for (int i = 1; i < dumps.size(); i++) Assert.assertTrue("At least five seconds between dump samples", Instant.parse(dumps.get(i).startedAt).toEpochMilli() - Instant.parse(dumps.get(i - 1).completedAt).toEpochMilli() >= 4900);
            Assert.assertTrue(fixture.process.isAlive());
            writeEvidence("same-user", p, report);
        }
    }
    @Test public void disabledAttachRecordsJcmdAndJstackFailures() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            ProcessScanner scanner = new ProcessScanner(config, new ProcessCommandExecutor());
            JavaProcessInfo p = findFixture(scanner.scan(), true);
            JvmReport report = new JvmReport(p);
            new ThreadDumpCollector(config, new ProcessCommandExecutor(), scanner).collect(report, root, s -> { });
            Assert.assertEquals(0, report.dumpCount);
            Assert.assertEquals(6, report.operations.stream().filter(o -> o.type.equals("thread-dump")).count());
            for (Operation o : report.operations) if (!o.type.equals("gc-statistics")) { Assert.assertFalse(o.success); Assert.assertNotNull(o.error); }
            Assert.assertTrue("Disabled attach target is still alive", fixture.process.isAlive());
            writeEvidence("attach-disabled", p, report);
        }
    }
    @Test public void encryptedReportEndToEnd() throws Exception {
        try (Fixture otherCollector = new Fixture(false, true); Fixture fixture = new Fixture(false)) {
            ProcessScanner.ScanResult scan = new ProcessScanner(config, new ProcessCommandExecutor()).scan();
            JavaProcessInfo p = findFixture(scan, false);
            Assert.assertTrue("Second Collector detected from its JVM launch marker", scan.processes.stream().filter(x -> x.pid == otherCollector.pid).findFirst().get().collectorProcess);
            Assert.assertTrue("Own JVM remains visible in the list", scan.processes.stream().filter(x -> x.pid == ProcessScanner.currentPid()).findFirst().get().collectorProcess);
            Path logs = Files.createDirectories(root.resolve("source-logs"));
            Files.write(logs.resolve("active.log"), "fixture log".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CollectorConfiguration.LogDirectory spec = new CollectorConfiguration.LogDirectory(); spec.path = logs; spec.extensions.add(".log"); config.logs.add(spec);
            char[] password = ZipEncryptionService.generatePassword();
            try {
                CollectionOrchestrator.Result result = new CollectionOrchestrator(config).collect(new LinkedHashSet<>(Arrays.asList(p.pid, otherCollector.pid, ProcessScanner.currentPid())), password, s -> { });
                Assert.assertTrue(Files.isRegularFile(result.archive)); Assert.assertEquals(3, result.manifest.jvms.get(0).dumpCount);
                Assert.assertEquals("Only the application JVM receives diagnostic commands", 1, result.manifest.jvms.size());
                Assert.assertEquals("Own and second Collector recorded as excluded", 2, result.manifest.excludedJvms.size());
                try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(result.archive.toFile(), password)) {
                    Assert.assertNotNull(zip.getFileHeader("Manifest.json")); Assert.assertNotNull(zip.getFileHeader("system-info.json"));
                    Assert.assertNotNull(zip.getFileHeader("sunucu-kaynak-kullanimi.txt"));
                    for (String artifact : Arrays.asList("kaynak-izleme.json", "kaynak-izleme.csv", "kaynak-izleme-ozeti.txt", "analiz-ozeti.txt", "thread-dump-analysis.json", "log-hata-ozeti.txt", "disk-bilgileri.txt", "resolved-config.json")) Assert.assertNotNull(artifact, zip.getFileHeader(artifact));
                    try (InputStream input = zip.getInputStream(zip.getFileHeader("kaynak-izleme.json"))) {
                        JsonObject monitoring = JsonParser.parseReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        boolean targetMeasured = false;
                        for (JsonElement sample : monitoring.getAsJsonArray("samples"))
                            for (JsonElement measured : sample.getAsJsonObject().getAsJsonArray("processes")) {
                                Assert.assertNotEquals("Other Collector excluded from per-PID monitoring", otherCollector.pid, measured.getAsJsonObject().get("IDProcess").getAsLong());
                                Assert.assertNotEquals("Own Collector excluded from per-PID monitoring", ProcessScanner.currentPid(), measured.getAsJsonObject().get("IDProcess").getAsLong());
                                if (measured.getAsJsonObject().get("IDProcess").getAsLong() == p.pid) targetMeasured = true;
                            }
                        Assert.assertTrue("Real target JVM CPU/RAM counters included", targetMeasured);
                        Path evidenceDir = Files.createDirectories(Paths.get("target/windows-evidence"));
                        DiagnosticReportGenerator.writeJson(evidenceDir.resolve("end-to-end-monitor.json"), monitoring);
                    }
                    try (InputStream input = zip.getInputStream(zip.getFileHeader("thread-dump-analysis.json"))) {
                        JsonArray analysis = JsonParser.parseReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
                        Assert.assertEquals(3, analysis.get(0).getAsJsonObject().get("parsedSamples").getAsInt());
                    }
                    try (InputStream input = zip.getInputStream(zip.getFileHeader("system-info.json"))) {
                        JsonObject system = JsonParser.parseReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        JsonObject usage = system.getAsJsonObject("serverUsage");
                        Assert.assertFalse("Real Windows total RAM", usage.get("totalRamGiB").isJsonNull());
                        Assert.assertFalse("Real Windows RAM usage", usage.get("ramUsagePercent").isJsonNull());
                        Assert.assertFalse("Real Windows total CPU", usage.get("cpuUsagePercent").isJsonNull());
                        double cpu = usage.get("cpuUsagePercent").getAsDouble(); double ram = usage.get("ramUsagePercent").getAsDouble();
                        Assert.assertTrue(cpu >= 0 && cpu <= 100); Assert.assertTrue(ram >= 0 && ram <= 100);
                    }
                    try (InputStream input = zip.getInputStream(zip.getFileHeader("sunucu-kaynak-kullanimi.txt"))) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                        String summary = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                        Assert.assertTrue(summary.contains("CPU kullanımı (sunucu toplamı):")); Assert.assertTrue(summary.contains("Kullanılan RAM:"));
                        Path evidenceDir = Files.createDirectories(Paths.get("target/windows-evidence"));
                        Files.write(evidenceDir.resolve("sunucu-kaynak-kullanimi.txt"), bytes.toByteArray());
                    }
                    Assert.assertNotNull(zip.getFileHeader("logs/01/active.log"));
                    try (InputStream input = zip.getInputStream(zip.getFileHeader("Manifest.json"))) {
                        JsonObject manifest = JsonParser.parseReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        Assert.assertEquals(3, manifest.getAsJsonArray("jvms").get(0).getAsJsonObject().get("dumpCount").getAsInt());
                        Assert.assertEquals(2, manifest.getAsJsonArray("excludedJvms").size());
                    }
                }
                try (java.util.stream.Stream<Path> paths = Files.list(config.outputDirectory)) {
                    Assert.assertEquals("Staging and partial archives removed", 1, paths.count());
                }
                Assert.assertTrue(fixture.process.isAlive());
                Assert.assertTrue(otherCollector.process.isAlive());
                Map<String, Object> evidence = new LinkedHashMap<>(); evidence.put("archiveVerified", true); evidence.put("dumpCount", 3); evidence.put("status", result.manifest.collectionStatus);
                evidence.put("excludedCollectors", result.manifest.excludedJvms);
                Files.createDirectories(Paths.get("target", "windows-evidence")); DiagnosticReportGenerator.writeJson(Paths.get("target", "windows-evidence", "end-to-end.json"), evidence);
            } finally { Arrays.fill(password, '\0'); }
        }
    }
    @Test public void externalUserOrServiceJvmProbeWhenConfigured() throws Exception {
        String pidValue = System.getProperty("diagnostic.externalPid");
        Assume.assumeTrue("Supply -Ddiagnostic.externalPid=<PID> from another Windows user/session", pidValue != null);
        long pid = Long.parseLong(pidValue);
        ProcessScanner scanner = new ProcessScanner(config, new ProcessCommandExecutor());
        ProcessScanner.ScanResult scan = scanner.scan();
        JavaProcessInfo p = scan.processes.stream().filter(x -> x.pid == pid).findFirst().orElseThrow(() -> new AssertionError("External PID not discovered"));
        String expectedOwner = System.getProperty("diagnostic.expectedOwner");
        if (expectedOwner != null) Assert.assertTrue("Expected external owner", p.owner != null && expectedOwner.equalsIgnoreCase(p.owner));
        Assert.assertNotNull("Windows identity required", p.startTicks);
        JvmReport report = new JvmReport(p);
        new ThreadDumpCollector(config, new ProcessCommandExecutor(), scanner).collect(report, root, s -> { });
        Assert.assertEquals(3, report.operations.stream().filter(o -> o.type.equals("thread-dump") && o.command != null && o.command.contains("Thread.print")).count());
        Assert.assertNull("External target still running with same identity", scanner.identityError(p));
        writeEvidence("external-pid-" + pid, p, report);
        Map<String, Object> context = new LinkedHashMap<>(); context.put("elevated", scan.elevated); context.put("collectorIdentity", scan.collectorIdentity); context.put("targetOwner", p.owner); context.put("targetSession", p.sessionId);
        DiagnosticReportGenerator.writeJson(Paths.get("target", "windows-evidence", "external-context.json"), context);
    }
    private JavaProcessInfo findFixture(ProcessScanner.ScanResult scan, boolean disabled) {
        // PID is printed by that exact JVM, not guessed by name.
        long pid = Fixture.currentPid;
        return scan.processes.stream().filter(p -> p.pid == pid).findFirst().orElseThrow(() -> new AssertionError("Fixture PID not discovered: " + pid + " errors=" + scan.errors));
    }
    private void writeEvidence(String name, JavaProcessInfo process, JvmReport report) throws Exception {
        Path dir = Files.createDirectories(Paths.get("target", "windows-evidence"));
        DiagnosticReportGenerator.writeJson(dir.resolve(name + ".json"), report);
    }
    static final class Fixture implements AutoCloseable {
        static long currentPid;
        final long pid;
        final Process process;
        Fixture(boolean disableAttach) throws Exception { this(disableAttach, false); }
        Fixture(boolean disableAttach, boolean collectorMarker) throws Exception {
            List<String> args = new ArrayList<>(); args.add(Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString());
            if (disableAttach) args.add("-XX:+DisableAttachMechanism");
            if (collectorMarker) args.add("-Ddiagnostic.collector=true");
            args.add("-cp"); args.add(Paths.get("target", "test-classes").toAbsolutePath().toString()); args.add("tr.diagnostic.fixtures.TestJvm");
            process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            java.util.concurrent.ExecutorService waiting = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                String line = waiting.submit(reader::readLine).get(10, TimeUnit.SECONDS);
                Assert.assertTrue("Fixture ready", line != null && line.startsWith("READY "));
                pid = Long.parseLong(line.substring(6)); currentPid = pid;
            } finally { waiting.shutdownNow(); }
        }
        @Override public void close() throws Exception {
            process.getOutputStream().close(); // fixture exits normally on EOF
            Assert.assertTrue("Fixture graceful exit", process.waitFor(10, TimeUnit.SECONDS));
        }
    }
}
