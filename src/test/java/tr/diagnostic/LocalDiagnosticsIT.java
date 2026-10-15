package tr.diagnostic;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.lingala.zip4j.ZipFile;
import org.junit.*;

/** Real local PowerShell, loopback ICMP and BAT; serial/Windows queries are controlled fixtures. */
public class LocalDiagnosticsIT {
    @Test public void temporaryHeartbeatReadLockKeepsResultsAndLogPreparationSuccessful() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-heartbeat-lock-");
        try {
            CollectorConfiguration c=new CollectorConfiguration();c.remoteWindowsDiagnostics=false;c.applicationDirectory=root;
            c.ping.enabled=true;c.ping.addresses.add("127.0.0.1");c.remoteBeforeLogCopyBatch="prepare.bat";
            java.util.concurrent.atomic.AtomicReference<Path> jobRef=new java.util.concurrent.atomic.AtomicReference<>();
            CommandExecutor fixture=(command,timeout,out,err)->{
                String body=new String(Base64.getDecoder().decode(command.get(command.size()-1)),StandardCharsets.UTF_16LE);
                java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("-RequestFile '([^']+)'$").matcher(body);Assert.assertTrue(matcher.find());
                Path requestPath=Paths.get(matcher.group(1));Path job=requestPath.getParent();jobRef.set(job);
                String heartbeat=job.resolve("client-heartbeat.txt").toString().replace("'","''");
                // Match the worker's ReadAllText sharing mode, holding the read handle across several client polls.
                String locking="$f=[IO.File]::Open('"+heartbeat+"',[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read);try{Start-Sleep -Milliseconds 1800}finally{$f.Dispose()}";
                CommandResult lock=new PowerShell(new ProcessCommandExecutor()).run(locking,7000);Assert.assertTrue(lock.error(),lock.success());
                Assert.assertFalse("A transient read lock must not cancel the collector",Files.exists(job.resolve("cancel.txt")));
                JsonObject request;try(Reader reader=Files.newBufferedReader(requestPath,StandardCharsets.UTF_8)){request=JsonParser.parseReader(reader).getAsJsonObject();}
                JsonObject completed=new JsonObject();completed.add("jobId",request.get("jobId"));completed.addProperty("logsReady",true);
                completed.add("operations",JsonParser.parseString("[{type:'before-log-copy',success:true}]"));
                completed.add("ping",JsonParser.parseString("{success:true,completed:true}"));
                DiagnosticReportGenerator.writeJson(job.resolve("complete.json"),completed);
                Path artifact=job.resolve("data/pre-log/batch.stdout.txt");Files.createDirectories(artifact.getParent());Files.write(artifact,"prepared".getBytes(StandardCharsets.UTF_8));
                CommandResult success=new CommandResult();success.exitCode=0;return success;
            };
            DiagnosticReportGenerator.Manifest manifest=new DiagnosticReportGenerator.Manifest();Path staging=Files.createDirectory(root.resolve("staging"));
            Assert.assertTrue("Log copying remains allowed",new LocalDiagnosticsCollector(c,fixture).collect(manifest,staging,message->{}));
            Assert.assertTrue(manifest.errors.toString(),manifest.errors.isEmpty());Assert.assertNotNull(manifest.ping);
            Assert.assertTrue(manifest.ping.get("success").getAsBoolean());Assert.assertTrue(manifest.operations.get(0).success);
            Assert.assertTrue(Files.isRegularFile(staging.resolve("pre-log/batch.stdout.txt")));Assert.assertFalse(Files.exists(jobRef.get()));
        } finally {FileSupport.deleteTree(root);}
    }
    @Test public void localReportIncludesWindowsPingSerialAndPreparedLogsWithoutRemoteConnection() throws Exception { collect(false,false); }
    @Test public void failedLocalBatchKeepsDiagnosticsAndSkipsApplicationLogs() throws Exception { collect(true,false); }
    @Test public void timedOutLocalBatchKeepsDiagnosticsAndSkipsApplicationLogs() throws Exception { collect(true,true); }

    @Test public void interruptionStopsTheOwnedBatchAndCleansItsJobDirectory() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-local-cancel-");
        java.util.concurrent.atomic.AtomicReference<Path> job=new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller=null;
        try {
            Path app=Files.createDirectories(root.resolve("app"));
            Files.write(app.resolve("prepare.bat"),"@echo off\r\n:wait\r\ngoto wait\r\n".getBytes(StandardCharsets.US_ASCII));
            CollectorConfiguration c=new CollectorConfiguration();c.remoteWindowsDiagnostics=false;c.applicationDirectory=app;c.remoteBeforeLogCopyBatch="prepare.bat";
            Path staging=Files.createDirectories(root.resolve("staging"));
            CommandExecutor commands=(command,timeout,out,err)->{
                String script=new String(Base64.getDecoder().decode(command.get(command.size()-1)),StandardCharsets.UTF_16LE);
                java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("-RequestFile '([^']+)'$").matcher(script);Assert.assertTrue(matcher.find());
                job.set(Paths.get(matcher.group(1)).getParent());return new ProcessCommandExecutor().run(command,timeout,out,err);
            };
            java.util.concurrent.FutureTask<Boolean> task=new java.util.concurrent.FutureTask<>(()->new LocalDiagnosticsCollector(c,commands).collect(new DiagnosticReportGenerator.Manifest(),staging,message->{}));
            caller=new Thread(task,"local-diagnostics-cancel-test");caller.start();
            long deadline=System.nanoTime()+15_000_000_000L;
            while((job.get()==null||!Files.exists(job.get().resolve("data/pre-log/batch.stdout.txt")))&&System.nanoTime()<deadline)Thread.sleep(25);
            Assert.assertNotNull(job.get());Assert.assertTrue("BAT was started",Files.exists(job.get().resolve("data/pre-log/batch.stdout.txt")));
            caller.interrupt();
            try{task.get(15,java.util.concurrent.TimeUnit.SECONDS);Assert.fail("Interruption must propagate");}
            catch(java.util.concurrent.ExecutionException expected){Assert.assertTrue(expected.toString(),expected.getCause() instanceof InterruptedException);}
            caller.join(1000);Assert.assertFalse(caller.isAlive());Assert.assertFalse("Child files can be deleted after cancellation",Files.exists(job.get()));
            Assert.assertTrue(Files.isRegularFile(app.resolve("prepare.bat")));
        } finally {
            if(caller!=null&&caller.isAlive()){caller.interrupt();caller.join(15000);}
            FileSupport.deleteTree(root);
        }
    }

    private void collect(boolean failBatch, boolean timeoutBatch) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-local-extras-");
        char[] password=ZipEncryptionService.generatePassword();
        try {
            Path app=Files.createDirectories(root.resolve("application"));Path logs=Files.createDirectories(app.resolve("Log"));
            Files.write(logs.resolve("prepare.bat"),(timeoutBatch?"@echo off\r\n:wait\r\ngoto wait\r\n":"@echo off\r\necho prepared-locally>prepared.log\r\nexit /b "+(failBatch?7:0)+"\r\n").getBytes(StandardCharsets.US_ASCII));
            CollectorConfiguration c=new CollectorConfiguration();c.mode="local";c.outputDirectory=root.resolve("reports");c.applicationDirectory=app;c.installationDirectory=app;
            c.remoteHost="must-not-connect.invalid";c.remoteUsername="must-not-use-user";c.resourceMonitoringSeconds=0;
            c.remoteBeforeLogCopyBatch="Log/prepare.bat";c.remoteBeforeLogCopyDelaySeconds=1;
            if(timeoutBatch)c.remoteBeforeLogCopyTimeoutSeconds=1;
            c.ping.enabled=true;c.ping.addresses.add("127.0.0.1");c.ping.attempts=1;c.ping.intervalMillis=0;
            c.vxworks.enabled=true;c.vxworks.ports.add("COM65535");
            CollectorConfiguration.LogDirectory source=new CollectorConfiguration.LogDirectory();source.path=logs;source.destination="application";source.extensions.add(".log");c.logs.add(source);
            boolean[] helperStarted={false};Path[] job={null};
            CommandExecutor commands=(command,timeout,out,err)->{
                CommandResult result=new CommandResult();result.exitCode=0;
                if(command.get(0).endsWith("jps.exe")){result.stdout="";return result;}
                String body=new String(Base64.getDecoder().decode(command.get(command.size()-1)),StandardCharsets.UTF_16LE);
                if(body.contains("local-diagnostics.ps1")){
                    java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("-RequestFile '([^']+)'$").matcher(body);Assert.assertTrue(body,matcher.find());
                    Path requestFile=Paths.get(matcher.group(1));job[0]=requestFile.getParent();
                    String request=new String(Files.readAllBytes(requestFile),StandardCharsets.UTF_8);
                    Assert.assertFalse(request.contains("must-not-connect")||request.contains("must-not-use-user")||request.contains(new String(password)));
                    Assert.assertFalse("No remote connection helper",Files.exists(job[0].resolve("remote-client.ps1")));
                    Files.write(job[0].resolve("remote-windows.ps1"),("function Collect-WindowsDiagnostics { $folder=Join-Path $dataRoot 'server/diagnostics';[void][IO.Directory]::CreateDirectory($folder);Write-Json (Join-Path $folder 'events.json') @();[IO.File]::WriteAllText((Join-Path $dataRoot 'server/SONUC.txt'),'local-windows-fixture');$result.operations+=,[pscustomobject]@{type='windows-fixture';success=$true;artifact='server/diagnostics/events.json'} }").getBytes(StandardCharsets.UTF_8));
                    String serial="param($PortName,$JobRoot,$RequestFile);$folder=Join-Path $JobRoot ('data/vxworks/ports/port-'+$PortName);[void][IO.Directory]::CreateDirectory($folder);[IO.File]::WriteAllText((Join-Path $folder 'console.txt'),'serial-fixture');$r=@{port=$PortName;success=$true;completed=$true;versionConfirmed=$true;status='COMPLETE';tasks=@();commands=@();socketRows=@();initialTaskCount=0;attemptedTaskCount=0;tracesWithFrames=0;taskInfosCollected=0;diagnostics=@();networkAvailable=$true};$json=$r|ConvertTo-Json -Depth 6;[IO.File]::WriteAllText((Join-Path $folder 'port-result.json'),$json);$json";
                    Files.write(job[0].resolve("remote-vxworks.ps1"),serial.getBytes(StandardCharsets.UTF_8));helperStarted[0]=true;
                    return new ProcessCommandExecutor().run(command,timeout,out,err);
                }
                result.stdout=body.contains("$additionalNames")?"{processes:[],errors:[],elevated:false,collectorIdentity:'fixture'}":"{errors:[],computer:[],operatingSystem:[],cpuTotal:[],disks:[]}";
                return result;
            };
            CollectionOrchestrator.Result result=new CollectionOrchestrator(c,commands).collect(Collections.singleton(ProcessScanner.currentPid()),password,message->{});
            Assert.assertTrue(helperStarted[0]);Assert.assertFalse("Own helper directory cleaned",Files.exists(job[0]));
            Assert.assertEquals("local",result.manifest.mode);Assert.assertNull(result.manifest.targetHost);
            Assert.assertNotNull(result.manifest.ping);Assert.assertNotNull(result.manifest.vxworks);
            Assert.assertTrue(result.manifest.ping.toString(),result.manifest.ping.get("completed").getAsBoolean());
            Assert.assertTrue(result.manifest.vxworks.toString(),result.manifest.vxworks.get("success").getAsBoolean());
            DiagnosticReportGenerator.Operation batch=result.manifest.operations.stream().filter(op->"before-log-copy".equals(op.type)).findFirst().get();
            Assert.assertEquals(!failBatch,batch.success);Assert.assertEquals("direct-cmd",batch.executionMode);
            if(timeoutBatch)Assert.assertTrue(batch.error,batch.timedOut);
            if(!failBatch){Assert.assertEquals(1,batch.postBatchWaitSeconds);Assert.assertTrue(batch.durationMillis>=900);}
            Assert.assertTrue(result.manifest.operations.stream().anyMatch(op->"windows-fixture".equals(op.type)&&op.success));
            try(ZipFile zip=new ZipFile(result.archive.toFile(),password)){
                for(String artifact:Arrays.asList("server/diagnostics/events.json","server/SONUC.txt","ping/results.json","ping/SONUC.txt","vxworks/SONUC.txt","vxworks/ports/port-COM65535/console.txt","pre-log/batch.stdout.txt","debug/local-diagnostics/activity-worker.jsonl")) Assert.assertNotNull(artifact,zip.getFileHeader(artifact));
                if(failBatch){Assert.assertNull(zip.getFileHeader("logs/application/prepared.log"));Assert.assertEquals("SKIPPED_PREPARATION_FAILED",result.manifest.logs.get(0).status);}
                else{Assert.assertNotNull(zip.getFileHeader("logs/application/prepared.log"));Assert.assertEquals("COPIED",result.manifest.logs.get(0).status);}
                try(InputStream input=zip.getInputStream(zip.getFileHeader("ping/SONUC.txt"))){Assert.assertTrue(new String(readAll(input),StandardCharsets.UTF_8).contains("BU BİLGİSAYARDAN"));}
            }
            Assert.assertTrue("Source BAT left in place",Files.isRegularFile(logs.resolve("prepare.bat")));
        } finally {Arrays.fill(password,'\0');FileSupport.deleteTree(root);}
    }
    private static byte[] readAll(InputStream input) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)out.write(bytes,0,count);return out.toByteArray();}
}
