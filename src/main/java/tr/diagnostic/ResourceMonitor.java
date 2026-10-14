package tr.diagnostic;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A single bounded PowerShell session samples while dump/log collection runs. */
public final class ResourceMonitor {
    private final CollectorConfiguration config;
    private final PowerShell powershell;
    public ResourceMonitor(CollectorConfiguration config, CommandExecutor executor) { this.config = config; powershell = new PowerShell(executor); }
    public JsonObject sample(List<JavaProcessInfo> targets) throws IOException, InterruptedException {
        StringBuilder setup = new StringBuilder("$expectedStarts=@{");
        for (JavaProcessInfo p : targets) if (p.startTicks != null && p.startTicks.matches("[0-9]+")) setup.append("'").append(p.pid).append("'='").append(p.startTicks).append("';");
        setup.append("}; $monitorDurationSeconds=").append(config.resourceMonitoringSeconds)
            .append("; $sampleIntervalSeconds=").append(config.resourceSampleIntervalSeconds).append("; ");
        CommandResult r = powershell.run(setup + PowerShell.resource("resource-monitor.ps1"), (config.resourceMonitoringSeconds + config.scanTimeoutSeconds) * 1000L);
        if (!r.success() || r.outputTruncated) throw new IOException("Kaynak ölçümü: " + (r.outputTruncated ? "çıktı sınırı aşıldı" : r.error()));
        try { return JsonParser.parseString(r.stdout.trim()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Kaynak ölçümü JSON okunamadı.", e); }
    }
    public void write(JsonObject result, Path staging, List<String> errors) throws IOException {
        DiagnosticReportGenerator.writeJson(staging.resolve("kaynak-izleme.json"), result);
        for (JsonElement e : array(result, "errors")) errors.add("ResourceMonitor: " + e.getAsString());
        StringBuilder csv = new StringBuilder("timestamp_utc,elapsed_seconds,cpu_percent,used_ram_gib,free_ram_gib,ram_percent,disk_read_bytes_sec,disk_write_bytes_sec\n");
        Statistics cpu = new Statistics(), ram = new Statistics();
        Map<Long, ProcessStatistics> byPid = new TreeMap<>();
        Double logical = number(result, "logicalProcessors");
        JsonArray samples = array(result, "samples");
        for (JsonElement element : samples) {
            JsonObject sample = element.getAsJsonObject(); ServerResourceUsage usage = ServerResourceUsage.fromWindows(sample);
            cpu.add(usage.cpuUsagePercent); ram.add(usage.ramUsagePercent);
            JsonObject disk = first(sample, "diskIo");
            csv.append(text(sample, "sampledAtUtc")).append(',').append(format(number(sample, "elapsedSeconds"))).append(',')
                .append(format(usage.cpuUsagePercent)).append(',').append(format(usage.usedRamGiB)).append(',').append(format(usage.freeRamGiB)).append(',').append(format(usage.ramUsagePercent)).append(',')
                .append(format(number(disk, "DiskReadBytesPersec"))).append(',').append(format(number(disk, "DiskWriteBytesPersec"))).append('\n');
            for (JsonElement process : array(sample, "processes")) {
                JsonObject p = process.getAsJsonObject(); Double id = number(p, "IDProcess"); if (id == null || id <= 0) continue;
                ProcessStatistics stats = byPid.computeIfAbsent(id.longValue(), pid -> new ProcessStatistics());
                Double rawCpu = number(p, "PercentProcessorTime");
                Double normalizedCpu = logical != null && logical > 0 && rawCpu != null ? rawCpu / logical : null;
                stats.cpu.add(normalizedCpu != null && normalizedCpu <= 100 ? normalizedCpu : null);
                Double working = number(p, "WorkingSet"); stats.ram.add(working == null ? null : working / 1024 / 1024);
                stats.name = text(p, "Name"); stats.samples++;
            }
        }
        Files.write(staging.resolve("kaynak-izleme.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder summary = new StringBuilder("KAYNAK TAKİBİ ÖZETİ\nÖrnek sayısı: " + samples.size() + "\n");
        summary.append("İstenen süre: ").append(format(number(result, "requestedDurationSeconds"))).append(" saniye\n")
            .append("Gerçek ölçüm süresi: ").append(format(number(result, "actualDurationSeconds"))).append(" saniye\n")
            .append("CPU ortalama / en yüksek: ").append(cpu.describe("%")) .append("\nRAM ortalama / en yüksek: ").append(ram.describe("%"))
            .append("\nAralık hedefi: ").append(format(number(result, "intervalSeconds"))).append(" saniye; sorgu süreleri gerçek aralığı uzatabilir.\n\nJAVA SÜREÇLERİ\n")
            .append("PID | Süreç | Örnek | CPU ortalama / en yüksek (%) | Working set ortalama / en yüksek (MiB)\n");
        for (Map.Entry<Long, ProcessStatistics> entry : byPid.entrySet()) {
            ProcessStatistics p = entry.getValue(); summary.append(entry.getKey()).append(" | ").append(p.name).append(" | ").append(p.samples).append(" | ").append(p.cpu.describe("%")) .append(" | ").append(p.ram.describe("MiB")).append('\n');
        }
        summary.append("\nProcess CPU sunucunun mantıksal CPU sayısına bölünerek 0–100 ölçeğinde gösterilir.\n")
            .append("Örnekler başlangıç zamanı doğrulanabilen PID'ler içindir. Bitmiş/kimliği değişmiş süreçler atlanır.\n")
            .append("Boş CSV hücresi/Bilinmiyor eksik ölçümdür; sıfır varsayılmaz. Ortalamalar geçerli örneklerin aritmetik ortalamasıdır.\n");
        Files.write(staging.resolve("kaynak-izleme-ozeti.txt"), summary.toString().getBytes(StandardCharsets.UTF_8));
        if (samples.size() == 0 || cpu.count == 0 || ram.count == 0) errors.add("ResourceMonitor: sunucu CPU/RAM örnekleri eksik.");
    }
    static JsonArray array(JsonObject root, String key) { JsonElement e = root == null ? null : root.get(key); return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray(); }
    static JsonObject first(JsonObject root, String key) { JsonArray a = array(root, key); return a.size() > 0 && a.get(0).isJsonObject() ? a.get(0).getAsJsonObject() : null; }
    static Double number(JsonObject root, String key) {
        try { double v = root.get(key).getAsDouble(); return Double.isFinite(v) && v >= 0 ? v : null; } catch (RuntimeException e) { return null; }
    }
    static String text(JsonObject root, String key) { try { return root.get(key).getAsString().replace('\n', ' ').replace('\r', ' '); } catch (RuntimeException e) { return "Bilinmiyor"; } }
    static String format(Double value) { return value == null ? "" : String.format(Locale.ROOT, "%.3f", value); }
    private static final class ProcessStatistics { String name; int samples; Statistics cpu = new Statistics(), ram = new Statistics(); }
    private static final class Statistics {
        int count; double sum, max;
        void add(Double value) { if (value != null) { count++; sum += value; max = Math.max(max, value); } }
        String describe(String unit) { return count == 0 ? "Bilinmiyor" : String.format(Locale.forLanguageTag("tr-TR"), "%.2f / %.2f %s (%d geçerli örnek)", sum / count, max, unit, count); }
    }
}
