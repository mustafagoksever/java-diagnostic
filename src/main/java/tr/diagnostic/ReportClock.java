package tr.diagnostic;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;

/** Wall clocks are labeled by host; durations use a monotonic clock. */
public final class ReportClock {
    public String clientComputer = System.getenv("COMPUTERNAME");
    public String clientTimeZoneId = ZoneId.systemDefault().getId();
    public String collectorJvmStartedAtUtc = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString();
    public String collectorJvmStartedAtLocal = local(collectorJvmStartedAtUtc, clientTimeZoneId);
    public String clientStartedAtUtc, clientStartedAtLocal;
    public String clientReceivedAtUtc, clientReceivedAtLocal;
    public String clientPreparedAtUtc, clientPreparedAtLocal;
    public long clientElapsedMillisBeforeZip;
    public String serverStartedAtUtc, serverStartedAtLocal, serverCompletedAtUtc, serverCompletedAtLocal, serverTimeZoneId;
    public Long serverElapsedMillis;
    public Long serverClockAheadMinSeconds, serverClockAheadMaxSeconds;
    public String scope = "Client preparation ends before manifest hashing, ZIP creation/validation and cleanup. Server collection ends before result inventory/hashing and download. Clock offset bounds are estimates, assuming no clock adjustments during collection.";
    private transient long startedNanos = System.nanoTime();

    public ReportClock(String startedAt) {
        clientStartedAtUtc = startedAt;
        clientStartedAtLocal = local(startedAt, clientTimeZoneId);
    }

    void remote(JsonObject remote, Instant receivedAt) {
        clientReceivedAtUtc = receivedAt.toString();
        clientReceivedAtLocal = local(clientReceivedAtUtc, clientTimeZoneId);
        serverStartedAtUtc = text(remote, "serverStartedAtUtc");
        serverStartedAtLocal = text(remote, "serverStartedAtLocal");
        serverCompletedAtUtc = text(remote, "serverCompletedAtUtc");
        serverCompletedAtLocal = text(remote, "serverCompletedAtLocal");
        serverTimeZoneId = text(remote, "serverTimeZoneId");
        try { if (remote.has("serverElapsedMillis") && !remote.get("serverElapsedMillis").isJsonNull()) serverElapsedMillis = remote.get("serverElapsedMillis").getAsLong(); }
        catch (RuntimeException ignored) { /* Missing/invalid server metadata stays unknown. */ }
        // The server start happened between client start and receipt. Comparing
        // the two starts alone would mistake connection setup time for skew.
        try {
            Instant client = Instant.parse(clientStartedAtUtc), server = Instant.parse(serverStartedAtUtc);
            if (!receivedAt.isBefore(client)) {
                serverClockAheadMinSeconds = Duration.between(receivedAt, server).getSeconds();
                serverClockAheadMaxSeconds = Duration.between(client, server).getSeconds();
            }
        } catch (RuntimeException ignored) { /* Do not invent a remote clock. */ }
    }

    boolean clockMismatch() {
        return serverClockAheadMinSeconds != null && serverClockAheadMaxSeconds != null
            && (serverClockAheadMinSeconds > 300 || serverClockAheadMaxSeconds < -300);
    }

    void write(DiagnosticReportGenerator.Manifest m, Path staging) throws IOException {
        clientPreparedAtUtc = m.completedAt == null ? Instant.now().toString() : m.completedAt;
        clientPreparedAtLocal = local(clientPreparedAtUtc, clientTimeZoneId);
        clientElapsedMillisBeforeZip = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        StringBuilder out = new StringBuilder("RAPOR ZAMANLARI\n");
        row(out, "Rapor kimliği", m.reportId); row(out, "Mod", m.mode);
        out.append("\nCOLLECTOR'IN ÇALIŞTIĞI BİLGİSAYAR\n");
        row(out, "Bilgisayar", clientComputer); row(out, "Saat dilimi", clientTimeZoneId);
        row(out, "Collector JVM açılışı (yerel)", collectorJvmStartedAtLocal); row(out, "Collector JVM açılışı (UTC)", collectorJvmStartedAtUtc);
        row(out, "Toplama başlangıcı (yerel)", clientStartedAtLocal); row(out, "Toplama başlangıcı (UTC)", clientStartedAtUtc);
        if ("remote".equals(m.mode)) { row(out, "Sunucu verilerinin alınması (yerel)", clientReceivedAtLocal); row(out, "Sunucu verilerinin alınması (UTC)", clientReceivedAtUtc); }
        row(out, "Rapor hazırlığının bitişi, ZIP öncesi (yerel)", clientPreparedAtLocal);
        row(out, "Rapor hazırlığının bitişi, ZIP öncesi (UTC)", clientPreparedAtUtc);
        row(out, "ZIP öncesi geçen süre (ms, monotonik)", clientElapsedMillisBeforeZip);
        if ("remote".equals(m.mode)) {
            out.append("\nSUNUCU\n"); row(out, "Hedef", m.targetHost); row(out, "Saat dilimi", serverTimeZoneId);
            row(out, "Toplama başlangıcı (sunucu yerel)", serverStartedAtLocal); row(out, "Toplama başlangıcı (sunucu UTC)", serverStartedAtUtc);
            row(out, "Toplama bitişi (sunucu yerel)", serverCompletedAtLocal); row(out, "Toplama bitişi (sunucu UTC)", serverCompletedAtUtc);
            row(out, "Sunucuda geçen süre (ms, monotonik)", serverElapsedMillis);
            row(out, "Sunucu saati - istemci saati, tahmini alt sınır (sn)", serverClockAheadMinSeconds);
            row(out, "Sunucu saati - istemci saati, tahmini üst sınır (sn)", serverClockAheadMaxSeconds);
            out.append("Saat farkı eşzamanlı ölçülmedi; aralık bağlantı, toplama ve indirme süresini kapsar. Saatlerin çalışma sırasında değiştirilmediği varsayılır.\n");
            if (clockMismatch()) out.append("UYARI: Tahmini aralık 5 dakikadan fazla saat farkı gösteriyor. Log tarih filtresi ve dump zamanları sunucu saatine bağlıdır.\n");
        }
        out.append("\nBu kayıt ZIP öncesinde hazırlanır. ZIP oluşturma/doğrulama ve son temizlik süreleri dahil değildir.\n")
            .append("Sunucu bitişi, sonuç dosyalarının envanter/hash işlemleri ve indirmeden öncedir.\n")
            .append("UTC kayıtlarında Z, yerel kayıtlarda saat dilimi/ofset bulunur. Bilinmeyen sunucu bilgisi istemci saatiyle doldurulmaz.\n");
        Files.write(staging.resolve("rapor-zamanlari.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonObject source, String key) {
        return source.has(key) && source.get(key).isJsonPrimitive() && source.getAsJsonPrimitive(key).isString() ? source.get(key).getAsString() : null;
    }
    private static String local(String utc, String zone) { return Instant.parse(utc).atZone(ZoneId.of(zone)).toString(); }
    private static void row(StringBuilder out, String label, Object value) { out.append(label).append(": ").append(value == null ? "Bilinmiyor" : value).append('\n'); }
}
