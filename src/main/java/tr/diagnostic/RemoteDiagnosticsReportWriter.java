package tr.diagnostic;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Writes readable summaries from server measurements without issuing commands. */
public final class RemoteDiagnosticsReportWriter {
    public void write(DiagnosticReportGenerator.Manifest manifest, Path staging) throws IOException {
        if (manifest.ping != null) writePing(manifest.ping, staging, manifest.errors);
        if (manifest.vxworks != null) writeVxWorks(manifest.vxworks, staging, manifest.errors);
    }
    private void writePing(JsonObject report, Path staging, List<String> errors) throws IOException {
        StringBuilder text = new StringBuilder("SUNUCUDAN PİNG TESTİ\nKaynak: ").append(value(report, "sourceComputer")).append('\n');
        if(report.has("subnet") && !report.get("subnet").isJsonNull() && !value(report,"subnet").isEmpty()) text.append("Ağ: ").append(value(report,"subnet")).append('\n');
        int replied=0,unanswered=0,incomplete=0;
        List<JsonObject> results=objectRows(report,"results",errors,"ping results");
        for(JsonObject row:results) {
            String status=value(row,"status");
            if("REACHABLE".equals(status)||"INTERMITTENT".equals(status)) replied++;
            else if("NO_ECHO_REPLY".equals(status)) unanswered++;
            else incomplete++;
        }
        text.append("Yanıt veren: ").append(replied).append(" | Yanıt yok: ").append(unanswered).append(" | Tamamlanamayan: ").append(incomplete).append('\n');
        if(report.has("parallelism")) text.append("Paralel IP sınırı: ").append(value(report,"parallelism")).append(" | Süre (ms): ").append(value(report,"durationMillis")).append('\n');
        for(JsonElement error:ResourceMonitor.array(report,"errors")) if(error.isJsonPrimitive()) text.append("Tarama notu: ").append(error.getAsString()).append('\n');
        text.append('\n');
        for (JsonObject row : results) {
            text.append(value(row,"address")).append(" | ").append(value(row,"status"))
                .append(" | yanıt=").append(value(row,"received")).append('/').append(value(row,"sent"))
                .append(" | kayıp=%").append(value(row,"lossPercent")).append(" | ortalama ms=").append(value(row,"averageRoundTripMillis")).append('\n');
        }
        text.append("Ping yanıtı alınamaması cihazın kapalı olduğunu tek başına kanıtlamaz.\n");
        writeText(staging.resolve("ping/SONUC.txt"), text.toString());
    }
    private void writeVxWorks(JsonObject report, Path staging, List<String> errors) throws IOException {
        StringBuilder text = new StringBuilder("VXWORKS TASK İZLERİ VE SOCKET/PORT DURUMU\nÖnce version ile VxWorks sürümü doğrulanır; ifconfig, gerekirse ifShow ile cihazın IP/MAC bilgisi okunur.\nArdından TatlYaz ve ilk i listesindeki TASKID'ler için tt 0xTASKID ve ti 0xTASKID denenir.\nEk sorgular: checkStack 0, taskShow 0,2, devs, whoami, w; PEND task'lar için tw 0xTASKID.\n\n");
        if ("auto".equals(value(report,"discoveryMode"))) text.append("Port seçimi: SUNUCUDA OTOMATİK KEŞİF\nBulunan portlar: ").append(joined(report,"detectedPorts")).append('\n');
        else text.append("Port seçimi: config'teki port listesi\n");
        for (JsonElement error : ResourceMonitor.array(report,"errors")) if(error.isJsonPrimitive()) text.append("Port keşif notu: ").append(error.getAsString()).append('\n');
        text.append('\n');
        StringBuilder tasks = new StringBuilder("\uFEFFport,ipAddresses,taskId,name,state,attempted,frameCount,error,artifact,infoAttempted,infoCollected,infoError,infoArtifact\r\n");
        StringBuilder sockets = new StringBuilder("\uFEFFport,ipAddresses,protocol,addressFamily,localAddress,localPort,foreignAddress,state,receiveQueue,sendQueue,artifact,line\r\n");
        int total = 0, attempted = 0, frames = 0, infoAttempted = 0, infos = 0;
        for (JsonObject port : objectRows(report,"ports",errors,"VxWorks ports")) {
            JsonObject identity = port.has("identity") && port.get("identity").isJsonObject() ? port.getAsJsonObject("identity") : null;
            String ips = joined(identity,"addresses"), macs = joined(identity,"macAddresses");
            total += number(port,"initialTaskCount"); attempted += number(port,"attemptedTaskCount"); frames += number(port,"tracesWithFrames");
            infoAttempted += number(port,"attemptedTaskInfoCount"); infos += number(port,"taskInfosCollected");
            text.append(value(port,"port")).append(" | IP: ").append(ips.isEmpty() ? "ALINAMADI" : ips);
            if (!macs.isEmpty()) text.append(" | MAC: ").append(macs);
            text.append(" | ").append(value(port,"status"))
                .append(" | task=").append(number(port,"initialTaskCount")).append(" | denenen=").append(number(port,"attemptedTaskCount"))
                .append(" | frame alınan=").append(number(port,"tracesWithFrames"))
                .append(" | ti alınan/denenen=").append(number(port,"taskInfosCollected")).append('/').append(number(port,"attemptedTaskInfoCount"));
            if (!flag(port,"networkAvailable")) text.append(" | socket tablosu ALINAMADI; portlar kapalı anlamına gelmez");
            if (!value(port,"error").isEmpty()) text.append(" | ").append(value(port,"error"));
            text.append('\n');
            if(port.has("tatlYaz") && port.get("tatlYaz").isJsonObject()) {
                JsonObject tatl=port.getAsJsonObject("tatlYaz");
                text.append("  TatlYaz: ").append(flag(tatl,"collected")?"ALINDI":flag(tatl,"attempted")?"ALINAMADI":"DENENMEDİ");
                if(!value(tatl,"artifact").isEmpty()) text.append(" | çıktı: ").append(value(tatl,"artifact"));
                if(!value(tatl,"error").isEmpty()) text.append(" | ").append(value(tatl,"error"));
                text.append('\n');
            }
            JsonObject detail;
            try {
                Path path = safePath(staging, value(port,"artifact"));
                if (!Files.isRegularFile(path)) { errors.add("VxWorks ayrıntı dosyası alınamadı: " + value(port,"port")); continue; }
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { detail = JsonParser.parseReader(reader).getAsJsonObject(); }
            } catch (IOException | RuntimeException e) { errors.add("VxWorks ayrıntısı okunamadı: " + value(port,"port")); continue; }
            for(JsonObject diagnostic:objectRows(detail,"diagnostics",errors,value(port,"port")+" diagnostics")) {
                text.append("  ").append(value(diagnostic,"command")).append(": ").append(flag(diagnostic,"collected")?"YANIT ALINDI":"ALINAMADI")
                    .append(" | ").append(value(diagnostic,"artifact"));
                if(!value(diagnostic,"error").isEmpty())text.append(" | ").append(value(diagnostic,"error"));
                text.append('\n');
            }
            for (JsonObject task : objectRows(detail,"tasks",errors,value(port,"port")+" tasks")) {
                csv(tasks, value(port,"port"),ips,value(task,"taskId"),value(task,"name"),value(task,"state"),value(task,"attempted"),value(task,"frameCount"),value(task,"error"),value(task,"artifact"),value(task,"infoAttempted"),value(task,"infoCollected"),value(task,"infoError"),value(task,"infoArtifact"));
            }
            SortedSet<Integer> listeners = new TreeSet<>();
            for (JsonObject socket : objectRows(detail,"socketRows",errors,value(port,"port")+" socketRows")) {
                csv(sockets,value(port,"port"),ips,value(socket,"protocol"),value(socket,"addressFamily"),value(socket,"localAddress"),value(socket,"localPort"),value(socket,"foreignAddress"),value(socket,"state"),value(socket,"receiveQueue"),value(socket,"sendQueue"),value(socket,"artifact"),value(socket,"line"));
                if (value(socket,"protocol").startsWith("TCP") && value(socket,"state").equals("LISTEN")) listeners.add(number(socket,"localPort"));
            }
            if (!listeners.isEmpty()) text.append("  TCP LISTEN: ").append(listeners).append('\n');
        }
        text.append("\nToplam task: ").append(total).append("; denenen: ").append(attempted).append("; frame alınan: ").append(frames)
            .append("; ti alınan/denenen: ").append(infos).append('/').append(infoAttempted).append(".\n")
            .append("SELF_TRACE_UNSUPPORTED, UNBREAKABLE_TASK ve TOP_FRAME_ERROR yanıtları ham task dosyalarında saklanır.\n")
            .append("ti, task durum/stack/register ayrıntısını gösterir. Alınamayan veya TASKID'si doğrulanamayan yanıt ayrıca kaydedilir.\n")
            .append("LISTEN yerel dinleme durumudur; dışarıdan erişim garantisi vermez. UDP satırları socket bilgisidir.\n")
            .append("Sunucu zamanları sunucu saatini kullanır; istemci saat farkı remote-context.json içindedir.\n");
        writeText(staging.resolve("vxworks/SONUC.txt"),text.toString());
        writeText(staging.resolve("vxworks/task-listesi.csv"),tasks.toString());
        writeText(staging.resolve("vxworks/socket-port-listesi.csv"),sockets.toString());
    }
    private static Path safePath(Path staging, String relative) throws IOException {
        Path path = staging.resolve(relative).normalize();
        if (!relative.matches("vxworks/ports/port-COM[1-9][0-9]{0,4}/port-result\\.json") || !path.startsWith(staging)) throw new IOException("Geçersiz VxWorks ayrıntı yolu.");
        return path;
    }
    private static List<JsonObject> objectRows(JsonObject source,String key,List<String> errors,String context) {
        List<JsonObject> rows=new ArrayList<>();
        if(source.has(key) && !source.get(key).isJsonArray() && !source.get(key).isJsonNull()) {
            errors.add("Geçersiz tanılama listesi: "+context);return rows;
        }
        for(JsonElement entry:ResourceMonitor.array(source,key)) {
            if(entry.isJsonObject())rows.add(entry.getAsJsonObject());
            else errors.add("Geçersiz tanılama kaydı atlandı: "+context);
        }
        return rows;
    }
    private static String value(JsonObject object,String key) { try { return object.get(key).getAsString().replace('\r',' ').replace('\n',' '); } catch(RuntimeException e) { return ""; } }
    private static String joined(JsonObject object,String key) {
        List<String> values = new ArrayList<>();
        for (JsonElement item : ResourceMonitor.array(object,key)) if(item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) values.add(item.getAsString());
        return String.join("; ",values);
    }
    static boolean flag(JsonObject object,String key) { try { return object.get(key).getAsBoolean(); } catch(RuntimeException e) { return false; } }
    private static int number(JsonObject object,String key) { try { return object.get(key).getAsInt(); } catch (RuntimeException e) { return 0; } }
    private static void csv(StringBuilder output,String... values) {
        for (int i=0;i<values.length;i++) { if(i>0)output.append(','); output.append('"').append(values[i].replace("\"","\"\"")).append('"'); } output.append("\r\n");
    }
    private static void writeText(Path path,String text) throws IOException { Files.createDirectories(path.getParent());Files.write(path,text.getBytes(StandardCharsets.UTF_8)); }
}
