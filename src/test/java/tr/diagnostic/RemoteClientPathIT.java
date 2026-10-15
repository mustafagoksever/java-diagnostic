package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Real Windows PowerShell UNC/SMB file handling; WMI and credentials are fixtures. */
public class RemoteClientPathIT {
    @Test public void hiddenOrUnavailableWorkerMetadataNeverMeansExitedAndPidReuseIsDetected() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path local=Files.createTempDirectory("diagnostic-worker-liveness-");
        try {
            Path source=Files.write(local.resolve("source.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Worker-Status'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$request=[pscustomobject]@{host='fixture'};$workerPid=42;$remoteRoot='C:\\Temp\\diagnostic-remote-fixture';$workerStartTicks=$null;"
                +"function Get-WmiObject { param($Class,$Filter,$ComputerName,$Credential,$Authentication,$Impersonation) if($fixtureThrow){throw 'Access denied'};return $fixtureWorker };"
                +"$fixtureWorker=[pscustomobject]@{CommandLine=$null};$hidden=Worker-Status;"
                +"$fixtureThrow=$true;$denied=Worker-Status;$fixtureThrow=$false;"
                +"$date=[DateTime]::UtcNow;$workerStartTicks=($date.Ticks+5).ToString();$fixtureWorker=[pscustomobject]@{CreationDate=$date;CommandLine=$null};$running=Worker-Status;"
                +"$workerStartTicks=($date.Ticks-10000000).ToString();$reused=Worker-Status;$fixtureWorker=$null;$absent=Worker-Status;"
                +"[pscustomobject]@{hidden=$hidden;denied=$denied;running=$running;reused=$reused;absent=$absent}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();
            Assert.assertEquals("UNKNOWN",result.get("hidden").getAsString());Assert.assertEquals("UNKNOWN",result.get("denied").getAsString());
            Assert.assertEquals("RUNNING",result.get("running").getAsString());Assert.assertEquals("REUSED",result.get("reused").getAsString());Assert.assertEquals("ABSENT",result.get("absent").getAsString());
        }finally{FileSupport.deleteTree(local);}
    }
    @Test public void ordinaryShareMapsToTempWithoutAdminShareAndWrongRootsAreRejected() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path local=Files.createTempDirectory("diagnostic-share-layout-");
        try {
            Path source=Files.write(local.resolve("source.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Share-Layout'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$workRoot='C:\\Temp';$request=[pscustomobject]@{host='server'};$jobId='11111111-1111-1111-1111-111111111111';"
                +"$ordinary=Share-Layout 'Temp' 'C:\\Temp';$admin=Share-Layout 'C$' 'C:\\';$rejected=$false;try{Share-Layout 'wrong' 'C:\\Temp2'|Out-Null}catch{$rejected=$true};"
                +"$workRoot='D:\\Temp';$dOrdinary=Share-Layout 'DTemp' 'D:\\Temp';$dAdmin=Share-Layout 'D$' 'D:\\';$crossDrive=$false;try{Share-Layout 'wrong' 'C:\\'|Out-Null}catch{$crossDrive=$true};"
                +"[pscustomobject]@{ordinary=$ordinary.job;admin=$admin.job;wrongRootRejected=$rejected;dOrdinary=$dOrdinary.job;dAdmin=$dAdmin.job;crossDriveRejected=$crossDrive}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();
            Assert.assertEquals("\\\\server\\Temp\\diagnostic-remote-11111111-1111-1111-1111-111111111111",result.get("ordinary").getAsString());
            Assert.assertEquals("\\\\server\\C$\\Temp\\diagnostic-remote-11111111-1111-1111-1111-111111111111",result.get("admin").getAsString());
            Assert.assertTrue(result.get("wrongRootRejected").getAsBoolean());
            Assert.assertEquals("\\\\server\\DTemp\\diagnostic-remote-11111111-1111-1111-1111-111111111111",result.get("dOrdinary").getAsString());
            Assert.assertEquals("\\\\server\\D$\\Temp\\diagnostic-remote-11111111-1111-1111-1111-111111111111",result.get("dAdmin").getAsString());
            Assert.assertTrue(result.get("crossDriveRejected").getAsBoolean());
        }finally{FileSupport.deleteTree(local);}
    }
    @Test public void lowServerDiskSpaceSelectsDAndUnavailableDisksFailBeforeJobCreation() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path local=Files.createTempDirectory("diagnostic-disk-fallback-");
        try {
            Path source=Files.write(local.resolve("source.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Select-WorkSpace'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$request=[pscustomobject]@{host='server'};function Emit($value){};function Remove-PSDrive{param($Name,$ErrorAction)};"
                +"function Get-WmiObject {param($Class,$Filter,$ComputerName,$Credential,$Authentication,$Impersonation) if($Class -ne 'Win32_LogicalDisk'){throw 'Unexpected query'};if($Filter -eq \"DeviceID='C:'\"){return [pscustomobject]@{FreeSpace=$cFree}};if($Filter -eq \"DeviceID='D:'\" -and $dExists){return [pscustomobject]@{FreeSpace=$dFree}};return $null};"
                +"function Connect-WorkShare {if($denied -eq $workRoot){throw 'SMB access denied'};return [pscustomobject]@{name=$workRoot;job=($workRoot+'\\diagnostic-remote-fixture')}};"
                +"$cFree=1GB;$dFree=1GB;$dExists=$true;$denied=$null;$preferred=(Select-WorkSpace).name;"
                +"$cFree=319MB;$fallback=(Select-WorkSpace).name;"
                +"$cFree=320MB;$boundary=(Select-WorkSpace).name;"
                +"$denied='C:\\Temp';$accessFallback=(Select-WorkSpace).name;"
                +"$cFree=1MB;$dFree=2MB;$bothLow=$false;try{Select-WorkSpace|Out-Null}catch{$bothLow=$_.Exception.Message.Contains('C:\\Temp') -and $_.Exception.Message.Contains('D:\\Temp') -and $_.Exception.Message.Contains('LOW_DISK_SPACE')};"
                +"$dExists=$false;$missing=$false;try{Select-WorkSpace|Out-Null}catch{$missing=$_.Exception.Message.Contains('Disk bulunamadi')};"
                +"$dExists=$true;$dFree=1GB;$denied='D:\\Temp';$deniedD=$false;try{Select-WorkSpace|Out-Null}catch{$deniedD=$_.Exception.Message.Contains('SMB access denied')};"
                +"[pscustomobject]@{preferred=$preferred;fallback=$fallback;boundary=$boundary;accessFallback=$accessFallback;bothLow=$bothLow;missing=$missing;deniedD=$deniedD}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(script,10000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();
            Assert.assertEquals("C:\\Temp",result.get("preferred").getAsString());Assert.assertEquals("D:\\Temp",result.get("fallback").getAsString());
            Assert.assertEquals("C:\\Temp",result.get("boundary").getAsString());Assert.assertEquals("D:\\Temp",result.get("accessFallback").getAsString());
            for(String flag:new String[]{"bothLow","missing","deniedD"})Assert.assertTrue(result.toString(),result.get(flag).getAsBoolean());
        }finally{FileSupport.deleteTree(local);}
    }
    @Test public void uncJobCanWriteDownloadVerifyAndCleanUpWithoutMixedDrivePaths() throws Exception {
        runClientFixture(null);
    }
    @Test public void failedWorkerStartRemovesTheEmptyJobFolder() throws Exception {
        runClientFixture("start");
    }
    @Test public void failedDownloadVerificationRemovesTheCompletedJobFolder() throws Exception {
        runClientFixture("hash");
    }
    @Test public void failedWmiConnectionIsExplicitInTheActivityLog() throws Exception {
        runClientFixture("wmi");
    }
    private void runClientFixture(String failure) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path local=Files.createTempDirectory("diagnostic-client-path-");
        String setup = "$fixtureFailConnection="+("wmi".equals(failure)?"$true":"$false")+";$fixtureFailStart="+("start".equals(failure)?"$true":"$false")+";$fixtureBadHash="+("hash".equals(failure)?"$true":"$false")+";"+
            "$fixtureIdentity=[Security.Principal.WindowsIdentity]::GetCurrent();"+
            "$fixtureParts=$fixtureIdentity.Name.Split('\\');"+
            "function New-PSDrive { param($Name,$PSProvider,$Root,$Credential) Microsoft.PowerShell.Management\\New-PSDrive -Name $Name -PSProvider $PSProvider -Root $Root };"+
            "function Get-WmiObject { param($Class,$Filter,[switch]$List,$ComputerName,$Credential,$Authentication,$Impersonation) " +
            "if($Class -eq 'Win32_ComputerSystem'){if($fixtureFailConnection){throw 'FIXTURE_WMI_CONNECTION_FAILED'};return [pscustomobject]@{Name=$env:COMPUTERNAME}};"+
            "if($Class -eq 'Win32_LogicalDisk'){return [pscustomobject]@{FreeSpace=1GB}};"+
            "if($Class -eq 'Win32_UserAccount'){return [pscustomobject]@{Name=$fixtureParts[-1];Domain=$fixtureParts[0];SID=$fixtureIdentity.User.Value}};"+
            "if($Class -eq 'Win32_Share'){if($Filter -eq \"Name='C$'\"){return [pscustomobject]@{Name='C$';Path='C:\\'}};return $null};"+
            "if($Class -eq 'Win32_Process' -and $List){$fixtureClass=[pscustomobject]@{};"+
            "$fixtureClass|Add-Member -MemberType ScriptMethod -Name Create -Value {param($command,$working,$startup) " +
            "if($fixtureFailStart){return [pscustomobject]@{ReturnValue=5;ProcessId=0}};"+
            "$fixtureRoot=[regex]::Match($command,'C:\\\\Temp\\\\diagnostic-remote-[0-9a-f-]{36}').Value;"+
            "if(!$fixtureRoot){throw 'Missing isolated fixture root'};"+
            "$fixtureRequest=Get-Content -LiteralPath ([IO.Path]::Combine($fixtureRoot,'request.json')) -Raw|ConvertFrom-Json;"+
            "if(!$fixtureRequest.nonInteractiveTransport -or !$fixtureRequest.interactiveUserSid -or !$fixtureRequest.interactiveUserName){throw 'Missing session identity'};"+
            "foreach($fixtureResource in @('remote-worker.ps1','remote-batch.ps1','process-scan.ps1','system-info.ps1','resource-monitor.ps1','remote-extras.ps1','remote-ping.ps1','remote-vxworks.ps1','jdk-discovery.ps1')){"+
            "if(![IO.File]::Exists([IO.Path]::Combine($fixtureRoot,$fixtureResource))){throw 'Helper not copied'}};"+
            "$fixtureData=[IO.Path]::Combine($fixtureRoot,'data');[void][IO.Directory]::CreateDirectory($fixtureData);"+
            "$fixtureFile=[IO.Path]::Combine($fixtureData,'sample.txt');[IO.File]::WriteAllText($fixtureFile,'UNC fixture payload');"+
            "$fixtureManifest=@{jobId=$fixtureRequest.jobId;files=@(@{path='sample.txt';size=(Get-Item -LiteralPath $fixtureFile).Length;sha256=(Sha256 $fixtureFile)})};"+
            "if($fixtureBadHash){$fixtureManifest.files[0].sha256='WRONG'};"+
            "$fixtureActivity=[IO.Path]::Combine($fixtureRoot,'activity-worker.jsonl');$fixtureUtf8=New-Object Text.UTF8Encoding($false);for($step=0;$step -lt 80;$step++){[IO.File]::AppendAllText($fixtureActivity,((@{timestampUtc='2026-10-14T00:00:00Z';message=('fixture-fast-step-'+$step)}|ConvertTo-Json -Compress)+\"`n\"),$fixtureUtf8)};"+
            "[IO.File]::WriteAllText([IO.Path]::Combine($fixtureRoot,'complete.json'),($fixtureManifest|ConvertTo-Json -Depth 8));"+
            "return [pscustomobject]@{ReturnValue=0;ProcessId=2147483646}};return $fixtureClass};return $null };";
        String script=setup+PowerShell.resource("remote-client.ps1");
        byte[] scriptBody=script.getBytes(StandardCharsets.UTF_8),scriptBom=new byte[scriptBody.length+3];scriptBom[0]=(byte)0xef;scriptBom[1]=(byte)0xbb;scriptBom[2]=(byte)0xbf;System.arraycopy(scriptBody,0,scriptBom,3,scriptBody.length);
        Path scriptFile=Files.write(local.resolve("client.ps1"),scriptBom);
        Path stdout=local.resolve("stdout.txt"),stderr=local.resolve("stderr.txt");
        Process helper=null;
        try {
            helper=new ProcessBuilder(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",scriptFile.toString()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            // Fixture account uses the current session's loopback SMB identity.
            JsonObject request=new JsonObject();request.addProperty("host","localhost");
            request.addProperty("username",System.getenv("USERDOMAIN")+"\\"+System.getenv("USERNAME"));request.addProperty("password","unused-fixture-secret");
            request.addProperty("localDirectory",local.resolve("download").toString());
            JsonObject settings=new JsonObject();settings.addProperty("timeoutSeconds",10);request.add("settings",settings);
            JsonObject resources=new JsonObject();
            for(String resource:RemotePowerShellTransport.HELPER_RESOURCES)resources.addProperty(resource,java.util.Base64.getEncoder().encodeToString(PowerShell.resource(resource).getBytes(StandardCharsets.UTF_8)));
            request.add("resources",resources);
            try(Writer writer=new OutputStreamWriter(helper.getOutputStream(),StandardCharsets.UTF_8)){writer.write(request.toString());writer.write('\n');}
            Assert.assertTrue("Client completes",helper.waitFor(30,TimeUnit.SECONDS));
            String output=new String(Files.readAllBytes(stdout),StandardCharsets.UTF_8),error=new String(Files.readAllBytes(stderr),StandardCharsets.UTF_8);
            Assert.assertEquals(output+error,failure==null?0:1,helper.exitValue());
            JsonObject result=null;
            for(String line:output.split("\\r?\\n")){JsonObject event=JsonParser.parseString(line).getAsJsonObject();if("result".equals(ResourceMonitor.text(event,"event")))result=event;}
            Assert.assertNotNull(output,result);
            if("wmi".equals(failure)){
                Assert.assertFalse(result.get("success").getAsBoolean());
                Assert.assertTrue(output,output.contains("[WMI] BAGLANILAMADI"));
                Assert.assertFalse(output,output.contains("[WMI] BAGLANDI"));
                Assert.assertFalse(output,output.contains("unused-fixture-secret"));return;
            }
            Assert.assertTrue(output,output.contains("[WMI] BAGLANDI"));
            Assert.assertTrue(output,output.contains("[SMB] BAGLANDI"));
            Assert.assertTrue(output,output.contains("[SMB] YAZMA DOGRULANDI"));
            Assert.assertTrue("Write confirmation must follow share connection",output.indexOf("[SMB] BAGLANDI") < output.indexOf("[SMB] YAZMA DOGRULANDI"));
            Assert.assertFalse("Share connection alone must not claim verified writes",output.contains("gecici dizine yazma dogrulandi"));
            if(failure!=null){
                Assert.assertFalse(result.toString(),result.get("success").getAsBoolean());
                Assert.assertTrue(result.toString(),result.get("error").getAsString().contains("start".equals(failure)?"WMI Create failed":"Download verification failed"));
                Path remoteRoot=Paths.get(result.get("remoteDirectory").getAsString());
                Assert.assertTrue(remoteRoot.toString().startsWith("C:\\Temp\\diagnostic-remote-"));
                Assert.assertFalse("Failed job was cleaned",Files.exists(remoteRoot));
                Assert.assertTrue("Failed job cleanup is logged: "+output,output.contains("[Temizlik] Basarisiz is") && output.contains("gecici") && output.contains("temizlendi"));
                Assert.assertTrue("Base Temp remains",Files.isDirectory(Paths.get("C:/Temp")));
                return;
            }
            Assert.assertTrue(result.toString(),result.get("success").getAsBoolean());
            Assert.assertTrue(result.toString(),result.get("cleanupSucceeded").getAsBoolean());
            Assert.assertTrue(result.toString(),result.get("remoteDirectory").getAsString().startsWith("C:\\Temp\\diagnostic-remote-"));
            Assert.assertEquals("UNC fixture payload",new String(Files.readAllBytes(local.resolve("download/sample.txt")),StandardCharsets.UTF_8));
            Assert.assertFalse("Credentials never go to output",output.contains("unused-fixture-secret"));
            Assert.assertTrue(output,output.contains("[Aktarim] TAMAMLANDI"));
            Assert.assertTrue(output,output.contains("[Temizlik] Sunucudaki gecici is klasoru temizlendi"));
            int forwarded=0;for(String line:output.split("\\r?\\n")){JsonObject event=JsonParser.parseString(line).getAsJsonObject();if(ResourceMonitor.text(event,"message").startsWith("fixture-fast-step-"))forwarded++;}
            Assert.assertEquals("Fast steps must reach the client exactly once",80,forwarded);
        } finally {
            if(helper!=null&&helper.isAlive()){helper.destroyForcibly();helper.waitFor(5,TimeUnit.SECONDS);}
            FileSupport.deleteTree(local);
        }
    }
}
