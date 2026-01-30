package in.vidyalai.claude.sdk.types.hook;

import in.vidyalai.claude.sdk.types.permission.PermissionDecision;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Hook-specific output for different hook event types.
 *
 * <p>
 * Each hook event type has different fields available:
 * <ul>
 * <li>PreToolUse: permissionDecision, permissionDecisionReason,
 * updatedInput</li>
 * <li>PostToolUse: additionalContext</li>
 * <li>PostToolUseFailure: additionalContext</li>
 * <li>UserPromptSubmit: additionalContext</li>
 * </ul>
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names in its {@link #toMap()} method because it represents data <b>sent
 * to the CLI</b> in hook responses. See {@link in.vidyalai.claude.sdk.types}
 * package documentation for details.
 */
public final class HookSpecificOutput {

    // JSON serialization field names
    private static final String HOOK_EVENT_NAME = "hookEventName";
    private static final String PERMISSION_DECISION = "permissionDecision";
    private static final String PERMISSION_DECISION_REASON = "permissionDecisionReason";
    private static final String UPDATED_INPUT = "updatedInput";
    private static final String ADDITIONAL_CONTEXT = "additionalContext";

    private final String hookEventName;

    // PreToolUse specific
    @Nullable
    private final PermissionDecision permissionDecision;
    @Nullable
    private final String permissionDecisionReason;
    @Nullable
    private final Map<String, Object> updatedInput;

    // PostToolUse/UserPromptSubmit specific
    @Nullable
    private final String additionalContext;

    private HookSpecificOutput(Builder builder) {
        this.hookEventName = builder.hookEventName;
        this.permissionDecision = builder.permissionDecision;
        this.permissionDecisionReason = builder.permissionDecisionReason;
        this.updatedInput = builder.updatedInput;
        this.additionalContext = builder.additionalContext;
    }

    /**
     * Creates a builder for PreToolUse hook output.
     */
    public static Builder preToolUse() {
        return new Builder(HookEvent.PRE_TOOL_USE);
    }

    /**
     * Creates a builder for PostToolUse hook output.
     */
    public static Builder postToolUse() {
        return new Builder(HookEvent.POST_TOOL_USE);
    }

    /**
     * Creates a builder for PostToolUseFailure hook output.
     */
    public static Builder postToolUseFailure() {
        return new Builder(HookEvent.POST_TOOL_USE_FAILURE);
    }

    /**
     * Creates a builder for UserPromptSubmit hook output.
     */
    public static Builder userPromptSubmit() {
        return new Builder(HookEvent.USER_PROMPT_SUBMIT);
    }

    /**
     * Converts this output to a Map for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(HOOK_EVENT_NAME, hookEventName);

        if (permissionDecision != null) {
            result.put(PERMISSION_DECISION, permissionDecision.getValue());
        }
        if (permissionDecisionReason != null) {
            result.put(PERMISSION_DECISION_REASON, permissionDecisionReason);
        }
        if (updatedInput != null) {
            result.put(UPDATED_INPUT, updatedInput);
        }
        if (additionalContext != null) {
            result.put(ADDITIONAL_CONTEXT, additionalContext);
        }

        return result;
    }

    // Getters
    public String hookEventName() {
        return hookEventName;
    }

    @Nullable
    public PermissionDecision permissionDecision() {
        return permissionDecision;
    }

    @Nullable
    public String permissionDecisionReason() {
        return permissionDecisionReason;
    }

    @Nullable
    public Map<String, Object> updatedInput() {
        return updatedInput;
    }

    @Nullable
    public String additionalContext() {
        return additionalContext;
    }

    /**
     * Builder for HookSpecificOutput.
     */
    public static final class Builder {

        private final String hookEventName;
        @Nullable
        private PermissionDecision permissionDecision;
        @Nullable
        private String permissionDecisionReason;
        @Nullable
        private Map<String, Object> updatedInput;
        @Nullable
        private String additionalContext;

        private Builder(HookEvent hookEvent) {
            this.hookEventName = hookEvent.getValue();
        }

        /**
         * Sets the permission decision (PreToolUse only).
         *
         * @param decision "allow", "deny", or "ask"
         */
        public Builder permissionDecision(PermissionDecision decision) {
            this.permissionDecision = decision;
            return this;
        }

        /**
         * Sets the reason for the permission decision (PreToolUse only).
         */
        public Builder permissionDecisionReason(String reason) {
            this.permissionDecisionReason = reason;
            return this;
        }

        /**
         * Sets updated tool input (PreToolUse only).
         */
        public Builder updatedInput(Map<String, Object> input) {
            this.updatedInput = input;
            return this;
        }

        /**
         * Sets additional context to provide to Claude.
         */
        public Builder additionalContext(String context) {
            this.additionalContext = context;
            return this;
        }

        public HookSpecificOutput build() {
            return new HookSpecificOutput(this);
        }

    }

}
