package tr.diagnostic;

import java.util.List;

public final class CommandResult {
    public List<String> command;
    public int exitCode = -1;
    public boolean timedOut;
    public long durationMillis;
    public String stdout = "";
    public String stderr = "";
    public boolean outputTruncated;
    public String launchError;
    public boolean success() { return !timedOut && launchError == null && exitCode == 0; }
    public String error() {
        if (timedOut) return "TIMEOUT: tanılama aracı süre sınırını aştı.";
        if (launchError != null) return "TOOL_START_FAILED: " + launchError;
        if (success()) return null;
        return "EXIT_" + exitCode + ": " + (stderr.isEmpty() ? stdout : stderr);
    }
}
