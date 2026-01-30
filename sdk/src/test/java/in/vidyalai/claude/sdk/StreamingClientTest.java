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

        void addControlResponse(String requestId, String subtype) {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "control_response");
            Map<String, Object> inner = new HashMap<>();
            inner.put("subtype", "success");
            inner.put("request_id", requestId);
            if ("initialize".equals(subtype)) {
                inner.put("commands", List.of());
                inner.put("output_style", "default");
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
            // No-op for mock
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
