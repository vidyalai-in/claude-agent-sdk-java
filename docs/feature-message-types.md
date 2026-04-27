# Message Types

Understanding the message type system for processing Claude conversations.

## Table of Contents
- [Overview](#overview)
- [Forward Compatibility](#forward-compatibility)
- [Message Type Hierarchy](#message-type-hierarchy)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [Task Messages](#task-messages)
- [MirrorErrorMessage](#mirrorerrormessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [RateLimitEvent](#ratelimitevent)
- [Content Blocks](#content-blocks)
- [Pattern Matching](#pattern-matching)
- [Examples](#examples)

## Overview

The SDK uses a sealed interface hierarchy for type-safe message handling. All messages implement the `Message` sealed interface, enabling exhaustive pattern matching.

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, MirrorErrorMessage, ResultMessage,
    StreamEvent, RateLimitEvent {}
```

## Forward Compatibility

The `MessageParser` is designed for forward compatibility with newer CLI versions. When the CLI emits a message type that the SDK does not recognize, the parser returns `null` instead of throwing an exception. The message iterator automatically skips `null` messages, so your code continues to work correctly even when connected to a newer CLI that emits new message types.

```java
// MessageParser.parse() returns @Nullable Message
// Unknown types return null and are silently skipped by the iterator
for (Message msg : ClaudeSDK.query(prompt)) {
    // Only known message types arrive here; unknown types are silently skipped
    switch (msg) { ... }
}
```

## Message Type Hierarchy

```
Message (sealed interface)
├── UserMessage (record) - Messages from user or tool results
├── AssistantMessage (record) - Messages from Claude
│   └── content: List<ContentBlock>
│       ├── TextBlock - Plain text
│       ├── ThinkingBlock - Claude's reasoning (extended thinking)
│       ├── ToolUseBlock - Tool invocation (caller executes)
│       ├── ToolResultBlock - Tool results
│       ├── ServerToolUseBlock - Server-side tool invocation (advisor, web_search, etc.)
│       └── ServerToolResultBlock - Server-side tool result
├── SystemMessage (record) - System notifications
├── TaskStartedMessage (record) - Task lifecycle: task started
├── TaskProgressMessage (record) - Task lifecycle: task in progress
├── TaskNotificationMessage (record) - Task lifecycle: task completed/failed/stopped
├── MirrorErrorMessage (record) - Non-fatal SessionStore.append() failure
├── ResultMessage (record) - Final result with cost/usage/timing
├── StreamEvent (record) - Partial streaming updates
└── RateLimitEvent (record) - Rate limit status change notifications
```

## UserMessage

Represents messages from the user. Content can be a simple string or a list of structured content blocks (e.g., when tool results are included).

### Fields

```java
record UserMessage(
    Object content,                          // String or List<ContentBlock>
    @Nullable String uuid,                   // Unique message identifier
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult  // Tool execution metadata (file edits, etc.)
) implements Message
```

### Methods

| Method | Description |
|--------|-------------|
| `type()` | Returns `"user"` |
| `contentAsString()` | Returns content as String, or null if structured |
| `contentAsBlocks()` | Returns content as `List<ContentBlock>`, or null if string |

### Example

```java
if (msg instanceof UserMessage user) {
    // Simple string content
    String text = user.contentAsString();
    if (text != null) {
        System.out.println("User said: " + text);
    }

    // Structured content blocks
    List<ContentBlock> blocks = user.contentAsBlocks();
    if (blocks != null) {
        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock result) {
                System.out.println("Tool result for: " + result.toolUseId());
            }
        }
    }

    // Check if this message is from a subagent
    if (user.parentToolUseId() != null) {
        System.out.println("Subagent message, parent tool: " + user.parentToolUseId());
    }

    // Access tool execution metadata (e.g., file edit details)
    Map<String, Object> toolResult = user.toolUseResult();
    if (toolResult != null) {
        System.out.println("File edited: " + toolResult.get("filePath"));
    }
}
```

## AssistantMessage

Represents messages from Claude, containing one or more content blocks.

### Fields

```java
record AssistantMessage(
    List<ContentBlock> content,              // List of content blocks
    String model,                            // Model that generated this response
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable AssistantMessageError error,   // Error if the response contains an error
    @Nullable Map<String, Object> usage,     // Per-turn token usage (input_tokens, output_tokens, cache tokens, etc.)
    @Nullable String messageId,              // Unique message ID from the API (e.g. "msg_01HRq...")
    @Nullable String stopReason,             // Reason the model stopped (e.g. "end_turn")
    @Nullable String sessionId,              // Session ID this message belongs to
    @Nullable String uuid                    // Unique identifier in the session transcript
) implements Message
```

The `usage` map contains per-turn API token consumption data when available, including keys like `input_tokens`, `output_tokens`, and cache-related fields. The `messageId`, `stopReason`, `sessionId`, and `uuid` fields capture API-level identifiers. These are nullable and absent for partial/streaming messages. Backwards-compatible constructors without the newer fields are also available.

### Methods

| Method | Description |
|--------|-------------|
| `type()` | Returns `"assistant"` |
| `getTextContent()` | Concatenates text from all `TextBlock` content blocks |
| `hasToolUse()` | Returns `true` if message contains at least one `ToolUseBlock` |

### AssistantMessageError Values

| Enum Constant | String Value | Description |
|---------------|-------------|-------------|
| `AUTHENTICATION_FAILED` | `"authentication_failed"` | API key invalid or missing |
| `BILLING_ERROR` | `"billing_error"` | Billing issue |
| `RATE_LIMIT` | `"rate_limit"` | Rate limit exceeded |
| `INVALID_REQUEST` | `"invalid_request"` | Malformed request |
| `SERVER_ERROR` | `"server_error"` | Internal server error |
| `UNKNOWN` | `"unknown"` | Unknown or unrecognized error |

### Example

```java
if (msg instanceof AssistantMessage assistant) {
    // Get all text
    String text = assistant.getTextContent();
    System.out.println("Claude: " + text);

    // Which model responded
    System.out.println("Model: " + assistant.model());

    // Process content blocks
    for (ContentBlock block : assistant.content()) {
        switch (block) {
            case TextBlock text ->
                System.out.println("Text: " + text.text());
            case ThinkingBlock thinking ->
                System.out.println("Thinking: " + thinking.thinking());
            case ToolUseBlock tool ->
                System.out.println("Used tool: " + tool.name() + " with input: " + tool.input());
            case ToolResultBlock result ->
                System.out.println("Tool result: " + result.content());
        }
    }

    // Check for errors
    if (assistant.error() != null) {
        System.err.println("Error: " + assistant.error().getValue());
    }

    // Check if this is from a subagent
    if (assistant.parentToolUseId() != null) {
        System.out.println("Subagent response, parent: " + assistant.parentToolUseId());
    }
}
```

## SystemMessage

System-level notifications and events from the CLI.

### Fields

```java
record SystemMessage(
    String subtype,           // Message subtype (e.g., "init")
    Map<String, Object> data  // Full raw message data
) implements Message
```

### Example

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System event: " + system.subtype());
    System.out.println("Data: " + system.data());
}
```

## Task Messages

Task messages are typed subtypes of system messages emitted during subagent task lifecycle events. They implement `Message` directly and have `type()` returning `"system"`. Existing `instanceof SystemMessage` checks do **not** match these — use the specific type.

### TaskStartedMessage

Emitted when a task (subagent) starts.

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
) implements Message
```

| Method | Description |
|--------|-------------|
| `type()` | Returns `"system"` |
| `get(String key)` | Gets a value from the raw data map |

### TaskProgressMessage

Emitted periodically while a task is running.

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
) implements Message
```

| Method | Description |
|--------|-------------|
| `type()` | Returns `"system"` |
| `get(String key)` | Gets a value from the raw data map |

### TaskNotificationMessage

Emitted when a task completes, fails, or is stopped.

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
) implements Message
```

### TaskNotificationStatus

```java
enum TaskNotificationStatus {
    COMPLETED("completed"),
    FAILED("failed"),
    STOPPED("stopped")
}
```

### TaskUsage

Usage statistics for a task:

```java
record TaskUsage(
    int totalTokens,   // total tokens used
    int toolUses,      // number of tool invocations
    int durationMs     // task duration in milliseconds
)
```

### Example: Handling Task Messages

```java
for (Message msg : client.receiveMessages()) {
    switch (msg) {
        case TaskStartedMessage task ->
            System.out.println("Task started: " + task.taskId() + " - " + task.description());
        case TaskProgressMessage task -> {
            TaskUsage usage = task.usage();
            System.out.printf("Task progress: %s | tokens=%d tools=%d%n",
                task.taskId(), usage.totalTokens(), usage.toolUses());
        }
        case TaskNotificationMessage task -> {
            System.out.printf("Task %s: %s (%s)%n",
                task.status(), task.taskId(), task.summary());
            if (task.usage() != null) {
                System.out.println("Final tokens: " + task.usage().totalTokens());
            }
        }
        default -> {}
    }
}
```

## MirrorErrorMessage

Non-fatal system message emitted when a `SessionStore.append()` call fails after retries are exhausted (`MIRROR_APPEND_MAX_ATTEMPTS=3`). The local-disk transcript is already durable, so the session continues unaffected — the mirrored copy in the external store will be missing the failed batch.

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted (null if pre-resolution)
    String error                       // failure description
) implements Message
```

Modeled as a top-level `Message` sealed-interface member (Java records can't extend records). `subtype` is always `"mirror_error"`; the base `data` field carries the raw payload for `SystemMessage`-style downcasts.

### Example: Recovering from a mirror error

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query(prompt, options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            String sid = err.key() != null ? err.key().sessionId() : "<unknown>";
            log.warn("Session {} mirror gap: {}. Will catch up via importSessionToStore.",
                     sid, err.error());
            // Optional: schedule a one-shot import to backfill from the local file
            //   ClaudeSDK.importSessionToStore(sid, store, null);
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { }
    }
}
```

See [Session Store](./feature-session-store.md) for the full mirror flow and retry semantics.

## ResultMessage

Final result sent at the end of each conversation turn with timing, cost, and usage information.

### Fields

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
    @Nullable Object structuredOutput,            // Structured output (when json_schema used)
    @Nullable Map<String, Object> modelUsage,     // Per-model usage breakdown
    @Nullable List<Object> permissionDenials,     // Permission denials during session
    @Nullable List<String> errors,                // Error messages from the CLI
    @Nullable String uuid                         // Unique message identifier in session
) implements Message
```

The `errors` field contains a list of error messages from the CLI, useful for diagnosing non-zero exit codes. The `modelUsage` field provides per-model token breakdowns. Backwards-compatible constructors without the newer fields are also available.

### Common Subtypes

- `"success"` - Conversation completed successfully
- `"error_max_budget_usd"` - Budget limit reached
- `"error_max_turns"` - Max turns limit reached

### Example

```java
if (msg instanceof ResultMessage result) {
    System.out.println("Conversation complete!");
    System.out.println("Subtype: " + result.subtype());
    System.out.println("Duration: " + result.durationMs() + "ms");
    System.out.println("API duration: " + result.durationApiMs() + "ms");
    System.out.println("Turns: " + result.numTurns());
    System.out.println("Session: " + result.sessionId());

    if (result.totalCostUsd() != null) {
        System.out.println("Cost: $" + result.totalCostUsd());
    }

    if (result.usage() != null) {
        System.out.println("Input tokens: " + result.usage().get("input_tokens"));
        System.out.println("Output tokens: " + result.usage().get("output_tokens"));
    }

    if (result.result() != null) {
        System.out.println("Result: " + result.result());
    }

    if (result.structuredOutput() != null) {
        System.out.println("Structured: " + result.structuredOutput());
    }
}
```

## StreamEvent

Partial message updates during streaming. Only emitted when `includePartialMessages` is enabled.

### Fields

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message
```

### Methods

| Method | Description |
|--------|-------------|
| `type()` | Returns `"stream_event"` |
| `eventType()` | Returns event type string from inner event map, or null |

### Common Event Types (from `event.get("type")`)

- `"content_block_start"` - New content block started
- `"content_block_delta"` - Incremental content update
- `"content_block_stop"` - Content block complete
- `"message_start"` - Message started
- `"message_delta"` - Message update
- `"message_stop"` - Message complete

### Example

```java
if (msg instanceof StreamEvent event) {
    System.out.println("Stream event: " + event.eventType());
    System.out.println("UUID: " + event.uuid());
    System.out.println("Session: " + event.sessionId());
    // Access raw event data
    Object delta = event.event().get("delta");
    if (delta != null) {
        System.out.println("Delta: " + delta);
    }
}
```

## RateLimitEvent

Emitted by the CLI whenever the rate limit status transitions. Use this to warn users before they hit a hard limit or to gracefully back off when the limit is exceeded.

### Fields

```java
record RateLimitEvent(
    RateLimitInfo rateLimitInfo,  // Detailed rate limit status information
    String uuid,                  // Unique identifier for this event
    String sessionId              // Session identifier
) implements Message
```

### RateLimitInfo

```java
record RateLimitInfo(
    RateLimitStatus status,                      // Current rate limit status
    @Nullable Long resetsAt,                     // Unix timestamp when the rate limit window resets
    @Nullable RateLimitType rateLimitType,       // Which rate limit window applies
    @Nullable Double utilization,               // Fraction of the rate limit consumed (0.0–1.0)
    @Nullable RateLimitStatus overageStatus,    // Status of overage/pay-as-you-go usage
    @Nullable Long overageResetsAt,             // Unix timestamp when overage window resets
    @Nullable String overageDisabledReason,     // Why overage is unavailable if rejected
    @Nullable Map<String, Object> raw           // Full raw map including any unmodeled fields
)
```

### RateLimitStatus

| Enum Constant | String Value | Description |
|---------------|-------------|-------------|
| `ALLOWED` | `"allowed"` | Within rate limits, no action needed |
| `ALLOWED_WARNING` | `"allowed_warning"` | Approaching the rate limit — warn the user |
| `REJECTED` | `"rejected"` | Rate limit has been hit — requests will be rejected |

### RateLimitType

| Enum Constant | String Value | Description |
|---------------|-------------|-------------|
| `FIVE_HOUR` | `"five_hour"` | 5-hour rolling window |
| `SEVEN_DAY` | `"seven_day"` | 7-day rolling window |
| `SEVEN_DAY_OPUS` | `"seven_day_opus"` | 7-day rolling window for Opus models |
| `SEVEN_DAY_SONNET` | `"seven_day_sonnet"` | 7-day rolling window for Sonnet models |
| `OVERAGE` | `"overage"` | Overage / pay-as-you-go limit |

### Example

```java
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof RateLimitEvent rle) {
        RateLimitInfo info = rle.rateLimitInfo();
        switch (info.status()) {
            case ALLOWED -> {
                // No action needed
            }
            case ALLOWED_WARNING -> {
                System.out.printf("Rate limit warning: %.0f%% used%n",
                    info.utilization() != null ? info.utilization() * 100 : 0);
                if (info.resetsAt() != null) {
                    System.out.println("Resets at: " + Instant.ofEpochSecond(info.resetsAt()));
                }
            }
            case REJECTED -> {
                System.err.println("Rate limit exceeded! Limit type: " + info.rateLimitType());
                if (info.resetsAt() != null) {
                    System.err.println("Resets at: " + Instant.ofEpochSecond(info.resetsAt()));
                }
            }
        }
    }
}
```

## Content Blocks

Assistant messages (and structured user messages) contain content blocks.

### ContentBlock Hierarchy

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock {}
```

### TextBlock

Plain text content from Claude.

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

Claude's internal reasoning when extended thinking is enabled. Includes a cryptographic signature.

```java
record ThinkingBlock(
    String thinking,   // Thinking content
    String signature   // Cryptographic signature for the thinking block
) implements ContentBlock
```

### ToolUseBlock

Tool invocation by Claude.

```java
record ToolUseBlock(
    String id,                          // Tool use ID (matches ToolResultBlock.toolUseId)
    String name,                        // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

Results from tool execution.

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content (String or structured)
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

Server-side tool invocation that the API executes on the model's behalf — the caller never returns a result. Used for `advisor`, `web_search`, `web_fetch`, `code_execution`, `bash_code_execution`, `text_editor_code_execution`, `tool_search_tool_regex`, and `tool_search_tool_bm25`.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // One of ServerToolName values (kept as raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

The `name` field is a discriminator — branch on it to know which server tool was invoked. The `ServerToolName` enum lists the recognized values:

```java
public enum ServerToolName {
    ADVISOR("advisor"),
    WEB_SEARCH("web_search"),
    WEB_FETCH("web_fetch"),
    CODE_EXECUTION("code_execution"),
    BASH_CODE_EXECUTION("bash_code_execution"),
    TEXT_EDITOR_CODE_EXECUTION("text_editor_code_execution"),
    TOOL_SEARCH_TOOL_REGEX("tool_search_tool_regex"),
    TOOL_SEARCH_TOOL_BM25("tool_search_tool_bm25");
}
```

### ServerToolResultBlock

Result block returned for a server-side tool call. Mirrors `ToolResultBlock`'s shape; `content` is the raw map from the API, opaque to this layer — callers that care about a specific server tool's result schema can inspect `content.get("type")`.

The CLI emits these as `advisor_tool_result` content blocks (the only currently shipped server-tool result type); they parse into `ServerToolResultBlock`.

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content (e.g. {"type":"advisor_result", ...})
) implements ContentBlock
```

For the advisor tool specifically, the `content` map's `type` field will be one of:
- `"advisor_result"` — text result with a `text` field
- `"advisor_redacted_result"` — encrypted blob with an `encrypted_content` field
- `"advisor_tool_result_error"` — error payload

## Pattern Matching

Java's pattern matching makes message handling elegant and type-safe.

### Switch Expression

```java
String result = switch (message) {
    case UserMessage u -> "User: " + u.contentAsString();
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case SystemMessage s -> "System: " + s.subtype();
    case TaskStartedMessage t -> "Task started: " + t.taskId();
    case TaskProgressMessage t -> "Task progress: " + t.taskId();
    case TaskNotificationMessage t -> "Task done: " + t.status();
    case MirrorErrorMessage m -> "Mirror error: " + m.error();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    case StreamEvent e -> "Streaming: " + e.eventType();
    case RateLimitEvent rle -> "Rate limit: " + rle.rateLimitInfo().status();
};
```

### Nested Pattern Matching

```java
switch (message) {
    case AssistantMessage assistant -> {
        for (ContentBlock block : assistant.content()) {
            switch (block) {
                case TextBlock text ->
                    System.out.println(text.text());
                case ThinkingBlock thinking ->
                    System.out.println("[Thinking] " + thinking.thinking());
                case ToolUseBlock tool ->
                    System.out.println("[Tool] " + tool.name() + ": " + tool.input());
                case ToolResultBlock result ->
                    System.out.println("[Result] " + result.content());
                case ServerToolUseBlock stu ->
                    System.out.println("[Server Tool] " + stu.name() + ": " + stu.input());
                case ServerToolResultBlock str ->
                    System.out.println("[Server Tool Result] " + str.content());
            }
        }
    }
    case MirrorErrorMessage err ->
        System.err.println("Mirror error: " + err.error());
    case ResultMessage result ->
        System.out.println("Done in " + result.durationMs() + "ms, cost: $" + result.totalCostUsd());
    default -> {}
}
```

### Instance of with Pattern Variables

```java
if (message instanceof AssistantMessage assistant) {
    // 'assistant' variable available here
    for (ContentBlock block : assistant.content()) {
        if (block instanceof ToolUseBlock tool) {
            // 'tool' variable available here
            processToolUse(tool.name(), tool.input());
        }
    }
}
```

## Examples

### Example 1: Processing All Messages

```java
for (Message msg : ClaudeSDK.query(prompt, options)) {
    switch (msg) {
        case UserMessage user ->
            log("User", user.contentAsString());

        case AssistantMessage assistant -> {
            log("Claude", assistant.getTextContent());
            log("Model", assistant.model());
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    log("Tool Used", tool.name());
                }
            }
        }

        case ResultMessage result -> {
            log("Result", "Cost: $" + result.totalCostUsd());
            log("Result", "Duration: " + result.durationMs() + "ms");
            if (result.usage() != null) {
                log("Tokens", result.usage().get("input_tokens") + " in / " +
                             result.usage().get("output_tokens") + " out");
            }
        }

        case SystemMessage system ->
            log("System", system.subtype());

        case StreamEvent event ->
            log("Stream", event.eventType());

        case RateLimitEvent rle ->
            log("RateLimit", rle.rateLimitInfo().status().getValue());
    }
}
```

### Example 2: Extracting Specific Information

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Find last assistant message
Optional<AssistantMessage> lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n\n"));

// Get result
Optional<ResultMessage> result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst();

// Get all tool uses
List<ToolUseBlock> toolUses = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .flatMap(m -> ((AssistantMessage) m).content().stream())
    .filter(b -> b instanceof ToolUseBlock)
    .map(b -> (ToolUseBlock) b)
    .toList();
```

### Example 3: Handling Errors

```java
for (Message msg : messages) {
    switch (msg) {
        case AssistantMessage assistant -> {
            if (assistant.error() != null) {
                System.err.println("Assistant error: " + assistant.error().getValue());
                handleAssistantError(assistant.error());
            }
        }

        case ResultMessage result -> {
            if (result.isError()) {
                System.err.println("Result error subtype: " + result.subtype());
                handleResultError(result);
            }
        }

        default -> {}
    }
}
```

### Example 4: Tool Use Tracking

```java
Map<String, Integer> toolUsage = new HashMap<>();

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ToolUseBlock tool) {
                toolUsage.merge(tool.name(), 1, Integer::sum);
            }
        }
    }
}

System.out.println("Tool usage:");
toolUsage.forEach((tool, count) ->
    System.out.println(tool + ": " + count + " times"));
```

### Example 5: Cost and Token Analysis

```java
double totalCost = 0.0;
int totalDurationMs = 0;
int inputTokens = 0;
int outputTokens = 0;

for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        totalDurationMs += result.durationMs();
        if (result.totalCostUsd() != null) {
            totalCost += result.totalCostUsd();
        }
        if (result.usage() != null) {
            Object in = result.usage().get("input_tokens");
            Object out = result.usage().get("output_tokens");
            if (in instanceof Number n) inputTokens += n.intValue();
            if (out instanceof Number n) outputTokens += n.intValue();
        }
    }
}

System.out.println("Total cost: $" + totalCost);
System.out.println("Total duration: " + totalDurationMs + "ms");
System.out.println("Input tokens: " + inputTokens);
System.out.println("Output tokens: " + outputTokens);
```

### Example 6: Subagent Message Filtering

```java
// Separate top-level messages from subagent messages
List<AssistantMessage> topLevel = new ArrayList<>();
List<AssistantMessage> subagent = new ArrayList<>();

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        if (assistant.parentToolUseId() != null) {
            subagent.add(assistant);
        } else {
            topLevel.add(assistant);
        }
    }
}
```

## See Also

- [Simple Queries](./feature-simple-queries.md) - Processing messages from queries
- [Interactive Conversations](./feature-interactive-conversations.md) - Processing messages from client
- [Streaming Events](./feature-streaming-events.md) - StreamEvent details
- [API Reference: Message Types](./api-message-types.md) - Complete API documentation
