package tr.diagnostic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RemoteSourceValidationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private CollectorConfiguration config(String yaml) throws Exception {
        Path file = temp.getRoot().toPath().resolve("config.yaml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return new ConfigurationManager().load(file);
    }
    private void invalid(CollectorConfiguration c) throws Exception {
        try { ConfigurationManager.validateRemoteSources(c); Assert.fail("Unsafe remote root accepted"); }
        catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("Uzak") || expected.getMessage().contains("uzak")); }
    }
    @Test public void relativeSourcesAndBatchRequireAnExplicitRoot() throws Exception {
        for (String base : new String[]{"config", "installation", "application"})
            invalid(config("logs: [{base: " + base + ", path: Log}]\n"));
        invalid(config("remote: {beforeLogCopyBatch: 'Log/collect-logs.bat'}\nlogs: []\n"));
        CollectorConfiguration valid = config("installationDirectory: 'C:/fixture-install'\napplicationDirectory: app\nremote: {beforeLogCopyBatch: 'Log/collect-logs.bat'}\nlogs: [{base: application, path: Log}]\n");
        ConfigurationManager.validateRemoteSources(valid);
    }
    @Test public void absoluteSourcesAndAbsoluteApplicationDoNotNeedInstallationRoot() throws Exception {
        CollectorConfiguration absolute = config("logs: [{path: 'D:/fixture-logs'}]\n");
        ConfigurationManager.validateRemoteSources(absolute);
        Assert.assertEquals("", absolute.installationPath);
        CollectorConfiguration app = config("applicationDirectory: 'D:/fixture-app'\nremote: {beforeLogCopyBatch: 'Log/collect-logs.bat'}\nlogs: [{base: application, path: Log}]\n");
        ConfigurationManager.validateRemoteSources(app);
        app.logs.get(0).base = "installation"; invalid(app);
    }
    @Test public void rejectsDriveRelativeRootRelativeAndImplicitWorkingDirectories() throws Exception {
        for (String path : new String[]{".", "install", "C:install", "/install", "\\install", "\\\\server\\share"}) {
            CollectorConfiguration c = config("logs: []\n"); c.installationPath = path; invalid(c);
        }
        for (String path : new String[]{"C:Log", "/Log", "\\Log", "\\\\server\\share"}) {
            CollectorConfiguration c = config("installationDirectory: 'C:/fixture-install'\nlogs: [{path: Log}]\n");
            c.logs.get(0).sourcePath = path; invalid(c);
        }
    }
    @Test public void preflightFailurePreventsTransportAndStagingCreationEvenAfterModeSwitch() throws Exception {
        CollectorConfiguration c = config("mode: local\nlogs: [{base: application, path: Log}]\n");
        c.outputDirectory = temp.getRoot().toPath().resolve("not-created");
        AtomicBoolean called = new AtomicBoolean();
        RemoteCollectionOrchestrator orchestrator = new RemoteCollectionOrchestrator(c, (s,p,r,l) -> {called.set(true); throw new IOException("Transport must not run");});
        try (RemoteConnection remote = new RemoteConnection("fixture-server", "fixture-user", "auto", "fixture-secret".toCharArray())) {
            try { orchestrator.collect(null, new char[12], remote, message -> {}); Assert.fail("Missing root accepted"); }
            catch (IOException expected) { Assert.assertTrue(expected.getMessage().contains("installationDirectory")); }
            Assert.assertFalse(called.get()); Assert.assertFalse(Files.exists(c.outputDirectory));
            Assert.assertEquals("list", orchestrator.settings("list", null, remote).get("action").getAsString());
        }
    }
    @Test public void validationUsesTheEffectiveModeAndDumpOnlyNeedsNoRoot() throws Exception {
        ConfigurationManager.validateRemoteSources(config("logs: []\n"));
        config("logs: [{path: Log}]\n");
        Path yaml = temp.getRoot().toPath().resolve("config.yaml");
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(messages, true, "UTF-8")) {
            System.setOut(capture); System.setErr(capture);
            Assert.assertEquals(1, Main.run(new String[]{"validate-config", "--config", yaml.toString()}));
            Assert.assertTrue(messages.toString("UTF-8").contains("installationDirectory"));
            Assert.assertEquals(0, Main.run(new String[]{"validate-config", "--local", "--config", yaml.toString()}));
        } finally { System.setOut(out); System.setErr(err); }
    }
}
