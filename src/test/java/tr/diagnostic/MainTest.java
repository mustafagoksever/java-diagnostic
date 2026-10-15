package tr.diagnostic;

import org.junit.*;
import java.util.Arrays;

public class MainTest {
    @Test public void defaultsToEmbeddedConfiguration() throws Exception {
        Assert.assertNull(Main.Arguments.parse(new String[0]).config);
        CollectorConfiguration config = new ConfigurationManager().loadEmbedded();
        Assert.assertEquals("remote", config.mode);
        CollectorConfiguration source = new ConfigurationManager().load(java.nio.file.Paths.get("config.yaml"));
        Assert.assertEquals(source.outputDirectory, config.outputDirectory);
        Assert.assertEquals(source.remoteHost, config.remoteHost);
        Assert.assertEquals(source.logs.size(), config.logs.size());
        Assert.assertTrue("Embedded ZIP setting matches YAML", Arrays.equals(source.zipPasswordCopy(), config.zipPasswordCopy()));
    }
    @Test public void adminFlagOpensOnlyTheDetailedGui() {
        Assert.assertFalse(Main.Arguments.parse(new String[0]).admin);
        Main.Arguments admin=Main.Arguments.parse(new String[]{"--admin"});
        Assert.assertTrue(admin.admin);Assert.assertEquals("gui",admin.command);
        Assert.assertTrue(Main.Arguments.parse(new String[]{"gui","--admin","--local"}).admin);
        for(String[] args:new String[][]{{"collect","--all","--admin"},{"list-java","--admin"},{"validate-config","--admin"},{"gui","--local"}}){
            try{Main.Arguments.parse(args);Assert.fail(Arrays.toString(args));}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void validatesExclusiveTargetsAndPasswordOptions() {
        String[][] invalid = {{"collect"}, {"collect", "--all", "--pid", "12"}, {"collect", "--pid", "0"}, {"collect", "--pid", "12,"}, {"collect", "--all", "--generate-password", "--password-env", "SECRET"}, {"list-java", "--all"}, {"collect", "--pid"}, {"collect", "--all", "--password", "plaintext"}};
        for (String[] args : invalid) { try { Main.Arguments.parse(args); Assert.fail(Arrays.toString(args)); } catch (IllegalArgumentException expected) { } }
        Main.Arguments args = Main.Arguments.parse(new String[]{"collect", "--pid", "12,34", "--generate-password"});
        Assert.assertEquals(2, args.pids.size()); Assert.assertTrue(args.generatePassword);
    }
    @Test public void supportsLocalOverrideAndRejectsMixedModesAndPlaintextRemotePassword() {
        Assert.assertEquals("local", Main.Arguments.parse(new String[]{"collect", "--all", "--local"}).mode);
        Assert.assertEquals("remote", Main.Arguments.parse(new String[]{"list-java", "--remote", "--user", "server\\user", "--remote-password-env", "SESSION_PASSWORD"}).mode);
        for (String[] args : new String[][]{{"list-java", "--local", "--remote"},{"list-java", "--local", "--user", "user"},{"list-java", "--remote-password", "plaintext"}}) {
            try { Main.Arguments.parse(args); Assert.fail("Invalid remote arguments accepted"); } catch (IllegalArgumentException expected) { }
        }
    }
}
