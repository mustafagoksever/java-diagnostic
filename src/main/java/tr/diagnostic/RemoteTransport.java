package tr.diagnostic;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

interface RemoteTransport {
    JsonObject execute(JsonObject settings, Path staging, RemoteConnection connection, Consumer<String> progress) throws IOException, InterruptedException;
}
