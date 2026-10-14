package tr.diagnostic;

import com.google.gson.*;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

public final class ProcessScanner {
    public static final class ScanResult {
        public List<JavaProcessInfo> processes = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public boolean elevated;
        public String collectorIdentity;
        public boolean windowsDiscoverySucceeded;
    }
    private final CollectorConfiguration config;
    private final CommandExecutor executor;
    private final PowerShell powershell;
    private final ToolResolver tools;
    public ProcessScanner(CollectorConfiguration config, CommandExecutor executor) {
        this.config = config; this.executor = executor;
        this.powershell = new PowerShell(executor); this.tools = new ToolResolver(config);
    }
    public ScanResult scan() throws IOException, InterruptedException {
        ScanResult result = new ScanResult();
        StringBuilder names = new StringBuilder("$additionalNames=@(");
        for (String name : config.additionalProcessNames) names.append("'").append(name.replace("'", "''")).append("',");
        if (!config.additionalProcessNames.isEmpty()) names.setLength(names.length() - 1);
        names.append("); ");
        CommandResult windows = powershell.run(names + PowerShell.resource("process-scan.ps1"), config.scanTimeoutSeconds * 1000L);
        if (windows.success() && !windows.outputTruncated) {
            try {
                JsonObject root = JsonParser.parseString(windows.stdout.trim()).getAsJsonObject();
                Gson gson = new Gson();
                for (JsonElement p : root.getAsJsonArray("processes")) result.processes.add(gson.fromJson(p, JavaProcessInfo.class));
                for (JsonElement e : root.getAsJsonArray("errors")) result.errors.add(e.getAsString());
                result.elevated = root.get("elevated").getAsBoolean();
                result.collectorIdentity = root.get("collectorIdentity").getAsString();
                result.windowsDiscoverySucceeded = true;
            } catch (RuntimeException e) { result.errors.add("Windows discovery JSON: " + e); }
        } else result.errors.add("Windows discovery: " + (windows.outputTruncated ? "output truncated" : windows.error()));
        enrichFromJps(result);
        long self = currentPid();
        result.processes.removeIf(p -> "sun.tools.jps.Jps".equals(p.mainClass) || "jdk.jcmd/sun.tools.jps.Jps".equals(p.mainClass));
        if (result.processes.stream().noneMatch(p -> p.pid == self)) {
            JavaProcessInfo own = new JavaProcessInfo(); own.pid = self; own.processName = "Collector";
            own.discoverySource = "collector-runtime (Windows metadata unavailable)";
            result.processes.add(own);
        }
        for (JavaProcessInfo p : result.processes) {
            if (p.pid == self) {
                p.collectorProcess = true; p.mainClass = Main.class.getName(); p.javaVersion = System.getProperty("java.version");
                p.versionSource = "collector-runtime";
                p.collectionExclusionReason = "Bu Collector JVM'si; rapor hedefinden hariç tutuldu.";
            } else if (p.collectorProcess || isCollectorMainClass(p.mainClass)) {
                p.collectorProcess = true;
                p.collectionExclusionReason = "Başka Collector JVM'si; rapor hedefinden hariç tutuldu.";
            }
        }
        result.processes.sort(Comparator.comparingLong(p -> p.pid));
        return result;
    }
    static boolean isCollectorMainClass(String value) {
        if (value == null) return false;
        if (Main.class.getName().equals(value)) return true;
        String file = value.replace('\\', '/'); file = file.substring(file.lastIndexOf('/') + 1);
        return file.matches("(?i)java-diagnostic-collector(?:-[0-9][0-9A-Za-z.-]*)?\\.jar");
    }
    static boolean excludedFromCollection(JavaProcessInfo process) {
        return process.pid == currentPid() || process.collectorProcess;
    }
    private void enrichFromJps(ScanResult result) throws IOException, InterruptedException {
        Optional<Path> jps = tools.find("jps", null);
        if (!jps.isPresent()) { result.errors.add("jps bulunamadı; Windows keşfi kullanılabilir."); return; }
        Path out = Files.createTempFile("diagnostic-jps-", ".out"), err = Files.createTempFile("diagnostic-jps-", ".err");
        try {
            CommandResult r = executor.run(Arrays.asList(jps.get().toString(), "-l"), config.commandTimeoutSeconds * 1000L, out, err);
            if (!r.success()) { result.errors.add("jps: " + r.error()); return; }
            for (String line : r.stdout.split("\\R")) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) continue;
                try {
                    long pid = Long.parseLong(parts[0]);
                    JavaProcessInfo p = result.processes.stream().filter(x -> x.pid == pid).findFirst().orElse(null);
                    if (p == null) { p = new JavaProcessInfo(); p.pid = pid; p.processName = "java"; p.discoverySource = "jps-only (identity unverified)"; result.processes.add(p); }
                    p.mainClass = parts[1];
                } catch (NumberFormatException ignored) { /* non-PID line */ }
            }
        } finally { Files.deleteIfExists(out); Files.deleteIfExists(err); }
    }
    /** Fail closed when creation time cannot be confirmed (PID reuse). */
    public String identityError(JavaProcessInfo process) throws IOException, InterruptedException {
        if (process.pid <= 0 || process.startTicks == null) return "IDENTITY_UNVERIFIED: başlangıç zamanı yok; attach yapılmadı.";
        String script = "try { $p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + process.pid + "'; if(!$p){ throw 'TARGET_EXITED' }; $date=$p.CreationDate } "
            + "catch { $p=Get-WmiObject Win32_Process -Filter 'ProcessId=" + process.pid + "'; if(!$p){ throw 'TARGET_EXITED' }; $date=$p.ConvertToDateTime($p.CreationDate) }; "
            + "$date.ToUniversalTime().Ticks.ToString()";
        CommandResult r = powershell.run(script, config.commandTimeoutSeconds * 1000L);
        if (!r.success()) return "IDENTITY_CHECK_FAILED: " + r.error();
        return process.startTicks.equals(r.stdout.trim()) ? null : "PID_REUSED: başlangıç zamanı değişti; attach yapılmadı.";
    }
    public static long currentPid() {
        return Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
    }
}
