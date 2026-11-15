package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import in.vidyalai.claude.sdk.internal.MessageParser;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;

/**
 * {@code user_message_uuid} and {@code user_message_uuids} on assistant,
 * stream-event and result frames (Python SDK #1505).
 */
class UserMessageUuidsParsingTest {

    private static Map<String, Object> assistantFrame() {
        return Map.of("type", "assistant",
                "message", Map.of("content", List.of(), "model", "claude-opus-4-6"));
    }

    private static Map<String, Object> streamEventFrame() {
        return Map.of("type", "stream_event", "uuid", "event-1", "session_id", "session_123",
                "event", Map.of("type", "message_start"));
    }

    private static Map<String, Object> resultFrame(String subtype) {
        return Map.of("type", "result", "subtype", subtype, "duration_ms", 1000, "duration_api_ms", 500,
                "is_error", !"success".equals(subtype), "num_turns", 2, "session_id", "session_123");
    }

    static Stream<Arguments> frames() {
        List<Map<String, Object>> frames = List.of(assistantFrame(), streamEventFrame(),
                resultFrame("success"), resultFrame("error_during_execution"));
        List<Arguments> cases = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            cases.add(Arguments.of(frame, null, null));
            // Older CLIs report only the single uuid.
            cases.add(Arguments.of(frame, "user-1", null));
            cases.add(Arguments.of(frame, "user-2", List.of("user-1", "user-2")));
        }
        return cases.stream();
    }

    private static String uuidOf(Message message) {
        if (message instanceof AssistantMessage a) {
            return a.userMessageUuid();
        } else if (message instanceof StreamEvent e) {
            return e.userMessageUuid();
        }
        return ((ResultMessage) message).userMessageUuid();
    }

    private static List<String> uuidsOf(Message message) {
        if (message instanceof AssistantMessage a) {
            return a.userMessageUuids();
        } else if (message instanceof StreamEvent e) {
            return e.userMessageUuids();
        }
        return ((ResultMessage) message).userMessageUuids();
    }

    @ParameterizedTest
    @MethodSource("frames")
    void uuidsOfTheAnsweredUserMessagesAreSurfacedAsSent(Map<String, Object> frame, String uuid,
            List<String> uuids) throws Exception {
        Map<String, Object> data = new HashMap<>(frame);
        if (uuid != null) {
            data.put("user_message_uuid", uuid);
        }
        if (uuids != null) {
            data.put("user_message_uuids", uuids);
        }

        Message message = MessageParser.parse(data);

        assertThat(uuidOf(message)).isEqualTo(uuid);
        assertThat(uuidsOf(message)).isEqualTo(uuids);
    }

    @Test
    void emptyListStaysDistinguishableFromAbsent() throws Exception {
        Map<String, Object> data = new HashMap<>(resultFrame("error_during_execution"));
        data.put("user_message_uuids", List.of());

        ResultMessage message = (ResultMessage) Objects.requireNonNull(MessageParser.parse(data));

        assertThat(message.userMessageUuid()).isNull();
        assertThat(message.userMessageUuids()).isNotNull().isEmpty();
    }

    @Test
    void malformedValuesDegradeRatherThanFailTheFrame() throws Exception {
        Map<String, Object> data = new HashMap<>(resultFrame("success"));
        data.put("user_message_uuid", 7);
        List<Object> mixed = new ArrayList<>();
        mixed.add("user-1");
        mixed.add(3);
        mixed.add(null);
        data.put("user_message_uuids", mixed);

        ResultMessage message = (ResultMessage) Objects.requireNonNull(MessageParser.parse(data));

        assertThat(message.userMessageUuid()).isNull();
        assertThat(message.userMessageUuids()).containsExactly("user-1");
    }
}
