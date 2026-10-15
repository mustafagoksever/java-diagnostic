package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;

/** Local Windows integration tests; fake serial adapter, real loopback ICMP only. */
public class RemoteExtrasIT {
    @Test public void pingActivityCountsIntermittentRepliesAsRespondingAddresses() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-ping-activity-");
        try{
            Path source=Files.write(root.resolve("extras.ps1"),PowerShell.resource("remote-extras.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+quote(source)+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Collect-RemoteExtraDiagnostics'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$settings=[pscustomobject]@{action='collect';ping=@{enabled=$true;addresses=@('127.0.0.1','127.0.0.2');attempts=2;parallelism=1;timeoutMillis=500;intervalMillis=0};vxworks=@{enabled=$false}};"
                +"$result=[pscustomobject]@{};$messages=New-Object 'Collections.Generic.List[string]';function Activity($message){$messages.Add([string]$message)};"
                +"function Invoke-ExtraHelper {return [pscustomobject]@{success=$true;results=@(@{address='127.0.0.1';status='INTERMITTENT';received=1;sent=2;averageRoundTripMillis=1},@{address='127.0.0.2';status='NO_ECHO_REPLY';received=0;sent=2;averageRoundTripMillis=$null})}};"
                +"Collect-RemoteExtraDiagnostics;[pscustomobject]@{messages=@($messages.ToArray())}|ConvertTo-Json -Depth 4 -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());
            JsonArray messages=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject().getAsJsonArray("messages");
            boolean summary=false;for(JsonElement message:messages)if(message.getAsString().equals("[Ping] Bitti: 1 IP yanit verdi; toplam 2 sonuc."))summary=true;
            Assert.assertTrue(messages.toString(),summary);
        }finally{FileSupport.deleteTree(root);}
    }
    @Test public void serialReaderUsesAll32BitTaskIdsAndCapturesSocketFallback() throws Exception {serial(false,false);}
    @Test public void serialReaderPreserves64BitTaskIdsAndNativeRestrictions() throws Exception {serial(true,false);}
    @Test public void silentPortTimesOutClosesAndNeverGetsDiagnosticCommands() throws Exception {serial(false,true);}
    @Test public void serialCloseFailureStillDisposesAndWritesFinalResult() throws Exception {serial(false,true,false,"close-failed");}
    @Test public void missingDeviceIpDoesNotInventAnAddressOrSkipTaskTraces() throws Exception {serial(false,false,true);}
    @Test public void unsupportedTaskInfoIsRecordedWithoutSkippingTracesOrNetwork() throws Exception {serial(false,false,false,"unsupported");}
    @Test public void taskInfoForAnotherTaskIsNotReportedAsCollected() throws Exception {serial(true,false,false,"unconfirmed");}
    @Test public void taskInfoTimeoutPreservesPartialOutputAndStopsFurtherCommands() throws Exception {serial(false,false,false,"silent");}
    @Test public void taskInfoWriteFailureNeverCopiesThePreviousTraceIntoItsFile() throws Exception {serial(false,false,false,"write-failed");}
    @Test public void traceTimeoutIsRecordedInTaskIndexAndUserFacingCsv() throws Exception {serial(false,false,false,"tt-silent");}
    @Test public void voidTaskInfoAcceptsMinusOneWithAValidTaskControlBlock() throws Exception {serial(false,false,false,"minus-one");}
    @Test public void voidTaskInfoAcceptsUnsignedAllOnesWithAValid64BitTaskId() throws Exception {serial(true,false,false,"unsigned-ones");}
    @Test public void workerRunsExtrasOnlyForCollectAndContinuesAfterPortFailure() throws Exception {worker(false,false);}
    @Test public void processListingNeverTouchesSerialOrPing() throws Exception {worker(true,false);}
    @Test public void noEchoReplyIsAFindingAndDoesNotMakePingCollectorFail() throws Exception {worker(false,true);}
    @Test public void automaticallyDiscoveredPortsAreSortedDeduplicatedAndReplaceManualSelection() throws Exception {worker(false,false,"auto");}
    @Test public void noDiscoveredPortsIsReportedWithoutLosingApplicationLogs() throws Exception {worker(false,false,"empty");}
    @Test public void failedPortDiscoveryIsReportedWithoutLosingApplicationLogs() throws Exception {worker(false,false,"failure");}
    @Test public void invalidDiscoveredNamesAreNotSentToSerialHelpers() throws Exception {worker(false,false,"invalid");}
    @Test public void listingWithAutomaticPortsEnabledDoesNotRunDiscovery() throws Exception {worker(true,false,"failure");}
    @Test public void automaticReaderAcceptsDiscoveredPortBeyondTheOldManualRange() throws Exception {serial(false,false,false,"auto-port");}
    @Test public void unsupportedfixtureIdentifyIsRecordedAndTaskAndNetworkCollectionContinues() throws Exception {serial(false,false,false,"custom-unsupported");}
    @Test public void customCommandTimeoutPreservesItsOutputAndStopsFurtherCommands() throws Exception {serial(false,false,false,"custom-silent");}
    @Test public void customCommandWriteFailureDoesNotCopyThePreviousIdentityResponse() throws Exception {serial(false,false,false,"custom-write-failed");}
    @Test public void unsupportedAdditionalDiagnosticsPreserveNativeErrorsAndContinueNetwork() throws Exception {serial(false,false,false,"diagnostic-unsupported");}
    @Test public void additionalDiagnosticTimeoutPreservesTracesAndStopsFurtherCommands() throws Exception {serial(false,false,false,"diagnostic-silent");}
    @Test public void arrowPromptWithoutVxWorksVersionNeverReceivesDiagnosticCommands() throws Exception {serial(false,false,false,"version-invalid");}
    @Test public void unknownSymbolMentioningVxWorksDoesNotPassVersionConfirmation() throws Exception {serial(false,false,false,"version-unsupported");}
    @Test public void responseLimitPreservesBoundedPartialOutputEvenWhenTheFirstReadIsOversized() throws Exception {serial(false,false,false,"custom-oversize");}
    @Test public void customCommandCapturesLateLogsForThreeSecondsBeforeTheNextCommand() throws Exception {serial(false,false,false,"custom-delayed");}
    @Test public void emptyCustomCommandSkipsOnlyTheOptionalCommand() throws Exception {serial(false,false,false,"custom-disabled");}
    @Test public void customSymbolAndDelayAreReadFromSettings() throws Exception {serial(false,false,false,"custom-renamed");}
    @Test public void cancellationDuringfixtureIdentifyCapturePreservesOutputAndStopsCommands() throws Exception {serial(false,false,false,"custom-cancel");}
    @Test public void unsupportedTimeStillCollectsModulesAndNetwork() throws Exception {serial(false,false,false,"time-unsupported");}
    @Test public void timeTimeoutPreservesPartialLogAndDoesNotSendModuleShow() throws Exception {serial(false,false,false,"time-silent");}

    private void serial(boolean wide,boolean silent) throws Exception {
        serial(wide,silent,false);
    }
    private void serial(boolean wide,boolean silent,boolean noIdentity) throws Exception {
        serial(wide,silent,noIdentity,"normal");
    }
    private void serial(boolean wide,boolean silent,boolean noIdentity,String tiMode) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path job=createJob();
        try{
            String port=tiMode.equals("auto-port")?"COM500":wide?"COM17":"COM7";
            CollectorConfiguration c=new CollectorConfiguration();c.remoteWindowsDiagnostics=false;c.vxworks.enabled=true;c.vxworks.autoDiscoverPorts=tiMode.equals("auto-port");if(!c.vxworks.autoDiscoverPorts)c.vxworks.ports.add(port);c.vxworks.commandTimeoutSeconds=(silent||tiMode.endsWith("silent"))?1:5;c.vxworks.portTimeoutSeconds=30;
            if(tiMode.equals("custom-delayed"))c.vxworks.commandTimeoutSeconds=1;
            c.vxworks.customCommand="fixtureIdentify";
            if(tiMode.equals("custom-disabled"))c.vxworks.customCommand="";
            if(tiMode.equals("custom-renamed")){c.vxworks.customCommand="fixtureInfo";c.vxworks.customCommandDelaySeconds=1;}
            writeSettings(job,c,"collect");
            String reader=PowerShell.resource("remote-vxworks.ps1");
            reader=reader.replaceAll("(?m)^\\$serial=New-Object IO\\.Ports\\.SerialPort.*$",Matcher.quoteReplacement("$serial=New-FakeSerial"));
            Assert.assertFalse("No physical serial constructor remains",reader.contains("$serial=New-Object IO.Ports.SerialPort"));
            script(job.resolve("remote-vxworks.ps1"),reader);
            String fixture;
            try(InputStream input=getClass().getResourceAsStream("/serial-fixture.ps1")){fixture=new String(bytes(input),StandardCharsets.UTF_8);}
            if(tiMode.equals("custom-renamed"))fixture=fixture.replace("fixtureIdentify","fixtureInfo");
            String launch="[Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('tr-TR');$global:fixtureRoot='"+quote(job)+"';$global:fixtureWide="+(wide?"$true":"$false")+";$global:fixtureSilent="+(silent?"$true":"$false")+";$global:fixtureNoIdentity="+(noIdentity?"$true":"$false")+";$global:fixtureTiMode='"+tiMode+"';\r\n"+fixture+"\r\n& '"+quote(job.resolve("remote-vxworks.ps1"))+"' -PortName '"+port+"' -JobRoot '"+quote(job)+"' -RequestFile '"+quote(job.resolve("request.json"))+"'";
            script(job.resolve("launch.ps1"),launch);CommandResult execution=run(job,"launch.ps1",30000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();Assert.assertTrue("Adapter disposed",Files.isRegularFile(job.resolve("disposed.txt")));
            if(tiMode.equals("close-failed")){Assert.assertTrue(result.toString(),result.get("error").getAsString().contains("SERIAL_CLOSE_FAILED"));Assert.assertTrue(result.toString(),result.get("error").getAsString().contains("SERIAL_DISPOSE_FAILED"));}
            StringBuilder messages=new StringBuilder();
            for(String line:Files.readAllLines(job.resolve("activity-"+port+".jsonl"),StandardCharsets.UTF_8)){
                if(!line.trim().isEmpty())messages.append(JsonParser.parseString(line).getAsJsonObject().get("message").getAsString()).append('\n');
            }
            String activity=messages.toString();
            for(JsonElement command:result.getAsJsonArray("commands")){
                String name=command.getAsJsonObject().get("command").getAsString();
                Assert.assertTrue("Missing command start: "+name,activity.contains(name+" | BASLADI"));
                Assert.assertTrue("Missing command outcome: "+name,activity.contains(name+" | YANIT ALINDI")||activity.contains(name+" | DESTEKLENMIYOR")||activity.contains(name+" | BASARISIZ")||activity.contains(name+" | YANIT TAMAMLANMADI"));
            }
            Assert.assertTrue(activity,activity.contains("PORT TAMAMLANDI"));
            Assert.assertTrue(Files.isDirectory(job.resolve("data/vxworks/ports/port-"+port)));
            if(silent){Assert.assertFalse(result.toString(),result.get("vxworksPrompt").getAsBoolean());Assert.assertEquals(0,result.getAsJsonArray("commands").size());return;}
            if(tiMode.startsWith("version-")){
                Assert.assertFalse(result.get("versionConfirmed").getAsBoolean());Assert.assertFalse(result.getAsJsonObject("customCommand").get("attempted").getAsBoolean());
                Assert.assertEquals(1,result.getAsJsonArray("commands").size());Assert.assertTrue(result.get("error").getAsString().contains("VXWORKS_VERSION_NOT_CONFIRMED"));return;
            }
            Assert.assertTrue(result.get("versionConfirmed").getAsBoolean());
            if(tiMode.equals("custom-disabled")||tiMode.equals("custom-renamed")){
                JsonObject optional=result.getAsJsonObject("customCommand");
                Assert.assertTrue(result.toString(),result.get("completed").getAsBoolean());
                Assert.assertEquals(3,result.get("attemptedTaskCount").getAsInt());
                Assert.assertTrue(result.get("networkAvailable").getAsBoolean());
                int matched=0;
                for(JsonElement item:result.getAsJsonArray("commands")){
                    JsonObject command=item.getAsJsonObject();String name=command.get("command").getAsString();
                    Assert.assertNotEquals("fixtureIdentify",name);
                    if(name.equals("fixtureInfo")){matched++;Assert.assertEquals(1000,command.get("postPromptCaptureMillis").getAsInt());}
                }
                if(tiMode.equals("custom-disabled")){
                    Assert.assertFalse(optional.get("attempted").getAsBoolean());Assert.assertEquals(0,matched);
                    Assert.assertFalse(Files.exists(job.resolve("data/vxworks/ports/port-COM7/custom-command.txt")));
                }else{
                    Assert.assertEquals(1,matched);Assert.assertTrue(optional.get("collected").getAsBoolean());
                    Assert.assertEquals("fixtureInfo",optional.get("command").getAsString());
                    Assert.assertTrue(new String(Files.readAllBytes(job.resolve("data").resolve(optional.get("artifact").getAsString())),StandardCharsets.UTF_8).startsWith("fixtureInfo"));
                }
                return;
            }
            JsonObject custom=result.getAsJsonObject("customCommand");Assert.assertTrue(custom.get("attempted").getAsBoolean());
            Path customFile=job.resolve("data").resolve(custom.get("artifact").getAsString());
            int customCommands=0;for(JsonElement item:result.getAsJsonArray("commands"))if(item.getAsJsonObject().get("command").getAsString().equals("fixtureIdentify"))customCommands++;
            Assert.assertEquals(1,customCommands);
            if(tiMode.equals("custom-cancel")){
                Assert.assertFalse(result.get("completed").getAsBoolean());Assert.assertTrue(custom.get("error").getAsString().contains("REMOTE_CANCELLED"));
                Assert.assertEquals(4,result.getAsJsonArray("commands").size());Assert.assertTrue(Files.isRegularFile(customFile));return;
            }
            if(tiMode.equals("custom-oversize")){
                Assert.assertFalse(result.get("completed").getAsBoolean());Assert.assertEquals("FAILED",result.get("status").getAsString());
                Assert.assertTrue(custom.get("error").getAsString().contains("SERIAL_RESPONSE_LIMIT"));
                Assert.assertTrue("Oversized first read must leave bounded partial response",Files.isRegularFile(customFile));Assert.assertEquals(1024*1024,Files.size(customFile));
                Assert.assertTrue(new String(Files.readAllBytes(customFile),StandardCharsets.UTF_8).startsWith("fixtureIdentify\r\nLARGE_BEGIN"));return;
            }
            if(tiMode.equals("custom-silent")||tiMode.equals("custom-write-failed")){
                Assert.assertFalse(custom.get("collected").getAsBoolean());Assert.assertFalse(result.get("completed").getAsBoolean());
                Assert.assertEquals(0,result.getAsJsonArray("tasks").size());Assert.assertEquals(4,result.getAsJsonArray("commands").size());
                if(tiMode.equals("custom-silent")){
                    Assert.assertEquals("TIMED_OUT",result.get("status").getAsString());Assert.assertTrue(custom.get("error").getAsString().contains("SERIAL_RESPONSE_TIMEOUT"));
                    Assert.assertTrue(new String(Files.readAllBytes(customFile),StandardCharsets.UTF_8).contains("partial custom output"));
                }else{
                    Assert.assertEquals("FAILED",result.get("status").getAsString());Assert.assertTrue(custom.get("error").getAsString().contains("FIXTURE_CUSTOM_COMMAND_WRITE_FAILED"));Assert.assertFalse(Files.exists(customFile));
                }
                return;
            }
            Assert.assertEquals(!tiMode.equals("custom-unsupported"),custom.get("collected").getAsBoolean());
            String customText=new String(Files.readAllBytes(customFile),StandardCharsets.UTF_8);
            if(tiMode.equals("custom-unsupported")){Assert.assertTrue(custom.get("error").getAsString().contains("CUSTOM_COMMAND_UNSUPPORTED"));Assert.assertTrue(customText.contains("unknown symbol"));}
            else Assert.assertTrue(customText.contains("FIXTURE_CUSTOM_OUTPUT"));
            JsonObject customOp=null;for(JsonElement item:result.getAsJsonArray("commands"))if(item.getAsJsonObject().get("command").getAsString().equals("fixtureIdentify"))customOp=item.getAsJsonObject();
            Assert.assertNotNull(customOp);Assert.assertEquals(3000,customOp.get("postPromptCaptureMillis").getAsInt());Assert.assertTrue(customOp.toString(),customOp.get("durationMillis").getAsLong()>=3000);
            if(tiMode.equals("custom-delayed")){
                Assert.assertTrue(customText.contains("LATE_CUSTOM_COMMAND_OUTPUT"));
                Assert.assertFalse(new String(Files.readAllBytes(job.resolve("data/vxworks/ports/port-COM7/i-before.txt")),StandardCharsets.UTF_8).contains("LATE_CUSTOM_COMMAND_OUTPUT"));
            }
            if(tiMode.equals("write-failed")){
                JsonObject task=result.getAsJsonArray("tasks").get(0).getAsJsonObject();Assert.assertFalse(result.get("completed").getAsBoolean());
                Assert.assertEquals("FAILED",result.get("status").getAsString());Assert.assertTrue(task.get("error").isJsonNull());
                Assert.assertTrue(task.get("infoError").getAsString().contains("FIXTURE_SERIAL_WRITE_FAILED"));
                Assert.assertFalse("No prior response or empty placeholder saved for an unsent ti",Files.exists(job.resolve("data").resolve(task.get("infoArtifact").getAsString())));
                String trace=new String(Files.readAllBytes(job.resolve("data").resolve(task.get("artifact").getAsString())),StandardCharsets.UTF_8);Assert.assertTrue(trace.startsWith("tt 0x42"));
                Assert.assertEquals(1,task.get("frameCount").getAsInt());Assert.assertEquals(7,result.getAsJsonArray("commands").size());return;
            }
            if(tiMode.equals("tt-silent")){
                JsonObject task=result.getAsJsonArray("tasks").get(0).getAsJsonObject();Assert.assertEquals("TIMED_OUT",result.get("status").getAsString());
                Assert.assertTrue(task.get("error").getAsString().contains("SERIAL_RESPONSE_TIMEOUT"));Assert.assertFalse(task.get("infoAttempted").getAsBoolean());
                Assert.assertEquals(6,result.getAsJsonArray("commands").size());
                String index=new String(Files.readAllBytes(job.resolve("data/vxworks/ports/port-COM7/task-index.json")),StandardCharsets.UTF_8);Assert.assertTrue(index.contains("SERIAL_RESPONSE_TIMEOUT"));
                DiagnosticReportGenerator.Manifest manifest=new DiagnosticReportGenerator.Manifest();
                manifest.vxworks=JsonParser.parseString("{ports:[{port:'COM7',artifact:'vxworks/ports/port-COM7/port-result.json'}]}").getAsJsonObject();
                new RemoteDiagnosticsReportWriter().write(manifest,job.resolve("data"));
                String csv=new String(Files.readAllBytes(job.resolve("data/vxworks/task-listesi.csv")),StandardCharsets.UTF_8);Assert.assertTrue(csv.contains("SERIAL_RESPONSE_TIMEOUT"));return;
            }
            if(tiMode.equals("silent")){
                Assert.assertFalse(result.toString(),result.get("completed").getAsBoolean());Assert.assertTrue(result.get("timedOut").getAsBoolean());
                Assert.assertEquals(1,result.get("attemptedTaskCount").getAsInt());Assert.assertEquals(1,result.get("attemptedTaskInfoCount").getAsInt());Assert.assertEquals(0,result.get("taskInfosCollected").getAsInt());
                JsonObject task=result.getAsJsonArray("tasks").get(0).getAsJsonObject();Assert.assertTrue(task.get("infoError").getAsString().contains("SERIAL_RESPONSE_TIMEOUT"));
                Assert.assertTrue(new String(Files.readAllBytes(job.resolve("data").resolve(task.get("infoArtifact").getAsString())),StandardCharsets.UTF_8).contains("partial task info"));
                JsonArray commands=result.getAsJsonArray("commands");Assert.assertEquals("ti 0x42",commands.get(commands.size()-1).getAsJsonObject().get("command").getAsString());
                Assert.assertEquals(7,commands.size());return;
            }
            if(tiMode.equals("diagnostic-silent")){
                Assert.assertEquals("TIMED_OUT",result.get("status").getAsString());Assert.assertFalse(result.get("completed").getAsBoolean());
                Assert.assertEquals(3,result.get("attemptedTaskCount").getAsInt());
                JsonArray ops=result.getAsJsonArray("commands");Assert.assertEquals("whoami",ops.get(ops.size()-1).getAsJsonObject().get("command").getAsString());
                Assert.assertTrue(new String(Files.readAllBytes(job.resolve("data/vxworks/ports/port-COM7/diagnostics/whoami.txt")),StandardCharsets.UTF_8).contains("partial username"));
                JsonArray diagnostics=result.getAsJsonArray("diagnostics");Assert.assertTrue(diagnostics.get(diagnostics.size()-1).getAsJsonObject().get("error").getAsString().contains("SERIAL_RESPONSE_TIMEOUT"));return;
            }
            if(tiMode.equals("time-silent")){
                Assert.assertFalse(result.get("completed").getAsBoolean());Assert.assertTrue(result.get("timedOut").getAsBoolean());
                JsonArray ops=result.getAsJsonArray("commands");Assert.assertEquals("time",ops.get(ops.size()-1).getAsJsonObject().get("command").getAsString());
                Assert.assertTrue(new String(Files.readAllBytes(job.resolve("data/vxworks/ports/port-COM7/diagnostics/time.txt")),StandardCharsets.UTF_8).contains("partial time output"));
                Assert.assertFalse(Files.exists(job.resolve("data/vxworks/ports/port-COM7/diagnostics/moduleShow.txt")));
                Assert.assertTrue(activity,activity.contains("moduleShow | CALISTIRILMADI"));
                return;
            }
            Assert.assertTrue(result.toString(),result.get("completed").getAsBoolean());Assert.assertFalse(result.get("success").getAsBoolean());
            Assert.assertEquals(3,result.get("initialTaskCount").getAsInt());Assert.assertEquals(3,result.get("attemptedTaskCount").getAsInt());Assert.assertEquals(1,result.get("tracesWithFrames").getAsInt());
            boolean validInfo=Arrays.asList("normal","minus-one","unsigned-ones","auto-port","custom-unsupported","diagnostic-unsupported","custom-delayed","time-unsupported").contains(tiMode);
            Assert.assertEquals(3,result.get("attemptedTaskInfoCount").getAsInt());Assert.assertEquals(validInfo?2:0,result.get("taskInfosCollected").getAsInt());
            String prefix=wide?"0xffff8000000000":"0x";
            Assert.assertEquals(prefix+"42",result.getAsJsonArray("tasks").get(0).getAsJsonObject().get("taskId").getAsString());
            Assert.assertEquals("SELF_TRACE_UNSUPPORTED",result.getAsJsonArray("tasks").get(1).getAsJsonObject().get("error").getAsString());
            Assert.assertEquals("UNBREAKABLE_TASK",result.getAsJsonArray("tasks").get(2).getAsJsonObject().get("error").getAsString());
            Assert.assertTrue(result.get("networkAvailable").getAsBoolean());Assert.assertEquals(result.toString(),2,result.getAsJsonArray("socketRows").size());
            Assert.assertEquals("LISTEN",result.getAsJsonArray("socketRows").get(0).getAsJsonObject().get("state").getAsString());
            Assert.assertEquals(23,result.getAsJsonArray("socketRows").get(0).getAsJsonObject().get("localPort").getAsInt());
            Assert.assertEquals("UDP",result.getAsJsonArray("socketRows").get(1).getAsJsonObject().get("protocol").getAsString());
            JsonObject identity=result.getAsJsonObject("identity");
            Assert.assertEquals(!noIdentity,identity.get("collected").getAsBoolean());
            Assert.assertEquals(noIdentity?0:1,identity.getAsJsonArray("addresses").size());
            if(!noIdentity){
                Assert.assertEquals(wide?"192.0.2.198":"192.0.2.195",identity.getAsJsonArray("addresses").get(0).getAsString());
                Assert.assertEquals(wide?"02:00:00:00:00:11":"02:00:00:00:00:22",identity.getAsJsonArray("macAddresses").get(0).getAsString());
            }
            List<String> commands=new ArrayList<>();for(JsonElement item:result.getAsJsonArray("commands"))commands.add(item.getAsJsonObject().get("command").getAsString());
            Assert.assertEquals("version",commands.get(0));Assert.assertEquals("ifconfig",commands.get(1));
            int identityCommands=(wide&&!noIdentity)?1:2;
            if(identityCommands==2)Assert.assertEquals("ifShow",commands.get(2));
            Assert.assertEquals("fixtureIdentify",commands.get(identityCommands+1));
            int firstList=identityCommands+2;
            Assert.assertEquals("i",commands.get(firstList));
            Assert.assertEquals("tt "+prefix+"42",commands.get(firstList+1));
            Assert.assertEquals("ti "+prefix+"42",commands.get(firstList+2));
            Assert.assertEquals("tt "+prefix+"43",commands.get(firstList+3));
            Assert.assertEquals("ti "+prefix+"43",commands.get(firstList+4));
            Assert.assertEquals("tt "+prefix+"44",commands.get(firstList+5));
            Assert.assertEquals("ti "+prefix+"44",commands.get(firstList+6));
            Assert.assertEquals("i",commands.get(firstList+7));
            Assert.assertEquals(Arrays.asList("time","moduleShow","checkStack 0","taskShow 0,2","devs","whoami","w","tw "+prefix+"42","tw "+prefix+"44","inetstatShow"),commands.subList(firstList+8,firstList+18));
            Assert.assertFalse(commands.contains("."));Assert.assertFalse(commands.contains("tw "+prefix+"43"));Assert.assertEquals(1,Collections.frequency(commands,"memShow 0"));
            JsonArray diagnostics=result.getAsJsonArray("diagnostics");Assert.assertEquals(10,diagnostics.size());
            for(int n=0;n<diagnostics.size();n++){
                JsonObject op=diagnostics.get(n).getAsJsonObject();Assert.assertEquals(n==0||(!tiMode.equals("diagnostic-unsupported")&&!(tiMode.equals("time-unsupported")&&op.get("command").getAsString().equals("time"))),op.get("collected").getAsBoolean());
                Assert.assertTrue(Files.isRegularFile(job.resolve("data").resolve(op.get("artifact").getAsString())));
            }
            String timeText=new String(Files.readAllBytes(job.resolve("data/vxworks/ports/port-"+port+"/diagnostics/time.txt")),StandardCharsets.UTF_8);
            String moduleText=new String(Files.readAllBytes(job.resolve("data/vxworks/ports/port-"+port+"/diagnostics/moduleShow.txt")),StandardCharsets.UTF_8);
            if(!tiMode.equals("diagnostic-unsupported")){
                Assert.assertTrue(timeText,timeText.contains(tiMode.equals("time-unsupported")?"unknown symbol name 'time'":"value = 1791988800"));
                Assert.assertTrue(moduleText,moduleText.contains("fixtureApp.out"));
            }
            DiagnosticReportGenerator.Manifest diagnosticManifest=new DiagnosticReportGenerator.Manifest();
            diagnosticManifest.vxworks=JsonParser.parseString("{ports:[{port:'"+port+"',artifact:'vxworks/ports/port-"+port+"/port-result.json'}]}").getAsJsonObject();
            new RemoteDiagnosticsReportWriter().write(diagnosticManifest,job.resolve("data"));
            String summary=new String(Files.readAllBytes(job.resolve("data/vxworks/SONUC.txt")),StandardCharsets.UTF_8);
            Assert.assertTrue(summary,summary.contains("time: "));Assert.assertTrue(summary,summary.contains("moduleShow: "));
            for(JsonElement item:result.getAsJsonArray("tasks")){
                JsonObject task=item.getAsJsonObject();Assert.assertTrue(task.get("infoAttempted").getAsBoolean());
                String infoPath=task.get("infoArtifact").getAsString();Assert.assertTrue(infoPath.endsWith("ti-"+task.get("taskId").getAsString()+".txt"));
                Assert.assertTrue(Files.isRegularFile(job.resolve("data").resolve(infoPath)));
                if(tiMode.equals("unsupported"))Assert.assertEquals("TASK_INFO_UNSUPPORTED",task.get("infoError").getAsString());
                else if(tiMode.equals("unconfirmed"))Assert.assertTrue(task.get("infoError").getAsString().startsWith("TASK_INFO_NOT_CONFIRMED"));
            }
            if(validInfo){
                Assert.assertTrue(result.getAsJsonArray("tasks").get(0).getAsJsonObject().get("infoCollected").getAsBoolean());
                Assert.assertTrue(result.getAsJsonArray("tasks").get(1).getAsJsonObject().get("infoCollected").getAsBoolean());
                Assert.assertTrue(result.getAsJsonArray("tasks").get(2).getAsJsonObject().get("infoError").getAsString().startsWith("TASK_INFO_FAILED"));
            }
            int traces=0;for(JsonElement item:result.getAsJsonArray("commands")){JsonObject op=item.getAsJsonObject();if(op.get("command").getAsString().startsWith("tt ")){traces++;Assert.assertTrue(op.get("command").getAsString().matches("tt 0x[0-9a-f]+"));}}
            Assert.assertEquals(3,traces);Assert.assertEquals(0,result.getAsJsonArray("addedTaskIds").size());Assert.assertEquals(0,result.getAsJsonArray("removedTaskIds").size());
        }finally{FileSupport.deleteTree(job);}
    }
    private void worker(boolean list,boolean fakeTimeout) throws Exception {
        worker(list,fakeTimeout,"manual");
    }
    private void worker(boolean list,boolean fakeTimeout,String discoveryMode) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path job=createJob();
        try{
            for(String resource:RemotePowerShellTransport.HELPER_RESOURCES)script(job.resolve(resource),PowerShell.resource(resource));
            script(job.resolve("jdk-discovery.ps1"),PowerShell.resource("jdk-discovery.ps1")+"\nfunction Get-JdkInventory {param($roots,$targets)[pscustomobject]@{candidates=@();plans=@();notes=@()}}\n");
            boolean automatic=!discoveryMode.equals("manual");
            if(automatic){
                String extras=PowerShell.resource("remote-extras.ps1");Assert.assertTrue(extras.contains("[IO.Ports.SerialPort]::GetPortNames()"));
                String ports=discoveryMode.equals("empty")?"@()":discoveryMode.equals("failure")?"$(throw ''FIXTURE_PORT_ENUMERATION_FAILED'')":discoveryMode.equals("invalid")?"@(''COM20'',''COM0'',''COM65536'',''LPT1'',''COM7;whoami'')":"@(''COM500'',''COM7'',''com20'',''COM7'')";
                script(job.resolve("remote-extras.ps1"),extras.replace("[IO.Ports.SerialPort]::GetPortNames()",ports));
            }
            script(job.resolve("process-scan.ps1"),"'{\"processes\":[],\"errors\":[],\"elevated\":false}'");script(job.resolve("system-info.ps1"),"'{\"errors\":[]}'");
            if(fakeTimeout){String ping=PowerShell.resource("remote-ping.ps1");Assert.assertTrue(ping.contains("$reply=$state.task.GetAwaiter().GetResult()"));script(job.resolve("remote-ping.ps1"),ping.replace("$reply=$state.task.GetAwaiter().GetResult()","$null=$state.task.GetAwaiter().GetResult();$reply=[pscustomobject]@{Status=[Net.NetworkInformation.IPStatus]::TimedOut;Address=$null}"));}
            String vx="param($JobRoot,$RequestFile,$PortName)\nif($PortName -eq 'COM7'){throw 'FIXTURE_PORT_BUSY'};$folder=Join-Path $JobRoot ('data/vxworks/ports/port-'+$PortName);[void][IO.Directory]::CreateDirectory($folder);$value=[pscustomobject]@{port=$PortName;identity=@{addresses=@('192.0.2.195');macAddresses=@('02:00:00:00:00:22');collected=$true};success=$true;completed=$true;status='COLLECTED';initialTaskCount=0;attemptedTaskCount=0;tracesWithFrames=0;networkAvailable=$true;tasks=@();commands=@();socketRows=@()};$json=$value|ConvertTo-Json -Depth 5;[IO.File]::WriteAllText((Join-Path $folder 'port-result.json'),$json);$json";
            vx=vx.replace("$value=[pscustomobject]@{port=$PortName;","[IO.File]::WriteAllText((Join-Path $folder 'custom-command.txt'),'FIXTURE_CUSTOM_OUTPUT');$value=[pscustomobject]@{port=$PortName;customCommand=@{attempted=$true;collected=$true;artifact=('vxworks/ports/port-'+$PortName+'/custom-command.txt');error=$null};");
            script(job.resolve("remote-vxworks.ps1"),vx);
            CollectorConfiguration c=new CollectorConfiguration();c.remoteWindowsDiagnostics=false;c.installationPath=job.toString();c.applicationPath=".";c.resourceMonitoringSeconds=0;c.collectGcStats=false;c.remoteTimeoutSeconds=60;
            c.ping.enabled=true;c.ping.addresses.add("127.0.0.1");c.ping.attempts=2;c.ping.intervalMillis=0;c.vxworks.enabled=true;
            if(automatic){
                c.vxworks.autoDiscoverPorts=true;c.vxworks.ports.add("COM99");
                Files.write(job.resolve("source.log"),"preserved application log".getBytes(StandardCharsets.UTF_8));
                CollectorConfiguration.LogDirectory log=new CollectorConfiguration.LogDirectory();log.sourcePath="source.log";log.base="installation";log.destination="auto-discovery-fixture";log.modifiedWithinHours=0;c.logs.add(log);
            }else c.vxworks.ports.addAll(Arrays.asList("COM7","COM9"));
            writeSettings(job,c,list?"list":"collect");CommandResult execution=run(job,"remote-worker.ps1",75000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(new String(Files.readAllBytes(job.resolve("complete.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            if(list){Assert.assertFalse(result.has("ping"));Assert.assertFalse(result.has("vxworks"));Assert.assertFalse(Files.exists(job.resolve("data/ping")));Assert.assertFalse(Files.exists(job.resolve("data/vxworks")));return;}
            JsonObject ping=result.getAsJsonObject("ping");Assert.assertTrue(ping.toString(),ping.get("success").getAsBoolean());Assert.assertEquals(fakeTimeout?0:2,ping.getAsJsonArray("results").get(0).getAsJsonObject().get("received").getAsInt());
            if(automatic){
                JsonObject vxResult=result.getAsJsonObject("vxworks");Assert.assertEquals("auto",vxResult.get("discoveryMode").getAsString());
                JsonArray detected=vxResult.getAsJsonArray("detectedPorts"),portResults=vxResult.getAsJsonArray("ports");
                Assert.assertFalse(Files.exists(job.resolve("data/vxworks/ports/port-COM99")));
                Assert.assertEquals("preserved application log",new String(Files.readAllBytes(job.resolve("data/logs/auto-discovery-fixture/source.log")),StandardCharsets.UTF_8));
                if(discoveryMode.equals("empty")||discoveryMode.equals("failure")){
                    Assert.assertFalse(vxResult.get("success").getAsBoolean());Assert.assertEquals(0,detected.size());Assert.assertEquals(0,portResults.size());Assert.assertTrue(vxResult.getAsJsonArray("errors").size()>0);
                    JsonObject operation=result.getAsJsonArray("operations").get(1).getAsJsonObject();Assert.assertEquals("serial-port-discovery",operation.get("type").getAsString());Assert.assertEquals(discoveryMode.equals("empty"),operation.get("success").getAsBoolean());
                    Assert.assertEquals(2,result.getAsJsonArray("operations").size());
                }else if(discoveryMode.equals("invalid")){
                    Assert.assertEquals(1,portResults.size());Assert.assertEquals("COM20",detected.get(0).getAsString());Assert.assertTrue(portResults.get(0).getAsJsonObject().get("success").getAsBoolean());
                    Assert.assertFalse(vxResult.get("success").getAsBoolean());Assert.assertEquals(4,vxResult.getAsJsonArray("errors").size());
                }else{
                    Assert.assertEquals(3,detected.size());Assert.assertEquals(3,portResults.size());
                    for(int i=0;i<3;i++){String expected=Arrays.asList("COM7","COM20","COM500").get(i);Assert.assertEquals(expected,detected.get(i).getAsString());Assert.assertEquals(expected,portResults.get(i).getAsJsonObject().get("port").getAsString());}
                    Assert.assertFalse(portResults.get(0).getAsJsonObject().get("success").getAsBoolean());Assert.assertTrue(portResults.get(1).getAsJsonObject().get("success").getAsBoolean());Assert.assertTrue(portResults.get(2).getAsJsonObject().get("success").getAsBoolean());
                    Assert.assertEquals(5,result.getAsJsonArray("operations").size());
                }
                DiagnosticReportGenerator.Manifest manifest=new DiagnosticReportGenerator.Manifest();manifest.vxworks=vxResult;new RemoteDiagnosticsReportWriter().write(manifest,job.resolve("data"));
                Assert.assertTrue(new String(Files.readAllBytes(job.resolve("data/vxworks/SONUC.txt")),StandardCharsets.UTF_8).contains("SUNUCUDA OTOMATİK KEŞİF"));
                return;
            }
            JsonArray ports=result.getAsJsonObject("vxworks").getAsJsonArray("ports");Assert.assertEquals(2,ports.size());Assert.assertFalse(ports.get(0).getAsJsonObject().get("success").getAsBoolean());Assert.assertTrue(ports.get(1).getAsJsonObject().get("success").getAsBoolean());
            Assert.assertEquals("192.0.2.195",ports.get(1).getAsJsonObject().getAsJsonObject("identity").getAsJsonArray("addresses").get(0).getAsString());
            JsonObject custom=ports.get(1).getAsJsonObject().getAsJsonObject("customCommand");Assert.assertTrue(custom.get("collected").getAsBoolean());
            Assert.assertEquals("FIXTURE_CUSTOM_OUTPUT",new String(Files.readAllBytes(job.resolve("data").resolve(custom.get("artifact").getAsString())),StandardCharsets.UTF_8));
            Assert.assertEquals(3,result.getAsJsonArray("operations").size());Assert.assertTrue(Files.isRegularFile(job.resolve("data/ping/results.csv")));Assert.assertTrue(Files.isRegularFile(job.resolve("data/vxworks/ports/port-COM9/port-result.json")));
            for(JsonElement item:result.getAsJsonArray("files")){JsonObject file=item.getAsJsonObject();Assert.assertEquals(file.get("sha256").getAsString().toLowerCase(Locale.ROOT),FileSupport.sha256(job.resolve("data").resolve(file.get("path").getAsString())));}
        }finally{FileSupport.deleteTree(job);}
    }
    private static Path createJob()throws Exception{Path job=Paths.get("C:/Temp","diagnostic-remote-"+UUID.randomUUID());Files.createDirectories(job);FileSupport.restrictDirectory(job);return job;}
    private static void writeSettings(Path job,CollectorConfiguration c,String action)throws Exception{
        try(RemoteConnection connection=new RemoteConnection("localhost","fixture\\user","C:/missing-jdk","unused-test-secret".toCharArray())){JsonObject settings=new RemoteCollectionOrchestrator(c).settings(action,Collections.emptySet(),connection);settings.addProperty("jobId",job.getFileName().toString().substring("diagnostic-remote-".length()));DiagnosticReportGenerator.writeJson(job.resolve("request.json"),settings);}
    }
    private static CommandResult run(Path job,String name,long timeout)throws Exception{return new ProcessCommandExecutor().run(Arrays.asList(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve(name).toString(),"-RequestFile",job.resolve("request.json").toString()),timeout,job.resolve("stdout.txt"),job.resolve("stderr.txt"));}
    private static String quote(Path path){return path.toString().replace("'","''");}
    private static void script(Path path,String text)throws Exception{byte[] body=text.getBytes(StandardCharsets.UTF_8),bom=new byte[body.length+3];bom[0]=(byte)0xef;bom[1]=(byte)0xbb;bom[2]=(byte)0xbf;System.arraycopy(body,0,bom,3,body.length);Files.write(path,bom);}
    private static byte[] bytes(InputStream input)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}
}
