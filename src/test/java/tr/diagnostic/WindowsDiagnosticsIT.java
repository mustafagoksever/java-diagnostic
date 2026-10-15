package tr.diagnostic;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;

/** Native read-only command failures/timeouts must preserve output and release handles. */
public class WindowsDiagnosticsIT {
    @Test public void applicationLogDenialPreservesSystemEvents() throws Exception { events("Application",false); }
    @Test public void systemLogDenialStillCollectsApplicationEvents() throws Exception { events("System",false); }
    @Test public void emptyEventLogsProduceSuccessfulEmptyArrays() throws Exception { events("",true); }
    @Test public void timedOutSystemLogDoesNotBlockApplicationEvents() throws Exception { events("System-timeout",false); }
    @Test public void bothEventLogsArePreservedInTheCombinedArray() throws Exception { events("",false); }
    private void events(String denied,boolean empty) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("windows-events-test-");
        boolean timeout=denied.endsWith("-timeout");String deniedLog=timeout?"System":denied;
        try {
            Path helper=Files.write(root.resolve("windows.ps1"),PowerShell.resource("remote-windows.ps1").getBytes(StandardCharsets.UTF_8));
            String body="$ErrorActionPreference='Stop';$dataRoot='"+q(root)+"';[void][IO.Directory]::CreateDirectory((Join-Path $dataRoot 'server/diagnostics'));"
                +"$result=[pscustomobject]@{operations=@();errors=@()};$settings=@{action='collect';scanTimeoutSeconds=20};$script:requested=@();"
                +"function Deadline{};function Activity($message){};function Invoke-WindowsCommand($name,$exe,$arguments,$artifact){};"
                +"function Write-Json($path,$value){[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 6))};"
                +". '"+q(helper)+"';"
                +"function Invoke-WindowsCommand($name,$exe,$arguments,$artifact){};"
                +"function Run-Script($body,$setup,$label,$timeout){if($label -notlike 'windows-events*'){return @()};"
                +"function Get-WinEvent($FilterHashtable,$MaxEvents,$ErrorAction){$log=$FilterHashtable.LogName;$script:requested+=,$log;"
                +(empty?"$failure=New-Object Management.Automation.ErrorRecord ([Exception]'No events'),'NoMatchingEventsFound,Microsoft.PowerShell.Commands.GetWinEventCommand',([Management.Automation.ErrorCategory]::ObjectNotFound),$null;throw $failure;":"if($log -eq '"+deniedLog+"'){throw '"+(timeout?"QUERY_TIMEOUT: event fixture":"FIXTURE_ACCESS_DENIED")+"'};[pscustomobject]@{LogName=$log;Id=42;Message='preserved event'};")
                +"}; $json=& ([scriptblock]::Create($body));if($json){$json|ConvertFrom-Json}};"
                +"Collect-WindowsDiagnostics;[pscustomobject]@{requested=$requested;operations=$result.operations}|ConvertTo-Json -Depth 8 -Compress";
            CommandResult run=new PowerShell(new ProcessCommandExecutor()).run(body,15000);
            Assert.assertTrue(run.error(),run.success());JsonObject result=JsonParser.parseString(run.stdout.trim()).getAsJsonObject();
            Assert.assertEquals(2,result.getAsJsonArray("requested").size());
            String available=deniedLog.equals("System")?"application":"system";
            JsonArray aggregate=JsonParser.parseString(new String(Files.readAllBytes(root.resolve("server/diagnostics/events.json")),StandardCharsets.UTF_8)).getAsJsonArray();
            Assert.assertEquals(empty?0:denied.isEmpty()?2:1,aggregate.size());
            if(!empty&&!denied.isEmpty())Assert.assertEquals(available,aggregate.get(0).getAsJsonObject().get("LogName").getAsString().toLowerCase(java.util.Locale.ROOT));
            int eventOps=0,failed=0;
            for(JsonElement e:result.getAsJsonArray("operations")){JsonObject op=e.getAsJsonObject();if(op.get("type").getAsString().startsWith("windows-events-")){eventOps++;if(!op.get("success").getAsBoolean()){failed++;Assert.assertTrue(op.get("error").getAsString().contains(timeout?"QUERY_TIMEOUT":"FIXTURE_ACCESS_DENIED"));Assert.assertEquals(timeout,op.get("timedOut").getAsBoolean());}}}
            Assert.assertEquals(2,eventOps);Assert.assertEquals(empty||denied.isEmpty()?0:1,failed);
            Assert.assertTrue(Files.isRegularFile(root.resolve("server/diagnostics/events-"+available+".json")));
        }finally{FileSupport.deleteTree(root);}
    }
    @Test public void timeoutAndFailureKeepPartialOutputAndDoNotBlockNextCommand() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("windows-diagnostic-test-");
        try {
            Path helper=Files.write(root.resolve("windows.ps1"),PowerShell.resource("remote-windows.ps1").getBytes(StandardCharsets.UTF_8));
            String body="$ErrorActionPreference='Stop';$dataRoot='"+q(root)+"';$result=[pscustomobject]@{operations=@();errors=@()};$clock=[Diagnostics.Stopwatch]::StartNew();$settings=@{timeoutSeconds=60};"
                +"function Deadline{};function Activity($message){};function Wait-Helper($child,$budget){return $child.WaitForExit($budget)};"
                +"function Stop-Helper($child,$started){if($child){try{if($started -and !$child.HasExited){$child.Kill();[void]$child.WaitForExit(3000)}}finally{$child.Dispose()}}};"
                +". '"+q(helper)+"';$exe=Join-Path $PSHOME 'powershell.exe';"
                +"Invoke-WindowsCommand 'timeout' $exe '-NoProfile -NonInteractive -Command \"Write-Output partial; Start-Sleep 30\"' 'server/network/timeout.txt';"
                +"Invoke-WindowsCommand 'failure' $exe '-NoProfile -NonInteractive -Command \"Write-Output failed; exit 7\"' 'server/diagnostics/failure.txt';"
                +"Invoke-WindowsCommand 'next' $exe '-NoProfile -NonInteractive -Command \"Write-Output complete\"' 'server/network/next.txt';"
                +"$result|ConvertTo-Json -Depth 8 -Compress";
            CommandResult run=new PowerShell(new ProcessCommandExecutor()).run(body,25000);
            Assert.assertTrue(run.error(),run.success());JsonObject result=JsonParser.parseString(run.stdout.trim()).getAsJsonObject();
            JsonArray ops=result.getAsJsonArray("operations");Assert.assertEquals(3,ops.size());
            Assert.assertTrue(ops.get(0).getAsJsonObject().get("timedOut").getAsBoolean());
            Assert.assertFalse(ops.get(0).getAsJsonObject().get("success").getAsBoolean());
            Assert.assertEquals(7,ops.get(1).getAsJsonObject().get("exitCode").getAsInt());
            Assert.assertTrue(ops.get(2).getAsJsonObject().get("success").getAsBoolean());
            Assert.assertTrue(new String(Files.readAllBytes(root.resolve("server/network/timeout.txt")),StandardCharsets.UTF_8).contains("partial"));
            // Exclusive reopen proves the completed command released both streams.
            for(JsonElement e:ops)for(String key:new String[]{"artifact","stderrArtifact"}){
                Path path=root.resolve(e.getAsJsonObject().get(key).getAsString());Path moved=path.resolveSibling(path.getFileName()+".moved");Files.move(path,moved);Files.delete(moved);
            }
        }finally{FileSupport.deleteTree(root);}
    }
    private static String q(Path p){return p.toString().replace("'","''");}
}
