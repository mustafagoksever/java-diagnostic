package tr.diagnostic;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class DiagnosticReportGenerator {
    public static final class Manifest {
        public int schemaVersion = 1;
        public String reportId;
        public String startedAt = Instant.now().toString();
        public String completedAt;
        public ReportClock timing = new ReportClock(startedAt);
        public String collectionStatus;
        public String collectorJavaVersion = System.getProperty("java.version");
        public long collectorPid = ProcessScanner.currentPid();
        public String mode = "local";
        public String targetHost;
        public String transport;
        public com.google.gson.JsonObject ping;
        public com.google.gson.JsonObject vxworks;
        public com.google.gson.JsonObject toolDiscovery;
        public ProcessScanner.ScanResult discovery;
        public List<JvmReport> jvms = new ArrayList<>();
        public List<ExcludedJvm> excludedJvms = new ArrayList<>();
        public List<LogResult> logs = new ArrayList<>();
        public List<Operation> operations = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public List<FileRecord> files = new ArrayList<>();
        public String archivePolicy = "AES-256; every entry decrypted/authenticated and SHA-256 compared before ZIP publication; manifest excludes its own digest. CLI/GUI success confirms validation.";
        public String attachNote = "Administrator privileges do not guarantee attach. Raw tool errors are recorded; permissions, sessions, disabled attach, exited targets, tool/JVM incompatibility and vendor differences require operator review.";
    }
    public static final class ExcludedJvm {
        public JavaProcessInfo process;
        public String reason;
        public ExcludedJvm(JavaProcessInfo process) {
            this.process = process;
            this.reason = process.collectionExclusionReason == null ? "Collector JVM'si; rapor hedefinden hariç tutuldu." : process.collectionExclusionReason;
        }
    }
    public static final class JvmReport {
        public JavaProcessInfo process;
        public int requestedDumpCount = 3;
        public int dumpCount;
        public String selectedDumpTool;
        public List<Operation> operations = new ArrayList<>();
        public JvmReport(JavaProcessInfo process) { this.process = process; }
    }
    public static final class Operation {
        public String type;
        public String startedAt = Instant.now().toString();
        public String completedAt;
        public int sample;
        public List<String> command;
        public String toolHome, toolArchitecture, toolJavaVersion, toolVendor;
        public String workingDirectory;
        public String executionMode;
        public Integer sessionId;
        public String taskName;
        public Boolean taskCleanupSucceeded;
        public int postBatchWaitSeconds;
        public String batchCompletedAt;
        public boolean success;
        public boolean timedOut;
        public Integer exitCode;
        public long durationMillis;
        public String artifact;
        public String stderrArtifact;
        public String error;
    }
    public static final class LogResult {
        public String source;
        public String destination;
        public String status;
        public String error;
        public long bytesCopied;
        public boolean changedDuringCopy;
    }
    public static final class FileRecord {
        public String path;
        public long size;
        public String sha256;
    }
    public void write(Manifest manifest, Path staging) throws IOException {
        writeProcessIndex(manifest, staging.resolve("java-surecleri.txt"));
        manifest.timing.write(manifest, staging);
        manifest.files.clear();
        try (java.util.stream.Stream<Path> paths = Files.walk(staging)) {
            Iterator<Path> it = paths.filter(Files::isRegularFile).sorted().iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (p.getFileName().toString().equals("Manifest.json")) continue;
                FileRecord record = new FileRecord(); record.path = relative(staging, p);
                record.size = Files.size(p); record.sha256 = FileSupport.sha256(p);
                manifest.files.add(record);
            }
        }
        writeJson(staging.resolve("Manifest.json"), manifest);
    }
    public static void writeProcessIndex(Manifest manifest, Path destination) throws IOException {
        StringBuilder text = new StringBuilder("JAVA SÜREÇLERİ VE DUMP DOSYALARI\n");
        text.append("Uygulama adı JAR/ana sınıf/OSGi uygulama kimliğinden alınır. Alınamayan isim tahmin edilmez.\n")
            .append("PID yeniden başlatmada değişebilir; ad ve başlangıç zamanıyla birlikte değerlendirin.\n\n");
        Map<Long, JavaProcessInfo> processes = new TreeMap<>();
        if (manifest.discovery != null) for (JavaProcessInfo p : manifest.discovery.processes) processes.put(p.pid, p);
        for (JvmReport j : manifest.jvms) processes.put(j.process.pid, j.process);
        for (ExcludedJvm j : manifest.excludedJvms) processes.put(j.process.pid, j.process);
        text.append("PID | Uygulama adı | Başarılı dump\n");
        for (JavaProcessInfo p : processes.values()) {
            JvmReport report = manifest.jvms.stream().filter(j -> j.process.pid == p.pid).findFirst().orElse(null);
            text.append(p.pid).append(" | ").append(line(p.applicationLabel())).append(" | ")
                .append(p.collectorProcess ? "Collector — hariç" : report == null ? "Toplanmadı" : report.dumpCount + "/3").append('\n');
        }
        text.append("\nSÜREÇ AYRINTILARI\n\n");
        for (JavaProcessInfo p : processes.values()) {
            text.append("PID ").append(p.pid).append(" — ").append(line(p.applicationLabel())).append('\n')
                .append("  Süreç: ").append(line(p.processName)).append(" | Ana sınıf/JAR: ").append(line(p.mainClass)).append('\n')
                .append("  Sahip: ").append(line(p.owner)).append(" | Oturum: ").append(p.sessionId).append('\n')
                .append("  Başlangıç (UTC): ").append(line(p.startTime)).append('\n');
            text.append("  Java sürümü: ").append(line(p.javaVersion)).append(" | Mimari: ").append(line(p.architecture)).append('\n');
            JvmReport report = manifest.jvms.stream().filter(j -> j.process.pid == p.pid).findFirst().orElse(null);
            if (p.collectorProcess) text.append("  Collector; rapor hedefinden hariç.\n");
            else if (report == null) text.append("  Bu süreç için dump toplama kaydı yok.\n");
            else {
                text.append("  Başarılı dump: ").append(report.dumpCount).append("/3\n");
                text.append("  Seçilen dump aracı: ").append(line(report.selectedDumpTool)).append('\n');
                for (Operation op : report.operations) {
                    if (op.success && "thread-dump".equals(op.type)) text.append("  Dump ").append(op.sample).append(": ").append(line(op.artifact)).append('\n');
                    else if (!op.success) text.append("  Hata (").append(line(op.type)).append(", örnek ").append(op.sample).append("): ").append(line(op.error)).append('\n');
                }
            }
            text.append('\n');
        }
        Files.write(destination, text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String line(String value) { return value == null || value.isEmpty() ? "Bilinmiyor" : value.replace('\r',' ').replace('\n',' ').replace('\t',' '); }
    public static void writeJson(Path path, Object value) throws IOException {
        try (java.io.Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(value, writer);
        }
    }
    public static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }
}
