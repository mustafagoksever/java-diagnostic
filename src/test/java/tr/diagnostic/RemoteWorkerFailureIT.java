package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real Windows worker/child processes; no network or remote credentials. */
public class RemoteWorkerFailureIT {
    @Test public void missingRootIsRejectedBeforeDiscoveryWhenTheRequestBypassesTheClient() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); Path job=job();
        try {
            Path marker=job.resolve("discovery-started.txt");
            write(job.resolve("process-scan.ps1"),"[IO.File]::WriteAllText('"+marker.toString().replace("'","''")+"','unexpected')");
            CollectorConfiguration c=config(job);
            CollectorConfiguration.LogDirectory log=new CollectorConfiguration.LogDirectory();log.sourcePath="Log";log.base="application";c.logs.add(log);
            JsonObject request;
            try(RemoteConnection connection=connection()){request=new RemoteCollectionOrchestrator(c).settings("collect",null,connection);}
            request.addProperty("installationDirectory","");request.addProperty("applicationDirectory","auto");
            JsonObject result=execute(job,request);
            Assert.assertFalse("Discovery must not start for an invalid root",Files.exists(marker));
            Assert.assertTrue(result.get("discovery").isJsonNull());
            Assert.assertTrue(result.toString(),result.getAsJsonArray("errors").toString().contains("REMOTE_ROOT_REQUIRED"));
        } finally {if(Files.exists(job))FileSupport.deleteTree(job);}
    }
    @Test public void workerAcceptsOnlyCOrDTempJobRoots() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path local=Files.createTempDirectory("diagnostic-worker-root-");
        try {
            Path source=Files.write(local.resolve("worker.ps1"),PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Test-JobRoot'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$suffix='\\diagnostic-remote-11111111-1111-1111-1111-111111111111';[pscustomobject]@{c=(Test-JobRoot ('C:\\Temp'+$suffix));d=(Test-JobRoot ('D:\\Temp'+$suffix));other=(Test-JobRoot ('E:\\Temp'+$suffix));parent=(Test-JobRoot 'D:\\Temp');escape=(Test-JobRoot ('D:\\Temp\\..'+$suffix))}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();
            Assert.assertTrue(result.get("c").getAsBoolean());Assert.assertTrue(result.get("d").getAsBoolean());
            for(String flag:new String[]{"other","parent","escape"})Assert.assertFalse(result.toString(),result.get(flag).getAsBoolean());
        }finally{FileSupport.deleteTree(local);}
    }
    @Test public void cancellationStopsTheBlockedQueryAndRemovesItsFiles() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path job=job();Path capture=Files.createTempDirectory("diagnostic-active-cancel-");Process worker=null;
        try {
            Path childPid=job.resolve("query.pid");
            write(job.resolve("process-scan.ps1"),"[IO.File]::WriteAllText('"+childPid.toString().replace("'","''")+"',[string]$PID);Start-Sleep -Seconds 30;'{\"processes\":[],\"errors\":[]}'");
            JsonObject settings;try(RemoteConnection connection=connection()){settings=new RemoteCollectionOrchestrator(config(job)).settings("list",null,connection);}
            settings.addProperty("jobId",job.getFileName().toString().substring("diagnostic-remote-".length()));DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);
            worker=new ProcessBuilder(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("remote-worker.ps1").toString(),"-RequestFile",job.resolve("request.json").toString()).redirectOutput(capture.resolve("out.txt").toFile()).redirectError(capture.resolve("err.txt").toFile()).start();
            long deadline=System.nanoTime()+10_000_000_000L;while(!Files.isRegularFile(childPid)&&System.nanoTime()<deadline)Thread.sleep(50);
            Assert.assertTrue("Query started",Files.isRegularFile(childPid));long child=Long.parseLong(new String(Files.readAllBytes(childPid),StandardCharsets.UTF_8));
            Files.write(job.resolve("cancel.txt"),new byte[]{1});Assert.assertTrue("Worker responds to cancellation",worker.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(new String(Files.readAllBytes(capture.resolve("err.txt")),StandardCharsets.UTF_8),0,worker.exitValue());Assert.assertFalse(Files.exists(job));
            CommandResult probe=new ProcessCommandExecutor().run(Arrays.asList("tasklist.exe","/FI","PID eq "+child,"/NH"),10000,capture.resolve("probe.out"),capture.resolve("probe.err"));Assert.assertFalse("Own query stopped",probe.stdout.contains("powershell.exe"));
        }finally{if(worker!=null&&worker.isAlive()){worker.destroyForcibly();worker.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}if(Files.exists(job))FileSupport.deleteTree(job);FileSupport.deleteTree(capture);}
    }
    @Test public void missingClientHeartbeatRequestsCleanupWithoutUsingWallClock() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path temp=Files.createTempDirectory("diagnostic-heartbeat-");
        try {
            Path source=Files.write(temp.resolve("worker.ps1"),PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Deadline'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$jobRoot='"+temp.toString().replace("'","''")+"';$settings=[pscustomobject]@{clientHeartbeatRequired=$true;timeoutSeconds=600};$clock=[pscustomobject]@{Elapsed=[TimeSpan]::FromSeconds(91)};$lastHeartbeat=$null;$lastHeartbeatAt=0;$nextHeartbeatCheck=0;$cleanupRequested=$false;"
                +"$disconnected=$false;try{Deadline}catch{$disconnected=$_.Exception.Message -like 'CLIENT_DISCONNECTED*'};[pscustomobject]@{disconnected=$disconnected;cleanup=$cleanupRequested}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();Assert.assertTrue(result.get("disconnected").getAsBoolean());Assert.assertTrue(result.get("cleanup").getAsBoolean());
        }finally{FileSupport.deleteTree(temp);}
    }
    @Test public void cancellationRemovesOnlyOwnJobDirectory() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path job=job();Path capture=Files.createTempDirectory("diagnostic-cancel-output-");
        Path other=Paths.get("C:/Temp","diagnostic-remote-"+UUID.randomUUID());Files.createDirectories(other);Path sentinel=Files.write(other.resolve("keep.txt"),new byte[]{1});
        try {
            JsonObject settings;try(RemoteConnection connection=connection()){settings=new RemoteCollectionOrchestrator(config(job)).settings("list",null,connection);}
            settings.addProperty("jobId",job.getFileName().toString().substring("diagnostic-remote-".length()));DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);
            Files.write(job.resolve("cancel.txt"),new byte[]{1});
            CommandResult execution=new ProcessCommandExecutor().run(Arrays.asList(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("remote-worker.ps1").toString(),"-RequestFile",job.resolve("request.json").toString()),15000,capture.resolve("out.txt"),capture.resolve("err.txt"));
            Assert.assertTrue(execution.error(),execution.success());Assert.assertFalse("Cancelled job removed",Files.exists(job));Assert.assertTrue("Other job untouched",Files.isRegularFile(sentinel));
        }finally{if(Files.exists(job))FileSupport.deleteTree(job);FileSupport.deleteTree(other);FileSupport.deleteTree(capture);}
    }
    @Test public void diskReserveRejectsCopiesBeforeTheyFillTheVolume() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path temp=Files.createTempDirectory("diagnostic-space-check-");
        try {
            Path source=Files.write(temp.resolve("worker.ps1"),PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Ensure-Space'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$minimumFreeBytes=256MB;$workDisk=[pscustomobject]@{AvailableFreeSpace=300MB};$small=$true;try{Ensure-Space 1MB}catch{$small=$false};$large=$false;try{Ensure-Space 100MB}catch{$large=$_.Exception.Message -like 'LOW_DISK_SPACE*'};[pscustomobject]@{smallAllowed=$small;largeRejected=$large}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();
            Assert.assertTrue(result.get("smallAllowed").getAsBoolean());Assert.assertTrue(result.get("largeRejected").getAsBoolean());
        }finally{FileSupport.deleteTree(temp);}
    }
    @Test public void allSelectionProducesOnlyStringErrorsAndCanBePackaged() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path job = job();
        try {
            // Deterministic machine with no target JVMs, selectedPids null = all.
            write(job.resolve("process-scan.ps1"), "'{\"processes\":[],\"errors\":[],\"elevated\":false,\"collectorIdentity\":\"fixture\"}'");
            write(job.resolve("system-info.ps1"), "'{\"errors\":[]}'");
            CollectorConfiguration c = config(job);
            JsonObject workerSettings;
            try(RemoteConnection connection=connection()){workerSettings=new RemoteCollectionOrchestrator(c).settings("collect",null,connection);}
            // Avoid enriching the empty fixture with unrelated JVMs from jps.
            workerSettings.addProperty("jdkHome",job.resolve("missing-jdk").toString());
            write(job.resolve("jdk-discovery.ps1"),PowerShell.resource("jdk-discovery.ps1")+"\nfunction Get-JdkInventory {param($roots,$targets)[pscustomobject]@{candidates=@();plans=@();notes=@()}}\n");
            JsonObject result = execute(job, workerSettings);
            for (JsonElement error : result.getAsJsonArray("errors")) {
                Assert.assertTrue(result.toString(), error.isJsonPrimitive() && error.getAsJsonPrimitive().isString());
                Assert.assertFalse(error.getAsString(), error.getAsString().contains("TARGET_NOT_DISCOVERED"));
            }
            Path local = Files.createTempDirectory("diagnostic-all-result-");
            char[] password = ZipEncryptionService.generatePassword();
            try (RemoteConnection connection = connection()) {
                c.outputDirectory = local;
                RemoteTransport fake = (settings, staging, conn, progress) -> {
                    Files.copy(job.resolve("data/remote-system.json"), staging.resolve("remote-system.json"));
                    JsonObject event = new JsonObject(); event.add("result", result); return event;
                };
                CollectionOrchestrator.Result archive = new RemoteCollectionOrchestrator(c, fake).collect(null, password, connection, s -> {});
                Assert.assertTrue(Files.isRegularFile(archive.archive));
            } finally { Arrays.fill(password, '\0'); FileSupport.deleteTree(local); }
        } finally { FileSupport.deleteTree(job); }
    }
    @Test public void failedJcmdStartUsesNextJdkAndContinuesCopyingLogs() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path job = job();
        try (WindowsAttachIT.Fixture fixture = new WindowsAttachIT.Fixture(false)) {
            Path invalid = Files.createDirectories(job.resolve("invalid-jdk/bin"));
            Files.write(invalid.resolve("jcmd.exe"), "not an executable".getBytes(StandardCharsets.UTF_8));
            Files.copy(jdk().resolve("bin/java.exe"),invalid.resolve("java.exe"));
            Files.copy(jdk().resolve("release"),invalid.getParent().resolve("release"));
            // Metadata points to a broken target-associated JDK; the actual
            // fixture keeps running under its own valid JDK. No fake EXE runs.
            String scanner="$scan=(& {\n"+PowerShell.resource("process-scan.ps1")+"\n})|ConvertFrom-Json;foreach($p in $scan.processes){if($p.pid -eq "+fixture.pid+"){$p.executablePath='"+invalid.resolve("java.exe").toString().replace("'","''")+"'}};$scan|ConvertTo-Json -Depth 8 -Compress";
            write(job.resolve("process-scan.ps1"),scanner);
            Path source = Files.write(job.resolve("source.log"), "ERROR fixture".getBytes(StandardCharsets.UTF_8));
            CollectorConfiguration c = config(job);
            CollectorConfiguration.LogDirectory log = new CollectorConfiguration.LogDirectory();
            log.sourcePath=source.toString();log.base="installation";log.destination="fixture";log.modifiedWithinHours=0;c.logs.add(log);
            JsonObject settings;
            try (RemoteConnection connection = new RemoteConnection("localhost", "test\\user", invalid.getParent().toString(), "unused-secret".toCharArray())) {
                c.jdkHomes.add(jdk());settings=new RemoteCollectionOrchestrator(c).settings("collect", Collections.singleton(fixture.pid), connection);
            }
            JsonObject result = execute(job, settings);
            JsonObject jvm = result.getAsJsonArray("jvms").get(0).getAsJsonObject();
            Assert.assertEquals(result.toString(),3,jvm.get("dumpCount").getAsInt());
            long fallback = 0;
            for (JsonElement element : jvm.getAsJsonArray("operations")) {
                JsonObject op = element.getAsJsonObject();
                if ("thread-dump".equals(op.get("type").getAsString()) && op.get("success").getAsBoolean()) fallback++;
            }
            Assert.assertEquals(3,fallback);
            Assert.assertTrue(jvm.getAsJsonArray("operations").toString().contains("invalid-jdk"));
            Assert.assertFalse(jvm.get("selectedDumpTool").getAsString().contains("invalid-jdk"));
            Assert.assertTrue(Files.isRegularFile(job.resolve("data/logs/fixture/source.log")));
            Assert.assertTrue(fixture.process.isAlive());
            for (JsonElement error : result.getAsJsonArray("errors")) Assert.assertFalse(error.toString(),error.toString().contains("No process is associated"));
        } finally { FileSupport.deleteTree(job); }
    }
    @Test public void slowDiscoveryIsStoppedWithinScanBudgetAndChildExits() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path job = job();
        try {
            Path pidFile = job.resolve("query.pid");
            write(job.resolve("process-scan.ps1"), "[IO.File]::WriteAllText('"+pidFile.toString().replace("'","''")+"',[string]$PID);Start-Sleep -Seconds 30;'{\"processes\":[],\"errors\":[]}'");
            CollectorConfiguration c=config(job);c.scanTimeoutSeconds=2;
            long started=System.nanoTime();JsonObject result=execute(job,c,null,"list");
            Assert.assertTrue("Discovery is bounded",(System.nanoTime()-started)/1000000<12000);
            Assert.assertTrue(result.toString(),result.getAsJsonArray("errors").toString().contains("QUERY_TIMEOUT: discovery"));
            Assert.assertTrue("Child reached query",Files.isRegularFile(pidFile));
            long child=Long.parseLong(new String(Files.readAllBytes(pidFile),StandardCharsets.UTF_8));
            CommandResult probe=new ProcessCommandExecutor().run(Arrays.asList("tasklist.exe","/FI","PID eq "+child,"/NH"),10000,job.resolve("probe.out"),job.resolve("probe.err"));
            Assert.assertTrue(probe.error(),probe.success());Assert.assertFalse("Query child terminated",probe.stdout.contains("powershell.exe"));
        } finally { FileSupport.deleteTree(job); }
    }
    private static CollectorConfiguration config(Path job) {
        CollectorConfiguration c=new CollectorConfiguration();c.remoteWindowsDiagnostics=false;c.installationPath=job.toString();c.applicationPath=".";
        c.resourceMonitoringSeconds=0;c.collectionDelaySeconds=0;c.collectGcStats=false;c.remoteTimeoutSeconds=120;c.commandTimeoutSeconds=10;
        return c;
    }
    private static Path jdk() { Path home=Paths.get(System.getProperty("java.home"));return home.getFileName().toString().equalsIgnoreCase("jre")?home.getParent():home; }
    private static RemoteConnection connection() {return new RemoteConnection("localhost","test\\user",jdk().toString(),"unused-secret".toCharArray());}
    private static Path job() throws Exception {
        Path job=Paths.get("C:/Temp","diagnostic-remote-"+UUID.randomUUID());Files.createDirectories(job);FileSupport.restrictDirectory(job);
        for(String resource:Arrays.asList("remote-worker.ps1","process-scan.ps1","system-info.ps1","resource-monitor.ps1","jdk-discovery.ps1"))write(job.resolve(resource),PowerShell.resource(resource));
        return job;
    }
    private static void write(Path path,String text) throws Exception {
        byte[] body=text.getBytes(StandardCharsets.UTF_8),bom=new byte[body.length+3];bom[0]=(byte)0xef;bom[1]=(byte)0xbb;bom[2]=(byte)0xbf;System.arraycopy(body,0,bom,3,body.length);Files.write(path,bom);
    }
    private static JsonObject execute(Path job,CollectorConfiguration c,Set<Long> selected,String action)throws Exception {
        try(RemoteConnection connection=connection()){return execute(job,new RemoteCollectionOrchestrator(c).settings(action,selected,connection));}
    }
    private static JsonObject execute(Path job,JsonObject settings)throws Exception {
        settings.addProperty("jobId",job.getFileName().toString().substring("diagnostic-remote-".length()));DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);
        CommandResult execution=new ProcessCommandExecutor().run(Arrays.asList(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("remote-worker.ps1").toString(),"-RequestFile",job.resolve("request.json").toString()),150000,job.resolve("worker.out"),job.resolve("worker.err"));
        Assert.assertTrue(execution.error(),execution.success());Assert.assertTrue(execution.stderr,Files.isRegularFile(job.resolve("complete.json")));
        return JsonParser.parseString(new String(Files.readAllBytes(job.resolve("complete.json")),StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
