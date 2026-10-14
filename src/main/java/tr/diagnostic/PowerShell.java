package tr.diagnostic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class PowerShell {
    private final CommandExecutor executor;
    public PowerShell(CommandExecutor executor) { this.executor = executor; }
    public static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"); }
    public CommandResult run(String script, long timeoutMillis) throws IOException, InterruptedException {
        if (!isWindows()) throw new IOException("Bu işlem Windows gerektirir.");
        Path out = Files.createTempFile("diagnostic-ps-", ".out");
        Path err = Files.createTempFile("diagnostic-ps-", ".err");
        try {
            String prelude = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false); ";
            String encoded = Base64.getEncoder().encodeToString((prelude + script).getBytes(StandardCharsets.UTF_16LE));
            String root = System.getenv("SystemRoot");
            Path exe = Paths.get(root == null ? "C:\\Windows" : root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            return executor.run(Arrays.asList(exe.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded), timeoutMillis, out, err);
        } finally { Files.deleteIfExists(out); Files.deleteIfExists(err); }
    }
    public static String resource(String name) throws IOException {
        try (InputStream input = PowerShell.class.getResourceAsStream("/" + name)) {
            if (input == null) throw new IOException("Kaynak bulunamadı: " + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
