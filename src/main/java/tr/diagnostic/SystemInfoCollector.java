package tr.diagnostic;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class SystemInfoCollector {
    private final CollectorConfiguration config;
    private final PowerShell powershell;
    public SystemInfoCollector(CollectorConfiguration config, CommandExecutor executor) { this.config = config; this.powershell = new PowerShell(executor); }
    public void collect(List<JavaProcessInfo> processes, Path staging, List<String> errors) throws IOException, InterruptedException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collectorJavaVersion", System.getProperty("java.version"));
        data.put("collectorJavaVendor", System.getProperty("java.vendor"));
        data.put("collectorJavaHome", System.getProperty("java.home"));
        data.put("osName", System.getProperty("os.name")); data.put("osArch", System.getProperty("os.arch"));
        data.put("sampledAt", Instant.now().toString());
        StringBuilder pids = new StringBuilder("$targetPids=@(");
        for (JavaProcessInfo p : processes) pids.append(p.pid).append(',');
        if (!processes.isEmpty()) pids.setLength(pids.length() - 1);
        pids.append("); ");
        JsonObject windows = null;
        CommandResult r = powershell.run(pids + PowerShell.resource("system-info.ps1"), config.scanTimeoutSeconds * 1000L);
        if (r.success() && !r.outputTruncated) {
            try {
                windows = JsonParser.parseString(r.stdout.trim()).getAsJsonObject(); data.put("windows", windows);
                for (JsonElement e : windows.getAsJsonArray("errors")) errors.add("SystemInfo: " + e.getAsString());
            } catch (RuntimeException e) { errors.add("SystemInfo JSON: " + e); }
        } else errors.add("SystemInfo: " + (r.outputTruncated ? "output truncated" : r.error()));
        writeWindows(windows, staging, data);
    }
    static void writeWindows(JsonObject windows, Path staging, Map<String, Object> data) throws IOException {
        if (windows != null) data.put("windows", windows);
        ServerResourceUsage usage = ServerResourceUsage.fromWindows(windows);
        data.put("serverUsage", usage);
        DiagnosticReportGenerator.writeJson(staging.resolve("system-info.json"), data);
        Files.write(staging.resolve("sunucu-kaynak-kullanimi.txt"), usage.asText().getBytes(StandardCharsets.UTF_8));
        StringBuilder disks = new StringBuilder("DİSK ALANI\nSürücü | Toplam GiB | Boş GiB\n");
        for (JsonElement d : ResourceMonitor.array(windows, "disks")) {
            JsonObject disk = d.getAsJsonObject(); Double size = ResourceMonitor.number(disk, "Size"), free = ResourceMonitor.number(disk, "FreeSpace");
            disks.append(ResourceMonitor.text(disk, "DeviceID")).append(" | ").append(size == null ? "Bilinmiyor" : ResourceMonitor.format(size / 1024 / 1024 / 1024))
                .append(" | ").append(free == null ? "Bilinmiyor" : ResourceMonitor.format(free / 1024 / 1024 / 1024)).append('\n');
        }
        if (ResourceMonitor.array(windows, "disks").size() == 0) disks.append("Disk bilgisi alınamadı.\n");
        disks.append("Disk okuma/yazma hızları kaynak-izleme.csv dosyasındadır.\n");
        Files.write(staging.resolve("disk-bilgileri.txt"), disks.toString().getBytes(StandardCharsets.UTF_8));
    }
}
