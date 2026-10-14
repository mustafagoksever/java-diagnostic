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
        this.config = config; this.executor = executor; this.scanner = scanner; this.tools = new ToolResolver(config);
        this.delay = delay;
    }
    public void collect(JvmReport report, Path staging, Consumer<String> progress) throws IOException, InterruptedException {
        Path dir = Files.createDirectories(staging.resolve("jvms").resolve(FileSupport.safeName(report.process.label()) + "-" + report.process.pid));
        Operation version = execute(report, staging, dir, "jcmd", "VM.version", 0, Arrays.asList("VM.version"));
        if (version.success) {
            report.process.javaVersion = ProcessCommandExecutor.readBounded(staging.resolve(version.artifact), 64 * 1024).trim();
            report.process.versionSource = "jcmd VM.version";
        }
        if (config.collectGcStats) execute(report, staging, dir, "jstat", "gc-statistics", 0, Arrays.asList("-gcutil", Long.toString(report.process.pid), "1000", "1"));
        for (int sample = 1; sample <= DUMP_COUNT; sample++) {
            if (sample > 1) delay.sleep(INTERVAL_MILLIS);
            progress.accept("PID " + report.process.pid + ": thread dump " + sample + "/" + DUMP_COUNT);
            Operation first = execute(report, staging, dir, "jcmd", "thread-dump", sample, Arrays.asList("Thread.print", "-l"));
            if (!first.success) {
                Operation fallback = execute(report, staging, dir, "jstack", "thread-dump", sample, Arrays.asList("-l", Long.toString(report.process.pid)));
                if (fallback.success) report.dumpCount++;
            } else report.dumpCount++;
        }
    }
    private Operation execute(JvmReport report, Path staging, Path dir, String tool, String type, int sample, List<String> arguments)
            throws IOException, InterruptedException {
        Operation op = new Operation(); op.type = type; op.sample = sample;
        report.operations.add(op);
        Optional<Path> exe = tools.find(tool, report.process);
        if (!exe.isPresent()) { op.error = "TOOL_NOT_FOUND: " + tool + "; jdkHomes veya JAVA_HOME ayarlayın."; op.completedAt = Instant.now().toString(); return op; }
        List<String> command = new ArrayList<>(); command.add(exe.get().toString());
        if (tool.equals("jcmd")) command.add(Long.toString(report.process.pid));
        command.addAll(arguments); op.command = command;
        String identityError;
        try { identityError = scanner.identityError(report.process); }
        catch (IOException e) { op.error = "IDENTITY_CHECK_FAILED: " + e; op.completedAt = Instant.now().toString(); return op; }
        if (identityError != null) { op.error = identityError; op.completedAt = Instant.now().toString(); return op; }
        String name = FileSupport.safeName(report.process.processName) + "-" + report.process.pid + "-" + STAMP.format(Instant.now()) + "-" + type + "-" + sample + "-" + tool;
        Path out = dir.resolve(name + ".txt"), err = dir.resolve(name + ".stderr.txt");
        op.artifact = DiagnosticReportGenerator.relative(staging, out);
        op.stderrArtifact = DiagnosticReportGenerator.relative(staging, err);
        try {
            CommandResult r = executor.run(command, config.commandTimeoutSeconds * 1000L, out, err);
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
