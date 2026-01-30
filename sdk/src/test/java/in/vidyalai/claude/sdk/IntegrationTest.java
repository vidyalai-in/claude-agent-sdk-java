package in.vidyalai.claude.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.transport.Transport;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.ThinkingBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Integration tests for Claude SDK.
 * Equivalent to Python's test_client.py and test_integration.py
 */
class IntegrationTest {

    // ==================== Query Function Tests ====================

    @Test
    void testQuerySinglePrompt() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("4");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("What is 2+2?");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();
        assertThat(messages.stream().anyMatch(m -> m instanceof AssistantMessage)).isTrue();

        // Find the assistant message with "4"
        Optional<AssistantMessage> answer = messages.stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> (AssistantMessage) m)
                .filter(a -> a.getTextContent().contains("4"))
                .findFirst();

        assertThat(answer).isPresent();

        client.close();
    }

    @Test
    void testQueryWithOptions() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("Hello!");
        mockTransport.addResultMessage();

        var options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read", "Write"))
                .systemPrompt("You are helpful")
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .maxTurns(5)
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect("Hi");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();
        assertThat(messages.stream().anyMatch(m -> m instanceof AssistantMessage)).isTrue();

        client.close();
    }

    @Test
    void testQueryWithCwd() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("Done");
        mockTransport.addResultMessage();

        var options = ClaudeAgentOptions.builder()
                .cwd(java.nio.file.Path.of("/tmp"))
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect("test");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();

        client.close();
    }

    // ==================== Response Handling Tests ====================

    @Test
    void testReceiveResponseIncludesResultMessage() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("Working on it...");
        mockTransport.addAssistantMessage("Done!");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Do something");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should include both assistant messages and the result
        assertThat(messages.stream().filter(m -> m instanceof AssistantMessage).count())
                .isGreaterThanOrEqualTo(1);

        // Last message should be ResultMessage
        assertThat(messages.get(messages.size() - 1)).isInstanceOf(ResultMessage.class);

        client.close();
    }

    @Test
    void testReceiveResponseWithToolUse() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessageWithToolUse("Let me read that file", "Read", Map.of("file_path", "/test.txt"));
        mockTransport.addToolResultMessage("toolu_123", "File contents here");
        mockTransport.addAssistantMessage("I found the file contents.");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Read /test.txt");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();

        // Check that we have at least one assistant message
        assertThat(messages.stream().anyMatch(m -> m instanceof AssistantMessage)).isTrue();

        client.close();
    }

    // ==================== Error Handling Tests ====================

    @Test
    void testErrorResultMessage() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addErrorResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Do something bad");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should have error result
        assertThat(messages.stream().anyMatch(m -> m instanceof ResultMessage)).isTrue();

        Optional<ResultMessage> result = messages.stream()
                .filter(m -> m instanceof ResultMessage)
                .map(m -> (ResultMessage) m)
                .findFirst();

        assertThat(result).isPresent();
        assertThat(result.get().isError()).isTrue();

        client.close();
    }

    // ==================== Multi-turn Conversation Tests ====================

    @Test
    void testMultiTurnConversation() {
        MockQueryTransport mockTransport = new MockQueryTransport();

        // First response
        mockTransport.addAssistantMessage("I understand. What would you like to know?");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Hello, I have a question");

        // First turn
        List<Message> firstResponse = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            firstResponse.add(msg);
        }

        assertThat(firstResponse).isNotEmpty();

        // Add second response
        mockTransport.addAssistantMessage("The answer is 42.");
        mockTransport.addResultMessage();

        // Second turn
        client.sendMessage("What is the meaning of life?");

        List<Message> secondResponse = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            secondResponse.add(msg);
        }

        assertThat(secondResponse).isNotEmpty();

        client.close();
    }

    // ==================== Stream Event Tests ====================

    @Test
    void testStreamEventsHandling() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addStreamEvent("content_block_delta", Map.of("index", 0));
        mockTransport.addAssistantMessage("Complete response");
        mockTransport.addResultMessage();

        var options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect("Test streaming");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should handle stream events without errors
        assertThat(messages).isNotEmpty();

        client.close();
    }

    // ==================== System Message Tests ====================

    @Test
    void testSystemMessageHandling() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addSystemMessage("init", Map.of("version", "1.0"));
        mockTransport.addAssistantMessage("Hello!");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Hi");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should handle system messages without errors
        assertThat(messages).isNotEmpty();

        client.close();
    }

    // ==================== Continuation Option Tests ====================

    @Test
    void testContinuationOption() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("Continuing from previous conversation");
        mockTransport.addResultMessage();

        var options = ClaudeAgentOptions.builder()
                .continueConversation(true)
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect("Continue");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();
        assertThat(options.continueConversation()).isTrue();

        client.close();
    }

    @Test
    void testResumeOption() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("Resumed from session");
        mockTransport.addResultMessage();

        var options = ClaudeAgentOptions.builder()
                .resume("session-123")
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect("Resume");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();
        assertThat(options.resume()).isEqualTo("session-123");

        client.close();
    }

    // ==================== Max Budget Option Tests ====================

    @Test
    void testMaxBudgetUsdOption() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessage("Starting to read...");
        mockTransport.addBudgetExceededResultMessage();

        var options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(0.0001)
                .build();

        var client = new ClaudeSDKClient(options, mockTransport);
        client.connect("Read the readme");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should have 2 messages (assistant + result)
        assertThat(messages).hasSize(2);

        // Verify result message
        assertThat(messages.get(1)).isInstanceOf(ResultMessage.class);
        ResultMessage result = (ResultMessage) messages.get(1);
        assertThat(result.subtype()).isEqualTo("error_max_budget_usd");
        assertThat(result.isError()).isFalse();
        assertThat(result.totalCostUsd()).isEqualTo(0.0002);

        client.close();
    }

    // ==================== User Message Tests ====================

    @Test
    void testUserMessageHandling() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addUserMessage("User input");
        mockTransport.addAssistantMessage("Response to user");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Start");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Should include user message
        assertThat(messages.stream().anyMatch(m -> m instanceof UserMessage)).isTrue();

        client.close();
    }

    // ==================== Thinking Block Tests ====================

    @Test
    void testAssistantMessageWithThinking() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessageWithThinking("Let me think...", "sig-123", "Here's my answer");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("What is 2+2?");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        assertThat(messages).isNotEmpty();

        // Find assistant message with thinking
        Optional<AssistantMessage> assistant = messages.stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> (AssistantMessage) m)
                .filter(a -> a.content().stream().anyMatch(c -> c instanceof ThinkingBlock))
                .findFirst();

        assertThat(assistant).isPresent();
        assertThat(assistant.get().content()).hasSize(2);

        client.close();
    }

    // ==================== Subagent Tests ====================

    @Test
    void testMessagesInsideSubagent() {
        MockQueryTransport mockTransport = new MockQueryTransport();
        mockTransport.addAssistantMessageWithParentToolUse("Response from subagent", "parent-tool-123");
        mockTransport.addResultMessage();

        var client = new ClaudeSDKClient(ClaudeAgentOptions.defaults(), mockTransport);
        client.connect("Start subagent");

        List<Message> messages = new ArrayList<>();
        for (Message msg : client.receiveResponse()) {
            messages.add(msg);
        }

        // Find assistant message with parent_tool_use_id
        Optional<AssistantMessage> assistant = messages.stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> (AssistantMessage) m)
                .filter(a -> a.parentToolUseId() != null)
                .findFirst();

        assertThat(assistant).isPresent();
        assertThat(assistant.get().parentToolUseId()).isEqualTo("parent-tool-123");

        client.close();
    }

    // ==================== Mock Transport Implementation ====================

    /**
     * Mock transport for testing query function flows.
     */
    static class MockQueryTransport implements Transport {

        private final List<String> writtenData = Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.BlockingQueue<Map<String, Object>> messagesToReturn = new java.util.concurrent.LinkedBlockingQueue<>();
        private boolean connected = false;
        private boolean closed = false;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final java.util.concurrent.atomic.AtomicBoolean endSent = new java.util.concurrent.atomic.AtomicBoolean(
                false);

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

        void addAssistantMessageWithToolUse(String text, String toolName, Map<String, Object> toolInput) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "assistant");
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "assistant");
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", text));
            content.add(Map.of("type", "tool_use", "id", "toolu_123", "name", toolName, "input", toolInput));
            inner.put("content", content);
            inner.put("model", "claude-sonnet-4-5");
            message.put("message", inner);
            messagesToReturn.offer(message);
        }

        void addToolResultMessage(String toolUseId, String result) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "user");
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "user");
            inner.put("content", List.of(Map.of(
                    "type", "tool_result",
                    "tool_use_id", toolUseId,
                    "content", result)));
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
            message.put("session_id", "test-session");
            message.put("total_cost_usd", 0.001);
            messagesToReturn.offer(message);
        }

        void addErrorResultMessage() {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "result");
            message.put("subtype", "error");
            message.put("duration_ms", 500);
            message.put("duration_api_ms", 400);
            message.put("is_error", true);
            message.put("num_turns", 1);
            message.put("session_id", "test-session");
            message.put("total_cost_usd", 0.0005);
            messagesToReturn.offer(message);
        }

        void addStreamEvent(String eventType, Map<String, Object> data) {
            Map<String, Object> fullData = new HashMap<>(data);
            fullData.put("type", eventType);

            Map<String, Object> event = new HashMap<>();
            event.put("type", "stream_event");
            event.put("uuid", "uuid-123");
            event.put("session_id", "test-session");
            event.put("event", fullData);
            messagesToReturn.offer(event);
        }

        void addSystemMessage(String subtype, Map<String, Object> data) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "system");
            message.put("subtype", subtype);
            message.put("data", data);
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

        void addBudgetExceededResultMessage() {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "result");
            message.put("subtype", "error_max_budget_usd");
            message.put("duration_ms", 500);
            message.put("duration_api_ms", 400);
            message.put("is_error", false);
            message.put("num_turns", 1);
            message.put("session_id", "test-session-budget");
            message.put("total_cost_usd", 0.0002);
            message.put("usage", Map.of("input_tokens", 100, "output_tokens", 50));
            messagesToReturn.offer(message);
        }

        void addAssistantMessageWithThinking(String thinking, String signature, String text) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "assistant");
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "assistant");
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "thinking", "thinking", thinking, "signature", signature));
            content.add(Map.of("type", "text", "text", text));
            inner.put("content", content);
            inner.put("model", "claude-opus-4-5");
            message.put("message", inner);
            messagesToReturn.offer(message);
        }

        void addAssistantMessageWithParentToolUse(String text, String parentToolUseId) {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "assistant");
            Map<String, Object> inner = new HashMap<>();
            inner.put("role", "assistant");
            inner.put("content", List.of(Map.of("type", "text", "text", text)));
            inner.put("model", "claude-sonnet-4-5");
            message.put("message", inner);
            message.put("parent_tool_use_id", parentToolUseId);
            messagesToReturn.offer(message);
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
                        nextMessage = messagesToReturn.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (nextMessage != null && "end".equals(nextMessage.get("type"))) {
                            endSent.set(true);
                            return false;
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
