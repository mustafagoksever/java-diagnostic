package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.lingala.zip4j.ZipFile;

/** Explicit opt-in only. Never starts/stops target JVMs or changes server settings. */
public class RemoteServerIT {
    @Test public void collectsSpecifiedServerJvmAndPhotoSourcesWithVerifiedEncryptedArchive() throws Exception {
        String secret=System.getenv("DIAGNOSTIC_REMOTE_PASSWORD"), user=System.getenv("DIAGNOSTIC_REMOTE_USER"), selected=System.getProperty("diagnostic.remotePid");
        Assume.assumeTrue("Requires explicit remote credentials and -Ddiagnostic.remotePid",PowerShell.isWindows()&&secret!=null&&user!=null&&selected!=null);
        long pid=Long.parseLong(selected);Assert.assertTrue(pid>0);
        CollectorConfiguration config=new ConfigurationManager().load(Paths.get("config.yaml"));
        config.outputDirectory=Files.createDirectories(Paths.get("target/remote-server-evidence/reports").toAbsolutePath());
        String server=System.getProperty("diagnostic.remoteHost",config.remoteHost);
        char[] password=ZipEncryptionService.generatePassword();
        try(RemoteConnection connection=new RemoteConnection(server,user,config.remoteJdkHome,secret.toCharArray())){
            RemoteCollectionOrchestrator orchestrator=new RemoteCollectionOrchestrator(config);
            ProcessScanner.ScanResult scan=orchestrator.list(connection,message->{ });
            JavaProcessInfo before=scan.processes.stream().filter(p->p.pid==pid).findFirst().orElseThrow(()->new AssertionError("Specified PID not discovered"));
            CollectionOrchestrator.Result report=orchestrator.collect(Collections.singleton(pid),password,connection,System.out::println);
            Assert.assertEquals(1,report.manifest.jvms.size());Assert.assertEquals(3,report.manifest.jvms.get(0).dumpCount);
            try(ZipFile zip=new ZipFile(report.archive.toFile(),password)){
                for(String path:Arrays.asList("Manifest.json","system-info.json","sunucu-kaynak-kullanimi.txt","kaynak-izleme.csv","kaynak-izleme.json","disk-bilgileri.txt","analiz-ozeti.txt","log-hata-ozeti.txt","resolved-config.json","remote-context.json"))Assert.assertNotNull(path,zip.getFileHeader(path));
                try(InputStream input=zip.getInputStream(zip.getFileHeader("remote-context.json"))){JsonObject context=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();Assert.assertTrue(context.get("cleanupSucceeded").getAsBoolean());}
            }
            ProcessScanner.ScanResult after=orchestrator.list(connection,message->{ });
            Assert.assertTrue(after.processes.stream().anyMatch(p->p.pid==pid&&Objects.equals(p.startTicks,before.startTicks)));
            DiagnosticReportGenerator.writeJson(Paths.get("target/remote-server-evidence/manifest.json"),report.manifest);
        }finally{Arrays.fill(password,'\0');}
    }
}
