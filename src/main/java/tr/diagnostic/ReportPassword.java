package tr.diagnostic;

/** Retrieves a fresh, clearable buffer from configuration; contains no deployment secret. */
final class ReportPassword {
    private ReportPassword() { }

    static char[] configured(CollectorConfiguration config) {
        char[] password = config.zipPasswordCopy();
        if (password.length < 12) {
            java.util.Arrays.fill(password, '\0');
            throw new IllegalArgumentException("ZIP parolasını config.yaml zipPassword alanında tanımlayın veya CLI parola seçeneklerini kullanın.");
        }
        return password;
    }
}
