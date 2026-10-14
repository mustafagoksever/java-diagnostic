package tr.diagnostic.fixtures;
public final class SlowCommand {
    public static void main(String[] args) throws Exception {
        System.out.println("stdout ready"); System.err.println("stderr ready");
        System.out.flush(); System.err.flush(); Thread.sleep(30000);
    }
}
