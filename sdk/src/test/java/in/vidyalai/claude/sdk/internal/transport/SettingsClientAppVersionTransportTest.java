package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;

/**
 * Transport-side pieces of the Python SDK v0.2.169–v0.2.182 sync: settings
 * that fail closed when merged with a sandbox (#1426), the {@code clientApp}
 * option (#1432), and the CLI version read from its init message instead of a
 * separate {@code cli -v} run (#1483).
 */
class SettingsClientAppVersionTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CLI = Path.of("/usr/bin/claude");

    private static List<String> buildCommand(ClaudeAgentOptions options) {
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            return transport.buildCommand();
        } finally {
            transport.close();
        }
    }

    @Nested
    class SettingsWithSandbox {

        @TempDir
        Path tmp;

        private ClaudeAgentOptions.Builder sandboxed(String settings) {
            return ClaudeAgentOptions.builder()
                    .cliPath(CLI)
                    .settings(settings)
                    .sandbox(new SandboxSettings(true));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> mergedSettings(ClaudeAgentOptions options) throws IOException {
            List<String> cmd = buildCommand(options);
            return MAPPER.readValue(cmd.get(cmd.indexOf("--settings") + 1), Map.class);
        }

        @Test
        void relativeSettingsPathResolvesAgainstCwd() throws IOException {
            Files.writeString(tmp.resolve("settings.json"), "{\"permissions\": {\"deny\": [\"Bash\"]}}");

            Map<String, Object> merged = mergedSettings(sandboxed("settings.json").cwd(tmp).build());

            assertThat(merged).containsEntry("permissions", Map.of("deny", List.of("Bash")));
            assertThat(merged.get("sandbox")).isInstanceOfSatisfying(Map.class,
                    sandbox -> assertThat(sandbox.get("enabled")).isEqualTo(true));
        }

        @Test
        void absoluteSettingsPathIsReadAsGiven() throws IOException {
            Path file = tmp.resolve("settings.json");
            Files.writeString(file, "{\"permissions\": {\"deny\": [\"Bash\"]}}");

            Map<String, Object> merged = mergedSettings(
                    sandboxed(file.toString()).cwd(tmp.resolve("elsewhere")).build());

            assertThat(merged).containsEntry("permissions", Map.of("deny", List.of("Bash")));
        }

        @Test
        void settingsFileWithBomLoads() throws IOException {
            Files.write(tmp.resolve("settings.json"),
                    ("﻿{\"permissions\": {\"deny\": [\"Bash\"]}}").getBytes(StandardCharsets.UTF_8));

            Map<String, Object> merged = mergedSettings(sandboxed("settings.json").cwd(tmp).build());

            assertThat(merged).containsEntry("permissions", Map.of("deny", List.of("Bash")));
        }

        @SuppressWarnings("null")
        @ParameterizedTest
        @CsvSource(delimiter = '|', nullValues = "NONE", value = {
                "missing.json|NONE",
                ".|NONE",
                "settings.json|{not json",
                "settings.json|[1, 2]",
                // Malformed inline JSON
                "{\"env\": {\"KEY\": \"secret-value\",}}|NONE",
                // Trailing content: json.loads raises "Extra data"; it must not
                // be dropped silently
                "settings.json|{\"permissions\": {}} {\"hooks\": {}}",
                "{\"a\": 1} {\"env\": {\"KEY\": \"secret-value\"}}|NONE",
        })
        void unloadableSettingsThrowInsteadOfBeingDropped(String settings, String contents) throws IOException {
            if (contents != null) {
                Files.writeString(tmp.resolve(settings), contents);
            }
            ClaudeAgentOptions options = sandboxed(settings).cwd(tmp).build();

            assertThatThrownBy(() -> buildCommand(options))
                    .isInstanceOf(CLIConnectionException.class)
                    .hasMessageContaining("settings")
                    // Inline settings can hold secrets
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("secret-value"))
                    .satisfies(e -> assertThat(String.valueOf(e.getCause())).doesNotContain("secret-value"));
        }

        @Test
        void withoutSandboxTheSettingsPassThroughUnread() {
            List<String> cmd = buildCommand(ClaudeAgentOptions.builder()
                    .cliPath(CLI)
                    .settings("missing.json")
                    .build());

            assertThat(cmd.get(cmd.indexOf("--settings") + 1)).isEqualTo("missing.json");
        }
    }

    @Nested
    class ClientApp {

        private static final String EXPLICIT = SubprocessCLITransport.EXPLICIT_CLIENT_APP_ENV;
        private static final String DERIVED = SubprocessCLITransport.DERIVED_CLIENT_APP_ENV;

        private Map<String, String> envFor(ClaudeAgentOptions options, Map<String, String> ambient) {
            Map<String, String> env = new HashMap<>(ambient);
            SubprocessCLITransport.applyEnvDefaults(options, env);
            return env;
        }

        @Test
        void setsNothingByDefault() {
            Map<String, String> env = envFor(ClaudeAgentOptions.builder().build(), Map.of());
            assertThat(env).doesNotContainKeys(EXPLICIT, DERIVED);
        }

        @Test
        void clientAppSetsTheExplicitVariable() {
            Map<String, String> env = envFor(ClaudeAgentOptions.builder().clientApp("chosen/1.0").build(), Map.of());
            assertThat(env).containsEntry(EXPLICIT, "chosen/1.0").doesNotContainKey(DERIVED);
        }

        @Test
        void clientAppWinsOverOptionsEnvAndTheInheritedValue() {
            Map<String, String> env = envFor(ClaudeAgentOptions.builder()
                    .clientApp("chosen/1.0")
                    .env(Map.of(EXPLICIT, "from-env"))
                    .build(), Map.of(EXPLICIT, "from-parent"));
            assertThat(env).containsEntry(EXPLICIT, "chosen/1.0");
        }

        @Test
        void optionsEnvAndInheritedValuesStillWorkWithoutClientApp() {
            assertThat(envFor(ClaudeAgentOptions.builder().env(Map.of(EXPLICIT, "from-env")).build(), Map.of()))
                    .containsEntry(EXPLICIT, "from-env");
            assertThat(envFor(ClaudeAgentOptions.builder().build(), Map.of(EXPLICIT, "from-parent")))
                    .containsEntry(EXPLICIT, "from-parent");
        }

        @Test
        void emptyClientAppIsTheOptOut() {
            Map<String, String> env = envFor(ClaudeAgentOptions.builder().clientApp("").build(),
                    Map.of(EXPLICIT, "from-parent"));
            assertThat(env).containsEntry(EXPLICIT, "");
        }

        @Test
        void emptyEnvValueIsTheOptOut() {
            Map<String, String> env = envFor(ClaudeAgentOptions.builder().env(Map.of(EXPLICIT, "")).build(),
                    Map.of());
            assertThat(env).containsEntry(EXPLICIT, "").doesNotContainKey(DERIVED);
        }

        @Test
        void optOutAlsoRemovesAnInheritedDerivedLabel() {
            Map<String, String> env = envFor(ClaudeAgentOptions.builder().clientApp("").build(),
                    Map.of(DERIVED, "outer-app"));
            assertThat(env).containsEntry(EXPLICIT, "").doesNotContainKey(DERIVED);
        }

        @Test
        void derivedLabelFromAnOuterSessionIsDropped() {
            // Inherited, or set through options.env: either way it names some
            // other session's app, and it is not overridable.
            assertThat(envFor(ClaudeAgentOptions.builder().build(), Map.of(DERIVED, "outer-app")))
                    .doesNotContainKey(DERIVED);
            assertThat(envFor(ClaudeAgentOptions.builder().env(Map.of(DERIVED, "x")).build(), Map.of()))
                    .doesNotContainKey(DERIVED);
        }

        @Test
        void toBuilderKeepsClientApp() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder().clientApp("chosen/1.0").build();
            assertThat(options.toBuilder().build().clientApp()).isEqualTo("chosen/1.0");
        }
    }

    @Nested
    class VersionFromInitMessage {

        private List<String> warningsFor(ClaudeAgentOptions options, List<Map<String, Object>> frames) {
            Assumptions.assumeTrue(System.getenv("CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK") == null,
                    "the version check is disabled in this environment");
            List<String> warnings = new CopyOnWriteArrayList<>();
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        warnings.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            Logger logger = Logger.getLogger(SubprocessCLITransport.class.getName());
            logger.addHandler(capture);
            SubprocessCLITransport transport = new SubprocessCLITransport(options);
            try {
                frames.forEach(transport::checkClaudeVersion);
            } finally {
                transport.close();
                logger.removeHandler(capture);
            }
            return List.copyOf(warnings);
        }

        private Map<String, Object> init(String version) {
            Map<String, Object> frame = new HashMap<>(Map.of("type", "system", "subtype", "init"));
            if (version != null) {
                frame.put("claude_code_version", version);
            }
            return frame;
        }

        @Test
        void cliTooOldToReportItsVersionWarnsOnce() {
            // The CLI sends an init message per turn.
            List<String> warnings = warningsFor(ClaudeAgentOptions.builder().cliPath(CLI).build(),
                    List.of(init(null), init(null)));
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).contains(CLI.toString(), "2.0.0");
        }

        @ParameterizedTest
        @ValueSource(strings = { "2.0.20", "99.99.99" })
        void supportedCliVersionDoesNotWarn(String version) {
            assertThat(warningsFor(ClaudeAgentOptions.builder().cliPath(CLI).build(), List.of(init(version))))
                    .isEmpty();
        }

        @Test
        void onlyTheFirstInitMessageIsChecked() {
            List<Map<String, Object>> frames = List.of(
                    Map.of("type", "assistant"),
                    Map.of("type", "system", "subtype", "status"),
                    init("2.1.300"),
                    init(null));
            assertThat(warningsFor(ClaudeAgentOptions.builder().cliPath(CLI).build(), frames)).isEmpty();
        }

        @Test
        void verbatimPromptsWarnsWhenTheCliReportsNoVersion() {
            List<String> warnings = warningsFor(
                    ClaudeAgentOptions.builder().cliPath(CLI).verbatimPrompts(true).build(), List.of(init(null)));
            assertThat(warnings).hasSize(2);
            assertThat(warnings).anyMatch(w -> w.contains("verbatimPrompts") && w.contains("unknown"));
        }

        @ParameterizedTest
        @CsvSource(nullValues = "NONE", value = {
                "2.1.300,2.1.300",
                "'2.1.300 (Claude Code)',2.1.300",
                "garbage,NONE",
                "'',NONE",
        })
        void reportedVersionIsTheDottedPrefix(String raw, String expected) {
            assertThat(SubprocessCLITransport.reportedCliVersion(init(raw))).isEqualTo(expected);
        }
    }
}
