package tr.diagnostic;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import tr.diagnostic.DiagnosticReportGenerator.*;
public class ProcessReportTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void processIndexConnectsApplicationPidAndActualDumpFilesAndIsHashed()throws Exception {
        Manifest m=new Manifest();JavaProcessInfo p=new JavaProcessInfo();p.pid=6900;p.processName="java.exe";p.applicationName="operator-app.jar";
        JvmReport j=new JvmReport(p);j.dumpCount=3;m.jvms.add(j);
        Operation op=new Operation();op.type="thread-dump";op.sample=1;op.success=true;op.artifact="jvms/java.exe-6900/sample-1.txt";j.operations.add(op);
        new DiagnosticReportGenerator().write(m,temp.getRoot().toPath());
        String text=new String(Files.readAllBytes(temp.getRoot().toPath().resolve("java-surecleri.txt")),StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("PID 6900 — operator-app.jar"));Assert.assertTrue(text.contains(op.artifact));
        Assert.assertTrue(m.files.stream().anyMatch(f->f.path.equals("java-surecleri.txt")&&f.sha256!=null));
    }
    @Test public void missingNameIsExplicitAndJarFallbackUsesOnlyFilename() {
        JavaProcessInfo p=new JavaProcessInfo();p.processName="java.exe";Assert.assertEquals("Uygulama adı alınamadı",p.applicationLabel());
        p.mainClass="C:\\Program Files\\app\\sample.jar";Assert.assertEquals("sample.jar",p.applicationLabel());
    }
}
