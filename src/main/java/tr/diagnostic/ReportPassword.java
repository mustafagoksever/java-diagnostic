package tr.diagnostic;

/** Default deployment password. Embedded values remain recoverable from a JAR. */
final class ReportPassword {
    private ReportPassword() { }

    static char[] defaultPassword() {
        // Return a fresh, clearable buffer; never print or persist it in a report.
        return new char[]{'A', 's', 'e', 'l', 's', 'a', 'n', '1', '2', '3', 'R', 'a', 'd', 'a', 'r'};
    }
}
