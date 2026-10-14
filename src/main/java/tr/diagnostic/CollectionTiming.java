package tr.diagnostic;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/** Client-side operation times; elapsed time is independent of clock adjustments. */
final class CollectionTiming {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss XXX");
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    interface Task<T> {
        T run() throws IOException, InterruptedException;
    }

    static <T> T run(Consumer<String> log, Task<T> task) throws IOException, InterruptedException {
        long started = System.nanoTime();
        log.accept("Toplama başlangıç zamanı: " + TIME.format(ZonedDateTime.now()));
        boolean completed = false;
        try {
            T result = task.run();
            completed = true;
            return result;
        } finally {
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            long wholeSeconds = (long) seconds;
            log.accept("Toplama bitiş zamanı: " + TIME.format(ZonedDateTime.now())
                + (completed ? "" : " | İşlem başarısız veya kesildi."));
            log.accept(String.format(TURKISH, "Toplam süre: %.2f dakika (%d dk %d sn).",
                seconds / 60.0, wholeSeconds / 60, wholeSeconds % 60));
        }
    }
}
