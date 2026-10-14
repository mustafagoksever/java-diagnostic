package tr.diagnostic;

import com.google.gson.*;
import java.util.Locale;

/** Whole-machine usage, distinct from per-process CPU and committed memory. */
final class ServerResourceUsage {
    String serverName;
    String sampledAtUtc;
    Double cpuUsagePercent;
    Double totalRamGiB;
    Double usedRamGiB;
    Double freeRamGiB;
    Double ramUsagePercent;

    static ServerResourceUsage fromWindows(JsonObject windows) {
        ServerResourceUsage usage = new ServerResourceUsage();
        if (windows == null) return usage;
        usage.serverName = text(first(windows, "computer"), "Name");
        usage.sampledAtUtc = text(windows, "sampledAtUtc");
        Double cpu = number(first(windows, "cpuTotal"), "PercentProcessorTime");
        if (cpu != null && cpu <= 100) usage.cpuUsagePercent = cpu;
        JsonObject os = first(windows, "operatingSystem");
        Double totalKiB = number(os, "TotalVisibleMemorySize");
        Double freeKiB = number(os, "FreePhysicalMemory");
        if (totalKiB != null && totalKiB > 0) {
            usage.totalRamGiB = totalKiB / (1024 * 1024);
            if (freeKiB != null && freeKiB <= totalKiB) {
                usage.freeRamGiB = freeKiB / (1024 * 1024);
                usage.usedRamGiB = (totalKiB - freeKiB) / (1024 * 1024);
                usage.ramUsagePercent = (totalKiB - freeKiB) / totalKiB * 100;
            }
        }
        return usage;
    }
    String asText() {
        return "SUNUCU CPU / RAM KULLANIMI\n"
            + "Sunucu: " + display(serverName) + "\n"
            + "Ölçüm zamanı (UTC): " + display(sampledAtUtc) + "\n\n"
            + "CPU kullanımı (sunucu toplamı): " + value(cpuUsagePercent, "%") + "\n"
            + "Toplam RAM (işletim sistemine görünen): " + value(totalRamGiB, "GiB") + "\n"
            + "Kullanılan RAM: " + value(usedRamGiB, "GiB") + "\n"
            + "Boş RAM: " + value(freeRamGiB, "GiB") + "\n"
            + "RAM kullanımı: " + value(ramUsagePercent, "%") + "\n\n"
            + "Değerler rapor oluşturulurken alınan anlık ölçümlerdir.\n"
            + "CPU kaynağı: Windows Processor _Total / PercentProcessorTime.\n"
            + "RAM kaynağı: Windows TotalVisibleMemorySize ve FreePhysicalMemory.\n"
            + "1 GiB = 1024 MiB. Alınamayan veya geçersiz değerler Bilinmiyor olarak gösterilir.\n"
            + "Ham ölçümler ve varsa toplama hataları system-info.json ve Manifest.json içindedir.\n";
    }
    private static String value(Double n, String unit) { return n == null ? "Bilinmiyor" : String.format(Locale.forLanguageTag("tr-TR"), "%.2f %s", n, unit); }
    private static String display(String text) { return text == null ? "Bilinmiyor" : text.replace('\n', ' ').replace('\r', ' '); }
    private static JsonObject first(JsonObject object, String name) {
        JsonElement e = object.get(name);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() == 0) return null;
        JsonElement first = e.getAsJsonArray().get(0); return first.isJsonObject() ? first.getAsJsonObject() : null;
    }
    private static String text(JsonObject object, String name) {
        if (object == null) return null;
        JsonElement e = object.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
    private static Double number(JsonObject object, String name) {
        String text = text(object, name);
        if (text == null) return null;
        try { double value = Double.parseDouble(text); return Double.isFinite(value) && value >= 0 ? value : null; }
        catch (NumberFormatException e) { return null; }
    }
}
