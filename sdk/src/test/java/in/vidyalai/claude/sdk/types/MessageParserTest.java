package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.exceptions.MessageParseException;
import in.vidyalai.claude.sdk.internal.MessageParser;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.RateLimitEvent;
import in.vidyalai.claude.sdk.types.message.RateLimitStatus;
import in.vidyalai.claude.sdk.types.message.RateLimitType;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.message.TaskNotificationMessage;
import in.vidyalai.claude.sdk.types.message.TaskNotificationStatus;
import in.vidyalai.claude.sdk.types.message.TaskProgressMessage;
import in.vidyalai.claude.sdk.types.message.TaskStartedMessage;
import in.vidyalai.claude.sdk.types.message.TaskUsage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ThinkingBlock;
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;
import in.vidyalai.claude.sdk.types.message.ServerToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ServerToolUseBlock;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;

class MessageParserTest {

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withStringContent() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "uuid", "test-uuid",
                "message", Map.of(
                        "role", "user",
                        "content", "Hello, Claude!"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.type()).isEqualTo("user");
        assertThat(userMessage.uuid()).isEqualTo("test-uuid");
        assertThat(userMessage.contentAsString()).isEqualTo("Hello, Claude!");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withTextBlock() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-sonnet-4-5",
                        "content", List.of(
                                Map.of("type", "text", "text", "Hello!"))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.type()).isEqualTo("assistant");
        assertThat(assistantMessage.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(assistantMessage.content()).hasSize(1);
        assertThat(assistantMessage.content().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) assistantMessage.content().get(0)).text()).isEqualTo("Hello!");
        assertThat(assistantMessage.getTextContent()).isEqualTo("Hello!");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withToolUseBlock() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-sonnet-4-5",
                        "content", List.of(
                                Map.of(
                                        "type", "tool_use",
                                        "id", "tool-123",
                                        "name", "Bash",
                                        "input",
                                        Map.of("command", "ls -la")))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.hasToolUse()).isTrue();

        ContentBlock block = assistantMessage.content().get(0);
        assertThat(block).isInstanceOf(ToolUseBlock.class);
        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertThat(toolUse.id()).isEqualTo("tool-123");
        assertThat(toolUse.name()).isEqualTo("Bash");
        assertThat(toolUse.input()).containsEntry("command", "ls -la");
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage() {
        Map<String, Object> data = Map.of(
                "type", "result",
                "subtype", "success",
                "duration_ms", 1234,
                "duration_api_ms", 1000,
                "is_error", false,
                "num_turns", 1,
                "session_id", "session-123",
                "total_cost_usd", 0.0025,
                "result", "Task completed");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage resultMessage = (ResultMessage) message;
        assertThat(resultMessage.type()).isEqualTo("result");
        assertThat(resultMessage.subtype()).isEqualTo("success");
        assertThat(resultMessage.durationMs()).isEqualTo(1234);
        assertThat(resultMessage.durationApiMs()).isEqualTo(1000);
        assertThat(resultMessage.isError()).isFalse();
        assertThat(resultMessage.numTurns()).isEqualTo(1);
        assertThat(resultMessage.sessionId()).isEqualTo("session-123");
        assertThat(resultMessage.totalCostUsd()).isEqualTo(0.0025);
        assertThat(resultMessage.result()).isEqualTo("Task completed");
    }

    @SuppressWarnings("null")
    @Test
    void parseSystemMessage() {
        Map<String, Object> data = Map.of(
                "type", "system",
                "subtype", "init",
                "extra_field", "extra_value");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(SystemMessage.class);
        SystemMessage systemMessage = (SystemMessage) message;
        assertThat(systemMessage.type()).isEqualTo("system");
        assertThat(systemMessage.subtype()).isEqualTo("init");
        assertThat(systemMessage.data()).containsEntry("extra_field", "extra_value");
    }

    @SuppressWarnings("null")
    @Test
    void parseStreamEvent() {
        Map<String, Object> data = Map.of(
                "type", "stream_event",
                "uuid", "event-123",
                "session_id", "session-456",
                "event", Map.of("type", "content_block_delta", "delta", "some text"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(StreamEvent.class);
        StreamEvent streamEvent = (StreamEvent) message;
        assertThat(streamEvent.type()).isEqualTo("stream_event");
        assertThat(streamEvent.uuid()).isEqualTo("event-123");
        assertThat(streamEvent.sessionId()).isEqualTo("session-456");
        assertThat(streamEvent.eventType()).isEqualTo("content_block_delta");
    }

    @Test
    void parseMessage_unknownType_returnsNull() {
        Map<String, Object> data = Map.of("type", "unknown_type");

        Message result = MessageParser.parse(data);
        assertThat(result).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void parseMessage_rateLimitEvent_returnsTypedEvent() {
        Map<String, Object> data = Map.of(
                "type", "rate_limit_event",
                "rate_limit_info", Map.of(
                        "status", "allowed_warning",
                        "resetsAt", 1700000000,
                        "rateLimitType", "five_hour",
                        "utilization", 0.85,
                        "isUsingOverage", false),
                "uuid", "550e8400-e29b-41d4-a716-446655440000",
                "session_id", "test-session-id");

        Message result = MessageParser.parse(data);
        assertThat(result).isInstanceOf(RateLimitEvent.class);
        RateLimitEvent event = (RateLimitEvent) result;
        assertThat(event.uuid()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(event.sessionId()).isEqualTo("test-session-id");
        assertThat(event.rateLimitInfo().status()).isEqualTo(RateLimitStatus.ALLOWED_WARNING);
        assertThat(event.rateLimitInfo().resetsAt()).isEqualTo(1700000000L);
        assertThat(event.rateLimitInfo().rateLimitType()).isEqualTo(RateLimitType.FIVE_HOUR);
        assertThat(event.rateLimitInfo().utilization()).isEqualTo(0.85);
    }

    @SuppressWarnings("null")
    @Test
    void parseMessage_rateLimitEventRejected_returnsTypedEvent() {
        Map<String, Object> data = Map.of(
                "type", "rate_limit_event",
                "rate_limit_info", Map.of(
                        "status", "rejected",
                        "resetsAt", 1700003600,
                        "rateLimitType", "seven_day",
                        "isUsingOverage", false),
                "uuid", "660e8400-e29b-41d4-a716-446655440001",
                "session_id", "test-session-id");

        Message result = MessageParser.parse(data);
        assertThat(result).isInstanceOf(RateLimitEvent.class);
        RateLimitEvent event = (RateLimitEvent) result;
        assertThat(event.rateLimitInfo().status()).isEqualTo(RateLimitStatus.REJECTED);
    }

    @Test
    void parseMessage_futureSomeEventType_returnsNull() {
        Map<String, Object> data = Map.of(
                "type", "some_future_event_type",
                "uuid", "770e8400-e29b-41d4-a716-446655440002",
                "session_id", "test-session-id");

        Message result = MessageParser.parse(data);
        assertThat(result).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void parseMessage_missingType_throwsException() {
        Map<String, Object> data = Map.of("content", "test");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("missing 'type' field");
    }

    @SuppressWarnings("null")
    @Test
    void parseMessage_nullData_throwsException() {
        assertThatThrownBy(() -> MessageParser.parse(null))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("expected dict, got null");
    }

    // ==================== Additional User Message Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withContentBlocks() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "Hello"))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.contentAsBlocks()).hasSize(1);
        assertThat(userMessage.contentAsBlocks().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) userMessage.contentAsBlocks().get(0)).text()).isEqualTo("Hello");
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withToolUseBlock() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "Let me read this file"),
                                Map.of(
                                        "type", "tool_use",
                                        "id", "tool_456",
                                        "name", "Read",
                                        "input",
                                        Map.of("file_path", "/example.txt")))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.contentAsBlocks()).hasSize(2);
        assertThat(userMessage.contentAsBlocks().get(0)).isInstanceOf(TextBlock.class);
        assertThat(userMessage.contentAsBlocks().get(1)).isInstanceOf(ToolUseBlock.class);
        ToolUseBlock toolUse = (ToolUseBlock) userMessage.contentAsBlocks().get(1);
        assertThat(toolUse.id()).isEqualTo("tool_456");
        assertThat(toolUse.name()).isEqualTo("Read");
        assertThat(toolUse.input()).containsEntry("file_path", "/example.txt");
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withToolResultBlock() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", "tool_789",
                                        "content", "File contents here"))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.contentAsBlocks()).hasSize(1);
        assertThat(userMessage.contentAsBlocks().get(0)).isInstanceOf(ToolResultBlock.class);
        ToolResultBlock toolResult = (ToolResultBlock) userMessage.contentAsBlocks().get(0);
        assertThat(toolResult.toolUseId()).isEqualTo("tool_789");
        assertThat(toolResult.content()).isEqualTo("File contents here");
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withToolResultError() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", "tool_error",
                                        "content", "File not found",
                                        "is_error", true))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.contentAsBlocks()).hasSize(1);
        ToolResultBlock toolResult = (ToolResultBlock) userMessage.contentAsBlocks().get(0);
        assertThat(toolResult.toolUseId()).isEqualTo("tool_error");
        assertThat(toolResult.content()).isEqualTo("File not found");
        assertThat(toolResult.isError()).isTrue();
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_toolResultInlineContentPreserved() {
        // Full tool-result content is preserved when the CLI passes it inline
        String inlineContent = "x".repeat(1000);
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", "toolu_01ABC",
                                        "content", inlineContent,
                                        "is_error", false))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        ToolResultBlock toolResult = (ToolResultBlock) userMessage.contentAsBlocks().get(0);
        assertThat(toolResult.content()).isEqualTo(inlineContent);
        assertThat(toolResult.content().toString()).doesNotStartWith("<persisted-output>");
        assertThat(toolResult.isError()).isFalse();
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_toolResultPersistedOutputDetectable() {
        // After a layer-2 spill, content starts with '<persisted-output>' —
        // callers can detect this and warn users
        String persistedContent =
                "<persisted-output>\n"
                        + "Output too large (73.0KB). Full output saved to: /tmp/.claude/tool-results/abc123.txt\n"
                        + "\nPreview (first 2KB):\n" + "x".repeat(2000) + "\n...\n</persisted-output>";

        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", "toolu_01DEF",
                                        "content", persistedContent,
                                        "is_error", false))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        ToolResultBlock toolResult = (ToolResultBlock) userMessage.contentAsBlocks().get(0);
        // Content is preserved as-is — callers can detect by prefix
        assertThat(toolResult.content().toString()).startsWith("<persisted-output>");
        // Persisted output is only a 2KB preview, not the full 73K
        assertThat(toolResult.content().toString().length()).isLessThan(50_000);
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withMixedContent() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "Here's what I found:"),
                                Map.of(
                                        "type", "tool_use",
                                        "id", "use_1",
                                        "name", "Search",
                                        "input", Map.of("query", "test")),
                                Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", "use_1",
                                        "content", "Search results"),
                                Map.of("type", "text", "text", "What do you think?"))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.contentAsBlocks()).hasSize(4);
        assertThat(userMessage.contentAsBlocks().get(0)).isInstanceOf(TextBlock.class);
        assertThat(userMessage.contentAsBlocks().get(1)).isInstanceOf(ToolUseBlock.class);
        assertThat(userMessage.contentAsBlocks().get(2)).isInstanceOf(ToolResultBlock.class);
        assertThat(userMessage.contentAsBlocks().get(3)).isInstanceOf(TextBlock.class);
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_insideSubagent() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "Hello"))),
                "parent_tool_use_id", "toolu_01Xrwd5Y13sEHtzScxR77So8");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.parentToolUseId()).isEqualTo("toolu_01Xrwd5Y13sEHtzScxR77So8");
    }

    // ==================== Additional Assistant Message Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withThinkingBlock() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-opus-4-5",
                        "content", List.of(
                                Map.of(
                                        "type", "thinking",
                                        "thinking",
                                        "I'm thinking about the answer...",
                                        "signature", "sig-123"),
                                Map.of("type", "text", "text", "Here's my response"))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.content()).hasSize(2);
        assertThat(assistantMessage.content().get(0)).isInstanceOf(ThinkingBlock.class);
        ThinkingBlock thinking = (ThinkingBlock) assistantMessage.content().get(0);
        assertThat(thinking.thinking()).isEqualTo("I'm thinking about the answer...");
        assertThat(thinking.signature()).isEqualTo("sig-123");
        assertThat(assistantMessage.content().get(1)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) assistantMessage.content().get(1)).text()).isEqualTo("Here's my response");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_insideSubagent() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-sonnet-4-5",
                        "content", List.of(
                                Map.of("type", "text", "text", "Hello"),
                                Map.of(
                                        "type", "tool_use",
                                        "id", "tool_123",
                                        "name", "Read",
                                        "input",
                                        Map.of("file_path", "/test.txt")))),
                "parent_tool_use_id", "toolu_01Xrwd5Y13sEHtzScxR77So8");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.parentToolUseId()).isEqualTo("toolu_01Xrwd5Y13sEHtzScxR77So8");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withoutError() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-opus-4-5-20251101",
                        "content", List.of(
                                Map.of("type", "text", "text", "Hello"))));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.error()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withAuthenticationError() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "<synthetic>",
                        "content", List.of(
                                Map.of("type", "text", "text", "Invalid API key · Fix external API key"))),
                "session_id", "test-session",
                "error", "authentication_failed");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.error()).isNotNull();
        assertThat(assistantMessage.error().getValue()).isEqualTo("authentication_failed");
        assertThat(assistantMessage.content()).hasSize(1);
        assertThat(assistantMessage.content().get(0)).isInstanceOf(TextBlock.class);
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withUnknownError() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "<synthetic>",
                        "content", List.of(
                                Map.of("type", "text", "text",
                                        "API Error: 500 {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Internal server error\"}}"))),
                "session_id", "test-session",
                "error", "unknown");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.error()).isNotNull();
        assertThat(assistantMessage.error().getValue()).isEqualTo("unknown");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withRateLimitError() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "<synthetic>",
                        "content", List.of(
                                Map.of("type", "text", "text", "Rate limit exceeded"))),
                "error", "rate_limit");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistantMessage = (AssistantMessage) message;
        assertThat(assistantMessage.error()).isNotNull();
        assertThat(assistantMessage.error().getValue()).isEqualTo("rate_limit");
    }

    // ==================== Additional Error Handling Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_missingFields_throwsException() {
        Map<String, Object> data = Map.of("type", "user");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("Missing required field");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_missingFields_throwsException() {
        Map<String, Object> data = Map.of("type", "assistant");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("Missing required field");
    }

    @SuppressWarnings("null")
    @Test
    void parseSystemMessage_missingFields_throwsException() {
        Map<String, Object> data = Map.of("type", "system");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("Missing required field");
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_missingFields_throwsException() {
        Map<String, Object> data = Map.of(
                "type", "result",
                "subtype", "success");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("Missing required field");
    }

    @SuppressWarnings("null")
    @Test
    void parseMessage_exceptionContainsData() {
        // Use a malformed known type (missing required fields) to trigger error
        Map<String, Object> data = Map.of("type", "assistant");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .satisfies(e -> {
                    MessageParseException mpe = (MessageParseException) e;
                    assertThat(mpe.getData()).containsEntry("type", "assistant");
                });
    }

    // ==================== Result Message with Usage Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_withUsage() {
        Map<String, Object> data = Map.of(
                "type", "result",
                "subtype", "success",
                "duration_ms", 1234,
                "duration_api_ms", 1000,
                "is_error", false,
                "num_turns", 1,
                "session_id", "session-123",
                "total_cost_usd", 0.0025,
                "usage", Map.of(
                        "input_tokens", 100,
                        "output_tokens", 50));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage resultMessage = (ResultMessage) message;
        assertThat(resultMessage.usage()).isNotNull();
        assertThat(resultMessage.usage()).containsEntry("input_tokens", 100);
        assertThat(resultMessage.usage()).containsEntry("output_tokens", 50);
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_withStructuredOutput() {
        Map<String, Object> data = Map.of(
                "type", "result",
                "subtype", "success",
                "duration_ms", 1234,
                "duration_api_ms", 1000,
                "is_error", false,
                "num_turns", 1,
                "session_id", "session-123",
                "total_cost_usd", 0.0025,
                "structured_output", Map.of("key", "value"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage resultMessage = (ResultMessage) message;
        assertThat(resultMessage.structuredOutput()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredOutput = (Map<String, Object>) resultMessage.structuredOutput();
        assertThat(structuredOutput).containsEntry("key", "value");
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_errorMaxBudget() {
        Map<String, Object> data = Map.of(
                "type", "result",
                "subtype", "error_max_budget_usd",
                "duration_ms", 500,
                "duration_api_ms", 400,
                "is_error", false,
                "num_turns", 1,
                "session_id", "test-session-budget",
                "total_cost_usd", 0.0002);

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage resultMessage = (ResultMessage) message;
        assertThat(resultMessage.subtype()).isEqualTo("error_max_budget_usd");
        assertThat(resultMessage.isError()).isFalse();
        assertThat(resultMessage.totalCostUsd()).isEqualTo(0.0002);
    }

    // ==================== Stream Event Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseStreamEvent_withParentToolUseId() {
        Map<String, Object> data = Map.of(
                "type", "stream_event",
                "uuid", "event-123",
                "session_id", "session-456",
                "event", Map.of("type", "content_block_delta", "delta", "some text"),
                "parent_tool_use_id", "tool-parent-123");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(StreamEvent.class);
        StreamEvent streamEvent = (StreamEvent) message;
        assertThat(streamEvent.parentToolUseId()).isEqualTo("tool-parent-123");
    }

    // ==================== Tool Use Result Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withToolUseResult() {
        Map<String, Object> toolResultData = Map.of(
                "filePath", "/path/to/file.py",
                "oldString", "old code",
                "newString", "new code",
                "originalFile", "full file contents",
                "structuredPatch", List.of(
                        Map.of(
                                "oldStart", 33,
                                "oldLines", 7,
                                "newStart", 33,
                                "newLines", 7,
                                "lines", List.of(
                                        "   # comment",
                                        "-      old line",
                                        "+      new line"))),
                "userModified", false,
                "replaceAll", false);

        Map<String, Object> data = Map.ofEntries(
                Map.entry("type", "user"),
                Map.entry("message", Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "tool_use_id",
                                        "toolu_vrtx_01KXWexk3NJdwkjWzPMGQ2F1",
                                        "type", "tool_result",
                                        "content",
                                        "The file has been updated.")))),
                Map.entry("session_id", "84afb479-17ae-49af-8f2b-666ac2530c3a"),
                Map.entry("uuid", "2ace3375-1879-48a0-a421-6bce25a9295a"),
                Map.entry("tool_use_result", toolResultData));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.toolUseResult()).isNotNull();
        assertThat(userMessage.toolUseResult()).isEqualTo(toolResultData);
        assertThat(userMessage.toolUseResult()).containsEntry("filePath", "/path/to/file.py");
        assertThat(userMessage.toolUseResult()).containsEntry("oldString", "old code");
        assertThat(userMessage.toolUseResult()).containsEntry("newString", "new code");
        assertThat(userMessage.uuid()).isEqualTo("2ace3375-1879-48a0-a421-6bce25a9295a");

        // Verify structured patch details
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structuredPatch = (List<Map<String, Object>>) userMessage.toolUseResult()
                .get("structuredPatch");
        assertThat(structuredPatch).hasSize(1);
        assertThat(structuredPatch.get(0)).containsEntry("oldStart", 33);
        assertThat(structuredPatch.get(0)).containsEntry("oldLines", 7);
        assertThat(structuredPatch.get(0)).containsEntry("newStart", 33);
        assertThat(structuredPatch.get(0)).containsEntry("newLines", 7);
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withStringContentAndToolUseResult() {
        Map<String, Object> toolResultData = Map.of(
                "filePath", "/path/to/file.py",
                "userModified", true);

        Map<String, Object> data = Map.of(
                "type", "user",
                "message", Map.of("content", "Simple string content"),
                "tool_use_result", toolResultData);

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.contentAsString()).isEqualTo("Simple string content");
        assertThat(userMessage.toolUseResult()).isNotNull();
        assertThat(userMessage.toolUseResult()).isEqualTo(toolResultData);
        assertThat(userMessage.toolUseResult()).containsEntry("filePath", "/path/to/file.py");
        assertThat(userMessage.toolUseResult()).containsEntry("userModified", true);
    }

    @SuppressWarnings("null")
    @Test
    void parseUserMessage_withoutToolUseResult() {
        Map<String, Object> data = Map.of(
                "type", "user",
                "uuid", "test-uuid",
                "message", Map.of(
                        "role", "user",
                        "content", "Hello, Claude!"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) message;
        assertThat(userMessage.toolUseResult()).isNull();
    }

    // ==================== Task Message Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseTaskStartedMessage() {
        Map<String, Object> data = new java.util.HashMap<>(Map.of(
                "type", "system",
                "subtype", "task_started",
                "task_id", "task-abc",
                "tool_use_id", "toolu_01",
                "description", "Reticulating splines",
                "task_type", "background",
                "uuid", "uuid-1",
                "session_id", "session-1"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(TaskStartedMessage.class);
        TaskStartedMessage started = (TaskStartedMessage) message;
        assertThat(started.taskId()).isEqualTo("task-abc");
        assertThat(started.description()).isEqualTo("Reticulating splines");
        assertThat(started.uuid()).isEqualTo("uuid-1");
        assertThat(started.sessionId()).isEqualTo("session-1");
        assertThat(started.toolUseId()).isEqualTo("toolu_01");
        assertThat(started.taskType()).isEqualTo("background");
    }

    @SuppressWarnings("null")
    @Test
    void parseTaskStartedMessage_optionalFieldsAbsent() {
        Map<String, Object> data = Map.of(
                "type", "system",
                "subtype", "task_started",
                "task_id", "task-abc",
                "description", "Working",
                "uuid", "uuid-1",
                "session_id", "session-1");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(TaskStartedMessage.class);
        TaskStartedMessage started = (TaskStartedMessage) message;
        assertThat(started.toolUseId()).isNull();
        assertThat(started.taskType()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void parseTaskProgressMessage() {
        Map<String, Object> data = new java.util.HashMap<>(Map.of(
                "type", "system",
                "subtype", "task_progress",
                "task_id", "task-abc",
                "tool_use_id", "toolu_01",
                "description", "Halfway there",
                "usage", Map.of("total_tokens", 1234, "tool_uses", 5, "duration_ms", 9876),
                "last_tool_name", "Read",
                "uuid", "uuid-2",
                "session_id", "session-1"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(TaskProgressMessage.class);
        TaskProgressMessage progress = (TaskProgressMessage) message;
        assertThat(progress.taskId()).isEqualTo("task-abc");
        assertThat(progress.description()).isEqualTo("Halfway there");
        assertThat(progress.lastToolName()).isEqualTo("Read");
        assertThat(progress.toolUseId()).isEqualTo("toolu_01");
        assertThat(progress.uuid()).isEqualTo("uuid-2");
        assertThat(progress.sessionId()).isEqualTo("session-1");
        TaskUsage usage = progress.usage();
        assertThat(usage.totalTokens()).isEqualTo(1234);
        assertThat(usage.toolUses()).isEqualTo(5);
        assertThat(usage.durationMs()).isEqualTo(9876);
    }

    @SuppressWarnings("null")
    @Test
    void parseTaskNotificationMessage() {
        Map<String, Object> data = new java.util.HashMap<>(Map.of(
                "type", "system",
                "subtype", "task_notification",
                "task_id", "task-abc",
                "tool_use_id", "toolu_01",
                "status", "completed",
                "output_file", "/tmp/out.md",
                "summary", "All done",
                "uuid", "uuid-3",
                "session_id", "session-1"));
        data.put("usage", Map.of("total_tokens", 2000, "tool_uses", 7, "duration_ms", 12345));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(TaskNotificationMessage.class);
        TaskNotificationMessage notif = (TaskNotificationMessage) message;
        assertThat(notif.taskId()).isEqualTo("task-abc");
        assertThat(notif.status()).isEqualTo(TaskNotificationStatus.COMPLETED);
        assertThat(notif.outputFile()).isEqualTo("/tmp/out.md");
        assertThat(notif.summary()).isEqualTo("All done");
        assertThat(notif.toolUseId()).isEqualTo("toolu_01");
        assertThat(notif.uuid()).isEqualTo("uuid-3");
        assertThat(notif.sessionId()).isEqualTo("session-1");
        assertThat(notif.usage()).isNotNull();
        assertThat(notif.usage().totalTokens()).isEqualTo(2000);
        assertThat(notif.usage().toolUses()).isEqualTo(7);
        assertThat(notif.usage().durationMs()).isEqualTo(12345);
    }

    @SuppressWarnings("null")
    @Test
    void parseTaskNotificationMessage_optionalFieldsAbsent() {
        Map<String, Object> data = Map.of(
                "type", "system",
                "subtype", "task_notification",
                "task_id", "task-abc",
                "status", "failed",
                "output_file", "/tmp/out.md",
                "summary", "Boom",
                "uuid", "uuid-3",
                "session_id", "session-1");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(TaskNotificationMessage.class);
        TaskNotificationMessage notif = (TaskNotificationMessage) message;
        assertThat(notif.status()).isEqualTo(TaskNotificationStatus.FAILED);
        assertThat(notif.usage()).isNull();
        assertThat(notif.toolUseId()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void taskMessageBaseFields_arePopulated() {
        Map<String, Object> data = new java.util.HashMap<>(Map.of(
                "type", "system",
                "subtype", "task_started",
                "task_id", "t1",
                "description", "desc",
                "uuid", "u1",
                "session_id", "s1"));

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(TaskStartedMessage.class);
        TaskStartedMessage started = (TaskStartedMessage) message;
        // Base fields (backward compat: subtype and data are populated)
        assertThat(started.subtype()).isEqualTo("task_started");
        assertThat(started.data()).containsEntry("task_id", "t1");
    }

    @SuppressWarnings("null")
    @Test
    void unknownSystemSubtype_yieldsGenericSystemMessage() {
        Map<String, Object> data = Map.of("type", "system", "subtype", "some_future_subtype", "foo", "bar");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(SystemMessage.class);
        assertThat(message).isNotInstanceOf(TaskStartedMessage.class);
        assertThat(message).isNotInstanceOf(TaskProgressMessage.class);
        assertThat(message).isNotInstanceOf(TaskNotificationMessage.class);
        assertThat(message.getClass()).isEqualTo(SystemMessage.class);
        SystemMessage sys = (SystemMessage) message;
        assertThat(sys.subtype()).isEqualTo("some_future_subtype");
    }

    // ==================== ResultMessage stop_reason Tests ====================

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_withStopReason() {
        Map<String, Object> data = Map.of(
                "type", "result",
                "subtype", "success",
                "duration_ms", 1000,
                "duration_api_ms", 500,
                "is_error", false,
                "num_turns", 2,
                "session_id", "session_123",
                "stop_reason", "end_turn",
                "result", "Done");

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage result = (ResultMessage) message;
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.result()).isEqualTo("Done");
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_withNullStopReason() {
        Map<String, Object> data = new java.util.HashMap<>(Map.of(
                "type", "result",
                "subtype", "error_max_turns",
                "duration_ms", 1000,
                "duration_api_ms", 500,
                "is_error", true,
                "num_turns", 10,
                "session_id", "session_123"));
        data.put("stop_reason", null);

        Message message = MessageParser.parse(data);

        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage result = (ResultMessage) message;
        assertThat(result.stopReason()).isNull();
    }

    // ==================== AssistantMessage usage field ====================

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withUsage() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "hi")),
                        "model", "claude-opus-4-5",
                        "usage", Map.of(
                                "input_tokens", 100,
                                "output_tokens", 50,
                                "cache_read_input_tokens", 2000,
                                "cache_creation_input_tokens", 500)));

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) message;
        assertThat(am.usage()).isNotNull();
        assertThat(am.usage()).containsEntry("input_tokens", 100);
        assertThat(am.usage()).containsEntry("output_tokens", 50);
        assertThat(am.usage()).containsEntry("cache_read_input_tokens", 2000);
        assertThat(am.usage()).containsEntry("cache_creation_input_tokens", 500);
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withoutUsage() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "hi")),
                        "model", "claude-opus-4-5"));

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) message;
        assertThat(am.usage()).isNull();
    }

    // --- Tests for new AssistantMessage fields (v0.1.51) ---

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withAllFields() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "assistant");
        data.put("session_id", "fdf2d90a-fd9e-4736-ae35-806edd13643f");
        data.put("uuid", "0dbd2453-1209-4fe9-bd51-4102f64e33df");
        data.put("message", Map.of(
                "content", List.of(Map.of("type", "text", "text", "Hello")),
                "model", "claude-sonnet-4-5-20250929",
                "id", "msg_01HRq7YZE3apPqSHydvG77Ve",
                "stop_reason", "end_turn",
                "usage", Map.of("input_tokens", 10, "output_tokens", 5)));

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) message;
        assertThat(am.messageId()).isEqualTo("msg_01HRq7YZE3apPqSHydvG77Ve");
        assertThat(am.stopReason()).isEqualTo("end_turn");
        assertThat(am.sessionId()).isEqualTo("fdf2d90a-fd9e-4736-ae35-806edd13643f");
        assertThat(am.uuid()).isEqualTo("0dbd2453-1209-4fe9-bd51-4102f64e33df");
        assertThat(am.usage()).isNotNull();
        assertThat(am.usage().get("input_tokens")).isEqualTo(10);
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_optionalFieldsAbsent() {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "hi")),
                        "model", "claude-opus-4-5"));

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) message;
        assertThat(am.messageId()).isNull();
        assertThat(am.stopReason()).isNull();
        assertThat(am.sessionId()).isNull();
        assertThat(am.uuid()).isNull();
    }

    // --- Tests for new ResultMessage fields (v0.1.51) ---

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_withModelUsage() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "result");
        data.put("subtype", "success");
        data.put("duration_ms", 3000);
        data.put("duration_api_ms", 2000);
        data.put("is_error", false);
        data.put("num_turns", 1);
        data.put("session_id", "fdf2d90a-fd9e-4736-ae35-806edd13643f");
        data.put("stop_reason", "end_turn");
        data.put("total_cost_usd", 0.0106);
        data.put("usage", Map.of("input_tokens", 3, "output_tokens", 24));
        data.put("result", "Hello");
        data.put("modelUsage", Map.of(
                "claude-sonnet-4-5-20250929", Map.of(
                        "inputTokens", 3,
                        "outputTokens", 24,
                        "costUSD", 0.0106)));
        data.put("permission_denials", List.of());
        data.put("uuid", "d379c496-f33a-4ea4-b920-3c5483baa6f7");

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage rm = (ResultMessage) message;
        assertThat(rm.modelUsage()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> sonnetUsage = (Map<String, Object>) rm.modelUsage().get("claude-sonnet-4-5-20250929");
        assertThat(sonnetUsage.get("costUSD")).isEqualTo(0.0106);
        assertThat(rm.permissionDenials()).isEmpty();
        assertThat(rm.uuid()).isEqualTo("d379c496-f33a-4ea4-b920-3c5483baa6f7");
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_optionalFieldsAbsent() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "result");
        data.put("subtype", "success");
        data.put("duration_ms", 1000);
        data.put("duration_api_ms", 500);
        data.put("is_error", false);
        data.put("num_turns", 1);
        data.put("session_id", "session_123");

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage rm = (ResultMessage) message;
        assertThat(rm.modelUsage()).isNull();
        assertThat(rm.permissionDenials()).isNull();
        assertThat(rm.errors()).isNull();
        assertThat(rm.uuid()).isNull();
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_withErrors() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "result");
        data.put("subtype", "error_during_execution");
        data.put("duration_ms", 5000);
        data.put("duration_api_ms", 3000);
        data.put("is_error", true);
        data.put("num_turns", 3);
        data.put("session_id", "session_456");
        data.put("errors", List.of(
                "Tool execution failed: permission denied",
                "Unable to write to /etc/hosts"));
        data.put("uuid", "err-uuid-789");

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage rm = (ResultMessage) message;
        assertThat(rm.errors()).containsExactly(
                "Tool execution failed: permission denied",
                "Unable to write to /etc/hosts");
        assertThat(rm.isError()).isTrue();
        assertThat(rm.subtype()).isEqualTo("error_during_execution");
        assertThat(rm.uuid()).isEqualTo("err-uuid-789");
    }

    @SuppressWarnings("null")
    @Test
    void parseResultMessage_successNoErrors() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "result");
        data.put("subtype", "success");
        data.put("duration_ms", 1000);
        data.put("duration_api_ms", 500);
        data.put("is_error", false);
        data.put("num_turns", 1);
        data.put("session_id", "session_789");
        data.put("result", "Task completed successfully");

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(ResultMessage.class);
        ResultMessage rm = (ResultMessage) message;
        assertThat(rm.errors()).isNull();
        assertThat(rm.result()).isEqualTo("Task completed successfully");
    }

    @SuppressWarnings("null")
    @Test
    void parseAssistantMessage_withServerToolUseBlock() throws Exception {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-sonnet-4-5",
                        "content", List.of(Map.of(
                                "type", "server_tool_use",
                                "id", "stu-1",
                                "name", "advisor",
                                "input", Map.of("query", "How are markets?")))));

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) message;
        assertThat(am.content()).hasSize(1);
        ContentBlock block = am.content().get(0);
        assertThat(block).isInstanceOf(ServerToolUseBlock.class);
        ServerToolUseBlock stu = (ServerToolUseBlock) block;
        assertThat(stu.id()).isEqualTo("stu-1");
        assertThat(stu.name()).isEqualTo("advisor");
        assertThat(stu.type()).isEqualTo("server_tool_use");
    }

    @SuppressWarnings({ "null" })
    @Test
    void parseAssistantMessage_withAdvisorToolResultBlock() throws Exception {
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-sonnet-4-5",
                        "content", List.of(Map.of(
                                "type", "advisor_tool_result",
                                "tool_use_id", "stu-1",
                                "content", Map.of(
                                        "type", "advisor_search_result",
                                        "results", List.of(Map.of("url", "https://x")))))));

        Message message = MessageParser.parse(data);
        AssistantMessage am = (AssistantMessage) message;
        ContentBlock block = am.content().get(0);
        assertThat(block).isInstanceOf(ServerToolResultBlock.class);
        ServerToolResultBlock str = (ServerToolResultBlock) block;
        assertThat(str.toolUseId()).isEqualTo("stu-1");
        assertThat(str.content()).containsKey("type");
        assertThat(str.type()).isEqualTo("server_tool_result");
    }

    @SuppressWarnings({ "null" })
    @Test
    void parseAssistantMessage_withRedactedAdvisorResultBlock() throws Exception {
        // External API users get advisor output as an encrypted blob in the content dict.
        Map<String, Object> data = Map.of(
                "type", "assistant",
                "message", Map.of(
                        "model", "claude-sonnet-4-5",
                        "content", List.of(Map.of(
                                "type", "advisor_tool_result",
                                "tool_use_id", "stu-1",
                                "content", Map.of(
                                        "type", "advisor_redacted_result",
                                        "encrypted_content", "EuYDCioIDhgC...")))));

        Message message = MessageParser.parse(data);
        AssistantMessage am = (AssistantMessage) message;
        ContentBlock block = am.content().get(0);
        assertThat(block).isInstanceOf(ServerToolResultBlock.class);
        ServerToolResultBlock str = (ServerToolResultBlock) block;
        assertThat(str.content()).containsEntry("type", "advisor_redacted_result");
        assertThat(str.content()).containsEntry("encrypted_content", "EuYDCioIDhgC...");
    }

    @SuppressWarnings("null")
    @Test
    void parseSystemMessage_mirrorErrorSubtype() throws Exception {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "system");
        data.put("subtype", "mirror_error");
        data.put("error", "S3 timeout");
        data.put("key", Map.of(
                "project_key", "my-project",
                "session_id", "abc12345-1234-1234-1234-123456789012"));

        Message message = MessageParser.parse(data);
        assertThat(message).isInstanceOf(MirrorErrorMessage.class);
        MirrorErrorMessage mem = (MirrorErrorMessage) message;
        assertThat(mem.subtype()).isEqualTo("mirror_error");
        assertThat(mem.error()).isEqualTo("S3 timeout");
        assertThat(mem.key()).isNotNull();
        assertThat(mem.key().projectKey()).isEqualTo("my-project");
        assertThat(mem.key().sessionId()).isEqualTo("abc12345-1234-1234-1234-123456789012");
    }

}
