package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.exceptions.MessageParseException;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.AssistantMessageError;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.RateLimitEvent;
import in.vidyalai.claude.sdk.types.message.RateLimitInfo;
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
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;

/**
 * Parser for converting raw JSON messages to typed Message objects.
 */
public final class MessageParser {

    private static final Logger logger = Logger.getLogger(MessageParser.class.getName());

    private MessageParser() {
        // Utility class
    }

    /**
     * Parses a raw message dictionary into a typed Message object.
     *
     * <p>
     * Returns {@code null} for unrecognized message types so that newer CLI
     * versions don't crash older SDK versions (forward compatibility).
     *
     * @param data the raw message data
     * @return the parsed Message, or {@code null} if the message type is unknown
     * @throws MessageParseException if parsing fails
     */
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException {
        if (data == null) {
            throw new MessageParseException("Invalid message data type (expected dict, got null)", null);
        }

        String messageType = (String) data.get("type");
        if (messageType == null) {
            throw new MessageParseException("Message missing 'type' field", data);
        }

        try {
            return switch (messageType) {
                case "user" -> parseUserMessage(data);
                case "assistant" -> parseAssistantMessage(data);
                case "system" -> parseSystemMessage(data);
                case "result" -> parseResultMessage(data);
                case "stream_event" -> parseStreamEvent(data);
                case "rate_limit_event" -> parseRateLimitEvent(data);
                default -> {
                    // Forward-compatible: skip unrecognized message types so newer
                    // CLI versions don't crash older SDK versions.
                    logger.fine("Skipping unknown message type: " + messageType);
                    yield null;
                }
            };
        } catch (MessageParseException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageParseException("Failed to parse message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static UserMessage parseUserMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String parentToolUseId = (String) data.get("parent_tool_use_id");
            String uuid = (String) data.get("uuid");
            Object tuResult = data.get("tool_use_result");
            Map<String, Object> toolUseResult = switch (tuResult) {
                case null -> null;
                case Map<?, ?> m -> (Map<String, Object>) m;
                case String s -> Map.of("unknown", s);
                default -> Map.of("unknown", tuResult.toString());
            };

            Map<String, Object> message = (Map<String, Object>) data.get("message");
            if (message == null) {
                throw new MessageParseException("Missing required field: 'message' in user message", data);
            }

            Object content = message.get("content");
            if (content instanceof List<?> contentList) {
                List<ContentBlock> blocks = new ArrayList<>();
                for (Object item : contentList) {
                    Map<String, Object> block = (Map<String, Object>) item;
                    blocks.add(parseContentBlock(block));
                }
                return new UserMessage(blocks, uuid, parentToolUseId, toolUseResult);
            }

            return new UserMessage(content, uuid, parentToolUseId, toolUseResult);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in user message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AssistantMessage parseAssistantMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String parentToolUseId = (String) data.get("parent_tool_use_id");

            Map<String, Object> message = (Map<String, Object>) data.get("message");
            if (message == null) {
                throw new MessageParseException("Missing required field: 'message' in assistant message", data);
            }

            List<?> contentList = (List<?>) message.get("content");
            if (contentList == null) {
                throw new MessageParseException("Missing 'content' field in assistant message", data);
            }

            List<ContentBlock> blocks = new ArrayList<>();
            for (Object item : contentList) {
                Map<String, Object> block = (Map<String, Object>) item;
                blocks.add(parseContentBlock(block));
            }

            String model = (String) message.get("model");
            if (model == null) {
                throw new MessageParseException("Missing 'model' field in assistant message", data);
            }

            // Error field is at top level of response, not inside message
            String errorStr = (String) data.get("error");
            AssistantMessageError error = ((errorStr != null)
                    ? AssistantMessageError.fromValue(errorStr)
                    : null);

            // Per-turn usage from the API (input_tokens, output_tokens, cache tokens, etc.)
            Map<String, Object> usage = (Map<String, Object>) message.get("usage");

            // Additional metadata fields
            String messageId = (String) message.get("id");
            String stopReason = (String) message.get("stop_reason");
            String sessionId = (String) data.get("session_id");
            String uuid = (String) data.get("uuid");

            return new AssistantMessage(blocks, model, parentToolUseId, error, usage,
                    messageId, stopReason, sessionId, uuid);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in assistant message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ContentBlock parseContentBlock(Map<String, Object> block) throws MessageParseException {
        String type = (String) block.get("type");
        if (type == null) {
            throw new MessageParseException("Missing 'type' field in content block", block);
        }

        return switch (type) {
            case "text" -> new TextBlock((String) block.get("text"));
            case "thinking" -> new ThinkingBlock(
                    (String) block.get("thinking"),
                    (String) block.get("signature"));
            case "tool_use" -> new ToolUseBlock(
                    (String) block.get("id"),
                    (String) block.get("name"),
                    (Map<String, Object>) block.get("input"));
            case "tool_result" -> new ToolResultBlock(
                    (String) block.get("tool_use_id"),
                    block.get("content"),
                    (Boolean) block.get("is_error"));
            default -> throw new MessageParseException("Unknown content block type: " + type, block);
        };
    }

    @SuppressWarnings("unchecked")
    private static Message parseSystemMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String subtype = (String) data.get("subtype");
            if (subtype == null) {
                throw new MessageParseException("Missing required field: 'subtype' in system message", data);
            }
            return switch (subtype) {
                case "task_started" -> new TaskStartedMessage(
                        subtype,
                        data,
                        getRequired(data, "task_id", String.class),
                        getRequired(data, "description", String.class),
                        getRequired(data, "uuid", String.class),
                        getRequired(data, "session_id", String.class),
                        (String) data.get("tool_use_id"),
                        (String) data.get("task_type"));
                case "task_progress" -> new TaskProgressMessage(
                        subtype,
                        data,
                        getRequired(data, "task_id", String.class),
                        getRequired(data, "description", String.class),
                        parseTaskUsage(getRequired(data, "usage", Map.class)),
                        getRequired(data, "uuid", String.class),
                        getRequired(data, "session_id", String.class),
                        (String) data.get("tool_use_id"),
                        (String) data.get("last_tool_name"));
                case "task_notification" -> new TaskNotificationMessage(
                        subtype,
                        data,
                        getRequired(data, "task_id", String.class),
                        TaskNotificationStatus.fromValue(getRequired(data, "status", String.class)),
                        getRequired(data, "output_file", String.class),
                        getRequired(data, "summary", String.class),
                        getRequired(data, "uuid", String.class),
                        getRequired(data, "session_id", String.class),
                        (String) data.get("tool_use_id"),
                        parseTaskUsageOptional((Map<String, Object>) data.get("usage")));
                default -> new SystemMessage(subtype, data);
            };
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in system message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResultMessage parseResultMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String subtype = getRequired(data, "subtype", String.class);
            int durationMs = getRequired(data, "duration_ms", Number.class).intValue();
            int durationApiMs = getRequired(data, "duration_api_ms", Number.class).intValue();
            boolean isError = getRequired(data, "is_error", Boolean.class);
            int numTurns = getRequired(data, "num_turns", Number.class).intValue();
            String sessionId = getRequired(data, "session_id", String.class);

            // Optional fields
            String stopReason = (String) data.get("stop_reason");
            Double totalCostUsd = null;
            if (data.get("total_cost_usd") instanceof Number n) {
                totalCostUsd = n.doubleValue();
            }

            Map<String, Object> usage = (Map<String, Object>) data.get("usage");
            String result = (String) data.get("result");
            Object structuredOutput = data.get("structured_output");

            // Additional metadata fields
            Map<String, Object> modelUsage = (Map<String, Object>) data.get("modelUsage");
            List<Object> permissionDenials = (List<Object>) data.get("permission_denials");
            List<String> errors = (List<String>) data.get("errors");
            String uuid = (String) data.get("uuid");

            return new ResultMessage(
                    subtype, durationMs, durationApiMs, isError, numTurns,
                    sessionId, stopReason, totalCostUsd, usage, result, structuredOutput,
                    modelUsage, permissionDenials, errors, uuid);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in result message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static RateLimitEvent parseRateLimitEvent(Map<String, Object> data) throws MessageParseException {
        try {
            String uuid = getRequired(data, "uuid", String.class);
            String sessionId = getRequired(data, "session_id", String.class);
            Map<String, Object> info = getRequired(data, "rate_limit_info", Map.class);
            RateLimitStatus status = parseRateLimitStatus(getRequired(info, "status", String.class));
            Long resetsAt = (info.get("resetsAt") instanceof Number n) ? n.longValue() : null;
            RateLimitType rateLimitType = info.get("rateLimitType") instanceof String s
                    ? parseRateLimitType(s) : null;
            Double utilization = (info.get("utilization") instanceof Number n) ? n.doubleValue() : null;
            RateLimitStatus overageStatus = info.get("overageStatus") instanceof String s
                    ? parseRateLimitStatus(s) : null;
            Long overageResetsAt = (info.get("overageResetsAt") instanceof Number n) ? n.longValue() : null;
            String overageDisabledReason = (String) info.get("overageDisabledReason");
            RateLimitInfo rateLimitInfo = new RateLimitInfo(
                    status, resetsAt, rateLimitType, utilization,
                    overageStatus, overageResetsAt, overageDisabledReason, info);
            return new RateLimitEvent(rateLimitInfo, uuid, sessionId);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in rate_limit_event message: " + e.getMessage(), data, e);
        } catch (MessageParseException e) {
            throw new MessageParseException("Missing required field in rate_limit_event message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static StreamEvent parseStreamEvent(Map<String, Object> data) throws MessageParseException {
        try {
            String uuid = getRequired(data, "uuid", String.class);
            String sessionId = getRequired(data, "session_id", String.class);
            Map<String, Object> event = (Map<String, Object>) data.get("event");
            if (event == null) {
                throw new MessageParseException("Missing 'event' field in stream_event message", data);
            }
            String parentToolUseId = (String) data.get("parent_tool_use_id");

            return new StreamEvent(uuid, sessionId, event, parentToolUseId);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in stream_event message: " + e.getMessage(), data, e);
        }
    }

    private static TaskUsage parseTaskUsage(Map<String, Object> usage) {
        int totalTokens = usage.get("total_tokens") instanceof Number n ? n.intValue() : 0;
        int toolUses = usage.get("tool_uses") instanceof Number n ? n.intValue() : 0;
        int durationMs = usage.get("duration_ms") instanceof Number n ? n.intValue() : 0;
        return new TaskUsage(totalTokens, toolUses, durationMs);
    }

    @Nullable
    private static TaskUsage parseTaskUsageOptional(@Nullable Map<String, Object> usage) {
        return usage == null ? null : parseTaskUsage(usage);
    }

    private static RateLimitStatus parseRateLimitStatus(String value) {
        try {
            return RateLimitStatus.fromValue(value);
        } catch (IllegalArgumentException e) {
            logger.fine("Unknown RateLimitStatus value: " + value);
            return null;
        }
    }

    private static RateLimitType parseRateLimitType(String value) {
        try {
            return RateLimitType.fromValue(value);
        } catch (IllegalArgumentException e) {
            logger.fine("Unknown RateLimitType value: " + value);
            return null;
        }
    }

    private static <T> T getRequired(Map<String, Object> data, String key, Class<T> type) throws MessageParseException {
        Object value = data.get(key);
        if (value == null) {
            throw new MessageParseException("Missing required field: " + key, data);
        }
        if (!type.isInstance(value)) {
            throw new MessageParseException(
                    "Invalid type for field '" + key + "': expected " + type.getSimpleName() + ", got "
                            + value.getClass().getSimpleName(),
                    data);
        }
        return type.cast(value);
    }

}
