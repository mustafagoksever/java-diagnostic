package tr.diagnostic;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.*;

/** Exercises production worker functions locally without WMI, SMB or a server. */
public class RemoteLogCollectionIT {
    @Test public void boundsDatesAndCountsNestedFailuresInTheSummary() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path root = Files.createTempDirectory("diagnostic-log-filter-");
        try {
            Path worker = Files.write(root.resolve("worker.ps1"), PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
            String script = "$ErrorActionPreference='Stop';$tokens=$null;$issues=$null;"
                + "$ast=[Management.Automation.Language.Parser]::ParseFile('" + quote(worker) + "',[ref]$tokens,[ref]$issues);if($issues.Count){throw $issues[0]};"
                + "foreach($name in @('Copy-Log','Write-CollectionSummary')){$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq $name},$true);. ([scriptblock]::Create($fn.Extent.Text))};"
                + "$dataRoot='" + quote(root) + "';$messages=New-Object 'Collections.Generic.List[string]';"
                + "function Deadline {};function Ensure-Space {};function Relative($p){return $p.Substring($dataRoot.Length+1)};function Activity([string]$m){$messages.Add($m)};"
                + "$now=[DateTime]::UtcNow;$cutoff=$now.AddHours(-48);"
                + "$spec=[pscustomobject]@{modifiedWithinHours=48;extensions=@('.log');maxFileSizeBytes=16};"
                + "$recent=Join-Path $dataRoot 'recent.log';$future=Join-Path $dataRoot 'future.log';$old=Join-Path $dataRoot 'old.log';$large=Join-Path $dataRoot 'large.log';"
                + "[IO.File]::WriteAllText($recent,'recent');[IO.File]::WriteAllText($future,'future');[IO.File]::WriteAllText($old,'old');[IO.File]::WriteAllBytes($large,(New-Object byte[] 32));"
                + "[IO.File]::SetLastWriteTimeUtc($future,$now.AddDays(1));[IO.File]::SetLastWriteTimeUtc($old,$now.AddHours(-49));"
                + "$before=[IO.File]::ReadAllText($future);$recentCopy=Copy-Log $recent (Join-Path $dataRoot 'copies/recent.log') $spec $cutoff;"
                + "$futureCopy=Copy-Log $future (Join-Path $dataRoot 'copies/future.log') $spec $cutoff;"
                + "$oldCopy=Copy-Log $old (Join-Path $dataRoot 'copies/old.log') $spec $cutoff;"
                + "$largeCopy=Copy-Log $large (Join-Path $dataRoot 'copies/large.log') $spec $cutoff;"
                + "$spec.modifiedWithinHours=0;$unfiltered=Copy-Log $future (Join-Path $dataRoot 'copies/unfiltered.log') $spec ([DateTime]::MinValue);"
                + "$result=[pscustomobject]@{errors=@('root issue');operations=@(@{success=$false},@{success=$true});jvms=@(@{operations=@(@{success=$false},@{success=$true})});logs=@($recentCopy,$futureCopy,$largeCopy)};"
                + "Write-CollectionSummary;[pscustomobject]@{recent=$recentCopy;future=$futureCopy;oldFiltered=($null -eq $oldCopy);large=$largeCopy;unfiltered=$unfiltered;"
                + "futureAbsent=!(Test-Path -LiteralPath (Join-Path $dataRoot 'copies/future.log'));sourceUnchanged=([IO.File]::ReadAllText($future) -eq $before -and [IO.File]::GetLastWriteTimeUtc($future) -eq $now.AddDays(1));summary=$messages[$messages.Count-1]}|ConvertTo-Json -Depth 5 -Compress";
            CommandResult run = new PowerShell(new ProcessCommandExecutor()).run(script, 15000);
            Assert.assertTrue(run.error(), run.success());
            JsonObject evidence = JsonParser.parseString(run.stdout.trim()).getAsJsonObject();
            Assert.assertEquals("COPIED", evidence.getAsJsonObject("recent").get("status").getAsString());
            Assert.assertEquals("SKIPPED_FUTURE_TIMESTAMP", evidence.getAsJsonObject("future").get("status").getAsString());
            Assert.assertTrue(evidence.getAsJsonObject("future").get("error").getAsString().contains("FUTURE_TIMESTAMP"));
            Assert.assertEquals("SKIPPED_SIZE", evidence.getAsJsonObject("large").get("status").getAsString());
            Assert.assertEquals("COPIED", evidence.getAsJsonObject("unfiltered").get("status").getAsString());
            for (String key : new String[]{"oldFiltered", "futureAbsent", "sourceUnchanged"}) Assert.assertTrue(key, evidence.get(key).getAsBoolean());
            String summary = evidence.get("summary").getAsString();
            Assert.assertTrue(summary, summary.contains("ust duzey hata/not=1"));
            Assert.assertTrue(summary, summary.contains("basarisiz komut denemesi=2"));
            Assert.assertTrue(summary, summary.contains("eksik/degisen log=2"));
        } finally { FileSupport.deleteTree(root); }
    }
    private static String quote(Path path) { return path.toString().replace("'", "''"); }
}
