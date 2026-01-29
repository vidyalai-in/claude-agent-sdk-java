package in.vidyalai.claude.sdk.exceptions;

import org.jspecify.annotations.Nullable;

/**
 * Raised when the CLI process fails.
 */
public class ProcessException extends ClaudeSDKException {

    @Nullable
    private final Integer exitCode;

    @Nullable
    private final String stderr;

    /**
     * Creates a new exception with the specified message.
     *
     * @param message the detail message
     */
    public ProcessException(String message) {
        super(message);
        this.exitCode = null;
        this.stderr = null;
    }

    /**
     * Creates a new exception with exit code and stderr.
     *
     * @param message  the detail message
     * @param exitCode the process exit code
     * @param stderr   the stderr output
     */
    public ProcessException(String message, @Nullable Integer exitCode, @Nullable String stderr) {
        super(buildMessage(message, exitCode, stderr));
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    private static String buildMessage(String message, @Nullable Integer exitCode, @Nullable String stderr) {
        StringBuilder sb = new StringBuilder(message);
        if (exitCode != null) {
            sb.append(" (exit code: ").append(exitCode).append(")");
        }
        if ((stderr != null) && (!stderr.isBlank())) {
            sb.append("\nError output: ").append(stderr);
        }
        return sb.toString();
    }

    /**
     * Gets the process exit code.
     *
     * @return the exit code, or null if not available
     */
    @Nullable
    public Integer getExitCode() {
        return exitCode;
    }

    /**
     * Gets the stderr output.
     *
     * @return the stderr output, or null if not available
     */
    @Nullable
    public String getStderr() {
        return stderr;
    }

}
