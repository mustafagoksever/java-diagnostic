package tr.diagnostic;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** WMI/DCOM and SMB through built-in Windows PowerShell, without WinRM changes. */
public final class RemotePowerShellTransport implements RemoteTransport {
    static final List<String> HELPER_RESOURCES = Collections.unmodifiableList(Arrays.asList(
        "remote-worker.ps1", "remote-batch.ps1", "process-scan.ps1", "system-info.ps1", "resource-monitor.ps1",
        "remote-extras.ps1", "remote-ping.ps1", "remote-vxworks.ps1"));
    @Override public JsonObject execute(JsonObject settings, Path staging, RemoteConnection connection, Consumer<String> progress) throws IOException, InterruptedException {
        if (!PowerShell.isWindows()) throw new IOException("Uzak toplama Windows üzerinde çalışmalıdır.");
        String systemRoot = System.getenv("SystemRoot");
        Path exe = Paths.get(systemRoot == null ? "C:\\Windows" : systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Path scriptDirectory = Files.createTempDirectory("diagnostic-remote-client-");
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Process helper = null;
        char[] secret = connection.passwordCopy();
        try {
            FileSupport.restrictDirectory(scriptDirectory);
            Path scriptFile = scriptDirectory.resolve("remote-client.ps1");
            byte[] body = PowerShell.resource("remote-client.ps1").getBytes(StandardCharsets.UTF_8);
            byte[] bom = new byte[body.length + 3]; bom[0]=(byte)0xef; bom[1]=(byte)0xbb; bom[2]=(byte)0xbf;
            System.arraycopy(body, 0, bom, 3, body.length); Files.write(scriptFile, bom);
            // A larger embedded script can exceed Windows' command-line limit.
            // This temporary script contains no credentials; they still use stdin.
            helper = new ProcessBuilder(exe.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString()).start();
            final Process running = helper;
            Future<JsonObject> response = readers.submit(() -> {
                JsonObject last = null;
                try (BufferedReader input = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (line.length() > 16 * 1024 * 1024) throw new IOException("Uzak sonuç boyut sınırını aştı.");
                        if (line.trim().isEmpty()) continue;
                        JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                        if ("progress".equals(ResourceMonitor.text(event, "event"))) progress.accept(ResourceMonitor.text(event, "message"));
                        if ("result".equals(ResourceMonitor.text(event, "event"))) last = event;
                    }
                }
                return last;
            });
            Future<String> stderr = readers.submit(() -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = running.getErrorStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) if (bytes.size() < 8192) bytes.write(buffer, 0, Math.min(count, 8192 - bytes.size()));
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            });
            // Secrets only in stdin, never arguments, environment, resource files or manifests.
            try (Writer output = new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8)) {
                JsonWriter writer = new JsonWriter(output);
                writer.beginObject().name("host").value(connection.host).name("username").value(connection.username)
                    .name("password").value(new String(secret)).name("localDirectory").value(staging.toAbsolutePath().toString());
                writer.name("settings"); new Gson().toJson(settings, writer);
                writer.name("resources").beginObject();
                for (String resource : HELPER_RESOURCES) {
                    writer.name(resource).value(Base64.getEncoder().encodeToString(PowerShell.resource(resource).getBytes(StandardCharsets.UTF_8)));
                }
                writer.endObject().endObject(); writer.flush(); output.write('\n'); output.flush();
            }
            long timeout = (settings.get("timeoutSeconds").getAsLong() + 90) * 1000L;
            if (!helper.waitFor(timeout, TimeUnit.MILLISECONDS)) throw new IOException("Uzak istemci timeout; sunucu yardımcıları kendi timeout'larıyla kapanır. Geçici klasör temizliği başarısız olabilir.");
            JsonObject event = response.get(10, TimeUnit.SECONDS);
            String errorOutput = stderr.get(10, TimeUnit.SECONDS);
            if (event == null) throw new IOException("Uzak istemci sonuç döndürmedi: " + errorOutput);
            if (!event.get("success").getAsBoolean()) {
                StringBuilder warnings = new StringBuilder();
                for (JsonElement warning : ResourceMonitor.array(event, "warnings")) warnings.append(" | ").append(warning.getAsString());
                throw new IOException("Uzak bağlantı/toplama: " + ResourceMonitor.text(event, "error") + " | Geçici klasör: " + ResourceMonitor.text(event, "remoteDirectory") + warnings);
            }
            return event;
        } catch (ExecutionException | TimeoutException | RuntimeException e) { throw new IOException("Uzak istemci çıktısı okunamadı: " + e.getClass().getSimpleName(), e); }
        finally {
            Arrays.fill(secret, '\0');
            if (helper != null && helper.isAlive()) {
                helper.destroy();
                try { if (!helper.waitFor(1000, TimeUnit.MILLISECONDS)) helper.destroyForcibly(); }
                catch (InterruptedException e) { helper.destroyForcibly(); Thread.currentThread().interrupt(); }
            }
            readers.shutdownNow();
            try { FileSupport.deleteTree(scriptDirectory); }
            catch (IOException e) { progress.accept("Yerel yardımcı script klasörü temizlenemedi: " + scriptDirectory); }
        }
    }
}
