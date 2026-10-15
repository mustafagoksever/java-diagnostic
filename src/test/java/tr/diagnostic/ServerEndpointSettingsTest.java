package tr.diagnostic;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.*;

public class ServerEndpointSettingsTest {
    private Preferences store;
    @Before public void create(){store=Preferences.userRoot().node("tr/diagnostic/test-endpoint-"+UUID.randomUUID());}
    @After public void remove()throws Exception{store.removeNode();Preferences.userRoot().flush();}
    @Test public void savedAddressSurvivesNewSettingsInstanceAndContainsNoCredentials()throws Exception{
        ServerEndpointSettings settings=new ServerEndpointSettings(store);
        Assert.assertEquals("192.0.2.1",settings.hostOrDefault("192.0.2.1"));
        settings.saveHost("192.0.2.25");
        Assert.assertEquals("192.0.2.25",new ServerEndpointSettings(store).hostOrDefault("192.0.2.1"));
        Assert.assertArrayEquals(new String[]{"serverHost"},store.keys());
    }
    @Test public void invalidAddressDoesNotReplacePreviouslySavedAddress()throws Exception{
        ServerEndpointSettings settings=new ServerEndpointSettings(store);settings.saveHost("192.0.2.25");
        try{settings.saveHost("bad/host");Assert.fail("Invalid host accepted");}catch(IllegalArgumentException expected){}
        Assert.assertEquals("192.0.2.25",settings.hostOrDefault("fallback"));
        store.put("serverHost","bad/host");Assert.assertEquals("192.0.2.1",settings.hostOrDefault("192.0.2.1"));
    }
}
