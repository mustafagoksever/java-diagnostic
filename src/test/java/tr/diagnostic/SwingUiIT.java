package tr.diagnostic;

import org.junit.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the real Swing event queue and scanner; creates no visible test windows. */
public class SwingUiIT {
    @Test public void settingsModeChangesRefreshSourceControlsAndSaveTheSelectedMode() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path root=Files.createTempDirectory("diagnostic-settings-mode-");
        AtomicReference<SettingsDialog> ref=new AtomicReference<>();AtomicReference<CollectorConfiguration> applied=new AtomicReference<>();
        try {
            Path install=Files.createDirectory(root.resolve("install"));
            Path yaml=Files.write(root.resolve("config.yaml"),("mode: local\nremote: {host: '192.0.2.1'}\ninstallationDirectory: '"+install.toString().replace('\\','/')+"'\napplicationDirectory: '.'\nlogs: [{base: config, path: logs}]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ConfigurationFile file=new ConfigurationFile(yaml);CollectorConfiguration config=file.load();
            SwingUtilities.invokeAndWait(()->{
                SettingsDialog dialog=new SettingsDialog(null,config,file,true,applied::set);ref.set(dialog);dialog.addNotify();dialog.validate();
                JComboBox<?> mode=(JComboBox<?>)findNamed(dialog.getContentPane(),"setting.mode");JTable table=findTable(dialog.getContentPane());
                Assert.assertTrue(findButton(dialog.getContentPane(),"Klasör Seç").isVisible());
                Assert.assertTrue(containsText(dialog.getContentPane(),"Kaynaklar bu bilgisayardan"));
                for(String selection:new String[]{"remote","local","remote"}){
                    mode.setSelectedItem(selection);boolean local="local".equals(selection);
                    Assert.assertEquals(local,findButton(dialog.getContentPane(),"Klasör Seç").isVisible());
                    Assert.assertTrue(containsText(dialog.getContentPane(),local?"Kaynaklar bu bilgisayardan":"Kaynak yollarını sunucudaki"));
                    JLabel renderer=(JLabel)table.prepareRenderer(table.getCellRenderer(0,1),0,1);
                    Assert.assertEquals(local?"Konfigürasyon klasörü":"Kurulum dizini",renderer.getText());
                    Assert.assertEquals("logs",table.getValueAt(0,0));Assert.assertEquals("config",table.getValueAt(0,1));
                }
                findButton(dialog.getContentPane(),"Kaydet").doClick();
            });
            long limit=System.nanoTime()+10_000_000_000L;while(applied.get()==null&&System.nanoTime()<limit)Thread.sleep(25);
            Assert.assertNotNull("Settings saved",applied.get());Assert.assertEquals("remote",applied.get().mode);Assert.assertEquals("remote",file.load().mode);
            SwingUtilities.invokeAndWait(()->Assert.assertFalse(ref.get().isDisplayable()));
        } finally {if(ref.get()!=null)SwingUtilities.invokeAndWait(()->ref.get().dispose());FileSupport.deleteTree(root);}
    }
    @Test public void normalGuiHonorsExplicitLocalModeWithoutServerCredentials() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path root=Files.createTempDirectory("diagnostic-local-gui-");String title="Local fixture "+java.util.UUID.randomUUID();
        try {
            Path yaml=Files.write(root.resolve("config.yaml"),("applicationTitle: '"+title+"'\nmode: remote\nlogs: []\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Assert.assertEquals(-1,Main.run(new String[]{"gui","--local","--config",yaml.toString()}));
            boolean[] found={false};SwingUtilities.invokeAndWait(()->{
                for(Window window:Window.getWindows())if(window instanceof CollectorFrame&&title.equals(((CollectorFrame)window).getTitle())){
                    CollectorFrame frame=(CollectorFrame)window;found[0]=true;
                    Assert.assertFalse("Local collection needs no server password",findPassword(frame.getContentPane()).getParent().isVisible());
                    Assert.assertNull(findMode(frame.getContentPane()));Assert.assertTrue(containsText(frame.getContentPane(),"Bu bilgisayarda rapor oluşturulacak"));
                }
            });Assert.assertTrue(found[0]);
        } finally {
            SwingUtilities.invokeAndWait(()->{for(Window window:Window.getWindows())if(window instanceof CollectorFrame&&title.equals(((CollectorFrame)window).getTitle()))window.dispose();});
            FileSupport.deleteTree(root);
        }
    }
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
        config.remoteHost = "fixture-server";
        config.applicationTitle = "Özel Tanılama Başlığı";
        java.lang.reflect.Method update = CollectorFrame.class.getDeclaredMethod("showCollectionProgress", CollectionProgress.Event.class);
        update.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            CollectorFrame frame = new CollectorFrame(config);
            try {
                Assert.assertEquals(config.applicationTitle, frame.getTitle());
                Assert.assertEquals("Ayarlar",frame.getJMenuBar().getMenu(0).getText());
                frame.addNotify(); frame.validate();
                JPasswordField password = findPassword(frame.getContentPane());
                Assert.assertNull(findMode(frame.getContentPane()));Assert.assertNotNull(password);Assert.assertEquals(0,password.getPassword().length);
                Assert.assertNull(findTable(frame.getContentPane()));Assert.assertNull(findTextArea(frame.getContentPane()));
                Assert.assertNull(findButton(frame.getContentPane(),"Java Süreçlerini Listele"));Assert.assertNull(findButton(frame.getContentPane(),"Logları Temizle"));
                Assert.assertNotNull(findButton(frame.getContentPane(),"Hata Raporu Oluştur"));Assert.assertNotNull(findButton(frame.getContentPane(),"Çıktı Klasörünü Aç"));
                Assert.assertEquals("Only username and password are text fields", 2, countTextFields(frame.getContentPane()));
                Assert.assertFalse("Server settings not visible", containsText(frame.getContentPane(), config.remoteHost) || containsText(frame.getContentPane(), config.remoteJdkHome));
                try {
                    update.invoke(frame, new CollectionProgress.Event(65, "Log dosyaları hazırlanıyor..."));
                    update.invoke(frame, new CollectionProgress.Event(12, "Gecikmiş olay"));
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                JProgressBar bar = findProgress(frame.getContentPane());
                Assert.assertEquals("Late events cannot move progress backwards", 65, bar.getValue());
                Assert.assertTrue(bar.getString(), bar.getString().contains("%65") && bar.getString().contains("Log dosyaları"));
                Assert.assertFalse(bar.isIndeterminate());
                frame.getContentPane().setSize(frame.getWidth(), frame.getHeight()); layout(frame.getContentPane());
                BufferedImage preview = new BufferedImage(frame.getContentPane().getWidth(), frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=preview.createGraphics();frame.getContentPane().printAll(graphics);graphics.dispose();
                try { Path dir=Files.createDirectories(Paths.get("target/windows-evidence"));javax.imageio.ImageIO.write(preview,"png",dir.resolve("swing-user-preview.png").toFile()); } catch(Exception e){throw new RuntimeException(e);}
            } finally {frame.dispose();}
        });
    }
    @Test public void adminScreenSavesServerAddressAndRetainsLocalModeAndLogs() throws Exception {
        Assume.assumeTrue(PowerShell.isWindows());Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path settingsRoot=Files.createTempDirectory("diagnostic-settings-ui-");
        Path configPath=Files.write(settingsRoot.resolve("config.yaml"),"remote: {host: '192.0.2.1'}\nlogs: []\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ConfigurationFile file=new ConfigurationFile(configPath);
        CollectorConfiguration config=new CollectorConfiguration();config.outputDirectory=Paths.get("reports").toAbsolutePath();
        AtomicReference<CollectorFrame> ref=new AtomicReference<>();
        try{
            SwingUtilities.invokeAndWait(()->{
                CollectorFrame frame=new CollectorFrame(config,true,file);ref.set(frame);frame.addNotify();frame.validate();
                Assert.assertEquals(config.applicationTitle + " — Admin", frame.getTitle());
                Assert.assertNotNull(findMode(frame.getContentPane()));Assert.assertNotNull(findTable(frame.getContentPane()));Assert.assertNotNull(findTextArea(frame.getContentPane()));
                Assert.assertEquals(2,countTextFields(frame.getContentPane()));
                Assert.assertEquals("Ayarlar",frame.getJMenuBar().getMenu(0).getText());
                findField(frame.getContentPane(),"username").setText("session-user");findPassword(frame.getContentPane()).setText("session-password");
            });
            SwingUtilities.invokeLater(()->ref.get().getJMenuBar().getMenu(0).getItem(0).doClick());
            AtomicReference<JDialog> dialog=new AtomicReference<>();
            long openDeadline=System.nanoTime()+5_000_000_000L;
            while(dialog.get()==null&&System.nanoTime()<openDeadline){SwingUtilities.invokeAndWait(()->{for(Window w:Window.getWindows())if(w instanceof SettingsDialog&&w.getOwner()==ref.get()&&w.isShowing())dialog.set((JDialog)w);});Thread.sleep(25);}
            Assert.assertNotNull("Settings menu opens dialog",dialog.get());
            SwingUtilities.invokeAndWait(()->{
                JDialog settings=dialog.get();
                findField(settings.getContentPane(),"settingsHost").setText("192.0.2.25");
                findField(settings.getContentPane(),"settingsInstallation").setText("D:/ESM");
                findField(settings.getContentPane(),"settingsApplication").setText("app");
                findButton(settings.getContentPane(),"Dizin Ekle").doClick();
                JTable sources=findTable(settings.getContentPane());sources.setValueAt("Log",0,0);sources.setValueAt("application",0,1);sources.setValueAt("app-logs",0,2);
                sources.setValueAt("250",0,4);sources.setValueAt("72",0,5);sources.setValueAt("log, .txt",0,6);
                findField(settings.getContentPane(),"setting.applicationTitle").setText("Updated settings title");
                JPasswordField zip=(JPasswordField)findField(settings.getContentPane(),"setting.zipPassword");Assert.assertEquals(0,zip.getPassword().length);zip.setText("ui-archive-secret");
                ((JSpinner)findNamed(settings.getContentPane(),"setting.remote.vxworks.maxOutputMb")).setValue(24);
                ((JSpinner)findNamed(settings.getContentPane(),"setting.resourceMonitoringSeconds")).setValue(17);
                ((JCheckBox)findNamed(settings.getContentPane(),"setting.remote.windowsDiagnostics")).setSelected(false);
                preview(settings.getContentPane(),"settings-server-preview.png");
                for(Component c:settings.getContentPane().getComponents())if(c instanceof JTabbedPane)((JTabbedPane)c).setSelectedIndex(1);
                preview(settings.getContentPane(),"settings-directories-preview.png");
                for(Component c:settings.getContentPane().getComponents())if(c instanceof JTabbedPane){
                    JTabbedPane tabs=(JTabbedPane)c;Assert.assertEquals(6,tabs.getTabCount());
                    for(int tab=2;tab<tabs.getTabCount();tab++){tabs.setSelectedIndex(tab);preview(settings.getContentPane(),"settings-tab-"+tab+"-preview.png");}
                }
                findButton(settings.getContentPane(),"Kaydet").doClick();
            });
            long limit=System.nanoTime()+10_000_000_000L;boolean[] saved={false};
            while(System.nanoTime()<limit){SwingUtilities.invokeAndWait(()->saved[0]=!dialog.get().isDisplayable());if(saved[0])break;Thread.sleep(50);}
            Assert.assertTrue("Address save completes",saved[0]);Assert.assertEquals("192.0.2.25",config.remoteHost);
            CollectorConfiguration reloaded=file.load();Assert.assertEquals("192.0.2.25",reloaded.remoteHost);Assert.assertEquals("D:/ESM",reloaded.installationPath);Assert.assertEquals("Log",reloaded.logs.get(0).sourcePath);
            Assert.assertEquals("app-logs",config.logs.get(0).destination);
            Assert.assertEquals(250L*1024*1024,reloaded.logs.get(0).maxFileSizeBytes);Assert.assertEquals(72,reloaded.logs.get(0).modifiedWithinHours);Assert.assertEquals(java.util.Arrays.asList(".log",".txt"),reloaded.logs.get(0).extensions);
            Assert.assertEquals("Updated settings title",config.applicationTitle);Assert.assertEquals(24,config.vxworks.maxOutputMb);Assert.assertEquals(17,config.resourceMonitoringSeconds);Assert.assertFalse(config.remoteWindowsDiagnostics);
            Assert.assertEquals(config.applicationTitle,reloaded.applicationTitle);Assert.assertEquals(config.vxworks.maxOutputMb,reloaded.vxworks.maxOutputMb);
            Assert.assertEquals("ui-archive-secret",new String(config.zipPasswordCopy()));Assert.assertEquals("ui-archive-secret",new String(reloaded.zipPasswordCopy()));
            String savedYaml=new String(Files.readAllBytes(configPath),java.nio.charset.StandardCharsets.UTF_8);Assert.assertFalse(savedYaml.contains("session-user")||savedYaml.contains("session-password"));
            SwingUtilities.invokeAndWait(()->{
                CollectorFrame frame=ref.get();Assert.assertTrue(findTextArea(frame.getContentPane()).getText().contains("kaydedildi"));
                frame.getContentPane().setSize(frame.getWidth(),frame.getHeight());layout(frame.getContentPane());
                BufferedImage image=new BufferedImage(frame.getContentPane().getWidth(),frame.getContentPane().getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=image.createGraphics();frame.getContentPane().printAll(graphics);graphics.dispose();
                try{Path dir=Files.createDirectories(Paths.get("target/windows-evidence"));javax.imageio.ImageIO.write(image,"png",dir.resolve("swing-admin-preview.png").toFile());}catch(Exception e){throw new RuntimeException(e);}
                JPasswordField password=findPassword(frame.getContentPane());findMode(frame.getContentPane()).setSelectedItem("Yerel Bilgisayar");
                Assert.assertEquals(0,password.getPassword().length);Assert.assertFalse(password.getParent().isVisible());
            });
        }finally{if(ref.get()!=null)SwingUtilities.invokeAndWait(()->{for(Window window:Window.getWindows())if(window.getOwner()==ref.get())window.dispose();ref.get().dispose();});FileSupport.deleteTree(settingsRoot);}
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
        config.remoteHost = "fixture-server";
        config.setZipPassword("fixture-archive-password");
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
                CollectorFrame frame = new CollectorFrame(config, false, new ConfigurationFile(root.resolve("config.yaml")), writer); ref.set(frame);
                frame.addNotify(); frame.validate();
                findField(frame.getContentPane(), "username").setText("session-user");
                findPassword(frame.getContentPane()).setText("session-secret");
                findButton(frame.getContentPane(), "Hata Raporu Oluştur").doClick();
            });
            Assert.assertTrue("Failure reaches background writer", writing.await(10, java.util.concurrent.TimeUnit.SECONDS));
            long tick = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> Assert.assertFalse(findButton(ref.get().getContentPane(), "Hata Raporu Oluştur").isEnabled()));
            SwingUtilities.invokeAndWait(() -> {
                JProgressBar bar = findProgress(ref.get().getContentPane());
                Assert.assertNotNull(bar); Assert.assertTrue(bar.isStringPainted());
                Assert.assertFalse("Failed collection stops at its last percentage", bar.isIndeterminate());
                Assert.assertTrue(bar.getValue() < 100); Assert.assertTrue(bar.getString().contains("İşlem tamamlanamadı"));
            });
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
    private static JProgressBar findProgress(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JProgressBar) return (JProgressBar) c;
            if (c instanceof Container) { JProgressBar found = findProgress((Container) c); if (found != null) return found; }
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
    private static Component findNamed(Container parent,String name){for(Component c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container){Component found=findNamed((Container)c,name);if(found!=null)return found;}}return null;}
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container) layout((Container) c); }
    private static void preview(Container parent,String name){
        layout(parent);BufferedImage image=new BufferedImage(parent.getWidth(),parent.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();parent.printAll(graphics);graphics.dispose();
        try{Path dir=Files.createDirectories(Paths.get("target/windows-evidence"));javax.imageio.ImageIO.write(image,"png",dir.resolve(name).toFile());}catch(Exception e){throw new RuntimeException(e);}
    }
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
