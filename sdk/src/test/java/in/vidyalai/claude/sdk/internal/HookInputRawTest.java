package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.InboundCallbackWaitTest.hookRequest;
import static in.vidyalai.claude.sdk.internal.InboundCallbackWaitTest.preToolUseInput;
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.types.config.CompactTriggerType;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.input.HookInput;
import in.vidyalai.claude.sdk.types.hook.input.PreCompactHookInput;
import in.vidyalai.claude.sdk.types.hook.input.PreToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.hook.output.UserPromptSubmitHookSpecificOutput;

/**
 * Hook inputs carry the CLI's whole map ({@link HookInput#raw()}), parse an
 * unknown {@code PreCompact} trigger leniently, and hook outputs can carry
 * keys the SDK does not model, as a Python hook's plain dicts can.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class HookInputRawTest {

    private static final long WAIT_SECONDS = 5;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    /** Runs one hook request through a handler and returns what the callback saw. */
    private HookInput deliver(PipeTransport transport, HookEvent event, Map<String, Object> input)
            throws Exception {
        AtomicReference<HookInput> seen = new AtomicReference<>();
        HookMatcher.HookCallback callback = (hookInput, context) -> {
            seen.set(hookInput);
            return CompletableFuture.completedFuture(HookOutput.empty());
        };
        QueryHandler handler = new QueryHandler(transport, true, null,
                Map.of(event, List.of(new HookMatcher(null, List.of(callback)))), null,
                null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();
        handler.initialize();

        transport.push(hookRequest("hook_req_1", input));
        awaitUntil("the answer", WAIT_SECONDS, () -> !transport.responsesTo("hook_req_1").isEmpty());
        assertThat(transport.responsesTo("hook_req_1").get(0)).containsEntry("subtype", "success");
        return seen.get();
    }

    @Test
    void rawCarriesEveryKeyTheCliSent() throws Exception {
        Map<String, Object> input = preToolUseInput();
        input.put("future_field", Map.of("depth", 2));
        input.put("nullable_field", null);

        HookInput seen = deliver(new PipeTransport(), HookEvent.PRE_TOOL_USE, input);

        assertThat(seen).isInstanceOf(PreToolUseHookInput.class);
        assertThat(((PreToolUseHookInput) seen).toolName()).isEqualTo("Bash");
        assertThat(seen.raw())
                .containsEntry("hook_event_name", "PreToolUse")
                .containsEntry("tool_name", "Bash")
                .containsEntry("future_field", Map.of("depth", 2))
                .containsKey("nullable_field");
        assertThatThrownBy(() -> seen.raw().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Map<String, Object> preCompactInput(Object trigger) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("hook_event_name", "PreCompact");
        input.put("session_id", "s");
        input.put("transcript_path", "/tmp/t.jsonl");
        input.put("cwd", ".");
        input.put("trigger", trigger);
        input.put("custom_instructions", null);
        return input;
    }

    @Test
    void anUnknownCompactTriggerReadsAsNullAndTheHookStillRuns() throws Exception {
        HookInput seen = deliver(new PipeTransport(), HookEvent.PRE_COMPACT, preCompactInput("scheduled"));

        assertThat(seen).isInstanceOf(PreCompactHookInput.class);
        assertThat(((PreCompactHookInput) seen).trigger()).isNull();
        assertThat(seen.raw()).containsEntry("trigger", "scheduled");
    }

    @Test
    void aKnownCompactTriggerStillParses() throws Exception {
        HookInput seen = deliver(new PipeTransport(), HookEvent.PRE_COMPACT, preCompactInput("auto"));
        assertThat(((PreCompactHookInput) seen).trigger()).isEqualTo(CompactTriggerType.AUTO);
    }

    @Test
    void rawIsEmptyOutsideTheSdkReadPath() {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        HookInput parsed = mapper.convertValue(preToolUseInput(), HookInput.class);
        assertThat(parsed.raw()).isEmpty();

        PreToolUseHookInput built = new PreToolUseHookInput("s", "/t", ".", null, "Bash", Map.of(), "toolu_1",
                null, null);
        assertThat(built.raw()).isEmpty();
    }

    @Test
    void hookOutputCarriesExtraFields() {
        HookOutput output = HookOutput.builder()
                .reason("typed")
                .extraField("reason", "extra loses to the typed field")
                .extraField("futureKey", List.of(1, 2))
                .extraFields(Map.of("other", true))
                .hookSpecificOutput(new UserPromptSubmitHookSpecificOutput("ctx"))
                .build();

        assertThat(output.toMap())
                .containsEntry("reason", "typed")
                .containsEntry("futureKey", List.of(1, 2))
                .containsEntry("other", true)
                .containsKey("hookSpecificOutput");
        assertThat(output.extraFields()).containsOnlyKeys("reason", "futureKey", "other");

        // An extra field supplies a key the typed fields leave unset.
        assertThat(HookOutput.builder().extraField("decision", "block").build().toMap())
                .isEqualTo(Map.of("decision", "block"));
        // Async outputs carry them too.
        assertThat(HookOutput.builder().async(true).extraField("tag", "x").build().toMap())
                .isEqualTo(Map.of("async", true, "tag", "x"));
    }

}
