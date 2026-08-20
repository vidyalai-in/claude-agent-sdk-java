# Message Types API Reference

Type hierarchy for Claude messages.

## MessageParser

The `MessageParser` class converts raw JSON maps from the CLI into typed `Message` objects.

```java
public final class MessageParser {
    // Returns null for unrecognized message types (forward compatibility)
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException;
}
```

Unknown message types return `null` instead of throwing an exception, allowing the SDK to remain compatible with newer CLI versions that may emit new message types.

## Message Interface

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent,
    ConversationResetMessage {
    String type();
}
```

> **Compatibility note (v0.1.23).** `ConversationResetMessage` widened this
> union. Because `Message` is sealed, an exhaustive `switch` with no `default`
> branch stops compiling until a `ConversationResetMessage` case is added. Add a
> `default ->` branch instead if you would rather absorb future additions
> silently.

## UserMessage

```java
record UserMessage(
    Object content,                               // String or List<ContentBlock>
    @Nullable String uuid,                        // Unique message identifier
    @Nullable String parentToolUseId,             // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult,  // Tool execution metadata
    @Nullable MessageOrigin origin                // Provenance; null when unattributed
) implements Message {
    String type();                    // Returns "user"
    @Nullable String contentAsString();           // Content as String, or null if structured
    @Nullable List<ContentBlock> contentAsBlocks(); // Content as blocks, or null if string
}
```

A backwards-compatible 4-parameter constructor without `origin` remains
available. `origin` is populated on injected turns (task notifications,
channel/peer messages) and on user messages the CLI replays; tool-result
messages never carry it. See [MessageOrigin](#messageorigin).

## AssistantMessage

```java
record AssistantMessage(
    List<ContentBlock> content,                   // List of content blocks
    String model,                                 // Model that generated the response
    @Nullable String parentToolUseId,             // Set when inside a subagent tool use
    @Nullable AssistantMessageError error,        // Error information, if any
    @Nullable Map<String, Object> usage,          // Per-turn token usage (input_tokens, output_tokens, cache tokens, etc.)
    @Nullable String messageId,                   // Unique message ID from the API (e.g. "msg_01HRq...")
    @Nullable String stopReason,                  // Reason the model stopped (e.g. "end_turn")
    @Nullable String sessionId,                   // Session ID this message belongs to
    @Nullable String uuid                         // Unique identifier in the session transcript
) implements Message {
    String type();              // Returns "assistant"
    String getTextContent();    // Concatenates text from all TextBlock instances
    boolean hasToolUse();       // True if message contains at least one ToolUseBlock
}
```

Backwards-compatible constructors are also available for code that does not need the newer fields:

```java
new AssistantMessage(content, model, parentToolUseId, error)  // usage and all later fields default to null
new AssistantMessage(content, model, parentToolUseId, error, usage)  // messageId and later fields default to null
```

### AssistantMessageError

```java
enum AssistantMessageError {
    AUTHENTICATION_FAILED,  // "authentication_failed"
    BILLING_ERROR,          // "billing_error"
    RATE_LIMIT,             // "rate_limit"
    INVALID_REQUEST,        // "invalid_request"
    SERVER_ERROR,           // "server_error"
    UNKNOWN;                // "unknown" (also used for unrecognized error values)

    String getValue();      // Returns the JSON string value
    static AssistantMessageError fromValue(String value); // Parse from string
}
```

## SystemMessage

```java
record SystemMessage(
    String subtype,                 // Message subtype (e.g., "init")
    Map<String, Object> data        // Full raw message data
) implements Message {
    String type();  // Returns "system"
}
```

## ResultMessage

```java
record ResultMessage(
    String subtype,                               // "success", "error_during_execution", etc.
    int durationMs,                               // Total duration in milliseconds
    int durationApiMs,                            // API call duration in milliseconds
    boolean isError,                              // Whether the result is an error
    int numTurns,                                 // Number of conversation turns
    String sessionId,                             // Session identifier
    @Nullable String stopReason,                  // Reason the session stopped
    @Nullable Double totalCostUsd,                // Total cost in USD
    @Nullable Map<String, Object> usage,          // Token usage breakdown
    @Nullable String result,                      // Result text
    @Nullable Object structuredOutput,            // Structured output if json_schema specified
    @Nullable Map<String, ModelUsage> modelUsage,  // Per-model usage breakdown
    @Nullable List<Object> permissionDenials,     // Permission denials during session
    @Nullable DeferredToolUse deferredToolUse,    // Tool call deferred by a PreToolUse "defer" decision
    @Nullable List<String> errors,                // Error messages from the CLI
    @Nullable Integer apiErrorStatus,             // HTTP status of failing API call when isError=true and subtype="success"
    @Nullable String uuid,                        // Unique message identifier in session
    @Nullable String terminalReason,              // Why the query loop terminated
    @Nullable MessageOrigin origin                // Origin of the triggering user message
) implements Message {
    String type();  // Returns "result"
}
```

When a run ends in a terminal error result the CLI also exits non-zero, and the SDK reports that as a [`ResultException`](./api-exceptions.md#resultexception) carrying the same payload as this message — see that page for which API surfaces it and which do not.

**Parsing note on `errors`:** the CLI sends a list of strings here. A bare string is tolerated and kept as a single-element list, and any other shape is ignored rather than rejecting the whole result frame; the Python SDK performs no type check on this field at all, so a malformed value must not cost the caller the entire result.

Backwards-compatible constructors are also available for code that does not need the newer fields. The original 11-parameter constructor (without `modelUsage`, `permissionDenials`, `deferredToolUse`, `errors`, `apiErrorStatus`, `uuid`, `terminalReason`, and `origin`) continues to work; 15-parameter (without `deferredToolUse`, `apiErrorStatus`, `terminalReason`, `origin`), 17-parameter (without `terminalReason`, `origin`) and 18-parameter (without `origin`) overloads are also available for callers written against the earlier shapes.

### terminalReason

Why the query loop ended. Values observed from the CLI include `"completed"`, `"max_turns"`, `"aborted_streaming"` and `"aborted_tools"`.

`"aborted_streaming"` and `"aborted_tools"` mean the turn was cancelled — via `ClaudeSDKClient.interrupt()` or an `interrupt` control request — giving callers an explicit cancelled marker without a separate result subtype:

```java
ResultMessage result = ClaudeSDK.queryForResult("Long task", options);
if ("aborted_streaming".equals(result.terminalReason())
        || "aborted_tools".equals(result.terminalReason())) {
    System.out.println("Turn was interrupted");
}
```

`null` when the CLI did not report a terminal reason — older CLI versions, or a result that bypassed the query loop such as a local slash command. Mirrors the TypeScript SDK's `SDKResultMessage.terminal_reason`.

### ModelUsage

Per-model token and cost breakdown, keyed by model string in `ResultMessage.modelUsage()`.

```java
record ModelUsage(
    long inputTokens,                 // Tokens sent to the model
    long outputTokens,                // Tokens generated by the model
    long cacheReadInputTokens,        // Tokens read from the prompt cache
    long cacheCreationInputTokens,    // Tokens written to the prompt cache
    long webSearchRequests,           // Server-side web search requests
    double costUsd,                   // Cost attributed to this model, in USD
    long contextWindow,               // Model's context window size in tokens
    long maxOutputTokens,             // Model's maximum output length in tokens
    @Nullable String canonicalModel,  // Canonical model id used for pricing lookup
    @Nullable String provider,        // API provider that served this model
    @Nullable Map<String, Object> raw // Verbatim CLI map, including unmodelled fields
)
```

**JSON naming:** unusually for CLI-received data, this type uses camelCase JSON keys. The CLI passes the `modelUsage` value through verbatim, so its keys match the TypeScript SDK's `ModelUsage` shape rather than the snake_case used elsewhere on `ResultMessage`. The Java accessor is `costUsd()`; the JSON key is `costUSD`.

`canonicalModel` gives a stable key for client-side rate-table lookups across provider-specific ids and aliases (a Bedrock ARN maps to `claude-opus-4-7`), so cost drift is detectable without parsing the raw model string. `provider` is one of `firstParty`, `bedrock`, `vertex`, `foundry`, `anthropicAws`, `anthropicGoogleCloud`, `mantle`, `gateway`. Both are `null` on CLI versions that do not emit them.

```java
ResultMessage result = ClaudeSDK.queryForResult("Analyze this", options);
if (result.modelUsage() != null) {
    result.modelUsage().forEach((model, usage) ->
        System.out.printf("%s: %d in / %d out, $%.4f%n",
            model, usage.inputTokens(), usage.outputTokens(), usage.costUsd()));
}
```

Parsing is lenient by design: a counter the CLI did not emit reads as `0` rather than failing the frame, and an entry whose value is not an object is skipped rather than rejecting the whole result message. `raw()` retains the verbatim map so fields added by a newer CLI stay reachable without an SDK upgrade.

### DeferredToolUse

A tool call deferred by a `PreToolUse` hook returning `permissionDecision: "defer"`. The CLI stops the run and surfaces the deferred call here so the SDK consumer can decide whether to resume.

```java
record DeferredToolUse(
    String id,                       // Unique identifier of the deferred tool call
    String name,                     // Tool name
    Map<String, Object> input        // Tool input arguments
)
```

## StreamEvent

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message {
    String type();              // Returns "stream_event"
    @Nullable String eventType(); // Returns event.get("type"), or null
}
```

## Content Blocks

### ContentBlock Interface

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock
```

Every block exposes `type()`, the raw CLI discriminator string.

### TextBlock

```java
record TextBlock(String text) implements ContentBlock
```

### ThinkingBlock

```java
record ThinkingBlock(
    String thinking,   // Internal reasoning content
    String signature   // Cryptographic signature
) implements ContentBlock
```

### ToolUseBlock

```java
record ToolUseBlock(
    String id,                           // Tool use ID
    String name,                         // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input  // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

Server-side tool invocation (advisor, web_search, web_fetch, code_execution, etc.). The API executes these on the model's behalf — the caller never returns a result.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // ServerToolName value (raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`ServerToolName` enum values: `ADVISOR`, `WEB_SEARCH`, `WEB_FETCH`, `CODE_EXECUTION`, `BASH_CODE_EXECUTION`, `TEXT_EDITOR_CODE_EXECUTION`, `TOOL_SEARCH_TOOL_REGEX`, `TOOL_SEARCH_TOOL_BM25`.

### ServerToolResultBlock

Result block returned for a server-side tool call. The CLI emits these as `advisor_tool_result` content blocks; `content` is opaque (advisor result types include `advisor_result`, `advisor_redacted_result`, `advisor_tool_result_error`).

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content
) implements ContentBlock
```

### ImageBlock

Emitted when the `Read` tool renders a PDF page. The tool result itself only announces the page count; the file arrives in a *separate* user message carrying one `image` block per rendered page.

```java
record ImageBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`source` is deliberately left as the raw map because the API defines `base64`, `url`, `file`, `text` and `content` source shapes and can add more. Three convenience accessors cover the base64 case, each returning `null` when the key is absent or not a string:

| Method | Returns |
|---|---|
| `sourceType()` | `source["type"]`, e.g. `"base64"` |
| `mediaType()` | `source["media_type"]`, e.g. `"image/jpeg"` |
| `data()` | `source["data"]`, the base64 payload |

### DocumentBlock

The other shape the CLI uses for the same `Read`-a-PDF flow: a single `document` block holding the whole file rather than one image per page. Both shapes have been observed from CLI 2.1.218 against the same file on different runs, so both are modelled.

```java
record DocumentBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

Exposes the same `sourceType()` / `mediaType()` / `data()` accessors as `ImageBlock`; `mediaType()` is typically `"application/pdf"`.

### UnknownBlock

Forward-compatibility fallback for a block type this SDK version does not model. `MessageParser.parse()` already returns `null` for an unrecognised *message* type so a newer CLI cannot crash an older SDK; content blocks get the same treatment instead of throwing.

```java
record UnknownBlock(
    String type,               // The unrecognised discriminator
    Map<String, Object> raw    // The block, preserved whole
) implements ContentBlock
```

The parser logs once per unrecognised type at `WARNING` from the `in.vidyalai.claude.sdk.internal.MessageParser` logger. Before this existed, an unmodelled block threw `MessageParseException`, which killed the reader thread and discarded every other block in the message — including text the model had already produced.

> **Note:** `ContentBlock` is sealed. An exhaustive `switch` over it in caller code stops compiling when the `permits` clause grows. Handle `UnknownBlock` explicitly rather than adding a `default` branch, so the next addition is still a compile error rather than a silent fallthrough.

## MirrorErrorMessage

Non-fatal SessionStore append failure. Surfaces after the batcher's retry budget is exhausted; the local-disk transcript is already durable.

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted
    String error                       // failure description
) implements Message {
    String type();                      // returns "system"
}
```

## HookEventMessage

Hook lifecycle event. Only emitted when `includeHookEvents(true)` is set on `ClaudeAgentOptions`.

```java
record HookEventMessage(
    String subtype,                       // "hook_started" or "hook_response"
    Map<String, Object> data,             // full raw event dict from the CLI
    String hookEventName,                 // e.g. "PreToolUse", "PostToolUse", "Stop"
    @Nullable String sessionId,           // session ID this event belongs to
    @Nullable String uuid                 // unique event ID
) implements Message {
    String type();                        // returns "system"
    <T> T get(String key);                // typed lookup into data
}
```

`HookEventMessage` is a top-level sealed-interface member (it does not match `instanceof SystemMessage`). On a `hook_response` the `data` map carries `output`, `exit_code`, and `outcome` keys.

## RateLimitEvent

```java
record RateLimitEvent(
    RateLimitInfo rateLimitInfo,  // Detailed rate limit status information
    String uuid,                  // Unique identifier for this event
    String sessionId              // Session identifier
) implements Message {
    String type();  // Returns "rate_limit_event"
}
```

## ConversationResetMessage

Emitted when the session's conversation is replaced without ending the
connection — after `/clear`, or any other flow that discards the transcript
mid-session.

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message {
    String type();  // Returns "conversation_reset"
}
```

In streaming-input mode a single connection carries many user turns, and a
reset clears the conversation history *and* zeroes the running totals reported
on subsequent `ResultMessage` objects (`totalCostUsd`, for instance). If you
accumulate those totals across a long-lived session, snapshot them when this
message arrives.

`newConversationId` is a key for a UI to hang an empty transcript on (and to
discard any cached session title). It is **not** the `sessionId` of subsequent
messages — read that from the next message, which carries a new one.

```java
case ConversationResetMessage reset -> {
    System.out.printf("session %s reset -> new conversation %s%n",
            reset.sessionId(), reset.newConversationId());
    snapshotTotals();   // subsequent results restart their counters at zero
}
```

Missing any of the three required fields raises `MessageParseException`.

## MessageOrigin

Provenance of a user-role message, and — on a `ResultMessage` — of the message
that triggered that turn.

```java
record MessageOrigin(
    @Nullable MessageOriginKind kind,   // null when the CLI sent a kind this version doesn't model
    String kindValue,                   // verbatim wire value of `kind`, never null
    @Nullable String server,            // kind == CHANNEL: MCP server the message arrived on
    @Nullable String from,              // kind == PEER/OBSERVER: sender address (sender-asserted)
    @Nullable String name,              // kind == PEER: sender display name, CLI-normalized
    @Nullable String fromSession,       // kind == PEER: sender's host-openable session id
    @Nullable String senderTaskId,      // kind == PEER/OBSERVER: in-process subagent task id
    @Nullable String body,              // kind == PEER: decoded body, envelope stripped
    @Nullable Integer verifiedPeerPid,  // kind == PEER: kernel-verified pid of the connecting process
    @Nullable TaskNotificationOriginSubkind subkind,  // kind == TASK_NOTIFICATION
    Map<String, Object> raw             // full origin object from the CLI, verbatim
) {
    boolean isHuman();  // true when kind == MessageOriginKind.HUMAN
}
```

In streaming-input mode one connection interleaves the turns your application
sends with turns the session injects on its own — background-task
notifications, fired scheduled-task prompts, MCP channel messages, messages
relayed from peer sessions. `origin` tells them apart:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

A null `origin` means the CLI did not attribute the message. Prompts sent
through `ClaudeSDK.query()` or `ClaudeSDKClient.query(String)` arrive that way
unless you stamp `"origin": {"kind": "human"}` on the message map yourself via
`ClaudeSDKClient.query(Iterator)` — only the `human` kind is honored from an
SDK host, and doing so requires Claude Code >= 2.1.210.

**Forward compatibility.** A `kind` newer than this SDK models leaves `kind()`
null while `kindValue()` still carries the wire string, and `isHuman()` is
false for it — treat anything unrecognized as "not human". The same applies to
`subkind()`. The complete CLI object is retained on `raw()`, so keys this
version does not model stay reachable.

`from`, `name` and `fromSession` are **sender-asserted**. Use them for reply
routing and display, never as proof of identity.

### MessageOriginKind

```java
enum MessageOriginKind {
    HUMAN,              // "human"              — submitted by the application/user
    CHANNEL,            // "channel"            — arrived on an MCP channel
    PEER,               // "peer"               — relayed from a peer session or subagent
    TASK_NOTIFICATION,  // "task-notification"  — background task, or a fired scheduled prompt
    COORDINATOR,        // "coordinator"        — injected by a multi-session coordinator
    UNCLASSIFIED,       // "unclassified"       — CLI could not attribute the turn
    OBSERVER,           // "observer"           — from an attached observer
    AUTO_CONTINUATION,  // "auto-continuation"  — session continued its own prior work
    OBSERVER_ACTIVITY;  // "observer-activity"  — activity report from an observer

    String getValue();
    static MessageOriginKind fromValue(String value);  // throws IllegalArgumentException if unknown
}
```

### TaskNotificationOriginSubkind

Present when `kind == TASK_NOTIFICATION`, absent for ordinary background-task
notifications.

```java
enum TaskNotificationOriginSubkind {
    SCHEDULED_TRIGGER,   // "scheduled-trigger"  — the fired prompt of a scheduled task
    PEER_SEND_MESSAGE;   // "peer-send-message"  — sent from another of the user's sessions

    String getValue();
    static TaskNotificationOriginSubkind fromValue(String value);
}
```

## RateLimitInfo

```java
record RateLimitInfo(
    RateLimitStatus status,                      // Current rate limit status
    @Nullable Long resetsAt,                     // Unix timestamp when the rate limit window resets
    @Nullable RateLimitType rateLimitType,       // Which rate limit window applies
    @Nullable Double utilization,               // Fraction of the rate limit consumed (0.0–1.0)
    @Nullable RateLimitStatus overageStatus,    // Status of overage/pay-as-you-go usage
    @Nullable Long overageResetsAt,             // Unix timestamp when overage window resets
    @Nullable String overageDisabledReason,     // Why overage is unavailable if rejected
    @Nullable Map<String, Object> raw           // Full raw map from the CLI
)
```

## RateLimitStatus

```java
enum RateLimitStatus {
    ALLOWED,           // "allowed" — within rate limits
    ALLOWED_WARNING,   // "allowed_warning" — approaching the rate limit
    REJECTED;          // "rejected" — rate limit has been hit

    String getValue();                           // Returns the JSON string value
    static RateLimitStatus fromValue(String);    // Parse from string
}
```

## RateLimitType

```java
enum RateLimitType {
    FIVE_HOUR,         // "five_hour" — 5-hour rolling window
    SEVEN_DAY,         // "seven_day" — 7-day rolling window
    SEVEN_DAY_OPUS,    // "seven_day_opus" — 7-day Opus-specific window
    SEVEN_DAY_SONNET,  // "seven_day_sonnet" — 7-day Sonnet-specific window
    OVERAGE;           // "overage" — overage/pay-as-you-go limit

    String getValue();                        // Returns the JSON string value
    static RateLimitType fromValue(String);   // Parse from string
}
```

## Task Message Types

### TaskStartedMessage

```java
record TaskStartedMessage(
    String subtype,               // always "task_started"
    Map<String, Object> data,     // raw message data
    String taskId,                // unique task identifier
    String description,           // human-readable description
    String uuid,                  // message UUID
    String sessionId,             // session identifier
    @Nullable String toolUseId,   // tool use ID (may be null)
    @Nullable String taskType     // task type (may be null)
) implements Message {
    String type();  // Returns "system"
}
```

### TaskProgressMessage

```java
record TaskProgressMessage(
    String subtype,                  // always "task_progress"
    Map<String, Object> data,        // raw message data
    String taskId,                   // unique task identifier
    String description,              // human-readable description
    TaskUsage usage,                 // token/tool usage so far
    String uuid,                     // message UUID
    String sessionId,                // session identifier
    @Nullable String toolUseId,      // tool use ID (may be null)
    @Nullable String lastToolName    // last tool used (may be null)
) implements Message {
    String type();  // Returns "system"
}
```

### TaskNotificationMessage

```java
record TaskNotificationMessage(
    String subtype,                  // always "task_notification"
    Map<String, Object> data,        // raw message data
    String taskId,                   // unique task identifier
    TaskNotificationStatus status,   // COMPLETED, FAILED, or STOPPED
    String outputFile,               // path to task output file
    String summary,                  // human-readable summary
    String uuid,                     // message UUID
    String sessionId,                // session identifier
    @Nullable String toolUseId,      // tool use ID (may be null)
    @Nullable TaskUsage usage        // final usage statistics (may be null)
) implements Message {
    String type();  // Returns "system"
}
```

### TaskNotificationStatus

```java
enum TaskNotificationStatus {
    COMPLETED,  // "completed"
    FAILED,     // "failed"
    STOPPED;    // "stopped"

    String getValue();
    static TaskNotificationStatus fromValue(String);
}
```

### TaskUpdatedMessage

Emitted on `system`/`task_updated` events as a background task moves through its lifecycle. A task's terminal state sometimes arrives **only** as a `task_updated` patch with no accompanying `TaskNotificationMessage` (e.g. a task stopped via `TaskStop` reports `status="killed"` here). Parsed defensively — a missing or non-map `patch` falls back to an empty map, and an unknown/absent status to `null`, so a lifecycle event never crashes parsing.

```java
record TaskUpdatedMessage(
    String subtype,                    // always "task_updated"
    Map<String, Object> data,          // raw message data
    String taskId,                     // unique task identifier ("" if absent)
    Map<String, Object> patch,         // changed fields (e.g. status, end_time); never null
    @Nullable TaskUpdatedStatus status,// patch.status, or null if absent/unknown
    @Nullable String sessionId,        // session identifier (may be null)
    @Nullable String uuid              // message UUID (may be null)
) implements Message {
    String type();        // Returns "system"
    boolean isTerminal(); // true if status is present and in TERMINAL_TASK_STATUSES

    // Statuses that mean the task has finished, spanning both lifecycle
    // vocabularies (task_notification's "stopped" and task_updated's "killed").
    static final Set<String> TERMINAL_TASK_STATUSES =
        Set.of("completed", "failed", "stopped", "killed");
}
```

### TaskUpdatedStatus

```java
enum TaskUpdatedStatus {
    PENDING,    // "pending"   (non-terminal)
    RUNNING,    // "running"   (non-terminal)
    PAUSED,     // "paused"    (non-terminal)
    COMPLETED,  // "completed" (terminal)
    FAILED,     // "failed"    (terminal)
    KILLED;     // "killed"    (terminal — raw form; task_notification maps it to "stopped")

    String getValue();
    static TaskUpdatedStatus fromValue(String);              // throws on unknown
    static @Nullable TaskUpdatedStatus fromValueOrNull(String); // null on unknown/null
}
```

### TaskUsage

```java
record TaskUsage(
    int totalTokens,  // total tokens used
    int toolUses,     // number of tool invocations
    int durationMs    // task duration in milliseconds
)
```

## See Also
- [Message Types Guide](./feature-message-types.md)
