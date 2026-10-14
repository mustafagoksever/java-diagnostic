package tr.diagnostic;

import java.util.Arrays;

/** Session-only credentials. Never serialize this class or put it in a manifest. */
public final class RemoteConnection implements AutoCloseable {
    public final String host, username, jdkHome;
    private final char[] password;
    public RemoteConnection(String host, String username, String jdkHome, char[] password) {
        validate(host, username, jdkHome);
        if (password == null || password.length == 0) throw new IllegalArgumentException("Sunucu parolasını girin.");
        this.host = host.trim(); this.username = username.trim(); this.jdkHome = jdkHome.trim(); this.password = password.clone();
    }
    static void validate(String host, String username, String jdkHome) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) throw new IllegalArgumentException("Sunucu IPv4 adresi veya DNS adı olmalı.");
        if (username == null || username.trim().isEmpty() || username.matches("(?s).*[\\r\\n\\x00].*")) throw new IllegalArgumentException("Geçerli Windows kullanıcı adını girin.");
        if (jdkHome == null || !jdkHome.matches("(?s)[A-Za-z]:[/\\\\].+") || jdkHome.matches("(?s).*[\\r\\n\\x00\"].*")) throw new IllegalArgumentException("Sunucu JDK yolu C:/Java/jdk8 gibi mutlak olmalı.");
    }
    char[] passwordCopy() { return password.clone(); }
    @Override public void close() { Arrays.fill(password, '\0'); }
}
