package tr.diagnostic;

import tr.diagnostic.DiagnosticReportGenerator.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

public final class ThreadDumpCollector {
    public static final int DUMP_COUNT = 3;
    public static final long INTERVAL_MILLIS = 5000;
    private final CommandExecutor executor;
    private final ToolResolver tools;
    private final ProcessScanner scanner;
    private final CollectorConfiguration config;
    interface Delay { void sleep(long millis) throws InterruptedException; }
    private final Delay delay;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    public ThreadDumpCollector(CollectorConfiguration config, CommandExecutor executor, ProcessScanner scanner) {
        this(config, executor, scanner, Thread::sleep);
    }
    ThreadDumpCollector(CollectorConfiguration config, CommandExecutor executor, ProcessScanner scanner, Delay delay) {
        this.config = config; this.executor = executor; this.scanner = scanner; this.tools = scanner.tools();
        this.delay = delay;
    }
    public void collect(JvmReport report, Path staging, Consumer<String> progress) throws IOException, InterruptedException {
        Path dir = Files.createDirectories(staging.resolve("jvms").resolve(FileSupport.safeName(report.process.label()) + "-" + report.process.pid));
        Operation version = tryTools(report, staging, dir, "VM.version", 0, Collections.singletonList("jcmd"));
        if (version != null && version.success) {
            report.process.javaVersion = ProcessCommandExecutor.readBounded(staging.resolve(version.artifact), 64 * 1024).trim();
            report.process.versionSource = "jcmd VM.version";
        }
        if (config.collectGcStats) tryTools(report, staging, dir, "gc-statistics", 0, Collections.singletonList("jstat"));
        for (int sample = 1; sample <= DUMP_COUNT; sample++) {
            if (sample > 1) delay.sleep(INTERVAL_MILLIS);
            progress.accept("PID " + report.process.pid + ": thread dump " + sample + "/" + DUMP_COUNT);
            Operation dump = tryTools(report, staging, dir, "thread-dump", sample, Arrays.asList("jcmd", "jstack"));
            if (dump != null && dump.success) report.dumpCount++;
        }
    }
    private Operation tryTools(JvmReport report, Path staging, Path dir, String type, int sample, List<String> toolNames) throws IOException, InterruptedException {
        List<ToolResolver.Choice> choices = tools.choices(toolNames, report.process);
        if ("thread-dump".equals(type) && report.selectedDumpTool != null) choices.sort(Comparator.comparingInt(c -> c.executable.toString().equals(report.selectedDumpTool) ? 0 : 1));
        if (choices.isEmpty()) {
            Operation missing = new Operation(); missing.type=type; missing.sample=sample; missing.error="NO_COMPATIBLE_TOOL: debug/jdk-discovery.json dosyasına bakın."; missing.completedAt=Instant.now().toString(); report.operations.add(missing); return missing;
        }
        long deadline = System.nanoTime() + config.commandTimeoutSeconds * ("thread-dump".equals(type) ? 2_000_000_000L : 1_000_000_000L);
        Operation last=null;
        for (ToolResolver.Choice choice : choices) {
            if (System.nanoTime() >= deadline) break;
            List<String> args;
            if ("VM.version".equals(type)) args=Arrays.asList("VM.version");
            else if ("gc-statistics".equals(type)) args=Arrays.asList("-gcutil", Long.toString(report.process.pid), "1000", "1");
            else args=choice.tool.equals("jcmd")?Arrays.asList("Thread.print","-l"):Arrays.asList("-l",Long.toString(report.process.pid));
            last=execute(report,staging,dir,choice,type,sample,args,deadline);
            if (last.success) { if ("thread-dump".equals(type)) report.selectedDumpTool=choice.executable.toString(); return last; }
            if (last.error!=null && last.error.matches("^(TARGET_EXITED|PID_REUSED|IDENTITY_UNVERIFIED|IDENTITY_CHECK_FAILED).*")) break;
        }
        return last;
    }
    private Operation execute(JvmReport report, Path staging, Path dir, ToolResolver.Choice choice, String type, int sample, List<String> arguments, long deadline)
            throws IOException, InterruptedException {
        Operation op = new Operation(); op.type = type; op.sample = sample;
        report.operations.add(op);
        String tool = choice.tool;
        op.toolHome=choice.home; op.toolArchitecture=choice.architecture; op.toolJavaVersion=choice.javaVersion; op.toolVendor=choice.vendor;
        List<String> command = new ArrayList<>(); command.add(choice.executable.toString());
        if (tool.equals("jcmd")) command.add(Long.toString(report.process.pid));
        command.addAll(arguments); op.command = command;
        String identityError;
        try {
            long left = (deadline-System.nanoTime())/1_000_000L;
            if (left<=0) { op.error="TOOL_BUDGET_EXHAUSTED"; op.completedAt=Instant.now().toString(); return op; }
            identityError = scanner.identityError(report.process, left);
        }
        catch (IOException e) { op.error = "IDENTITY_CHECK_FAILED: " + e; op.completedAt = Instant.now().toString(); return op; }
        if (identityError != null) { op.error = identityError; op.completedAt = Instant.now().toString(); return op; }
        String name = FileSupport.safeName(report.process.processName) + "-" + report.process.pid + "-" + STAMP.format(Instant.now()) + "-" + type + "-" + sample + "-" + tool + "-" + UUID.randomUUID().toString().substring(0,8);
        Path out = dir.resolve(name + ".txt"), err = dir.resolve(name + ".stderr.txt");
        op.artifact = DiagnosticReportGenerator.relative(staging, out);
        op.stderrArtifact = DiagnosticReportGenerator.relative(staging, err);
        try {
            long remaining = (deadline-System.nanoTime())/1_000_000L;
            if (remaining<=0) { op.error="TOOL_BUDGET_EXHAUSTED"; return op; }
            CommandResult r = executor.run(command, Math.min(config.commandTimeoutSeconds * 1000L, remaining), out, err);
            op.exitCode = r.exitCode; op.timedOut = r.timedOut; op.durationMillis = r.durationMillis;
            op.success = r.success(); op.error = r.error();
            if (type.equals("thread-dump") && op.success && !looksLikeDump(r.stdout)) {
                op.success = false; op.error = "INVALID_DUMP: araç başarı kodu döndürdü fakat thread dump içeriği yok.";
            }
            if (op.error != null && op.error.length() > 4096) op.error = op.error.substring(0, 4096) + " (full output in artifact)";
        } catch (IOException e) { op.error = e.toString(); }
        catch (InterruptedException e) { op.error = "INTERRUPTED: tanılama aracı kesildi."; throw e; }
        finally { op.completedAt = Instant.now().toString(); }
        return op;
    }
    static boolean looksLikeDump(String text) {
        return text.contains("Full thread dump") || text.contains("java.lang.Thread.State:") || text.contains("3XMTHREADINFO");
    }
}
