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
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ThinkingBlock;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;

class MessageParserTest {

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

    @SuppressWarnings("null")
    @Test
    void parseMessage_unknownType_throwsException() {
        Map<String, Object> data = Map.of("type", "unknown");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("Unknown message type: unknown");
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
        Map<String, Object> data = Map.of("type", "unknown", "some", "data");

        assertThatThrownBy(() -> MessageParser.parse(data))
                .isInstanceOf(MessageParseException.class)
                .satisfies(e -> {
                    MessageParseException mpe = (MessageParseException) e;
                    assertThat(mpe.getData()).containsEntry("type", "unknown");
                    assertThat(mpe.getData()).containsEntry("some", "data");
                });
    }

    // ==================== Result Message with Usage Tests ====================

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

}
