package in.vidyalai.claude.sdk.exceptions;

import org.jspecify.annotations.Nullable;

/**
 * Raised when Claude Code CLI is not found or not installed.
 */
public class CLINotFoundException extends CLIConnectionException {

    private static final String DEFAULT_MESSAGE = """
            Claude Code not found. Install by following:
                https://code.claude.com/docs/en/setup

            Or provide the path via ClaudeAgentOptions:
                ClaudeAgentOptions.builder().cliPath(Path.of("/path/to/claude")).build()
            """;

    @Nullable
    private final String cliPath;

    /**
     * Creates a new exception with the default message.
     */
    public CLINotFoundException() {
        super(DEFAULT_MESSAGE);
        this.cliPath = null;
    }

    /**
     * Creates a new exception with the specified message.
     *
     * @param message the detail message
     */
    public CLINotFoundException(String message) {
        super(message);
        this.cliPath = null;
    }

    /**
     * Creates a new exception with the specified message and CLI path.
     *
     * @param message the detail message
     * @param cliPath the path that was searched
     */
    public CLINotFoundException(String message, String cliPath) {
        super(message + ": " + cliPath);
        this.cliPath = cliPath;
    }

    /**
     * Gets the CLI path that was searched.
     *
     * @return the CLI path, or null if not specified
     */
    @Nullable
    public String getCliPath() {
        return cliPath;
    }

}
