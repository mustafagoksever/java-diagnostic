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
    private final boolean admin;
    private final ServerEndpointSettings endpointSettings;
    private final FailureReportWriter failureReports;
    private final JButton collect = new JButton("Hata Raporu Oluştur");
    private final JButton open = new JButton("Çıktı Klasörünü Aç");
    private final JButton list = new JButton("Java Süreçlerini Listele");
    private final JButton clearLog = new JButton("Logları Temizle");
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Uzak Sunucu", "Yerel Bilgisayar"});
    private final JTextField username = new JTextField(20);
    private final JPasswordField serverPassword = new JPasswordField(16);
    private final JTextField serverHost = new JTextField(20);
    private final JButton saveHost = new JButton("Sunucu IP'sini Kaydet");
    private final JPanel remoteFields = new JPanel(new GridBagLayout());
    private final JTextArea activity = new JTextArea(9, 90);
    private final JLabel status = new JLabel("Hazır. Collector süreçleri listelenir; rapora hedef olarak alınmaz.");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultTableModel rows = new DefaultTableModel(new String[]{"PID", "Süreç / Uygulama", "Ana sınıf", "Sahip", "Oturum", "Başlangıç (UTC)", "Java sürümü", "Rapor hedefi"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    public CollectorFrame(CollectorConfiguration config) {
        this(config,false);
    }
    public CollectorFrame(CollectorConfiguration config, boolean admin) {
        this(config,admin,new ServerEndpointSettings());
    }
    CollectorFrame(CollectorConfiguration config, boolean admin, ServerEndpointSettings endpointSettings) {
        this(config, admin, endpointSettings, new FailureReportWriter());
    }
    CollectorFrame(CollectorConfiguration config, boolean admin, ServerEndpointSettings endpointSettings, FailureReportWriter failureReports) {
        super(admin?"Java 8 Windows Diagnostic Collector — Admin":"Hata Raporu"); this.config = config;
        this.admin=admin;this.endpointSettings=endpointSettings;
        this.failureReports=failureReports;
        status.setText(admin?"Hazır. Collector süreçleri listelenir; rapora hedef olarak alınmaz.":"Hazır.");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (collect.isEnabled()) { serverPassword.setText(""); dispose(); }
                else status.setText("İşlem sürüyor; tamamlandığında pencereyi kapatabilirsiniz.");
            }
        });
        JPanel buttons = new JPanel(admin?new FlowLayout(FlowLayout.LEFT):new GridLayout(1,2,8,0)); buttons.add(collect); buttons.add(open);
        if(admin){buttons.add(list);buttons.add(clearLog);}
        clearLog.setToolTipText("Ekrandaki işlem günlüğünü ve Java süreçleri listesini temizler.");
        clearLog.addActionListener(e -> { activity.setText(""); activity.setCaretPosition(0); rows.setRowCount(0); if (collect.isEnabled()) status.setText("İşlem günlüğü ve Java süreçleri listesi temizlendi."); });
        username.setText(config.remoteUsername);
        username.setName("username");serverPassword.setName("serverPassword");serverHost.setName("serverHost");
        serverHost.setText(config.remoteHost);
        mode.setSelectedIndex(admin && "local".equals(config.mode) ? 1 : 0);
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT)); modeRow.add(new JLabel("Çalışma modu:")); modeRow.add(mode);
        addRemoteField(0, "Windows kullanıcı adı:", username);
        addRemoteField(1, "Sunucu parolası:", serverPassword);
        if(admin){addRemoteField(2,"Sunucu IP/adresi:",serverHost);addRemoteField(3,"",saveHost);}
        saveHost.addActionListener(e -> saveServerHost());
        JPanel connection = new JPanel(new BorderLayout()); if(admin)connection.add(modeRow, BorderLayout.NORTH); connection.add(remoteFields, BorderLayout.CENTER);
        remoteFields.setVisible(mode.getSelectedIndex() == 0);
        mode.addActionListener(e -> { remoteFields.setVisible(mode.getSelectedIndex() == 0); rows.setRowCount(0); serverPassword.setText(""); append("Mod: " + mode.getSelectedItem() + ". Rapor çıktısı bu bilgisayara kaydedilir."); revalidate(); });
        JPanel top = new JPanel(new BorderLayout(8, 8)); top.add(connection, BorderLayout.NORTH); top.add(buttons, BorderLayout.CENTER); top.add(status, BorderLayout.SOUTH);
        activity.setEditable(false); activity.setLineWrap(true); activity.setWrapStyleWord(true);
        JPanel root = new JPanel(new BorderLayout(8, 8)); root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(top, BorderLayout.NORTH);
        if(admin){
            JTable table = new JTable(rows); table.setAutoCreateRowSorter(true);
            JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(activity)); center.setResizeWeight(0.65);
            root.add(center,BorderLayout.CENTER);
        }
        root.add(progress, BorderLayout.SOUTH); setContentPane(root);
        collect.addActionListener(e -> startCollection()); list.addActionListener(e -> refresh()); open.addActionListener(e -> openOutput());
        if(admin){setSize(1200,780);setMinimumSize(new Dimension(850,600));}
        else{pack();setSize(Math.max(560,getWidth()),Math.max(230,getHeight()));setMinimumSize(getSize());}
        setLocationRelativeTo(null);
    }
    private void addRemoteField(int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(2, 4, 2, 8); c.anchor = GridBagConstraints.WEST; c.gridy = row;
        c.gridx = 0; remoteFields.add(new JLabel(label), c); c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; remoteFields.add(field, c);
    }
    private RemoteConnection connection() {
        if (mode.getSelectedIndex() == 1) return null;
        char[] password = serverPassword.getPassword();
        try {
            RemoteConnection remote=new RemoteConnection(admin?serverHost.getText().trim():config.remoteHost,username.getText().trim(),config.remoteJdkHome,password);
            if(admin)config.remoteHost=remote.host;
            return remote;
        }
        finally { Arrays.fill(password, '\0'); }
    }
    private void saveServerHost() {
        final String host=serverHost.getText().trim();
        try{ServerEndpointSettings.validate(host);}catch(Exception e){failure(e,"Sunucu adresini kaydetme");return;}
        busy(true);append("Sunucu adresi kaydediliyor...");
        new SwingWorker<Void,Void>(){
            @Override protected Void doInBackground() throws Exception{endpointSettings.saveHost(host);return null;}
            @Override protected void done(){
                busy(false);
                try{get();config.remoteHost=host;rows.setRowCount(0);append("Sunucu adresi kaydedildi: "+host+". Sonraki normal açılışlarda bu adres kullanılacak.");}
                catch(Exception e){failure(e,"Sunucu adresini kaydetme");}
            }
        }.execute();
    }
    private void busy(boolean value) { collect.setEnabled(!value); list.setEnabled(!value); open.setEnabled(!value); mode.setEnabled(!value); username.setEnabled(!value); serverPassword.setEnabled(!value); serverHost.setEnabled(!value);saveHost.setEnabled(!value);progress.setIndeterminate(value); }
    private String userMessage(String value) { return value == null ? "Bilinmeyen hata" : value; }
    private void append(String value) { value = userMessage(value); if(admin){activity.append(value + "\n"); activity.setCaretPosition(activity.getDocument().getLength());}status.setText(value); }
    private void appendMessages(List<String> messages) {
        if (!admin || messages.isEmpty()) return;
        StringBuilder batch = new StringBuilder();
        for (String message : messages) batch.append(userMessage(message)).append('\n');
        activity.append(batch.toString());
        activity.setCaretPosition(activity.getDocument().getLength());
        status.setText(userMessage(messages.get(messages.size() - 1)));
    }
    private void refresh() {
        final RemoteConnection remote;
        try { remote = connection(); } catch (Exception e) { failure(e,"Java süreçlerini listeleme"); return; }
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
                } catch (Exception e) { failure(e,"Java süreçlerini listeleme"); }
            }
        }.execute();
    }
    private void startCollection() {
        final RemoteConnection remote;
        try { remote = connection(); } catch (Exception e) { failure(e,"Rapor oluşturma"); return; }
        final char[] password = ReportPassword.defaultPassword();
        busy(true); append("Tanılama raporu hazırlanıyor...");
        new SwingWorker<CollectionOrchestrator.Result, String>() {
            @Override protected CollectionOrchestrator.Result doInBackground() throws Exception {
                try { return remote == null ? new CollectionOrchestrator(config).collect(null, password, message -> {if(admin)publish(message);}) : new RemoteCollectionOrchestrator(config).collect(null, password, remote, message -> {if(admin)publish(message);}); }
                finally { if (remote != null) remote.close(); }
            }
            @Override protected void process(List<String> messages) { appendMessages(messages); }
            @Override protected void done() {
                busy(false);
                try {
                    CollectionOrchestrator.Result result = get();
                    if(admin){
                        append("Doğrulanmış ZIP: " + result.archive);
                        int dumps = result.manifest.jvms.stream().mapToInt(j -> j.dumpCount).sum();
                        JOptionPane.showMessageDialog(CollectorFrame.this, "Rapor: " + result.archive + "\nDurum: " + result.manifest.collectionStatus + "\nThread dump sayısı: " + dumps + "\nAyrıntılar ZIP içindeki Manifest.json dosyasında.", "Rapor hazır", JOptionPane.INFORMATION_MESSAGE);
                    }else{
                        boolean complete="COMPLETE".equals(result.manifest.collectionStatus)&&result.warnings.isEmpty();
                        append(complete?"Hata raporu hazır.":"Rapor hazır; bazı bilgiler alınamadı.");
                        JOptionPane.showMessageDialog(CollectorFrame.this,"Rapor: "+result.archive+(complete?"":"\nBazı bilgiler alınamadı; ayrıntılar raporun içindedir."),"Rapor hazır",JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) { failure(e,"Rapor oluşturma"); }
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
            @Override protected void done() { busy(false); try { get(); } catch (Exception e) { failure(e,"Çıktı klasörünü açma"); } }
        }.execute();
    }
    private void failure(Exception e, String operation) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        final char[] serverSecret = serverPassword.getPassword();
        final char[] archiveSecret = ReportPassword.defaultPassword();
        final String user = username.getText();
        busy(true); append("İşlem tamamlanamadı; hata kaydı hazırlanıyor...");
        new SwingWorker<java.nio.file.Path, Void>() {
            private String detail;
            @Override protected java.nio.file.Path doInBackground() throws Exception {
                try {
                    String[] secrets = {user, user.trim(), new String(serverSecret), new String(archiveSecret)};
                    detail = FailureReportWriter.redact(userMessage(cause.getMessage()), secrets);
                    return failureReports.write(config.outputDirectory, operation, cause, secrets);
                } finally { Arrays.fill(serverSecret, '\0'); Arrays.fill(archiveSecret, '\0'); }
            }
            @Override protected void done() {
                busy(false);
                java.nio.file.Path record = null;
                try { record = get(); } catch (Exception writeFailure) { /* Report logging failure without hiding the original error. */ }
                String message = admin ? "İşlem başarısız: " + detail : "İşlem tamamlanamadı.";
                if (record != null) message += "\nTeknik hata kaydı: " + record;
                else message += "\nHata kaydı da yazılamadı. Çıktı ve kullanıcı klasörlerinin yazma izinlerini kontrol edin.";
                append(admin ? message : "İşlem tamamlanamadı.");
                JOptionPane.showMessageDialog(CollectorFrame.this, message, "Hata", JOptionPane.ERROR_MESSAGE);
            }
        }.execute();
    }
}
