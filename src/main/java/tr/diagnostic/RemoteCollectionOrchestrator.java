package tr.diagnostic;

import com.google.gson.*;
import tr.diagnostic.DiagnosticReportGenerator.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

public final class RemoteCollectionOrchestrator {
    private final CollectorConfiguration config;
    private final RemoteTransport transport;
    public RemoteCollectionOrchestrator(CollectorConfiguration config) { this(config, new RemotePowerShellTransport()); }
    RemoteCollectionOrchestrator(CollectorConfiguration config, RemoteTransport transport) { this.config = config; this.transport = transport; }
    JsonObject settings(String action, Set<Long> selected, RemoteConnection connection) throws IOException {
        if ("collect".equals(action)) ConfigurationManager.validateRemoteSources(config);
        JsonObject data = new JsonObject();
        data.addProperty("action", action); data.addProperty("jdkHome", connection.jdkHome);
        data.addProperty("workShare", config.remoteWorkShare);
        data.addProperty("windowsDiagnostics", config.remoteWindowsDiagnostics);
        data.addProperty("beforeLogCopyBatch", config.remoteBeforeLogCopyBatch);
        data.addProperty("beforeLogCopyTimeoutSeconds", config.remoteBeforeLogCopyTimeoutSeconds);
        data.addProperty("beforeLogCopyDelaySeconds", config.remoteBeforeLogCopyDelaySeconds);
        data.addProperty("timeoutSeconds", action.equals("list") ? Math.max(60, config.scanTimeoutSeconds + config.commandTimeoutSeconds + 15) : config.remoteTimeoutSeconds);
        data.addProperty("commandTimeoutSeconds", config.commandTimeoutSeconds); data.addProperty("scanTimeoutSeconds", config.scanTimeoutSeconds);
        data.addProperty("collectionDelaySeconds", config.collectionDelaySeconds); data.addProperty("resourceMonitoringSeconds", config.resourceMonitoringSeconds);
        data.addProperty("resourceSampleIntervalSeconds", config.resourceSampleIntervalSeconds); data.addProperty("collectGcStats", config.collectGcStats);
        data.addProperty("installationDirectory", config.installationPath); data.addProperty("applicationDirectory", config.applicationPath);
        Gson gson = new Gson(); data.add("selectedPids", selected == null ? JsonNull.INSTANCE : gson.toJsonTree(selected));
        data.add("ping", gson.toJsonTree(config.ping)); data.add("vxworks", gson.toJsonTree(config.vxworks));
        data.add("additionalProcessNames", gson.toJsonTree(config.additionalProcessNames));
        JsonArray homes = new JsonArray(); for (Path home : config.jdkHomes) homes.add(home.toString()); data.add("jdkHomes", homes);
        JsonArray logs = new JsonArray();
        for (CollectorConfiguration.LogDirectory spec : config.logs) {
            JsonObject log = new JsonObject(); log.addProperty("path", spec.sourcePath == null ? spec.path.toString() : spec.sourcePath);
            log.addProperty("base", spec.base); log.addProperty("destination", spec.destination); log.addProperty("recursive", spec.recursive);
            log.add("extensions", gson.toJsonTree(spec.extensions)); log.addProperty("modifiedWithinHours", spec.modifiedWithinHours); log.addProperty("maxFileSizeBytes", spec.maxFileSizeBytes); logs.add(log);
        }
        data.add("logs", logs); return data;
    }
    public ProcessScanner.ScanResult list(RemoteConnection connection, Consumer<String> progress) throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory("diagnostic-remote-list-");
        try {
            FileSupport.restrictDirectory(temp);
            JsonObject event = transport.execute(settings("list", null, connection), temp, connection, progress);
            JsonObject result = event.getAsJsonObject("result");
            if (!result.has("discovery") || result.get("discovery").isJsonNull()) throw new IOException("Sunucuda Java keşfi tamamlanamadı: " + result.get("errors"));
            ProcessScanner.ScanResult scan = new Gson().fromJson(result.get("discovery"), ProcessScanner.ScanResult.class);
            for (JsonElement error : ResourceMonitor.array(result, "errors")) scan.errors.add(error.getAsString());
            for (JsonElement warning : ResourceMonitor.array(event, "warnings")) scan.errors.add(warning.getAsString());
            return scan;
        } finally { FileSupport.deleteTree(temp); }
    }
    public CollectionOrchestrator.Result collect(Set<Long> selected, char[] password, RemoteConnection connection, Consumer<String> progress) throws IOException, InterruptedException {
        return CollectionTiming.run(progress, () -> collectReport(selected, password, connection, progress));
    }
    private CollectionOrchestrator.Result collectReport(Set<Long> selected, char[] password, RemoteConnection connection, Consumer<String> progress) throws IOException, InterruptedException {
        if (password == null || password.length < 12) throw new IOException("ZIP parolası en az 12 karakter olmalı.");
        JsonObject request = settings("collect", selected, connection);
        Files.createDirectories(config.outputDirectory);
        Path staging = Files.createTempDirectory(config.outputDirectory, ".diagnostic-staging-");
        CollectionOrchestrator.Result result = new CollectionOrchestrator.Result();
        Manifest m = new Manifest(); result.manifest = m;
        m.mode = "remote"; m.targetHost = connection.host; m.transport = "WMI/DCOM + SMB; C:/Temp then D:/Temp job folders; existing server JDK; no installed service/agent";
        m.reportId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            progress.accept("[Hazırlık] Yerel rapor çalışma klasörü oluşturuldu; erişim izinleri ayarlanıyor.");
            FileSupport.restrictDirectory(staging);
            progress.accept("[Bağlantı] Uzak toplama başlatılıyor; WMI ve SMB erişim sonuçları sırayla günlüğe yazılacak.");
            JsonObject event = transport.execute(request, staging, connection, progress);
            JsonObject remote = event.getAsJsonObject("result"); Gson gson = new Gson();
            progress.accept("[Aktarım] Sunucu sonuçları bu bilgisayara alındı; rapor hazırlanıyor.");
            m.toolDiscovery = remote.has("toolDiscovery") && remote.get("toolDiscovery").isJsonObject() ? remote.getAsJsonObject("toolDiscovery") : null;
            m.ping = remote.has("ping") && remote.get("ping").isJsonObject() ? remote.getAsJsonObject("ping") : null;
            m.vxworks = remote.has("vxworks") && remote.get("vxworks").isJsonObject() ? remote.getAsJsonObject("vxworks") : null;
            if (config.ping.enabled && m.ping == null) m.errors.add("Sunucudan ping toplama sonucu alınamadı.");
            if (config.vxworks.enabled && m.vxworks == null) m.errors.add("VxWorks toplama sonucu alınamadı.");
            if (remote.has("discovery") && !remote.get("discovery").isJsonNull()) m.discovery = gson.fromJson(remote.get("discovery"), ProcessScanner.ScanResult.class);
            for (JsonElement jvm : ResourceMonitor.array(remote, "jvms")) m.jvms.add(gson.fromJson(jvm, JvmReport.class));
            for (JsonElement excluded : ResourceMonitor.array(remote, "excludedJvms")) m.excludedJvms.add(gson.fromJson(excluded, ExcludedJvm.class));
            for (JsonElement log : ResourceMonitor.array(remote, "logs")) m.logs.add(gson.fromJson(log, LogResult.class));
            for (JsonElement operation : ResourceMonitor.array(remote, "operations")) m.operations.add(gson.fromJson(operation, Operation.class));
            for (JsonElement error : ResourceMonitor.array(remote, "errors")) {
                if (error.isJsonPrimitive() && error.getAsJsonPrimitive().isString()) m.errors.add(error.getAsString());
                else m.errors.add("Sunucu sonuç verisinde geçersiz hata kaydı.");
            }
            for (JsonElement warning : ResourceMonitor.array(event, "warnings")) { result.warnings.add(warning.getAsString()); m.errors.add(warning.getAsString()); }
            Map<String, Object> context = new LinkedHashMap<>(); context.put("server", connection.host); context.put("mode", "remote");
            context.put("serverStartedAtUtc", remote.get("serverStartedAtUtc")); context.put("serverCompletedAtUtc", remote.get("serverCompletedAtUtc"));
            context.put("clientReceivedAtUtc", Instant.now().toString()); context.put("cleanupSucceeded", event.get("cleanupSucceeded")); context.put("hashesVerifiedDuringDownload", true);
            context.put("remoteDirectory", event.get("remoteDirectory"));
            m.timing.remote(remote, Instant.parse((String) context.get("clientReceivedAtUtc")));
            context.put("serverStartedAtLocal", m.timing.serverStartedAtLocal); context.put("serverCompletedAtLocal", m.timing.serverCompletedAtLocal);
            context.put("serverTimeZoneId", m.timing.serverTimeZoneId); context.put("serverElapsedMillis", m.timing.serverElapsedMillis);
            context.put("serverClockAheadMinSeconds", m.timing.serverClockAheadMinSeconds); context.put("serverClockAheadMaxSeconds", m.timing.serverClockAheadMaxSeconds);
            if (m.timing.clockMismatch()) { String warning = "Sunucu ve istemci kayıtları 5 dakikadan fazla saat farkı gösteriyor; ayrıntılar rapor-zamanlari.txt içinde. Log tarih filtresi sunucu saatini kullanır."; result.warnings.add(warning); progress.accept(warning); }
            DiagnosticReportGenerator.writeJson(staging.resolve("remote-context.json"), context);
            if (remote.has("resolved") && !remote.get("resolved").isJsonNull()) DiagnosticReportGenerator.writeJson(staging.resolve("resolved-config.json"), remote.get("resolved"));
            JsonObject windows = readJson(staging.resolve("remote-system.json"));
            if (windows == null) { m.errors.add("Sunucu sistem bilgisi alınamadı."); progress.accept("[Rapor] Sunucu sistem bilgisi ALINAMADI; eksik ölçümler raporda bilinmiyor olarak gösterilecek."); }
            Map<String, Object> systemContext = new LinkedHashMap<>(); systemContext.put("mode", "remote"); systemContext.put("targetHost", connection.host);
            systemContext.put("collectorJavaVersion", System.getProperty("java.version")); systemContext.put("collectorRunsOn", "client");
            SystemInfoCollector.writeWindows(windows, staging, systemContext);
            progress.accept("[Rapor] Sunucu CPU/RAM ve disk raporları yazıldı.");
            if (config.resourceMonitoringSeconds > 0) {
                JsonObject samples = readJson(staging.resolve("kaynak-izleme.json"));
                if (samples == null) { m.errors.add("Sunucu kaynak takibi alınamadı."); progress.accept("[Rapor] Sunucu kaynak takibi ALINAMADI; hata manifestte kaydedildi."); }
                else { new ResourceMonitor(config, new ProcessCommandExecutor()).write(samples, staging, m.errors); progress.accept("[Rapor] Kaynak izleme JSON verisinden CSV ve ölçüm özeti yazıldı."); }
            }
            progress.accept("Sunucu dump/log analizleri hazırlanıyor...");
            new RemoteDiagnosticsReportWriter().write(m, staging);
            progress.accept("[Rapor] Ping ve VxWorks sonuç özetleri, task/socket tabloları hazırlandı.");
            new ThreadDumpAnalyzer().write(m.jvms, staging);
            progress.accept("[Analiz] Thread dump analiz özeti tamamlandı.");
            new LogErrorAnalyzer().write(staging);
            progress.accept("[Analiz] Log hata eşleşmeleri tarandı; hata özeti tamamlandı.");
            boolean incomplete = m.discovery == null || !m.discovery.windowsDiscoverySucceeded || m.jvms.isEmpty() || !m.errors.isEmpty()
                || m.jvms.stream().anyMatch(j -> j.dumpCount < 3 || j.operations.stream().anyMatch(o -> !o.success))
                || m.logs.stream().anyMatch(l -> "FAILED".equals(l.status) || l.changedDuringCopy)
                || (m.ping != null && !RemoteDiagnosticsReportWriter.flag(m.ping,"success"))
                || (m.vxworks != null && !RemoteDiagnosticsReportWriter.flag(m.vxworks,"success"))
                || m.operations.stream().anyMatch(o -> !o.success);
            m.collectionStatus = incomplete ? "PARTIAL" : "COMPLETE"; m.completedAt = Instant.now().toString();
            new DiagnosticReportGenerator().write(m, staging);
            progress.accept("[Rapor] Manifest, süreç listesi ve dosya SHA-256 envanteri yazıldı. Durum: " + m.collectionStatus);
            result.archive = config.outputDirectory.resolve("diagnostic-remote-" + m.reportId + ".zip");
            progress.accept("Bu bilgisayarda AES-256 ZIP oluşturuluyor ve doğrulanıyor...");
            new ZipEncryptionService().createAndValidate(staging, result.archive, password);
            progress.accept("[ZIP] TAMAMLANDI: AES-256 ZIP oluşturuldu; bütün girdiler okunarak doğrulandı.");
            return result;
        } catch (IOException | InterruptedException | RuntimeException e) {
            progress.accept("[HATA] Uzak rapor işlemi tamamlanamadı: " + e.getMessage());
            throw e;
        } finally {
            progress.accept("[Temizlik] Yerel geçici rapor klasörü temizleniyor...");
            try { FileSupport.deleteTree(staging); progress.accept("[Temizlik] Yerel geçici rapor klasörü temizlendi."); }
            catch (IOException e) { result.warnings.add("Yerel geçici dizin temizlenemedi: " + staging); progress.accept(result.warnings.get(result.warnings.size() - 1)); }
        }
    }
    private static JsonObject readJson(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) return null;
        try (java.io.Reader reader = Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (RuntimeException e) { return null; }
    }
}
