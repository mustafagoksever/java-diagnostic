package tr.diagnostic;

/** Discovery metadata; missing fields are explicit rather than guessed. */
public final class JavaProcessInfo {
    public long pid;
    public String processName;
    public String mainClass;
    public String applicationName;
    public String owner;
    public String ownerError;
    public Integer sessionId;
    public String startTime;
    public String startTicks;
    public String executablePath;
    public String javaVersion;
    public String versionSource;
    public String discoverySource;
    public boolean collectorProcess;
    public String collectionExclusionReason;

    public String label() {
        return mainClass == null || mainClass.isEmpty() ? processName : mainClass;
    }
    public String applicationLabel() {
        if (applicationName != null && !applicationName.trim().isEmpty()) return applicationName;
        if (mainClass != null && !mainClass.trim().isEmpty()) {
            String normalized = mainClass.replace('\\', '/');
            return normalized.substring(normalized.lastIndexOf('/') + 1);
        }
        return "Uygulama adı alınamadı";
    }
}
