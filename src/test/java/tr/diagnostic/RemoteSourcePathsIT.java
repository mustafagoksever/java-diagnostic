package tr.diagnostic;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.*;

/** Real Windows path resolution through the production PowerShell function. */
public class RemoteSourcePathsIT {
    @Test public void missingRootsNeverResolveAgainstTheHelperWorkingDirectory() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path root = Files.createTempDirectory("remote-source-paths-");
        try {
            Path worker = Files.write(root.resolve("worker.ps1"), PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
            String script = "$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+worker.toString().replace("'","''")+"',[ref]$tokens,[ref]$issues);"
                + "$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Resolve-CollectionPaths'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
                + "$settings=[pscustomobject]@{installationDirectory='';applicationDirectory='auto';beforeLogCopyBatch='';logs=@()};$dumpOnly=Resolve-CollectionPaths;"
                + "$settings.logs=@(@{base='application';path='Log'});$missing=$false;try{$null=Resolve-CollectionPaths}catch{$missing=$_.Exception.Message -like 'REMOTE_ROOT_REQUIRED*'};"
                + "$settings.installationDirectory='.';$relative=$false;try{$null=Resolve-CollectionPaths}catch{$relative=$_.Exception.Message -like 'REMOTE_ROOT_REQUIRED*'};"
                + "$settings.installationDirectory='';$settings.logs=@(@{path='D:/fixture-logs'});$absolute=Resolve-CollectionPaths;"
                + "$settings.applicationDirectory='D:/fixture-app';$settings.beforeLogCopyBatch='Log/collect-logs.bat';$settings.logs=@(@{base='application';path='Log'});$app=Resolve-CollectionPaths;"
                + "[pscustomobject]@{dumpRootUnset=($null -eq $dumpOnly.installationDirectory -and $null -eq $dumpOnly.applicationDirectory);missingRejected=$missing;relativeRejected=$relative;absoluteLogRootUnset=($null -eq $absolute.installationDirectory);application=$app.applicationDirectory}|ConvertTo-Json -Compress";
            CommandResult result = new PowerShell(new ProcessCommandExecutor()).run(script, 10000);
            Assert.assertTrue(result.error(), result.success());
            JsonObject evidence = JsonParser.parseString(result.stdout.trim()).getAsJsonObject();
            for (String key : new String[]{"dumpRootUnset", "missingRejected", "relativeRejected", "absoluteLogRootUnset"}) Assert.assertTrue(evidence.toString(), evidence.get(key).getAsBoolean());
            Assert.assertEquals("D:\\fixture-app", evidence.get("application").getAsString());
        } finally { FileSupport.deleteTree(root); }
    }
}
