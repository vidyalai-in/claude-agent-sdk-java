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
import in.vidyalai.claude.sdk.types.message.ConversationResetMessage;
import in.vidyalai.claude.sdk.types.message.DeferredToolUse;
import in.vidyalai.claude.sdk.types.message.DocumentBlock;
import in.vidyalai.claude.sdk.types.message.ImageBlock;
import in.vidyalai.claude.sdk.types.message.HookEventMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.MessageOrigin;
import in.vidyalai.claude.sdk.types.message.MessageOriginKind;
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
import in.vidyalai.claude.sdk.types.message.TaskNotificationOriginSubkind;
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

        Object rawType = data.get("type");
        if (rawType == null) {
            throw new MessageParseException("Message missing 'type' field", data);
        }
        if (!(rawType instanceof String messageType)) {
            // A non-string type can name no message this SDK models; the Python
            // SDK falls through to its unknown-type branch and skips it too.
            logger.fine("Skipping message with non-string type: " + rawType);
            return null;
        }

        try {
            return switch (messageType) {
                case "user" -> parseUserMessage(data);
                case "assistant" -> parseAssistantMessage(data);
                case "system" -> parseSystemMessage(data);
                case "result" -> parseResultMessage(data);
                case "stream_event" -> parseStreamEvent(data);
                case "rate_limit_event" -> parseRateLimitEvent(data);
                case "conversation_reset" -> parseConversationResetMessage(data);
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

    /**
     * Reads {@code data["origin"]} when it is a well-formed origin object.
     *
     * <p>
     * The CLI's object is retained verbatim on {@link MessageOrigin#raw()} —
     * including keys this SDK version doesn't model — so newer origin
     * kinds/fields stay visible to callers. Anything that is not an object with
     * a string {@code kind} is treated as absent. An unrecognized {@code kind}
     * or {@code subkind} leaves the corresponding enum null rather than
     * rejecting the frame; the wire strings remain readable via
     * {@link MessageOrigin#kindValue()} and {@link MessageOrigin#raw()}.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable MessageOrigin parseOrigin(Map<String, Object> data) {
        if (!(data.get("origin") instanceof Map<?, ?> originMap)
                || !(originMap.get("kind") instanceof String kindValue)) {
            return null;
        }
        Map<String, Object> raw = (Map<String, Object>) originMap;
        MessageOriginKind kind;
        try {
            kind = MessageOriginKind.fromValue(kindValue);
        } catch (IllegalArgumentException e) {
            logger.fine("Unknown MessageOriginKind value: " + kindValue);
            kind = null;
        }
        TaskNotificationOriginSubkind subkind = null;
        if (raw.get("subkind") instanceof String subkindValue) {
            try {
                subkind = TaskNotificationOriginSubkind.fromValue(subkindValue);
            } catch (IllegalArgumentException e) {
                logger.fine("Unknown TaskNotificationOriginSubkind value: " + subkindValue);
            }
        }
        return new MessageOrigin(
                kind,
                kindValue,
                raw.get("server") instanceof String s ? s : null,
                raw.get("from") instanceof String s ? s : null,
                raw.get("name") instanceof String s ? s : null,
                raw.get("fromSession") instanceof String s ? s : null,
                raw.get("senderTaskId") instanceof String s ? s : null,
                raw.get("body") instanceof String s ? s : null,
                raw.get("verifiedPeerPid") instanceof Number n ? n.intValue() : null,
                subkind,
                raw);
    }

    @SuppressWarnings("unchecked")
    private static UserMessage parseUserMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String parentToolUseId = optString(data, "parent_tool_use_id");
            String uuid = optString(data, "uuid");
            MessageOrigin origin = parseOrigin(data);
            Object toolUseResultRaw = data.get("tool_use_result");
            Map<String, Object> toolUseResult = toolUseResultAsMap(toolUseResultRaw);

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
                    ContentBlock parsed = parseContentBlock(block);
                    if (parsed != null) {
                        blocks.add(parsed);
                    }
                }
                return new UserMessage(blocks, uuid, parentToolUseId, toolUseResult, origin, toolUseResultRaw);
            }

            return new UserMessage(content, uuid, parentToolUseId, toolUseResult, origin, toolUseResultRaw);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in user message: " + e.getMessage(), data, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AssistantMessage parseAssistantMessage(Map<String, Object> data) throws MessageParseException {
        try {
            String parentToolUseId = optString(data, "parent_tool_use_id");

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
                ContentBlock parsed = parseContentBlock(block);
                if (parsed != null) {
                    blocks.add(parsed);
                }
            }

            // Required, as in the Python SDK: an absent key fails the frame, an
            // explicit null is read as null.
            String model = getPresent(message, "model", String.class);

            // Error field is at top level of response, not inside message. An
            // unrecognized string, or a value that is not a string at all, reads
            // as UNKNOWN; the value as sent is kept on errorRaw.
            Object errorRaw = data.get("error");
            AssistantMessageError error = null;
            if (errorRaw instanceof String errorStr) {
                error = AssistantMessageError.fromValue(errorStr);
            } else if (errorRaw != null) {
                error = AssistantMessageError.UNKNOWN;
            }

            // Per-turn usage from the API (input_tokens, output_tokens, cache tokens, etc.)
            Map<String, Object> usage = optMap(message, "usage");

            // Additional metadata fields
            String messageId = optString(message, "id");
            String stopReason = optString(message, "stop_reason");
            String sessionId = optString(data, "session_id");
            String uuid = optString(data, "uuid");

            return new AssistantMessage(blocks, model, parentToolUseId, error, usage,
                    messageId, stopReason, sessionId, uuid,
                    parseUserMessageUuid(data), parseUserMessageUuids(data), errorRaw);
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in assistant message: " + e.getMessage(), data, e);
        }
    }

    /**
     * Parses one content block.
     *
     * <p>
     * Deliberately more lenient than the Python SDK in one direction: a missing
     * field of a modelled block reads as null rather than failing the frame.
     * Where Python passes a value through untyped (a non-object {@code input},
     * {@code content} or {@code source}, a non-boolean {@code is_error}) the
     * typed record cannot hold it, so it reads as null too, instead of raising
     * a {@link ClassCastException}.
     *
     * @return the block, or null for a block whose {@code type} is an explicit
     *         null or not a string: Python matches it against no known name and
     *         drops it, and so does this parser
     */
    private static @Nullable ContentBlock parseContentBlock(Map<String, Object> block)
            throws MessageParseException {
        if (!block.containsKey("type")) {
            throw new MessageParseException("Missing 'type' field in content block", block);
        }
        if (!(block.get("type") instanceof String type)) {
            logger.fine("Skipping content block with a null or non-string type: " + block.get("type"));
            return null;
        }

        return switch (type) {
            case "text" -> new TextBlock((String) block.get("text"));
            case "thinking" -> new ThinkingBlock(
                    (String) block.get("thinking"),
                    (String) block.get("signature"));
            case "tool_use" -> new ToolUseBlock(
                    (String) block.get("id"),
                    (String) block.get("name"),
                    optMap(block, "input"));
            case "tool_result" -> new ToolResultBlock(
                    (String) block.get("tool_use_id"),
                    block.get("content"),
                    (block.get("is_error") instanceof Boolean b) ? b : null);
            case "server_tool_use" -> new ServerToolUseBlock(
                    (String) block.get("id"),
                    (String) block.get("name"),
                    optMap(block, "input"));
            case "advisor_tool_result" -> new ServerToolResultBlock(
                    (String) block.get("tool_use_id"),
                    optMap(block, "content"));
            // Reading a PDF produces these on the user side without the caller asking for
            // anything image- or document-shaped, so they are not an exotic case.
            case "image" -> new ImageBlock(optMap(block, "source"));
            case "document" -> new DocumentBlock(optMap(block, "source"));
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
            if (!data.containsKey("subtype")) {
                throw new MessageParseException("Missing required field: 'subtype' in system message", data);
            }
            if (!(data.get("subtype") instanceof String subtype)) {
                // Python accepts any present subtype and yields a generic
                // SystemMessage; the raw value stays in data.
                return new SystemMessage(null, data);
            }
            return switch (subtype) {
                case "task_started" -> new TaskStartedMessage(
                        subtype,
                        data,
                        getPresent(data, "task_id", String.class),
                        getPresent(data, "description", String.class),
                        getPresent(data, "uuid", String.class),
                        getPresent(data, "session_id", String.class),
                        optString(data, "tool_use_id"),
                        optString(data, "task_type"));
                case "task_progress" -> {
                    requireKey(data, "usage");
                    yield new TaskProgressMessage(
                            subtype,
                            data,
                            getPresent(data, "task_id", String.class),
                            getPresent(data, "description", String.class),
                            parseTaskUsageOptional(optMap(data, "usage")),
                            getPresent(data, "uuid", String.class),
                            getPresent(data, "session_id", String.class),
                            optString(data, "tool_use_id"),
                            optString(data, "last_tool_name"));
                }
                case "task_notification" -> {
                    // A status this SDK does not model (or a non-string one) reads
                    // as null rather than ending the stream; the wire value stays
                    // on statusValue() and in data. Python passes it through.
                    requireKey(data, "status");
                    yield new TaskNotificationMessage(
                            subtype,
                            data,
                            getPresent(data, "task_id", String.class),
                            parseTaskNotificationStatus(data.get("status")),
                            getPresent(data, "output_file", String.class),
                            getPresent(data, "summary", String.class),
                            getPresent(data, "uuid", String.class),
                            getPresent(data, "session_id", String.class),
                            optString(data, "tool_use_id"),
                            parseTaskUsageOptional(optMap(data, "usage")));
                }
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
            // Required, as in the Python SDK: an absent key fails the frame. An
            // explicit null is accepted there; here it reads as null, or as
            // 0/false for the primitive components.
            String subtype = getPresent(data, "subtype", String.class);
            int durationMs = intOrZero(getPresent(data, "duration_ms", Number.class));
            int durationApiMs = intOrZero(getPresent(data, "duration_api_ms", Number.class));
            Boolean isErrorValue = getPresent(data, "is_error", Boolean.class);
            boolean isError = (isErrorValue != null) && isErrorValue;
            int numTurns = intOrZero(getPresent(data, "num_turns", Number.class));
            String sessionId = getPresent(data, "session_id", String.class);

            // Optional fields
            String stopReason = optString(data, "stop_reason");
            Double totalCostUsd = null;
            if (data.get("total_cost_usd") instanceof Number n) {
                totalCostUsd = n.doubleValue();
            }

            Map<String, Object> usage = optMap(data, "usage");
            String result = optString(data, "result");
            Object structuredOutput = data.get("structured_output");

            // Additional metadata fields
            Map<String, ModelUsage> modelUsage = parseModelUsage(data.get("modelUsage"));
            List<Object> permissionDenials = (data.get("permission_denials") instanceof List<?> denials)
                    ? (List<Object>) denials
                    : null;
            // The CLI emits a list of strings here. Tolerate a bare string
            // (older or buggy emitters) and ignore any other shape rather than
            // rejecting the whole result frame — the Python SDK does no type
            // check on this field at all.
            Object rawErrors = data.get("errors");
            List<String> errors = (rawErrors instanceof List<?> errorList)
                    ? (List<String>) errorList
                    : ((rawErrors instanceof String errorText) ? List.of(errorText) : null);
            String uuid = optString(data, "uuid");
            String terminalReason = optString(data, "terminal_reason");

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
                    uuid, terminalReason, parseOrigin(data),
                    parseUserMessageUuid(data), parseUserMessageUuids(data));
        } catch (ClassCastException e) {
            throw new MessageParseException("Invalid field type in result message: " + e.getMessage(), data, e);
        }
    }

    /**
     * Reads {@code user_message_uuid}: the uuid of the user message a turn is
     * answering. Anything but a string reads as absent.
     */
    private static @Nullable String parseUserMessageUuid(Map<String, Object> data) {
        return (data.get("user_message_uuid") instanceof String uuid) ? uuid : null;
    }

    /**
     * Reads {@code user_message_uuids}: the uuids of every user message a turn
     * has taken in. An empty list stays empty, distinct from an absent one
     * (null). Non-string entries are dropped rather than failing the frame.
     */
    private static @Nullable List<String> parseUserMessageUuids(Map<String, Object> data) {
        if (!(data.get("user_message_uuids") instanceof List<?> raw)) {
            return null;
        }
        List<String> uuids = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof String uuid) {
                uuids.add(uuid);
            }
        }
        return List.copyOf(uuids);
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
            String uuid = getPresent(data, "uuid", String.class);
            String sessionId = getPresent(data, "session_id", String.class);
            // A null rate_limit_info is rejected, as in Python (where indexing
            // None raises). A present status of any value parses; one this SDK
            // does not model reads as null, with the wire value still on raw().
            Map<String, Object> info = getRequired(data, "rate_limit_info", Map.class);
            requireKey(info, "status");
            RateLimitStatus status = (info.get("status") instanceof String s)
                    ? parseRateLimitStatus(s) : null;
            Long resetsAt = (info.get("resetsAt") instanceof Number n) ? n.longValue() : null;
            RateLimitType rateLimitType = info.get("rateLimitType") instanceof String s
                    ? parseRateLimitType(s) : null;
            Double utilization = (info.get("utilization") instanceof Number n) ? n.doubleValue() : null;
            RateLimitStatus overageStatus = info.get("overageStatus") instanceof String s
                    ? parseRateLimitStatus(s) : null;
            Long overageResetsAt = (info.get("overageResetsAt") instanceof Number n) ? n.longValue() : null;
            String overageDisabledReason = optString(info, "overageDisabledReason");
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

    private static ConversationResetMessage parseConversationResetMessage(Map<String, Object> data)
            throws MessageParseException {
        try {
            String newConversationId = getPresent(data, "new_conversation_id", String.class);
            String uuid = getPresent(data, "uuid", String.class);
            String sessionId = getPresent(data, "session_id", String.class);
            return new ConversationResetMessage(newConversationId, uuid, sessionId);
        } catch (MessageParseException e) {
            throw new MessageParseException(
                    "Missing required field in conversation_reset message: " + e.getMessage(), data, e);
        }
    }

    private static StreamEvent parseStreamEvent(Map<String, Object> data) throws MessageParseException {
        try {
            String uuid = getPresent(data, "uuid", String.class);
            String sessionId = getPresent(data, "session_id", String.class);
            if (!data.containsKey("event")) {
                throw new MessageParseException("Missing 'event' field in stream_event message", data);
            }
            Map<String, Object> event = optMap(data, "event");
            String parentToolUseId = optString(data, "parent_tool_use_id");

            return new StreamEvent(uuid, sessionId, event, parentToolUseId,
                    parseUserMessageUuid(data), parseUserMessageUuids(data));
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

    private static @Nullable TaskNotificationStatus parseTaskNotificationStatus(@Nullable Object value) {
        TaskNotificationStatus status = (value instanceof String s)
                ? TaskNotificationStatus.fromValueOrNull(s)
                : null;
        if ((status == null) && (value != null)) {
            logger.fine("Unknown TaskNotificationStatus value: " + value);
        }
        return status;
    }

    /**
     * The compatibility view of {@code tool_use_result} on
     * {@link UserMessage#toolUseResult()}: the object itself when the CLI sent
     * one, otherwise the value wrapped as {@code {"unknown": value}}.
     *
     * <p>
     * The wrapped value is the one received, not its {@code toString()}: for a
     * string that is what this method always produced, and for a list or number
     * the old {@code toString()} was Java's own rendering, which no caller could
     * read back. The wrapping itself is deprecated in favour of
     * {@link UserMessage#toolUseResultRaw()}.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> toolUseResultAsMap(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of("unknown", raw);
    }

    /** Reads an optional string; absent, null or any other type reads as null. */
    private static @Nullable String optString(Map<String, Object> data, String key) {
        return (data.get(key) instanceof String s) ? s : null;
    }

    /** Reads an optional object; absent, null or any other type reads as null. */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> optMap(Map<String, Object> data, String key) {
        return (data.get(key) instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    private static int intOrZero(@Nullable Number value) {
        return (value != null) ? value.intValue() : 0;
    }

    /**
     * Fails when {@code key} is absent, as indexing a missing key does in the
     * Python SDK. A key that is present with a null value passes.
     */
    private static void requireKey(Map<String, Object> data, String key) throws MessageParseException {
        if (!data.containsKey(key)) {
            throw new MessageParseException("Missing required field: " + key, data);
        }
    }

    /**
     * Reads a field the frame must carry, accepting an explicit null.
     *
     * <p>
     * Matches the Python SDK, whose {@code data[key]} fails only on an absent
     * key and returns {@code None} for a present null. A non-null value of the
     * wrong type is still rejected: the typed record cannot hold it.
     *
     * @return the value, or null if the key is present with a null value
     * @throws MessageParseException if the key is absent or the value has the
     *                               wrong type
     */
    private static <T> @Nullable T getPresent(Map<String, Object> data, String key, Class<T> type)
            throws MessageParseException {
        requireKey(data, key);
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new MessageParseException(
                    "Invalid type for field '" + key + "': expected " + type.getSimpleName() + ", got "
                            + value.getClass().getSimpleName(),
                    data);
        }
        return type.cast(value);
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
