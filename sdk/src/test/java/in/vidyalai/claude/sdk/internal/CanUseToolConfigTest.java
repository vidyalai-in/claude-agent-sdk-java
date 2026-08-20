package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * Shared {@code canUseTool} validation for {@code query()} and
 * {@code ClaudeSDKClient.connect()} (Python SDK #1204).
 *
 * <p>
 * The callback used to be rejected outright for string prompts. It no longer
 * is: a string prompt is streamed over stdin internally, so the control
 * protocol — and therefore the callback — is available for it too.
 */
class CanUseToolConfigTest {

    private static ClaudeAgentOptions.Builder withCallback() {
        return ClaudeAgentOptions.builder()
                .canUseTool((toolName, input, context) ->
                        CompletableFuture.completedFuture(new PermissionResultAllow()));
    }

    @Test
    void callbackRoutesPermissionPromptsOverStdio() {
        ClaudeAgentOptions configured =
                CanUseToolConfig.configureCanUseTool(withCallback().build());

        assertThat(configured.permissionPromptToolName()).isEqualTo("stdio");
        assertThat(configured.canUseTool()).isNotNull();
    }

    @Test
    void noCallback_leavesOptionsUntouched() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();

        assertThat(CanUseToolConfig.configureCanUseTool(options)).isSameAs(options);
        assertThat(options.permissionPromptToolName()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void callbackWithPermissionPromptToolName_isRejected() {
        ClaudeAgentOptions options = withCallback()
                .permissionPromptToolName("mcp__auth__prompt")
                .build();

        assertThatThrownBy(() -> CanUseToolConfig.configureCanUseTool(options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be used with permissionPromptToolName");
    }
}
