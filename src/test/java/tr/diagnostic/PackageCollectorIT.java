package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Isolated distribution fixtures; never replaces the workspace/running JAR. */
public class PackageCollectorIT {
    @Test public void successfulPublicationReplacesJarAndCreatesSingleJarDistribution() throws Exception {publish("normal");}
    @Test public void runningCollectorPreventsPublicationAndKeepsPreviousJar() throws Exception {publish("active");}
    @Test public void lockedDestinationFailurePreservesOldJarAndRemovesCandidate() throws Exception {publish("locked");}
    private void publish(String mode)throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Path root=Files.createTempDirectory("diagnostic-package-test-");
        try{
            Files.createDirectories(root.resolve("scripts"));Files.createDirectories(root.resolve("target"));
            Files.copy(Paths.get("scripts/Package-Collector.ps1"),root.resolve("scripts/Package-Collector.ps1"));
            byte[] old="old jar bytes".getBytes(StandardCharsets.US_ASCII),next="new jar bytes".getBytes(StandardCharsets.US_ASCII);
            Files.write(root.resolve("java-diagnostic-collector.jar"),old);Files.write(root.resolve("target/java-diagnostic-collector.jar"),next);
            String literal=root.toString().replace("'","''");
            String body="$ErrorActionPreference='Stop';$env:PSModulePath=[IO.Path]::Combine($PSHOME,'Modules');$fixtureRoot='"+literal+"';$fixtureMode='"+mode+"';"
                +"function Get-CimInstance {param($ClassName,$Filter,$ErrorAction)if($fixtureMode -eq 'active'){[pscustomobject]@{ProcessId=12345;CommandLine=('javaw.exe -jar \"'+(Join-Path $fixtureRoot 'java-diagnostic-collector.jar')+'\"')}}};"
                +"$lock=$null;$failed=$false;$message='';try{if($fixtureMode -eq 'locked'){$lock=[IO.File]::Open((Join-Path $fixtureRoot 'java-diagnostic-collector.jar'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)};"
                +"$null=& (Join-Path $fixtureRoot 'scripts/Package-Collector.ps1') -SkipBuild}catch{$failed=$true;$message=$_.Exception.Message}finally{if($lock){$lock.Dispose()}};"
                +"[pscustomobject]@{failed=$failed;message=$message}|ConvertTo-Json -Compress";
            CommandResult execution=new PowerShell(new ProcessCommandExecutor()).run(body,20000);Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=JsonParser.parseString(execution.stdout.trim()).getAsJsonObject();
            Assert.assertEquals(result.toString(),!mode.equals("normal"),result.get("failed").getAsBoolean());
            Assert.assertArrayEquals(mode.equals("normal")?next:old,Files.readAllBytes(root.resolve("java-diagnostic-collector.jar")));
            Assert.assertEquals(mode.equals("normal"),Files.isRegularFile(root.resolve("target/diagnostic-collector-single-jar.zip")));
            try(java.util.stream.Stream<Path> paths=Files.list(root)){Assert.assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith(".collector-publish-")));}
            if(mode.equals("active"))Assert.assertTrue(result.toString(),result.get("message").getAsString().contains("Collector JAR is running (PID 12345)"));
            if(mode.equals("locked"))Assert.assertTrue(result.toString(),result.get("message").getAsString().contains("Replace"));
            if(mode.equals("normal"))try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(root.resolve("target/diagnostic-collector-single-jar.zip").toFile())){Assert.assertEquals(1,zip.size());Assert.assertNotNull(zip.getEntry("java-diagnostic-collector.jar"));}
        }finally{FileSupport.deleteTree(root);}
    }
}
