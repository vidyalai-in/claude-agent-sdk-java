# 消息类型

理解用于处理 Claude 会话的消息类型系统。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-message-types.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [向前兼容](#向前兼容)
- [消息类型层次](#消息类型层次)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [任务消息](#任务消息)
- [MirrorErrorMessage](#mirrorerrormessage)
- [HookEventMessage](#hookeventmessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [RateLimitEvent](#ratelimitevent)
- [内容块](#内容块)
- [模式匹配](#模式匹配)
- [示例](#示例)

## 概览

SDK 使用密封接口层次来实现类型安全的消息处理。所有消息都实现 `Message` 密封接口，从而支持穷尽的模式匹配。

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}
```

## 向前兼容

`MessageParser` 的设计考虑了对更新版本 CLI 的向前兼容。当 CLI 发出 SDK 不认识的消息类型时，解析器返回 `null` 而不是抛出异常。消息迭代器会自动跳过 `null` 消息，因此即便连接到会发出新消息类型的更新版 CLI，你的代码仍然能正常工作。

```java
// MessageParser.parse() returns @Nullable Message
// Unknown types return null and are silently skipped by the iterator
for (Message msg : ClaudeSDK.query(prompt)) {
    // Only known message types arrive here; unknown types are silently skipped
    switch (msg) { ... }
}
```

**未知类型与格式错误的内容不同。** 返回 `null` 只适用于*无法识别的消息类型*。结构无效的*已知*类型（`user` / `assistant`）是另一回事 —— 解析器会抛出 `MessageParseException`，而不是默默丢弃，这样真正的协议违规不会被掩盖。自 0.1.18 起这一点被明确强制：`content` 不是列表的 `assistant` 消息（例如裸字符串）会抛出 `"Invalid assistant content (expected list, got …)"`，而 `content` 列表中不是对象的元素（`user` 和 `assistant` 消息都适用）会抛出 `"Invalid content block (expected dict, got …)"`。以前这些会以原始的 `ClassCastException` 形式出现；现在统一报告为 `MessageParseException`（与解析器的其他结构校验一致）。

## 消息类型层次

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
├── TaskUpdatedMessage (record) - Task lifecycle: state-change patch (may be the only terminal signal)
├── MirrorErrorMessage (record) - Non-fatal SessionStore.append() failure
├── HookEventMessage (record) - Hook lifecycle events (when includeHookEvents enabled)
├── ResultMessage (record) - Final result with cost/usage/timing
├── StreamEvent (record) - Partial streaming updates
├── RateLimitEvent (record) - Rate limit status change notifications
└── ConversationResetMessage (record) - Conversation replaced mid-session (e.g. /clear)
```

> **兼容性提示（v0.1.23）。** `ConversationResetMessage` 扩大了这个密封联合体，
> 因此没有 `default` 分支的穷尽 `switch` 在补上相应的 case 之前会编译失败。
> 参见[模式匹配](#模式匹配)。

## UserMessage

表示来自用户的消息。内容可以是简单字符串，也可以是结构化内容块的列表（例如包含工具结果时）。

### 字段

```java
record UserMessage(
    Object content,                          // String or List<ContentBlock>
    @Nullable String uuid,                   // Unique message identifier
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult  // Tool execution metadata (file edits, etc.)
) implements Message
```

### 方法

| 方法 | 说明 |
|--------|-------------|
| `type()` | 返回 `"user"` |
| `contentAsString()` | 以 String 返回内容；若为结构化内容则返回 null |
| `contentAsBlocks()` | 以 `List<ContentBlock>` 返回内容；若为字符串则返回 null |

### 示例

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

表示来自 Claude 的消息，包含一个或多个内容块。

### 字段

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

`usage` map 在可用时包含每轮的 API token 消耗数据，包括 `input_tokens`、`output_tokens` 以及缓存相关字段。`messageId`、`stopReason`、`sessionId` 和 `uuid` 字段记录 API 级别的标识符。这些字段可为 null，在部分／流式消息中不存在。也提供了不含较新字段的向后兼容构造函数。

### 方法

| 方法 | 说明 |
|--------|-------------|
| `type()` | 返回 `"assistant"` |
| `getTextContent()` | 拼接所有 `TextBlock` 内容块中的文本 |
| `hasToolUse()` | 消息中至少含一个 `ToolUseBlock` 时返回 `true` |

### AssistantMessageError 取值

| 枚举常量 | 字符串值 | 说明 |
|---------------|-------------|-------------|
| `AUTHENTICATION_FAILED` | `"authentication_failed"` | API key 无效或缺失 |
| `BILLING_ERROR` | `"billing_error"` | 计费问题 |
| `RATE_LIMIT` | `"rate_limit"` | 超出速率限制 |
| `INVALID_REQUEST` | `"invalid_request"` | 请求格式错误 |
| `SERVER_ERROR` | `"server_error"` | 服务器内部错误 |
| `UNKNOWN` | `"unknown"` | 未知或无法识别的错误 |

### 示例

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

来自 CLI 的系统级通知与事件。

### 字段

```java
record SystemMessage(
    String subtype,           // Message subtype (e.g., "init")
    Map<String, Object> data  // Full raw message data
) implements Message
```

### 示例

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System event: " + system.subtype());
    System.out.println("Data: " + system.data());
}
```

## 任务消息

任务消息是在子 agent 任务生命周期事件中发出的、有类型的系统消息子类。它们直接实现 `Message`，且 `type()` 返回 `"system"`。现有的 `instanceof SystemMessage` 检查**不会**匹配它们 —— 请使用具体类型。

### TaskStartedMessage

任务（子 agent）启动时发出。

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

| 方法 | 说明 |
|--------|-------------|
| `type()` | 返回 `"system"` |
| `get(String key)` | 从原始 data map 中取值 |

### TaskProgressMessage

任务运行期间定期发出。

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

| 方法 | 说明 |
|--------|-------------|
| `type()` | 返回 `"system"` |
| `get(String key)` | 从原始 data map 中取值 |

### TaskNotificationMessage

任务完成、失败或被停止时发出。

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

### TaskUpdatedMessage

在后台任务经历其生命周期时，随 `system`/`task_updated` 事件发出。`patch` 携带发生变化的字段（例如 `status`、`end_time`）。

任务的终态有时**只**通过 `TaskUpdatedMessage` 到达，而没有相应的 `TaskNotificationMessage` —— 例如通过 `TaskStop` 停止的任务在这里报告 `status="killed"`，对应的通知有时会被抑制。跟踪活跃任务 ID 的消费方应在**任一**消息出现终态时清除它们。

解析是防御性的 —— 缺失或非 map 的 `patch` 会回退为空 map，未知／缺失的 status 回退为 `null`，因此生命周期事件绝不会让解析崩溃。

```java
record TaskUpdatedMessage(
    String subtype,                     // always "task_updated"
    Map<String, Object> data,           // raw message data
    String taskId,                      // unique task identifier ("" if absent)
    Map<String, Object> patch,          // changed fields; never null (empty if absent)
    @Nullable TaskUpdatedStatus status, // patch.status, or null if absent/unknown
    @Nullable String sessionId,         // session identifier (may be null)
    @Nullable String uuid               // message UUID (may be null)
) implements Message
```

| 方法 | 说明 |
|--------|-------------|
| `type()` | 返回 `"system"` |
| `isTerminal()` | 当 `status` 存在且属于 `TERMINAL_TASK_STATUSES` 时为 `true` |
| `get(String key)` | 从原始 data map 中取值 |
| `TaskUpdatedMessage.TERMINAL_TASK_STATUSES` | `Set.of("completed", "failed", "stopped", "killed")` —— 覆盖两套任务生命周期词汇的终态 |

### TaskUpdatedStatus

`task_updated` 报告的是原始的 `KILLED`；CLI 只有在发出 `task_notification` 时才把它映射为 `STOPPED`（`TaskNotificationStatus`）。

```java
enum TaskUpdatedStatus {
    PENDING("pending"),     // non-terminal
    RUNNING("running"),     // non-terminal
    PAUSED("paused"),       // non-terminal
    COMPLETED("completed"), // terminal
    FAILED("failed"),       // terminal
    KILLED("killed")        // terminal
}
```

`TaskUpdatedStatus.fromValueOrNull(String)` 解析原始状态时，遇到未知值或 `null` 不会抛出异常（用于防御性解析）；`fromValue(String)` 遇到未知值会抛出异常。

### TaskUsage

任务的用量统计：

```java
record TaskUsage(
    int totalTokens,   // total tokens used
    int toolUses,      // number of tool invocations
    int durationMs     // task duration in milliseconds
)
```

### 示例：处理任务消息

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
        case TaskUpdatedMessage task -> {
            System.out.printf("Task updated: %s -> %s%n", task.taskId(), task.status());
            if (task.isTerminal()) {
                // Terminal state may arrive ONLY here (no TaskNotificationMessage),
                // e.g. status=KILLED for a task stopped via TaskStop.
                System.out.println("Task finished (terminal): " + task.taskId());
            }
        }
        default -> {}
    }
}
```

## MirrorErrorMessage

当 `SessionStore.append()` 在重试耗尽（`MIRROR_APPEND_MAX_ATTEMPTS=3`）后仍然失败时发出的非致命系统消息。本地磁盘上的记录已经持久化，因此会话不受影响 —— 只是外部存储中的镜像副本会缺少失败的那一批。

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted (null if pre-resolution)
    String error                       // failure description
) implements Message
```

它被建模为顶层 `Message` 密封接口的成员（Java record 不能继承 record）。`subtype` 始终是 `"mirror_error"`；基础的 `data` 字段携带原始负载，便于以 `SystemMessage` 风格向下转型。

### 示例：从镜像错误中恢复

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

完整的镜像流程与重试语义见 [Session Store](./feature-session-store.md)。

## HookEventMessage

被暴露到消息流中的钩子生命周期事件。只有在 `ClaudeAgentOptions` 上设置了 `includeHookEvents(true)` 时才会发出。参见 [钩子 → 消息流上的钩子生命周期事件](./feature-hooks.md#消息流上的钩子生命周期事件)。

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

`type()` 返回 `"system"` 以便与 `SystemMessage` 对称，但 `HookEventMessage` 是独立的密封接口成员 —— `instanceof SystemMessage` **不会**匹配。请直接对 `HookEventMessage` 分支处理。

`subtype` 的取值：

- `"hook_started"` —— 钩子开始执行时。
- `"hook_response"` —— 钩子完成时；`data` 中包含 `output`、`exit_code` 和 `outcome` 键。

```java
for (Message msg : ClaudeSDK.query(prompt, options.toBuilder().includeHookEvents(true).build())) {
    if (msg instanceof HookEventMessage hook) {
        System.out.printf("[%s] %s%n", hook.subtype(), hook.hookEventName());
        if ("hook_response".equals(hook.subtype())) {
            System.out.println("  outcome: " + hook.get("outcome"));
        }
    }
}
```

## ResultMessage

在每一轮会话结束时发送的最终结果，包含耗时、花费和用量信息。

### 字段

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
    @Nullable Map<String, ModelUsage> modelUsage,  // Per-model usage breakdown
    @Nullable List<Object> permissionDenials,     // Permission denials during session
    @Nullable DeferredToolUse deferredToolUse,    // Tool call deferred by a PreToolUse hook
    @Nullable List<String> errors,                // Error messages from the CLI
    @Nullable Integer apiErrorStatus,             // HTTP status of failing API call when isError=true and subtype="success"
    @Nullable String uuid,                        // Unique message identifier in session
    @Nullable String terminalReason               // Why the query loop terminated
) implements Message
```

`errors` 字段包含来自 CLI 的错误消息列表，有助于诊断非零退出码。`modelUsage` 字段提供按模型划分的 token 明细，类型为 `ModelUsage` —— 参见 [API 参考 → ModelUsage](./api-message-types.md#modelusage)。`deferredToolUse` 字段在 `PreToolUse` 钩子返回 `permissionDecision: "defer"` 时被设置 —— 参见[钩子 → 权限决策 `"defer"`](./feature-hooks.md#权限决策-defer)。`apiErrorStatus` 字段在 `isError=true` 且 `subtype="success"` 时（API 失败但会话本身完成了）携带失败 API 调用的 HTTP 状态码（例如 `429`、`500`、`529`）；记录它是安全的，因为它不含任何消息内容。也提供了不含较新字段的向后兼容构造函数。

`terminalReason` 字段报告查询循环为何结束 —— `"completed"`、`"max_turns"`、`"aborted_streaming"`、`"aborted_tools"`。两个 `aborted_*` 值表示该轮通过 `ClaudeSDKClient.interrupt()` 被取消，这让调用方无需借助单独的结果 subtype 就能得到明确的取消标记：

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Long running task");
    client.interrupt();

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof ResultMessage result) {
            // "aborted_streaming" or "aborted_tools" after an interrupt
            System.out.println("Ended because: " + result.terminalReason());
        }
    }
}
```

当 CLI 没有报告终止原因时它为 `null` —— 例如较旧的 CLI 版本，或绕过查询循环的结果（比如本地斜杠命令）。

### DeferredToolUse

```java
record DeferredToolUse(
    String id,                       // unique identifier of the deferred tool call
    String name,                     // tool name
    Map<String, Object> input        // tool input arguments
)
```

### 常见 subtype

- `"success"` —— 会话成功完成
- `"error_max_budget_usd"` —— 达到预算上限
- `"error_max_turns"` —— 达到最大轮数上限

### 示例

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

流式传输期间的部分消息更新。只有启用 `includePartialMessages` 时才会发出。

### 字段

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message
```

### 方法

| 方法 | 说明 |
|--------|-------------|
| `type()` | 返回 `"stream_event"` |
| `eventType()` | 从内部 event map 返回事件类型字符串，若无则为 null |

### 常见事件类型（来自 `event.get("type")`）

- `"content_block_start"` —— 新内容块开始
- `"content_block_delta"` —— 内容增量更新
- `"content_block_stop"` —— 内容块结束
- `"message_start"` —— 消息开始
- `"message_delta"` —— 消息更新
- `"message_stop"` —— 消息结束

### 示例

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

每当速率限制状态发生变化时由 CLI 发出。可用它在用户触及硬性上限前发出警告，或在超限时优雅地退避。

### 字段

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

| 枚举常量 | 字符串值 | 说明 |
|---------------|-------------|-------------|
| `ALLOWED` | `"allowed"` | 在限制内，无需处理 |
| `ALLOWED_WARNING` | `"allowed_warning"` | 接近限制 —— 请提醒用户 |
| `REJECTED` | `"rejected"` | 已触及限制 —— 请求会被拒绝 |

### RateLimitType

| 枚举常量 | 字符串值 | 说明 |
|---------------|-------------|-------------|
| `FIVE_HOUR` | `"five_hour"` | 5 小时滚动窗口 |
| `SEVEN_DAY` | `"seven_day"` | 7 天滚动窗口 |
| `SEVEN_DAY_OPUS` | `"seven_day_opus"` | Opus 模型的 7 天滚动窗口 |
| `SEVEN_DAY_SONNET` | `"seven_day_sonnet"` | Sonnet 模型的 7 天滚动窗口 |
| `OVERAGE` | `"overage"` | 超额／按量付费限制 |

### 示例

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

## ConversationResetMessage

在不结束连接的情况下替换会话对话时发出 —— 例如 `/clear` 之后，或任何在会话中途丢弃记录的流程。在 v0.1.23 之前，解析器会默默丢弃这一帧，因此应用从未看到重置，包括并非自己发起的重置。

### 字段

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message
```

### 它为什么重要

重置会清空对话历史，*同时*把后续 `ResultMessage` 上报告的累计值归零。如果你在一个长期存在的流式会话中累计花费或 token 用量，这一帧是你在它们归零之前做快照的唯一信号。

它也标记了 session id 的边界：`newConversationId` 是供 UI 挂载空白记录的键，**不是**后续消息的 `sessionId`。重置之后的消息带有新的 `sessionId` —— 请从下一条消息中读取。

```java
double runningCostUsd = 0.0;

for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case ResultMessage r -> {
            if (r.totalCostUsd() != null) runningCostUsd += r.totalCostUsd();
        }
        case ConversationResetMessage reset -> {
            System.out.printf("Reset: %s -> new conversation %s%n",
                    reset.sessionId(), reset.newConversationId());
            archive(runningCostUsd);   // CLI counters restart from zero here
        }
        default -> { }
    }
}
```

## 消息来源

`UserMessage.origin()` 和 `ResultMessage.origin()` 揭示某一轮*为何*被发起。在流式输入模式下，一条连接会把你的应用发送的轮次，与会话自行注入的轮次交织在一起 —— 后台任务通知、触发的定时任务提示、MCP 通道消息、从对等会话转发的消息。在 `ResultMessage` 上，该字段报告*触发*该轮的那条消息的来源，这正是你区分“这是对我的提示的回答”和“这是对某个后台任务的回答”的依据。

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
    render(origin.subkind());   // scheduled-trigger / peer-send-message, or null
}
```

当 CLI 没有为消息标注来源时 `origin` 为 null —— 对于你发送的提示，这是正常情况。若想让你自己的轮次被标注，请自行在消息 map 上打标，并通过 `ClaudeSDKClient.query(Iterator)` 发送：

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", prompt));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));

client.query(List.of(message).iterator());
```

来自 SDK 宿主的来源中，只有 `human` 这一 kind 会被采信，且要求 Claude Code >= 2.1.210。工具结果类的用户消息永远不带 origin。

无法识别的 kind 会保持可见而不是变成错误：`kind()` 为 null，同时 `kindValue()` 保存线上字符串，`isHuman()` 为 false —— 因此未知的来源标注总是读作“非人类”。完整的 CLI 对象保留在 `raw()` 上。逐字段参考：[MessageOrigin](./api-message-types.md#messageorigin)。

`from`、`name` 和 `fromSession` 等字段是**发送方自述的** —— 可用于回复路由与展示，切勿当作身份证明。

## 内容块

助手消息（以及结构化的用户消息）包含内容块。

### ContentBlock 层次

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock {}
```

由于接口是密封的，只要 `permits` 子句扩大，对它的穷尽 `switch` 就会编译失败。请优先显式处理 `UnknownBlock`，而不是添加 `default` 分支 —— 这样下一个新块类型会成为你可以据此行动的编译错误，而不是悄无声息地落入兜底分支。

### TextBlock

来自 Claude 的纯文本内容。

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

启用扩展思考时 Claude 的内部推理。包含一个加密签名。

```java
record ThinkingBlock(
    String thinking,   // Thinking content
    String signature   // Cryptographic signature for the thinking block
) implements ContentBlock
```

### ToolUseBlock

Claude 发起的工具调用。

```java
record ToolUseBlock(
    String id,                          // Tool use ID (matches ToolResultBlock.toolUseId)
    String name,                        // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

工具执行的结果。

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content (String or structured)
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

由 API 代表模型执行的服务端工具调用 —— 调用方永远不需要返回结果。用于 `advisor`、`web_search`、`web_fetch`、`code_execution`、`bash_code_execution`、`text_editor_code_execution`、`tool_search_tool_regex` 和 `tool_search_tool_bm25`。

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // One of ServerToolName values (kept as raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`name` 字段是判别器 —— 根据它分支即可知道调用了哪个服务端工具。`ServerToolName` 枚举列出了已识别的取值：

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

服务端工具调用返回的结果块。形状与 `ToolResultBlock` 相同；`content` 是来自 API 的原始 map，对这一层而言是不透明的 —— 关心某个服务端工具结果 schema 的调用方可以检查 `content.get("type")`。

CLI 以 `advisor_tool_result` 内容块的形式发出它们（目前唯一已发布的服务端工具结果类型）；它们会解析为 `ServerToolResultBlock`。

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content (e.g. {"type":"advisor_result", ...})
) implements ContentBlock
```

具体到 advisor 工具，`content` map 的 `type` 字段会是以下之一：
- `"advisor_result"` —— 含 `text` 字段的文本结果
- `"advisor_redacted_result"` —— 含 `encrypted_content` 字段的加密块
- `"advisor_tool_result_error"` —— 错误负载

### ImageBlock 与 DocumentBlock

两者来自同一流程：用 `Read` 工具读取 PDF。`tool_result` 本身只公布页数，文件会在**另一条用户消息**中随后到达。CLI 对该消息使用两种形状之一，且在 CLI 2.1.218 上针对同一个 1.5 MB 文件的不同运行中，两种形状都曾出现：

- 每个渲染页面一个 `image` 块（`image/jpeg`，base64），或
- 一个承载整份 PDF 的 `document` 块（`application/pdf`，base64）。

```java
record ImageBlock(Map<String, Object> source) implements ContentBlock
record DocumentBlock(Map<String, Object> source) implements ContentBlock
```

`source` 保留为原始 map，因为 API 定义了 `base64`、`url`、`file`、`text` 和 `content` 等来源形状，而且还可能新增。以下三个访问器覆盖 base64 的情形，键缺失或不是字符串时各自返回 `null`：

```java
for (ContentBlock block : userMessage.contentAsBlocks()) {
    switch (block) {
        case ImageBlock image -> System.out.printf("page image: %s, %d base64 chars%n",
                image.mediaType(), image.data() == null ? 0 : image.data().length());
        case DocumentBlock doc -> System.out.printf("document: %s%n", doc.mediaType());
        default -> { }
    }
}
```

| 方法 | 返回 |
|---|---|
| `sourceType()` | `source["type"]`，例如 `"base64"` |
| `mediaType()` | `source["media_type"]`，例如 `"image/jpeg"` 或 `"application/pdf"` |
| `data()` | `source["data"]`，即 base64 负载 |

> **读取 PDF 需要调高 `maxBufferSize`。** 这些块是 base64 —— 每 3 字节变 4 字节 —— 且位于 CLI stdout 的同一行上，因此一个 1.5 MB 的 PDF 大约是 2.1 MB 的一行，而默认值只有 1 MB。默认值未作改动（与 Python SDK 保持一致），所以任何要读取文件的调用方都必须显式设置：
>
> ```java
> ClaudeAgentOptions options = ClaudeAgentOptions.builder()
>     .allowedTools(List.of("Read"))
>     .maxBufferSize(16 * 1024 * 1024)
>     .build();
> ```
>
> 没有这一步，任何被授予对某个 PDF 目录 `Read` 权限的 agent 都会在运行中途失败。

### UnknownBlock

针对本 SDK 版本尚未建模的块类型的向前兼容。

```java
record UnknownBlock(
    String type,             // The unrecognised discriminator
    Map<String, Object> raw  // The block, preserved whole
) implements ContentBlock
```

`MessageParser` 早已对无法识别的*消息*类型返回 `null`，使新版 CLI 不会让旧版 SDK 崩溃；现在内容块也以同样的方式处理，而不是抛出异常。无法识别的块被完整保留，并由 `in.vidyalai.claude.sdk.internal.MessageParser` 针对每种类型以 `WARNING` 级别记录一次。

这正是 `image` 和 `document` 曾经需要却没有的东西。此前的行为会抛出 `MessageParseException`，从而杀死读取线程、丢弃该消息中的所有其他块（包括模型已经产出的文本），并以一个调用方从未请求过的类型名作为 JSON 解码失败暴露出来。

## 模式匹配

Java 的模式匹配让消息处理既优雅又类型安全。

### switch 表达式

```java
String result = switch (message) {
    case UserMessage u -> "User: " + u.contentAsString();
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case SystemMessage s -> "System: " + s.subtype();
    case TaskStartedMessage t -> "Task started: " + t.taskId();
    case TaskProgressMessage t -> "Task progress: " + t.taskId();
    case TaskNotificationMessage t -> "Task done: " + t.status();
    case TaskUpdatedMessage t -> "Task updated: " + t.status();
    case MirrorErrorMessage m -> "Mirror error: " + m.error();
    case HookEventMessage h -> "Hook " + h.subtype() + ": " + h.hookEventName();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    case StreamEvent e -> "Streaming: " + e.eventType();
    case RateLimitEvent rle -> "Rate limit: " + rle.rateLimitInfo().status();
    case ConversationResetMessage c -> "Conversation reset: " + c.newConversationId();
};
```

因为 `Message` 是密封的，像这样没有 `default` 的 `switch` 必须列出每一种被允许的类型，而编译器会强制这一点。这正是重点所在：新增一种消息类型时，你会在每个穷尽 switch 处得到编译错误，而不是在运行时默默丢帧。

代价是，对这类代码而言新增类型是源码级的破坏性变更。`ConversationResetMessage` 在 v0.1.23 中正是如此。如果你更愿意悄悄吸收未来的新增类型，请加上 `default` 分支：

```java
String result = switch (message) {
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    default -> "(other)";        // future message types land here
};
```

### 嵌套模式匹配

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

### 带模式变量的 instanceof

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

## 示例

### 示例 1：处理所有消息

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

### 示例 2：提取特定信息

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

### 示例 3：处理错误

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

### 示例 4：跟踪工具使用

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

### 示例 5：花费与 token 分析

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

### 示例 6：过滤子 agent 消息

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

## 另见

- [简单查询](./feature-simple-queries.md) —— 处理查询返回的消息
- [交互式会话](./feature-interactive-conversations.md) —— 处理客户端返回的消息
- [流式事件](./feature-streaming-events.md) —— StreamEvent 详解
- [API 参考：消息类型](./api-message-types.md) —— 完整的 API 文档
