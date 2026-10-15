package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs the server worker on local Windows with a disposable JVM; no server access. */
public class RemoteWorkerIT {
    @Test public void workerCollectsDumpsLogsMetricsAndHashesWithoutInstalledCollector() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path job = Paths.get("C:/Temp", "diagnostic-remote-"+UUID.randomUUID());
        Path sourceRoot = Files.createTempDirectory("diagnostic-worker-source-");
        Files.createDirectories(job); FileSupport.restrictDirectory(job);
        try (WindowsAttachIT.Fixture fixture = new WindowsAttachIT.Fixture(false)) {
            for (String resource : Arrays.asList("remote-worker.ps1", "process-scan.ps1", "system-info.ps1", "resource-monitor.ps1", "jdk-discovery.ps1", "remote-windows.ps1")) {
                byte[] body = PowerShell.resource(resource).getBytes(StandardCharsets.UTF_8); byte[] bom = new byte[body.length+3]; bom[0]=(byte)0xef;bom[1]=(byte)0xbb;bom[2]=(byte)0xbf;System.arraycopy(body,0,bom,3,body.length); Files.write(job.resolve(resource),bom);
            }
            Path log = Files.write(sourceRoot.resolve("app.log"), "ERROR fixture\n".getBytes(StandardCharsets.UTF_8));
            String before = FileSupport.sha256(log);
            Path nested=Files.createDirectories(sourceRoot.resolve("sublogs/nested"));
            Files.write(nested.resolve("recent.LOG"),"small log".getBytes(StandardCharsets.UTF_8));
            Files.write(nested.resolve("ignored.xml"),"xml".getBytes(StandardCharsets.UTF_8));
            Files.write(nested.resolve("large.log"),new byte[32]);
            CollectorConfiguration c = new CollectorConfiguration(); c.installationPath = sourceRoot.toString(); c.applicationPath = "."; c.resourceMonitoringSeconds = 3; c.remoteTimeoutSeconds = 90; c.commandTimeoutSeconds=10;
            CollectorConfiguration.LogDirectory spec = new CollectorConfiguration.LogDirectory(); spec.sourcePath = "app.log"; spec.base="installation"; spec.destination="KA/log"; spec.modifiedWithinHours=0; c.logs.add(spec);
            CollectorConfiguration.LogDirectory recursive=new CollectorConfiguration.LogDirectory();recursive.sourcePath="sublogs";recursive.base="installation";recursive.destination="recursive";recursive.extensions.add(".log");recursive.modifiedWithinHours=0;recursive.maxFileSizeBytes=16;c.logs.add(recursive);
            Path runtime = Paths.get(System.getProperty("java.home")); Path jdk = runtime.getFileName().toString().equalsIgnoreCase("jre") ? runtime.getParent() : runtime;
            try (RemoteConnection connection = new RemoteConnection("localhost", "test\\user", "auto", "unused-transport-secret".toCharArray())) {
                JsonObject settings = new RemoteCollectionOrchestrator(c).settings("collect", Collections.singleton(fixture.pid), connection); settings.addProperty("jobId",job.getFileName().toString().substring("diagnostic-remote-".length()));
                DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);
            }
            CommandResult execution = new ProcessCommandExecutor().run(Arrays.asList(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("remote-worker.ps1").toString(),"-RequestFile",job.resolve("request.json").toString()),120000,sourceRoot.resolve("worker.out"),sourceRoot.resolve("worker.err"));
            Assert.assertTrue(execution.error(),execution.success()); Assert.assertTrue("Completion marker: "+execution.stderr,Files.isRegularFile(job.resolve("complete.json")));
            JsonObject identity=JsonParser.parseString(new String(Files.readAllBytes(job.resolve("worker-state.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            Assert.assertEquals(job.getFileName().toString().substring("diagnostic-remote-".length()),identity.get("jobId").getAsString());
            Assert.assertTrue(identity.get("pid").getAsLong()>0);Assert.assertTrue(identity.get("startTicks").getAsString().matches("[0-9]+"));
            JsonObject result; try (java.io.Reader reader=Files.newBufferedReader(job.resolve("complete.json"),StandardCharsets.UTF_8)) {result=JsonParser.parseReader(reader).getAsJsonObject();}
            for (JsonElement error : result.getAsJsonArray("errors")) {
                Assert.assertTrue("Unexpected worker error: "+error,
                    error.isJsonPrimitive() && error.getAsString().startsWith("Module inspection unavailable for "));
            }
            Assert.assertEquals(3,result.getAsJsonArray("jvms").get(0).getAsJsonObject().get("dumpCount").getAsInt());
            Assert.assertTrue(Files.isRegularFile(job.resolve("data/debug/jdk-discovery.json")));
            JsonObject selected=result.getAsJsonArray("jvms").get(0).getAsJsonObject();
            Assert.assertTrue(selected.get("selectedDumpTool").getAsString().endsWith("jcmd.exe"));
            Assert.assertEquals("x64",selected.getAsJsonObject("process").get("architecture").getAsString());
            Assert.assertTrue(result.getAsJsonObject("toolDiscovery").getAsJsonArray("candidates").size()>0);
            long successfulDumps=0;
            for(JsonElement operation:selected.getAsJsonArray("operations")){
                JsonObject op=operation.getAsJsonObject();
                if("thread-dump".equals(op.get("type").getAsString())&&op.get("success").getAsBoolean()){
                    successfulDumps++;Assert.assertEquals("x64",op.get("toolArchitecture").getAsString());Assert.assertFalse(op.get("toolJavaVersion").getAsString().isEmpty());
                    Assert.assertEquals(selected.get("selectedDumpTool").getAsString(),op.getAsJsonArray("command").get(0).getAsString());
                }
            }
            Assert.assertEquals(3,successfulDumps);
            Assert.assertFalse(result.get("serverTimeZoneId").getAsString().isEmpty());
            Assert.assertEquals(java.time.Instant.parse(result.get("serverStartedAtUtc").getAsString()),java.time.OffsetDateTime.parse(result.get("serverStartedAtLocal").getAsString()).toInstant());
            Assert.assertEquals(java.time.Instant.parse(result.get("serverCompletedAtUtc").getAsString()),java.time.OffsetDateTime.parse(result.get("serverCompletedAtLocal").getAsString()).toInstant());
            Assert.assertTrue(result.get("serverElapsedMillis").getAsLong()>=10000);
            Assert.assertTrue(Files.isRegularFile(job.resolve("data/server/SONUC.txt")));
            Assert.assertTrue(Files.size(job.resolve("data/server/network/netstat-ano.txt"))>0);
            Assert.assertTrue(result.getAsJsonArray("operations").size()>=16);
            Assert.assertTrue(fixture.process.isAlive());
            Assert.assertEquals(before,FileSupport.sha256(log)); Assert.assertEquals(before,FileSupport.sha256(job.resolve("data/logs/KA/log/app.log")));
            Assert.assertTrue(Files.isRegularFile(job.resolve("data/kaynak-izleme.json"))); Assert.assertTrue(Files.isRegularFile(job.resolve("data/remote-system.json")));
            String activity=new String(Files.readAllBytes(job.resolve("data/debug/activity-worker.jsonl")),StandardCharsets.UTF_8);
            Assert.assertTrue(activity,activity.contains("[Kesif] TAMAMLANDI"));
            Assert.assertTrue(activity,activity.contains("3/3 dump basarili"));
            Assert.assertTrue(activity,activity.contains("uzanti filtresi"));
            Assert.assertTrue(activity,activity.contains("SKIPPED_SIZE"));
            Assert.assertTrue(activity,activity.contains("[Log kaynagi] Tarama bitti"));
            Assert.assertTrue(Files.isRegularFile(job.resolve("data/logs/recursive/nested/recent.LOG")));
            Assert.assertFalse(Files.exists(job.resolve("data/logs/recursive/nested/ignored.xml")));Assert.assertFalse(Files.exists(job.resolve("data/logs/recursive/nested/large.log")));
            JsonObject monitoring=JsonParser.parseString(new String(Files.readAllBytes(job.resolve("data/kaynak-izleme.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            Assert.assertTrue(monitoring.getAsJsonArray("samples").size()>0);
            ServerResourceUsage usage=ServerResourceUsage.fromWindows(monitoring.getAsJsonArray("samples").get(0).getAsJsonObject());Assert.assertNotNull(usage.cpuUsagePercent);Assert.assertNotNull(usage.ramUsagePercent);
            List<java.time.Instant> times=new ArrayList<>();for(JsonElement operation:result.getAsJsonArray("jvms").get(0).getAsJsonObject().getAsJsonArray("operations")){JsonObject op=operation.getAsJsonObject();if("thread-dump".equals(op.get("type").getAsString())&&op.get("success").getAsBoolean())times.add(java.time.Instant.parse(op.get("startedAt").getAsString()));}
            for(int i=1;i<times.size();i++)Assert.assertTrue(java.time.Duration.between(times.get(i-1),times.get(i)).toMillis()>=4900);
            for (JsonElement element:result.getAsJsonArray("files")) {JsonObject file=element.getAsJsonObject();Path path=job.resolve("data").resolve(file.get("path").getAsString());Assert.assertEquals(file.get("sha256").getAsString().toLowerCase(Locale.ROOT),FileSupport.sha256(path));Assert.assertEquals(file.get("size").getAsLong(),Files.size(path));}
            Path evidence=Files.createDirectories(Paths.get("target/windows-evidence/remote-worker")); DiagnosticReportGenerator.writeJson(evidence.resolve("result.json"),result);
        } finally { FileSupport.deleteTree(job); FileSupport.deleteTree(sourceRoot); }
    }
}
