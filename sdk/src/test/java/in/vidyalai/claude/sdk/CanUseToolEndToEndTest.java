package in.vidyalai.claude.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * A {@code canUseTool} callback drives a full permission round-trip on the
 * one-shot {@link ClaudeSDK#query(String, ClaudeAgentOptions)} API (Python SDK
 * #1204).
 *
 * <p>
 * Two things used to make this impossible, and this test covers both from the
 * caller's side rather than from {@code QueryHandler}'s:
 *
 * <ol>
 * <li>the callback was rejected outright for string prompts with "requires
 * streaming mode"; and</li>
 * <li>{@code streamInput()} held stdin open only for hooks and SDK MCP servers,
 * so a run configured with just a callback closed stdin as soon as the prompt
 * was written — and the CLI, blocked on a {@code control_response} that could
 * no longer arrive, failed the permission request with "Stream closed".</li>
 * </ol>
 *
 * <p>
 * The transport below models that second failure faithfully: once
 * {@code endInput()} has been called, a write is rejected the way the CLI's
 * closed stdin would reject it, and the rejection is recorded rather than
 * merely thrown, so reverting the fix fails the assertion instead of hanging
 * the test.
 */
class CanUseToolEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("null")
    @Test
    void stringPromptWithCanUseTool_completesThePermissionRoundTrip() {
        List<String> callbackCalls = new ArrayList<>();

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool((toolName, input, context) -> {
                    callbackCalls.add(toolName);
                    return CompletableFuture.completedFuture(new PermissionResultAllow());
                })
                .build();

        List<Message> messages;
        try (PermissionGatedTransport transport = new PermissionGatedTransport()) {
            // Used to throw IllegalArgumentException before the prompt was
            // even written.
            messages = ClaudeSDK.query("write it", options, transport);

            assertThat(callbackCalls).containsExactly("Write");
            assertThat(transport.permissionVerdicts()).hasSize(1);
            assertThat(transport.permissionVerdicts().get(0)).containsEntry("behavior", "allow");
            // stdin stayed open long enough for the verdict to land.
            assertThat(transport.writesRejectedAfterEndInput()).isZero();
            // ...and was closed once the run ended.
            assertThat(transport.endInputCalled()).isTrue();
        }

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(1)).isInstanceOf(ResultMessage.class);
    }

    @Test
    void stringPromptWithCanUseTool_deniedVerdictAlsoReachesTheCli() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool((toolName, input, context) -> CompletableFuture.completedFuture(
                        new in.vidyalai.claude.sdk.types.permission.PermissionResultDeny("read-only run")))
                .build();

        try (PermissionGatedTransport transport = new PermissionGatedTransport()) {
            ClaudeSDK.query("write it", options, transport);

            assertThat(transport.permissionVerdicts()).hasSize(1);
            Map<String, Object> verdict = transport.permissionVerdicts().get(0);
            assertThat(verdict).containsEntry("behavior", "deny");
            assertThat(verdict).containsEntry("message", "read-only run");
            assertThat(transport.writesRejectedAfterEndInput()).isZero();
        }
    }

    /**
     * A transport that gates the run on a permission round-trip.
     *
     * <p>
     * Answers {@code initialize}; on the prompt write, emits a
     * {@code can_use_tool} control request; on the SDK's control response,
     * emits the assistant message and the result. A write after
     * {@code endInput()} is rejected like the CLI's closed stdin would reject
     * it — and, so the test cannot hang on the fix being absent, the run is
     * unblocked anyway with the result frames.
     */
    static class PermissionGatedTransport implements Transport {

        private final LinkedBlockingQueue<Map<String, Object>> inbound = new LinkedBlockingQueue<>();
        private final List<Map<String, Object>> verdicts = new ArrayList<>();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean inputEnded = new AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicInteger rejectedWrites =
                new java.util.concurrent.atomic.AtomicInteger();

        List<Map<String, Object>> permissionVerdicts() {
            synchronized (verdicts) {
                return List.copyOf(verdicts);
            }
        }

        boolean endInputCalled() {
            return inputEnded.get();
        }

        int writesRejectedAfterEndInput() {
            return rejectedWrites.get();
        }

        @Override
        public void connect() {
            ready.set(true);
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                private Map<String, Object> pending;
                private boolean done = false;

                @Override
                public boolean hasNext() {
                    while (pending == null && !done && !closed.get()) {
                        try {
                            pending = inbound.poll(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    if (pending != null && "result".equals(pending.get("type"))) {
                        // The CLI exits after the run-ending result.
                        done = true;
                    }
                    return pending != null;
                }

                @Override
                public Map<String, Object> next() {
                    if (pending == null) {
                        throw new NoSuchElementException();
                    }
                    Map<String, Object> next = pending;
                    pending = null;
                    return next;
                }
            };
        }

        @SuppressWarnings({ "unchecked", "null" })
        @Override
        public void write(String data) throws CLIConnectionException {
            if (closed.get()) {
                throw new CLIConnectionException("Transport closed");
            }
            Map<String, Object> frame;
            try {
                frame = MAPPER.readValue(data, Map.class);
            } catch (Exception e) {
                throw new CLIConnectionException("Bad frame: " + e.getMessage(), e);
            }

            if (inputEnded.get()) {
                // What the CLI does when the SDK writes to a stdin it already
                // closed. Record it, then unblock the run so the assertion —
                // not a timeout — is what reports the regression.
                rejectedWrites.incrementAndGet();
                emitTurn();
                throw new CLIConnectionException("Stream closed");
            }

            switch (String.valueOf(frame.get("type"))) {
                case "control_request" -> respondSuccess((String) frame.get("request_id"));
                case "control_response" -> {
                    Map<String, Object> response = (Map<String, Object>) frame.get("response");
                    Object payload = (response != null) ? response.get("response") : null;
                    if (payload instanceof Map<?, ?> verdict) {
                        synchronized (verdicts) {
                            verdicts.add((Map<String, Object>) verdict);
                        }
                    }
                    emitTurn();
                }
                // The prompt: ask for permission before doing anything else.
                default -> requestWritePermission();
            }
        }

        private void respondSuccess(String requestId) {
            Map<String, Object> response = new HashMap<>();
            response.put("subtype", "success");
            response.put("request_id", requestId);
            response.put("response", Map.of("commands", List.of()));

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", "control_response");
            envelope.put("response", response);
            inbound.add(envelope);
        }

        private void requestWritePermission() {
            Map<String, Object> request = new HashMap<>();
            request.put("subtype", "can_use_tool");
            request.put("tool_name", "Write");
            request.put("input", Map.of("file_path", "/tmp/x.txt", "content", "hi"));

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", "control_request");
            envelope.put("request_id", "perm-1");
            envelope.put("request", request);
            inbound.add(envelope);
        }

        private void emitTurn() {
            Map<String, Object> assistant = new HashMap<>();
            assistant.put("type", "assistant");
            assistant.put("session_id", "s");
            assistant.put("message", Map.of(
                    "role", "assistant",
                    "model", "claude-sonnet-4-5",
                    "content", List.of(Map.of("type", "text", "text", "done"))));
            inbound.add(assistant);

            Map<String, Object> result = new HashMap<>();
            result.put("type", "result");
            result.put("subtype", "success");
            result.put("is_error", false);
            result.put("num_turns", 1);
            result.put("session_id", "s");
            result.put("duration_ms", 1);
            result.put("duration_api_ms", 1);
            result.put("total_cost_usd", 0.0);
            inbound.add(result);
        }

        @Override
        public void endInput() {
            inputEnded.set(true);
        }

        @Override
        public boolean isReady() {
            return ready.get() && !closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
