package tr.diagnostic;

import com.google.gson.*;
import tr.diagnostic.DiagnosticReportGenerator.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Runs the remote flow's extra collectors directly, without WMI/SMB or credentials. */
final class LocalDiagnosticsCollector {
    private static final List<String> RESOURCES = Arrays.asList("local-diagnostics.ps1", "remote-worker.ps1",
        "jdk-discovery.ps1", "remote-windows.ps1", "remote-extras.ps1", "remote-ping.ps1", "remote-vxworks.ps1", "remote-batch.ps1");
    private final CollectorConfiguration config;
    private final CommandExecutor executor;
    LocalDiagnosticsCollector(CollectorConfiguration config, CommandExecutor executor) { this.config=config; this.executor=executor; }

    JsonObject settings(Manifest manifest, String jobId) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("jobId", jobId); request.addProperty("action", "collect"); request.addProperty("mode", "local");
        request.addProperty("timeoutSeconds", config.remoteTimeoutSeconds); request.addProperty("scanTimeoutSeconds", config.scanTimeoutSeconds);
        request.addProperty("windowsDiagnostics", config.remoteWindowsDiagnostics);
        request.addProperty("beforeLogCopyBatch", config.remoteBeforeLogCopyBatch);
        request.addProperty("beforeLogCopyTimeoutSeconds", config.remoteBeforeLogCopyTimeoutSeconds);
        request.addProperty("beforeLogCopyDelaySeconds", config.remoteBeforeLogCopyDelaySeconds);
        request.addProperty("nonInteractiveTransport", false); request.addProperty("clientHeartbeatRequired", true);
        if (!config.remoteBeforeLogCopyBatch.isEmpty() && config.applicationDirectory == null) throw new IOException("Yerel log hazırlığı için applicationDirectory gerekli.");
        request.addProperty("applicationDirectory", config.applicationDirectory == null ? "" : config.applicationDirectory.toAbsolutePath().normalize().toString());
        Gson gson = new Gson(); request.add("ping", gson.toJsonTree(config.ping)); request.add("vxworks", gson.toJsonTree(config.vxworks));
        request.add("discovery", gson.toJsonTree(manifest.discovery));
        return request;
    }

    boolean collect(Manifest manifest, Path staging, Consumer<String> progress) throws InterruptedException {
        boolean logsReady = config.remoteBeforeLogCopyBatch.isEmpty();
        if (!config.remoteWindowsDiagnostics && !config.ping.enabled && !config.vxworks.enabled && logsReady) return true;
        Path job = null; ExecutorService runner = Executors.newSingleThreadExecutor(); Future<CommandResult> running = null;
        boolean safeToDelete = true, interruptedDuringStop = false;
        Map<Path, Long> offsets = new HashMap<>();
        try {
            String jobId = UUID.randomUUID().toString();
            JsonObject request = settings(manifest, jobId);
            job = createJob(jobId);
            for (String resource : RESOURCES) Files.write(job.resolve(resource), ("\uFEFF" + PowerShell.resource(resource)).getBytes(StandardCharsets.UTF_8));
            DiagnosticReportGenerator.writeJson(job.resolve("request.json"), request);
            heartbeat(job);
            final String script = "& '" + quote(job.resolve("local-diagnostics.ps1")) + "' -RequestFile '" + quote(job.resolve("request.json")) + "'";
            running = runner.submit(() -> new PowerShell(executor).run(script, (config.remoteTimeoutSeconds + 15L) * 1000));
            CommandResult command;
            while (true) {
                heartbeat(job); forwardActivity(job, offsets, progress);
                try { command = running.get(500, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException waiting) { /* keep logs and disconnect protection alive */ }
            }
            while (forwardActivity(job, offsets, progress)) { /* drain the final batches after the helper exits */ }
            if (!command.success()) throw new IOException("Yerel tanılama yardımcısı: " + command.error());
            Path completed = job.resolve("complete.json");
            if (!Files.isRegularFile(completed)) throw new IOException("Yerel tanılama sonucu alınamadı.");
            JsonObject result;
            try (Reader reader = Files.newBufferedReader(completed, StandardCharsets.UTF_8)) { result=JsonParser.parseReader(reader).getAsJsonObject(); }
            if (!jobId.equals(ResourceMonitor.text(result, "jobId"))) throw new IOException("Yerel tanılama iş kimliği uyuşmuyor.");
            Gson gson = new Gson();
            for (JsonElement operation : ResourceMonitor.array(result, "operations")) manifest.operations.add(gson.fromJson(operation, Operation.class));
            for (JsonElement error : ResourceMonitor.array(result, "errors")) manifest.errors.add("LocalDiagnostics: " + error.getAsString());
            if (result.has("ping") && result.get("ping").isJsonObject()) manifest.ping=result.getAsJsonObject("ping");
            if (result.has("vxworks") && result.get("vxworks").isJsonObject()) manifest.vxworks=result.getAsJsonObject("vxworks");
            if (config.ping.enabled && manifest.ping == null) manifest.errors.add("Yerel ping toplama sonucu alınamadı.");
            if (config.vxworks.enabled && manifest.vxworks == null) manifest.errors.add("Yerel VxWorks toplama sonucu alınamadı.");
            logsReady = result.has("logsReady") && result.get("logsReady").getAsBoolean();
        } catch (IOException | ExecutionException | RuntimeException e) {
            manifest.errors.add("LocalDiagnostics: " + e.getMessage()); progress.accept("Yerel ek tanılamanın bazı adımları tamamlanamadı; mevcut veriler rapora eklenecek.");
        } finally {
            if (running != null && !running.isDone()) {
                try {
                    Files.write(job.resolve("cancel.txt"), new byte[]{1});
                    running.get(10, TimeUnit.SECONDS);
                } catch (IOException | ExecutionException | TimeoutException e) { safeToDelete=false; running.cancel(true); }
                catch (InterruptedException e) { safeToDelete=false; interruptedDuringStop=true; running.cancel(true); Thread.currentThread().interrupt(); }
            }
            runner.shutdownNow();
            if (job != null) {
                try { copyArtifacts(job, staging); } catch (IOException e) { manifest.errors.add("Yerel ek tanılama dosyaları: " + e.getMessage()); }
                if (safeToDelete) {
                    try { FileSupport.deleteTree(job); } catch (IOException e) { manifest.errors.add("Yerel ek tanılama klasörü temizlenemedi: " + job); }
                } else manifest.errors.add("Yerel tanılama yardımcısının kapanışı doğrulanamadı; geçici klasör korundu: " + job);
            }
        }
        if (interruptedDuringStop) throw new InterruptedException("Yerel tanılama yardımcısı durdurulurken işlem kesildi.");
        return logsReady;
    }

    private static Path createJob(String jobId) throws IOException {
        IOException failure = null;
        for (String base : Arrays.asList("C:/Temp", "D:/Temp")) {
            Path root=Paths.get(base).toAbsolutePath().normalize(); Path job=root.resolve("diagnostic-remote-" + jobId);
            try {
                Files.createDirectories(root); Files.createDirectory(job);
                try {
                    FileSupport.restrictDirectory(job);
                    if (Files.getFileStore(job).getUsableSpace() < 320L*1024*1024) throw new IOException("En az 320 MiB boş alan gerekli.");
                    return job;
                } catch (IOException e) { FileSupport.deleteTree(job); throw e; }
            } catch (IOException e) { failure=e; }
        }
        throw new IOException("Yerel tanılama için C:/Temp veya D:/Temp altında çalışma klasörü oluşturulamadı.", failure);
    }
    private static void heartbeat(Path job) throws IOException, InterruptedException {
        // Windows ReadAllText briefly denies writers. Retry without discarding the worker's results;
        // permanent failures remain bounded and interruption still cancels collection promptly.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        byte[] signal=UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        while(true) {
            try { Files.write(job.resolve("client-heartbeat.txt"),signal); return; }
            catch(FileSystemException temporarilyUnavailable) {
                if(System.nanoTime()>=deadline)throw temporarilyUnavailable;
                Thread.sleep(100);
            }
        }
    }
    private static String quote(Path path) { return path.toAbsolutePath().toString().replace("'", "''"); }
    static boolean forwardActivity(Path job, Map<Path,Long> offsets, Consumer<String> progress) throws IOException {
        boolean advanced=false;
        try (DirectoryStream<Path> files=Files.newDirectoryStream(job,"activity-*.jsonl")) {
            for (Path file : files) {
                long position=offsets.containsKey(file)?offsets.get(file):0L;
                try (RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")) {
                    input.seek(position); long start=position;
                    byte[] bytes=new byte[(int)Math.min(1024*1024,Math.max(0,input.length()-position))]; input.readFully(bytes);
                    ByteArrayOutputStream line=new ByteArrayOutputStream();
                    // Only advance past complete UTF-8 lines; an active writer may hold the last line.
                    for (int i=0;i<bytes.length;i++) {
                        int value=bytes[i]&255;
                        if (value == '\n') {
                            position=start+i+1;
                            try {
                                JsonObject event=JsonParser.parseString(new String(line.toByteArray(),StandardCharsets.UTF_8)).getAsJsonObject();
                                progress.accept(ResourceMonitor.text(event,"message"));
                                if(event.has("percent")) CollectionProgress.report(progress,Math.max(45,Math.min(65,event.get("percent").getAsInt())),ResourceMonitor.text(event,"stage"));
                            } catch (JsonParseException | IllegalStateException | NumberFormatException ignored) { /* keep subsequent records */ }
                            line.reset();
                        } else if (line.size() < 1024*1024) line.write(value);
                    }
                    offsets.put(file,position);
                    if(position>start)advanced=true;
                } catch (FileNotFoundException temporarilyLocked) { /* retry on next poll */ }
            }
        }
        return advanced;
    }
    private static void copyArtifacts(Path job, Path staging) throws IOException {
        Path data=job.resolve("data");
        if (Files.isDirectory(data)) Files.walkFileTree(data,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Yerel tanılama dosyası normal dosya değil: " + file);
                Path destination=staging.resolve(data.relativize(file)); Files.createDirectories(destination.getParent());
                Files.copy(file,destination); return FileVisitResult.CONTINUE;
            }
        });
        Path debug=Files.createDirectories(staging.resolve("debug/local-diagnostics"));
        try (DirectoryStream<Path> files=Files.newDirectoryStream(job,"activity-*.jsonl")) {
            for(Path file:files) Files.copy(file,debug.resolve(file.getFileName()));
        }
    }
}
