package tr.diagnostic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Redirect to disk to avoid pipe deadlocks and unbounded heap usage. */
public final class ProcessCommandExecutor implements CommandExecutor {
    private static final int MAX_CAPTURE = 8 * 1024 * 1024;
    @Override public CommandResult run(List<String> command, long timeoutMillis, Path stdout, Path stderr)
            throws IOException, InterruptedException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis");
        CommandResult r = new CommandResult();
        r.command = new ArrayList<>(command);
        long start = System.nanoTime();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            process.getOutputStream().close(); // non-interactive helpers must never wait for input
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                r.timedOut = true;
                stopHelper(process);
            } else r.exitCode = process.exitValue();
        } catch (IOException e) {
            r.launchError = e.toString();
        } catch (InterruptedException e) {
            if (process != null) stopHelper(process);
            throw e;
        } finally {
            r.durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        r.stdout = readBounded(stdout, MAX_CAPTURE);
        r.stderr = readBounded(stderr, 64 * 1024);
        r.outputTruncated = Files.exists(stdout) && Files.size(stdout) > MAX_CAPTURE;
        return r;
    }

    private static void stopHelper(Process process) throws InterruptedException {
        // This is ONLY the ProcessBuilder child, never the diagnostic target PID.
        process.destroy();
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(2000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Tanılama yardımcı süreci kapatılamadı.");
        }
    }
    static String readBounded(Path file, int max) throws IOException {
        if (!Files.exists(file)) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while (bytes.size() < max && (count = input.read(buffer, 0, Math.min(buffer.length, max - bytes.size()))) != -1) bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\uFEFF", "");
    }
}
