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
    @Test public void normalScreenHasOnlyCredentialsAndReportActions() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        CollectorConfiguration config = new CollectorConfiguration(); config.outputDirectory = Paths.get("reports").toAbsolutePath();
        SwingUtilities.invokeAndWait(() -> {
            CollectorFrame frame = new CollectorFrame(config);
            try {
                frame.addNotify(); frame.validate();
                JPasswordField password = findPassword(frame.getContentPane());
                Assert.assertNull(findMode(frame.getContentPane()));Assert.assertNotNull(password);Assert.assertEquals(0,password.getPassword().length);
                Assert.assertNull(findTable(frame.getContentPane()));Assert.assertNull(findTextArea(frame.getContentPane()));
                Assert.assertNull(findButton(frame.getContentPane(),"Java Süreçlerini Listele"));Assert.assertNull(findButton(frame.getContentPane(),"Logları Temizle"));
                Assert.assertNotNull(findButton(frame.getContentPane(),"Hata Raporu Oluştur"));Assert.assertNotNull(findButton(frame.getContentPane(),"Çıktı Klasörünü Aç"));
                Assert.assertEquals("Only username and password are text fields", 2, countTextFields(frame.getContentPane()));
                Assert.assertFalse("Server settings not visible", containsText(frame.getContentPane(), config.remoteHost) || containsText(frame.getContentPane(), config.remoteJdkHome));
                frame.getContentPane().setSize(frame.getWidth(), frame.getHeight()); layout(frame.getContentPane());
                BufferedImage preview = new BufferedImage(frame.getContentPane().getWidth(), frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=preview.createGraphics();frame.getContentPane().printAll(graphics);graphics.dispose();
                try { Path dir=Files.createDirectories(Paths.get("target/windows-evidence"));javax.imageio.ImageIO.write(preview,"png",dir.resolve("swing-user-preview.png").toFile()); } catch(Exception e){throw new RuntimeException(e);}
            } finally {frame.dispose();}
        });
    }
    @Test public void adminScreenSavesServerAddressAndRetainsLocalModeAndLogs() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        java.util.prefs.Preferences store=java.util.prefs.Preferences.userRoot().node("tr/diagnostic/test-admin-ui-"+java.util.UUID.randomUUID());
        CollectorConfiguration config=new CollectorConfiguration();config.outputDirectory=Paths.get("reports").toAbsolutePath();
        AtomicReference<CollectorFrame> ref=new AtomicReference<>();
        try{
            SwingUtilities.invokeAndWait(()->{
                CollectorFrame frame=new CollectorFrame(config,true,new ServerEndpointSettings(store));ref.set(frame);frame.addNotify();frame.validate();
                Assert.assertNotNull(findMode(frame.getContentPane()));Assert.assertNotNull(findTable(frame.getContentPane()));Assert.assertNotNull(findTextArea(frame.getContentPane()));
                Assert.assertEquals(3,countTextFields(frame.getContentPane()));
                findField(frame.getContentPane(),"serverHost").setText("192.0.2.25");
                findField(frame.getContentPane(),"username").setText("session-user");findPassword(frame.getContentPane()).setText("session-password");
                findButton(frame.getContentPane(),"Sunucu IP'sini Kaydet").doClick();
            });
            long limit=System.nanoTime()+10_000_000_000L;boolean[] saved={false};
            while(System.nanoTime()<limit){SwingUtilities.invokeAndWait(()->saved[0]=findButton(ref.get().getContentPane(),"Sunucu IP'sini Kaydet").isEnabled());if(saved[0])break;Thread.sleep(50);}
            Assert.assertTrue("Address save completes",saved[0]);Assert.assertEquals("192.0.2.25",config.remoteHost);
            Assert.assertEquals("192.0.2.25",new ServerEndpointSettings(store).hostOrDefault("fallback"));Assert.assertArrayEquals(new String[]{"serverHost"},store.keys());
            SwingUtilities.invokeAndWait(()->{
                CollectorFrame frame=ref.get();Assert.assertTrue(findTextArea(frame.getContentPane()).getText().contains("kaydedildi"));
                frame.getContentPane().setSize(frame.getWidth(),frame.getHeight());layout(frame.getContentPane());
                BufferedImage image=new BufferedImage(frame.getContentPane().getWidth(),frame.getContentPane().getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=image.createGraphics();frame.getContentPane().printAll(graphics);graphics.dispose();
                try{Path dir=Files.createDirectories(Paths.get("target/windows-evidence"));javax.imageio.ImageIO.write(image,"png",dir.resolve("swing-admin-preview.png").toFile());}catch(Exception e){throw new RuntimeException(e);}
                JPasswordField password=findPassword(frame.getContentPane());findMode(frame.getContentPane()).setSelectedItem("Yerel Bilgisayar");
                Assert.assertEquals(0,password.getPassword().length);Assert.assertFalse(password.getParent().isVisible());
            });
        }finally{if(ref.get()!=null)SwingUtilities.invokeAndWait(()->ref.get().dispose());store.removeNode();java.util.prefs.Preferences.userRoot().flush();}
    }
    @Test public void listingKeepsEdtResponsiveAndRendersTurkishUi() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path root = Files.createTempDirectory("diagnostic-ui-it-");
        CollectorConfiguration config = new CollectorConfiguration(); config.outputDirectory = root;
        config.mode = "local";
        AtomicReference<CollectorFrame> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                CollectorFrame frame = new CollectorFrame(config,true); ref.set(frame);
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
    @Test public void failedNormalCollectionKeepsTechnicalCauseOffEdtAndShowsOnlyRecordLocation() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows()); Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path root = Files.createTempDirectory("diagnostic-failure-ui-");
        CollectorConfiguration config = new CollectorConfiguration();
        config.outputDirectory = root.resolve("output-is-a-file"); Files.write(config.outputDirectory, new byte[]{1});
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Path> record = new AtomicReference<>();
        AtomicReference<CollectorFrame> ref = new AtomicReference<>();
        FailureReportWriter writer = new FailureReportWriter(root.resolve("fallback")) {
            @Override Path write(Path output, String operation, Throwable error, String... secrets) throws java.io.IOException {
                Assert.assertFalse("File writing must not run on EDT", SwingUtilities.isEventDispatchThread());
                writing.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Test release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                Path path = super.write(output, operation, error, secrets); record.set(path); return path;
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                CollectorFrame frame = new CollectorFrame(config, false, new ServerEndpointSettings(), writer); ref.set(frame);
                frame.addNotify(); frame.validate();
                findField(frame.getContentPane(), "username").setText("session-user");
                findPassword(frame.getContentPane()).setText("session-secret");
                findButton(frame.getContentPane(), "Hata Raporu Oluştur").doClick();
            });
            Assert.assertTrue("Failure reaches background writer", writing.await(10, java.util.concurrent.TimeUnit.SECONDS));
            long tick = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> Assert.assertFalse(findButton(ref.get().getContentPane(), "Hata Raporu Oluştur").isEnabled()));
            Assert.assertTrue("EDT stays responsive during logging", (System.nanoTime() - tick) / 1_000_000 < 1000);
            release.countDown();
            boolean[] shown = {false}; long deadline = System.nanoTime() + 10_000_000_000L;
            while (!shown[0] && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) if (window instanceof JDialog && window.getOwner() == ref.get() && window.isShowing()) {
                        try {
                            Assert.assertNotNull(record.get());
                            Assert.assertTrue(containsText((Container) window, record.get().toString()));
                            Assert.assertFalse("No technical cause in normal dialog", containsText((Container) window, "FileAlreadyExistsException"));
                            Assert.assertFalse(containsText((Container) window, "session-user") || containsText((Container) window, "session-secret"));
                            shown[0] = true;
                        } finally { window.dispose(); }
                    }
                });
                if (!shown[0]) Thread.sleep(50);
            }
            Assert.assertTrue("Failure dialog shows support record location", shown[0]);
            String text = new String(Files.readAllBytes(record.get()), java.nio.charset.StandardCharsets.UTF_8);
            Assert.assertTrue(text, text.contains("FileAlreadyExistsException") && text.contains("Rapor oluşturma") && text.contains("RemoteCollectionOrchestrator"));
            Assert.assertFalse(text, text.contains("session-user") || text.contains("session-secret"));
        } finally {
            release.countDown();
            if (ref.get() != null) SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) if (window.getOwner() == ref.get()) window.dispose();
                ref.get().dispose();
            });
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
    private static JTextField findField(Container parent,String name){for(Component c:parent.getComponents()){if(c instanceof JTextField&&name.equals(c.getName()))return (JTextField)c;if(c instanceof Container){JTextField found=findField((Container)c,name);if(found!=null)return found;}}return null;}
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
