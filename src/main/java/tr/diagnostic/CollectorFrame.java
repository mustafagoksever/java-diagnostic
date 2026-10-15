package tr.diagnostic;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class CollectorFrame extends JFrame {
    private final CollectorConfiguration config;
    private final JButton collect = new JButton("Hata Raporu Oluştur");
    private final JButton open = new JButton("Çıktı Klasörünü Aç");
    private final JButton list = new JButton("Java Süreçlerini Listele");
    private final JButton clearLog = new JButton("Logları Temizle");
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Uzak Sunucu", "Yerel Bilgisayar"});
    private final JTextField username = new JTextField(20);
    private final JPasswordField serverPassword = new JPasswordField(16);
    private final JPanel remoteFields = new JPanel(new GridBagLayout());
    private final JTextArea activity = new JTextArea(9, 90);
    private final JLabel status = new JLabel("Hazır. Collector süreçleri listelenir; rapora hedef olarak alınmaz.");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultTableModel rows = new DefaultTableModel(new String[]{"PID", "Süreç / Uygulama", "Ana sınıf", "Sahip", "Oturum", "Başlangıç (UTC)", "Java sürümü", "Rapor hedefi"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    public CollectorFrame(CollectorConfiguration config) {
        super("Java 8 Windows Diagnostic Collector"); this.config = config;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (collect.isEnabled()) { serverPassword.setText(""); dispose(); }
                else status.setText("İşlem sürüyor; tamamlandığında pencereyi kapatabilirsiniz.");
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT)); buttons.add(collect); buttons.add(open); buttons.add(list); buttons.add(clearLog);
        clearLog.setToolTipText("Ekrandaki işlem günlüğünü ve Java süreçleri listesini temizler.");
        clearLog.addActionListener(e -> { activity.setText(""); activity.setCaretPosition(0); rows.setRowCount(0); if (collect.isEnabled()) status.setText("İşlem günlüğü ve Java süreçleri listesi temizlendi."); });
        username.setText(config.remoteUsername);
        mode.setSelectedIndex("local".equals(config.mode) ? 1 : 0);
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT)); modeRow.add(new JLabel("Çalışma modu:")); modeRow.add(mode);
        addRemoteField(0, "Windows kullanıcı adı:", username);
        addRemoteField(1, "Sunucu parolası:", serverPassword);
        JPanel connection = new JPanel(new BorderLayout()); connection.add(modeRow, BorderLayout.NORTH); connection.add(remoteFields, BorderLayout.CENTER);
        remoteFields.setVisible(mode.getSelectedIndex() == 0);
        mode.addActionListener(e -> { remoteFields.setVisible(mode.getSelectedIndex() == 0); rows.setRowCount(0); serverPassword.setText(""); append("Mod: " + mode.getSelectedItem() + ". Rapor çıktısı bu bilgisayara kaydedilir."); revalidate(); });
        JPanel top = new JPanel(new BorderLayout(8, 8)); top.add(connection, BorderLayout.NORTH); top.add(buttons, BorderLayout.CENTER); top.add(status, BorderLayout.SOUTH);
        activity.setEditable(false); activity.setLineWrap(true); activity.setWrapStyleWord(true);
        JTable table = new JTable(rows); table.setAutoCreateRowSorter(true);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(activity)); center.setResizeWeight(0.65);
        JPanel root = new JPanel(new BorderLayout(8, 8)); root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(top, BorderLayout.NORTH); root.add(center, BorderLayout.CENTER); root.add(progress, BorderLayout.SOUTH); setContentPane(root);
        collect.addActionListener(e -> startCollection()); list.addActionListener(e -> refresh()); open.addActionListener(e -> openOutput());
        setSize(1200, 780); setMinimumSize(new Dimension(850, 600)); setLocationRelativeTo(null);
    }
    private void addRemoteField(int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(2, 4, 2, 8); c.anchor = GridBagConstraints.WEST; c.gridy = row;
        c.gridx = 0; remoteFields.add(new JLabel(label), c); c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; remoteFields.add(field, c);
    }
    private RemoteConnection connection() {
        if (mode.getSelectedIndex() == 1) return null;
        char[] password = serverPassword.getPassword();
        try { return new RemoteConnection(config.remoteHost, username.getText().trim(), config.remoteJdkHome, password); }
        finally { Arrays.fill(password, '\0'); }
    }
    private void busy(boolean value) { collect.setEnabled(!value); list.setEnabled(!value); open.setEnabled(!value); mode.setEnabled(!value); username.setEnabled(!value); serverPassword.setEnabled(!value); progress.setIndeterminate(value); }
    private String userMessage(String value) { return value == null ? "Bilinmeyen hata" : value.replace(config.remoteHost, "[sunucu]").replace(config.remoteJdkHome, "[JDK]").replace(config.remoteJdkHome.replace('/', '\\'), "[JDK]"); }
    private void append(String value) { value = userMessage(value); activity.append(value + "\n"); activity.setCaretPosition(activity.getDocument().getLength()); status.setText(value); }
    private void appendMessages(List<String> messages) {
        if (messages.isEmpty()) return;
        StringBuilder batch = new StringBuilder();
        for (String message : messages) batch.append(userMessage(message)).append('\n');
        activity.append(batch.toString());
        activity.setCaretPosition(activity.getDocument().getLength());
        status.setText(userMessage(messages.get(messages.size() - 1)));
    }
    private void refresh() {
        final RemoteConnection remote;
        try { remote = connection(); } catch (Exception e) { failure(e); return; }
        busy(true); append("Java süreçleri keşfediliyor...");
        new SwingWorker<ProcessScanner.ScanResult, String>() {
            @Override protected ProcessScanner.ScanResult doInBackground() throws Exception {
                try { return remote == null ? new CollectionOrchestrator(config).list() : new RemoteCollectionOrchestrator(config).list(remote, this::publish); }
                finally { if (remote != null) remote.close(); }
            }
            @Override protected void process(List<String> messages) { appendMessages(messages); }
            @Override protected void done() {
                busy(false);
                try {
                    ProcessScanner.ScanResult scan = get(); rows.setRowCount(0);
                    for (JavaProcessInfo p : scan.processes) rows.addRow(new Object[]{p.pid, p.processName + " / " + p.applicationLabel(), p.mainClass, p.owner == null ? "Bilinmiyor: " + p.ownerError : p.owner, p.sessionId, p.startTime, p.javaVersion == null ? "Bilinmiyor" : p.javaVersion, p.collectorProcess ? "Collector — hariç" : "Dahil"});
                    append(scan.processes.size() + " süreç bulundu. Administrator: " + scan.elevated);
                    for (String error : scan.errors) append("Keşif notu: " + error);
                } catch (Exception e) { failure(e); }
            }
        }.execute();
    }
    private void startCollection() {
        final RemoteConnection remote;
        try { remote = connection(); } catch (Exception e) { failure(e); return; }
        final char[] password = ReportPassword.defaultPassword();
        busy(true); append("Tanılama raporu hazırlanıyor...");
        new SwingWorker<CollectionOrchestrator.Result, String>() {
            @Override protected CollectionOrchestrator.Result doInBackground() throws Exception {
                try { return remote == null ? new CollectionOrchestrator(config).collect(null, password, message -> publish(message)) : new RemoteCollectionOrchestrator(config).collect(null, password, remote, message -> publish(message)); }
                finally { if (remote != null) remote.close(); }
            }
            @Override protected void process(List<String> messages) { appendMessages(messages); }
            @Override protected void done() {
                busy(false);
                try {
                    CollectionOrchestrator.Result result = get();
                    append("Doğrulanmış ZIP: " + result.archive);
                    int dumps = result.manifest.jvms.stream().mapToInt(j -> j.dumpCount).sum();
                    JOptionPane.showMessageDialog(CollectorFrame.this, "Rapor: " + result.archive + "\nDurum: " + result.manifest.collectionStatus + "\nThread dump sayısı: " + dumps + "\nAyrıntılar ZIP içindeki Manifest.json dosyasında.", "Rapor hazır", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) { failure(e); }
                finally { Arrays.fill(password, '\0'); }
            }
        }.execute();
    }
    private void openOutput() {
        busy(true);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                Files.createDirectories(config.outputDirectory);
                if (!Desktop.isDesktopSupported()) throw new java.io.IOException("Çıktı klasörünü elle açın: " + config.outputDirectory);
                Desktop.getDesktop().open(config.outputDirectory.toFile()); return null;
            }
            @Override protected void done() { busy(false); try { get(); } catch (Exception e) { failure(e); } }
        }.execute();
    }
    private void failure(Exception e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        append("İşlem başarısız: " + cause.getMessage()); JOptionPane.showMessageDialog(this, userMessage(cause.getMessage()), "Hata", JOptionPane.ERROR_MESSAGE);
    }
}
