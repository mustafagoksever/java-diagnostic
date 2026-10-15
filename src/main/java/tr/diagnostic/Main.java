package tr.diagnostic;

import java.awt.GraphicsEnvironment;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        int code = run(args);
        if (code >= 0) System.exit(code);
    }
    static int run(String[] args) {
        char[] password = null;
        RemoteConnection connection = null;
        boolean guiRequested = args.length == 0 || args[0].startsWith("--") || "gui".equals(args[0]);
        try {
            Arguments options = Arguments.parse(args);
            if (options.help) { printUsage(); return 0; }
            ConfigurationFile configurationFile = options.config == null ? new ConfigurationFile() : new ConfigurationFile(options.config);
            CollectorConfiguration config = configurationFile.load();
            if (options.mode != null) config.mode = options.mode;
            if (options.host != null) config.remoteHost = options.host;
            if (options.username != null) config.remoteUsername = options.username;
            if (options.remoteJdk != null) config.remoteJdkHome = options.remoteJdk;
            if (options.command.equals("validate-config")) {
                if ("remote".equals(config.mode)) ConfigurationManager.validateRemoteSources(config);
                System.out.println("Konfigürasyon geçerli. Kaynakların varlığı toplama sırasında denetlenir.");
                System.out.println("Mod: " + config.mode + (config.mode.equals("remote") ? " | Sunucu bağlantısı config'ten okunur." : ""));
                if (config.mode.equals("remote")) System.out.println("Sunucudaki kurulum: " + config.installationPath + "\nUygulama (sunucuda çözülecek): " + config.applicationPath + "\nBu bilgisayardaki çıktı: " + config.outputDirectory);
                else System.out.println("Kurulum: " + config.installationDirectory + "\nUygulama: " + config.applicationDirectory + "\nÇıktı: " + config.outputDirectory);
                return 0;
            }
            if (options.command.equals("gui")) {
                if (GraphicsEnvironment.isHeadless()) throw new IllegalArgumentException("Swing için grafik oturumu gerekli; CLI kullanın.");
                ServerEndpointSettings endpointSettings=new ServerEndpointSettings();
                if(options.host==null && options.config==null && !configurationFile.exists())config.remoteHost=endpointSettings.hostOrDefault(config.remoteHost);
                SwingUtilities.invokeLater(() -> new CollectorFrame(config,options.admin,configurationFile).setVisible(true)); return -1;
            }
            if (options.command.equals("collect") && "remote".equals(config.mode)) ConfigurationManager.validateRemoteSources(config);
            CollectionOrchestrator orchestrator = new CollectionOrchestrator(config);
            if ("remote".equals(config.mode)) connection = readRemoteConnection(config, options.remotePasswordEnv);
            if (options.command.equals("list-java")) {
                ProcessScanner.ScanResult scan = connection == null ? orchestrator.list() : new RemoteCollectionOrchestrator(config).list(connection, System.err::println);
                System.out.println("Administrator: " + scan.elevated + " | Kullanıcı: " + scan.collectorIdentity);
                System.out.println("PID\tSÜREÇ / ANA SINIF\tSAHİP\tOTURUM\tBAŞLANGIÇ\tJAVA SÜRÜMÜ\tRAPOR HEDEFİ");
                for (JavaProcessInfo p : scan.processes) System.out.println(p.pid + "\t" + p.processName + " / " + p.applicationLabel() + "\t" + display(p.owner) + "\t" + p.sessionId + "\t" + display(p.startTime) + "\t" + display(p.javaVersion) + "\t" + (p.collectorProcess ? "Collector — hariç" : "Dahil"));
                for (String e : scan.errors) System.err.println("Keşif notu: " + e);
                return scan.windowsDiscoverySucceeded ? 0 : 3;
            }
            if (options.generatePassword) {
                password = ZipEncryptionService.generatePassword();
                System.err.println("Üretilen ZIP parolası (ayrı ve güvenli bir yere kaydedin): " + new String(password));
            } else if (options.passwordEnv != null) {
                String value = System.getenv(options.passwordEnv);
                if (value == null || value.isEmpty()) throw new IllegalArgumentException("Parola ortam değişkeni tanımlı veya dolu değil.");
                password = value.toCharArray();
            } else {
                password = ReportPassword.configured(config);
            }
            if (password == null || password.length < 12) throw new IllegalArgumentException("ZIP parolası en az 12 karakter olmalı.");
            CollectionOrchestrator.Result result = connection == null ? orchestrator.collect(options.all ? null : options.pids, password, System.out::println) : new RemoteCollectionOrchestrator(config).collect(options.all ? null : options.pids, password, connection, System.out::println);
            System.out.println("Doğrulanmış rapor: " + result.archive.toAbsolutePath());
            System.out.println("Toplama durumu: " + result.manifest.collectionStatus);
            for (DiagnosticReportGenerator.JvmReport jvm : result.manifest.jvms) System.out.println("PID " + jvm.process.pid + ": " + jvm.dumpCount + "/3 dump");
            return result.manifest.collectionStatus.equals("COMPLETE") && result.warnings.isEmpty() ? 0 : 3;
        } catch (IllegalArgumentException e) { showStartupError(guiRequested, "Kullanım hatası: " + e.getMessage()); return 2; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("Toplama kesildi; hedef JVM'ler çalışmaya devam eder."); return 1; }
        catch (Exception e) { showStartupError(guiRequested, "İşlem başarısız: " + e.getMessage()); return 1; }
        finally { if (password != null) Arrays.fill(password, '\0'); if (connection != null) connection.close(); }
    }
    static void showStartupError(boolean gui, String message) {
        System.err.println(message);
        if (gui && !GraphicsEnvironment.isHeadless()) {
            Runnable dialog = () -> JOptionPane.showMessageDialog(null,
                "Program başlatılamadı. Konfigürasyonu veya JAR paketini kontrol edin.\nAyrıntıları görmek için CMD üzerinden çalıştırabilirsiniz.",
                "Başlatma hatası", JOptionPane.ERROR_MESSAGE);
            try { if (SwingUtilities.isEventDispatchThread()) dialog.run(); else SwingUtilities.invokeAndWait(dialog); }
            catch (Exception ignored) { System.err.println("Başlatma hata penceresi gösterilemedi."); }
        }
    }
    private static RemoteConnection readRemoteConnection(CollectorConfiguration config, String variable) {
        java.io.Console console = System.console();
        if (config.remoteUsername == null || config.remoteUsername.trim().isEmpty()) {
            if (console == null) throw new IllegalArgumentException("Uzak CLI için --user DOMAIN\\USER belirtin veya Swing ekranından kullanıcı adı girin.");
            config.remoteUsername = console.readLine("Windows kullanıcı adı: ");
        }
        char[] secret;
        if (variable != null) {
            String value = System.getenv(variable);
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("Sunucu parolası ortam değişkeni boş veya tanımlı değil.");
            secret = value.toCharArray();
        } else {
            if (console == null) throw new IllegalArgumentException("Uzak mod için --remote-password-env NAME kullanın veya Swing arayüzünden parola girin.");
            secret = console.readPassword("%s sunucu parolası: ", config.remoteUsername);
        }
        try { return new RemoteConnection(config.remoteHost, config.remoteUsername, config.remoteJdkHome, secret); }
        finally { if (secret != null) Arrays.fill(secret, '\0'); }
    }
    private static String display(String value) { return value == null ? "bilinmiyor" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '); }
    private static void printUsage() {
        System.out.println("java -jar java-diagnostic-collector.jar [gui] [--admin] [--config config.yaml]\n"
            + "  Varsayılan: sade kullanıcı ekranı. Ayarlar menüsü: IP ve dizinler. --admin: günlükler ve süreç listesi.\n"
            + "  list-java [--config config.yaml]\n  validate-config [--config config.yaml]\n"
            + "  collect (--all | --pid 123[,456]) [--config config.yaml]\n"
            + "          [--generate-password | --password-env NAME]\n"
            + "Mod: varsayılan remote. --local veya --remote; --host IP --user DOMAIN\\USER\n"
            + "     --remote-jdk auto|C:/Java/jdk8 --remote-password-env NAME\n"
            + "Çıkış kodları: 0 başarılı, 1 işlem hatası, 2 kullanım hatası, 3 kısmi rapor/keşif.");
    }
    static final class Arguments {
        String command = "gui";
        Path config; // Explicit override takes precedence over config.yaml beside the JAR.
        boolean help, all, generatePassword, admin;
        String passwordEnv;
        String mode, host, username, remoteJdk, remotePasswordEnv;
        Set<Long> pids = new LinkedHashSet<>();
        static Arguments parse(String[] args) {
            Arguments o = new Arguments(); int i = 0;
            if (args.length > 0 && !args[0].startsWith("--")) o.command = args[i++];
            if (!Arrays.asList("gui", "collect", "list-java", "validate-config").contains(o.command)) throw new IllegalArgumentException("Bilinmeyen komut. --help kullanın.");
            while (i < args.length) {
                String flag = args[i++];
                switch (flag) {
                    case "--help": o.help = true; break;
                    case "--admin": o.admin = true; break;
                    case "--config": o.config = Paths.get(value(args, i++, flag)); break;
                    case "--all": o.all = true; break;
                    case "--pid":
                        for (String v : value(args, i++, flag).split(",", -1)) {
                            try { long pid = Long.parseLong(v); if (pid <= 0 || pid > 4294967295L) throw new NumberFormatException(); o.pids.add(pid); }
                            catch (NumberFormatException e) { throw new IllegalArgumentException("--pid pozitif bir Windows PID değeri olmalı."); }
                        } break;
                    case "--generate-password": o.generatePassword = true; break;
                    case "--password-env": o.passwordEnv = value(args, i++, flag); break;
                    case "--local": if (o.mode != null) throw new IllegalArgumentException("Yalnız bir mod seçin."); o.mode = "local"; break;
                    case "--remote": if (o.mode != null) throw new IllegalArgumentException("Yalnız bir mod seçin."); o.mode = "remote"; break;
                    case "--host": o.host = value(args, i++, flag); break;
                    case "--user": o.username = value(args, i++, flag); break;
                    case "--remote-jdk": o.remoteJdk = value(args, i++, flag); break;
                    case "--remote-password-env": o.remotePasswordEnv = value(args, i++, flag); break;
                    default: throw new IllegalArgumentException("Bilinmeyen seçenek: " + flag);
                }
            }
            if (o.help) return o;
            if(o.admin && !o.command.equals("gui"))throw new IllegalArgumentException("--admin yalnızca GUI ekranında kullanılabilir.");
            if ("local".equals(o.mode) && (o.host != null || o.username != null || o.remoteJdk != null || o.remotePasswordEnv != null)) throw new IllegalArgumentException("Sunucu seçenekleri --local ile kullanılamaz.");
            if (o.command.equals("collect")) {
                if (o.all == !o.pids.isEmpty()) throw new IllegalArgumentException("collect için yalnızca --all veya --pid belirtin.");
                if (o.generatePassword && o.passwordEnv != null) throw new IllegalArgumentException("Parola seçenekleri birlikte kullanılamaz.");
            } else if (o.all || !o.pids.isEmpty() || o.generatePassword || o.passwordEnv != null) throw new IllegalArgumentException("Toplama seçenekleri yalnızca collect komutunda kullanılabilir.");
            return o;
        }
        private static String value(String[] args, int index, String flag) {
            if (index >= args.length || args[index].startsWith("--") || args[index].isEmpty()) throw new IllegalArgumentException(flag + " bir değer gerektirir.");
            return args[index];
        }
    }
}
