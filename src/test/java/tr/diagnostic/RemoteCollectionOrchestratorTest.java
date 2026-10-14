package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import net.lingala.zip4j.ZipFile;

public class RemoteCollectionOrchestratorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void malformedRemoteErrorIsRecordedWithoutLosingTheArchive() throws Exception {
        CollectorConfiguration c = new CollectorConfiguration(); c.outputDirectory = temp.getRoot().toPath(); c.resourceMonitoringSeconds = 0;
        RemoteTransport fake = (settings, staging, connection, progress) -> {
            Assert.assertTrue(settings.get("selectedPids").isJsonNull());
            JsonObject event = JsonParser.parseString("{result:{errors:[null],jvms:[],logs:[],serverStartedAtUtc:'2026-10-13T00:00:00Z'}}").getAsJsonObject();
            return event;
        };
        char[] password = ZipEncryptionService.generatePassword();
        try (RemoteConnection connection = new RemoteConnection("server", "server\\user", "C:/Java/jdk8", "session-secret".toCharArray())) {
            CollectionOrchestrator.Result result = new RemoteCollectionOrchestrator(c, fake).collect(null, password, connection, s -> {});
            Assert.assertTrue(Files.isRegularFile(result.archive));
            Assert.assertEquals("PARTIAL", result.manifest.collectionStatus);
            Assert.assertTrue(result.manifest.errors.contains("Sunucu sonuç verisinde geçersiz hata kaydı."));
        } finally { Arrays.fill(password, '\0'); }
    }
    @Test public void remotePidMayEqualClientPidAndSystemMetricsComeFromServer() throws Exception {
        CollectorConfiguration c = new CollectorConfiguration(); c.outputDirectory = temp.getRoot().toPath(); c.resourceMonitoringSeconds = 0;
        long clientPid = ProcessScanner.currentPid();
        RemoteTransport fake = (settings, staging, connection, progress) -> {
            Assert.assertEquals("remote-server", connection.host);
            Assert.assertFalse(settings.toString().contains("session-secret"));
            JsonObject result = JsonParser.parseString("{discovery:{windowsDiscoverySucceeded:true,elevated:true,processes:[],errors:[]},errors:[],logs:[],excludedJvms:[],jvms:[{process:{pid:"+clientPid+",processName:'java.exe',collectorProcess:false},requestedDumpCount:3,dumpCount:3,operations:[]}],serverStartedAtUtc:'2015-01-01T00:00:00Z',resolved:{installationDirectory:'C:/remote'}}").getAsJsonObject();
            Files.write(staging.resolve("remote-system.json"), "{computer:[{Name:'SERVER-ONLY'}],cpuTotal:[{PercentProcessorTime:77}],operatingSystem:[{TotalVisibleMemorySize:8388608,FreePhysicalMemory:4194304}],disks:[],errors:[]}".getBytes(StandardCharsets.UTF_8));
            JsonObject event = new JsonObject(); event.addProperty("cleanupSucceeded", true); event.add("result", result); return event;
        };
        char[] zipPassword = ZipEncryptionService.generatePassword();
        try (RemoteConnection connection = new RemoteConnection("remote-server", "SERVER\\user", "C:/Java/jdk8", "session-secret".toCharArray())) {
            CollectionOrchestrator.Result result = new RemoteCollectionOrchestrator(c, fake).collect(Collections.singleton(clientPid), zipPassword, connection, message -> { });
            Assert.assertEquals("remote", result.manifest.mode); Assert.assertEquals(1, result.manifest.jvms.size()); Assert.assertEquals(0, result.manifest.excludedJvms.size());
            try (ZipFile zip = new ZipFile(result.archive.toFile(), zipPassword)) {
                try (InputStream input = zip.getInputStream(zip.getFileHeader("system-info.json"))) {
                    JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                    Assert.assertEquals("SERVER-ONLY", json.getAsJsonObject("serverUsage").get("serverName").getAsString());
                    Assert.assertEquals(77, json.getAsJsonObject("serverUsage").get("cpuUsagePercent").getAsDouble(), 0);
                }
                try (InputStream input = zip.getInputStream(zip.getFileHeader("Manifest.json"))) {
                    String json = new String(readAll(input), StandardCharsets.UTF_8); Assert.assertFalse(json.contains("session-secret")); Assert.assertFalse(json.contains("SERVER\\\\user"));
                }
            }
            try (java.util.stream.Stream<Path> files = Files.list(c.outputDirectory)) { Assert.assertEquals(1, files.count()); }
        } finally { Arrays.fill(zipPassword, '\0'); }
    }
    @Test public void remoteSourcesRemainDeclarativeAndCredentialsAreCleared() throws Exception {
        CollectorConfiguration c = new CollectorConfiguration();
        CollectorConfiguration.LogDirectory log = new CollectorConfiguration.LogDirectory(); log.path = Paths.get("C:/client/wrong"); log.sourcePath = "Log"; log.base = "application"; c.logs.add(log);
        char[] original = "session-secret".toCharArray(); RemoteConnection connection = new RemoteConnection("server", "server\\user", "C:/Java/jdk8", original); Arrays.fill(original, '\0');
        JsonObject settings = new RemoteCollectionOrchestrator(c).settings("collect", null, connection);
        Assert.assertEquals("Log", settings.getAsJsonArray("logs").get(0).getAsJsonObject().get("path").getAsString());
        Assert.assertEquals('s', connection.passwordCopy()[0]); connection.close();
        for (char value : connection.passwordCopy()) Assert.assertEquals('\0', value);
    }
    @Test public void batchFailureAndConsoleArtifactsAreRecordedInTheEncryptedManifest() throws Exception {
        CollectorConfiguration c=new CollectorConfiguration();c.outputDirectory=temp.getRoot().toPath();c.resourceMonitoringSeconds=0;
        c.remoteBeforeLogCopyBatch="Log/kaydal.bat";c.remoteBeforeLogCopyTimeoutSeconds=120;
        RemoteTransport fake=(settings,staging,connection,progress)->{
            Assert.assertEquals("Log/kaydal.bat",settings.get("beforeLogCopyBatch").getAsString());Assert.assertEquals(120,settings.get("beforeLogCopyTimeoutSeconds").getAsInt());
            Assert.assertEquals(30,settings.get("beforeLogCopyDelaySeconds").getAsInt());
            Files.createDirectories(staging.resolve("pre-log"));Files.write(staging.resolve("pre-log/kaydal.stderr.txt"),"batch failed".getBytes(StandardCharsets.UTF_8));
            Files.write(staging.resolve("remote-system.json"),"{errors:[]}".getBytes(StandardCharsets.UTF_8));
            return JsonParser.parseString("{result:{discovery:{windowsDiscoverySucceeded:true},jvms:[],logs:[],errors:[],operations:[{type:'before-log-copy',success:false,exitCode:7,workingDirectory:'C:/server/Log',stderrArtifact:'pre-log/kaydal.stderr.txt',error:'failed'}]}}").getAsJsonObject();
        };
        char[] password=ZipEncryptionService.generatePassword();
        try(RemoteConnection connection=new RemoteConnection("server","server\\user","C:/Java/jdk8","session-secret".toCharArray())){
            CollectionOrchestrator.Result result=new RemoteCollectionOrchestrator(c,fake).collect(null,password,connection,s->{});
            Assert.assertEquals("PARTIAL",result.manifest.collectionStatus);Assert.assertEquals(7,result.manifest.operations.get(0).exitCode.intValue());
            try(ZipFile zip=new ZipFile(result.archive.toFile(),password);InputStream input=zip.getInputStream(zip.getFileHeader("Manifest.json"))){
                JsonObject manifest=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject operation=manifest.getAsJsonArray("operations").get(0).getAsJsonObject();Assert.assertEquals("C:/server/Log",operation.get("workingDirectory").getAsString());
                Assert.assertNotNull(zip.getFileHeader(operation.get("stderrArtifact").getAsString()));
            }
        }finally{Arrays.fill(password,'\0');}
    }
    private static byte[] readAll(InputStream input) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n; while ((n=input.read(buffer))!=-1) bytes.write(buffer,0,n); return bytes.toByteArray(); }
    @Test public void pingAndVxWorksAreIncludedInTheSameEncryptedReport() throws Exception {
        CollectorConfiguration c=new CollectorConfiguration();c.outputDirectory=temp.getRoot().toPath();c.resourceMonitoringSeconds=0;
        c.ping.enabled=true;c.ping.addresses.add("15.30.9.98");c.vxworks.enabled=true;c.vxworks.ports.add("COM7");
        RemoteTransport fake=(settings,staging,connection,progress)->{
            Assert.assertTrue(settings.getAsJsonObject("ping").get("enabled").getAsBoolean());Assert.assertEquals("COM7",settings.getAsJsonObject("vxworks").getAsJsonArray("ports").get(0).getAsString());
            Assert.assertFalse(settings.toString().contains("session-secret"));
            Path detail=staging.resolve("vxworks/ports/port-COM7/port-result.json");Files.createDirectories(detail.getParent());Files.write(detail,"{tasks:[{taskId:'0x42',infoAttempted:true,infoCollected:true,infoArtifact:'vxworks/ports/port-COM7/tasks/ti-0x42.txt'}],socketRows:[]}".getBytes(StandardCharsets.UTF_8));
            Path info=detail.getParent().resolve("tasks/ti-0x42.txt");Files.createDirectories(info.getParent());Files.write(info,"task control block and registers".getBytes(StandardCharsets.UTF_8));
            Files.write(detail.getParent().resolve("TatlYaz.txt"),"custom image output".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(staging.resolve("ping"));Files.write(staging.resolve("ping/ping-15.30.9.98.txt"),"TimedOut".getBytes(StandardCharsets.UTF_8));
            Files.write(staging.resolve("remote-system.json"),"{errors:[]}".getBytes(StandardCharsets.UTF_8));
            return JsonParser.parseString("{result:{serverStartedAtUtc:'2026-10-14T00:00:00Z',discovery:{windowsDiscoverySucceeded:true},jvms:[],logs:[],errors:[],ping:{success:true,completed:true,results:[{address:'15.30.9.98',received:0,sent:4,status:'NO_ECHO_REPLY'}]},vxworks:{success:false,ports:[{port:'COM7',status:'PARTIAL',artifact:'vxworks/ports/port-COM7/port-result.json'}]},operations:[{type:'server-ping',success:true},{type:'vxworks-COM7',success:false}]}}").getAsJsonObject();
        };
        char[] password=ZipEncryptionService.generatePassword();
        try(RemoteConnection connection=new RemoteConnection("server","server\\user","C:/Java/jdk8","session-secret".toCharArray())){
            CollectionOrchestrator.Result result=new RemoteCollectionOrchestrator(c,fake).collect(null,password,connection,s->{});
            Assert.assertEquals("PARTIAL",result.manifest.collectionStatus);Assert.assertTrue(result.manifest.ping.get("success").getAsBoolean());Assert.assertNotNull(result.manifest.vxworks);
            try(ZipFile zip=new ZipFile(result.archive.toFile(),password)){
                Assert.assertNotNull(zip.getFileHeader("ping/ping-15.30.9.98.txt"));Assert.assertNotNull(zip.getFileHeader("ping/SONUC.txt"));Assert.assertNotNull(zip.getFileHeader("vxworks/SONUC.txt"));Assert.assertNotNull(zip.getFileHeader("vxworks/task-listesi.csv"));
                try(InputStream input=zip.getInputStream(zip.getFileHeader("vxworks/ports/port-COM7/tasks/ti-0x42.txt"))){Assert.assertEquals("task control block and registers",new String(readAll(input),StandardCharsets.UTF_8));}
                try(InputStream input=zip.getInputStream(zip.getFileHeader("vxworks/ports/port-COM7/TatlYaz.txt"))){Assert.assertEquals("custom image output",new String(readAll(input),StandardCharsets.UTF_8));}
                try(InputStream input=zip.getInputStream(zip.getFileHeader("Manifest.json"))){JsonObject manifest=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();Assert.assertTrue(manifest.has("ping"));Assert.assertTrue(manifest.has("vxworks"));}
            }
        }finally{Arrays.fill(password,'\0');}
    }
}
