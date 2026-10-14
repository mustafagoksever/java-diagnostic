package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import java.nio.file.*;
import java.util.*;

/** Actual Windows counters over a full minute; separate from unit tests. */
public class ResourceMonitorIT {
    @Test public void samplesRealWindowsForSixtySeconds() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());
        CollectorConfiguration c = new CollectorConfiguration(); c.resourceMonitoringSeconds = 60;
        ResourceMonitor monitor = new ResourceMonitor(c, new ProcessCommandExecutor());
        JsonObject result = monitor.sample(Collections.emptyList());
        double duration = result.get("actualDurationSeconds").getAsDouble();
        Assert.assertTrue(duration >= 59.9 && duration < 120);
        JsonArray samples = result.getAsJsonArray("samples"); Assert.assertTrue(samples.size() >= 2);
        double previous = -1; int valid = 0;
        for (JsonElement e : samples) {
            JsonObject sample = e.getAsJsonObject(); double elapsed = sample.get("elapsedSeconds").getAsDouble();
            Assert.assertTrue(elapsed > previous); previous = elapsed;
            ServerResourceUsage u = ServerResourceUsage.fromWindows(sample);
            if (u.cpuUsagePercent != null && u.ramUsagePercent != null) valid++;
        }
        Assert.assertTrue("Real CPU/RAM counters available", valid >= 2);
        Path evidence = Files.createDirectories(Paths.get("target/windows-evidence/minute-monitor"));
        List<String> errors = new ArrayList<>(); monitor.write(result, evidence, errors);
        Assert.assertTrue(errors.toString(), errors.isEmpty());
    }
}
