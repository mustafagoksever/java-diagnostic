package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;

public class ConfigurationManagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private CollectorConfiguration load(String yaml) throws Exception {
        Path file = temp.getRoot().toPath().resolve("config.yaml"); Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return new ConfigurationManager().load(file);
    }
    @Test public void multipleDirectoriesResolveRelativeToYaml() throws Exception {
        CollectorConfiguration c = load("outputDirectory: output\nlogs:\n - path: logs-a\n   extensions: [.LOG, .txt]\n   recursive: false\n   modifiedWithinHours: 0\n   maxFileSizeMb: 2\n - path: logs-b\n");
        Assert.assertEquals(temp.getRoot().toPath().resolve("output"), c.outputDirectory);
        Assert.assertEquals(2, c.logs.size()); Assert.assertFalse(c.logs.get(0).recursive);
        Assert.assertEquals(".log", c.logs.get(0).extensions.get(0)); Assert.assertEquals(2 * 1024L * 1024, c.logs.get(0).maxFileSizeBytes);
    }
    @Test public void rejectsInvalidTypesRangesAndUnknownSecrets() throws Exception {
        String[] invalid = {"password: TOP_SECRET", "commandTimeoutSeconds: 0", "commandTimeoutSeconds: 1.5", "logs: wrong", "logs: [{path: x, extensions: [log]}]", "collectGcStats: yesplease", "additionalProcessNames: [bad'command.exe]", "outputDirectory: a\noutputDirectory: b", "!!java.lang.Runtime {}", "logs: &a [{path: x}]\njdkHomes: *a"};
        for (String yaml : invalid) {
            try { load(yaml); Assert.fail("Accepted invalid YAML"); }
            catch (IOException e) { Assert.assertFalse("Never echo secret YAML content", e.getMessage().contains("TOP_SECRET")); }
        }
    }
    @Test public void resolvesInstallationAndApplicationSourcesAndDetectsBothLayouts() throws Exception {
        Path install = Files.createDirectory(temp.getRoot().toPath().resolve("install"));
        Files.createDirectory(install.resolve("uygulama"));
        String yaml = "installationDirectory: install\napplicationDirectory: auto\nlogs:\n - {base: application, path: plugins/a.xml, destination: KA/conf}\n - {base: installation, path: gomulu/log}\n";
        CollectorConfiguration c = load(yaml);
        Assert.assertEquals(install.resolve("uygulama/plugins/a.xml"), c.logs.get(0).path);
        Assert.assertEquals(install.resolve("gomulu/log"), c.logs.get(1).path);
        Files.delete(install.resolve("uygulama")); Files.createDirectory(install.resolve("plugins"));
        Assert.assertEquals(install, load(yaml).applicationDirectory);
    }
    @Test public void rejectsUnsafeDestinationsAndInvalidMonitoringSettings() throws Exception {
        for (String yaml : new String[]{"resourceMonitoringSeconds: -1", "resourceSampleIntervalSeconds: 0", "collectionDelaySeconds: -1", "logs: [{path: a, destination: ../escape}]", "logs: [{path: a, destination: /absolute}]", "logs: [{path: a, base: unknown}]"}) {
            try { load(yaml); Assert.fail("Invalid configuration accepted"); } catch (IOException expected) { }
        }
    }
    @Test public void defaultsToRemoteAndRejectsCredentialsInConfiguration() throws Exception {
        CollectorConfiguration c = load("remote: {host: '15.30.9.176', jdkHome: 'C:/Java/jdk8'}\nlogs: []\n");
        Assert.assertEquals("remote", c.mode); Assert.assertEquals("", c.remoteUsername);
        Assert.assertEquals("15.30.9.176", c.remoteHost);
        Assert.assertEquals("auto", c.remoteWorkShare);
        Assert.assertEquals("Temp",load("remote: {workShare: Temp}\n").remoteWorkShare);
        try{load("remote: {workShare: '../wrong'}\n");Assert.fail("Invalid share name accepted");}catch(IOException expected){}
        for (String yaml : new String[]{"remote: {password: secret}", "remote: {username: Administrator}", "mode: unsupported", "remote: {host: 'bad/host'}", "remote: {jdkHome: relative}", "remote: {timeoutSeconds: 0}"}) {
            try { load(yaml); Assert.fail("Invalid remote config accepted"); } catch (IOException expected) { Assert.assertFalse(expected.getMessage().contains("secret")); }
        }
    }
    @Test public void validatesRemoteBatchPathsAndTimeouts() throws Exception {
        CollectorConfiguration c=load("remote: {beforeLogCopyBatch: 'Log/kaydal.bat', beforeLogCopyTimeoutSeconds: 120}\n");
        Assert.assertEquals("Log/kaydal.bat",c.remoteBeforeLogCopyBatch);Assert.assertEquals(120,c.remoteBeforeLogCopyTimeoutSeconds);
        Assert.assertEquals(30,c.remoteBeforeLogCopyDelaySeconds);
        Assert.assertEquals("",load("remote: {beforeLogCopyBatch: ''}\n").remoteBeforeLogCopyBatch);
        for(String value:new String[]{"../evil.bat","C:/evil.bat","/evil.bat","Log/x.bat & whoami","Log/x%USERNAME%.bat","Log/script.exe"}){
            try{load("remote: {beforeLogCopyBatch: '"+value+"'}\n");Assert.fail("Invalid BAT path accepted");}catch(IOException expected){}
        }
        for(int timeout:new int[]{0,601}){
            try{load("remote: {beforeLogCopyTimeoutSeconds: "+timeout+"}\n");Assert.fail("Invalid timeout accepted");}catch(IOException expected){}
        }
        for(int delay:new int[]{-1,301}){
            try{load("remote: {beforeLogCopyDelaySeconds: "+delay+"}\n");Assert.fail("Invalid delay accepted");}catch(IOException expected){}
        }
    }
    @Test public void validatesPingAndSerialSettingsWithoutAcceptingCommandsOrSecrets() throws Exception {
        CollectorConfiguration c=load("remote:\n ping: {enabled: true, addresses: ['15.30.9.198', '15.30.9.198'], attempts: 4}\n vxworks: {enabled: true, ports: ['com7', 'COM17'], baudRate: 115200, dataBits: 8, stopBits: 1, parity: None, flowControl: None}\n");
        Assert.assertTrue(c.ping.enabled);Assert.assertEquals(1,c.ping.addresses.size());Assert.assertEquals(4,c.ping.attempts);
        Assert.assertTrue(c.vxworks.enabled);Assert.assertEquals(Arrays.asList("COM7","COM17"),c.vxworks.ports);
        Assert.assertEquals(180,c.vxworks.portTimeoutSeconds);Assert.assertEquals(8,c.vxworks.maxOutputMb);
        for(String yaml:new String[]{
            "remote: {ping: {enabled: true}}", "remote: {ping: {addresses: ['15.30.9.999']}}", "remote: {ping: {addresses: ['server']}}", "remote: {ping: {addresses: ['15.30.9.1/24']}}", "remote: {ping: {attempts: 0}}", "remote: {ping: {timeoutMillis: 99}}",
            "remote: {vxworks: {enabled: true}}", "remote: {vxworks: {ports: ['COM0']}}", "remote: {vxworks: {ports: ['COM65536']}}", "remote: {vxworks: {ports: ['COM7;whoami']}}", "remote: {vxworks: {dataBits: 9}}", "remote: {vxworks: {stopBits: 0}}", "remote: {vxworks: {flowControl: RtsCts}}", "remote: {vxworks: {commands: ['reboot']}}", "remote: {vxworks: {password: secret}}", "remote: {vxworks: {portTimeoutSeconds: 15, commandTimeoutSeconds: 20}}"
        }){try{load(yaml);Assert.fail("Invalid extra diagnostics config accepted");}catch(IOException expected){Assert.assertFalse(expected.getMessage().contains("secret"));}}
        Assert.assertFalse(load("logs: []").ping.enabled);Assert.assertFalse(load("logs: []").vxworks.enabled);
    }
    @Test public void automaticSerialDiscoveryNeedsNoManualPortListAndOldConfigsKeepTheirSelection() throws Exception {
        CollectorConfiguration c=load("remote: {vxworks: {enabled: true, autoDiscoverPorts: true, ports: []}}\n");
        Assert.assertTrue(c.vxworks.enabled);Assert.assertTrue(c.vxworks.autoDiscoverPorts);Assert.assertTrue(c.vxworks.ports.isEmpty());
        Assert.assertFalse(load("remote: {vxworks: {enabled: true, ports: ['COM7']}}\n").vxworks.autoDiscoverPorts);
        Assert.assertEquals(Arrays.asList("COM500","COM65535"),load("remote: {vxworks: {ports: ['com500', 'COM65535']}}\n").vxworks.ports);
        try{load("remote: {vxworks: {autoDiscoverPorts: maybe}}\n");Assert.fail("Invalid auto-discovery flag accepted");}catch(IOException expected){}
    }
    @Test public void expandsSubnetWithoutNetworkBroadcastOrDuplicateTargets() throws Exception {
        CollectorConfiguration c=load("remote: {ping: {enabled: true, subnet: '15.30.9.0/24', addresses: ['15.30.9.198', '15.30.10.20'], parallelism: 16, attempts: 1}}\n");
        Assert.assertEquals(255,c.ping.addresses.size());Assert.assertEquals(16,c.ping.parallelism);
        Assert.assertTrue(c.ping.addresses.contains("15.30.9.1"));Assert.assertTrue(c.ping.addresses.contains("15.30.9.254"));
        Assert.assertFalse(c.ping.addresses.contains("15.30.9.0"));Assert.assertFalse(c.ping.addresses.contains("15.30.9.255"));
        Assert.assertEquals(255,new HashSet<>(c.ping.addresses).size());
        Assert.assertEquals(Arrays.asList("255.255.255.253","255.255.255.254"),load("remote: {ping: {subnet: '255.255.255.252/30'}}").ping.addresses);
        Assert.assertEquals(Arrays.asList("15.30.9.176"),load("remote: {ping: {enabled: true, addresses: ['15.30.9.176']}}").ping.addresses);
    }
    @Test public void rejectsUnboundedOrMalformedSubnetAndParallelism() throws Exception {
        for(String setting:new String[]{"subnet: '15.30.9.0/16'","subnet: '15.30.9.1/24'","subnet: '15.30.9.0/31'","subnet: '15.30.999.0/24'","subnet: 'server/24'","subnet: '15.30.09.0/24'","subnet: '15.30.9.0/24;whoami'","parallelism: 0","parallelism: 33","parallelism: 1.5"}){
            try{load("remote: {ping: {"+setting+"}}");Assert.fail("Invalid scan setting accepted: "+setting);}catch(IOException expected){}
        }
        StringBuilder targets=new StringBuilder("remote: {ping: {addresses: [");
        for(int n=0;n<513;n++){if(n>0)targets.append(',');targets.append("'10.0.").append(n/256).append('.').append(n%256).append("'");}
        try{load(targets.append("]}}").toString());Assert.fail("Target limit not enforced");}catch(IOException expected){}
    }
}
