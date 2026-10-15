package tr.diagnostic;

import tr.diagnostic.DiagnosticReportGenerator.LogResult;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Read-only directory and individual-file snapshots; never executes collected scripts. */
public final class LogCollector {
    public List<LogResult> collect(CollectorConfiguration config, Path staging) throws IOException {
        List<LogResult> results = new ArrayList<>();
        Path reportDirectory = config.outputDirectory.toAbsolutePath().normalize();
        int index = 0;
        for (CollectorConfiguration.LogDirectory spec : config.logs) {
            Path root = spec.path.toAbsolutePath().normalize();
            // If reports are written ABOVE the application, excluding
            // the whole output tree would also exclude every application log.
            Path outputRoot = reportDirectory.startsWith(root) && !reportDirectory.equals(root)
                ? reportDirectory : staging.toAbsolutePath().normalize();
            Path realOutput = outputRoot.toRealPath();
            String destination = spec.destination == null ? String.format(Locale.ROOT, "%02d", ++index) : spec.destination;
            Path logsRoot = staging.resolve("logs");
            Path dest = logsRoot.resolve(destination).normalize();
            if (!dest.startsWith(logsRoot)) throw new IOException("Log hedefi staging dışında.");
            long cutoff = spec.modifiedWithinHours == 0 ? Long.MIN_VALUE : Instant.now().minusSeconds(spec.modifiedWithinHours * 3600L).toEpochMilli();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                results.add(result(root, "FAILED", "Kaynak yok, erişilemiyor veya sembolik bağlantı.")); continue;
            }
            try {
                if (ownedReport(root, reportDirectory)) continue;
                if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
                    copy(root, dest.resolve(root.getFileName()), spec, cutoff, outputRoot, realOutput, staging, results);
                } else Files.walkFileTree(root, Collections.emptySet(), spec.recursive ? Integer.MAX_VALUE : 1, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return excluded(dir, outputRoot, realOutput) || ownedReport(dir, reportDirectory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (attrs.isRegularFile() && !attrs.isSymbolicLink() && !ownedReport(file, reportDirectory)) copy(file, dest.resolve(root.relativize(file)), spec, cutoff, outputRoot, realOutput, staging, results);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException e) { results.add(result(file, "FAILED", e.toString())); return FileVisitResult.CONTINUE; }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) { if (e != null) results.add(result(dir, "FAILED", e.toString())); return FileVisitResult.CONTINUE; }
                });
            } catch (IOException e) { results.add(result(root, "FAILED", e.toString())); }
        }
        return results;
    }
    private static boolean ownedReport(Path file, Path reportDirectory) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!reportDirectory.equals(absolute.getParent())) return false;
        String name = absolute.getFileName().toString();
        return name.startsWith(".diagnostic-staging-") || name.matches("diagnostic-(?:remote-)?[0-9]{8}-[0-9]{6}-[0-9a-f]{8}\\.zip")
            || (name.startsWith(".diagnostic-") && name.endsWith(".partial"));
    }
    private static boolean excluded(Path file, Path output, Path realOutput) throws IOException {
        return file.toAbsolutePath().normalize().startsWith(output) || file.toRealPath().startsWith(realOutput);
    }
    private static void copy(Path source, Path target, CollectorConfiguration.LogDirectory spec, long cutoff, Path output, Path realOutput, Path staging, List<LogResult> results) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Log toplama iptal edildi.");
        if (excluded(source, output, realOutput)) return;
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) return;
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!spec.extensions.isEmpty() && spec.extensions.stream().noneMatch(name::endsWith)) return;
        if (before.lastModifiedTime().toMillis() < cutoff) return;
        if (before.size() > spec.maxFileSizeBytes) { results.add(result(source, "SKIPPED_SIZE", "Maksimum dosya boyutu aşıldı.")); return; }
        LogResult r = result(source, "COPIED", null); results.add(r);
        r.destination = DiagnosticReportGenerator.relative(staging, target);
        if (Files.exists(target)) { r.status = "FAILED"; r.error = "DESTINATION_COLLISION: önceki kopya korundu."; return; }
        try {
            Files.createDirectories(target.getParent()); r.bytesCopied = copySnapshot(source, target, before.size());
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            r.changedDuringCopy = before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()) || r.bytesCopied != before.size();
            if (r.changedDuringCopy) r.status = "COPIED_CHANGING_SOURCE";
        } catch (IOException e) { r.status = "FAILED"; r.error = e.toString(); Files.deleteIfExists(target); }
    }
    static long copySnapshot(Path source, Path destination, long limit) throws IOException {
        long copied = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[65536]; int n;
            while (copied < limit && (n = input.read(buffer, 0, (int) Math.min(buffer.length, limit - copied))) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Log kopyalama iptal edildi.");
                output.write(buffer, 0, n); copied += n;
            }
        }
        return copied;
    }
    private static LogResult result(Path source, String status, String error) {
        LogResult r = new LogResult(); r.source = source.toString(); r.status = status; r.error = error; return r;
    }
}
