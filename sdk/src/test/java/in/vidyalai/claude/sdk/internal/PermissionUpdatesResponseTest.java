package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions.CanUseTool;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.hook.output.PermissionRequestHookSpecificOutput;
import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;

/**
 * The {@code updatedPermissions} of a {@code canUseTool} allow go out in the
 * shape Claude Code accepts, all together or not at all (Python SDK #1502,
 * {@code tests/test_tool_callbacks.py}).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings({ "null", "unchecked" })
class PermissionUpdatesResponseTest {

    private static final long WAIT_SECONDS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<QueryHandler> handlers = new ArrayList<>();
    private final List<LogRecord> warnings = new ArrayList<>();
    private final Logger logger = Logger.getLogger(QueryHandler.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                synchronized (warnings) {
                    warnings.add(record);
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void setUp() {
        logger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capture);
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private Map<String, Object> answer(CanUseTool canUseTool, String toolName, Map<String, Object> input,
            List<Map<String, Object>> suggestions) throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = new QueryHandler(transport, true, canUseTool,
                null, null, null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "can_use_tool");
        request.put("tool_name", toolName);
        request.put("input", input);
        request.put("permission_suggestions", suggestions);
        transport.push(Map.of("type", "control_request", "request_id", "perm_1", "request", request));

        awaitUntil("the answer", WAIT_SECONDS, () -> !transport.responsesTo("perm_1").isEmpty());
        List<Map<String, Object>> responses = transport.responsesTo("perm_1");
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0)).containsEntry("subtype", "success");
        return (Map<String, Object>) responses.get(0).get("response");
    }

    private static Map<String, Object> wire(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void echoesAWholeToolSuggestionWithoutRuleContent() throws Exception {
        // A suggestion without ruleContent goes back without it, not as null:
        // Claude Code rejects a null ruleContent and then ignores every update.
        List<Map<String, Object>> suggestions = List.of(
                wire("type", "addRules", "destination", "localSettings", "behavior", "allow",
                        "rules", List.of(Map.of("toolName", "WebSearch"))),
                wire("type", "addRules", "destination", "session", "behavior", "allow",
                        "rules", List.of(Map.of("toolName", "Bash", "ruleContent", "git status"))),
                wire("type", "setMode", "mode", "acceptEdits", "destination", "session"));

        Map<String, Object> response = answer((tool, input, context) -> CompletableFuture
                .completedFuture(new PermissionResultAllow(null, context.suggestions())),
                "WebSearch", Map.of("query", "weather"), suggestions);

        assertThat(response).containsEntry("behavior", "allow");
        assertThat(response.get("updatedPermissions")).isEqualTo(suggestions);
    }

    @Test
    @SuppressWarnings("removal")
    void sendsNoUpdatesIfOneIsMalformed() throws Exception {
        // The malformed one may be a restriction the others depend on.
        CanUseTool allowWithUpdates = (tool, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultAllow(null, List.of(
                        PermissionUpdate.addRules(List.of(new PermissionRuleValue("Read")),
                                PermissionBehavior.ALLOW, PermissionUpdateDestination.SESSION),
                        PermissionUpdate.addRules(List.of(new PermissionRuleValue("Bash", "ls")), null, null),
                        PermissionUpdate.setMode(PermissionMode.ACCEPT_EDITS, null))));

        Map<String, Object> response = answer(allowWithUpdates, "Read",
                Map.of("file_path", "/tmp/x"), List.of());

        assertThat(response).containsEntry("behavior", "allow");
        assertThat(response).containsEntry("updatedInput", Map.of("file_path", "/tmp/x"));
        assertThat(response).doesNotContainKey("updatedPermissions");
        List<String> messages;
        synchronized (warnings) {
            messages = warnings.stream().map(LogRecord::getMessage).toList();
        }
        assertThat(messages).anySatisfy(m -> assertThat(m)
                .contains("updatedPermissions[1] cannot be sent")
                .contains("has no behavior"));
    }

    @Test
    @SuppressWarnings("removal")
    void theDeprecatedRemoveRulesFactorySendsNoUpdates() throws Exception {
        CanUseTool allow = (tool, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultAllow(null, List.of(PermissionUpdate.removeRules(
                        List.of(new PermissionRuleValue("Bash")), PermissionUpdateDestination.SESSION))));

        Map<String, Object> response = answer(allow, "Bash", Map.of("command", "ls"), List.of());

        assertThat(response).containsEntry("behavior", "allow");
        assertThat(response).doesNotContainKey("updatedPermissions");
    }

    @Test
    void validUpdatesWithoutDestinationGoOutAsSession() throws Exception {
        CanUseTool allow = (tool, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultAllow(null, List.of(
                        PermissionUpdate.addRules(List.of(new PermissionRuleValue("Bash", "npm test")),
                                PermissionBehavior.ALLOW, null),
                        PermissionUpdate.removeRules(List.of(new PermissionRuleValue("Bash", "npm test")),
                                PermissionBehavior.ALLOW, null))));

        Map<String, Object> response = answer(allow, "Bash", Map.of("command", "npm test"), List.of());

        assertThat(response.get("updatedPermissions")).isEqualTo(List.of(
                Map.of("type", "addRules", "destination", "session", "behavior", "allow",
                        "rules", List.of(Map.of("toolName", "Bash", "ruleContent", "npm test"))),
                Map.of("type", "removeRules", "destination", "session", "behavior", "allow",
                        "rules", List.of(Map.of("toolName", "Bash", "ruleContent", "npm test")))));
    }


    /**
     * A hook output holding a PermissionUpdate that cannot be sent is answered
     * with an error frame. Java-only: Python's hook output is a plain dict.
     * Writing nothing would leave the CLI waiting for its hook timeout.
     */
    private Map<String, Object> hookAnswer(Map<String, Object> decision) throws Exception {
        HookMatcher.HookCallback hook = (input, context) -> CompletableFuture.completedFuture(
                HookOutput.builder().hookSpecificOutput(new PermissionRequestHookSpecificOutput(decision)).build());
        HookOnFailureTest.ScriptedTransport transport = new HookOnFailureTest.ScriptedTransport(Map.of());
        QueryHandler handler = new QueryHandler(transport, true, null,
                Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher(List.of(hook)))), Duration.ofSeconds(10));
        handlers.add(handler);
        transport.connect();
        handler.start();
        handler.initialize();

        Map<String, Object> init = MAPPER.readValue(transport.writes().get(0), Map.class);
        Map<String, Object> hooks = (Map<String, Object>) ((Map<String, Object>) init.get("request")).get("hooks");
        String callbackId = ((List<String>) ((List<Map<String, Object>>) hooks.get("PreToolUse")).get(0)
                .get("hookCallbackIds")).get(0);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("hook_event_name", "PreToolUse");
        input.put("session_id", "s");
        input.put("transcript_path", "/tmp/t.jsonl");
        input.put("cwd", ".");
        input.put("tool_name", "Bash");
        input.put("tool_input", Map.of());
        input.put("tool_use_id", "toolu_1");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "hook_callback");
        request.put("callback_id", callbackId);
        request.put("input", input);
        request.put("tool_use_id", "toolu_1");
        transport.push(Map.of("type", "control_request", "request_id", "hook_1", "request", request));

        List<Map<String, Object>> responses = new ArrayList<>();
        awaitUntil("the hook answer", WAIT_SECONDS, () -> {
            responses.clear();
            for (String written : transport.writes()) {
                Map<String, Object> frame = MAPPER.readValue(written, Map.class);
                if ("control_response".equals(frame.get("type"))) {
                    Map<String, Object> response = (Map<String, Object>) frame.get("response");
                    if ("hook_1".equals(response.get("request_id"))) {
                        responses.add(response);
                    }
                }
            }
            return !responses.isEmpty();
        });
        assertThat(responses).hasSize(1);
        return responses.get(0);
    }

    @Test
    @SuppressWarnings("removal")
    void aHookOutputWithAnUnsendableUpdateIsAnsweredWithAnError() throws Exception {
        Map<String, Object> response = hookAnswer(Map.of(
                "behavior", "allow",
                "updatedPermissions", List.of(PermissionUpdate.removeRules(
                        List.of(new PermissionRuleValue("Bash")), PermissionUpdateDestination.SESSION))));

        assertThat(response).containsEntry("subtype", "error");
        assertThat((String) response.get("error")).contains("has no behavior");
    }

    @Test
    void aHookOutputWithAValidUpdateIsSentWithTheSessionDefault() throws Exception {
        Map<String, Object> response = hookAnswer(Map.of(
                "behavior", "allow",
                "updatedPermissions", List.of(PermissionUpdate.setMode(PermissionMode.ACCEPT_EDITS, null))));

        assertThat(response).containsEntry("subtype", "success");
        Map<String, Object> output = (Map<String, Object>) ((Map<String, Object>) response.get("response"))
                .get("hookSpecificOutput");
        Map<String, Object> decision = (Map<String, Object>) output.get("decision");
        assertThat(decision.get("updatedPermissions")).isEqualTo(List.of(
                Map.of("type", "setMode", "mode", "acceptEdits", "destination", "session")));
    }

}
