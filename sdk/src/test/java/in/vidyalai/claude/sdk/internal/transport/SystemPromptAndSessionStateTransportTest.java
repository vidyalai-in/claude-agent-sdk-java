package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.SystemPromptCustom;

/**
 * Transport-side pieces of the Python SDK v0.2.141–v0.2.160 sync: the custom
 * system prompt form (#1268), the session-state request behind the run-end
 * fix (#1190), and the {@code verbatimPrompts} older-CLI warning (#1269).
 */
class SystemPromptAndSessionStateTransportTest {

    private static final String READS_STATE = SubprocessCLITransport.SDK_READS_SESSION_STATE_ENV;

    private static List<String> buildCommand(ClaudeAgentOptions options) {
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            return transport.buildCommand();
        } finally {
            transport.close();
        }
    }

    // --- custom system prompt ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "Be helpful", "", "--help" })
    void customSystemPromptReachesTheCliLikeAPlainString(String prompt) {
        List<String> cmd = buildCommand(ClaudeAgentOptions.builder()
                .systemPrompt(SystemPromptCustom.of(prompt, false))
                .cliPath(Path.of("/usr/bin/claude"))
                .build());

        assertThat(cmd.get(cmd.indexOf("--system-prompt") + 1)).isEqualTo(prompt);
        assertThat(cmd).doesNotContain("--append-system-prompt", "--system-prompt-snapshot");
    }

    // --- CLAUDE_CODE_SDK_READS_SESSION_STATE ---------------------------------

    private static Map<String, String> envFor(Map<String, String> optionsEnv, Map<String, String> ambient) {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().env(optionsEnv).build();
        Map<String, String> env = new HashMap<>(ambient);
        SubprocessCLITransport.applyEnvDefaults(options, env);
        return env;
    }

    @Test
    void asksForSessionStateByDefault() {
        Map<String, String> env = envFor(Map.of(), Map.of());
        assertThat(env).containsEntry(READS_STATE, "1");
        // Seeing the frames stays the caller's own opt-in.
        assertThat(env).doesNotContainKey("CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS");
    }

    @Test
    void callerOptionsEnvWins() {
        assertThat(envFor(Map.of(READS_STATE, "0"), Map.of())).containsEntry(READS_STATE, "0");
    }

    @Test
    void ambientEnvWins() {
        assertThat(envFor(Map.of(), Map.of(READS_STATE, "0"))).containsEntry(READS_STATE, "0");
    }

    @Test
    void callerChoiceInAnotherCaseWins() {
        Map<String, String> env = envFor(Map.of(READS_STATE.toLowerCase(), "0"), Map.of());
        assertThat(env).doesNotContainKey(READS_STATE);
        assertThat(env).containsEntry(READS_STATE.toLowerCase(), "0");
    }

    // --- verbatimPrompts older-CLI warning -----------------------------------

    private static final ClaudeAgentOptions VERBATIM = ClaudeAgentOptions.builder().verbatimPrompts(true).build();

    @ParameterizedTest
    @ValueSource(strings = { "2.1.247", "2.0.0" })
    void warnsWhenCliPredatesClientComposed(String version) {
        assertThat(SubprocessCLITransport.verbatimPromptsWarning(VERBATIM, version, "/usr/bin/claude"))
                .contains("verbatimPrompts is enabled")
                .contains(version)
                .contains("/usr/bin/claude")
                .contains("2.1.248");
    }

    @ParameterizedTest
    @ValueSource(strings = { "2.1.248", "2.1.283", "3.0.0" })
    void noWarningWhenCliSupportsClientComposed(String version) {
        assertThat(SubprocessCLITransport.verbatimPromptsWarning(VERBATIM, version, "/usr/bin/claude")).isNull();
    }

    @Test
    void noWarningWhenOptionIsOff() {
        assertThat(SubprocessCLITransport.verbatimPromptsWarning(
                ClaudeAgentOptions.builder().build(), "2.0.0", "/usr/bin/claude")).isNull();
    }
}
