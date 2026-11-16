package in.vidyalai.claude.sdk.internal;

import static in.vidyalai.claude.sdk.internal.PipeTransport.awaitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import in.vidyalai.claude.sdk.exceptions.ClaudeSDKException;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.UserMessage;

/**
 * A malformed {@code control_response} never kills the reader. As in the
 * Python SDK ({@code query.py} {@code _read_messages}): a frame without a
 * known {@code request_id} is ignored, any subtype other than
 * {@code "error"} is a success, and a non-object body reads as {@code {}}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("null")
class ControlResponseLeniencyTest {

    private static final long WAIT_SECONDS = 5;

    private final List<QueryHandler> handlers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (QueryHandler h : handlers) {
            h.close();
        }
    }

    private QueryHandler start(PipeTransport transport) {
        QueryHandler handler = new QueryHandler(transport, true, null, null, null,
                null, null, null, Duration.ofSeconds(10), null);
        handlers.add(handler);
        transport.connect();
        handler.start();
        return handler;
    }

    private static Map<String, Object> controlResponse(Object response) {
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "control_response");
        frame.put("response", response);
        return frame;
    }

    private static Map<String, Object> response(Object requestId, Object subtype, Object body) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (requestId != null) {
            response.put("request_id", requestId);
        }
        if (subtype != null) {
            response.put("subtype", subtype);
        }
        if (body != null) {
            response.put("response", body);
        }
        return response;
    }

    @Test
    void malformedResponsesAreIgnoredAndTheReaderKeepsGoing() throws Exception {
        PipeTransport transport = new PipeTransport();
        transport.replier = request -> null; // answered by hand below
        QueryHandler handler = start(transport);

        CompletableFuture<Void> interrupt = CompletableFuture.runAsync(handler::interrupt);
        awaitUntil("the interrupt request", WAIT_SECONDS,
                () -> !transport.requestsWritten("interrupt").isEmpty());
        String requestId = (String) transport.writtenFrames().get(0).get("request_id");

        transport.push(Map.of("type", "control_response")); // no response at all
        transport.push(controlResponse("not an object"));
        transport.push(controlResponse(List.of(1, 2)));
        transport.push(controlResponse(response(null, "success", Map.of()))); // no request_id
        transport.push(controlResponse(response(42, "success", Map.of()))); // non-string id
        transport.push(controlResponse(response("req_unknown", "success", Map.of())));
        transport.push(controlResponse(response("req_unknown", "error", null)));
        // Missing subtype: still a success.
        transport.push(controlResponse(response(requestId, null, "not a dict")));
        transport.push(Map.of("type", "user", "message", Map.of("role", "user", "content", "still here")));

        interrupt.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Iterator<Message> messages = handler.receiveMessages();
        Message next = messages.next();
        assertThat(next).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) next).content()).isEqualTo("still here");
    }

    @Test
    void anUnknownSubtypeIsASuccessAndANonObjectBodyReadsAsEmpty() throws Exception {
        PipeTransport transport = new PipeTransport();
        transport.replier = request -> controlResponse(
                response(request.get("request_id"), "something_new", "not a dict"));
        QueryHandler handler = start(transport);

        assertThat(handler.initialize()).isNotNull();
        assertThat(handler.getInitializationResult()).isEqualTo(Map.of());
    }

    @Test
    void anErrorWithoutTextIsAnUnknownError() throws Exception {
        PipeTransport transport = new PipeTransport();
        transport.replier = request -> controlResponse(response(request.get("request_id"), "error", null));
        QueryHandler handler = start(transport);

        assertThatThrownBy(handler::interrupt)
                .isInstanceOf(ClaudeSDKException.class)
                .hasMessage("Unknown error");
    }

    @Test
    void anErrorIsReportedWithItsText() throws Exception {
        PipeTransport transport = new PipeTransport();
        transport.replier = request -> PipeTransport.error(request, "nope");
        QueryHandler handler = start(transport);

        assertThatThrownBy(handler::interrupt)
                .isInstanceOf(ClaudeSDKException.class)
                .hasMessage("nope");
    }

}
