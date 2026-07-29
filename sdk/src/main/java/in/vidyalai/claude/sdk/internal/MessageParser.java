package in.vidyalai.claude.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.exceptions.MessageParseException;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.AssistantMessageError;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.DeferredToolUse;
import in.vidyalai.claude.sdk.types.message.DocumentBlock;
import in.vidyalai.claude.sdk.types.message.ImageBlock;
import in.vidyalai.claude.sdk.types.message.HookEventMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.RateLimitEvent;
import in.vidyalai.claude.sdk.types.message.RateLimitInfo;
import in.vidyalai.claude.sdk.types.message.RateLimitStatus;
import in.vidyalai.claude.sdk.types.message.RateLimitType;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.ServerToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ServerToolUseBlock;
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;
import in.vidyalai.claude.sdk.types.message.ModelUsage;
import in.vidyalai.claude.sdk.types.message.StreamEvent;
import in.vidyalai.claude.sdk.types.message.SystemMessage;
import in.vidyalai.claude.sdk.types.message.TaskNotificationMessage;
import in.vidyalai.claude.sdk.types.message.TaskNotificationStatus;
import in.vidyalai.claude.sdk.types.message.TaskProgressMessage;
import in.vidyalai.claude.sdk.types.message.TaskStartedMessage;
import in.vidyalai.claude.sdk.types.message.TaskUpdatedMessage;
import in.vidyalai.claude.sdk.types.message.TaskUpdatedStatus;
import in.vidyalai.claude.sdk.types.message.TaskUsage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ThinkingBlock;
import in.vidyalai.claude.sdk.types.message.ToolResultBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;
import in.vidyalai.claude.sdk.types.message.UnknownBlock;
import in.vidyalai.claude.sdk.types.message.UserMessage;
import in.vidyalai.claude.sdk.types.session.SessionKey;

/**
 * Parser for converting raw JSON messages to typed Message objects.
 */
public final class MessageParser {

    private static final Logger logger = Logger.getLogger(MessageParser.class.getName());

    /** Block types already reported as unmodelled, so a long run warns once rather than per block. */
    private static final Set<String> WARNED_BLOCK_TYPES = ConcurrentHashMap.newKeySet();

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
                    if (!(item instanceof Map<?, ?>)) {
                        throw new MessageParseException(
                                "Invalid content block (expected dict, got "
                                        + ((item != null) ? item.getClass().getSimpleName() : "null") + ")",
                                data);
                    }
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

            Object rawContent = message.get("content");
            if (rawContent == null) {
                throw new MessageParseException("Missing 'content' field in assistant message", data);
            }
            if (!(rawContent instanceof List<?> contentList)) {
                throw new MessageParseException(
                        "Invalid assistant content (expected list, got "
                                + rawContent.getClass().getSimpleName() + ")",
                        data);
            }

            List<ContentBlock> blocks = new ArrayList<>();
            for (Object item : contentList) {
                if (!(item instanceof Map<?, ?>)) {
                    throw new MessageParseException(
                            "Invalid content block (expected dict, got "
                                    + ((item != null) ? item.getClass().getSimpleName() : "null") + ")",
                            data);
                }
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
            case "server_tool_use" -> new ServerToolUseBlock(
                    (String) block.get("id"),
                    (String) block.get("name"),
                    (Map<String, Object>) block.get("input"));
            case "advisor_tool_result" -> new ServerToolResultBlock(
                    (String) block.get("tool_use_id"),
                    (Map<String, Object>) block.get("content"));
            // Reading a PDF produces these on the user side without the caller asking for
            // anything image- or document-shaped, so they are not an exotic case.
            case "image" -> new ImageBlock((Map<String, Object>) block.get("source"));
            case "document" -> new DocumentBlock((Map<String, Object>) block.get("source"));
            default -> unknownBlock(type, block);
        };
    }

    /**
     * Preserves a block type this SDK does not model, rather than failing the whole run.
     *
     * <p>The CLI and the API own the block vocabulary and extend it independently of this
     * library. Throwing here means an added type kills the reader thread mid-turn and reports
     * itself as a JSON decode failure, which is both fatal and misleading — that is exactly how
     * {@code image} and {@code document} presented before they were modelled above. Logged at
     * WARNING, once per type per process, so it stays visible without flooding a long run.
     */
    private static ContentBlock unknownBlock(String type, Map<String, Object> block) {
        if (WARNED_BLOCK_TYPES.add(type)) {
            logger.warning("Content block type '" + type + "' is not modelled by this SDK version; "
                    + "passing it through as an UnknownBlock. Upgrade the SDK if you need it typed.");
        }
        return new UnknownBlock(type, block);
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
                case "task_updated" -> {
                    // Terminal task completion sometimes arrives only as a
                    // task_updated patch (no separate task_notification), so
                    // expose it as a typed lifecycle message rather than a
                    // generic SystemMessage. Parsed defensively: the patch may
                    // omit uuid/session_id and parsing must never raise on a
                    // lifecycle event.
                    Map<String, Object> patch = data.get("patch") instanceof Map<?, ?> p
                            ? (Map<String, Object>) p
                            : Map.of();
                    // Terminal-ness is derived from patch.status; the CLI is
                    // assumed to set it on terminal transitions. A patch that
                    // carries only end_time/result/error (no status) is left
                    // non-terminal (status=null) — the full patch is still
                    // preserved on .patch() for callers that need more.
                    TaskUpdatedStatus status = TaskUpdatedStatus.fromValueOrNull(
                            patch.get("status") instanceof String s ? s : null);
                    yield new TaskUpdatedMessage(
                            subtype,
                            data,
                            data.get("task_id") instanceof String s ? s : "",
                            patch,
                            status,
                            data.get("session_id") instanceof String s ? s : null,
                            data.get("uuid") instanceof String s ? s : null);
                }
                case "hook_started", "hook_response" -> {
                    String hookEventName = "";
                    Object he = data.get("hook_event");
                    if (he instanceof String s && !s.isEmpty()) {
                        hookEventName = s;
                    } else if (data.get("hook_name") instanceof String s2 && !s2.isEmpty()) {
                        hookEventName = s2;
                    } else if (data.get("hook_event_name") instanceof String s3) {
                        hookEventName = s3;
                    }
                    String sessionId = data.get("session_id") instanceof String s ? s : null;
                    String uuid = data.get("uuid") instanceof String s ? s : null;
                    yield new HookEventMessage(subtype, data, hookEventName, sessionId, uuid);
                }
                case "mirror_error" -> {
                    // SDK-synthesized via Query.reportMirrorError — never emitted by the CLI subprocess.
                    Object rawKey = data.get("key");
                    SessionKey key = null;
                    if (rawKey instanceof Map<?, ?> keyMap) {
                        Object pk = ((Map<String, Object>) keyMap).get("project_key");
                        Object sid = ((Map<String, Object>) keyMap).get("session_id");
                        Object sp = ((Map<String, Object>) keyMap).get("subpath");
                        if (pk instanceof String pkStr && sid instanceof String sidStr) {
                            key = new SessionKey(pkStr, sidStr, sp instanceof String spStr ? spStr : null);
                        }
                    }
                    String error = data.get("error") instanceof String s ? s : "";
                    yield new MirrorErrorMessage(subtype, data, key, error);
                }
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
            Map<String, ModelUsage> modelUsage = parseModelUsage(data.get("modelUsage"));
            List<Object> permissionDenials = (List<Object>) data.get("permission_denials");
            List<String> errors = (List<String>) data.get("errors");
            String uuid = (String) data.get("uuid");
            String terminalReason = (String) data.get("terminal_reason");

            DeferredToolUse deferredToolUse = null;
            Object deferredRaw = data.get("deferred_tool_use");
            if (deferredRaw instanceof Map<?, ?> deferredMap) {
                Object id = deferredMap.get("id");
                Object name = deferredMap.get("name");
                Object input = deferredMap.get("input");
                if (id instanceof String idStr && name instanceof String nameStr) {
                    Map<String, Object> inputMap = input instanceof Map<?, ?>
                            ? (Map<String, Object>) input
                            : Map.of();
                    deferredToolUse = new DeferredToolUse(idStr, nameStr, inputMap);
                }
            }

            Integer apiErrorStatus = null;
            if (data.get("api_error_status") instanceof Number n) {
                apiErrorStatus = n.intValue();
            }

            return new ResultMessage(
                    subtype, durationMs, durationApiMs, isError, numTurns,
                    sessionId, stopReason, totalCostUsd, usage, result, structuredOutput,
                    modelUsage, permissionDenials, deferredToolUse, errors, apiErrorStatus,
                    uuid, terminalReason);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in result message: " + e.getMessage(), data, e);
        }
    }

    /**
     * Converts the CLI's {@code modelUsage} object into typed entries.
     *
     * <p>
     * The CLI passes this value through verbatim, so its keys are camelCase
     * rather than the snake_case used elsewhere on the result frame. Entries
     * with a non-object value are skipped rather than rejected: the raw map is
     * retained on each {@link ModelUsage} so unmodeled fields stay reachable.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, ModelUsage> parseModelUsage(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> byModel)) {
            return null;
        }
        Map<String, ModelUsage> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : byModel.entrySet()) {
            if (!(entry.getKey() instanceof String model)
                    || !(entry.getValue() instanceof Map<?, ?> fields)) {
                continue;
            }
            Map<String, Object> usage = (Map<String, Object>) fields;
            parsed.put(model, new ModelUsage(
                    longField(usage, "inputTokens"),
                    longField(usage, "outputTokens"),
                    longField(usage, "cacheReadInputTokens"),
                    longField(usage, "cacheCreationInputTokens"),
                    longField(usage, "webSearchRequests"),
                    usage.get("costUSD") instanceof Number n ? n.doubleValue() : 0.0,
                    longField(usage, "contextWindow"),
                    longField(usage, "maxOutputTokens"),
                    usage.get("canonicalModel") instanceof String s ? s : null,
                    usage.get("provider") instanceof String s ? s : null,
                    usage));
        }
        return parsed;
    }

    /**
     * Reads a numeric field, treating an absent or non-numeric value as zero.
     * The CLI emits every counter on each entry, so a missing one means no
     * usage of that kind rather than a malformed frame.
     */
    private static long longField(Map<String, Object> fields, String name) {
        return fields.get(name) instanceof Number n ? n.longValue() : 0L;
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
