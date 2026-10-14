package tr.diagnostic;

import tr.diagnostic.DiagnosticReportGenerator.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Heuristic offline HotSpot analysis, never a definitive diagnosis. */
public final class ThreadDumpAnalyzer {
    private static final Pattern HEADER = Pattern.compile("^\"(.*)\".*?\\bnid=([^\\s]+).*$");
    private static final Pattern DEADLOCK = Pattern.compile("Found (one|[0-9]+) (?:Java-level )?deadlocks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCKED = Pattern.compile("^- locked <([^>]+)>");
    private static final Pattern WAITING_LOCK = Pattern.compile("^- waiting to lock <([^>]+)>");
    public static final class Analysis {
        public long pid; public int samples; public int parsedSamples; public int reportedDeadlocks;
        public Map<String, Integer> lastThreadStates = new TreeMap<>();
        public List<ThreadEntry> blockedThreads = new ArrayList<>(), unchangedThreads = new ArrayList<>();
        public List<UiFinding> uiFindings = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }
    public static final class UiFinding {
        public String category, threadName, nativeId, state, callSite, explanation;
        public int observedSamples;
        public boolean presentInAllThreeSamples;
        public List<String> evidence = new ArrayList<>(), threadsWaitingOnHeldLocks = new ArrayList<>();
    }
    public static final class ThreadEntry {
        public String name, nativeId, state = "UNKNOWN";
        public List<String> stack = new ArrayList<>();
        private String key() { return name + "\u0000" + nativeId; }
        private String signature() { return state + "\n" + String.join("\n", stack); }
    }
    public Analysis analyze(JvmReport report, Path staging) throws IOException {
        Analysis result = new Analysis(); result.pid = report.process.pid;
        List<Map<String, ThreadEntry>> parsed = new ArrayList<>();
        Set<Integer> seenSamples = new HashSet<>();
        for (Operation op : report.operations) {
            if (!op.success || !"thread-dump".equals(op.type) || op.artifact == null || !seenSamples.add(op.sample)) continue;
            result.samples++;
            Path artifact = staging.resolve(op.artifact).normalize();
            if (!artifact.startsWith(staging.normalize())) throw new IOException("Dump analiz yolu staging dışında.");
            String dump = ProcessCommandExecutor.readBounded(artifact, 8 * 1024 * 1024);
            if (Files.size(artifact) > 8 * 1024 * 1024) result.notes.add("Örnek " + op.sample + ": analiz ilk 8 MiB ile sınırlı.");
            Matcher deadlock = DEADLOCK.matcher(dump);
            while (deadlock.find()) result.reportedDeadlocks = Math.max(result.reportedDeadlocks, deadlock.group(1).equalsIgnoreCase("one") ? 1 : Integer.parseInt(deadlock.group(1)));
            Map<String, ThreadEntry> threads = parse(dump);
            if (threads.isEmpty()) { result.notes.add("Örnek " + op.sample + ": HotSpot thread biçimi çözümlenemedi; ham dosyayı inceleyin."); continue; }
            parsed.add(threads);
        }
        result.parsedSamples = parsed.size();
        if (!parsed.isEmpty()) {
            Map<String, ThreadEntry> last = parsed.get(parsed.size() - 1);
            for (ThreadEntry thread : last.values()) {
                result.lastThreadStates.merge(thread.state, 1, Integer::sum);
                if (thread.state.equals("BLOCKED")) result.blockedThreads.add(thread);
                if (parsed.size() >= 3 && !thread.stack.isEmpty() && parsed.stream().allMatch(sample -> sample.containsKey(thread.key()) && sample.get(thread.key()).signature().equals(thread.signature()))) result.unchangedThreads.add(thread);
                if (isUiThread(thread)) {
                    String network = networkCall(thread);
                    if (network != null) {
                        UiFinding finding = uiFinding(thread, "NETWORK_ON_UI", network);
                        finding.observedSamples = (int) parsed.stream().filter(sample -> sample.containsKey(thread.key()) && network.equals(networkCall(sample.get(thread.key())))).count();
                        finding.presentInAllThreeSamples = parsed.size() >= 3 && finding.observedSamples == parsed.size();
                        finding.explanation = "Ekran thread'i ağ işlemi içinde. RUNNABLE durumu CPU tükettiğini veya arayüzün yanıt verdiğini kanıtlamaz. Aynı ağ çağrısı sayılırken değişen socket/kilit adresleri dikkate alınmaz; bu gözlem deadlock teşhisi değildir.";
                        Set<String> held = new HashSet<>();
                        for (String line : thread.stack) { Matcher m = LOCKED.matcher(line); if (m.find()) held.add(m.group(1)); }
                        for (ThreadEntry other : last.values()) for (String line : other.stack) {
                            Matcher m = WAITING_LOCK.matcher(line);
                            if (m.find() && held.contains(m.group(1))) { finding.threadsWaitingOnHeldLocks.add(other.name + " nid=" + other.nativeId); break; }
                        }
                        result.uiFindings.add(finding);
                    } else if (thread.stack.stream().anyMatch(line -> line.startsWith("at java.awt.Dialog.show("))) {
                        UiFinding finding = uiFinding(thread, "MODAL_DIALOG_CONTEXT", "java.awt.Dialog.show");
                        finding.observedSamples = (int) parsed.stream().filter(sample -> sample.containsKey(thread.key()) && sample.get(thread.key()).stack.stream().anyMatch(line -> line.startsWith("at java.awt.Dialog.show("))).count();
                        finding.presentInAllThreeSamples = parsed.size() >= 3 && finding.observedSamples == parsed.size();
                        finding.explanation = "Modal diyalog bağlamı görülüyor. Olay kuyruğunu beklemek tek başına donma değildir; açık/gizli bir diyalog olup olmadığı ekrandan doğrulanmalıdır.";
                        result.uiFindings.add(finding);
                    }
                }
            }
        }
        result.notes.add("Aynı stack veya WAITING durumu tek başına donma kanıtı değildir; thread rolü ve kaynak ölçümleriyle birlikte incelenmelidir.");
        result.notes.add("Deadlock sayısı yalnızca dump aracının açık bildirimidir; bildirim olmaması deadlock olmadığını garanti etmez.");
        return result;
    }
    private static boolean isUiThread(ThreadEntry thread) {
        return thread.name.startsWith("AWT-EventQueue-") || thread.name.equals("JavaFX Application Thread")
            || thread.stack.stream().anyMatch(line -> line.startsWith("at org.eclipse.swt.widgets.Display.readAndDispatch("));
    }
    private static String networkCall(ThreadEntry thread) {
        for (String line : thread.stack) {
            if (!line.startsWith("at ")) continue;
            String frame = line.substring(3).split("\\(", 2)[0];
            if ((frame.startsWith("java.net.") && (frame.endsWith(".waitForConnect") || frame.endsWith(".socketConnect") || frame.endsWith(".socketRead0") || frame.endsWith(".socketRead")))
                || frame.equals("sun.nio.ch.Net.connect0") || frame.equals("sun.nio.ch.SocketDispatcher.read0")) return frame;
        }
        return null;
    }
    private static UiFinding uiFinding(ThreadEntry thread, String category, String callSite) {
        UiFinding finding = new UiFinding(); finding.category = category; finding.threadName = thread.name;
        finding.nativeId = thread.nativeId; finding.state = thread.state; finding.callSite = callSite;
        for (String line : thread.stack) if (line.startsWith("at ") && finding.evidence.size() < 30) finding.evidence.add(line);
        return finding;
    }
    static Map<String, ThreadEntry> parse(String dump) {
        Map<String, ThreadEntry> threads = new LinkedHashMap<>(); ThreadEntry current = null;
        for (String line : dump.split("\\R")) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) { current = new ThreadEntry(); current.name = header.group(1); current.nativeId = header.group(2); threads.put(current.key(), current); continue; }
            if (line.startsWith("\"") || (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)))) { current = null; continue; }
            if (current == null) continue;
            String content = line.trim();
            if (content.startsWith("java.lang.Thread.State:")) current.state = content.substring("java.lang.Thread.State:".length()).trim().split("\\s+", 2)[0];
            else if (content.startsWith("at ") || content.startsWith("- ")) current.stack.add(content);
        }
        return threads;
    }
    public void write(List<JvmReport> reports, Path staging) throws IOException {
        List<Analysis> results = new ArrayList<>();
        StringBuilder text = new StringBuilder("THREAD DUMP ANALİZ ÖZETİ\nKesin kök neden teşhisi değildir; ham dump'ları kaynak ölçümleriyle birlikte inceleyin.\n\n");
        for (JvmReport report : reports) {
            Analysis a;
            try { a = analyze(report, staging); }
            catch (IOException e) { a = new Analysis(); a.pid = report.process.pid; a.notes.add("Analiz okunamadı: " + e); }
            results.add(a);
            text.append("PID ").append(a.pid).append(" — ").append(report.process.label()).append('\n')
                .append("Başarılı dump: ").append(report.dumpCount).append("/3; çözümlenen: ").append(a.parsedSamples).append('\n')
                .append("Araç tarafından bildirilen deadlock: ").append(a.reportedDeadlocks).append('\n')
                .append("Son dump thread durumları: ").append(a.lastThreadStates).append('\n')
                .append("Kilit bekleyen BLOCKED thread sayısı: ").append(a.blockedThreads.size()).append('\n');
            text.append("EKRAN THREAD BULGULARI:\n");
            if (a.uiFindings.isEmpty()) text.append("  Desteklenen ağ/modal diyalog kalıplarında bulgu yok; bu ekranın donmadığını garanti etmez.\n");
            for (UiFinding finding : a.uiFindings) {
                text.append("  ").append(finding.category).append(" — ").append(finding.threadName).append(" nid=").append(finding.nativeId)
                    .append("; ").append(finding.observedSamples).append('/').append(a.parsedSamples).append(" örnekte: ").append(finding.callSite).append('\n')
                    .append("  ").append(finding.explanation).append('\n');
                for (String line : finding.evidence) text.append("    ").append(line).append('\n');
                if (!finding.threadsWaitingOnHeldLocks.isEmpty()) text.append("  Son örnekte ekran thread'inin tuttuğu kilitleri bekleyenler: ").append(finding.threadsWaitingOnHeldLocks).append('\n');
            }
            describe(text, a.blockedThreads, "BLOCKED thread'ler");
            describe(text, a.unchangedThreads, "En az üç dump boyunca state/stack/lock bilgisi aynı thread'ler");
            for (String note : a.notes) text.append("Not: ").append(note).append('\n');
            text.append('\n');
        }
        if (reports.isEmpty()) text.append("Java hedefi bulunamadı; thread dump analiz edilemedi.\n");
        DiagnosticReportGenerator.writeJson(staging.resolve("thread-dump-analysis.json"), results);
        Files.write(staging.resolve("analiz-ozeti.txt"), text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void describe(StringBuilder text, List<ThreadEntry> threads, String title) {
        text.append(title).append(" (ilk 20):\n"); int count = 0;
        for (ThreadEntry thread : threads) {
            if (count++ >= 20) break;
            text.append("  ").append(thread.name).append(" nid=").append(thread.nativeId).append(" state=").append(thread.state).append('\n');
            for (String line : thread.stack.subList(0, Math.min(4, thread.stack.size()))) text.append("    ").append(line).append('\n');
        }
    }
}
