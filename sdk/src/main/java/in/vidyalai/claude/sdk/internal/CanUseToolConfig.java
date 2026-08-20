package in.vidyalai.claude.sdk.internal;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;

/**
 * Validates a {@code canUseTool} callback and routes permission prompts over
 * stdio.
 *
 * <p>Shared by {@link in.vidyalai.claude.sdk.ClaudeSDK}'s query family and
 * {@link in.vidyalai.claude.sdk.ClaudeSDKClient#connect()} so both entry points
 * enforce the same rules. Mirrors the Python SDK's
 * {@code _configure_can_use_tool}.
 */
public final class CanUseToolConfig {

    private CanUseToolConfig() {
        // Utility class
    }

    /**
     * Returns {@code options} unchanged when no callback is set; otherwise
     * checks it is not combined with {@code permissionPromptToolName}, emits
     * the shadowing advisory, and returns a copy with
     * {@code permissionPromptToolName = "stdio"} so the CLI sends permission
     * requests over the control protocol.
     *
     * <p>No streaming-mode check: string prompts are streamed over stdin
     * internally, so the control protocol is available for them too.
     *
     * @param options the caller's options
     * @return the options to run with
     * @throws IllegalArgumentException if both {@code canUseTool} and
     *                                  {@code permissionPromptToolName} are set
     */
    public static ClaudeAgentOptions configureCanUseTool(ClaudeAgentOptions options) {
        if (options.canUseTool() == null) {
            return options;
        }
        // canUseTool and permissionPromptToolName are mutually exclusive
        if (options.permissionPromptToolName() != null) {
            throw new IllegalArgumentException(
                    "canUseTool callback cannot be used with permissionPromptToolName. " +
                            "Please use one or the other.");
        }
        // Advisory: warn if other options shadow the callback
        CanUseToolShadow.warnIfShadowed(options);
        // Automatically set permissionPromptToolName to "stdio" for control protocol
        return options.withPermissionPromptToolName("stdio");
    }

}
