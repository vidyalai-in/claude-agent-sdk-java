package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.input.PreToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * {@link ClaudeSDK#query} against a stand-in CLI that reports session state
 * (Python SDK #1279, issue #1190; port of {@code tests/test_run_end_subprocess.py}).
 *
 * <p>
 * Unlike {@code QueryHandlerRunEndTest}, nothing here is mocked on the SDK
 * side: the real {@link SubprocessCLITransport} spawns {@link FakeSessionStateCli},
 * sets {@code CLAUDE_CODE_SDK_READS_SESSION_STATE} on it, and the facade reads
 * the ceiling from {@code options.env}. These tests do not run if the parent
 * environment already names either session-state variable, since the stand-in
 * would then see the parent's choice.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "spawns a shell script")
class RunEndSubprocessTest {

    private static final String SDK_READS = "CLAUDE_CODE_SDK_READS_SESSION_STATE";
    private static final String EMIT = "CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS";

    @TempDir
    Path tmp;

    /** Writes an executable script that runs {@link FakeSessionStateCli} on this JVM's classpath. */
    private Path writeFakeCli(String version) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String script = "#!/bin/sh\n"
                + "exec '" + java + "' -Dfake.cli.version=" + version
                + " -cp '" + System.getProperty("java.class.path") + "' "
                + FakeSessionStateCli.class.getName() + " \"$@\"\n";
        Path path = tmp.resolve("fake_claude");
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        return path;
    }

    private record Run(List<Message> messages, List<String> asked) {
    }

    private Run run(Map<String, String> env) throws IOException {
        return run(env, false);
    }

    private Run run(Map<String, String> env, boolean streamed) throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        HookMatcher.HookCallback hook = (input, context) -> {
            asked.add(((PreToolUseHookInput) input).toolName());
            return CompletableFuture.completedFuture(HookOutput.empty());
        };
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(writeFakeCli("2.1.999"))
                .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher(null, List.of(hook)))))
                .env(env)
                .build();
        List<Message> messages = streamed
                ? ClaudeSDK.query(List.<Map<String, Object>>of(Map.of(
                        "type", "user",
                        "session_id", "",
                        "message", Map.of("role", "user", "content", "go"))).iterator(), options)
                : ClaudeSDK.query("go", options);
        return new Run(messages, asked);
    }

    private static List<String> results(List<Message> messages) {
        List<String> results = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof ResultMessage r) {
                results.add(r.result());
            }
        }
        return results;
    }

    private static List<String> states(List<Message> messages) {
        List<String> states = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof SystemMessage sm && "session_state_changed".equals(sm.subtype())) {
                states.add(String.valueOf(sm.data().get("state")));
            }
        }
        return states;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fakeEnv(List<Message> messages) {
        for (Message m : messages) {
            if (m instanceof SystemMessage sm && "init".equals(sm.subtype())) {
                return (Map<String, Object>) sm.data().get("fake_env");
            }
        }
        throw new AssertionError("no init message");
    }

    private static boolean parentNamesSessionStateVars() {
        return System.getenv().keySet().stream()
                .anyMatch(k -> k.equalsIgnoreCase(SDK_READS) || k.equalsIgnoreCase(EMIT));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void followUpTurnIsServed_andSdkFramesStayHidden() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(parentNamesSessionStateVars());

        Run run = run(Map.of());

        Map<String, Object> env = fakeEnv(run.messages());
        assertThat(env.get(SDK_READS)).isEqualTo("1");
        assertThat(env.get(EMIT)).isNull();
        assertThat(results(run.messages())).containsExactly("LAUNCHED", "FINISHED");
        assertThat(run.asked()).containsExactly("Write");
        assertThat(states(run.messages())).isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void streamedPromptAlsoWaitsForIdle() throws Exception {
        // The iterator form of ClaudeSDK.query takes the same streamInput
        // path as the string form; check the facade wires it the same way.
        org.junit.jupiter.api.Assumptions.assumeFalse(parentNamesSessionStateVars());

        Run run = run(Map.of(), true);

        assertThat(results(run.messages())).containsExactly("LAUNCHED", "FINISHED");
        assertThat(run.asked()).containsExactly("Write");
        assertThat(states(run.messages())).isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void callerWhoOptedInSeesTheFrames() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(parentNamesSessionStateVars());

        Run run = run(Map.of(EMIT, "1"));

        assertThat(results(run.messages())).containsExactly("LAUNCHED", "FINISHED");
        assertThat(run.asked()).containsExactly("Write");
        assertThat(states(run.messages())).containsExactly("running", "idle");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void ceilingClosesStdinWhenIdleNeverComes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(parentNamesSessionStateVars());
        long started = System.nanoTime();

        Run run = run(Map.of(
                "FAKE_CLI_SCENARIO", "stuck",
                "CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS", "300"));

        assertThat(results(run.messages())).containsExactly("LAUNCHED");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(300);
    }

    // --- verbatimPrompts older-CLI warning, through the real version check ---

    private List<String> warningsConnectingTo(String version, boolean verbatimPrompts) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK") == null,
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
        SubprocessCLITransport transport = new SubprocessCLITransport(ClaudeAgentOptions.builder()
                .cliPath(writeFakeCli(version))
                .verbatimPrompts(verbatimPrompts)
                .build());
        try {
            transport.connect();
        } finally {
            transport.close();
            logger.removeHandler(capture);
        }
        return warnings.stream().filter(w -> w.contains("verbatimPrompts")).toList();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void warnsWhenCliPredatesClientComposed() throws Exception {
        assertThat(warningsConnectingTo("2.1.247", true))
                .singleElement()
                .satisfies(w -> assertThat(w).contains("2.1.247").contains("2.1.248"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void noWarningWhenCliSupportsClientComposed() throws Exception {
        assertThat(warningsConnectingTo("2.1.248", true)).isEmpty();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void noWarningWhenOptionIsOff() throws Exception {
        assertThat(warningsConnectingTo("2.1.100", false)).isEmpty();
    }
}
