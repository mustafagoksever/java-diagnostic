package tr.diagnostic;

import java.util.function.Consumer;

/** Completed work stages, independent of elapsed time and diagnostic success. */
interface CollectionProgress extends Consumer<String> {
    void update(int percent, String stage);

    static void report(Consumer<String> listener, int percent, String stage) {
        if (listener instanceof CollectionProgress) ((CollectionProgress) listener).update(percent, stage);
    }

    final class Event {
        final int percent;
        final String message;
        Event(int percent, String message) { this.percent = percent; this.message = message; }
    }
}
