package tr.diagnostic;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.lingala.zip4j.ZipFile;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class CollectionActivityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void completionLogsFollowZipValidationAndStagingCleanup() throws Exception {
        CollectorConfiguration config=new CollectorConfiguration();config.outputDirectory=temp.getRoot().toPath();config.resourceMonitoringSeconds=0;
        Path[] stagingPath=new Path[1];List<String> messages=new ArrayList<>();
        RemoteTransport fake=(settings,staging,connection,progress)->{
            stagingPath[0]=staging;
            for(String path:Arrays.asList("debug/discovery/jps.txt","debug/queries/identity.json")){
                Path file=staging.resolve(path);Files.createDirectories(file.getParent());Files.write(file,"fixture".getBytes(StandardCharsets.UTF_8));
            }
            return JsonParser.parseString("{result:{serverStartedAtUtc:'2026-10-14T00:00:00Z',discovery:{windowsDiscoverySucceeded:true,processes:[],errors:[]},jvms:[],logs:[],errors:[],operations:[]}}").getAsJsonObject();
        };
        char[] password=ZipEncryptionService.generatePassword();
        try(RemoteConnection connection=connection()){
            List<Integer> percentages = new ArrayList<>();
            CollectionOrchestrator.Result result=new RemoteCollectionOrchestrator(config,fake).collect(null,password,connection,new CollectionProgress() {
                @Override public void accept(String message) {
                    if(message.startsWith("Toplama bitiş zamanı:"))Assert.assertFalse("Finish must include cleanup",Files.exists(stagingPath[0]));
                    messages.add(message);
                }
                @Override public void update(int percent, String stage) {
                    if (percent == 100) {
                        Assert.assertFalse("100% follows cleanup", Files.exists(stagingPath[0]));
                        Assert.assertTrue("100% follows archive creation", config.outputDirectory.toFile().listFiles((dir, name) -> name.endsWith(".zip")).length > 0);
                    }
                    percentages.add(percent);
                }
            });
            Assert.assertEquals(Integer.valueOf(100), percentages.get(percentages.size() - 1));
            for (int i = 1; i < percentages.size(); i++) Assert.assertTrue(percentages.get(i) >= percentages.get(i - 1));
            assertTiming(messages,false);
            try(ZipFile zip=new ZipFile(result.archive.toFile(),password)){
                Assert.assertNotNull(zip.getFileHeader("debug/discovery/jps.txt"));Assert.assertNotNull(zip.getFileHeader("debug/queries/identity.json"));
            }
            Assert.assertEquals(2,result.manifest.files.stream().filter(f->f.path.startsWith("debug/")).count());
        }finally{Arrays.fill(password,'\0');}
    }

    @Test public void remoteFailureKeepsItsExceptionAndLogsEndAndDuration() throws Exception {
        CollectorConfiguration config=new CollectorConfiguration();config.outputDirectory=temp.getRoot().toPath();
        IOException failure=new IOException("fixture failure");List<String> messages=new ArrayList<>();
        try(RemoteConnection connection=connection()){
            try{new RemoteCollectionOrchestrator(config,(s,p,c,l)->{throw failure;}).collect(null,new char[12],connection,messages::add);Assert.fail();}
            catch(IOException actual){Assert.assertSame(failure,actual);}
            assertTiming(messages,true);
            try(java.util.stream.Stream<Path> files=Files.list(config.outputDirectory)){Assert.assertEquals(0,files.count());}
        }
    }

    @Test public void localValidationFailureStillHasOneTimingSummary() throws Exception {
        List<String> messages=new ArrayList<>();
        try{new CollectionOrchestrator(new CollectorConfiguration()).collect(null,new char[0],messages::add);Assert.fail();}
        catch(IOException expected){}
        assertTiming(messages,true);
    }

    @Test public void interruptedCollectionKeepsItsExceptionAndLogsDuration() throws Exception {
        InterruptedException interruption=new InterruptedException("fixture interruption");List<String> messages=new ArrayList<>();
        try{CollectionTiming.run(messages::add,()->{throw interruption;});Assert.fail();}
        catch(InterruptedException actual){Assert.assertSame(interruption,actual);}
        assertTiming(messages,true);
    }

    private static RemoteConnection connection(){return new RemoteConnection("fixture","fixture","C:/Java/jdk8","fixture-session".toCharArray());}
    private static void assertTiming(List<String> messages,boolean failed){
        Assert.assertTrue(messages.toString(),messages.get(0).startsWith("Toplama başlangıç zamanı:"));
        Assert.assertEquals(1,messages.stream().filter(s->s.startsWith("Toplama başlangıç zamanı:")).count());
        Assert.assertEquals(1,messages.stream().filter(s->s.startsWith("Toplama bitiş zamanı:")).count());
        Assert.assertEquals(1,messages.stream().filter(s->s.startsWith("Toplam süre:")).count());
        Assert.assertEquals(failed,messages.get(messages.size()-2).contains("İşlem başarısız veya kesildi."));
        Assert.assertTrue(messages.toString(),messages.get(messages.size()-1).matches("Toplam süre: [0-9]+,[0-9]{2} dakika \\([0-9]+ dk [0-9]+ sn\\)\\."));
    }
}
