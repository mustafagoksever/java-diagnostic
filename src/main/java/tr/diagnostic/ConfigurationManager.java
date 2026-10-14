package tr.diagnostic;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/** Strict, safe YAML schema. Unknown keys (including passwords) are rejected. */
public final class ConfigurationManager {
    public CollectorConfiguration load(Path file) throws IOException {
        Path base = file.toAbsolutePath().normalize().getParent();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return load(reader, base); }
    }
    public CollectorConfiguration loadEmbedded() throws IOException {
        InputStream resource = ConfigurationManager.class.getResourceAsStream("/internal/config.yaml");
        if (resource == null) throw new IOException("Gömülü konfigürasyon bulunamadı; JAR paketini yeniden derleyin.");
        try (Reader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return load(reader, Paths.get(".").toAbsolutePath().normalize());
        }
    }
    private CollectorConfiguration load(Reader reader, Path base) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(1024 * 1024);
        options.setNestingDepthLimit(20);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException e) {
            // Do not echo YAML fragments: they may contain a mistakenly supplied secret.
            throw new IOException("YAML okunamadı; biçimi, tekrar eden alanları ve güvenli veri tiplerini kontrol edin.");
        }
        Map<String, Object> root = map(loaded, "config");
        keys(root, "mode", "remote", "outputDirectory", "commandTimeoutSeconds", "scanTimeoutSeconds", "jdkHomes", "additionalProcessNames", "collectGcStats", "logs", "installationDirectory", "applicationDirectory", "collectionDelaySeconds", "resourceMonitoringSeconds", "resourceSampleIntervalSeconds");
        CollectorConfiguration c = new CollectorConfiguration();
        c.mode = string(root, "mode", "remote");
        if (!Arrays.asList("remote", "local").contains(c.mode)) throw new IOException("mode remote veya local olmalı.");
        if (root.containsKey("remote")) {
            Map<String, Object> remote = map(root.get("remote"), "remote");
            keys(remote, "host", "jdkHome", "timeoutSeconds", "workShare", "beforeLogCopyBatch", "beforeLogCopyTimeoutSeconds", "beforeLogCopyDelaySeconds");
            c.remoteHost = string(remote, "host", c.remoteHost);
            c.remoteJdkHome = string(remote, "jdkHome", c.remoteJdkHome);
            c.remoteTimeoutSeconds = number(remote, "timeoutSeconds", 1800, 60, 7200);
            c.remoteWorkShare = string(remote, "workShare", "auto");
            if (!c.remoteWorkShare.matches("[A-Za-z0-9_. -]+\\$?") || c.remoteWorkShare.equals(".") || c.remoteWorkShare.equals("..")) throw new IOException("remote.workShare paylaşım adı veya auto olmalı; UNC yol olmamalı.");
            if (remote.containsKey("beforeLogCopyBatch")) {
                Object value = remote.get("beforeLogCopyBatch");
                if (!(value instanceof String)) throw new IOException("remote.beforeLogCopyBatch metin olmalı; kapatmak için boş metin kullanın.");
                c.remoteBeforeLogCopyBatch = ((String) value).trim().replace('\\', '/');
            }
            String batch = c.remoteBeforeLogCopyBatch;
            if (!batch.isEmpty() && (!batch.matches("[A-Za-z0-9_./ -]+") || batch.startsWith("/") || Arrays.asList(batch.split("/")).contains("..") || !batch.toLowerCase(Locale.ROOT).endsWith(".bat")))
                throw new IOException("remote.beforeLogCopyBatch uygulama köküne göre güvenli bir .bat yolu olmalı.");
            c.remoteBeforeLogCopyTimeoutSeconds = number(remote, "beforeLogCopyTimeoutSeconds", 120, 1, 600);
            c.remoteBeforeLogCopyDelaySeconds = number(remote, "beforeLogCopyDelaySeconds", 30, 0, 300);
        }
        try { RemoteConnection.validate(c.remoteHost, "configuration-endpoint", c.remoteJdkHome); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
        c.installationPath = string(root, "installationDirectory", "C:/aselsan/ares2n");
        c.applicationPath = string(root, "applicationDirectory", "auto");
        c.installationDirectory = root.containsKey("installationDirectory") ? resolve(base, string(root, "installationDirectory", null)) : base;
        String app = string(root, "applicationDirectory", root.containsKey("installationDirectory") ? "auto" : ".");
        if (app.equals("auto")) {
            Path sub = c.installationDirectory.resolve("uygulama");
            c.applicationDirectory = Files.isDirectory(sub) ? sub :
                Files.isDirectory(c.installationDirectory.resolve("plugins")) || Files.isDirectory(c.installationDirectory.resolve("Log")) ? c.installationDirectory : sub;
        } else c.applicationDirectory = resolve(c.installationDirectory, app);
        c.outputDirectory = resolve(base, string(root, "outputDirectory", "reports"));
        c.collectionDelaySeconds = number(root, "collectionDelaySeconds", 0, 0, 300);
        c.resourceMonitoringSeconds = number(root, "resourceMonitoringSeconds", 60, 0, 300);
        c.resourceSampleIntervalSeconds = number(root, "resourceSampleIntervalSeconds", 1, 1, 10);
        c.commandTimeoutSeconds = number(root, "commandTimeoutSeconds", 20, 1, 300);
        c.scanTimeoutSeconds = number(root, "scanTimeoutSeconds", 60, 1, 600);
        c.collectGcStats = bool(root, "collectGcStats", true);
        for (Object v : list(root, "jdkHomes")) c.jdkHomes.add(resolve(base, text(v, "jdkHomes")));
        for (Object v : list(root, "additionalProcessNames")) {
            String name = text(v, "additionalProcessNames");
            if (!name.matches("[A-Za-z0-9_. -]+\\.exe")) throw new IOException("additionalProcessNames geçerli bir exe adı içermeli.");
            c.additionalProcessNames.add(name);
        }
        for (Object v : list(root, "logs")) {
            Map<String, Object> m = map(v, "logs");
            keys(m, "path", "recursive", "extensions", "modifiedWithinHours", "maxFileSizeMb", "base", "destination");
            CollectorConfiguration.LogDirectory log = new CollectorConfiguration.LogDirectory();
            String logBase = string(m, "base", "config");
            log.base = logBase;
            Path sourceBase;
            switch (logBase) {
                case "config": sourceBase = base; break;
                case "installation": sourceBase = c.installationDirectory; break;
                case "application": sourceBase = c.applicationDirectory; break;
                default: throw new IOException("Log base: config, installation veya application olmalı.");
            }
            log.sourcePath = string(m, "path", null);
            log.path = resolve(sourceBase, log.sourcePath);
            if (m.containsKey("destination")) {
                String destination = string(m, "destination", null).replace('\\', '/');
                if (!destination.matches("[A-Za-z0-9_./-]+") || destination.startsWith("/") || Arrays.asList(destination.split("/")).contains("..") || destination.equals(".")) throw new IOException("destination güvenli göreli bir klasör olmalı.");
                log.destination = destination;
            }
            log.recursive = bool(m, "recursive", true);
            log.modifiedWithinHours = number(m, "modifiedWithinHours", 48, 0, 87600);
            log.maxFileSizeBytes = number(m, "maxFileSizeMb", 100, 1, 10240) * 1024L * 1024;
            for (Object ext : list(m, "extensions")) {
                String s = text(ext, "extensions").toLowerCase(Locale.ROOT);
                if (!s.matches("\\.[a-z0-9]+")) throw new IOException("extensions .log gibi uzantılar içermeli.");
                log.extensions.add(s);
            }
            // Output inside a log tree is supported; LogCollector excludes it explicitly.
            c.logs.add(log);
        }
        return c;
    }

    private static Path resolve(Path base, String s) { return base.resolve(Paths.get(s)).normalize().toAbsolutePath(); }
    private static String text(Object v, String key) throws IOException {
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) throw new IOException(key + " boş olmayan metin olmalı.");
        return (String) v;
    }
    private static String string(Map<String, Object> m, String k, String def) throws IOException {
        return m.containsKey(k) ? text(m.get(k), k) : def == null ? text(null, k) : def;
    }
    private static int number(Map<String, Object> m, String k, int def, int min, int max) throws IOException {
        if (!m.containsKey(k)) return def;
        Object v = m.get(k);
        if (!(v instanceof Number) || v instanceof Float || v instanceof Double) throw new IOException(k + " tam sayı olmalı.");
        long n = ((Number) v).longValue();
        if (n < min || n > max) throw new IOException(k + " izin verilen aralıkta değil.");
        return (int) n;
    }
    private static boolean bool(Map<String, Object> m, String k, boolean def) throws IOException {
        if (!m.containsKey(k)) return def;
        if (!(m.get(k) instanceof Boolean)) throw new IOException(k + " true/false olmalı.");
        return (Boolean) m.get(k);
    }
    private static List<?> list(Map<String, Object> m, String k) throws IOException {
        if (!m.containsKey(k)) return Collections.emptyList();
        if (!(m.get(k) instanceof List)) throw new IOException(k + " liste olmalı.");
        return (List<?>) m.get(k);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v, String key) throws IOException {
        if (!(v instanceof Map)) throw new IOException(key + " alanı bir YAML mapping olmalı.");
        for (Object k : ((Map<?, ?>) v).keySet()) if (!(k instanceof String)) throw new IOException("Alan adları metin olmalı.");
        return (Map<String, Object>) v;
    }
    private static void keys(Map<String, Object> m, String... allowed) throws IOException {
        Set<String> known = new HashSet<>(Arrays.asList(allowed));
        for (String key : m.keySet()) if (!known.contains(key)) throw new IOException("Bilinmeyen konfigürasyon alanı. Parolaları YAML'a yazmayın.");
    }
}
