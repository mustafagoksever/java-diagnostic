package tr.diagnostic;

import tr.diagnostic.DiagnosticReportGenerator.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.*;
import com.google.gson.JsonObject;

public final class CollectionOrchestrator {
    public static final class Result {
        public Path archive;
        public Manifest manifest;
        public List<String> warnings = new ArrayList<>();
    }
    private final CollectorConfiguration config;
    private final CommandExecutor executor;
    public CollectionOrchestrator(CollectorConfiguration config) { this(config, new ProcessCommandExecutor()); }
    public CollectionOrchestrator(CollectorConfiguration config, CommandExecutor executor) { this.config = config; this.executor = executor; }
    public ProcessScanner.ScanResult list() throws IOException, InterruptedException { return new ProcessScanner(config, executor).scan(); }
    public Result collect(Set<Long> selectedPids, char[] password, Consumer<String> progress) throws IOException, InterruptedException {
        if (!PowerShell.isWindows()) throw new IOException("Collector Windows üzerinde çalışmalıdır.");
        if (password == null || password.length < 12) throw new IOException("ZIP parolası en az 12 karakter olmalı.");
        Files.createDirectories(config.outputDirectory);
        String id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path staging = Files.createTempDirectory(config.outputDirectory, ".diagnostic-staging-");
        IOException cleanupError = null;
        Result result = new Result(); Manifest m = new Manifest(); m.reportId = id; result.manifest = m;
        ExecutorService monitoring = Executors.newSingleThreadExecutor();
        Future<JsonObject> monitorResult = null;
        try {
            FileSupport.restrictDirectory(staging);
            if (config.collectionDelaySeconds > 0) {
                progress.accept("Toplama öncesi " + config.collectionDelaySeconds + " saniye bekleniyor...");
                Thread.sleep(config.collectionDelaySeconds * 1000L);
            }
            ProcessScanner scanner = new ProcessScanner(config, executor);
            progress.accept("Windows Java süreçleri keşfediliyor...");
            m.discovery = scanner.scan(); m.errors.addAll(m.discovery.errors);
            if (selectedPids != null) {
                for (Long pid : selectedPids) if (m.discovery.processes.stream().noneMatch(p -> p.pid == pid)) m.errors.add("TARGET_NOT_DISCOVERED: PID " + pid);
            }
            List<JavaProcessInfo> targets = new ArrayList<>();
            for (JavaProcessInfo p : m.discovery.processes) if (selectedPids == null || selectedPids.contains(p.pid)) {
                if (ProcessScanner.excludedFromCollection(p)) {
                    m.excludedJvms.add(new ExcludedJvm(p));
                    progress.accept("Collector PID " + p.pid + " listede gösterilir; dump/GC/kaynak hedeflerinden çıkarıldı.");
                } else targets.add(p);
            }
            if (targets.isEmpty()) m.errors.add("Toplanacak Java süreci bulunamadı.");
            ResourceMonitor monitor = new ResourceMonitor(config, executor);
            if (config.resourceMonitoringSeconds > 0) {
                progress.accept(config.resourceMonitoringSeconds + " saniyelik CPU/RAM takibi başlatıldı...");
                monitorResult = monitoring.submit(() -> monitor.sample(targets));
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            resolved.put("installationDirectory", config.installationDirectory == null ? null : config.installationDirectory.toString());
            resolved.put("applicationDirectory", config.applicationDirectory == null ? null : config.applicationDirectory.toString());
            List<Map<String, String>> sources = new ArrayList<>();
            for (CollectorConfiguration.LogDirectory spec : config.logs) {
                Map<String, String> source = new LinkedHashMap<>(); source.put("source", spec.path.toString()); source.put("destination", spec.destination); sources.add(source);
            }
            resolved.put("sources", sources); DiagnosticReportGenerator.writeJson(staging.resolve("resolved-config.json"), resolved);
            progress.accept("Windows sistem ve kaynak bilgileri toplanıyor...");
            try { new SystemInfoCollector(config, executor).collect(targets, staging, m.errors); }
            catch (IOException e) { m.errors.add("SystemInfo: " + e); }
            ThreadDumpCollector dumps = new ThreadDumpCollector(config, executor, scanner);
            for (JavaProcessInfo target : targets) {
                JvmReport jvm = new JvmReport(target); m.jvms.add(jvm);
                try { dumps.collect(jvm, staging, progress); }
                catch (IOException e) { m.errors.add("PID " + target.pid + ": " + e); }
            }
            progress.accept("Log dosyaları kopyalanıyor...");
            m.logs.addAll(new LogCollector().collect(config, staging));
            progress.accept("Thread dump ve log hata özetleri hazırlanıyor...");
            try { new ThreadDumpAnalyzer().write(m.jvms, staging); }
            catch (IOException e) { m.errors.add("ThreadDumpAnalyzer: " + e); }
            try { new LogErrorAnalyzer().write(staging); }
            catch (IOException e) { m.errors.add("LogErrorAnalyzer: " + e); }
            if (monitorResult != null) {
                progress.accept("CPU/RAM ölçümünün tamamlanması bekleniyor...");
                try { monitor.write(monitorResult.get(config.resourceMonitoringSeconds + config.scanTimeoutSeconds + 10L, TimeUnit.SECONDS), staging, m.errors); }
                catch (ExecutionException | TimeoutException | IOException e) { m.errors.add("ResourceMonitor: " + e); monitorResult.cancel(true); }
            }
            boolean incomplete = !m.discovery.windowsDiscoverySucceeded || targets.isEmpty() || !m.errors.isEmpty()
                || m.jvms.stream().anyMatch(j -> j.dumpCount < ThreadDumpCollector.DUMP_COUNT || j.operations.stream().anyMatch(o -> !o.success))
                || m.logs.stream().anyMatch(l -> l.status.equals("FAILED") || l.changedDuringCopy);
            m.collectionStatus = incomplete ? "PARTIAL" : "COMPLETE";
            m.completedAt = Instant.now().toString();
            new DiagnosticReportGenerator().write(m, staging);
            progress.accept("AES-256 ZIP oluşturuluyor ve bütün dosyalar doğrulanıyor...");
            result.archive = config.outputDirectory.resolve("diagnostic-" + id + ".zip");
            new ZipEncryptionService().createAndValidate(staging, result.archive, password);
        } finally {
            if (monitorResult != null && !monitorResult.isDone()) monitorResult.cancel(true);
            monitoring.shutdownNow();
            try {
                if (!monitoring.awaitTermination(5, TimeUnit.SECONDS)) progress.accept("Kaynak ölçümü yardımcı iş parçacığı henüz kapanmadı.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { FileSupport.deleteTree(staging); }
            catch (IOException e) { cleanupError = e; }
            if (cleanupError != null) {
                String warning = "Geçici rapor dizini silinemedi (yalnızca collector kullanıcısı erişebilir): " + staging + "; " + cleanupError;
                result.warnings.add(warning); progress.accept(warning);
            }
        }
        return result;
    }
}
