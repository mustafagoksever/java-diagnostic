package tr.diagnostic;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ResourceMonitorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void calculatesAveragesPeaksAndCpuNormalizationWithoutInventingMissingSamples() throws Exception {
        JsonObject result = JsonParser.parseString("{logicalProcessors:4, samples:[{sampledAtUtc:'t1',elapsedSeconds:0,cpuTotal:[{PercentProcessorTime:20}],operatingSystem:[{TotalVisibleMemorySize:8388608,FreePhysicalMemory:4194304}],processes:[{IDProcess:42,Name:'java',PercentProcessorTime:200,WorkingSet:104857600}]},{sampledAtUtc:'t2',elapsedSeconds:1,cpuTotal:[{PercentProcessorTime:40}],operatingSystem:[{TotalVisibleMemorySize:8388608,FreePhysicalMemory:2097152}]},{sampledAtUtc:'t3',elapsedSeconds:2}]} ").getAsJsonObject();
        Path staging = temp.getRoot().toPath(); List<String> errors = new ArrayList<>();
        new ResourceMonitor(new CollectorConfiguration(), new ProcessCommandExecutor()).write(result, staging, errors);
        String text = new String(Files.readAllBytes(staging.resolve("kaynak-izleme-ozeti.txt")), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("30,00 / 40,00")); Assert.assertTrue(text.contains("62,50 / 75,00"));
        Assert.assertTrue(text.contains("50,00 / 50,00")); Assert.assertTrue(text.contains("100,00 / 100,00"));
        String csv = new String(Files.readAllBytes(staging.resolve("kaynak-izleme.csv")), StandardCharsets.UTF_8);
        Assert.assertTrue(csv.contains("t3,2.000,,,,,,")); Assert.assertTrue(errors.isEmpty());
    }
    @Test public void recordsMissingCpuAndRamAsCollectionError() throws Exception {
        List<String> errors = new ArrayList<>();
        new ResourceMonitor(new CollectorConfiguration(), new ProcessCommandExecutor()).write(new JsonObject(), temp.getRoot().toPath(), errors);
        Assert.assertEquals(1, errors.size());
    }
}
