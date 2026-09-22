# 消息类型 API 参考

Claude 消息的类型层次结构。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../api-message-types.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## MessageParser

`MessageParser` 类把来自 CLI 的原始 JSON map 转换为类型化的 `Message` 对象。

```java
public final class MessageParser {
    // Returns null for unrecognized message types (forward compatibility)
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException;
}
```

对于无法识别的消息类型，它返回 `null` 而不是抛出异常，从而让 SDK 与可能发出新消息类型的较新 CLI
版本保持兼容。

## Message 接口

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent,
    ConversationResetMessage {
    String type();
}
```

> **兼容性说明（v0.1.23）。** `ConversationResetMessage` 扩大了这个联合类型。由于 `Message` 是密封的，
> 没有 `default` 分支的穷尽式 `switch` 会无法编译，直到你补上 `ConversationResetMessage` 分支。如果
> 你更希望默默吸收未来的新增类型，请改为添加 `default ->` 分支。

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

不含 `origin` 的 4 参数向后兼容构造函数仍然可用。`origin` 会在被注入的轮次（任务通知、通道/对等消息）
以及 CLI 回放的用户消息上被填充；工具结果消息从不携带它。参见 [MessageOrigin](#messageorigin)。

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

对于不需要较新字段的代码，也提供了向后兼容的构造函数：

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

当一次运行以终止性错误结果结束时，CLI 也会以非零状态码退出，SDK 会把它报告为携带与本消息相同负载的
[`ResultException`](./api-exceptions.md#resultexception) —— 关于哪些 API 界面会呈现它、哪些不会，
请参见那一页。

**关于 `errors` 的解析说明：** CLI 在这里发送的是字符串列表。裸字符串会被容忍并保留为单元素列表，
其他任何形状都会被忽略，而不是拒绝整个 result 帧；Python SDK 对该字段完全不做类型检查，因此一个格式
错误的值不应让调用方丢掉整个 result。

对于不需要较新字段的代码，也提供了向后兼容的构造函数。最初的 11 参数构造函数（不含 `modelUsage`、
`permissionDenials`、`deferredToolUse`、`errors`、`apiErrorStatus`、`uuid`、`terminalReason` 与
`origin`）仍然可用；针对更早形状编写的调用方还可使用 15 参数（不含 `deferredToolUse`、
`apiErrorStatus`、`terminalReason`、`origin`）、17 参数（不含 `terminalReason`、`origin`）以及
18 参数（不含 `origin`）的重载。

### terminalReason

查询循环结束的原因。从 CLI 观察到的取值包括 `"completed"`、`"max_turns"`、`"aborted_streaming"` 与
`"aborted_tools"`。

`"aborted_streaming"` 与 `"aborted_tools"` 表示该轮次被取消 —— 通过 `ClaudeSDKClient.interrupt()`
或一个 `interrupt` 控制请求 —— 从而在不额外引入 result 子类型的前提下，为调用方提供了明确的取消标记：

```java
ResultMessage result = ClaudeSDK.queryForResult("Long task", options);
if ("aborted_streaming".equals(result.terminalReason())
        || "aborted_tools".equals(result.terminalReason())) {
    System.out.println("Turn was interrupted");
}
```

当 CLI 未报告终止原因时为 `null` —— 例如较旧的 CLI 版本，或某个绕过了查询循环的 result（如本地斜杠
命令）。它对应 TypeScript SDK 的 `SDKResultMessage.terminal_reason`。

### ModelUsage

按模型划分的 token 与费用明细，在 `ResultMessage.modelUsage()` 中以模型字符串为键。

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

**JSON 命名：** 与其他来自 CLI 的数据不同，这个类型使用 camelCase 的 JSON 键。CLI 会原样透传
`modelUsage` 的取值，因此它的键与 TypeScript SDK 的 `ModelUsage` 形状一致，而不是 `ResultMessage`
上别处使用的 snake_case。Java 的访问器是 `costUsd()`；JSON 键是 `costUSD`。

`canonicalModel` 提供了一个稳定的键，用于在各种特定于提供方的 id 与别名之间做客户端费率表查找
（一个 Bedrock ARN 会映射到 `claude-opus-4-7`），从而无需解析原始模型字符串就能发现费用漂移。
`provider` 取值为 `firstParty`、`bedrock`、`vertex`、`foundry`、`anthropicAws`、
`anthropicGoogleCloud`、`mantle`、`gateway` 之一。在不发出它们的 CLI 版本上两者都为 `null`。

```java
ResultMessage result = ClaudeSDK.queryForResult("Analyze this", options);
if (result.modelUsage() != null) {
    result.modelUsage().forEach((model, usage) ->
        System.out.printf("%s: %d in / %d out, $%.4f%n",
            model, usage.inputTokens(), usage.outputTokens(), usage.costUsd()));
}
```

解析在设计上是宽容的：CLI 未发出的计数器读作 `0` 而不是让该帧失败，取值不是对象的条目会被跳过而不是
拒绝整条 result 消息。`raw()` 保留了原样的 map，因此较新 CLI 新增的字段无需升级 SDK 即可访问。

### DeferredToolUse

一次被返回 `permissionDecision: "defer"` 的 `PreToolUse` 钩子延迟的工具调用。CLI 会停止本次运行，
并在这里呈现被延迟的调用，供 SDK 使用方决定是否继续。

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

## 内容块

### ContentBlock 接口

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock
```

每个块都暴露 `type()`，即来自 CLI 的原始判别字符串。

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

服务端工具调用（advisor、web_search、web_fetch、code_execution 等）。API 会代表模型执行它们 ——
调用方永远不需要返回结果。

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // ServerToolName value (raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`ServerToolName` 枚举值：`ADVISOR`、`WEB_SEARCH`、`WEB_FETCH`、`CODE_EXECUTION`、
`BASH_CODE_EXECUTION`、`TEXT_EDITOR_CODE_EXECUTION`、`TOOL_SEARCH_TOOL_REGEX`、
`TOOL_SEARCH_TOOL_BM25`。

### ServerToolResultBlock

服务端工具调用返回的结果块。CLI 会把它们作为 `advisor_tool_result` 内容块发出；`content` 是不透明的
（advisor 的结果类型包括 `advisor_result`、`advisor_redacted_result`、
`advisor_tool_result_error`）。

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content
) implements ContentBlock
```

### ImageBlock

当 `Read` 工具渲染 PDF 页面时发出。工具结果本身只报告页数；文件会通过*另一条*用户消息到来，其中每个
被渲染的页面对应一个 `image` 块。

```java
record ImageBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`source` 刻意保留为原始 map，因为 API 定义了 `base64`、`url`、`file`、`text` 与 `content` 等多种
source 形状，并且还可能增加。有三个便捷访问器覆盖 base64 的情形，当键不存在或不是字符串时各自返回
`null`：

| 方法 | 返回 |
|---|---|
| `sourceType()` | `source["type"]`，例如 `"base64"` |
| `mediaType()` | `source["media_type"]`，例如 `"image/jpeg"` |
| `data()` | `source["data"]`，base64 负载 |

### DocumentBlock

CLI 在同一个"读取 PDF"流程中使用的另一种形状：用单个 `document` 块承载整个文件，而不是每页一个图像。
在 CLI 2.1.218 上，针对同一文件的不同运行曾观察到这两种形状，因此两者都建模了。

```java
record DocumentBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

它暴露与 `ImageBlock` 相同的 `sourceType()` / `mediaType()` / `data()` 访问器；`mediaType()` 通常是
`"application/pdf"`。

### UnknownBlock

针对本 SDK 版本尚未建模的块类型的向前兼容兜底。`MessageParser.parse()` 对于无法识别的*消息*类型已经
返回 `null`，从而使较新的 CLI 不会让较旧的 SDK 崩溃；内容块也享有同样的待遇，而不是抛出异常。

```java
record UnknownBlock(
    String type,               // The unrecognised discriminator
    Map<String, Object> raw    // The block, preserved whole
) implements ContentBlock
```

解析器会针对每一种无法识别的类型，通过 `in.vidyalai.claude.sdk.internal.MessageParser` logger 以
`WARNING` 级别记录一次。在这一机制出现之前，未建模的块会抛出 `MessageParseException`，从而杀死读取
线程，并丢弃该消息中的所有其他块 —— 包括模型已经产出的文本。

> **注意：** `ContentBlock` 是密封的。当 `permits` 子句增长时，调用方代码中对它的穷尽式 `switch`
> 会无法编译。请显式处理 `UnknownBlock`，而不是添加 `default` 分支，这样下一次新增仍然会是编译
> 错误，而不是悄无声息地落到兜底分支。

## MirrorErrorMessage

非致命的 SessionStore 追加失败。它在批处理器的重试预算耗尽后呈现；此时本地磁盘的会话记录已经持久化。

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

钩子的生命周期事件。仅当在 `ClaudeAgentOptions` 上设置了 `includeHookEvents(true)` 时才会发出。

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

`HookEventMessage` 是密封接口的顶层成员（它不满足 `instanceof SystemMessage`）。在 `hook_response`
上，`data` map 会带有 `output`、`exit_code` 与 `outcome` 键。

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

当会话的对话在不断开连接的情况下被替换时发出 —— 例如 `/clear` 之后，或任何其他在会话中途丢弃会话记录
的流程之后。

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message {
    String type();  // Returns "conversation_reset"
}
```

在流式输入模式下，单个连接会承载许多用户轮次，而一次重置会清空对话历史，*并且*把后续
`ResultMessage` 上报告的累计值（例如 `totalCostUsd`）归零。如果你在长期存活的会话中累计这些数值，
请在这条消息到达时对它们做一次快照。

`newConversationId` 是一个键，可供 UI 挂载一份空的会话记录（并丢弃任何缓存的会话标题）。它**不是**
后续消息的 `sessionId` —— 后者请从下一条消息中读取，它会携带一个新的 sessionId。

```java
case ConversationResetMessage reset -> {
    System.out.printf("session %s reset -> new conversation %s%n",
            reset.sessionId(), reset.newConversationId());
    snapshotTotals();   // subsequent results restart their counters at zero
}
```

缺少这三个必需字段中的任何一个都会引发 `MessageParseException`。

## MessageOrigin

用户角色消息的来源；在 `ResultMessage` 上，则是触发该轮次的那条消息的来源。

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

在流式输入模式下，同一个连接会把你的应用发送的轮次与会话自行注入的轮次交织在一起 —— 后台任务通知、
被触发的定时任务提示词、MCP 通道消息、从对等会话转发来的消息。`origin` 可以区分它们：

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

`origin` 为 null 表示 CLI 未标注该消息的来源。通过 `ClaudeSDK.query()` 或
`ClaudeSDKClient.query(String)` 发送的提示词就是这样到达的，除非你自己通过
`ClaudeSDKClient.query(Iterator)` 在消息 map 上标注 `"origin": {"kind": "human"}` —— SDK 宿主端只有
`human` 这一种取值会被接受，而且这样做需要 Claude Code >= 2.1.210。

**向前兼容。** 若某个 `kind` 比本 SDK 建模的更新，`kind()` 会是 null，而 `kindValue()` 仍然携带协议
字符串，并且 `isHuman()` 对它为 false —— 请把任何无法识别的来源都当作"非人类"。`subkind()` 同理。
CLI 的完整对象保留在 `raw()` 上，因此本版本未建模的键仍然可以访问。

`from`、`name` 与 `fromSession` 都是**由发送方自行声明的**。请把它们用于回复路由与展示，切勿当作
身份证明。

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

当 `kind == TASK_NOTIFICATION` 时存在；对于普通的后台任务通知则不存在。

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

## 任务消息类型

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

随着后台任务在其生命周期中推进，会在 `system`/`task_updated` 事件上发出。任务的终态有时**只**以一条
不带 `TaskNotificationMessage` 的 `task_updated` 补丁形式到来（例如通过 `TaskStop` 停止的任务会在
这里报告 `status="killed"`）。它的解析是防御性的 —— 缺失或不是 map 的 `patch` 会退化为空 map，未知
或缺失的状态会退化为 `null`，因此生命周期事件绝不会让解析崩溃。

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

## 另见
- [消息类型指南](./feature-message-types.md)
