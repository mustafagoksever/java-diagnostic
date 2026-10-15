package tr.diagnostic;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;

public class LocalDiagnosticsCollectorTest {
    @Test public void localJournalDrainPreservesBatchedMessagesAndIncompleteUtf8Lines() throws Exception {
        Path root=Files.createTempDirectory("local-journal-test-");
        try {
            char[] padding=new char[1900];Arrays.fill(padding,'x');StringBuilder records=new StringBuilder();
            for(int i=0;i<600;i++)records.append("{\"message\":\"").append(i).append(new String(padding)).append("\"}\n");
            Path journal=Files.write(root.resolve("activity-worker.jsonl"),records.toString().getBytes(StandardCharsets.UTF_8));
            byte[] tail="{\"message\":\"son-şöğü\"}\n".getBytes(StandardCharsets.UTF_8);
            Files.write(journal,Arrays.copyOf(tail,tail.length-3),StandardOpenOption.APPEND);
            Map<Path,Long> offsets=new HashMap<>();List<String> messages=new ArrayList<>();
            while(LocalDiagnosticsCollector.forwardActivity(root,offsets,messages::add)){}
            Assert.assertEquals(600,messages.size());
            Files.write(journal,Arrays.copyOfRange(tail,tail.length-3,tail.length),StandardOpenOption.APPEND);
            while(LocalDiagnosticsCollector.forwardActivity(root,offsets,messages::add)){}
            Assert.assertEquals(601,messages.size());Assert.assertEquals("son-şöğü",messages.get(600));
        } finally {FileSupport.deleteTree(root);}
    }
    @Test public void localRequestUsesResolvedPathsAndOmitsRemoteCredentialsAndZipSecret() throws Exception {
        CollectorConfiguration c=new CollectorConfiguration();
        c.remoteHost="unused-server";c.remoteUsername="unused-user";c.setZipPassword("private-archive-password");
        c.remoteBeforeLogCopyBatch="Log/prepare.bat";c.applicationDirectory=Paths.get("local application").toAbsolutePath();
        c.ping.enabled=true;c.ping.addresses.add("127.0.0.1");c.vxworks.enabled=true;c.vxworks.ports.add("COM65535");
        JsonObject request=new LocalDiagnosticsCollector(c,new ProcessCommandExecutor()).settings(new DiagnosticReportGenerator.Manifest(),"fixture");
        Assert.assertEquals("local",request.get("mode").getAsString());
        Assert.assertEquals(c.applicationDirectory.toString(),request.get("applicationDirectory").getAsString());
        Assert.assertEquals("Log/prepare.bat",request.get("beforeLogCopyBatch").getAsString());
        Assert.assertFalse(request.get("nonInteractiveTransport").getAsBoolean());
        Assert.assertTrue(request.get("clientHeartbeatRequired").getAsBoolean());
        Assert.assertFalse(request.toString().contains("unused-server")||request.toString().contains("unused-user")||request.toString().contains("private-archive-password"));
        Assert.assertTrue(request.getAsJsonObject("ping").get("enabled").getAsBoolean());
        Assert.assertTrue(request.getAsJsonObject("vxworks").get("enabled").getAsBoolean());
    }
    @Test public void disablingAllExtraCollectorsDoesNotStartAnyProcess() throws Exception {
        CollectorConfiguration c=new CollectorConfiguration();c.remoteWindowsDiagnostics=false;
        CommandExecutor forbidden=(command,timeout,out,err)->{throw new AssertionError("No helper expected");};
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();
        Assert.assertTrue(new LocalDiagnosticsCollector(c,forbidden).collect(m,Paths.get("unused"),message->{}));
        Assert.assertTrue(m.errors.isEmpty());
    }
}
