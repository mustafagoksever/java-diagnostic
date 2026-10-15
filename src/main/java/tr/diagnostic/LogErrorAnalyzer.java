package tr.diagnostic;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Analyze copied text only; bounded tails prevent large recording files from dominating. */
public final class LogErrorAnalyzer {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern LICENSE_ERROR = Pattern.compile(
        "\\blicen[cs]e\\b[^\\r\\n]{0,160}\\b(?:not\\s+valid|invalid|expired|denied)\\b|\\b(?:invalid|expired)\\s+licen[cs]e\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ERROR = Pattern.compile(
        "\\b(ERROR|FATAL|timeout)\\b|[A-Za-z0-9_.$]*Exception\\b|OutOfMemoryError|timed out"
        + "|\\bhaberle[sş]me\\s+ar[iı]zal[iı]\\b|\\bba[gğ]lant[iı]\\s+(?:kurulamad[iı]|koptu)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public void write(Path staging) throws IOException {
        StringBuilder output = new StringBuilder("LOG HATA ÖZETİ\nMetin eşleşmeleri olası inceleme noktalarıdır; tek başına arıza teşhisi değildir.\nHer dosyanın son 8 MiB kesitinden en fazla 20 eşleşme gösterilir; en fazla 200 dosya. Son lisans hatası örneği korunur, kalan yerler son eşleşmelere ayrılır.\n\n");
        Path root = staging.resolve("logs"); int files = 0, matches = 0;
        if (Files.isDirectory(root)) try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> paths = stream.filter(Files::isRegularFile).sorted().iterator();
            while (paths.hasNext()) {
                Path path = paths.next(); String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".out") || name.endsWith(".err") || name.endsWith(".trace"))) continue;
                if (files++ >= 200) { output.append("Dosya analiz sınırına ulaşıldı; diğer ham dosyaları inceleyin.\n"); break; }
                long offset = Math.max(0, Files.size(path) - MAX_BYTES);
                ByteBuffer bytes = ByteBuffer.allocate((int) Math.min(MAX_BYTES, Files.size(path)));
                try (SeekableByteChannel input = Files.newByteChannel(path)) { input.position(offset); while (bytes.hasRemaining() && input.read(bytes) != -1) { } }
                bytes.flip(); String content;
                try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(bytes.asReadOnlyBuffer()).toString(); }
                catch (CharacterCodingException e) { content = Charset.forName("windows-1254").decode(bytes).toString(); }
                SortedMap<Integer, String> last = new TreeMap<>(); int line = 0;
                Integer licenseLine = null; String licenseEvidence = null;
                try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
                    if (offset > 0) reader.readLine(); // Drop an incomplete leading line.
                    String value;
                    while ((value = reader.readLine()) != null) {
                        line++;
                        boolean licenseFailure = LICENSE_ERROR.matcher(value).find();
                        if (!licenseFailure && !ERROR.matcher(value).find()) continue;
                        matches++;
                        if (last.size() == 20) last.remove(last.firstKey());
                        String evidence = (offset == 0 ? "satır " : "son kesit satırı ") + line + ": " + value.substring(0, Math.min(2000, value.length()));
                        last.put(line, evidence);
                        if (licenseFailure) { licenseLine = line; licenseEvidence = evidence; }
                    }
                }
                if (licenseLine != null && !last.containsKey(licenseLine)) {
                    if (last.size() == 20) last.remove(last.firstKey());
                    last.put(licenseLine, licenseEvidence);
                }
                if (!last.isEmpty()) {
                    output.append(DiagnosticReportGenerator.relative(staging, path)).append(" (başlangıç byte offset: ").append(offset).append(")\n");
                    for (String value : last.values()) output.append("  ").append(value).append('\n'); output.append('\n');
                }
            }
        }
        output.append("Taranan kesitlerde toplam eşleşme: ").append(matches).append(". Ham kaynakların tümü bu analizle taranmış sayılmaz.\n");
        Files.write(staging.resolve("log-hata-ozeti.txt"), output.toString().getBytes(StandardCharsets.UTF_8));
    }
}
