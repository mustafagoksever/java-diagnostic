package tr.diagnostic;
import com.google.gson.*;
import org.junit.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class ProcessNamesIT {
    @Test public void wmiLaunchNamesWorkWhenJpsHasNoTargetAndDoNotExposeArguments()throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        String[] commands={"\"C:\\Java\\bin\\java.exe\" -Dpassword=fixture-secret -jar \"C:\\My App\\operator.jar\" --password fixture-secret",
            "java.exe -Xmx512m -cp \"C:\\classes;C:\\libs\\*\" com.example.Main --password fixture-secret",
            "java.exe -jar launcher.jar -application com.example.ares.ui --password fixture-secret"};
        JsonArray rows=new JsonArray();
        for(int i=0;i<commands.length;i++){JsonObject p=new JsonObject();p.addProperty("Name","java.exe");p.addProperty("ProcessId",100+i);p.addProperty("SessionId",1);p.addProperty("CommandLine",commands[i]);rows.add(p);}
        String encoded=Base64.getEncoder().encodeToString(rows.toString().getBytes(StandardCharsets.UTF_8));
        String setup="$additionalNames=@();$fixtureRows=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"+encoded+"')));foreach($p in $fixtureRows){$p|Add-Member CreationDate ([DateTime]::UtcNow)};"
            +"function Get-CimInstance {param($ClassName) return $fixtureRows};function Invoke-CimMethod {param($InputObject,$MethodName) return [pscustomobject]@{ReturnValue=0;Domain='TEST';User='user'}};";
        CommandResult result=new PowerShell(new ProcessCommandExecutor()).run(setup+PowerShell.resource("process-scan.ps1"),15000);
        Assert.assertTrue(result.error(),result.success());Assert.assertFalse("Command arguments are private",result.stdout.contains("fixture-secret"));
        JsonArray processes=JsonParser.parseString(result.stdout.trim()).getAsJsonObject().getAsJsonArray("processes");
        Assert.assertEquals("operator.jar",processes.get(0).getAsJsonObject().get("applicationName").getAsString());
        Assert.assertEquals("com.example.Main",processes.get(1).getAsJsonObject().get("applicationName").getAsString());
        Assert.assertEquals("com.example.ares.ui",processes.get(2).getAsJsonObject().get("applicationName").getAsString());
    }
}
