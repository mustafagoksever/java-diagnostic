package tr.diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CollectorConfiguration {
    public String mode = "remote";
    public String remoteHost = "15.30.9.176";
    public String remoteUsername = "";
    public String remoteJdkHome = "auto";
    public int remoteTimeoutSeconds = 1800;
    public String remoteWorkShare = "auto";
    public boolean remoteWindowsDiagnostics = true;
    public String remoteBeforeLogCopyBatch = "";
    public int remoteBeforeLogCopyTimeoutSeconds = 120;
    public int remoteBeforeLogCopyDelaySeconds = 30;
    public PingSettings ping = new PingSettings();
    public VxWorksSettings vxworks = new VxWorksSettings();
    public String installationPath = "C:/aselsan/ares2n";
    public String applicationPath = "auto";
    public Path outputDirectory;
    public Path installationDirectory;
    public Path applicationDirectory;
    public int collectionDelaySeconds = 0;
    public int resourceMonitoringSeconds = 60;
    public int resourceSampleIntervalSeconds = 1;
    public int commandTimeoutSeconds = 20;
    public int scanTimeoutSeconds = 60;
    public List<Path> jdkHomes = new ArrayList<>();
    public List<String> additionalProcessNames = new ArrayList<>();
    public boolean collectGcStats = true;
    public List<LogDirectory> logs = new ArrayList<>();

    public static final class PingSettings {
        public boolean enabled;
        public String subnet = "";
        public List<String> addresses = new ArrayList<>();
        public int parallelism = 16;
        public int attempts = 4;
        public int timeoutMillis = 1000;
        public int intervalMillis = 250;
    }
    public static final class VxWorksSettings {
        public boolean enabled;
        public boolean autoDiscoverPorts;
        public List<String> ports = new ArrayList<>();
        public int baudRate = 115200;
        public int dataBits = 8;
        public int stopBits = 1;
        public String parity = "None";
        public String flowControl = "None";
        public int portTimeoutSeconds = 180;
        public int commandTimeoutSeconds = 10;
        public int maxOutputMb = 8;
    }

    public static final class LogDirectory {
        public String sourcePath;
        public String base = "config";
        public Path path;
        public String destination;
        public boolean recursive = true;
        public List<String> extensions = new ArrayList<>();
        public int modifiedWithinHours = 48;
        public long maxFileSizeBytes = 100L * 1024 * 1024;
    }
}
