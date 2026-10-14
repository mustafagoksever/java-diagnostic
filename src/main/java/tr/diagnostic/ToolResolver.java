package tr.diagnostic;

import java.nio.file.*;
import java.util.*;

/** Tool homes can be pinned by JVM family/version; no shell or command strings. */
public final class ToolResolver {
    private final CollectorConfiguration config;
    public ToolResolver(CollectorConfiguration config) { this.config = config; }
    public Optional<Path> find(String tool, JavaProcessInfo target) {
        if (!Arrays.asList("jcmd", "jstack", "jps", "jstat").contains(tool)) throw new IllegalArgumentException("Unsupported tool");
        LinkedHashSet<Path> homes = new LinkedHashSet<>(config.jdkHomes);
        if (target != null && target.executablePath != null) {
            Path bin = Paths.get(target.executablePath).getParent();
            if (bin != null && bin.getParent() != null) homes.add(bin.getParent());
        }
        homes.add(Paths.get(System.getProperty("java.home")));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) homes.add(Paths.get(javaHome));
        for (Path home : homes) {
            Optional<Path> p = executable(home.resolve("bin"), tool);
            if (p.isPresent()) return p;
            // Java 8 java.home commonly points to <jdk>/jre.
            if (home.getFileName() != null && "jre".equalsIgnoreCase(home.getFileName().toString()) && home.getParent() != null) {
                p = executable(home.getParent().resolve("bin"), tool);
                if (p.isPresent()) return p;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            try { Optional<Path> p = executable(Paths.get(dir.replace("\"", "")), tool); if (p.isPresent()) return p; }
            catch (InvalidPathException ignored) { /* malformed PATH segment */ }
        }
        return Optional.empty();
    }
    private Optional<Path> executable(Path bin, String tool) {
        Path p = bin.resolve(tool + (PowerShell.isWindows() ? ".exe" : "")).toAbsolutePath().normalize();
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }
}
