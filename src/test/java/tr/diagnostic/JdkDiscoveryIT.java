package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real Windows PowerShell; synthetic PE/release metadata, no physical devices. */
public class JdkDiscoveryIT {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void filtersWrongArchitectureAndMajorAndPrefersTargetsOwnJdk() throws Exception {
        Path first=jdk("first-x64",0x8664,"1.8.0_281",true), own=jdk("own-x86",0x14c,"1.8.0_281",true), wrong=jdk("wrong-17",0x14c,"17.0.1",true), jre=jdk("only-jre",0x14c,"1.8.0_281",false);
        Path unrelated=temp.newFolder("unrelated-vendor-folder").toPath();
        String script=". '"+quote(discovery())+"';$target=[pscustomobject]@{pid=42;startTicks='123';executablePath='"+quote(own.resolve("bin/java.exe"))+"';javaVersion='1.8.0_281'};"
            +"Get-JdkInventory @('"+quote(first)+"','"+quote(wrong)+"','"+quote(jre)+"','"+quote(own)+"','"+quote(own)+"','"+quote(unrelated)+"') @($target) -OnlyProvided|ConvertTo-Json -Depth 12 -Compress";
        JsonObject inventory=run(script);Assert.assertEquals(4,inventory.getAsJsonArray("candidates").size());
        JsonObject plan=inventory.getAsJsonArray("plans").get(1).getAsJsonObject();Assert.assertEquals("x86",plan.get("architecture").getAsString());
        JsonArray ranked=plan.getAsJsonArray("candidates");Assert.assertEquals(own.toString(),ranked.get(0).getAsJsonObject().get("home").getAsString());
        Map<String,String> reasons=new HashMap<>();for(JsonElement element:ranked){JsonObject row=element.getAsJsonObject();reasons.put(row.get("home").getAsString(),row.get("reason").isJsonNull()?null:row.get("reason").getAsString());}
        Assert.assertEquals("ARCHITECTURE_MISMATCH",reasons.get(first.toString()));Assert.assertEquals("JAVA_MAJOR_MISMATCH",reasons.get(wrong.toString()));Assert.assertEquals("NO_DIAGNOSTIC_TOOLS",reasons.get(jre.toString()));Assert.assertNull(reasons.get(own.toString()));
    }
    @Test public void embeddedJvmLibraryAndNestedJreFindParentJdkWithoutLaunchingJava() throws Exception {
        Path own=jdk("embedded-jdk",0x8664,"1.8.0_504",true),library=own.resolve("jre/bin/server/jvm.dll");Files.createDirectories(library.getParent());Files.write(library,pe(0x8664));
        JsonObject result=run(". '"+quote(discovery())+"';$t=[pscustomobject]@{pid=8;startTicks='5';executablePath='C:\\unknown\\wrapper.exe';jvmLibraryPath='"+quote(library)+"'};Get-JdkInventory @() @($t) -OnlyProvided|ConvertTo-Json -Depth 12 -Compress");
        Assert.assertEquals(2,result.getAsJsonArray("candidates").size());JsonObject plan=result.getAsJsonArray("plans").get(1).getAsJsonObject();Assert.assertEquals("x64",plan.get("architecture").getAsString());
        Assert.assertEquals(own.toString(),plan.getAsJsonArray("candidates").get(0).getAsJsonObject().get("home").getAsString());
    }
    @Test public void corruptPeIsUnknownAndArm64IsNotMisidentifiedAsX64() throws Exception {
        Path bad=temp.newFile("bad.exe").toPath(),arm=temp.newFile("arm.exe").toPath();Files.write(bad,new byte[64]);Files.write(arm,pe(0xaa64));
        JsonObject r=run(". '"+quote(discovery())+"';[pscustomobject]@{bad=(Get-JavaMachine '"+quote(bad)+"');arm=(Get-JavaMachine '"+quote(arm)+"');java8=(Java-Major '1.8.0_281');java17=(Java-Major '17.0.1')}|ConvertTo-Json -Compress");
        Assert.assertTrue(r.get("bad").isJsonNull());Assert.assertEquals("arm64",r.get("arm").getAsString());Assert.assertEquals(8,r.get("java8").getAsInt());Assert.assertEquals(17,r.get("java17").getAsInt());
    }
    @Test public void hardlinkedJavaAndDiagnosticToolsKeepTheirExistingPaths() throws Exception {
        Path root=jdk("hardlinked-jdk",0x8664,"1.8.0_504",true);
        String script=". '"+quote(discovery())+"';"
            +"New-Item -ItemType HardLink -Path '"+quote(root.resolve("java-alias.exe"))+"' -Target '"+quote(root.resolve("bin/java.exe"))+"'|Out-Null;"
            +"New-Item -ItemType HardLink -Path '"+quote(root.resolve("jcmd-alias.exe"))+"' -Target '"+quote(root.resolve("bin/jcmd.exe"))+"'|Out-Null;"
            +"$target=[pscustomobject]@{pid=42;startTicks='123';executablePath='"+quote(root.resolve("bin/java.exe"))+"'};"
            +"$inventory=Get-JdkInventory @('"+quote(root)+"') @($target) -OnlyProvided;"
            +"[pscustomobject]@{java=(Java-RealPath $target.executablePath);tool=$inventory.candidates[0].tools.jcmd;targetHome=(Java-HomeFromFile $target.executablePath);version=$inventory.plans[1].javaVersion}|ConvertTo-Json -Compress";
        JsonObject result=run(script);
        Assert.assertEquals(root.resolve("bin/java.exe").toString(),result.get("java").getAsString());
        Assert.assertEquals(root.resolve("bin/jcmd.exe").toString(),result.get("tool").getAsString());
        Assert.assertEquals(root.toString(),result.get("targetHome").getAsString());
        Assert.assertEquals("1.8.0_504",result.get("version").getAsString());
    }
    @Test public void junctionDirectoriesStillResolveToTheirTarget() throws Exception {
        Path root=jdk("real-jdk",0x8664,"1.8.0_504",true),alias=temp.getRoot().toPath().resolve("linked-jdk");
        try {
            JsonObject result=run(". '"+quote(discovery())+"';New-Item -ItemType Junction -Path '"+quote(alias)+"' -Target '"+quote(root)+"'|Out-Null;[pscustomobject]@{resolved=(Java-RealPath '"+quote(alias)+"')}|ConvertTo-Json -Compress");
            Assert.assertEquals(root.toString(),result.get("resolved").getAsString());
        }finally{Files.deleteIfExists(alias);}
    }
    @Test public void wrapperProductVersionDoesNotRejectCompatibleJavaButArchitectureStillDoes() throws Exception {
        Path root=jdk("compatible",0x8664,"1.8.0_504",true),wrong=jdk("wrong-architecture",0x14c,"1.8.0_504",true);
        Path wrapper=temp.getRoot().toPath().resolve("wrapper.exe");
        Files.copy(Paths.get(System.getenv("SystemRoot"),"System32/cmd.exe"),wrapper);
        JsonObject result=run(". '"+quote(discovery())+"';$t=[pscustomobject]@{pid=42;startTicks='123';executablePath='"+quote(wrapper)+"';javaVersion='10.0.19041.4355';jvmLibraryPath=$null};Get-JdkInventory @('"+quote(root)+"','"+quote(wrong)+"') @($t) -OnlyProvided|ConvertTo-Json -Depth 12 -Compress");
        JsonObject plan=result.getAsJsonArray("plans").get(1).getAsJsonObject();
        Assert.assertTrue("Wrapper version must stay unknown",plan.get("javaVersion").isJsonNull());
        Assert.assertEquals("x64",plan.get("architecture").getAsString());
        Map<String,String> reasons=new HashMap<>();for(JsonElement e:plan.getAsJsonArray("candidates")){JsonObject row=e.getAsJsonObject();reasons.put(row.get("home").getAsString(),row.get("reason").isJsonNull()?null:row.get("reason").getAsString());}
        Assert.assertNull(reasons.get(root.toString()));Assert.assertEquals("ARCHITECTURE_MISMATCH",reasons.get(wrong.toString()));
    }
    @Test public void genuineJavaExecutableVersionStillWorksWithoutReleaseFile() throws Exception {
        Path runtime=Paths.get(System.getProperty("java.home")),copy=temp.getRoot().toPath().resolve("java.exe");
        Files.copy(runtime.resolve("bin/java.exe"),copy);
        JsonObject result=run(". '"+quote(discovery())+"';Get-JavaMetadata '"+quote(temp.getRoot().toPath())+"' '"+quote(copy)+"'|ConvertTo-Json -Compress");
        Assert.assertEquals(8,result.get("major").getAsInt());Assert.assertEquals("x64",result.get("architecture").getAsString());
    }
    @Test public void remoteFallbackStopsAtSuccessAndCachesItForNextSample() throws Exception {fallback(false,false);}
    @Test public void remoteIdentityFailureStopsOtherCandidates() throws Exception {fallback(true,false);}
    @Test public void remoteCandidatesShareTheSampleTimeoutBudget() throws Exception {fallback(false,true);}
    @Test public void remoteIdentityQueryUsesRemainingSampleBudget() throws Exception {
        Path script=Files.write(temp.newFile("worker.ps1").toPath(),PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
        String body="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+quote(script)+"',[ref]$tokens,[ref]$issues);$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Query-Process'},$true);. ([scriptblock]::Create($fn.Extent.Text));"
            +"$settings=[pscustomobject]@{commandTimeoutSeconds=20};function Run-Script {param($body,$setup,$label,$timeoutSeconds)[pscustomobject]@{seconds=$timeoutSeconds}};[pscustomobject]@{short=(Query-Process 42 75).seconds;fraction=(Query-Process 42 1500).seconds;capped=(Query-Process 42 50000).seconds}|ConvertTo-Json -Compress";
        JsonObject result=run(body);Assert.assertEquals(0.075,result.get("short").getAsDouble(),0.0001);Assert.assertEquals(1.5,result.get("fraction").getAsDouble(),0.0001);Assert.assertEquals(20,result.get("capped").getAsDouble(),0.0001);
    }
    private void fallback(boolean identityFailure,boolean slow)throws Exception {
        Path script=Files.write(temp.newFile("worker.ps1").toPath(),PowerShell.resource("remote-worker.ps1").getBytes(StandardCharsets.UTF_8));
        String body="$tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+quote(script)+"',[ref]$tokens,[ref]$issues);foreach($name in @('Jdk-Attempts','Try-Tools')){$fn=$ast.Find({param($a)$a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq $name},$true);. ([scriptblock]::Create($fn.Extent.Text))};"
            +"function Deadline {};$settings=[pscustomobject]@{commandTimeoutSeconds=1};$preferredDumps=@{};$script:called=@();$script:budgets=@();$candidates=@();$rows=@();foreach($n in 1..4){$path='C:\\jdk'+$n;$candidates+=,[pscustomobject]@{home=$path;architecture='x64';javaVersion='1.8.0';tools=[pscustomobject]@{jcmd=($path+'\\bin\\jcmd.exe');jstack=($path+'\\bin\\jstack.exe')}};$rows+=,[pscustomobject]@{home=$path;reason=$null}};"
            +"$target=[pscustomobject]@{pid=42;startTicks='123'};$jdkInventory=[pscustomobject]@{candidates=$candidates;plans=@([pscustomobject]@{pid=42;startTicks='123';architecture='x64';candidates=$rows})};"
            +"function Run-Tool {param($target,$tool,$arguments,$type,$sample,$dir,$selection,$budgetMillis)$script:called+=,$selection.executable;$script:budgets+=,$budgetMillis;"
            +(slow?"Start-Sleep -Milliseconds 1100;":"")
            +"$ok="+(identityFailure||slow?"$false":"($selection.executable -like '*jdk2*')")+";[pscustomobject]@{success=$ok;error="+(identityFailure?"'PID_REUSED'":"'candidate failed'")+";sample=$sample;command=@($selection.executable)}};"
            +"$report=[pscustomobject]@{operations=@();selectedDumpTool=$null};$null=Try-Tools $report $target @('jcmd','jstack') 'thread-dump' 1 'unused';"
            +(!identityFailure&&!slow?"$null=Try-Tools $report $target @('jcmd','jstack') 'thread-dump' 2 'unused';":"")
            +"[pscustomobject]@{called=$called;budgets=$budgets;selected=$report.selectedDumpTool;count=$report.operations.Count}|ConvertTo-Json -Depth 5 -Compress";
        JsonObject result=run(body);JsonArray calls=result.getAsJsonArray("called");
        if(identityFailure){Assert.assertEquals(1,calls.size());Assert.assertTrue(result.get("selected").isJsonNull());}
        else if(slow){Assert.assertEquals(2,calls.size());Assert.assertTrue(result.getAsJsonArray("budgets").get(1).getAsInt()<1000);}
        else{Assert.assertEquals(4,calls.size());Assert.assertTrue(calls.get(0).getAsString().contains("jdk1"));Assert.assertTrue(calls.get(1).getAsString().endsWith("jstack.exe"));Assert.assertEquals(calls.get(2),calls.get(3));Assert.assertTrue(result.get("selected").getAsString().contains("jdk2"));}
    }
    private Path discovery()throws Exception {return Files.write(temp.newFile("jdk.ps1").toPath(),PowerShell.resource("jdk-discovery.ps1").getBytes(StandardCharsets.UTF_8));}
    private Path jdk(String name,int machine,String version,boolean tools)throws Exception {
        Path root=Files.createDirectories(temp.getRoot().toPath().resolve(name)),bin=Files.createDirectories(root.resolve("bin"));Files.write(bin.resolve("java.exe"),pe(machine));
        Files.write(root.resolve("release"),("JAVA_VERSION=\""+version+"\"\r\nIMPLEMENTOR=\"Fixture\"\r\n").getBytes(StandardCharsets.UTF_8));
        if(tools)for(String tool:Arrays.asList("jcmd","jstack"))Files.write(bin.resolve(tool+".exe"),pe(machine));return root;
    }
    private static byte[] pe(int machine){ByteBuffer b=ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN);b.putShort(0,(short)0x5a4d);b.putInt(60,128);b.putInt(128,0x4550);b.putShort(132,(short)machine);return b.array();}
    private static String quote(Path path){return path.toString().replace("'","''");}
    private static JsonObject run(String script)throws Exception {Assume.assumeTrue(PowerShell.isWindows());CommandResult result=new PowerShell(new ProcessCommandExecutor()).run(script,15000);Assert.assertTrue(result.error(),result.success());return JsonParser.parseString(result.stdout.trim()).getAsJsonObject();}
}
