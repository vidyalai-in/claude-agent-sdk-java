package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateType;
import in.vidyalai.claude.sdk.types.permission.ToolPermissionContext;

/**
 * One permission suggestion this SDK cannot represent never fails the whole
 * {@code can_use_tool} request: the callback still runs, with the
 * suggestions it can represent. The Python SDK keeps whatever strings the CLI
 * sends.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class PermissionSuggestionLeniencyTest {

    private static final long WAIT_SECONDS = 5;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private static Map<String, Object> addRules(String behavior, String destination) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("type", "addRules");
        suggestion.put("rules", List.of(Map.of("toolName", "Bash", "ruleContent", "ls:*")));
        suggestion.put("behavior", behavior);
        suggestion.put("destination", destination);
        return suggestion;
    }

    @Test
    void theCallbackRunsWithTheSuggestionsItCanRepresent() throws Exception {
        PipeTransport transport = new PipeTransport();
        AtomicReference<ToolPermissionContext> seen = new AtomicReference<>();
        QueryHandler handler = new QueryHandler(transport, true, (tool, input, context) -> {
            seen.set(context);
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        }, null, null, null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();

        List<Object> suggestions = new ArrayList<>();
        suggestions.add(addRules("allow", "cliArg")); // kept: CLI_ARG
        suggestions.add(Map.of("type", "someFutureType", "destination", "session"));
        suggestions.add(addRules("maybe", "session"));
        suggestions.add(addRules("allow", "somewhereNew"));
        suggestions.add(Map.of("type", "setMode", "mode", "warp", "destination", "session"));
        suggestions.add(Map.of("type", "setMode", "destination", "session")); // kept: mode null
        suggestions.add("not an object");
        suggestions.add(null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "can_use_tool");
        request.put("tool_name", "Bash");
        request.put("input", Map.of("command", "ls"));
        request.put("permission_suggestions", suggestions);
        request.put("tool_use_id", "toolu_1");
        transport.push(Map.of("type", "control_request", "request_id", "perm_1", "request", request));

        awaitUntil("the answer", WAIT_SECONDS, () -> !transport.responsesTo("perm_1").isEmpty());
        assertThat(transport.responsesTo("perm_1").get(0)).containsEntry("subtype", "success");

        List<PermissionUpdate> kept = seen.get().suggestions();
        assertThat(kept).hasSize(2);
        assertThat(kept.get(0).type()).isEqualTo(PermissionUpdateType.ADD_RULES);
        assertThat(kept.get(0).destination()).isEqualTo(PermissionUpdateDestination.CLI_ARG);
        assertThat(kept.get(0).behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(kept.get(1).type()).isEqualTo(PermissionUpdateType.SET_MODE);
        assertThat(kept.get(1).mode()).isNull();
        assertThat(kept.get(1).destination()).isEqualTo(PermissionUpdateDestination.SESSION);
    }

    @Test
    void cliArgRoundTrips() {
        PermissionUpdate update = PermissionUpdate.fromMap(addRules("deny", "cliArg"));
        assertThat(update.destination()).isEqualTo(PermissionUpdateDestination.CLI_ARG);
        assertThat(update.toMap()).containsEntry("destination", "cliArg");
        assertThat(PermissionUpdateDestination.fromValue("cliArg")).isEqualTo(PermissionUpdateDestination.CLI_ARG);
    }

    @Test
    void setModeWithoutAModeKeepsTheModeUnset() {
        PermissionUpdate update = PermissionUpdate.fromMap(Map.of("type", "setMode", "destination", "session"));
        assertThat(update.mode()).isNull();
        // As the Python SDK's to_dict: an unset mode is omitted.
        assertThat(update.toMap()).isEqualTo(Map.of("type", "setMode", "destination", "session"));
    }

    @Test
    void anUnrepresentableValueStillThrowsFromFromMap() {
        assertThatThrownBy(() -> PermissionUpdate.fromMap(Map.of("type", "someFutureType")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionUpdate.fromMap(addRules("allow", "somewhereNew")))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
