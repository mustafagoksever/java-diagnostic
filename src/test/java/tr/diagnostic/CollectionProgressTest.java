package tr.diagnostic;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.*;
import org.junit.*;

public class CollectionProgressTest {
    private static final class Listener implements CollectionProgress {
        final List<Integer> percentages = new ArrayList<>();
        final List<String> stages = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        @Override public void update(int percent, String stage) { percentages.add(percent); stages.add(stage); }
        @Override public void accept(String message) { logs.add(message); }
    }
    @Test public void transportForwardsStructuredProgressAndKeepsLegacyMessages() {
        Listener listener = new Listener();
        RemotePowerShellTransport.forwardProgress(JsonParser.parseString("{message:'legacy log'}").getAsJsonObject(), listener);
        RemotePowerShellTransport.forwardProgress(JsonParser.parseString("{message:'verified file',percent:78,stage:'Dosyalar doğrulanıyor'}").getAsJsonObject(), listener);
        RemotePowerShellTransport.forwardProgress(JsonParser.parseString("{message:'log',percent:null,stage:null}").getAsJsonObject(), listener);
        RemotePowerShellTransport.forwardProgress(JsonParser.parseString("{message:'premature completion',percent:100,stage:'ready'}").getAsJsonObject(), listener);
        Assert.assertEquals(Arrays.asList(78), listener.percentages);
        Assert.assertEquals(Arrays.asList("Dosyalar doğrulanıyor"), listener.stages);
        Assert.assertEquals(4, listener.logs.size());
    }
    @Test public void failureNeverReportsCompletion() throws Exception {
        Listener listener = new Listener();
        try { CollectionTiming.run(listener, () -> { CollectionProgress.report(listener, 65, "Loglar"); throw new IOException("failure"); }); Assert.fail(); }
        catch (IOException expected) { }
        Assert.assertEquals(Arrays.asList(0, 65), listener.percentages);
    }
}
