package tr.diagnostic.fixtures;

/** Only an integration-test fixture; never part of the production JAR. */
public final class TestJvm {
    public static void main(String[] args) throws Exception {
        System.out.println("READY " + java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]); System.out.flush();
        Thread sleeper = new Thread(() -> {
            try { Thread.sleep(300000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "diagnostic-fixture-worker");
        sleeper.start();
        // Closing stdin requests a graceful exit; no external kill of a target JVM.
        while (System.in.read() != -1) { }
        sleeper.interrupt(); sleeper.join();
    }
}
