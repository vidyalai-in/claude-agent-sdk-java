package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.internal.ForwardSubagentTextTest.RespondingTransport;
import in.vidyalai.claude.sdk.transport.Transport;

/**
 * {@code verbatimPrompts} marks every user message the SDK sends
 * {@code client_composed}, so Claude Code delivers it as written: no
 * {@code @path} expansion, no slash-command dispatch (Python SDK #1269).
 */
class VerbatimPromptsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Transport> transportsToClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Transport t : transportsToClose) {
            try {
                t.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        transportsToClose.clear();
    }

    private RespondingTransport newTransport() {
        RespondingTransport transport = new RespondingTransport();
        transportsToClose.add(transport);
        return transport;
    }

    private static ClaudeAgentOptions options(boolean verbatimPrompts) {
        return ClaudeAgentOptions.builder().verbatimPrompts(verbatimPrompts).build();
    }

    /** The user messages written to stdin, in order. */
    @SuppressWarnings({ "unchecked", "null" })
    private static List<Map<String, Object>> userMessages(RespondingTransport transport) throws Exception {
        List<Map<String, Object>> users = new ArrayList<>();
        for (String written : transport.allWrites()) {
            Map<String, Object> frame = MAPPER.readValue(written, Map.class);
            if ("user".equals(frame.get("type"))) {
                users.add(frame);
            }
        }
        return users;
    }

    @SuppressWarnings("unchecked")
    private static Object content(Map<String, Object> frame) {
        return ((Map<String, Object>) frame.get("message")).get("content");
    }

    private static Map<String, Object> streamed(String text) {
        return Map.of(
                "type", "user",
                "session_id", "",
                "message", Map.of("role", "user", "content", text));
    }

    @Test
    void defaultsToFalse() {
        assertThat(ClaudeAgentOptions.builder().build().verbatimPrompts()).isFalse();
        assertThat(options(true).toBuilder().build().verbatimPrompts()).isTrue();
    }

    @Test
    void stampOverwritesACallerSuppliedValue_andCopiesTheMessage() {
        Map<String, Object> message = new HashMap<>(streamed("hi"));
        message.put("client_composed", false);

        Map<String, Object> stamped = QueryHandler.stampUserMessage(message, true);

        assertThat(stamped).containsEntry("client_composed", true);
        assertThat(message).containsEntry("client_composed", false);
        assertThat(QueryHandler.stampUserMessage(message, false)).isSameAs(message);
    }

    @Nested
    class QueryFunction {

        @Test
        void stringPromptIsMarkedClientComposed() throws Exception {
            RespondingTransport transport = newTransport();
            ClaudeSDK.query("read @/etc/hostname", options(true), transport);

            List<Map<String, Object>> users = userMessages(transport);
            assertThat(users).hasSize(1);
            assertThat(users.get(0)).containsEntry("client_composed", true);
            assertThat(content(users.get(0))).isEqualTo("read @/etc/hostname");
        }

        @Test
        void stringPromptIsUnmarkedByDefault() throws Exception {
            RespondingTransport transport = newTransport();
            ClaudeSDK.query("hi", options(false), transport);

            assertThat(userMessages(transport)).singleElement()
                    .satisfies(m -> assertThat(m).doesNotContainKey("client_composed"));
        }

        @Test
        void streamedPromptMarksEveryMessage() throws Exception {
            RespondingTransport transport = newTransport();
            Map<String, Object> first = new HashMap<>(streamed("one"));
            Map<String, Object> second = new HashMap<>(streamed("two"));
            second.put("client_composed", true);
            Map<String, Object> third = new HashMap<>(streamed("three"));
            third.put("client_composed", false);
            ClaudeSDK.query(List.of(first, second, third).iterator(), options(true), transport);

            List<Map<String, Object>> users = userMessages(transport);
            assertThat(users)
                    .hasSize(3)
                    .allSatisfy(m -> assertThat(m).containsEntry("client_composed", true));
            assertThat(users).extracting(VerbatimPromptsTest::content).containsExactly("one", "two", "three");
            // The caller's maps are not mutated.
            assertThat(first).doesNotContainKey("client_composed");
            assertThat(third).containsEntry("client_composed", false);
        }

        @Test
        void streamedPromptIsUntouchedByDefault() throws Exception {
            RespondingTransport transport = newTransport();
            Map<String, Object> perTurn = new HashMap<>(streamed("second"));
            perTurn.put("client_composed", true);
            ClaudeSDK.query(List.of(streamed("first"), perTurn).iterator(), options(false), transport);

            List<Map<String, Object>> users = userMessages(transport);
            assertThat(users).hasSize(2);
            assertThat(users.get(0)).doesNotContainKey("client_composed");
            // Per-turn control: a caller-set value passes through.
            assertThat(users.get(1)).containsEntry("client_composed", true);
        }
    }

    @Nested
    class Client {

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void connectWithStringPrompt(boolean enabled) throws Exception {
            RespondingTransport transport = newTransport();
            try (ClaudeSDKClient client = new ClaudeSDKClient(options(enabled), transport)) {
                client.connect("hi");
            }
            assertMarked(userMessages(transport), 1, enabled);
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void queryWithStringPrompt(boolean enabled) throws Exception {
            RespondingTransport transport = newTransport();
            try (ClaudeSDKClient client = new ClaudeSDKClient(options(enabled), transport)) {
                client.connect();
                client.query("hi");
            }
            assertMarked(userMessages(transport), 1, enabled);
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void queryWithStreamedPrompt(boolean enabled) throws Exception {
            RespondingTransport transport = newTransport();
            Map<String, Object> first = new HashMap<>(streamed("first"));
            try (ClaudeSDKClient client = new ClaudeSDKClient(options(enabled), transport)) {
                client.connect();
                client.query(List.of(first, streamed("second")).iterator());
            }
            assertMarked(userMessages(transport), 2, enabled);
            // The stamp goes on a copy; the caller's message is left alone.
            assertThat(first).doesNotContainKey("client_composed");
        }

        private void assertMarked(List<Map<String, Object>> users, int count, boolean enabled) {
            assertThat(users).hasSize(count);
            for (Map<String, Object> m : users) {
                if (enabled) {
                    assertThat(m).containsEntry("client_composed", true);
                } else {
                    assertThat(m).doesNotContainKey("client_composed");
                }
            }
        }
    }
}
