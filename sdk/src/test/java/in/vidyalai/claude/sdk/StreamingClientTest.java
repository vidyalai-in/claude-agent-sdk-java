package in.vidyalai.claude.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.mcp.McpStatusResponse;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * Tests for ClaudeSDKClient streaming functionality.
 * Equivalent to Python's test_streaming_client.py
 */
class StreamingClientTest {

    /**
     * Creates a mock transport for testing.
     */
    private static MockTransport createMockTransport() {
        return new MockTransport();
    }

    // ==================== Connection Lifecycle Tests ====================

    @Test
    void testAutoConnectWithTryWithResources() {
        MockTransport mockTransport = createMockTransport();
        mockTransport.addResultMessage();

        try (var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport)) {
            client.connect();
            assertThat(client.isConnected()).isTrue();
        }

        // After close, transport should be closed
        assertThat(mockTransport.isClosed()).isTrue();
    }

    @Test
    void testManualConnectDisconnect() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);

        assertThat(client.isConnected()).isFalse();

        client.connect();
        assertThat(client.isConnected()).isTrue();

        client.disconnect();
        assertThat(client.isConnected()).isFalse();
        client.close();
    }

    @Test
    void testConnectWithStringPrompt() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Hello Claude");

        assertThat(client.isConnected()).isTrue();
        client.disconnect();
        client.close();
    }

    // ==================== Message Sending Tests ====================

    @Test
    void testSendMessage() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        client.sendMessage("Test message");

        // Verify message was written
        List<String> written = mockTransport.getWrittenData();
        assertThat(written).anyMatch(s -> s.contains("Test message"));

        client.close();
    }

    @Test
    void testSendMessageWithSessionId() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        client.sendMessage("Test", "custom-session");

        List<String> written = mockTransport.getWrittenData();
        assertThat(written).anyMatch(s -> s.contains("custom-session"));

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testSendMessageNotConnected() {
        var client = new ClaudeSDKClient();

        assertThatThrownBy(() -> client.sendMessage("Test"))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");
        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testQueryNotConnected() {
        var client = new ClaudeSDKClient();

        assertThatThrownBy(() -> client.query("Test"))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");
        client.close();
    }

    // ==================== Message Receiving Tests ====================

    @Test
    void testReceiveMessages() {
        MockTransport mockTransport = createMockTransport();
        mockTransport.addAssistantMessage("Hello!");
        mockTransport.addUserMessage("Hi there");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should have assistant, user, and result messages
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages.stream().anyMatch(m -> m instanceof AssistantMessage)).isTrue();

        client.close();
    }

    @Test
    void testReceiveResponseStopsAtResultMessage() {
        MockTransport mockTransport = createMockTransport();
        mockTransport.addAssistantMessage("Answer");
        mockTransport.addResultMessage();
        mockTransport.addAssistantMessage("Should not see this");

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should stop at ResultMessage
        assertThat(messages).anyMatch(m -> m instanceof ResultMessage);

        // Last message should be ResultMessage
        Message lastMsg = messages.get(messages.size() - 1);
        assertThat(lastMsg).isInstanceOf(ResultMessage.class);

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testReceiveMessagesNotConnected() {
        var client = new ClaudeSDKClient();

        assertThatThrownBy(() -> client.receiveMessages())
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");
        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testReceiveResponseNotConnected() {
        var client = new ClaudeSDKClient();

        assertThatThrownBy(() -> client.receiveResponse())
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");
        client.close();
    }

    // ==================== Interrupt Tests ====================

    @Test
    void testInterrupt() {
        MockTransport mockTransport = createMockTransport();
        mockTransport.setInterruptSupported(true);

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        // Should not throw
        client.interrupt();

        // Verify interrupt request was sent
        List<String> written = mockTransport.getWrittenData();
        assertThat(written).anyMatch(s -> s.contains("interrupt"));

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testInterruptNotConnected() {
        var client = new ClaudeSDKClient();

        assertThatThrownBy(() -> client.interrupt())
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");
        client.close();
    }

    // ==================== Client Options Tests ====================

    @Test
    void testClientWithOptions() {
        MockTransport mockTransport = createMockTransport();

        var options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read", "Write"))
                .systemPrompt("Be helpful")
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect();

        assertThat(client.isConnected()).isTrue();

        client.close();
    }

    // ==================== Edge Cases ====================

    @Test
    void testDoubleConnect() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        assertThat(client.isConnected()).isTrue();

        // Disconnect first, then reconnect
        client.disconnect();

        // Second connect - should work (creates new connection)
        client.connect();

        assertThat(client.isConnected()).isTrue();

        client.disconnect();
        client.close();
    }

    @Test
    void testDisconnectWithoutConnect() {
        var client = new ClaudeSDKClient();

        // Should not throw
        client.disconnect();
        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testContextManagerWithException() {
        MockTransport mockTransport = createMockTransport();

        assertThatThrownBy(() -> {
            try (var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport)) {
                client.connect();
                throw new RuntimeException("Test error");
            }
        }).isInstanceOf(RuntimeException.class).hasMessage("Test error");

        // Transport should still be closed
        assertThat(mockTransport.isClosed()).isTrue();
    }

    @Test
    void testCollectMessagesAsList() {
        MockTransport mockTransport = createMockTransport();
        mockTransport.addAssistantMessage("Hello");
        mockTransport.addAssistantMessage("World");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages.get(messages.size() - 1)).isInstanceOf(ResultMessage.class);

        client.close();
    }

    // ==================== Permission Callback Tests ====================

    @SuppressWarnings("null")
    @Test
    void testClientWithCanUseToolCallback() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> {
            callbackInvoked.set(true);
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        };

        var options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .build();

        // Just verify options are created correctly
        assertThat(options.canUseTool()).isNotNull();
    }

    @SuppressWarnings("null")
    @Test
    void testCanUseToolWithPermissionPromptToolNameThrows() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture
                .completedFuture(new PermissionResultAllow());

        var options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .permissionPromptToolName("custom")
                .build();

        MockTransport mockTransport = createMockTransport();
        var client = new ClaudeSDKClient(options, mockTransport);

        assertThatThrownBy(client::connect)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canUseTool callback cannot be used with permissionPromptToolName");
        client.close();
    }

    // ==================== Model and Permission Mode Tests ====================

    @Test
    void testSetPermissionMode() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        // Should not throw
        client.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        client.close();
    }

    @Test
    void testSetModel() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        // Should not throw
        client.setModel("claude-sonnet-4-5");

        client.close();
    }

    // ==================== MCP Control Tests ====================

    @Test
    void testReconnectMcpServer() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        // Should not throw
        client.reconnectMcpServer("my-server");

        // Verify control request was sent with correct subtype and serverName
        boolean found = mockTransport.getWrittenData().stream().anyMatch(
                d -> d.contains("\"subtype\":\"mcp_reconnect\"") && d.contains("\"serverName\":\"my-server\""));
        assertThat(found).isTrue();

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testReconnectMcpServerNotConnected() {
        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), createMockTransport());

        assertThatThrownBy(() -> client.reconnectMcpServer("my-server"))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");

        client.close();
    }

    @Test
    void testToggleMcpServer_disabled() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        client.toggleMcpServer("my-server", false);

        boolean found = mockTransport.getWrittenData().stream().anyMatch(d -> d.contains("\"subtype\":\"mcp_toggle\"")
                && d.contains("\"serverName\":\"my-server\"")
                && d.contains("\"enabled\":false"));
        assertThat(found).isTrue();

        client.close();
    }

    @Test
    void testToggleMcpServer_enabled() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        client.toggleMcpServer("other-server", true);

        boolean found = mockTransport.getWrittenData().stream().anyMatch(d -> d.contains("\"subtype\":\"mcp_toggle\"")
                && d.contains("\"serverName\":\"other-server\"")
                && d.contains("\"enabled\":true"));
        assertThat(found).isTrue();

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testToggleMcpServerNotConnected() {
        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), createMockTransport());

        assertThatThrownBy(() -> client.toggleMcpServer("my-server", true))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");

        client.close();
    }

    @Test
    void testStopTask() {
        MockTransport mockTransport = createMockTransport();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        client.stopTask("task-abc123");

        boolean found = mockTransport.getWrittenData().stream()
                .anyMatch(d -> d.contains("\"subtype\":\"stop_task\"") && d.contains("\"task_id\":\"task-abc123\""));
        assertThat(found).isTrue();

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testStopTaskNotConnected() {
        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), createMockTransport());

        assertThatThrownBy(() -> client.stopTask("task-abc123"))
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");

        client.close();
    }

    @Test
    void testGetMcpStatus() {
        MockTransport mockTransport = createMockTransport();
        mockTransport.setMcpStatusResponseData(Map.of(
                "mcpServers", List.of(
                        Map.of("name", "my-server", "status", "connected"),
                        Map.of("name", "failed-server", "status", "failed", "error", "Connection refused"))));

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        McpStatusResponse status = client.getMcpStatus();

        assertThat(status).isNotNull();
        assertThat(status.mcpServers()).hasSize(2);
        assertThat(status.mcpServers().get(0).name()).isEqualTo("my-server");
        assertThat(status.mcpServers().get(1).name()).isEqualTo("failed-server");
        assertThat(status.mcpServers().get(1).error()).isEqualTo("Connection refused");

        client.close();
    }

    @SuppressWarnings("null")
    @Test
    void testGetMcpStatusNotConnected() {
        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), createMockTransport());

        assertThatThrownBy(client::getMcpStatus)
                .isInstanceOf(CLIConnectionException.class)
                .hasMessageContaining("Not connected");

        client.close();
    }

    // ==================== Streaming Input Tests ====================

    @Test
    void testConnectWithIterableMessages() {
        // Java equivalent of Python's test_connect_with_async_iterable
        // Tests sending multiple messages via query() with an Iterator

        MockTransport mockTransport = createMockTransport();
        mockTransport.addAssistantMessage("Processed all messages");
        mockTransport.addResultMessage();

        // Create an iterator that yields multiple messages
        List<Map<String, Object>> messageList = new ArrayList<>();
        messageList.add(Map.of(
                "type", "user",
                "session_id", "default",
                "message", Map.of(
                        "role", "user",
                        "content", "First message")));
        messageList.add(Map.of(
                "type", "user",
                "session_id", "default",
                "message", Map.of(
                        "role", "user",
                        "content", "Second message")));

        Iterator<Map<String, Object>> messageIterator = messageList.iterator();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        // Use query() with iterator to send multiple messages
        client.query(messageIterator);

        assertThat(client.isConnected()).isTrue();

        // Verify query() accepted the iterator (implementation detail: messages are
        // queued asynchronously)
        // The key test is that query(Iterator) method exists and doesn't throw
        assertThat(mockTransport.getWrittenData()).isNotEmpty();

        client.close();
    }

    @Test
    void testQueryWithIterableMessages() {
        // Java equivalent of Python's test_query_with_async_iterable
        // Tests query() method with an Iterator of messages

        MockTransport mockTransport = createMockTransport();
        mockTransport.addAssistantMessage("Response to both messages");
        mockTransport.addResultMessage();

        // Create an iterator that yields multiple messages
        List<Map<String, Object>> messageList = new ArrayList<>();
        messageList.add(Map.of(
                "type", "user",
                "session_id", "default",
                "message", Map.of(
                        "role", "user",
                        "content", "First query")));
        messageList.add(Map.of(
                "type", "user",
                "session_id", "default",
                "message", Map.of(
                        "role", "user",
                        "content", "Second query")));

        Iterator<Map<String, Object>> messageIterator = messageList.iterator();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();
        client.query(messageIterator);

        // Consume response
        Iterable<Message> messages = client.receiveResponse();
        List<Message> messagesList = new ArrayList<>();
        messages.forEach(messagesList::add);

        // Should have at least assistant message and result
        assertThat(messagesList).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messagesList).anyMatch(m -> m instanceof AssistantMessage);
        assertThat(messagesList).anyMatch(m -> m instanceof ResultMessage);

        client.close();
    }

    @Test
    void testConcurrentSendAndReceive() {
        // Java equivalent of Python's test_concurrent_send_receive
        // Tests sending messages from one thread while receiving from another

        MockTransport mockTransport = createMockTransport();
        mockTransport.addAssistantMessage("Response 1");
        mockTransport.addAssistantMessage("Response 2");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect();

        // Send first message
        client.sendMessage("Message 1");

        AtomicBoolean receivedFirstMessage = new AtomicBoolean(false);
        AtomicBoolean sentSecondMessage = new AtomicBoolean(false);

        // Receive messages in a separate thread
        Thread receiveThread = Thread.startVirtualThread(() -> {
            try {
                Iterator<Message> messages = client.receiveMessages();
                while (messages.hasNext()) {
                    Message msg = messages.next();
                    if (msg instanceof AssistantMessage && !receivedFirstMessage.get()) {
                        receivedFirstMessage.set(true);

                        // Send second message while receiving
                        if (!sentSecondMessage.get()) {
                            client.sendMessage("Message 2");
                            sentSecondMessage.set(true);
                        }
                    } else if (msg instanceof ResultMessage) {
                        break;
                    }
                }
            } catch (Exception e) {
                // Expected in test environment
            }
        });

        // Wait for receive thread to complete
        try {
            receiveThread.join(5000); // 5 second timeout
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify concurrent operations completed
        assertThat(receivedFirstMessage.get()).isTrue();
        assertThat(sentSecondMessage.get()).isTrue();

        client.close();
    }

    // ============ query(Iterator) is repeatable, like Python's ============

    /**
     * Reads the {@code message.content} of every user frame the client wrote,
     * in write order.
     */
    @SuppressWarnings("null")
    private static List<String> writtenPrompts(MockTransport transport) {
        ObjectMapper mapper = new ObjectMapper();
        List<String> prompts = new ArrayList<>();
        for (String line : transport.getWrittenData()) {
            try {
                Map<?, ?> frame = mapper.readValue(line, Map.class);
                if ("user".equals(frame.get("type")) && frame.get("message") instanceof Map<?, ?> m) {
                    prompts.add(String.valueOf(m.get("content")));
                }
            } catch (Exception ignored) {
                // Control frames and other traffic are not of interest here.
            }
        }
        return prompts;
    }

    private static Map<String, Object> userFrame(String content) {
        return Map.of("type", "user", "message", Map.of("role", "user", "content", content));
    }

    @SuppressWarnings("null")
    @Test
    void queryWithIterator_doesNotCloseStdin_soItCanBeCalledAgain() {
        // Regression: this used to hand the iterator to QueryHandler.streamInput,
        // which closes stdin once the iterator is exhausted. The CLI then exited
        // and any further write failed with "ProcessTransport is not ready for
        // writing", making the only origin-stamping API single-shot. Python's
        // ClaudeSDKClient.query() writes straight to the transport and keeps
        // stdin open, so the call is repeatable across a session.
        MockTransport mockTransport = createMockTransport();

        try (var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport)) {
            client.connect();

            client.query(List.of(userFrame("first")).iterator());
            client.query(List.of(userFrame("second")).iterator());
            client.query("third");

            assertThat(mockTransport.getEndInputCalls())
                    .as("client queries must never close the CLI's stdin")
                    .isZero();
            assertThat(writtenPrompts(mockTransport))
                    .containsExactly("first", "second", "third");
        }
    }

    @SuppressWarnings("null")
    @Test
    void queryWithIterator_defaultsSessionIdWithoutMutatingCallerMaps() {
        MockTransport mockTransport = createMockTransport();

        Map<String, Object> noSession = new HashMap<>(userFrame("no session"));
        Map<String, Object> ownSession = new HashMap<>(userFrame("own session"));
        ownSession.put("session_id", "explicit-session");

        try (var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport)) {
            client.connect();
            client.query(List.of(noSession, ownSession).iterator());

            ObjectMapper mapper = new ObjectMapper();
            List<String> sessionIds = new ArrayList<>();
            for (String line : mockTransport.getWrittenData()) {
                try {
                    Map<?, ?> frame = mapper.readValue(line, Map.class);
                    if ("user".equals(frame.get("type"))) {
                        sessionIds.add(String.valueOf(frame.get("session_id")));
                    }
                } catch (Exception ignored) {
                    // Not a user frame.
                }
            }
            // Missing session_id is filled in; an explicit one is left alone.
            assertThat(sessionIds).containsExactly("default", "explicit-session");
        }

        // The caller's maps are copied, never rewritten in place.
        assertThat(noSession).doesNotContainKey("session_id");
        assertThat(ownSession).containsEntry("session_id", "explicit-session");
    }

    @Test
    void queryWithIterator_honoursExplicitSessionIdArgument() {
        MockTransport mockTransport = createMockTransport();

        try (var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport)) {
            client.connect();
            client.query(List.of(userFrame("hi")).iterator(), "session-abc");

            assertThat(mockTransport.getWrittenData())
                    .anyMatch(line -> line.contains("\"session_id\":\"session-abc\""));
        }
    }

    @Test
    void queryWithIterator_preservesExtraFieldsSuchAsOrigin() {
        // Stamping `origin` is the reason to reach for this overload at all, so
        // unmodelled keys must survive the write untouched.
        MockTransport mockTransport = createMockTransport();

        Map<String, Object> stamped = new HashMap<>(userFrame("hi"));
        stamped.put("origin", Map.of("kind", "human"));

        try (var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport)) {
            client.connect();
            client.query(List.of(stamped).iterator());

            assertThat(mockTransport.getWrittenData())
                    .anyMatch(line -> line.contains("\"origin\":{\"kind\":\"human\"}"));
        }
    }

    // ==================== Mock Transport Implementation ====================

    /**
     * Mock transport for testing ClaudeSDKClient.
     */

    static class MockTransport implements Transport {

        private final List<String> writtenData = Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.BlockingQueue<Map<String, Object>> messagesToReturn = new java.util.concurrent.LinkedBlockingQueue<>();
        private boolean connected = false;
        private boolean closed = false;
        private boolean interruptSupported = false;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final AtomicBoolean endSent = new AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicInteger endInputCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile Map<String, Object> mcpStatusResponseData = null;

        void setMcpStatusResponseData(Map<String, Object> data) {
            this.mcpStatusResponseData = data;
        }

        void addControlResponse(String requestId, String subtype) {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "control_response");
            Map<String, Object> inner = new HashMap<>();
            inner.put("subtype", "success");
            inner.put("request_id", requestId);
            if ("initialize".equals(subtype)) {
                inner.put("commands", List.of());
                inner.put("output_style", "default");
            } else if ("mcp_status".equals(subtype) && mcpStatusResponseData != null) {
                inner.put("response", mcpStatusResponseData);
            }
            response.put("response", inner);
            messagesToReturn.offer(response);
        }

        void addAssistantMessage(String text) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "assistant");
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "assistant");
            inner.put("content", List.of(Map.of("type", "text", "text", text)));
            inner.put("model", "claude-sonnet-4-5");
            message.put("message", inner);
            messagesToReturn.offer(message);
        }

        void addUserMessage(String text) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "user");
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "user");
            inner.put("content", text);
            message.put("message", inner);
            messagesToReturn.offer(message);
        }

        void addResultMessage() {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "result");
            message.put("subtype", "success");
            message.put("duration_ms", 1000);
            message.put("duration_api_ms", 800);
            message.put("is_error", false);
            message.put("num_turns", 1);
            message.put("session_id", "test");
            message.put("total_cost_usd", 0.001);
            messagesToReturn.offer(message);
        }

        void setInterruptSupported(boolean supported) {
            this.interruptSupported = supported;
        }

        List<String> getWrittenData() {
            return new ArrayList<>(writtenData);
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public void connect() throws CLIConnectionException {
            connected = true;
            closed = false;
            endSent.set(false);
        }

        @Override
        public void write(String data) throws CLIConnectionException {
            writtenData.add(data);

            // Check for control requests and generate matching responses
            if (data.contains("\"type\":\"control_request\"")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) objectMapper.readValue(data, Map.class);
                    String requestId = (String) message.get("request_id");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = (Map<String, Object>) message.get("request");
                    String subtype = (String) request.get("subtype");

                    if ("interrupt".equals(subtype) && !interruptSupported) {
                        return; // Don't respond if interrupt not supported
                    }

                    // Generate matching response
                    addControlResponse(requestId, subtype);
                } catch (Exception e) {
                    // Ignore parsing errors
                }
            }
        }

        @Override
        public Iterator<Map<String, Object>> readMessages() {
            return new Iterator<>() {
                private Map<String, Object> nextMessage = null;

                @Override
                public boolean hasNext() {
                    if (nextMessage != null) {
                        return true;
                    }
                    if (closed || endSent.get()) {
                        return false;
                    }
                    try {
                        // Block waiting for messages (like a real subprocess would)
                        // But use poll with timeout so we can check closed flag periodically
                        nextMessage = messagesToReturn.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);

                        // If null was returned, check if closed before returning false
                        if (nextMessage == null) {
                            // Check closed flag again
                            if (closed || endSent.get()) {
                                return false;
                            }
                            // No message yet but not closed - keep waiting (return true to keep loop alive)
                            // Actually, return false but the caller should call hasNext() again
                            // This is where the issue is - we need to keep trying
                            // Let's check one more time with a blocking call
                            nextMessage = messagesToReturn.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        }

                        if (nextMessage != null && "end".equals(nextMessage.get("type"))) {
                            endSent.set(true);
                            return false;
                        }

                        // If still no message but not closed, keep the iterator alive by returning true
                        // and returning null from next() would throw NoSuchElementException
                        // Instead, we should keep checking - this is the issue!
                        // The solution: keep polling in a loop until we get a message or are closed
                        while (nextMessage == null && !closed && !endSent.get()) {
                            nextMessage = messagesToReturn.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (nextMessage != null && "end".equals(nextMessage.get("type"))) {
                                endSent.set(true);
                                return false;
                            }
                        }

                        return nextMessage != null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }

                @Override
                public Map<String, Object> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    Map<String, Object> msg = nextMessage;
                    nextMessage = null;
                    return msg;
                }
            };
        }

        @Override
        public void endInput() {
            endInputCalls.incrementAndGet();
        }

        /** How many times stdin was closed — must stay 0 for client queries. */
        int getEndInputCalls() {
            return endInputCalls.get();
        }

        @Override
        public boolean isReady() {
            return connected && !closed;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }

    }

}
