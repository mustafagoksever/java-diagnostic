package tr.diagnostic;

import com.google.gson.*;
import net.lingala.zip4j.ZipFile;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CliIT {
    @Test public void distributedJarLoadsSavedConfigurationBesideJarFromAnyWorkingDirectory() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path root=Files.createTempDirectory("diagnostic standalone jar ");
        try {
            Path jar=Files.copy(Paths.get("target/java-diagnostic-collector.jar"),root.resolve("java-diagnostic-collector.jar"));
            Path java=Paths.get(System.getProperty("java.home"),"bin","java.exe");
            for(int pass=0;pass<2;pass++) {
                if(pass==1)Files.write(root.resolve("config.yaml"),"mode: local\noutputDirectory: saved-output\nlogs: []\n".getBytes(StandardCharsets.UTF_8));
                Path output=root.resolve("validate-"+pass+".out"),error=root.resolve("validate-"+pass+".err");
                Process cli=new ProcessBuilder(java.toString(),"-jar",jar.toString(),"validate-config").directory(root.getParent().toFile()).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
                cli.getOutputStream().close();boolean done=cli.waitFor(15,TimeUnit.SECONDS);if(!done)cli.destroyForcibly();Assert.assertTrue("Standalone JAR exits",done);
                String text=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);
                Assert.assertEquals(text+new String(Files.readAllBytes(error),StandardCharsets.UTF_8),0,cli.exitValue());
                Assert.assertTrue(text,text.contains(pass==1?root.resolve("saved-output").toString():new ConfigurationManager().load(Paths.get("config.yaml")).outputDirectory.toString()));
            }
        } finally {FileSupport.deleteTree(root);}
    }
    @Test public void packagedJarCollectsFixtureUsingPasswordEnvironment() throws Exception {
        collectFixture(false);
    }
    @Test public void packagedJarUsesConfiguredPasswordWithoutPromptOrOutputLeak() throws Exception {
        collectFixture(true);
    }
    private void collectFixture(boolean embeddedPassword) throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        Path root = Files.createTempDirectory("diagnostic-cli-it-");
        char[] password = ZipEncryptionService.generatePassword();
        try (WindowsAttachIT.Fixture fixture = new WindowsAttachIT.Fixture(false)) {
            Path yaml = root.resolve("config.yaml");
            String settings = "outputDirectory: reports\nresourceMonitoringSeconds: 3\nlogs: []\n";
            if (embeddedPassword) settings += "zipPassword: '" + new String(password) + "'\n";
            Files.write(yaml, settings.getBytes(StandardCharsets.UTF_8));
            Path jar = Paths.get("target/java-diagnostic-collector.jar").toAbsolutePath();
            Path java = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
            Path out = root.resolve("cli.out"), err = root.resolve("cli.err");
            List<String> args = new ArrayList<>(Arrays.asList(java.toString(), "-jar", jar.toString(), "collect", "--local", "--pid", Long.toString(WindowsAttachIT.Fixture.currentPid), "--config", yaml.toString()));
            if (!embeddedPassword) { args.add("--password-env"); args.add("DIAGNOSTIC_IT_PASSWORD"); }
            ProcessBuilder command = new ProcessBuilder(args);
            if (!embeddedPassword) command.environment().put("DIAGNOSTIC_IT_PASSWORD", new String(password));
            Process cli = command.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            cli.getOutputStream().close();
            boolean completed = cli.waitFor(90, TimeUnit.SECONDS);
            if (!completed) { cli.destroyForcibly(); cli.waitFor(5, TimeUnit.SECONDS); }
            Assert.assertTrue("CLI completes in bounded time", completed);
            String output = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            String errors = new String(Files.readAllBytes(err), StandardCharsets.UTF_8);
            Assert.assertTrue(output + errors, cli.exitValue() == 0 || cli.exitValue() == 3);
            Assert.assertTrue(output, output.contains("Doğrulanmış rapor:"));
            Assert.assertFalse(output.contains("ZIP parolası")); Assert.assertFalse(errors.contains("ZIP parolası"));
            Assert.assertFalse(output.contains(new String(password))); Assert.assertFalse(errors.contains(new String(password)));
            Path archive;
            try (java.util.stream.Stream<Path> files = Files.list(root.resolve("reports"))) {
                List<Path> archives = new ArrayList<>(); files.forEach(archives::add); Assert.assertEquals(1, archives.size()); archive = archives.get(0);
            }
            try (ZipFile zip = new ZipFile(archive.toFile(), password);
                 InputStream input = zip.getInputStream(zip.getFileHeader("Manifest.json"))) {
                JsonObject manifest = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                Assert.assertEquals(3, manifest.getAsJsonArray("jvms").get(0).getAsJsonObject().get("dumpCount").getAsInt());
            }
            Assert.assertTrue(fixture.process.isAlive());
            Map<String, Object> evidence = new LinkedHashMap<>(); evidence.put("exitCode", cli.exitValue()); evidence.put("dumpCount", 3); evidence.put("passwordLeaked", false); evidence.put("archiveVerified", true);
            Path dir = Files.createDirectories(Paths.get("target/windows-evidence")); DiagnosticReportGenerator.writeJson(dir.resolve(embeddedPassword ? "cli-embedded-password.json" : "cli.json"), evidence);
        } finally { Arrays.fill(password, '\0'); FileSupport.deleteTree(root); }
    }
}
