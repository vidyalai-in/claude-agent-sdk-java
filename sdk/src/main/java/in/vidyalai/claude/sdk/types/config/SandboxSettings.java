package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sandbox settings configuration
 *
 * This controls how Claude Code sandboxes bash commands for filesystem
 * and network isolation.
 * 
 * <p>
 * Note: Filesystem and network restrictions are configured from permission
 * rules (Read/Edit/WebFetch), not from these sandbox settings.
 * - Filesystem read restrictions: Use Read deny rules
 * - Filesystem write restrictions: Use Edit allow/deny rules
 * - Network restrictions: Use WebFetch allow/deny rules
 *
 * @param enabled                   enable bash sandboxing (macOS/Linux only).
 *                                  Default: False
 * @param autoAllowBashIfSandboxed  auto-approve bash commands when sandboxed.
 *                                  Default: True
 * @param excludedCommands          commands that should run outside the sandbox
 *                                  (e.g., ["git", "docker"])
 * @param allowUnsandboxedCommands  allow commands to bypass sandbox via
 *                                  dangerouslyDisableSandbox. When False, all
 *                                  commands must run sandboxed (or be in
 *                                  excludedCommands). Default: True
 * @param network                   network configuration for sandbox
 * @param ignoreViolations          violations to ignore
 * @param enableWeakerNestedSandbox enable weaker sandbox for unprivileged
 *                                  Docker environments (Linux only). Reduces
 *                                  security. Default: False
 */
public record SandboxSettings(
        @JsonProperty("enabled") @Nullable Boolean enabled,
        @JsonProperty("autoAllowBashIfSandboxed") @Nullable Boolean autoAllowBashIfSandboxed,
        @JsonProperty("excludedCommands") @Nullable List<String> excludedCommands,
        @JsonProperty("allowUnsandboxedCommands") @Nullable Boolean allowUnsandboxedCommands,
        @JsonProperty("network") @Nullable SandboxNetworkConfig network,
        @JsonProperty("ignoreViolations") @Nullable SandboxIgnoreViolations ignoreViolations,
        @JsonProperty("enableWeakerNestedSandbox") @Nullable Boolean enableWeakerNestedSandbox) {

    /**
     * Creates sandbox settings with just enabled flag.
     * All other fields are left as null to match Python SDK's TypedDict behavior.
     */
    public SandboxSettings(boolean enabled) {
        this(enabled, null, null, null, null, null, null);
    }

    /**
     * Creates default sandbox settings (all fields null).
     * To enable sandboxing, use {@code new SandboxSettings(true)}.
     */
    public SandboxSettings() {
        this(null, null, null, null, null, null, null);
    }

}
