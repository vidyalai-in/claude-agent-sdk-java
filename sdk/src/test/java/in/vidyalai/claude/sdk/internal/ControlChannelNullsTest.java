package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.InboundCallbackWaitTest.canUseToolRequest;
import static in.vidyalai.claude.sdk.internal.InboundCallbackWaitTest.hookRequest;
import static in.vidyalai.claude.sdk.internal.InboundCallbackWaitTest.preToolUseInput;
import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.hook.output.PreToolUseHookSpecificOutput;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionResultDeny;

/**
 * Maps on the control channel are written as given, explicit nulls included,
 * as the Python SDK's {@code json.dumps} writes them; the SDK's own maps omit
 * their unset keys, and send an explicit null exactly where the Python SDK
 * does.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class ControlChannelNullsTest {

    private static final long WAIT_SECONDS = 5;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private QueryHandler start(PipeTransport transport, ClaudeAgentOptions.@Nullable CanUseTool canUseTool,
            @Nullable HookMatcher matcher) {
        Map<HookEvent, List<HookMatcher>> hooks = ((matcher != null)
                ? Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher))
                : null);
        QueryHandler handler = new QueryHandler(transport, true, canUseTool, hooks, null,
                null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();
        return handler;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(PipeTransport transport, String requestId) throws Exception {
        awaitUntil("the answer to " + requestId, WAIT_SECONDS, () -> !transport.responsesTo(requestId).isEmpty());
        Map<String, Object> response = transport.responsesTo(requestId).get(0);
        assertThat(response).containsEntry("subtype", "success");
        return (Map<String, Object>) response.get("response");
    }

    private static Map<String, Object> withNull(String key, Object... rest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, null);
        for (int i = 0; i < rest.length; i += 2) {
            map.put((String) rest[i], rest[i + 1]);
        }
        return map;
    }

    @Test
    void initializeSendsHooksNullWhenThereAreNone() throws Exception {
        PipeTransport transport = new PipeTransport();
        start(transport, null, null).initialize();

        Map<String, Object> request = transport.requestsWritten("initialize").get(0);
        assertThat(request).containsKey("hooks");
        assertThat(request.get("hooks")).isNull();
        // Unset optional fields stay omitted.
        assertThat(request).doesNotContainKeys("agents", "skills", "excludeDynamicSections",
                "forwardSubagentText", "systemPromptSnapshot");
    }

    @SuppressWarnings("unchecked")
    @Test
    void initializeSendsMatcherNullForAMatchAllMatcher() throws Exception {
        PipeTransport transport = new PipeTransport();
        HookMatcher matcher = new HookMatcher(null,
                List.of((input, context) -> CompletableFuture.completedFuture(HookOutput.empty())));
        start(transport, null, matcher).initialize();

        Map<String, Object> request = transport.requestsWritten("initialize").get(0);
        Map<String, Object> hooks = (Map<String, Object>) request.get("hooks");
        Map<String, Object> config = ((List<Map<String, Object>>) hooks.get("PreToolUse")).get(0);
        assertThat(config).containsKey("matcher");
        assertThat(config.get("matcher")).isNull();
        assertThat(config).containsEntry("hookCallbackIds", List.of("hook_0"));
        assertThat(config).doesNotContainKeys("timeout", "onFailure");
    }

    @SuppressWarnings("unchecked")
    @Test
    void permissionUpdatedInputKeepsItsNulls() throws Exception {
        PipeTransport transport = new PipeTransport();
        start(transport, (tool, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultAllow(withNull("cwd", "command", "ls"))), null);

        transport.push(canUseToolRequest("perm_1", Map.of("command", "rm")));
        Map<String, Object> body = body(transport, "perm_1");
        assertThat(body).containsOnlyKeys("behavior", "updatedInput");
        Map<String, Object> updated = (Map<String, Object>) body.get("updatedInput");
        assertThat(updated).containsKey("cwd").containsEntry("command", "ls");
        assertThat(updated.get("cwd")).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void theOriginalInputEchoedBackKeepsItsNulls() throws Exception {
        PipeTransport transport = new PipeTransport();
        start(transport, (tool, input, context) -> {
            // The callback sees the null too.
            assertThat(input).containsKey("timeout");
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        }, null);

        transport.push(canUseToolRequest("perm_1", withNull("timeout", "command", "ls")));
        Map<String, Object> updated = (Map<String, Object>) body(transport, "perm_1").get("updatedInput");
        assertThat(updated).containsOnlyKeys("timeout", "command");
        assertThat(updated.get("timeout")).isNull();
    }

    @Test
    void aDenyHasNoNullKeys() throws Exception {
        PipeTransport transport = new PipeTransport();
        start(transport, (tool, input, context) -> CompletableFuture.completedFuture(
                new PermissionResultDeny("no")), null);

        transport.push(canUseToolRequest("perm_1", Map.of()));
        assertThat(body(transport, "perm_1")).isEqualTo(Map.of("behavior", "deny", "message", "no"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void hookOutputKeepsUserNullsAndOmitsUnsetFields() throws Exception {
        PipeTransport transport = new PipeTransport();
        HookMatcher matcher = new HookMatcher(null, List.of((input, context) -> CompletableFuture.completedFuture(
                HookOutput.builder()
                        .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                                PermissionDecision.ALLOW, null, withNull("description", "command", "ls"), null))
                        .extraField("futureFlag", null)
                        .build())));
        start(transport, null, matcher).initialize();

        transport.push(hookRequest("hook_req_1", preToolUseInput()));
        Map<String, Object> body = body(transport, "hook_req_1");
        assertThat(body).containsOnlyKeys("hookSpecificOutput", "futureFlag");
        assertThat(body.get("futureFlag")).isNull();
        Map<String, Object> specific = (Map<String, Object>) body.get("hookSpecificOutput");
        assertThat(specific).containsOnlyKeys("hookEventName", "permissionDecision", "updatedInput");
        Map<String, Object> updated = (Map<String, Object>) specific.get("updatedInput");
        assertThat(updated).containsKey("description");
        assertThat(updated.get("description")).isNull();
    }

    @Test
    void anEmptyHookOutputIsAnEmptyObject() throws Exception {
        PipeTransport transport = new PipeTransport();
        HookMatcher matcher = new HookMatcher(null,
                List.of((input, context) -> CompletableFuture.completedFuture(HookOutput.empty())));
        start(transport, null, matcher).initialize();

        transport.push(hookRequest("hook_req_1", preToolUseInput()));
        assertThat(body(transport, "hook_req_1")).isEmpty();
    }

    @Test
    void streamedUserMessagesKeepTheirNulls() throws Exception {
        PipeTransport transport = new PipeTransport();
        QueryHandler handler = start(transport, null, null);
        Map<String, Object> message = new HashMap<>();
        message.put("type", "user");
        message.put("message", Map.of("role", "user", "content", "hi"));
        message.put("parent_tool_use_id", null);
        message.put("session_id", "");

        handler.streamInput(List.of(message).iterator());

        Map<String, Object> written = transport.writtenFrames().get(0);
        assertThat(written).containsKey("parent_tool_use_id");
        assertThat(written.get("parent_tool_use_id")).isNull();
    }

}
