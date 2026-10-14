package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;

public class ServerResourceUsageTest {
    @Test public void calculatesPhysicalRamUsageAndUsesWholeServerCpu() {
        JsonObject windows = JsonParser.parseString("{\"sampledAtUtc\":\"2026-10-13T08:00:00Z\",\"computer\":[{\"Name\":\"TEST-SERVER\"}],\"cpuTotal\":[{\"PercentProcessorTime\":25}],\"processes\":[{\"PercentProcessorTime\":400}],\"operatingSystem\":[{\"TotalVisibleMemorySize\":8388608,\"FreePhysicalMemory\":2097152}]}").getAsJsonObject();
        ServerResourceUsage usage = ServerResourceUsage.fromWindows(windows);
        Assert.assertEquals(25, usage.cpuUsagePercent, 0.001);
        Assert.assertEquals(8, usage.totalRamGiB, 0.001); Assert.assertEquals(2, usage.freeRamGiB, 0.001);
        Assert.assertEquals(6, usage.usedRamGiB, 0.001); Assert.assertEquals(75, usage.ramUsagePercent, 0.001);
        Assert.assertTrue(usage.asText().contains("TEST-SERVER")); Assert.assertTrue(usage.asText().contains("6,00 GiB")); Assert.assertTrue(usage.asText().contains("25,00 %"));
    }
    @Test public void acceptsWmiNumericStringsAndRealZeroCpu() {
        JsonObject windows = JsonParser.parseString("{\"cpuTotal\":[{\"PercentProcessorTime\":\"0\"}],\"operatingSystem\":[{\"TotalVisibleMemorySize\":\"1048576\",\"FreePhysicalMemory\":\"1048576\"}]}").getAsJsonObject();
        ServerResourceUsage usage = ServerResourceUsage.fromWindows(windows);
        Assert.assertEquals(0, usage.cpuUsagePercent, 0.001); Assert.assertEquals(0, usage.usedRamGiB, 0.001); Assert.assertEquals(0, usage.ramUsagePercent, 0.001);
    }
    @Test public void missingAndInvalidReadingsAreUnknownInsteadOfInventedZeroes() {
        ServerResourceUsage missing = ServerResourceUsage.fromWindows(null);
        Assert.assertNull(missing.cpuUsagePercent); Assert.assertNull(missing.ramUsagePercent); Assert.assertTrue(missing.asText().contains("Bilinmiyor"));
        for (String cpu : new String[]{"101", "-1", "\"NaN\"", "null"}) {
            JsonObject windows = JsonParser.parseString("{\"cpuTotal\":[{\"PercentProcessorTime\":" + cpu + "}],\"operatingSystem\":[{\"TotalVisibleMemorySize\":100,\"FreePhysicalMemory\":200}]}").getAsJsonObject();
            ServerResourceUsage invalid = ServerResourceUsage.fromWindows(windows);
            Assert.assertNull(invalid.cpuUsagePercent); Assert.assertNull(invalid.usedRamGiB); Assert.assertNull(invalid.ramUsagePercent);
        }
    }
}
