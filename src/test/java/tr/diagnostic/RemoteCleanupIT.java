package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual Windows file locks; cleanup may touch only the verified UUID job. */
public class RemoteCleanupIT {
    @Test public void transientLockIsRetriedAndSiblingIsPreserved() throws Exception { cleanup(false); }
    @Test public void permanentLockIsReportedAndCanBeRemovedAfterRelease() throws Exception { cleanup(true); }
    private void cleanup(boolean permanent) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path root=Files.createDirectories(Paths.get("C:/Temp","diagnostic-remote-"+UUID.randomUUID()));
        Path local=Files.createTempDirectory("cleanup-test-");
        Path sibling=Files.write(local.resolve("unrelated.txt"),new byte[]{1});
        Path locked=Files.write(Files.createDirectories(root.resolve("data/vxworks/ports/port-COM9/network")).resolve("locked.txt"),new byte[]{1});
        Process child=null;
        try {
            Path source=Files.write(local.resolve("client.ps1"),PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8));
            String script="$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+q(source)+"',[ref]$tokens,[ref]$issues);"
                +"$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Cleanup'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                +"$request=[pscustomobject]@{host='localhost'};$selectedShare='C$';$selectedShareSuffix='Temp';$jobId='"+root.getFileName().toString().substring(18)+"';"
                +"$shareRoot='Microsoft.PowerShell.Core\\FileSystem::\\\\localhost\\C$\\Temp\\"+root.getFileName()+"';$completed=$true;"
                +"[IO.File]::WriteAllText('"+q(local.resolve("ready"))+"','ready');$watch=[Diagnostics.Stopwatch]::StartNew();"
                +"try{Cleanup $false;@{removed=$true;elapsed=$watch.ElapsedMilliseconds}|ConvertTo-Json -Compress}catch{@{removed=$false;error=$_.Exception.Message;elapsed=$watch.ElapsedMilliseconds}|ConvertTo-Json -Compress}";
            Path ps=Files.write(local.resolve("run.ps1"),script.getBytes(StandardCharsets.UTF_8));
            try(InputStream lock=new FileInputStream(locked.toFile())) {
                // Confirm this JVM really denies delete sharing on this platform.
                boolean denied=false;try{Files.delete(locked);}catch(IOException e){denied=true;}
                Assert.assertTrue("Fixture must hold a real Windows delete lock",denied);
                child=new ProcessBuilder(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",ps.toString()).redirectOutput(local.resolve("out").toFile()).redirectError(local.resolve("err").toFile()).start();
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(!Files.exists(local.resolve("ready")) && child.isAlive() && System.nanoTime()<until)Thread.sleep(50);
                Assert.assertTrue("Cleanup reached",Files.exists(local.resolve("ready")));
                if(permanent)Assert.assertTrue(child.waitFor(15,TimeUnit.SECONDS));
                else {Thread.sleep(900);Assert.assertTrue("Retry keeps helper alive",child.isAlive());}
            }
            Assert.assertTrue(child.waitFor(15,TimeUnit.SECONDS));
            String err=new String(Files.readAllBytes(local.resolve("err")),StandardCharsets.UTF_8);
            Assert.assertEquals(err,0,child.exitValue());
            JsonObject result=JsonParser.parseString(new String(Files.readAllBytes(local.resolve("out")),StandardCharsets.UTF_8)).getAsJsonObject();
            Assert.assertEquals(result.toString(),!permanent,result.get("removed").getAsBoolean());
            Assert.assertEquals(permanent,Files.exists(root));
            if(permanent){Assert.assertTrue(result.has("error"));Assert.assertTrue(result.get("elapsed").getAsLong()>=4500);}
            Assert.assertTrue(Files.isRegularFile(sibling));
        } finally {if(child!=null&&child.isAlive())child.destroyForcibly();if(Files.exists(root))FileSupport.deleteTree(root);FileSupport.deleteTree(local);}
    }
    private static String q(Path p){return p.toString().replace("'","''");}
}
