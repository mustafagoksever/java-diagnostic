package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ThreadDumpCollectorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void fallsBackToJstackRecordsFailureAndCountsOnlyValidDumps() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); // PowerShell transport boundary is mocked, no OS processes launched.
        Path root = temp.getRoot().toPath(); Path bin = Files.createDirectories(root.resolve("jdk/bin"));
        for (String tool : Arrays.asList("jcmd.exe", "jstack.exe")) Files.write(bin.resolve(tool), new byte[0]);
        CollectorConfiguration config = new CollectorConfiguration(); config.jdkHomes.add(bin.getParent()); config.collectGcStats = false;
        JavaProcessInfo p = new JavaProcessInfo(); p.pid = 42; p.processName = "java.exe"; p.startTicks = "123";
        List<Long> waits = new ArrayList<>();
        CommandExecutor fake = (command, timeout, out, err) -> {
            CommandResult r = new CommandResult(); r.command = command;
            if (command.get(0).endsWith("powershell.exe")) { r.exitCode = 0; r.stdout = "123"; }
            else if (command.get(0).endsWith("jcmd.exe")) { r.exitCode = 1; r.stderr = "AttachNotSupportedException: Access is denied"; Files.write(out, new byte[0]); Files.write(err, r.stderr.getBytes(StandardCharsets.UTF_8)); }
            else { r.exitCode = 0; r.stdout = "Full thread dump Java HotSpot\njava.lang.Thread.State: WAITING\n"; Files.write(out, r.stdout.getBytes(StandardCharsets.UTF_8)); Files.write(err, new byte[0]); }
            return r;
        };
        DiagnosticReportGenerator.JvmReport report = new DiagnosticReportGenerator.JvmReport(p);
        new ThreadDumpCollector(config, fake, new ProcessScanner(config, fake), waits::add).collect(report, root, message -> { });
        Assert.assertEquals(3, report.dumpCount); Assert.assertEquals(Arrays.asList(5000L, 5000L), waits);
        Assert.assertEquals(3, report.operations.stream().filter(o -> o.type.equals("thread-dump") && !o.success && o.error.contains("Access is denied")).count());
        Assert.assertEquals(3, report.operations.stream().filter(o -> o.type.equals("thread-dump") && o.success && o.command.get(1).equals("-l")).count());
    }
    @Test public void reusedPidNeverReceivesAnAttachCommand() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path root = temp.getRoot().toPath(); Path bin = Files.createDirectories(root.resolve("jdk/bin"));
        Files.write(bin.resolve("jcmd.exe"), new byte[0]); Files.write(bin.resolve("jstack.exe"), new byte[0]);
        CollectorConfiguration config = new CollectorConfiguration(); config.jdkHomes.add(bin.getParent()); config.collectGcStats = false;
        JavaProcessInfo p = new JavaProcessInfo(); p.pid = 42; p.processName = "java.exe"; p.startTicks = "123";
        CommandExecutor fake = (command, timeout, out, err) -> {
            Assert.assertTrue("Only identity checks permitted", command.get(0).endsWith("powershell.exe"));
            CommandResult r = new CommandResult(); r.exitCode = 0; r.stdout = "456"; return r;
        };
        DiagnosticReportGenerator.JvmReport report = new DiagnosticReportGenerator.JvmReport(p);
        new ThreadDumpCollector(config, fake, new ProcessScanner(config, fake), millis -> { }).collect(report, root, message -> { });
        Assert.assertEquals(0, report.dumpCount); Assert.assertTrue(report.operations.stream().allMatch(o -> o.error.startsWith("PID_REUSED")));
    }
}
