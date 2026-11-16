package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.internal.SessionResume.AuthEnvironment;
import in.vidyalai.claude.sdk.internal.SessionResume.CommandResult;
import in.vidyalai.claude.sdk.internal.SessionResume.MaterializedResume;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;
import in.vidyalai.claude.sdk.types.session.SessionKey;
import in.vidyalai.claude.sdk.types.session.SessionStoreEntry;

/**
 * The macOS Keychain credentials fallback on resume (Python
 * {@code _read_keychain_credentials}), run on any platform through a simulated
 * host.
 */
class SessionResumeKeychainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEYCHAIN_JSON =
            "{\"claudeAiOauth\":{\"accessToken\":\"kc\",\"refreshToken\":\"SECRET\"}}";

    /** A scripted host: macOS or not, a fixed environment, a canned {@code security} result. */
    private static final class FakeHost implements AuthEnvironment {
        final Map<String, String> env = new HashMap<>();
        final List<List<String>> commands = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        final Path home;
        boolean macOs = true;
        @Nullable
        CommandResult result = new CommandResult(0, KEYCHAIN_JSON + "\n");

        FakeHost(Path home) {
            this.home = home;
        }

        @Override
        public @Nullable String getenv(String name) {
            return env.get(name);
        }

        @Override
        public Path homeDir() {
            return home;
        }

        @Override
        public boolean isMacOs() {
            return macOs;
        }

        @Override
        public @Nullable CommandResult run(List<String> command, long timeoutMs) {
            commands.add(command);
            timeouts.add(timeoutMs);
            return result;
        }
    }

    @TempDir
    Path home;

    private FakeHost host;
    private AuthEnvironment previous;

    @BeforeEach
    void installHost() {
        host = new FakeHost(home);
        previous = SessionResume.setAuthEnvironment(host);
    }

    @AfterEach
    void restoreHost() {
        SessionResume.setAuthEnvironment(previous);
    }

    private static MaterializedResume materialize(Map<String, String> optEnv) throws IOException {
        InMemorySessionStore store = new InMemorySessionStore();
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "user");
        entry.put("uuid", "u1");
        store.append(new SessionKey(SessionStores.projectKeyForDirectory(null), sessionId, null),
                List.of(SessionStoreEntry.of(entry)));
        MaterializedResume m = SessionResume.materializeResumeSession(ClaudeAgentOptions.builder()
                .sessionStore(store)
                .resume(sessionId)
                .env(optEnv)
                .build());
        assertThat(m).isNotNull();
        return m;
    }

    @SuppressWarnings({ "unchecked", "null" })
    private static Map<String, Object> oauth(MaterializedResume m) throws IOException {
        Map<String, Object> creds = MAPPER.readValue(
                Files.readString(m.configDir().resolve(".credentials.json"), StandardCharsets.UTF_8),
                Map.class);
        return (Map<String, Object>) creds.get("claudeAiOauth");
    }

    @Test
    void credentialsFromKeychainFallback() throws IOException {
        // Python test_credentials_from_keychain_fallback: no file and no env
        // credentials, so the Keychain supplies them, refresh token redacted.
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(oauth(m)).containsEntry("accessToken", "kc").doesNotContainKey("refreshToken");
        } finally {
            m.cleanup();
        }
    }

    @Test
    void securityIsInvokedLikePython() throws IOException {
        host.env.put("USER", "alice");
        MaterializedResume m = materialize(Map.of());
        m.cleanup();
        assertThat(host.commands).containsExactly(List.of(
                "security", "find-generic-password", "-a", "alice", "-w", "-s",
                "Claude Code-credentials"));
        assertThat(host.timeouts).containsExactly(5_000L);
    }

    @Test
    void keychainWinsOverCredentialsFile() throws IOException {
        // Python overwrites creds_json with the Keychain value whenever it
        // finds one: on macOS the file is only the CLI's plain-text fallback.
        Path config = Files.createDirectories(home.resolve(".claude"));
        Files.writeString(config.resolve(".credentials.json"),
                "{\"claudeAiOauth\":{\"accessToken\":\"file\"}}");
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(oauth(m)).containsEntry("accessToken", "kc");
        } finally {
            m.cleanup();
        }
    }

    @Test
    void failedLookupFallsBackToTheFile() throws IOException {
        Path config = Files.createDirectories(home.resolve(".claude"));
        Files.writeString(config.resolve(".credentials.json"),
                "{\"claudeAiOauth\":{\"accessToken\":\"file\",\"refreshToken\":\"r\"}}");
        host.result = new CommandResult(44, "");
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(oauth(m)).containsEntry("accessToken", "file").doesNotContainKey("refreshToken");
        } finally {
            m.cleanup();
        }
    }

    @Test
    void nonZeroExitWritesNoCredentials() throws IOException {
        // `security` exits 44 when the item is not found.
        host.result = new CommandResult(44, "security: SecKeychainSearchCopyNext: not found");
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(m.configDir().resolve(".credentials.json")).doesNotExist();
        } finally {
            m.cleanup();
        }
    }

    @Test
    void timeoutOrSpawnFailureWritesNoCredentials() throws IOException {
        host.result = null;
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(m.configDir().resolve(".credentials.json")).doesNotExist();
        } finally {
            m.cleanup();
        }
    }

    @Test
    void blankOutputWritesNoCredentials() throws IOException {
        host.result = new CommandResult(0, "  \n");
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(m.configDir().resolve(".credentials.json")).doesNotExist();
        } finally {
            m.cleanup();
        }
    }

    @Test
    void notConsultedOffMacOs() throws IOException {
        host.macOs = false;
        MaterializedResume m = materialize(Map.of());
        try {
            assertThat(host.commands).isEmpty();
            assertThat(m.configDir().resolve(".credentials.json")).doesNotExist();
        } finally {
            m.cleanup();
        }
    }

    @Test
    void notConsultedWhenAnApiKeyIsInTheOptionsEnv() throws IOException {
        materialize(Map.of("ANTHROPIC_API_KEY", "sk-test")).cleanup();
        assertThat(host.commands).isEmpty();
    }

    @Test
    void notConsultedWhenAnOauthTokenIsInTheProcessEnv() throws IOException {
        host.env.put("CLAUDE_CODE_OAUTH_TOKEN", "tok");
        materialize(Map.of()).cleanup();
        assertThat(host.commands).isEmpty();
    }

    @Test
    void notConsultedWithACustomConfigDir(@TempDir Path configDir) throws IOException {
        materialize(Map.of("CLAUDE_CONFIG_DIR", configDir.toString())).cleanup();
        host.env.put("CLAUDE_CONFIG_DIR", configDir.toString());
        materialize(Map.of()).cleanup();
        assertThat(host.commands).isEmpty();
    }

    @Test
    void emptyEnvValuesCountAsUnset() throws IOException {
        // Python: `opt_env.get(...) or os.environ.get(...)`.
        materialize(Map.of("ANTHROPIC_API_KEY", "", "CLAUDE_CONFIG_DIR", "")).cleanup();
        assertThat(host.commands).hasSize(1);
    }

    @SuppressWarnings("null")
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void systemRunnerCapturesStdoutAndExitCode() {
        CommandResult r = SessionResume.SYSTEM_AUTH_ENVIRONMENT.run(
                List.of("sh", "-c", "echo out; echo err >&2; exit 3"), 10_000L);
        assertThat(r).isNotNull();
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stdout()).isEqualTo("out\n");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void systemRunnerGivesUpAtTheTimeout() {
        long start = System.nanoTime();
        CommandResult r = SessionResume.SYSTEM_AUTH_ENVIRONMENT.run(List.of("sleep", "30"), 300L);
        assertThat(r).isNull();
        assertThat(System.nanoTime() - start).isLessThan(10_000_000_000L);
    }

    @Test
    void systemRunnerReturnsNullForAMissingCommand() {
        assertThat(SessionResume.SYSTEM_AUTH_ENVIRONMENT.run(
                List.of("definitely-not-a-command-" + UUID.randomUUID()), 1_000L)).isNull();
    }
}
