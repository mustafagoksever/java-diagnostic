package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;

public class ProcessCommandExecutorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void enforcesTimeoutAndDrainsBothOutputStreams() throws Exception {
        Path root = temp.getRoot().toPath(), out = root.resolve("out"), err = root.resolve("err");
        Path java = Paths.get(System.getProperty("java.home"), "bin", PowerShell.isWindows() ? "java.exe" : "java");
        long start = System.nanoTime();
        CommandResult result = new ProcessCommandExecutor().run(Arrays.asList(java.toString(), "-cp", Paths.get("target/test-classes").toAbsolutePath().toString(), "tr.diagnostic.fixtures.SlowCommand"), 1500, out, err);
        Assert.assertTrue(result.timedOut); Assert.assertFalse(result.success()); Assert.assertTrue(result.stdout.contains("stdout ready")); Assert.assertTrue(result.stderr.contains("stderr ready"));
        Assert.assertTrue("Bounded timeout cleanup", (System.nanoTime() - start) / 1000000 < 6000);
    }
    @Test public void missingToolIsReportedWithoutInventingAnExitCode() throws Exception {
        Path root = temp.getRoot().toPath();
        CommandResult result = new ProcessCommandExecutor().run(Collections.singletonList(root.resolve("missing-tool.exe").toString()), 1000, root.resolve("out"), root.resolve("err"));
        Assert.assertFalse(result.success()); Assert.assertEquals(-1, result.exitCode); Assert.assertTrue(result.error().startsWith("TOOL_START_FAILED"));
    }
}
