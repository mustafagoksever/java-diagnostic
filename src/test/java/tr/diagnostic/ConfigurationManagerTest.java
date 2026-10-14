package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

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
}
