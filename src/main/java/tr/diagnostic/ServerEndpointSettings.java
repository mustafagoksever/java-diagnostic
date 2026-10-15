package tr.diagnostic;

import java.io.IOException;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Only the server address is persisted, per Windows user; never credentials. */
final class ServerEndpointSettings {
    private final Preferences store;
    ServerEndpointSettings() { this(null); }
    ServerEndpointSettings(Preferences store) { this.store=store; }
    private Preferences node() { return store==null?Preferences.userNodeForPackage(Main.class).node("connection"):store; }
    String hostOrDefault(String fallback) {
        try { String host=node().get("serverHost",fallback); validate(host); return host; }
        catch (IllegalArgumentException | SecurityException e) { return fallback; }
    }
    void saveHost(String host) throws IOException {
        validate(host);
        try { Preferences settings=node();settings.put("serverHost",host);settings.flush(); }
        catch (BackingStoreException | SecurityException e) { throw new IOException("Sunucu adresi bu bilgisayara kaydedilemedi.",e); }
    }
    static void validate(String host) { RemoteConnection.validate(host,"endpoint-settings","auto"); }
}
