package tr.diagnostic;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

/** Real PowerShell journal reader: no network or physical serial ports. */
public class RemoteActivityIT {
    @Test public void fastStepsAndPartialUtf8RecordsAreForwardedOnceWithoutLoss() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-activity-");
        try{
            Path source=Files.write(root.resolve("client.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            Path journal=root.resolve("activity-worker.jsonl");List<String> records=new ArrayList<>();
            for(int i=0;i<600;i++)records.add(record("adım-"+i));
            byte[] complete=(String.join("\n",records)+"\n").getBytes(StandardCharsets.UTF_8);
            byte[] partial=record("son-şöğü").getBytes(StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();bytes.write(complete);bytes.write(partial,0,partial.length-3);
            Files.write(journal,bytes.toByteArray());
            String setup="$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+quote(source)+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Forward-Activity'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$activityOffsets=@{};$records=New-Object 'Collections.Generic.List[string]';function Share-Path($relative){return '"+quote(root)+"'};function Emit($entry){$records.Add([string]$entry.message)};"
                +"Forward-Activity;$first=$records.Count;Forward-Activity;$second=$records.Count;"
                +"$tail=[Convert]::FromBase64String('"+Base64.getEncoder().encodeToString(Arrays.copyOfRange(partial,partial.length-3,partial.length))+"');$out=[IO.File]::Open('"+quote(journal)+"',[IO.FileMode]::Append);try{$out.Write($tail,0,$tail.Length);$out.WriteByte(10)}finally{$out.Dispose()};"
                +"Forward-Activity;Forward-Activity;[pscustomobject]@{first=$first;second=$second;final=$records.Count;messages=@($records.ToArray())}|ConvertTo-Json -Depth 4 -Compress";
            CommandResult result=new PowerShell(new ProcessCommandExecutor()).run(setup,15000);
            Assert.assertTrue(result.error(),result.success());JsonObject data=JsonParser.parseString(result.stdout.trim()).getAsJsonObject();
            Assert.assertEquals(600,data.get("first").getAsInt());Assert.assertEquals(600,data.get("second").getAsInt());Assert.assertEquals(601,data.get("final").getAsInt());
            Set<String> messages=new HashSet<>();for(JsonElement message:data.getAsJsonArray("messages"))messages.add(message.getAsString());
            for(int i=0;i<600;i++){Assert.assertTrue(messages.contains("adım-"+i));Assert.assertEquals("Same timestamp steps must stay in order","adım-"+i,data.getAsJsonArray("messages").get(i).getAsString());}
            Assert.assertTrue(messages.contains("son-şöğü"));
        }finally{FileSupport.deleteTree(root);}
    }
    @Test public void finalDrainContinuesPastAFullBatchWithoutMessages() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-activity-drain-");
        try{
            Path source=Files.write(root.resolve("client.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            byte[] padding=new byte[1024*1024];Arrays.fill(padding,(byte)' ');padding[padding.length-1]=(byte)'\n';
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();output.write(padding);output.write((record("last-batch-step")+"\n").getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("activity-worker.jsonl"),output.toByteArray());
            String setup="$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+quote(source)+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Forward-Activity'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$activityOffsets=@{};$records=New-Object 'Collections.Generic.List[string]';function Share-Path($relative){return '"+quote(root)+"'};function Emit($entry){$records.Add([string]$entry.message)};"
                +"$iterations=0;do{Forward-Activity;if(++$iterations -gt 5){throw 'Drain did not terminate'}}while($activityBytesRead -gt 0);[pscustomobject]@{iterations=$iterations;messages=@($records.ToArray())}|ConvertTo-Json -Depth 4 -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(setup,15000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject data=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();Assert.assertEquals(3,data.get("iterations").getAsInt());
            Assert.assertEquals(1,data.getAsJsonArray("messages").size());Assert.assertEquals("last-batch-step",data.getAsJsonArray("messages").get(0).getAsString());
        }finally{FileSupport.deleteTree(root);}
    }
    @Test public void lockedJournalIsRetriedAndInvalidRecordsDoNotDiscardOtherSteps() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-activity-recovery-");
        try{
            Path source=Files.write(root.resolve("client.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            Path locked=Files.write(root.resolve("activity-worker.jsonl"),(record("worker-step")+"\n").getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("activity-COM7.jsonl"),(record("port-before")+"\ninvalid-json\n"+record("port-after")+"\n").getBytes(StandardCharsets.UTF_8));
            String setup="$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+quote(source)+"',[ref]$tokens,[ref]$issues);"
                +"foreach($name in @('Activity-Warning','Forward-Activity')){$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq $name},$true);. ([scriptblock]::Create($fn.Extent.Text))};"
                +"$activityOffsets=@{};$activityWarnings=@{};$warnings=@();$records=New-Object 'Collections.Generic.List[string]';$noticeCount=0;"
                +"function Share-Path($relative){return '"+quote(root)+"'};function Emit($entry){if($entry.message.StartsWith('[Gunluk]')){$script:noticeCount++}else{$records.Add([string]$entry.message)}};"
                +"$hold=[IO.File]::Open('"+quote(locked)+"',[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);"
                +"try{Forward-Activity;$first=$records.Count;Forward-Activity;$second=$records.Count}finally{$hold.Dispose()};"
                +"Forward-Activity;Forward-Activity;[pscustomobject]@{first=$first;second=$second;messages=@($records.ToArray());warnings=@($warnings);noticeCount=$noticeCount}|ConvertTo-Json -Depth 4 -Compress";
            CommandResult result=new PowerShell(new ProcessCommandExecutor()).run(setup,15000);
            Assert.assertTrue(result.error(),result.success());JsonObject data=JsonParser.parseString(result.stdout.trim()).getAsJsonObject();
            Assert.assertEquals(2,data.get("first").getAsInt());Assert.assertEquals(2,data.get("second").getAsInt());
            JsonArray messages=data.getAsJsonArray("messages");Assert.assertEquals(3,messages.size());
            Assert.assertEquals("port-before",messages.get(0).getAsString());Assert.assertEquals("port-after",messages.get(1).getAsString());Assert.assertEquals("worker-step",messages.get(2).getAsString());
            Assert.assertEquals("Each issue should be reported once",2,data.get("noticeCount").getAsInt());Assert.assertEquals(2,data.getAsJsonArray("warnings").size());
        }finally{FileSupport.deleteTree(root);}
    }
    private static String record(String message){JsonObject entry=new JsonObject();entry.addProperty("timestampUtc","2026-10-14T00:00:00Z");entry.addProperty("message",message);return entry.toString();}
    private static String quote(Path path){return path.toString().replace("'","''");}
}
