package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.exceptions.MessageParseException;
import in.vidyalai.claude.sdk.internal.MessageParser;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.AssistantMessageError;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.ConversationResetMessage;
import in.vidyalai.claude.sdk.types.message.DeferredToolUse;
import in.vidyalai.claude.sdk.types.message.HookEventMessage;
import in.vidyalai.claude.sdk.types.message.ImageBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;
import in.vidyalai.claude.sdk.types.message.RateLimitEvent;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.ServerToolResultBlock;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.message.TaskNotificationMessage;
import in.vidyalai.claude.sdk.types.message.TaskNotificationStatus;
import in.vidyalai.claude.sdk.types.message.TaskProgressMessage;
import in.vidyalai.claude.sdk.types.message.TaskStartedMessage;
import in.vidyalai.claude.sdk.types.message.TaskUpdatedMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;

/**
 * Parser leniency where the Python SDK passes a value through and this SDK
 * used to throw, plus the pinned cases where this SDK is deliberately more
 * lenient than Python. Inputs are JSON text, parsed as the transport does, so
 * explicit nulls arrive as they would from the CLI.
 */
@SuppressWarnings("null")
class MessageParserLeniencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> json(String text) {
        try {
            return MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Message parse(String text) {
        return MessageParser.parse(json(text));
    }

    private static AssistantMessage assistantWithBlock(String blockJson) {
        return (AssistantMessage) parse("{\"type\":\"assistant\",\"message\":{\"model\":\"m\",\"content\":["
                + blockJson + "]}}");
    }

    private static final String NOTIFICATION = "{\"type\":\"system\",\"subtype\":\"task_notification\","
            + "\"task_id\":\"t1\",\"status\":%s,\"output_file\":\"/tmp/out\",\"summary\":\"done\","
            + "\"uuid\":\"u1\",\"session_id\":\"s1\"}";

    @Nested
    class TaskNotificationStatusLeniency {

        @Test
        void unknownStatusReadsAsNullAndKeepsWireValue() {
            Message message = parse(String.format(NOTIFICATION, "\"cancelled\""));

            TaskNotificationMessage notification = (TaskNotificationMessage) message;
            assertThat(notification.status()).isNull();
            assertThat(notification.statusValue()).isEqualTo("cancelled");
            assertThat(notification.data()).containsEntry("status", "cancelled");
            assertThat(notification.taskId()).isEqualTo("t1");
        }

        @Test
        void nonStringStatusReadsAsNull() {
            TaskNotificationMessage notification = (TaskNotificationMessage) parse(
                    String.format(NOTIFICATION, "3"));

            assertThat(notification.status()).isNull();
            assertThat(notification.statusValue()).isNull();
            assertThat(notification.data()).containsEntry("status", 3);
        }

        @Test
        void explicitNullStatusReadsAsNull() {
            TaskNotificationMessage notification = (TaskNotificationMessage) parse(
                    String.format(NOTIFICATION, "null"));

            assertThat(notification.status()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = { "completed", "failed", "stopped" })
        void knownStatusesStillResolve(String status) {
            TaskNotificationMessage notification = (TaskNotificationMessage) parse(
                    String.format(NOTIFICATION, "\"" + status + "\""));

            assertThat(notification.status()).isEqualTo(TaskNotificationStatus.fromValue(status));
            assertThat(notification.statusValue()).isEqualTo(status);
        }

        @Test
        void absentStatusStillFails() {
            assertThatThrownBy(() -> parse("{\"type\":\"system\",\"subtype\":\"task_notification\","
                    + "\"task_id\":\"t1\",\"output_file\":\"/tmp/out\",\"summary\":\"done\","
                    + "\"uuid\":\"u1\",\"session_id\":\"s1\"}"))
                    .isInstanceOf(MessageParseException.class)
                    .hasMessageContaining("status");
        }

        @Test
        void fromValueOrNull() {
            assertThat(TaskNotificationStatus.fromValueOrNull("stopped")).isEqualTo(TaskNotificationStatus.STOPPED);
            assertThat(TaskNotificationStatus.fromValueOrNull("cancelled")).isNull();
            assertThat(TaskNotificationStatus.fromValueOrNull(null)).isNull();
        }

    }

    @Nested
    class ToolUseResult {

        private UserMessage userWith(String toolUseResultJson) {
            return (UserMessage) parse("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
                    + "[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"x\"}]},"
                    + "\"tool_use_result\":" + toolUseResultJson + "}");
        }

        @Test
        void stringIsKeptRawAndStillWrapped() {
            UserMessage user = userWith("\"Error: file not found\"");

            assertThat(user.toolUseResultRaw()).isEqualTo("Error: file not found");
            assertThat(user.toolUseResult()).isEqualTo(Map.of("unknown", "Error: file not found"));
        }

        @Test
        void listIsKeptRawAndWrappedAsReceivedNotAsToString() {
            UserMessage user = userWith("[{\"type\":\"text\",\"text\":\"hi\"}]");

            List<Map<String, Object>> expected = List.of(Map.of("type", "text", "text", "hi"));
            assertThat(user.toolUseResultRaw()).isEqualTo(expected);
            assertThat(user.toolUseResult()).containsOnlyKeys("unknown");
            assertThat(user.toolUseResult().get("unknown")).isEqualTo(expected);
        }

        @Test
        void numberAndBooleanAreKeptRaw() {
            assertThat(userWith("42").toolUseResultRaw()).isEqualTo(42);
            assertThat(userWith("42").toolUseResult()).isEqualTo(Map.of("unknown", 42));
            assertThat(userWith("true").toolUseResultRaw()).isEqualTo(true);
        }

        @Test
        void objectIsTheSameOnBothAccessors() {
            UserMessage user = userWith("{\"filePath\":\"/a.py\",\"userModified\":false}");

            assertThat(user.toolUseResult()).containsEntry("filePath", "/a.py");
            assertThat(user.toolUseResultRaw()).isSameAs(user.toolUseResult());
        }

        @Test
        void absentIsNullOnBoth() {
            UserMessage user = (UserMessage) parse("{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}");

            assertThat(user.toolUseResult()).isNull();
            assertThat(user.toolUseResultRaw()).isNull();
        }

        @Test
        void legacyConstructorsSetRawToTheMap() {
            Map<String, Object> result = Map.of("k", "v");

            assertThat(new UserMessage("c", null, null, result).toolUseResultRaw()).isSameAs(result);
            assertThat(new UserMessage("c", null, null, result, null).toolUseResultRaw()).isSameAs(result);
        }

    }

    @Nested
    class AssistantError {

        private AssistantMessage assistantWithError(String errorJson) {
            return (AssistantMessage) parse("{\"type\":\"assistant\",\"message\":{\"model\":\"<synthetic>\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"API Error\"}]},\"error\":" + errorJson + "}");
        }

        @Test
        void unknownStringKeepsWireValue() {
            AssistantMessage message = assistantWithError("\"overloaded\"");

            assertThat(message.error()).isEqualTo(AssistantMessageError.UNKNOWN);
            assertThat(message.errorRaw()).isEqualTo("overloaded");
        }

        @Test
        void knownStringKeepsWireValue() {
            AssistantMessage message = assistantWithError("\"rate_limit\"");

            assertThat(message.error()).isEqualTo(AssistantMessageError.RATE_LIMIT);
            assertThat(message.errorRaw()).isEqualTo("rate_limit");
        }

        @Test
        void nonStringErrorDoesNotThrow() {
            AssistantMessage message = assistantWithError("{\"type\":\"api_error\",\"status\":529}");

            assertThat(message.error()).isEqualTo(AssistantMessageError.UNKNOWN);
            assertThat(message.errorRaw()).isEqualTo(Map.of("type", "api_error", "status", 529));
        }

        @Test
        void absentOrNullErrorIsNullOnBoth() {
            AssistantMessage message = assistantWithError("null");

            assertThat(message.error()).isNull();
            assertThat(message.errorRaw()).isNull();
        }

        @Test
        void legacyConstructorDerivesRawFromTheEnum() {
            AssistantMessage message = new AssistantMessage(List.of(), "m", null,
                    AssistantMessageError.BILLING_ERROR);

            assertThat(message.errorRaw()).isEqualTo("billing_error");
            assertThat(new AssistantMessage(List.of(), "m", null, null).errorRaw()).isNull();
        }

    }

    /** The typed system records are siblings of SystemMessage, not subtypes: pins the corrected Javadoc. */
    @Test
    void typedSystemMessagesAreNotSystemMessageInstances() {
        List<String> frames = List.of(
                "{\"type\":\"system\",\"subtype\":\"task_started\",\"task_id\":\"t\",\"description\":\"d\","
                        + "\"uuid\":\"u\",\"session_id\":\"s\"}",
                "{\"type\":\"system\",\"subtype\":\"task_progress\",\"task_id\":\"t\",\"description\":\"d\","
                        + "\"usage\":{\"total_tokens\":1},\"uuid\":\"u\",\"session_id\":\"s\"}",
                String.format(NOTIFICATION, "\"completed\""),
                "{\"type\":\"system\",\"subtype\":\"task_updated\",\"task_id\":\"t\",\"patch\":{}}",
                "{\"type\":\"system\",\"subtype\":\"hook_started\",\"hook_event\":\"Stop\"}",
                "{\"type\":\"system\",\"subtype\":\"mirror_error\",\"error\":\"boom\"}");
        List<Class<?>> expected = List.of(TaskStartedMessage.class, TaskProgressMessage.class,
                TaskNotificationMessage.class, TaskUpdatedMessage.class, HookEventMessage.class,
                MirrorErrorMessage.class);

        for (int i = 0; i < frames.size(); i++) {
            Message message = parse(frames.get(i));
            assertThat(message).isInstanceOf(expected.get(i)).isNotInstanceOf(SystemMessage.class);
            assertThat(message.type()).isEqualTo("system");
        }
    }

    @Nested
    class ContentBlockLeniency {

        @Test
        void advisorResultWithNonObjectContentDoesNotThrow() {
            AssistantMessage message = assistantWithBlock(
                    "{\"type\":\"advisor_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":\"redacted\"}");

            ServerToolResultBlock block = (ServerToolResultBlock) message.content().get(0);
            assertThat(block.toolUseId()).isEqualTo("srvtoolu_1");
            assertThat(block.content()).isNull();
        }

        @Test
        void toolUseWithNonObjectInputDoesNotThrow() {
            AssistantMessage message = assistantWithBlock(
                    "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Bash\",\"input\":\"ls\"}");

            ToolUseBlock block = (ToolUseBlock) message.content().get(0);
            assertThat(block.name()).isEqualTo("Bash");
            assertThat(block.input()).isNull();
        }

        @Test
        void toolResultWithNonBooleanIsErrorDoesNotThrow() {
            UserMessage message = (UserMessage) parse("{\"type\":\"user\",\"message\":{\"content\":["
                    + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"x\",\"is_error\":\"true\"}]}}");

            ToolResultBlock block = (ToolResultBlock) message.contentAsBlocks().get(0);
            assertThat(block.content()).isEqualTo("x");
            assertThat(block.isError()).isNull();
        }

        @Test
        void imageWithNonObjectSourceDoesNotThrow() {
            UserMessage message = (UserMessage) parse("{\"type\":\"user\",\"message\":{\"content\":["
                    + "{\"type\":\"image\",\"source\":\"data:...\"}]}}");

            assertThat(((ImageBlock) message.contentAsBlocks().get(0)).source()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = { "1", "null", "true" })
        void blockWithNonStringTypeIsDropped(String type) {
            AssistantMessage message = assistantWithBlock(
                    "{\"type\":" + type + ",\"text\":\"x\"},{\"type\":\"text\",\"text\":\"kept\"}");

            assertThat(message.content()).hasSize(1);
            assertThat(((TextBlock) message.content().get(0)).text()).isEqualTo("kept");
        }

        @Test
        void blockWithoutTypeStillFails() {
            assertThatThrownBy(() -> assistantWithBlock("{\"text\":\"x\"}"))
                    .isInstanceOf(MessageParseException.class)
                    .hasMessageContaining("type");
        }

        /** Deliberate: Python raises on a missing block field; this SDK reads it as null. */
        @Test
        void missingBlockFieldReadsAsNull() {
            AssistantMessage message = assistantWithBlock("{\"type\":\"text\"}");

            ContentBlock block = message.content().get(0);
            assertThat(block).isInstanceOf(TextBlock.class);
            assertThat(((TextBlock) block).text()).isNull();
        }

    }

    @Nested
    class ExplicitNullOnRequiredField {

        @Test
        void taskStartedDescription() {
            TaskStartedMessage message = (TaskStartedMessage) parse("{\"type\":\"system\",\"subtype\":\"task_started\","
                    + "\"task_id\":\"t1\",\"description\":null,\"uuid\":\"u1\",\"session_id\":\"s1\"}");

            assertThat(message.description()).isNull();
            assertThat(message.taskId()).isEqualTo("t1");
        }

        @Test
        void taskProgressUsage() {
            TaskProgressMessage message = (TaskProgressMessage) parse("{\"type\":\"system\","
                    + "\"subtype\":\"task_progress\",\"task_id\":\"t1\",\"description\":\"d\",\"usage\":null,"
                    + "\"uuid\":\"u1\",\"session_id\":\"s1\"}");

            assertThat(message.usage()).isNull();
        }

        @Test
        void systemSubtype() {
            SystemMessage message = (SystemMessage) parse("{\"type\":\"system\",\"subtype\":null}");

            assertThat(message.subtype()).isNull();
            assertThat(message.data()).containsKey("subtype");
            assertThat(message.initData()).isNull();
        }

        @Test
        void resultDurationsAndFlags() {
            ResultMessage message = (ResultMessage) parse("{\"type\":\"result\",\"subtype\":\"success\","
                    + "\"duration_ms\":null,\"duration_api_ms\":null,\"is_error\":null,\"num_turns\":null,"
                    + "\"session_id\":null}");

            assertThat(message.durationMs()).isZero();
            assertThat(message.durationApiMs()).isZero();
            assertThat(message.isError()).isFalse();
            assertThat(message.numTurns()).isZero();
            assertThat(message.sessionId()).isNull();
        }

        @Test
        void assistantModel() {
            AssistantMessage message = (AssistantMessage) parse(
                    "{\"type\":\"assistant\",\"message\":{\"model\":null,\"content\":[]}}");

            assertThat(message.model()).isNull();
        }

        @Test
        void streamEventEvent() {
            StreamEvent event = (StreamEvent) parse(
                    "{\"type\":\"stream_event\",\"uuid\":\"u1\",\"session_id\":\"s1\",\"event\":null}");

            assertThat(event.event()).isNull();
            assertThat(event.eventType()).isNull();
        }

        @Test
        void conversationResetUuid() {
            ConversationResetMessage reset = (ConversationResetMessage) parse("{\"type\":\"conversation_reset\","
                    + "\"new_conversation_id\":\"c2\",\"uuid\":null,\"session_id\":\"s1\"}");

            assertThat(reset.uuid()).isNull();
            assertThat(reset.newConversationId()).isEqualTo("c2");
        }

        @Test
        void rateLimitStatus() {
            RateLimitEvent event = (RateLimitEvent) parse("{\"type\":\"rate_limit_event\",\"uuid\":\"u1\","
                    + "\"session_id\":\"s1\",\"rate_limit_info\":{\"status\":null}}");

            assertThat(event.rateLimitInfo().status()).isNull();
        }

        @Test
        void absentKeysStillFail() {
            assertThatThrownBy(() -> parse("{\"type\":\"stream_event\",\"uuid\":\"u1\",\"session_id\":\"s1\"}"))
                    .isInstanceOf(MessageParseException.class);
            assertThatThrownBy(() -> parse("{\"type\":\"assistant\",\"message\":{\"content\":[]}}"))
                    .isInstanceOf(MessageParseException.class)
                    .hasMessageContaining("model");
            assertThatThrownBy(() -> parse("{\"type\":\"rate_limit_event\",\"uuid\":\"u1\","
                    + "\"session_id\":\"s1\",\"rate_limit_info\":{}}"))
                    .isInstanceOf(MessageParseException.class)
                    .hasMessageContaining("status");
        }

        /** Python indexes into a null rate_limit_info and fails; so does this SDK. */
        @Test
        void nullRateLimitInfoStillFails() {
            assertThatThrownBy(() -> parse("{\"type\":\"rate_limit_event\",\"uuid\":\"u1\","
                    + "\"session_id\":\"s1\",\"rate_limit_info\":null}"))
                    .isInstanceOf(MessageParseException.class);
        }

    }

    @Nested
    class OptionalFieldOfWrongType {

        @Test
        void assistantFields() {
            AssistantMessage message = (AssistantMessage) parse("{\"type\":\"assistant\",\"parent_tool_use_id\":5,"
                    + "\"uuid\":7,\"session_id\":[],\"message\":{\"model\":\"m\",\"content\":[],\"id\":1,"
                    + "\"stop_reason\":1,\"usage\":\"n/a\"}}");

            assertThat(message.parentToolUseId()).isNull();
            assertThat(message.uuid()).isNull();
            assertThat(message.sessionId()).isNull();
            assertThat(message.messageId()).isNull();
            assertThat(message.stopReason()).isNull();
            assertThat(message.usage()).isNull();
        }

        @Test
        void userFields() {
            UserMessage message = (UserMessage) parse(
                    "{\"type\":\"user\",\"uuid\":5,\"parent_tool_use_id\":{},\"message\":{\"content\":\"hi\"}}");

            assertThat(message.uuid()).isNull();
            assertThat(message.parentToolUseId()).isNull();
            assertThat(message.contentAsString()).isEqualTo("hi");
        }

        @Test
        void resultFields() {
            ResultMessage message = (ResultMessage) parse("{\"type\":\"result\",\"subtype\":\"success\","
                    + "\"duration_ms\":1,\"duration_api_ms\":1,\"is_error\":false,\"num_turns\":1,"
                    + "\"session_id\":\"s1\",\"stop_reason\":3,\"result\":{\"x\":1},\"terminal_reason\":5,"
                    + "\"uuid\":9,\"usage\":[],\"permission_denials\":\"none\"}");

            assertThat(message.stopReason()).isNull();
            assertThat(message.result()).isNull();
            assertThat(message.terminalReason()).isNull();
            assertThat(message.uuid()).isNull();
            assertThat(message.usage()).isNull();
            assertThat(message.permissionDenials()).isNull();
        }

        @Test
        void rateLimitOverageDisabledReason() {
            RateLimitEvent event = (RateLimitEvent) parse("{\"type\":\"rate_limit_event\",\"uuid\":\"u1\","
                    + "\"session_id\":\"s1\",\"rate_limit_info\":{\"status\":\"allowed\","
                    + "\"overageDisabledReason\":5}}");

            assertThat(event.rateLimitInfo().overageDisabledReason()).isNull();
            assertThat(event.rateLimitInfo().raw()).containsEntry("overageDisabledReason", 5);
        }

        @Test
        void streamEventFields() {
            StreamEvent event = (StreamEvent) parse("{\"type\":\"stream_event\",\"uuid\":\"u1\","
                    + "\"session_id\":\"s1\",\"event\":\"ping\",\"parent_tool_use_id\":1}");

            assertThat(event.event()).isNull();
            assertThat(event.parentToolUseId()).isNull();
        }

        @Test
        void taskFields() {
            TaskNotificationMessage notification = (TaskNotificationMessage) parse("{\"type\":\"system\","
                    + "\"subtype\":\"task_notification\",\"task_id\":\"t1\",\"status\":\"completed\","
                    + "\"output_file\":\"/o\",\"summary\":\"s\",\"uuid\":\"u1\",\"session_id\":\"s1\","
                    + "\"tool_use_id\":4,\"usage\":\"x\"}");
            assertThat(notification.toolUseId()).isNull();
            assertThat(notification.usage()).isNull();

            TaskProgressMessage progress = (TaskProgressMessage) parse("{\"type\":\"system\","
                    + "\"subtype\":\"task_progress\",\"task_id\":\"t1\",\"description\":\"d\",\"usage\":\"x\","
                    + "\"uuid\":\"u1\",\"session_id\":\"s1\",\"last_tool_name\":2}");
            assertThat(progress.usage()).isNull();
            assertThat(progress.lastToolName()).isNull();

            TaskStartedMessage started = (TaskStartedMessage) parse("{\"type\":\"system\","
                    + "\"subtype\":\"task_started\",\"task_id\":\"t1\",\"description\":\"d\",\"uuid\":\"u1\","
                    + "\"session_id\":\"s1\",\"task_type\":[]}");
            assertThat(started.taskType()).isNull();
        }

        @Test
        void systemSubtypeOfWrongTypeIsGeneric() {
            SystemMessage message = (SystemMessage) parse("{\"type\":\"system\",\"subtype\":5}");

            assertThat(message.subtype()).isNull();
            assertThat(message.data()).containsEntry("subtype", 5);
        }

    }

    @Nested
    class MessageType {

        /** Python's match falls through to its unknown-type branch for {@code {"type":1}}. */
        @ParameterizedTest
        @ValueSource(strings = { "{\"type\":1}", "{\"type\":true}", "{\"type\":{}}", "{\"type\":[\"user\"]}" })
        void nonStringTypeIsSkipped(String frame) {
            assertThat(parse(frame)).isNull();
        }

        /** Deliberate: Python raises "missing type" for an empty string; this SDK skips it as unknown. */
        @Test
        void emptyTypeIsSkipped() {
            assertThat(parse("{\"type\":\"\"}")).isNull();
        }

        @Test
        void nullTypeStillFails() {
            assertThatThrownBy(() -> parse("{\"type\":null}"))
                    .isInstanceOf(MessageParseException.class)
                    .hasMessageContaining("type");
        }

    }

    @Nested
    class DeliberateLeniency {

        /** Python raises on {@code data["message"]["content"]}; this SDK reads absent content as null. */
        @Test
        void userMessageWithoutContent() {
            UserMessage message = (UserMessage) parse("{\"type\":\"user\",\"message\":{\"role\":\"user\"}}");

            assertThat(message.content()).isNull();
        }

        /** Python indexes id/name/input and raises; this SDK drops a malformed deferred_tool_use and defaults input. */
        @Test
        void deferredToolUse() {
            String base = "{\"type\":\"result\",\"subtype\":\"success\",\"duration_ms\":1,\"duration_api_ms\":1,"
                    + "\"is_error\":false,\"num_turns\":1,\"session_id\":\"s1\",\"deferred_tool_use\":";
            ResultMessage noInput = (ResultMessage) parse(base + "{\"id\":\"t\",\"name\":\"Bash\"}}");
            assertThat(noInput.deferredToolUse()).isEqualTo(new DeferredToolUse("t", "Bash", Map.of()));

            ResultMessage noName = (ResultMessage) parse(base + "{\"id\":\"t\"}}");
            assertThat(noName.deferredToolUse()).isNull();
        }

        /** A bare-string {@code errors} is wrapped in a list on purpose. */
        @Test
        void errorsAsBareString() {
            ResultMessage message = (ResultMessage) parse("{\"type\":\"result\",\"subtype\":\"error_during_execution\","
                    + "\"duration_ms\":1,\"duration_api_ms\":1,\"is_error\":true,\"num_turns\":1,"
                    + "\"session_id\":\"s1\",\"errors\":\"boom\"}");

            assertThat(message.errors()).containsExactly("boom");
        }

    }

}
