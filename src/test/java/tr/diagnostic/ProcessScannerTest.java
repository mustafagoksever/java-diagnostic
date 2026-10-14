package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;

public class ProcessScannerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private CommandExecutor fake(String processes) {
        return (command, timeout, out, err) -> {
            CommandResult r = new CommandResult(); r.exitCode = 0;
            if (command.get(0).endsWith("powershell.exe")) r.stdout = "{processes:" + processes + ",errors:[],elevated:false,collectorIdentity:'test'}";
            else { Assert.assertTrue("No attach tools during discovery", command.get(0).endsWith("jps.exe")); r.stdout = ""; }
            return r;
        };
    }
    @Test public void listsOwnAndOtherCollectorsButExcludesBothFromTargets() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        long self = ProcessScanner.currentPid();
        ProcessScanner.ScanResult scan = new ProcessScanner(new CollectorConfiguration(), fake("[{pid:" + self + ",processName:'java.exe'},{pid:42,processName:'javaw.exe',collectorProcess:true},{pid:43,processName:'java.exe'}]")).scan();
        Assert.assertEquals(3, scan.processes.size());
        JavaProcessInfo own = scan.processes.stream().filter(p -> p.pid == self).findFirst().get();
        Assert.assertTrue(own.collectorProcess); Assert.assertNotNull(own.collectionExclusionReason);
        Assert.assertEquals(2, scan.processes.stream().filter(ProcessScanner::excludedFromCollection).count());
        Assert.assertFalse(scan.processes.stream().filter(p -> p.pid == 43).findFirst().get().collectorProcess);
    }
    @Test public void showsOwnRuntimeEvenWhenWindowsDiscoveryHasNoMatchingProcess() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        ProcessScanner.ScanResult scan = new ProcessScanner(new CollectorConfiguration(), fake("[]")).scan();
        Assert.assertEquals(1, scan.processes.size()); Assert.assertEquals(ProcessScanner.currentPid(), scan.processes.get(0).pid);
        Assert.assertTrue(scan.processes.get(0).collectorProcess);
    }
    @Test public void recognizesCollectorMainClassAndJarNamesWithoutMatchingUnrelatedApplications() {
        for (String name : Arrays.asList("tr.diagnostic.Main", "C:\\tools\\java-diagnostic-collector.jar", "/tools/java-diagnostic-collector-1.0.0.jar")) Assert.assertTrue(ProcessScanner.isCollectorMainClass(name));
        for (String name : Arrays.asList(null, "app.Main", "java-diagnostic-collector-helper.jar", "my-java-diagnostic-collector.jar")) Assert.assertFalse(ProcessScanner.isCollectorMainClass(name));
        JavaProcessInfo own = new JavaProcessInfo(); own.pid = ProcessScanner.currentPid();
        Assert.assertTrue("PID guard applies even without a classification flag", ProcessScanner.excludedFromCollection(own));
    }
    @Test public void explicitOwnPidCannotCauseAttachAndExclusionIsRecordedInManifest() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        CollectorConfiguration c = new CollectorConfiguration(); c.outputDirectory = temp.getRoot().toPath().resolve("reports"); c.resourceMonitoringSeconds = 0;
        CommandExecutor commands = (command, timeout, out, err) -> {
            CommandResult r = new CommandResult(); r.exitCode = 0;
            if (command.get(0).endsWith("jps.exe")) { r.stdout = ""; return r; }
            Assert.assertTrue("Collector must never receive jcmd, jstack or jstat", command.get(0).endsWith("powershell.exe"));
            String script = new String(Base64.getDecoder().decode(command.get(command.size() - 1)), java.nio.charset.StandardCharsets.UTF_16LE);
            r.stdout = script.contains("$additionalNames") ? "{processes:[],errors:[],elevated:false,collectorIdentity:'test'}" : "{errors:[],computer:[],operatingSystem:[],cpuTotal:[],disks:[]}";
            return r;
        };
        char[] password = ZipEncryptionService.generatePassword();
        try {
            CollectionOrchestrator.Result result = new CollectionOrchestrator(c, commands).collect(Collections.singleton(ProcessScanner.currentPid()), password, message -> { });
            Assert.assertEquals(0, result.manifest.jvms.size()); Assert.assertEquals(1, result.manifest.excludedJvms.size());
            Assert.assertEquals(ProcessScanner.currentPid(), result.manifest.excludedJvms.get(0).process.pid);
            Assert.assertNotNull(result.manifest.excludedJvms.get(0).reason); Assert.assertTrue(Files.isRegularFile(result.archive));
        } finally { Arrays.fill(password, '\0'); }
    }
}
