package in.vidyalai.claude.sdk.internal.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A stand-in for the {@code claude} CLI that reports session state, launched
 * as a real subprocess by {@link RunEndSubprocessTest} (the Java port of the
 * Python SDK's {@code tests/test_run_end_subprocess.py}).
 *
 * <p>
 * It speaks the CLI side of the contract: with
 * {@code CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS} set it sends
 * {@code session_state_changed} frames as they are, otherwise with
 * {@code CLAUDE_CODE_SDK_READS_SESSION_STATE} set it marks them
 * {@code sdk_host_only}, otherwise it sends none. After its first result a
 * background agent "finishes" and wakes a follow-up turn whose hook needs
 * stdin; the stand-in only finishes that turn once the hook's answer arrives
 * on stdin, so the second result proves stdin was still open.
 *
 * <p>
 * {@code -v} prints the version given by the {@code fake.cli.version} system
 * property. {@code FAKE_CLI_SCENARIO=stuck} leaves the background agent
 * running forever, so the state stays "running" until stdin closes.
 */
public final class FakeSessionStateCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SDK_READS = "CLAUDE_CODE_SDK_READS_SESSION_STATE";
    private static final String EMIT = "CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS";

    private FakeSessionStateCli() {
    }

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("-v".equals(arg) || "--version".equals(arg)) {
                System.out.println(System.getProperty("fake.cli.version", "2.1.999") + " (Claude Code)");
                return;
            }
        }

        String scenario = System.getenv().getOrDefault("FAKE_CLI_SCENARIO", "wake");
        Map<String, Object> fakeEnv = new LinkedHashMap<>();
        fakeEnv.put(SDK_READS, System.getenv(SDK_READS));
        fakeEnv.put(EMIT, System.getenv(EMIT));
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("type", "system");
        init.put("subtype", "init");
        init.put("session_id", "s");
        init.put("model", "m");
        init.put("cwd", ".");
        init.put("tools", List.of());
        init.put("mcp_servers", List.of());
        init.put("permissionMode", "default");
        init.put("apiKeySource", "none");
        init.put("fake_env", fakeEnv);
        emit(init);

        String hookId = null;
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = MAPPER.readValue(line, Map.class);
            Object kind = msg.get("type");
            if ("control_request".equals(kind)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = (Map<String, Object>) msg.get("request");
                if ("initialize".equals(request.get("subtype")) && request.get("hooks") instanceof Map<?, ?> hooks) {
                    for (Object matchers : hooks.values()) {
                        for (Object matcher : (List<?>) matchers) {
                            Object ids = ((Map<?, ?>) matcher).get("hookCallbackIds");
                            if (hookId == null) {
                                hookId = (String) ((List<?>) ids).get(0);
                            }
                        }
                    }
                }
                emit(Map.of("type", "control_response",
                        "response", Map.of("subtype", "success",
                                "request_id", msg.get("request_id"),
                                "response", Map.of())));
            } else if ("user".equals(kind)) {
                state("running");
                assistant("LAUNCHED");
                result("LAUNCHED");
                if ("wake".equals(scenario)) {
                    // The background agent finished just before that result;
                    // its completion wakes a turn whose hook needs an answer
                    // on stdin.
                    assistant("writing");
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("hook_event_name", "PreToolUse");
                    input.put("session_id", "s");
                    input.put("transcript_path", "/tmp/t.jsonl");
                    input.put("cwd", ".");
                    input.put("tool_name", "Write");
                    input.put("tool_input", Map.of());
                    input.put("tool_use_id", "toolu_1");
                    Map<String, Object> hookRequest = new LinkedHashMap<>();
                    hookRequest.put("subtype", "hook_callback");
                    hookRequest.put("callback_id", hookId);
                    hookRequest.put("input", input);
                    hookRequest.put("tool_use_id", "toolu_1");
                    emit(Map.of("type", "control_request", "request_id", "hook-1", "request", hookRequest));
                }
                // "stuck": the background agent never finishes, so the state
                // stays "running" until stdin closes.
            } else if ("control_response".equals(kind)
                    && msg.get("response") instanceof Map<?, ?> response
                    && "hook-1".equals(response.get("request_id"))) {
                assistant("FINISHED");
                result("FINISHED");
                state("idle");
            }
        }
    }

    private static boolean truthy(String name) {
        String value = System.getenv(name);
        return value != null && List.of("1", "true", "yes", "on").contains(value.strip().toLowerCase());
    }

    private static void state(String value) throws Exception {
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "system");
        frame.put("subtype", "session_state_changed");
        frame.put("state", value);
        frame.put("uuid", "u-" + value);
        frame.put("session_id", "s");
        if (truthy(EMIT)) {
            emit(frame);
        } else if (truthy(SDK_READS)) {
            frame.put("sdk_host_only", true);
            emit(frame);
        }
    }

    private static void assistant(String text) throws Exception {
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "assistant");
        frame.put("parent_tool_use_id", null);
        frame.put("session_id", "s");
        frame.put("message", Map.of("role", "assistant", "model", "m",
                "content", List.of(Map.of("type", "text", "text", text))));
        emit(frame);
    }

    private static void result(String text) throws Exception {
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "result");
        frame.put("subtype", "success");
        frame.put("duration_ms", 1);
        frame.put("duration_api_ms", 1);
        frame.put("is_error", false);
        frame.put("num_turns", 1);
        frame.put("session_id", "s");
        frame.put("result", text);
        emit(frame);
    }

    private static synchronized void emit(Map<String, Object> frame) throws Exception {
        System.out.println(MAPPER.writeValueAsString(frame));
        System.out.flush();
    }
}
