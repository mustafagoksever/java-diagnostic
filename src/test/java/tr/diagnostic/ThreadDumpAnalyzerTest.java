package tr.diagnostic;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import tr.diagnostic.DiagnosticReportGenerator.*;

public class ThreadDumpAnalyzerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final String dump = "\"worker\" #1 prio=5 tid=0x01 nid=0x11 waiting\n   java.lang.Thread.State: BLOCKED (on object monitor)\n\tat app.Work.run(Work.java:10)\n\t- waiting to lock <0x123>\n\n\"worker\" #2 prio=5 tid=0x02 nid=0x22 waiting\n   java.lang.Thread.State: WAITING (parking)\n\tat sun.misc.Unsafe.park(Native Method)\n";
    private JvmReport report(int count) throws Exception {
        JavaProcessInfo p = new JavaProcessInfo(); p.pid = 42; JvmReport r = new JvmReport(p);
        for (int i = 1; i <= count; i++) {
            Operation o = new Operation(); o.type = "thread-dump"; o.sample = i; o.success = true; o.artifact = "dump-" + i + ".txt";
            Files.write(temp.getRoot().toPath().resolve(o.artifact), dump.getBytes(StandardCharsets.UTF_8)); r.operations.add(o);
        }
        return r;
    }
    @Test public void distinguishesDuplicateNamesAndRequiresThreeMatchingSamples() throws Exception {
        JvmReport r = report(2); ThreadDumpAnalyzer analyzer = new ThreadDumpAnalyzer();
        ThreadDumpAnalyzer.Analysis two = analyzer.analyze(r, temp.getRoot().toPath());
        Assert.assertEquals(0, two.unchangedThreads.size()); Assert.assertEquals(1, two.blockedThreads.size());
        r = report(3); ThreadDumpAnalyzer.Analysis three = analyzer.analyze(r, temp.getRoot().toPath());
        Assert.assertEquals(2, three.unchangedThreads.size()); Assert.assertEquals(0, three.reportedDeadlocks);
        Files.write(temp.getRoot().toPath().resolve("dump-2.txt"), dump.replace("Work.java:10", "Work.java:11").getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(1, analyzer.analyze(r, temp.getRoot().toPath()).unchangedThreads.size());
    }
    @Test public void usesExplicitDeadlockReportAndIgnoresFailedOperations() throws Exception {
        JvmReport r = report(3);
        Files.write(temp.getRoot().toPath().resolve("dump-1.txt"), (dump + "\nFound one Java-level deadlock:\n").getBytes(StandardCharsets.UTF_8));
        Operation failed = new Operation(); failed.type = "thread-dump"; failed.sample = 1; failed.success = false; failed.artifact = "missing.txt"; r.operations.add(0, failed);
        ThreadDumpAnalyzer.Analysis a = new ThreadDumpAnalyzer().analyze(r, temp.getRoot().toPath());
        Assert.assertEquals(3, a.samples); Assert.assertEquals(1, a.reportedDeadlocks);
    }
    @Test public void detectsUiNetworkWaitAcrossChangingSocketLocksAndShowsBlockedFollowers() throws Exception {
        JvmReport r=report(3);
        for(int i=1;i<=3;i++){
            String sample="\"AWT-EventQueue-0\" #1 nid=0x11 runnable\n   java.lang.Thread.State: RUNNABLE\n"
                +"\tat java.net.DualStackPlainSocketImpl.waitForConnect(Native Method)\n\t- locked <0x"+i+"> (a java.net.DualStackPlainSocketImpl)\n"
                +"\tat example.CorbaStub.stop(Unknown Source)\n\t- locked <0xabc> (a example.Controller)\n"
                +"\n\"Timer-1\" #2 nid=0x22 waiting\n   java.lang.Thread.State: BLOCKED (on object monitor)\n\tat example.Controller.tick(Unknown Source)\n\t- waiting to lock <0xabc> (a example.Controller)\n";
            Files.write(temp.getRoot().toPath().resolve("dump-"+i+".txt"),sample.getBytes(StandardCharsets.UTF_8));
        }
        ThreadDumpAnalyzer analyzer=new ThreadDumpAnalyzer();ThreadDumpAnalyzer.Analysis a=analyzer.analyze(r,temp.getRoot().toPath());
        Assert.assertEquals(1,a.uiFindings.size());ThreadDumpAnalyzer.UiFinding ui=a.uiFindings.get(0);
        Assert.assertEquals("NETWORK_ON_UI",ui.category);Assert.assertEquals(3,ui.observedSamples);Assert.assertTrue(ui.presentInAllThreeSamples);
        Assert.assertEquals("Timer-1 nid=0x22",ui.threadsWaitingOnHeldLocks.get(0));Assert.assertEquals(0,a.reportedDeadlocks);
        Assert.assertFalse(a.unchangedThreads.stream().anyMatch(t->t.name.equals("AWT-EventQueue-0")));
        analyzer.write(java.util.Collections.singletonList(r),temp.getRoot().toPath());
        String summary=new String(Files.readAllBytes(temp.getRoot().toPath().resolve("analiz-ozeti.txt")),StandardCharsets.UTF_8);
        Assert.assertTrue(summary.contains("NETWORK_ON_UI"));Assert.assertTrue(summary.contains("Timer-1"));
    }
    @Test public void idleUiAndModalEventPumpsAreNotClassifiedAsNetworkFreezes() throws Exception {
        JvmReport r=report(3);
        String idle="\"AWT-EventQueue-0\" #1 nid=0x11 waiting\n   java.lang.Thread.State: WAITING\n\tat java.awt.EventQueue.getNextEvent(Unknown Source)\n";
        for(int i=1;i<=3;i++)Files.write(temp.getRoot().toPath().resolve("dump-"+i+".txt"),idle.getBytes(StandardCharsets.UTF_8));
        ThreadDumpAnalyzer analyzer=new ThreadDumpAnalyzer();Assert.assertTrue(analyzer.analyze(r,temp.getRoot().toPath()).uiFindings.isEmpty());
        for(int i=1;i<=3;i++)Files.write(temp.getRoot().toPath().resolve("dump-"+i+".txt"),(idle+"\tat java.awt.Dialog.show(Unknown Source)\n").getBytes(StandardCharsets.UTF_8));
        ThreadDumpAnalyzer.UiFinding modal=analyzer.analyze(r,temp.getRoot().toPath()).uiFindings.get(0);
        Assert.assertEquals("MODAL_DIALOG_CONTEXT",modal.category);Assert.assertEquals(3,modal.observedSamples);
    }
}
