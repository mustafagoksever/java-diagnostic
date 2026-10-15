package tr.diagnostic;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ConfigurationFileTest {
    @Test public void titleOnlySavePreservesImplicitLocalApplicationRootAndLogSources() throws Exception {
        Path root=temp.getRoot().toPath();Path path=root.resolve("config.yaml");
        Path log=Files.createDirectories(root.resolve("logs")).resolve("sample.log");Files.write(log,new byte[]{1});
        Files.write(path,"mode: local\nlogs: [{base: application, path: logs}]\n".getBytes(StandardCharsets.UTF_8));
        ConfigurationFile file=new ConfigurationFile(path);CollectorConfiguration initial=file.load();
        Assert.assertEquals(root,initial.applicationDirectory);
        DirectorySettings edited=DirectorySettings.from(initial);edited.options.put("applicationTitle","Only title changed");
        CollectorConfiguration saved=file.save(edited,true);
        Assert.assertEquals(initial.applicationDirectory,saved.applicationDirectory);
        Assert.assertEquals(initial.logs.get(0).path,saved.logs.get(0).path);
        Assert.assertTrue(Files.exists(saved.logs.get(0).path.resolve("sample.log")));
        Assert.assertEquals(initial.applicationDirectory,file.load().applicationDirectory);
        // Explicit auto must retain its layout detection; an omitted application under a configured install uses auto too.
        for(String settings:new String[]{"applicationDirectory: auto\n","installationDirectory: install\n"}){
            Files.createDirectories(root.resolve("install"));
            Files.write(path,("mode: local\n"+settings+"logs: []\n").getBytes(StandardCharsets.UTF_8));
            CollectorConfiguration automatic=file.load();DirectorySettings unchanged=DirectorySettings.from(automatic);
            CollectorConfiguration roundTrip=file.save(unchanged,true);
            Assert.assertEquals("auto",roundTrip.applicationPath);Assert.assertEquals(automatic.applicationDirectory,roundTrip.applicationDirectory);
        }
    }
    @Test public void advancedSettingsRoundTripAndPingSubnetChangeDoesNotKeepPreviouslyExpandedHosts() throws Exception {
        Path path=temp.getRoot().toPath().resolve("config.yaml");
        Files.write(path,("mode: remote\ninstallationDirectory: 'C:/ESM'\nremote: {host: '192.0.2.1', ping: {enabled: true, subnet: '192.0.2.0/30', addresses: ['127.0.0.1']}}\nzipPassword: 'original-archive-secret'\nlogs: []\n").getBytes(StandardCharsets.UTF_8));
        ConfigurationFile file=new ConfigurationFile(path);CollectorConfiguration initial=file.load();
        Assert.assertEquals(Arrays.asList("127.0.0.1"),initial.ping.configuredAddresses);Assert.assertEquals(3,initial.ping.addresses.size());
        DirectorySettings edited=DirectorySettings.from(initial);edited.options.put("applicationTitle","Updated title");edited.options.put("mode","local");
        edited.options.put("collectionDelaySeconds",2);edited.options.put("resourceMonitoringSeconds",5);edited.options.put("resourceSampleIntervalSeconds",2);
        edited.options.put("commandTimeoutSeconds",7);edited.options.put("scanTimeoutSeconds",33);edited.options.put("collectGcStats",false);
        edited.options.put("additionalProcessNames",Arrays.asList("wrapper.exe"));edited.options.put("jdkHomes",Arrays.asList("C:/Java/jdk8"));
        edited.options.put("remote.jdkHome","C:/JDK8");edited.options.put("remote.workShare","Temp");edited.options.put("remote.timeoutSeconds",123);
        edited.options.put("remote.windowsDiagnostics",false);edited.options.put("remote.beforeLogCopyBatch","Log/prepare.bat");edited.options.put("remote.beforeLogCopyTimeoutSeconds",12);edited.options.put("remote.beforeLogCopyDelaySeconds",2);
        edited.options.put("remote.ping.subnet","198.51.100.0/30");edited.options.put("remote.ping.addresses",initial.ping.configuredAddresses);
        edited.options.put("remote.ping.parallelism",3);edited.options.put("remote.ping.attempts",2);edited.options.put("remote.ping.timeoutMillis",300);edited.options.put("remote.ping.intervalMillis",50);
        edited.options.put("remote.vxworks.enabled",true);edited.options.put("remote.vxworks.autoDiscoverPorts",false);edited.options.put("remote.vxworks.ports",Arrays.asList("COM7"));
        edited.options.put("remote.vxworks.baudRate",9600);edited.options.put("remote.vxworks.dataBits",7);edited.options.put("remote.vxworks.stopBits",2);edited.options.put("remote.vxworks.parity","Even");edited.options.put("remote.vxworks.flowControl","None");
        edited.options.put("remote.vxworks.portTimeoutSeconds",25);edited.options.put("remote.vxworks.commandTimeoutSeconds",3);edited.options.put("remote.vxworks.maxOutputMb",16);edited.options.put("remote.vxworks.customCommand","fixtureInfo");edited.options.put("remote.vxworks.customCommandDelaySeconds",4);
        edited.options.put("zipPassword","new-archive-secret");file.save(edited,false);CollectorConfiguration saved=file.load();
        Assert.assertEquals("Updated title",saved.applicationTitle);Assert.assertEquals("local",saved.mode);Assert.assertEquals(2,saved.collectionDelaySeconds);Assert.assertEquals(5,saved.resourceMonitoringSeconds);Assert.assertEquals(2,saved.resourceSampleIntervalSeconds);
        Assert.assertEquals(7,saved.commandTimeoutSeconds);Assert.assertEquals(33,saved.scanTimeoutSeconds);Assert.assertFalse(saved.collectGcStats);Assert.assertEquals(Arrays.asList("wrapper.exe"),saved.additionalProcessNames);Assert.assertEquals(1,saved.jdkHomes.size());
        Assert.assertEquals("C:/JDK8",saved.remoteJdkHome);Assert.assertEquals("Temp",saved.remoteWorkShare);Assert.assertEquals(123,saved.remoteTimeoutSeconds);Assert.assertFalse(saved.remoteWindowsDiagnostics);Assert.assertEquals("Log/prepare.bat",saved.remoteBeforeLogCopyBatch);Assert.assertEquals(12,saved.remoteBeforeLogCopyTimeoutSeconds);Assert.assertEquals(2,saved.remoteBeforeLogCopyDelaySeconds);
        Assert.assertEquals(Arrays.asList("127.0.0.1","198.51.100.1","198.51.100.2"),saved.ping.addresses);Assert.assertEquals(3,saved.ping.parallelism);Assert.assertEquals(2,saved.ping.attempts);Assert.assertEquals(300,saved.ping.timeoutMillis);Assert.assertEquals(50,saved.ping.intervalMillis);
        Assert.assertTrue(saved.vxworks.enabled);Assert.assertFalse(saved.vxworks.autoDiscoverPorts);Assert.assertEquals(Arrays.asList("COM7"),saved.vxworks.ports);Assert.assertEquals(9600,saved.vxworks.baudRate);Assert.assertEquals(7,saved.vxworks.dataBits);Assert.assertEquals(2,saved.vxworks.stopBits);Assert.assertEquals("Even",saved.vxworks.parity);Assert.assertEquals("None",saved.vxworks.flowControl);
        Assert.assertEquals(25,saved.vxworks.portTimeoutSeconds);Assert.assertEquals(3,saved.vxworks.commandTimeoutSeconds);Assert.assertEquals(16,saved.vxworks.maxOutputMb);Assert.assertEquals("fixtureInfo",saved.vxworks.customCommand);Assert.assertEquals(4,saved.vxworks.customCommandDelaySeconds);
        Assert.assertEquals("new-archive-secret",new String(saved.zipPasswordCopy()));
        initial.remoteUsername="session-user";DirectorySettings.apply(initial,saved);Assert.assertEquals("session-user",initial.remoteUsername);Assert.assertEquals("Updated title",initial.applicationTitle);Assert.assertEquals(16,initial.vxworks.maxOutputMb);Assert.assertEquals("new-archive-secret",new String(initial.zipPasswordCopy()));
        DirectorySettings invalid=DirectorySettings.from(saved);byte[] original=Files.readAllBytes(path);invalid.options.put("remote.vxworks.commandTimeoutSeconds",25);
        try{file.save(invalid,true);Assert.fail();}catch(java.io.IOException expected){}Assert.assertArrayEquals(original,Files.readAllBytes(path));
        invalid.options.clear();invalid.options.put("zipPassword",null);invalid.options.put("remote.ping.subnet",null);file.save(invalid,true);Assert.assertEquals(0,file.load().zipPasswordCopy().length);Assert.assertEquals(Arrays.asList("127.0.0.1"),file.load().ping.addresses);
    }
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void saveReloadsIpPathsAndSourcesWhileKeepingOtherSettingsAndSecretsOutOfTheUiModel() throws Exception {
        Path path=temp.getRoot().toPath().resolve("config.yaml");
        Files.write(path,("applicationTitle: 'Fixture'\nzipPassword: 'fixture-archive-secret'\nmode: remote\nremote: {host: '192.0.2.1', jdkHome: auto, ping: {enabled: false, addresses: ['127.0.0.1']}}\ninstallationDirectory: 'C:/Original'\napplicationDirectory: auto\nlogs: []\nresourceMonitoringSeconds: 17\n").getBytes(StandardCharsets.UTF_8));
        ConfigurationFile file=new ConfigurationFile(path);CollectorConfiguration initial=file.load();
        DirectorySettings settings=DirectorySettings.from(initial);settings.host="192.0.2.25";settings.installation="D:/ESM";settings.application="app";settings.output="raporlar";
        CollectorConfiguration.LogDirectory source=new CollectorConfiguration.LogDirectory();source.sourcePath="Log";source.base="application";source.destination="application";source.extensions.add(".log");source.modifiedWithinHours=36;source.maxFileSizeBytes=23L*1024*1024;settings.logs.add(source);
        file.save(settings,false);CollectorConfiguration reloaded=new ConfigurationFile(path).load();
        Assert.assertEquals("192.0.2.25",reloaded.remoteHost);Assert.assertEquals("D:/ESM",reloaded.installationPath);Assert.assertEquals("app",reloaded.applicationPath);
        Assert.assertEquals(path.getParent().resolve("raporlar"),reloaded.outputDirectory);
        Assert.assertEquals(1,reloaded.logs.size());Assert.assertEquals("Log",reloaded.logs.get(0).sourcePath);Assert.assertEquals("application",reloaded.logs.get(0).base);
        Assert.assertEquals(Arrays.asList(".log"),reloaded.logs.get(0).extensions);Assert.assertEquals(36,reloaded.logs.get(0).modifiedWithinHours);Assert.assertEquals(23L*1024*1024,reloaded.logs.get(0).maxFileSizeBytes);
        Assert.assertEquals("Fixture",reloaded.applicationTitle);Assert.assertEquals(17,reloaded.resourceMonitoringSeconds);Assert.assertEquals("fixture-archive-secret",new String(reloaded.zipPasswordCopy()));
        Assert.assertEquals(Arrays.asList("127.0.0.1"),reloaded.ping.addresses);
        DirectorySettings second=DirectorySettings.from(reloaded);second.logs.clear();file.save(second,false);Assert.assertTrue(file.load().logs.isEmpty());
    }
    @Test public void invalidSettingsLeaveExistingFileByteForByteUnchanged() throws Exception {
        Path path=temp.getRoot().toPath().resolve("config.yaml");byte[] original="remote: {host: '192.0.2.1'}\nlogs: []\n".getBytes(StandardCharsets.UTF_8);Files.write(path,original);
        ConfigurationFile file=new ConfigurationFile(path);DirectorySettings settings=DirectorySettings.from(file.load());
        settings.host="bad/host";try{file.save(settings,false);Assert.fail();}catch(IllegalArgumentException expected){}Assert.assertArrayEquals(original,Files.readAllBytes(path));
        settings.host="192.0.2.25";CollectorConfiguration.LogDirectory source=new CollectorConfiguration.LogDirectory();source.sourcePath="Log";source.base="installation";source.destination="../escape";settings.logs.add(source);
        try{file.save(settings,false);Assert.fail();}catch(java.io.IOException expected){}Assert.assertArrayEquals(original,Files.readAllBytes(path));
        try(java.util.stream.Stream<Path> files=Files.list(path.getParent())){Assert.assertEquals(1,files.count());}
    }
    @Test public void firstSaveCreatesWritableYamlFromEmbeddedDefaults() throws Exception {
        Path path=temp.getRoot().toPath().resolve("config.yaml");ConfigurationFile file=new ConfigurationFile(path,false);
        Assert.assertFalse(file.exists());CollectorConfiguration embedded=file.load();DirectorySettings settings=DirectorySettings.from(embedded);settings.host="192.0.2.99";
        file.save(settings,false);Assert.assertTrue(file.exists());Assert.assertEquals("192.0.2.99",file.load().remoteHost);
        Assert.assertEquals(embedded.remoteBeforeLogCopyBatch,file.load().remoteBeforeLogCopyBatch);Assert.assertArrayEquals(embedded.zipPasswordCopy(),file.load().zipPasswordCopy());
    }
}
