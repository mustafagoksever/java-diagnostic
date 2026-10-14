package tr.diagnostic;

import org.junit.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the real Swing event queue and scanner; creates no visible test windows. */
public class SwingUiIT {
    @Test public void startupConfigFailureShowsDialogOnEdtAndReturnsFailure() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        java.util.concurrent.FutureTask<Integer> launch=new java.util.concurrent.FutureTask<>(()->Main.run(new String[]{"gui","--config","target/nonexistent-startup-config.yaml"}));
        Thread runner=new Thread(launch,"startup-dialog-test");runner.start();
        boolean[] shown={false};long deadline=System.nanoTime()+10_000_000_000L;
        try {
            while(!shown[0] && System.nanoTime()<deadline) {
                SwingUtilities.invokeAndWait(()->{
                    for(Window window:Window.getWindows())if(window instanceof JDialog && window.isShowing() && "Başlatma hatası".equals(((JDialog)window).getTitle())) {
                        shown[0]=true;Assert.assertTrue(SwingUtilities.isEventDispatchThread());window.dispose();
                    }
                });
                if(!shown[0])Thread.sleep(50);
            }
            Assert.assertTrue("Visible startup error without console",shown[0]);
            Assert.assertEquals(Integer.valueOf(1),launch.get(5,java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            SwingUtilities.invokeAndWait(()->{for(Window window:Window.getWindows())if(window instanceof JDialog && "Başlatma hatası".equals(((JDialog)window).getTitle()))window.dispose();});
            runner.join(5000);
        }
    }
    @Test public void defaultsToRemoteWithOnlySessionCredentialsAndCanSwitchToLocal() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        CollectorConfiguration config = new CollectorConfiguration(); config.outputDirectory = Paths.get("reports").toAbsolutePath();
        SwingUtilities.invokeAndWait(() -> {
            CollectorFrame frame = new CollectorFrame(config);
            try {
                frame.addNotify(); frame.validate();
                JComboBox<?> mode = findMode(frame.getContentPane()); JPasswordField password = findPassword(frame.getContentPane());
                Assert.assertEquals("Uzak Sunucu", mode.getSelectedItem()); Assert.assertNotNull(password); Assert.assertEquals(0, password.getPassword().length);
                Assert.assertEquals("Only username and password are text fields", 2, countTextFields(frame.getContentPane()));
                Assert.assertFalse("Server settings not visible", containsText(frame.getContentPane(), config.remoteHost) || containsText(frame.getContentPane(), config.remoteJdkHome));
                frame.getContentPane().setSize(frame.getWidth(), frame.getHeight()); layout(frame.getContentPane());
                BufferedImage preview = new BufferedImage(frame.getContentPane().getWidth(), frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=preview.createGraphics();frame.getContentPane().printAll(graphics);graphics.dispose();
                try { Path dir=Files.createDirectories(Paths.get("target/windows-evidence"));javax.imageio.ImageIO.write(preview,"png",dir.resolve("swing-remote-preview.png").toFile()); } catch(Exception e){throw new RuntimeException(e);}
                password.setText("session-only"); mode.setSelectedItem("Yerel Bilgisayar");
                Assert.assertEquals(0,password.getPassword().length);Assert.assertFalse(password.getParent().isVisible());
            } finally {frame.dispose();}
        });
    }
    @Test public void listingKeepsEdtResponsiveAndRendersTurkishUi() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path root = Files.createTempDirectory("diagnostic-ui-it-");
        CollectorConfiguration config = new CollectorConfiguration(); config.outputDirectory = root;
        config.mode = "local";
        AtomicReference<CollectorFrame> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                CollectorFrame frame = new CollectorFrame(config); ref.set(frame);
                frame.addNotify(); frame.validate(); // Real peer/layout without showing a window.
                findButton(frame.getContentPane(), "Java Süreçlerini Listele").doClick();
                Assert.assertFalse(findButton(frame.getContentPane(), "Hata Raporu Oluştur").isEnabled());
            });
            long tick = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> Assert.assertNotNull(ref.get()));
            Assert.assertTrue("EDT responds while Windows scanner runs", (System.nanoTime() - tick) / 1000000 < 1000);
            long deadline = System.nanoTime() + 90_000_000_000L;
            boolean[] done = {false};
            while (System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> done[0] = findButton(ref.get().getContentPane(), "Java Süreçlerini Listele").isEnabled());
                if (done[0]) break;
                Thread.sleep(100);
            }
            Assert.assertTrue("Windows scanner finishes", done[0]);
            SwingUtilities.invokeAndWait(() -> {
                CollectorFrame frame = ref.get();
                JTextArea activity = findTextArea(frame.getContentPane());
                Assert.assertTrue(activity.getText(), activity.getText().contains("süreç bulundu"));
                JTable table = findTable(frame.getContentPane());
                Assert.assertNotNull(table);
                boolean ownVisible = false;
                for (int row = 0; row < table.getRowCount(); row++) {
                    if (((Number) table.getValueAt(row, 0)).longValue() == ProcessScanner.currentPid()) {
                        ownVisible = true; Assert.assertEquals("Collector — hariç", table.getValueAt(row, 7));
                    }
                }
                Assert.assertTrue("Own JVM visible with exclusion label", ownVisible);
                Assert.assertNotNull(findButton(frame.getContentPane(), "Çıktı Klasörünü Aç"));
                frame.getContentPane().setSize(frame.getWidth(), frame.getHeight());
                layout(frame.getContentPane());
                BufferedImage image = new BufferedImage(frame.getContentPane().getWidth(), frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); frame.getContentPane().printAll(graphics); graphics.dispose();
                try {
                    Path dir = Files.createDirectories(Paths.get("target/windows-evidence"));
                    javax.imageio.ImageIO.write(image, "png", dir.resolve("swing-preview.png").toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            if (ref.get() != null) SwingUtilities.invokeAndWait(() -> ref.get().dispose());
            FileSupport.deleteTree(root);
        }
    }
    private static JButton findButton(Container parent, String label) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton && ((JButton) c).getText().equals(label)) return (JButton) c;
            if (c instanceof Container) { JButton found = findButton((Container) c, label); if (found != null) return found; }
        }
        return null;
    }
    private static JTextArea findTextArea(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JTextArea) return (JTextArea) c;
            if (c instanceof Container) { JTextArea found = findTextArea((Container) c); if (found != null) return found; }
        }
        return null;
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container) layout((Container) c); }
    private static JTable findTable(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JTable) return (JTable) c;
            if (c instanceof Container) { JTable found = findTable((Container) c); if (found != null) return found; }
        }
        return null;
    }
    private static JComboBox<?> findMode(Container parent) {for(Component c:parent.getComponents()){if(c instanceof JComboBox)return (JComboBox<?>)c;if(c instanceof Container){JComboBox<?> found=findMode((Container)c);if(found!=null)return found;}}return null;}
    private static JPasswordField findPassword(Container parent) {for(Component c:parent.getComponents()){if(c instanceof JPasswordField)return (JPasswordField)c;if(c instanceof Container){JPasswordField found=findPassword((Container)c);if(found!=null)return found;}}return null;}
    private static int countTextFields(Container parent){int count=0;for(Component c:parent.getComponents()){if(c instanceof JTextField)count++;else if(c instanceof Container)count+=countTextFields((Container)c);}return count;}
    private static boolean containsText(Container parent,String value){for(Component c:parent.getComponents()){String text=c instanceof JLabel?((JLabel)c).getText():c instanceof JTextField?((JTextField)c).getText():c instanceof JTextArea?((JTextArea)c).getText():"";if(text!=null&&text.contains(value))return true;if(c instanceof Container&&containsText((Container)c,value))return true;}return false;}
}
