package tr.diagnostic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Replaceable boundary for a future per-session agent/service transport. */
public interface CommandExecutor {
    CommandResult run(List<String> command, long timeoutMillis, Path stdout, Path stderr) throws IOException, InterruptedException;
}
