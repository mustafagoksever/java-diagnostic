package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real Windows PowerShell scheduler with a controlled ICMP adapter; no LAN traffic. */
public class RemotePingIT {
    @Test public void fullSubnetIsBoundedParallelAndProducesOnlyCombinedFiles() throws Exception {scan("subnet",16,1,0);}
    @Test public void singleSlotHonorsAttemptCountAndInterval() throws Exception {scan("interval",1,2,100);}
    @Test public void synchronousAndAsynchronousFailuresDoNotSkipOtherAddresses() throws Exception {scan("failures",4,1,0);}
    @Test public void cancellationDisposesPendingRequestsAndMarksUnfinishedTargets() throws Exception {scan("cancel",4,1,0);}

    private void scan(String mode,int parallelism,int attempts,int interval) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path job=Paths.get("C:/Temp","diagnostic-remote-"+UUID.randomUUID());Files.createDirectories(job);FileSupport.restrictDirectory(job);
        try {
            Path yaml=job.resolve("config.yaml");
            Files.write(yaml,("remote: {ping: {enabled: true, subnet: '15.30.9.0/"+(mode.equals("subnet")?24:30)+"', parallelism: "+parallelism+", attempts: "+attempts+", intervalMillis: "+interval+"}}\n").getBytes(StandardCharsets.UTF_8));
            CollectorConfiguration c=new ConfigurationManager().load(yaml);
            if(mode.equals("cancel")||mode.equals("failures")){c.ping.addresses.clear();for(int n=1;n<=6;n++)c.ping.addresses.add("15.30.9."+n);}
            try(RemoteConnection connection=new RemoteConnection("localhost","fixture\\user","C:/missing-jdk","unused-test-secret".toCharArray())) {
                DiagnosticReportGenerator.writeJson(job.resolve("request.json"),new RemoteCollectionOrchestrator(c).settings("collect",Collections.emptySet(),connection));
            }
            String helper=PowerShell.resource("remote-ping.ps1"),constructor="$state.ping=New-Object Net.NetworkInformation.Ping";
            Assert.assertTrue(helper.contains(constructor));helper=helper.replace(constructor,"$state.ping=New-FixturePing");
            Assert.assertFalse(helper.contains("New-Object Net.NetworkInformation.Ping"));writeScript(job.resolve("remote-ping.ps1"),helper);
            String fixture;
            try(java.io.InputStream input=getClass().getResourceAsStream("/ping-fixture.ps1")) {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);fixture=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            }
            String quoted=job.toString().replace("'","''");
            writeScript(job.resolve("launch.ps1"),"$global:pingFixtureRoot='"+quoted+"';$global:pingFixtureMode='"+mode+"';\r\n"+fixture+"\r\n& '"+quoted+"\\remote-ping.ps1' -JobRoot '"+quoted+"' -RequestFile '"+quoted+"\\request.json'\r\n$global:pingFixtureStats|ConvertTo-Json -Depth 5|Set-Content -LiteralPath '"+quoted+"\\fixture-stats.json' -Encoding UTF8");
            CommandResult execution=new ProcessCommandExecutor().run(Arrays.asList(Paths.get(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",job.resolve("launch.ps1").toString()),20000,job.resolve("stdout.txt"),job.resolve("stderr.txt"));
            Assert.assertTrue(execution.error(),execution.success());
            JsonObject result=read(job.resolve("data/ping/results.json")),stats=read(job.resolve("fixture-stats.json"));
            Assert.assertEquals(0,stats.get("active").getAsInt());Assert.assertEquals(stats.get("created"),stats.get("disposed"));
            Assert.assertTrue(stats.toString(),stats.get("maximum").getAsInt()<=parallelism);
            Assert.assertEquals(c.ping.addresses.size(),result.getAsJsonArray("results").size());
            try(java.util.stream.Stream<Path> files=Files.list(job.resolve("data/ping"))){Assert.assertEquals(2,files.count());}
            Assert.assertEquals(c.ping.addresses.size()+1,Files.readAllLines(job.resolve("data/ping/results.csv"),StandardCharsets.UTF_8).size());
            if(mode.equals("cancel")) {
                Assert.assertFalse(result.get("completed").getAsBoolean());Assert.assertFalse(result.get("success").getAsBoolean());
                Assert.assertEquals(4,stats.get("cancelled").getAsInt());Assert.assertEquals("REMOTE_CANCELLED",result.getAsJsonArray("errors").get(0).getAsString());
                for(int i=0;i<6;i++){JsonObject row=result.getAsJsonArray("results").get(i).getAsJsonObject();Assert.assertEquals(i<4?"INCOMPLETE":"NOT_TESTED",row.get("status").getAsString());Assert.assertEquals(i<4?1:0,row.get("sent").getAsInt());}
                return;
            }
            Assert.assertTrue(result.toString(),result.get("completed").getAsBoolean());
            if(mode.equals("failures")) {
                Assert.assertFalse(result.get("success").getAsBoolean());Assert.assertEquals(2,result.getAsJsonArray("errors").size());
                Assert.assertEquals("ERROR",result.getAsJsonArray("results").get(0).getAsJsonObject().get("status").getAsString());
                Assert.assertEquals("ERROR",result.getAsJsonArray("results").get(1).getAsJsonObject().get("status").getAsString());
                Assert.assertTrue(result.getAsJsonArray("errors").get(0).getAsString().contains("FIXTURE_START_FAILURE"));
                Assert.assertTrue(result.getAsJsonArray("errors").get(1).getAsString().contains("FIXTURE_ASYNC_FAILURE"));
            } else {
                Assert.assertTrue(result.toString(),result.get("success").getAsBoolean());
                JsonObject first=result.getAsJsonArray("results").get(0).getAsJsonObject();Assert.assertEquals("REACHABLE",first.get("status").getAsString());Assert.assertEquals(attempts,first.get("received").getAsInt());
                JsonObject second=result.getAsJsonArray("results").get(1).getAsJsonObject();Assert.assertEquals("NO_ECHO_REPLY",second.get("status").getAsString());Assert.assertEquals(0,second.get("received").getAsInt());
                Assert.assertEquals("DestinationHostUnreachable",second.getAsJsonArray("attempts").get(0).getAsJsonObject().get("status").getAsString());
            }
            if(mode.equals("subnet")) {
                Assert.assertEquals(16,stats.get("maximum").getAsInt());Assert.assertEquals(254,stats.get("created").getAsInt());
                JsonArray rows=result.getAsJsonArray("results");Assert.assertEquals("15.30.9.1",rows.get(0).getAsJsonObject().get("address").getAsString());Assert.assertEquals("15.30.9.254",rows.get(253).getAsJsonObject().get("address").getAsString());
                Assert.assertTrue(result.toString(),result.get("durationMillis").getAsLong()<12000);
            }
            if(mode.equals("interval")) {
                JsonArray starts=stats.getAsJsonArray("starts");Assert.assertEquals(4,starts.size());
                for(int n:new int[]{0,2})Assert.assertTrue(starts.toString(),starts.get(n+1).getAsJsonObject().get("at").getAsLong()-starts.get(n).getAsJsonObject().get("at").getAsLong()>=interval);
            }
        } finally {FileSupport.deleteTree(job);}
    }
    private static JsonObject read(Path file)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8).replace("\ufeff","")).getAsJsonObject();}
    private static void writeScript(Path file,String text)throws Exception{Files.write(file,("\ufeff"+text).getBytes(StandardCharsets.UTF_8));}
}
