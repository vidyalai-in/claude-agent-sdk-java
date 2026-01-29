package in.vidyalai.claude.sdk.types.hook;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Output from hook callbacks.
 * See for documentation of the output types:
 * https://docs.anthropic.com/en/docs/claude-code/hooks#advanced%3A-json-output
 *
 * <p>
 * Hook outputs control the execution flow and provide feedback to Claude.
 * Use the builder pattern to construct output:
 *
 * <pre>{@code
 * HookOutput output = HookOutput.builder()
 *         .shouldContinue(true)
 *         .reason("Approved by policy")
 *         .build();
 * }</pre>
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names in its {@link #toMap()} method because it represents data <b>sent
 * to the CLI</b> in hook responses. See {@link in.vidyalai.claude.sdk.types}
 * package documentation for details.
 */
public final class HookOutput {

    // JSON serialization keys
    private static final String KEY_ASYNC = "async";
    private static final String KEY_ASYNC_TIMEOUT = "asyncTimeout";
    private static final String KEY_CONTINUE = "continue";
    private static final String KEY_SUPPRESS_OUTPUT = "suppressOutput";
    private static final String KEY_STOP_REASON = "stopReason";
    private static final String KEY_DECISION = "decision";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_REASON = "reason";
    private static final String KEY_HOOK_SPECIFIC_OUTPUT = "hookSpecificOutput";

    /**
     * Synchronous hook output with control and decision fields.
     * 
     * This defines the structure for hook callbacks to control execution and
     * provide feedback to Claude.
     * 
     * Common Control Fields:
     * continue: Whether Claude should proceed after hook execution (default: True).
     * suppressOutput: Hide stdout from transcript mode (default: False).
     * stopReason: Message shown when continue is False.
     * 
     * Decision Fields:
     * decision: Set to "block" to indicate blocking behavior.
     * systemMessage: Warning message displayed to the user.
     * reason: Feedback message for Claude about the decision.
     * 
     * Hook-Specific Output:
     * hookSpecificOutput: Event-specific controls (e.g., permissionDecision for
     * PreToolUse, additionalContext for PostToolUse).
     */
    // Common control fields
    @Nullable
    private final Boolean shouldContinue; // "continue" in JSON
    @Nullable
    private final Boolean suppressOutput;
    @Nullable
    private final String stopReason;

    // Decision fields
    @Nullable
    private final String decision;
    @Nullable
    private final String systemMessage;
    @Nullable
    private final String reason;

    // Hook-specific output
    @Nullable
    private final HookSpecificOutput hookSpecificOutput;

    /**
     * Async hook output that defers hook execution.
     * 
     * Fields:
     * async: Set to True to defer hook execution.
     * asyncTimeout: Optional timeout in milliseconds for the async operation.
     */
    @Nullable
    private final Boolean async;
    @Nullable
    private final Integer asyncTimeout;

    private HookOutput(Builder builder) {
        this.shouldContinue = builder.shouldContinue;
        this.suppressOutput = builder.suppressOutput;
        this.stopReason = builder.stopReason;
        this.decision = builder.decision;
        this.systemMessage = builder.systemMessage;
        this.reason = builder.reason;
        this.hookSpecificOutput = builder.hookSpecificOutput;
        this.async = builder.async;
        this.asyncTimeout = builder.asyncTimeout;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an empty output (continue with no changes).
     *
     * @return an empty hook output
     */
    public static HookOutput empty() {
        return new Builder().build();
    }

    /**
     * Creates an async output to defer hook execution.
     *
     * @param timeoutMs optional timeout in milliseconds
     * @return an async hook output
     */
    public static HookOutput async(@Nullable Integer timeoutMs) {
        return new Builder().async(true).asyncTimeout(timeoutMs).build();
    }

    /**
     * Converts this output to a Map for JSON serialization.
     *
     * @return a map representation of this hook output
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();

        if (async != null && async) {
            result.put(KEY_ASYNC, true);
            if (asyncTimeout != null) {
                result.put(KEY_ASYNC_TIMEOUT, asyncTimeout);
            }
            return result;
        }

        if (shouldContinue != null) {
            result.put(KEY_CONTINUE, shouldContinue);
        }
        if (suppressOutput != null) {
            result.put(KEY_SUPPRESS_OUTPUT, suppressOutput);
        }
        if (stopReason != null) {
            result.put(KEY_STOP_REASON, stopReason);
        }
        if (decision != null) {
            result.put(KEY_DECISION, decision);
        }
        if (systemMessage != null) {
            result.put(KEY_SYSTEM_MESSAGE, systemMessage);
        }
        if (reason != null) {
            result.put(KEY_REASON, reason);
        }
        if (hookSpecificOutput != null) {
            result.put(KEY_HOOK_SPECIFIC_OUTPUT, hookSpecificOutput.toMap());
        }

        return result;
    }

    // Getters

    /**
     * Returns whether Claude should continue after hook execution.
     *
     * @return true if should continue, or null if not set
     */
    @Nullable
    public Boolean shouldContinue() {
        return shouldContinue;
    }

    /**
     * Returns whether to hide stdout from transcript mode.
     *
     * @return true if output should be suppressed, or null if not set
     */
    @Nullable
    public Boolean suppressOutput() {
        return suppressOutput;
    }

    /**
     * Returns the message shown when continue is false.
     *
     * @return the stop reason, or null if not set
     */
    @Nullable
    public String stopReason() {
        return stopReason;
    }

    /**
     * Returns the decision (e.g., "block").
     *
     * @return the decision, or null if not set
     */
    @Nullable
    public String decision() {
        return decision;
    }

    /**
     * Returns the warning message displayed to the user.
     *
     * @return the system message, or null if not set
     */
    @Nullable
    public String systemMessage() {
        return systemMessage;
    }

    /**
     * Returns the feedback message for Claude about the decision.
     *
     * @return the reason, or null if not set
     */
    @Nullable
    public String reason() {
        return reason;
    }

    /**
     * Returns the hook-specific output controls.
     *
     * @return the hook-specific output, or null if not set
     */
    @Nullable
    public HookSpecificOutput hookSpecificOutput() {
        return hookSpecificOutput;
    }

    /**
     * Returns whether this is an async hook output.
     *
     * @return true if async, or null if not set
     */
    @Nullable
    public Boolean isAsync() {
        return async;
    }

    /**
     * Returns the async timeout in milliseconds.
     *
     * @return the timeout, or null if not set
     */
    @Nullable
    public Integer asyncTimeout() {
        return asyncTimeout;
    }

    /**
     * Builder for HookOutput.
     */
    public static final class Builder {

        @Nullable
        private Boolean shouldContinue;
        @Nullable
        private Boolean suppressOutput;
        @Nullable
        private String stopReason;
        @Nullable
        private String decision;
        @Nullable
        private String systemMessage;
        @Nullable
        private String reason;
        @Nullable
        private HookSpecificOutput hookSpecificOutput;
        @Nullable
        private Boolean async;
        @Nullable
        private Integer asyncTimeout;

        private Builder() {
        }

        /**
         * Sets whether Claude should continue after hook execution.
         *
         * @param shouldContinue true to continue
         * @return this builder
         */
        public Builder shouldContinue(Boolean shouldContinue) {
            this.shouldContinue = shouldContinue;
            return this;
        }

        /**
         * Sets whether to hide stdout from transcript mode.
         *
         * @param suppressOutput true to suppress output
         * @return this builder
         */
        public Builder suppressOutput(Boolean suppressOutput) {
            this.suppressOutput = suppressOutput;
            return this;
        }

        /**
         * Sets the message shown when continue is false.
         *
         * @param stopReason the stop reason message
         * @return this builder
         */
        public Builder stopReason(String stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        /**
         * Sets the decision (e.g., "block").
         *
         * @param decision the decision
         * @return this builder
         */
        public Builder decision(String decision) {
            this.decision = decision;
            return this;
        }

        /**
         * Sets the warning message displayed to the user.
         *
         * @param systemMessage the system message
         * @return this builder
         */
        public Builder systemMessage(String systemMessage) {
            this.systemMessage = systemMessage;
            return this;
        }

        /**
         * Sets the feedback message for Claude.
         *
         * @param reason the reason message
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Sets the hook-specific output controls.
         *
         * @param hookSpecificOutput the hook-specific output
         * @return this builder
         */
        public Builder hookSpecificOutput(HookSpecificOutput hookSpecificOutput) {
            this.hookSpecificOutput = hookSpecificOutput;
            return this;
        }

        /**
         * Sets whether this is an async hook output.
         *
         * @param async true for async
         * @return this builder
         */
        public Builder async(Boolean async) {
            this.async = async;
            return this;
        }

        /**
         * Sets the async timeout in milliseconds.
         *
         * @param asyncTimeout the timeout
         * @return this builder
         */
        public Builder asyncTimeout(Integer asyncTimeout) {
            this.asyncTimeout = asyncTimeout;
            return this;
        }

        /**
         * Builds the hook output.
         *
         * @return the hook output
         */
        public HookOutput build() {
            return new HookOutput(this);
        }

    }

}
