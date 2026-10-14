package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Executes real CMD/BAT files on local Windows through the server worker. */
public class RemoteBeforeLogCopyIT {
    @Test public void batchFinishesInLogDirectoryBeforeAnyLogIsCopied() throws Exception {run("success");}
    @Test public void failedBatchPreventsAllLogCopies() throws Exception {run("failure");}
    @Test public void missingBatchPreventsAllLogCopies() throws Exception {run("missing");}
    @Test public void timedOutBatchPreventsAllLogCopies() throws Exception {run("timeout");}
    @Test public void listingProcessesNeverRunsBatch() throws Exception {run("list");}
    @Test public void desktopBatchWithoutAnApplicationSessionIsNotExecuted() throws Exception {run("interactive");}
    @Test public void desktopBatchRunsInTheExistingUserSessionAndTaskIsRemoved() throws Exception {run("interactive-task");}
    @Test public void timedOutDesktopBatchCleansUpItsTask() throws Exception {run("interactive-timeout");}
    @Test public void waitsThirtySecondsAfterBatchBeforeCopyingLogs() throws Exception {run("delay");}
    @Test public void cancellationStopsOnlyTheInteractiveJobAndRemovesItsTask() throws Exception {run("interactive-cancel");}
    @Test public void sessionSelectionRejectsAmbiguousOrDifferentUserDesktops() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path temp=Files.createTempDirectory("diagnostic-session-selection-");
        try{
            Path source=Files.write(temp.resolve("worker.ps1"),PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Select-InteractiveTarget'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$settings=[pscustomobject]@{interactiveUserName='server\\user';interactiveUserSid='S-1-5-21-1'};$a=[pscustomobject]@{owner='server\\user';sessionId=1;collectorProcess=$false;mainClass='app'};$b=[pscustomobject]@{owner='server\\user';sessionId=2;collectorProcess=$false;mainClass='app'};$result=[pscustomobject]@{discovery=[pscustomobject]@{processes=@($a,$b)}};"
                +"$ambiguous=$false;try{Select-InteractiveTarget|Out-Null}catch{$ambiguous=$_.Exception.Message -like 'INTERACTIVE_SESSION_AMBIGUOUS*'};"
                +"$a.mainClass='org.eclipse.osgi.jar';$preferred=(Select-InteractiveTarget).sessionId;$settings.interactiveUserName='server\\other';$other=$false;try{Select-InteractiveTarget|Out-Null}catch{$other=$_.Exception.Message -like 'INTERACTIVE_SESSION_NOT_FOUND*'};"
                +"[pscustomobject]@{ambiguous=$ambiguous;preferred=$preferred;other=$other}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();Assert.assertTrue(result.get("ambiguous").getAsBoolean());Assert.assertEquals(1,result.get("preferred").getAsInt());Assert.assertTrue(result.get("other").getAsBoolean());
        }finally{FileSupport.deleteTree(temp);}
    }

    private void run(String scenario) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path job=Paths.get("C:/Temp","diagnostic-remote-"+UUID.randomUUID());
        Path source=Files.createTempDirectory("diagnostic bat source & ");
        Files.createDirectories(job);FileSupport.restrictDirectory(job);
        try {
            for(String resource:Arrays.asList("remote-worker.ps1","remote-batch.ps1","process-scan.ps1","system-info.ps1","resource-monitor.ps1"))writeScript(job.resolve(resource),PowerShell.resource(resource));
            writeScript(job.resolve("process-scan.ps1"),"'{\"processes\":[],\"errors\":[],\"elevated\":false}'");
            boolean taskScenario=scenario.equals("interactive-task")||scenario.equals("interactive-timeout")||scenario.equals("interactive-cancel");
            JsonObject interactiveIdentity=null;
            if(taskScenario){
                CommandResult identity=new PowerShell(new ProcessCommandExecutor()).run("[pscustomobject]@{sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;name=[Security.Principal.WindowsIdentity]::GetCurrent().Name;session=[Diagnostics.Process]::GetCurrentProcess().SessionId}|ConvertTo-Json -Compress",10000);
                Assert.assertTrue(identity.error(),identity.success());interactiveIdentity=JsonParser.parseString(identity.stdout.trim()).getAsJsonObject();
                Assert.assertTrue("Test requires an interactive Windows session",interactiveIdentity.get("session").getAsInt()>0);
                writeScript(job.resolve("process-scan.ps1"),"$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+ProcessScanner.currentPid()+"';[pscustomobject]@{processes=@([pscustomobject]@{pid=$p.ProcessId;processName='java.exe';owner=[Security.Principal.WindowsIdentity]::GetCurrent().Name;sessionId=$p.SessionId;startTicks=$p.CreationDate.ToUniversalTime().Ticks.ToString();mainClass='org.eclipse.osgi.fixture';collectorProcess=$false});errors=@();elevated=$false}|ConvertTo-Json -Depth 5 -Compress");
            }
            writeScript(job.resolve("system-info.ps1"),"'{\"errors\":[]}'");
            Path log=Files.createDirectories(source.resolve("Log"));
            Files.write(log.resolve("app.log"),"stale log".getBytes(StandardCharsets.UTF_8));
            if(!scenario.equals("missing")){
                String body=scenario.equals("interactive")?"@echo off\r\n> must-not-run.txt echo wrong\r\nnircmd sendkeypress k a y d a l enter\r\nexit /b 0\r\n":scenario.equals("timeout")?"@echo off\r\n:wait\r\ngoto wait\r\n":scenario.equals("failure")?"@echo off\r\necho fixture failure 1>&2\r\nexit /b 7\r\n":"@echo off\r\n> app.log echo prepared log\r\necho fixture stdout\r\nexit /b 0\r\n";
                // Route through the real scheduler without actually sending keys.
                if(taskScenario)body="@echo off\r\nif 1==2 nircmd sendkeypress k a y d a l enter\r\n> app.log echo prepared log\r\n> batch-started.txt echo ready\r\necho fixture stdout\r\n"+((scenario.equals("interactive-timeout")||scenario.equals("interactive-cancel"))?":wait\r\ngoto wait\r\n":"exit /b 0\r\n");
                // CMD BAT has no UTF-8 BOM; only ASCII content is needed here.
                Files.write(log.resolve("kaydal.bat"),body.getBytes(StandardCharsets.US_ASCII));
            }
            CollectorConfiguration c=new CollectorConfiguration();c.installationPath=source.toString();c.applicationPath="auto";c.resourceMonitoringSeconds=0;c.collectGcStats=false;
            c.remoteBeforeLogCopyBatch="Log/kaydal.bat";c.remoteBeforeLogCopyTimeoutSeconds=scenario.contains("timeout")?1:10;
            c.remoteBeforeLogCopyDelaySeconds=scenario.equals("delay")?30:0;
            CollectorConfiguration.LogDirectory spec=new CollectorConfiguration.LogDirectory();spec.sourcePath="Log";spec.base="application";spec.destination="fixture";spec.extensions.add(".log");spec.modifiedWithinHours=0;c.logs.add(spec);
            JsonObject settings;
            try(RemoteConnection connection=new RemoteConnection("localhost","test\\user","C:/missing-fixture-jdk","unused-secret".toCharArray())){settings=new RemoteCollectionOrchestrator(c).settings(scenario.equals("list")?"list":"collect",null,connection);}
            settings.addProperty("jobId",job.getFileName().toString().substring("diagnostic-remote-".length()));DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);
            if(scenario.equals("interactive")||taskScenario)settings.addProperty("nonInteractiveTransport",true);
            if(taskScenario){settings.addProperty("interactiveUserSid",interactiveIdentity.get("sid").getAsString());settings.addProperty("interactiveUserName",interactiveIdentity.get("name").getAsString());settings.add("selectedPids",new JsonArray());}
            DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);
            if(scenario.equals("interactive-cancel")){
                Process worker=new ProcessBuilder(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("remote-worker.ps1").toString(),"-RequestFile",job.resolve("request.json").toString()).redirectOutput(source.resolve("worker.out").toFile()).redirectError(source.resolve("worker.err").toFile()).start();
                try{
                    long deadline=System.nanoTime()+15_000_000_000L;while(!Files.exists(log.resolve("batch-started.txt"))&&System.nanoTime()<deadline)Thread.sleep(100);
                    Assert.assertTrue("Interactive BAT started",Files.exists(log.resolve("batch-started.txt")));
                    Files.write(job.resolve("cancel.txt"),new byte[]{1});Assert.assertTrue("Worker exits on cancellation",worker.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
                    Assert.assertEquals(new String(Files.readAllBytes(source.resolve("worker.err")),StandardCharsets.UTF_8),0,worker.exitValue());Assert.assertFalse("Own job folder removed",Files.exists(job));
                    String name="DiagnosticCollector-BAT-"+job.getFileName().toString().substring("diagnostic-remote-".length());
                    CommandResult check=new PowerShell(new ProcessCommandExecutor()).run("$s=New-Object -ComObject Schedule.Service;$s.Connect();@($s.GetFolder('\\').GetTasks(1)|Where-Object{$_.Name -eq '"+name+"'}).Count",10000);
                    Assert.assertTrue(check.error(),check.success());Assert.assertEquals("0",check.stdout.trim());return;
                }finally{if(worker.isAlive()){worker.destroyForcibly();worker.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
            }
            CommandResult execution=new ProcessCommandExecutor().run(Arrays.asList(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("remote-worker.ps1").toString(),"-RequestFile",job.resolve("request.json").toString()),75000,job.resolve("worker.out"),job.resolve("worker.err"));
            Assert.assertTrue(execution.error(),execution.success());Assert.assertTrue(execution.stderr,Files.isRegularFile(job.resolve("complete.json")));
            JsonObject result=JsonParser.parseString(new String(Files.readAllBytes(job.resolve("complete.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            if(scenario.equals("list")){
                Assert.assertEquals(0,result.getAsJsonArray("operations").size());Assert.assertEquals("stale log",new String(Files.readAllBytes(log.resolve("app.log")),StandardCharsets.UTF_8));return;
            }
            Assert.assertEquals(result.toString(),1,result.getAsJsonArray("operations").size());
            JsonObject operation=result.getAsJsonArray("operations").get(0).getAsJsonObject();Assert.assertEquals(log.toString(),operation.get("workingDirectory").getAsString());
            Path copied=job.resolve("data/logs/fixture/app.log");
            if(scenario.equals("success")||scenario.equals("delay")||scenario.equals("interactive-task")){
                Assert.assertTrue(operation.toString(),operation.get("success").getAsBoolean());Assert.assertEquals(0,operation.get("exitCode").getAsInt());
                Assert.assertEquals("prepared log",new String(Files.readAllBytes(copied),StandardCharsets.UTF_8).trim());
                Assert.assertEquals(FileSupport.sha256(log.resolve("app.log")),FileSupport.sha256(copied));
                Assert.assertTrue(new String(Files.readAllBytes(job.resolve("data/pre-log/kaydal.stdout.txt")),StandardCharsets.UTF_8).contains("fixture stdout"));
                if(scenario.equals("delay")){
                    Assert.assertEquals(30,operation.get("postBatchWaitSeconds").getAsInt());
                    Assert.assertTrue("Full thirty-second delay",java.time.Duration.between(java.time.Instant.parse(operation.get("batchCompletedAt").getAsString()),java.time.Instant.parse(operation.get("completedAt").getAsString())).toMillis()>=30000);
                }
            }else{
                Assert.assertFalse(operation.toString(),operation.get("success").getAsBoolean());Assert.assertFalse("No stale log copy",Files.exists(copied));
                Assert.assertEquals("SKIPPED_PREPARATION_FAILED",result.getAsJsonArray("logs").get(0).getAsJsonObject().get("status").getAsString());
                if(scenario.contains("timeout"))Assert.assertTrue(operation.toString(),operation.get("timedOut").getAsBoolean());
                if(scenario.equals("failure")){Assert.assertEquals(7,operation.get("exitCode").getAsInt());Assert.assertTrue(operation.toString(),operation.get("error").getAsString().contains("fixture failure"));}
                if(scenario.equals("interactive")){
                    Assert.assertTrue(operation.toString(),operation.get("error").getAsString().contains("INTERACTIVE_SESSION_NOT_FOUND"));
                    Assert.assertFalse("BAT never started",Files.exists(log.resolve("must-not-run.txt")));
                    Assert.assertFalse("No empty console artifact",Files.exists(job.resolve("data/pre-log")));
                }
            }
            if(taskScenario){
                Assert.assertEquals("interactive-task",operation.get("executionMode").getAsString());
                Assert.assertEquals(interactiveIdentity.get("session").getAsInt(),operation.get("sessionId").getAsInt());
                Assert.assertTrue(operation.toString(),operation.get("taskCleanupSucceeded").getAsBoolean());
                String taskName=operation.get("taskName").getAsString();
                CommandResult taskCheck=new PowerShell(new ProcessCommandExecutor()).run("$s=New-Object -ComObject Schedule.Service;$s.Connect();@($s.GetFolder('\\').GetTasks(1)|Where-Object{$_.Name -eq '"+taskName+"'}).Count",10000);
                Assert.assertTrue(taskCheck.error(),taskCheck.success());Assert.assertEquals("Temporary task removed","0",taskCheck.stdout.trim());
            }
        }finally{if(Files.exists(job))FileSupport.deleteTree(job);FileSupport.deleteTree(source);}
    }
    private static void writeScript(Path path,String text)throws Exception{
        byte[] body=text.getBytes(StandardCharsets.UTF_8),bom=new byte[body.length+3];bom[0]=(byte)0xef;bom[1]=(byte)0xbb;bom[2]=(byte)0xbf;System.arraycopy(body,0,bom,3,body.length);Files.write(path,bom);
    }
}
