package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class RemoteDiagnosticsReportWriterTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void writesTaskIdsSocketsAndNativeFailuresWithoutCallingThemClosed() throws Exception {
        Path root=temp.getRoot().toPath(),detail=root.resolve("vxworks/ports/port-COM7/port-result.json");Files.createDirectories(detail.getParent());
        Files.write(detail,("{tasks:[{taskId:'0xffff800000000042',name:'tJobTask',state:'PEND',attempted:true,frameCount:0,error:'UNBREAKABLE_TASK',artifact:'vxworks/ports/port-COM7/tasks/tt-0xffff800000000042.txt',infoAttempted:true,infoCollected:true,infoArtifact:'vxworks/ports/port-COM7/tasks/ti-0xffff800000000042.txt'}],socketRows:[{protocol:'TCP',addressFamily:'IPv4',localAddress:'0.0.0.0.23',localPort:23,foreignAddress:'0.0.0.0.0',state:'LISTEN',receiveQueue:0,sendQueue:0}]}" ).getBytes(StandardCharsets.UTF_8));
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();
        m.ping=JsonParser.parseString("{sourceComputer:'SUNUCU',success:true,results:[{address:'192.0.2.98',status:'NO_ECHO_REPLY',received:0,sent:4,lossPercent:100}]}").getAsJsonObject();
        m.vxworks=JsonParser.parseString("{ports:[{port:'COM7',identity:{addresses:['192.0.2.195'],macAddresses:['02:00:00:00:00:22']},status:'PARTIAL',initialTaskCount:1,attemptedTaskCount:1,tracesWithFrames:0,attemptedTaskInfoCount:1,taskInfosCollected:1,networkAvailable:true,artifact:'vxworks/ports/port-COM7/port-result.json'},{port:'COM19',status:'OPEN_FAILED',networkAvailable:false,artifact:'vxworks/ports/port-COM19/port-result.json'}]}").getAsJsonObject();
        new RemoteDiagnosticsReportWriter().write(m,root);
        Assert.assertTrue(read(root,"ping/SONUC.txt").contains("0/4"));
        Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("TCP LISTEN: [23]"));
        Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("COM7 | IP: 192.0.2.195 | MAC: 02:00:00:00:00:22"));
        Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("COM19 | IP: ALINAMADI"));
        Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("portlar kapalı anlamına gelmez"));
        Assert.assertTrue(read(root,"vxworks/task-listesi.csv").contains("UNBREAKABLE_TASK"));
        Assert.assertTrue(read(root,"vxworks/task-listesi.csv").contains("0xffff800000000042"));
        Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("ti alınan/denenen=1/1"));
        Assert.assertTrue(read(root,"vxworks/task-listesi.csv").contains("infoAttempted,infoCollected,infoError,infoArtifact"));
        Assert.assertTrue(read(root,"vxworks/task-listesi.csv").contains("ti-0xffff800000000042.txt"));
        Assert.assertTrue(read(root,"vxworks/task-listesi.csv").contains("\"COM7\",\"192.0.2.195\""));
        Assert.assertTrue(read(root,"vxworks/socket-port-listesi.csv").contains("\"COM7\",\"192.0.2.195\""));
        Assert.assertTrue(read(root,"vxworks/socket-port-listesi.csv").contains("LISTEN"));
        Assert.assertFalse(m.errors.isEmpty());
    }
    @Test public void refusesPathsOutsideThePortDetailDirectory() throws Exception {
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();
        m.vxworks=JsonParser.parseString("{ports:[{port:'COM7',artifact:'../outside.json'}]}").getAsJsonObject();
        new RemoteDiagnosticsReportWriter().write(m,temp.getRoot().toPath());Assert.assertEquals(1,m.errors.size());
    }
    @Test public void automaticDiscoveryAndNoPortReasonAreVisibleInTheExistingSummary() throws Exception {
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();m.mode="remote";
        m.vxworks=JsonParser.parseString("{discoveryMode:'auto',detectedPorts:[],ports:[],success:false,errors:['Sunucuda kayitli COM portu bulunamadi.']}").getAsJsonObject();
        new RemoteDiagnosticsReportWriter().write(m,temp.getRoot().toPath());
        String text=read(temp.getRoot().toPath(),"vxworks/SONUC.txt");
        Assert.assertTrue(text.contains("SUNUCUDA OTOMATİK KEŞİF"));Assert.assertTrue(text.contains("COM portu bulunamadi"));
    }
    @Test public void subnetSummaryDistinguishesNoReplyFromUnfinishedTargetsAndShowsFailures() throws Exception {
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();
        m.ping=JsonParser.parseString("{sourceComputer:'SUNUCU',subnet:'192.0.2.0/24',parallelism:16,durationMillis:1234,success:false,errors:['REMOTE_CANCELLED'],results:[{address:'192.0.2.1',status:'REACHABLE',sent:1,received:1},{address:'192.0.2.2',status:'NO_ECHO_REPLY',sent:1,received:0},{address:'192.0.2.3',status:'INCOMPLETE',sent:1,received:0},{address:'192.0.2.4',status:'NOT_TESTED',sent:0,received:0}]}").getAsJsonObject();
        new RemoteDiagnosticsReportWriter().write(m,temp.getRoot().toPath());
        String text=read(temp.getRoot().toPath(),"ping/SONUC.txt");
        Assert.assertTrue(text.contains("Ağ: 192.0.2.0/24"));Assert.assertTrue(text.contains("Yanıt veren: 1 | Yanıt yok: 1 | Tamamlanamayan: 2"));
        Assert.assertTrue(text.contains("Paralel IP sınırı: 16 | Süre (ms): 1234"));Assert.assertTrue(text.contains("Tarama notu: REMOTE_CANCELLED"));
    }
    @Test public void customCommandSummaryShowsCollectedUnsupportedAndNotAttemptedSeparately() throws Exception {
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();
        m.vxworks=JsonParser.parseString("{ports:[{port:'COM7',artifact:'vxworks/ports/port-COM7/port-result.json',customCommand:{attempted:true,collected:true,artifact:'vxworks/ports/port-COM7/custom-command.txt'}},{port:'COM9',artifact:'vxworks/ports/port-COM9/port-result.json',customCommand:{attempted:true,collected:false,error:'CUSTOM_COMMAND_UNSUPPORTED'}},{port:'COM11',artifact:'vxworks/ports/port-COM11/port-result.json',customCommand:{attempted:false,collected:false}}]}").getAsJsonObject();
        Path root=temp.getRoot().toPath();
        for(String port:new String[]{"COM7","COM9","COM11"}){
            Path detail=root.resolve("vxworks/ports/port-"+port+"/port-result.json");Files.createDirectories(detail.getParent());Files.write(detail,"{tasks:[],socketRows:[]}".getBytes(StandardCharsets.UTF_8));
        }
        new RemoteDiagnosticsReportWriter().write(m,root);
        String text=read(root,"vxworks/SONUC.txt");
        Assert.assertTrue(text.contains("Özel komut: ALINDI | çıktı: vxworks/ports/port-COM7/custom-command.txt"));
        Assert.assertTrue(text.contains("Özel komut: ALINAMADI | CUSTOM_COMMAND_UNSUPPORTED"));Assert.assertTrue(text.contains("Özel komut: DENENMEDİ"));
    }
    @Test public void additionalDiagnosticsAreListedWithTheirRawPathsAndUnsupportedErrors() throws Exception {
        Path root=temp.getRoot().toPath(),detail=root.resolve("vxworks/ports/port-COM7/port-result.json");Files.createDirectories(detail.getParent());
        Files.write(detail,"{tasks:[],socketRows:[],diagnostics:[{command:'version',collected:true,artifact:'vxworks/ports/port-COM7/diagnostics/version.txt'},{command:'tw 0x42',collected:false,artifact:'vxworks/ports/port-COM7/tasks/tw-0x42.txt',error:'COMMAND_UNSUPPORTED'}]}".getBytes(StandardCharsets.UTF_8));
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();m.vxworks=JsonParser.parseString("{ports:[{port:'COM7',artifact:'vxworks/ports/port-COM7/port-result.json'}]}").getAsJsonObject();
        new RemoteDiagnosticsReportWriter().write(m,root);String text=read(root,"vxworks/SONUC.txt");
        Assert.assertTrue(text.contains("version: YANIT ALINDI | vxworks/ports/port-COM7/diagnostics/version.txt"));
        Assert.assertTrue(text.contains("tw 0x42: ALINAMADI | vxworks/ports/port-COM7/tasks/tw-0x42.txt | COMMAND_UNSUPPORTED"));
    }
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    @Test public void malformedDiagnosticRowsDoNotDiscardValidRowsOrOtherPortReports() throws Exception {
        Path root=temp.getRoot().toPath(),detail=root.resolve("vxworks/ports/port-COM7/port-result.json");Files.createDirectories(detail.getParent());
        Files.write(detail,"{diagnostics:[null,7,{command:'version',collected:true}],tasks:[null,'bad',{taskId:'0x42',name:'validTask'}],socketRows:[false,{protocol:'TCP',state:'LISTEN',localPort:23}]}".getBytes(StandardCharsets.UTF_8));
        DiagnosticReportGenerator.Manifest m=new DiagnosticReportGenerator.Manifest();
        m.ping=JsonParser.parseString("{results:[null,4,{address:'127.0.0.1',status:'REACHABLE',received:1,sent:1}]}").getAsJsonObject();
        m.vxworks=JsonParser.parseString("{ports:[null,8,{port:'COM7',artifact:'vxworks/ports/port-COM7/port-result.json'}]}").getAsJsonObject();
        new RemoteDiagnosticsReportWriter().write(m,root);
        Assert.assertTrue(read(root,"ping/SONUC.txt").contains("127.0.0.1"));Assert.assertTrue(read(root,"vxworks/task-listesi.csv").contains("validTask"));
        Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("version: YANIT ALINDI"));Assert.assertTrue(read(root,"vxworks/SONUC.txt").contains("TCP LISTEN: [23]"));
        Assert.assertTrue(m.errors.toString(),m.errors.size()>=7);
    }
}
