# メッセージ型

Claude の会話を処理するためのメッセージ型システムを理解する。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-message-types.md)より古い場合があります。内容が食い違う場合は英語版が優先されます。コードブロックは英語版と同一のまま、翻訳していません。

## 目次
- [概要](#概要)
- [前方互換性](#前方互換性)
- [メッセージ型の階層](#メッセージ型の階層)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [タスクメッセージ](#タスクメッセージ)
- [MirrorErrorMessage](#mirrorerrormessage)
- [HookEventMessage](#hookeventmessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [RateLimitEvent](#ratelimitevent)
- [コンテンツブロック](#コンテンツブロック)
- [パターンマッチング](#パターンマッチング)
- [例](#例)

## 概要

SDK は型安全なメッセージ処理のために封印インターフェースの階層を使います。すべてのメッセージが `Message` 封印インターフェースを実装しているため、網羅的なパターンマッチングが可能です。

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}
```

## 前方互換性

`MessageParser` は新しい CLI バージョンとの前方互換性を考慮して設計されています。SDK が認識できないメッセージ型を CLI が出力した場合、パーサーは例外をスローせずに `null` を返します。メッセージイテレーターは `null` のメッセージを自動的にスキップするため、新しいメッセージ型を出す新しい CLI に接続していてもコードは正しく動作し続けます。

```java
// MessageParser.parse() returns @Nullable Message
// Unknown types return null and are silently skipped by the iterator
for (Message msg : ClaudeSDK.query(prompt)) {
    // Only known message types arrive here; unknown types are silently skipped
    switch (msg) { ... }
}
```

**未知の型と不正な内容は別物です。** `null` を返すのは*認識できないメッセージ型*の場合だけです。構造が不正な*既知*の型（`user` / `assistant`）は別のケースで、黙って捨てる代わりにパーサーが `MessageParseException` を発生させるため、本物のプロトコル違反が隠れることはありません。0.1.18 以降これは明示的に強制されています。`content` がリストでない `assistant` メッセージ（たとえば裸の文字列）は `"Invalid assistant content (expected list, got …)"` を、`content` リストの要素がオブジェクトでない場合（`user` と `assistant` の両方）は `"Invalid content block (expected dict, got …)"` を発生させます。以前はこれらが生の `ClassCastException` として表面化していましたが、現在はパーサーの他の構造検証と同様に `MessageParseException` として一貫して報告されます。

## メッセージ型の階層

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

> **互換性に関する注意（v0.1.23）。** `ConversationResetMessage` がこの封印ユニオンを
> 広げたため、`default` 分岐のない網羅的な `switch` は case を追加するまで
> コンパイルできなくなります。[パターンマッチング](#パターンマッチング)を参照してください。

## UserMessage

ユーザーからのメッセージを表します。内容は単純な文字列でも、構造化されたコンテンツブロックのリストでもかまいません（たとえばツール結果を含む場合）。

### フィールド

```java
record UserMessage(
    Object content,                          // String or List<ContentBlock>
    @Nullable String uuid,                   // Unique message identifier
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult  // Tool execution metadata (file edits, etc.)
) implements Message
```

### メソッド

| メソッド | 説明 |
|--------|-------------|
| `type()` | `"user"` を返す |
| `contentAsString()` | 内容を String で返す。構造化されている場合は null |
| `contentAsBlocks()` | 内容を `List<ContentBlock>` で返す。文字列の場合は null |

### 例

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

Claude からのメッセージを表し、1 つ以上のコンテンツブロックを含みます。

### フィールド

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

`usage` マップは、利用可能な場合にターンごとの API トークン消費データを含み、`input_tokens`、`output_tokens`、キャッシュ関連のキーなどが入ります。`messageId`、`stopReason`、`sessionId`、`uuid` の各フィールドは API レベルの識別子を保持します。これらは null 許容で、部分／ストリーミングメッセージでは存在しません。新しいフィールドを含まない後方互換のコンストラクターも用意されています。

### メソッド

| メソッド | 説明 |
|--------|-------------|
| `type()` | `"assistant"` を返す |
| `getTextContent()` | すべての `TextBlock` のテキストを連結する |
| `hasToolUse()` | `ToolUseBlock` を 1 つ以上含む場合に `true` |

### AssistantMessageError の値

| 列挙定数 | 文字列値 | 説明 |
|---------------|-------------|-------------|
| `AUTHENTICATION_FAILED` | `"authentication_failed"` | API キーが無効か欠落 |
| `BILLING_ERROR` | `"billing_error"` | 課金の問題 |
| `RATE_LIMIT` | `"rate_limit"` | レート制限を超過 |
| `INVALID_REQUEST` | `"invalid_request"` | リクエストの形式が不正 |
| `SERVER_ERROR` | `"server_error"` | サーバー内部エラー |
| `UNKNOWN` | `"unknown"` | 不明または認識できないエラー |

### 例

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

CLI からのシステムレベルの通知とイベント。

### フィールド

```java
record SystemMessage(
    String subtype,           // Message subtype (e.g., "init")
    Map<String, Object> data  // Full raw message data
) implements Message
```

### 例

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System event: " + system.subtype());
    System.out.println("Data: " + system.data());
}
```

## タスクメッセージ

タスクメッセージは、サブエージェントのタスクライフサイクルイベント中に出力される、型付けされたシステムメッセージのサブタイプです。これらは `Message` を直接実装し、`type()` は `"system"` を返します。既存の `instanceof SystemMessage` チェックはこれらに**マッチしません** — 具体的な型を使ってください。

### TaskStartedMessage

タスク（サブエージェント）が開始したときに出力されます。

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

| メソッド | 説明 |
|--------|-------------|
| `type()` | `"system"` を返す |
| `get(String key)` | 生の data マップから値を取得する |

### TaskProgressMessage

タスクの実行中に定期的に出力されます。

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

| メソッド | 説明 |
|--------|-------------|
| `type()` | `"system"` を返す |
| `get(String key)` | 生の data マップから値を取得する |

### TaskNotificationMessage

タスクが完了・失敗・停止したときに出力されます。

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

バックグラウンドタスクがライフサイクルを進む際に、`system`/`task_updated` イベントとして出力されます。`patch` は変化したフィールド（たとえば `status`、`end_time`）を運びます。

タスクの終了状態は、対応する `TaskNotificationMessage` を伴わず **`TaskUpdatedMessage` だけ**で届くことがあります。たとえば `TaskStop` で停止したタスクはここで `status="killed"` を報告し、対応する通知が抑制される場合があります。アクティブなタスク ID を追跡する側は、**どちらかの**メッセージで終了状態が来たらクリアしてください。

パースは防御的です — `patch` が欠落していたりマップでなかったりすると空のマップにフォールバックし、未知／欠落のステータスは `null` になるため、ライフサイクルイベントがパースをクラッシュさせることはありません。

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

| メソッド | 説明 |
|--------|-------------|
| `type()` | `"system"` を返す |
| `isTerminal()` | `status` が存在し `TERMINAL_TASK_STATUSES` に含まれる場合に `true` |
| `get(String key)` | 生の data マップから値を取得する |
| `TaskUpdatedMessage.TERMINAL_TASK_STATUSES` | `Set.of("completed", "failed", "stopped", "killed")` — 2 つのタスクライフサイクル語彙にまたがる終了状態 |

### TaskUpdatedStatus

`task_updated` は生の `KILLED` を報告します。CLI がそれを `STOPPED`（`TaskNotificationStatus`）に対応づけるのは、`task_notification` を出力するときだけです。

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

`TaskUpdatedStatus.fromValueOrNull(String)` は未知の値や `null` でもスローせずに生のステータスを解決します（防御的パースで使用）。`fromValue(String)` は未知の値でスローします。

### TaskUsage

タスクの使用統計：

```java
record TaskUsage(
    int totalTokens,   // total tokens used
    int toolUses,      // number of tool invocations
    int durationMs     // task duration in milliseconds
)
```

### 例：タスクメッセージの処理

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

リトライを使い切った後（`MIRROR_APPEND_MAX_ATTEMPTS=3`）に `SessionStore.append()` の呼び出しが失敗したときに出力される、致命的でないシステムメッセージです。ローカルディスクのトランスクリプトはすでに永続化されているためセッションは影響を受けません — 外部ストア側のミラーコピーに、失敗したバッチが欠けるだけです。

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted (null if pre-resolution)
    String error                       // failure description
) implements Message
```

トップレベルの `Message` 封印インターフェースのメンバーとしてモデル化されています（Java の record は record を継承できません）。`subtype` は常に `"mirror_error"` で、基底の `data` フィールドは `SystemMessage` 風のダウンキャスト用に生のペイロードを保持します。

### 例：ミラーエラーからの復旧

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

ミラーの全体的な流れとリトライのセマンティクスは [Session Store](./feature-session-store.md) を参照してください。

## HookEventMessage

メッセージストリームに表面化したフックのライフサイクルイベントです。`ClaudeAgentOptions` に `includeHookEvents(true)` を設定したときだけ出力されます。[フック → ストリーム上のフックライフサイクルイベント](./feature-hooks.md#ストリーム上のフックライフサイクルイベント)を参照してください。

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

`type()` は `SystemMessage` との対称性のために `"system"` を返しますが、`HookEventMessage` は別の封印インターフェースメンバーです — `instanceof SystemMessage` は**マッチしません**。`HookEventMessage` そのもので分岐してください。

`subtype` の値：

- `"hook_started"` — フックの実行が始まったとき。
- `"hook_response"` — フックが完了したとき。`data` に `output`、`exit_code`、`outcome` のキーが含まれます。

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

各会話ターンの最後に送られる最終結果で、所要時間・コスト・使用量の情報を含みます。

### フィールド

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

`errors` フィールドは CLI からのエラーメッセージのリストで、ゼロ以外の終了コードの診断に役立ちます。`modelUsage` フィールドはモデルごとのトークン内訳を `ModelUsage` 型で提供します — [API リファレンス → ModelUsage](./api-message-types.md#modelusage) を参照してください。`deferredToolUse` フィールドは `PreToolUse` フックが `permissionDecision: "defer"` を返したときに設定されます — [フック → 権限判断 `"defer"`](./feature-hooks.md#権限判断-defer)を参照してください。`apiErrorStatus` フィールドは `isError=true` かつ `subtype="success"` のとき（API は失敗したがセッション自体は完了した場合）に、失敗した API 呼び出しの HTTP ステータスコード（`429`、`500`、`529` など）を運びます。メッセージ内容を含まないため、ログに出しても安全です。新しいフィールドを含まない後方互換のコンストラクターも用意されています。

`terminalReason` フィールドはクエリループが終わった理由を報告します — `"completed"`、`"max_turns"`、`"aborted_streaming"`、`"aborted_tools"`。2 つの `aborted_*` は、そのターンが `ClaudeSDKClient.interrupt()` でキャンセルされたことを意味し、専用の結果 subtype なしに明示的なキャンセル印を呼び出し側に与えます。

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

CLI が終了理由を報告しなかった場合は `null` になります — 古い CLI バージョンや、ローカルのスラッシュコマンドのようにクエリループを経ない結果などです。

### DeferredToolUse

```java
record DeferredToolUse(
    String id,                       // unique identifier of the deferred tool call
    String name,                     // tool name
    Map<String, Object> input        // tool input arguments
)
```

### よくある subtype

- `"success"` — 会話が正常に完了
- `"error_max_budget_usd"` — 予算の上限に到達
- `"error_max_turns"` — 最大ターン数に到達

### 例

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

ストリーミング中の部分的なメッセージ更新です。`includePartialMessages` を有効にしたときだけ出力されます。

### フィールド

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message
```

### メソッド

| メソッド | 説明 |
|--------|-------------|
| `type()` | `"stream_event"` を返す |
| `eventType()` | 内部の event マップからイベント型の文字列を返す。無ければ null |

### よくあるイベント型（`event.get("type")` より）

- `"content_block_start"` — 新しいコンテンツブロックの開始
- `"content_block_delta"` — コンテンツの増分更新
- `"content_block_stop"` — コンテンツブロックの完了
- `"message_start"` — メッセージの開始
- `"message_delta"` — メッセージの更新
- `"message_stop"` — メッセージの完了

### 例

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

レート制限の状態が遷移するたびに CLI から出力されます。ユーザーがハードリミットに達する前に警告したり、超過時に穏やかにバックオフしたりするのに使えます。

### フィールド

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

| 列挙定数 | 文字列値 | 説明 |
|---------------|-------------|-------------|
| `ALLOWED` | `"allowed"` | 制限内。対応は不要 |
| `ALLOWED_WARNING` | `"allowed_warning"` | 制限に近づいている — ユーザーに警告する |
| `REJECTED` | `"rejected"` | 制限に達した — リクエストは拒否される |

### RateLimitType

| 列挙定数 | 文字列値 | 説明 |
|---------------|-------------|-------------|
| `FIVE_HOUR` | `"five_hour"` | 5 時間のローリングウィンドウ |
| `SEVEN_DAY` | `"seven_day"` | 7 日のローリングウィンドウ |
| `SEVEN_DAY_OPUS` | `"seven_day_opus"` | Opus モデル向けの 7 日ローリングウィンドウ |
| `SEVEN_DAY_SONNET` | `"seven_day_sonnet"` | Sonnet モデル向けの 7 日ローリングウィンドウ |
| `OVERAGE` | `"overage"` | 超過／従量課金の制限 |

### 例

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

接続を終了せずにセッションの会話が置き換えられたときに出力されます — `/clear` の後や、セッション途中でトランスクリプトを破棄する他のフローの後です。v0.1.23 より前はパーサーがこのフレームを黙って捨てていたため、アプリケーションはリセットを一切見られませんでした（自分が起こしていないものも含めて）。

### フィールド

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message
```

### なぜ重要か

リセットは会話履歴を消去し、*さらに*後続の `ResultMessage` が報告する累計値をゼロに戻します。長時間のストリーミングセッションでコストやトークン使用量を積算しているなら、このフレームはそれらがゼロに戻る前にスナップショットを取る唯一の合図です。

これはセッション ID の境界も示します。`newConversationId` は UI が空のトランスクリプトを紐づけるためのキーであり、**後続の** `sessionId` では**ありません**。リセット後のメッセージは新しい `sessionId` を持つので、次のメッセージから読み取ってください。

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

## メッセージの出所

`UserMessage.origin()` と `ResultMessage.origin()` は、そのターンが*なぜ*開始されたのかを示します。ストリーミング入力モードでは、1 つの接続の中でアプリケーションが送るターンと、セッションが自分で差し込むターン — バックグラウンドタスクの通知、発火したスケジュールタスクのプロンプト、MCP チャネルのメッセージ、ピアセッションから中継されたメッセージ — が入り混じります。`ResultMessage` 上のこのフィールドは、そのターンを*引き起こした*メッセージの出所を報告します。これにより「これは自分のプロンプトへの応答だ」と「これはバックグラウンドタスクへの応答だ」を区別できます。

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
    render(origin.subkind());   // scheduled-trigger / peer-send-message, or null
}
```

CLI がメッセージに出所を付与しなかった場合 `origin` は null です — 自分で送るプロンプトでは、これが通常の状態です。自分のターンにも出所を付けたい場合は、メッセージのマップに自分でスタンプし、`ClaudeSDKClient.query(Iterator)` から送ってください。

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", prompt));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));

client.query(List.of(message).iterator());
```

SDK ホストから受け付けられる kind は `human` のみで、Claude Code >= 2.1.210 が必要です。ツール結果のユーザーメッセージには出所が付きません。

認識できない kind はエラーにならず可視のまま残ります。`kind()` は null で `kindValue()` にワイヤ上の文字列が入り、`isHuman()` は false になります — つまり未知の出所は常に「人間ではない」と読めます。CLI のオブジェクト全体は `raw()` に保持されます。フィールドごとのリファレンス：[MessageOrigin](./api-message-types.md#messageorigin)。

`from`、`name`、`fromSession` といったフィールドは**送信側の自己申告**です — 返信のルーティングや表示には使えますが、身元の証明として扱ってはいけません。

## コンテンツブロック

アシスタントメッセージ（および構造化されたユーザーメッセージ）はコンテンツブロックを含みます。

### ContentBlock の階層

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock {}
```

インターフェースが封印されているため、`permits` 句が増えるたびに網羅的な `switch` はコンパイルできなくなります。`default` 分岐を足すより `UnknownBlock` を明示的に処理するほうが望ましいです — そうすれば次の新しいブロック型は、黙って素通りするのではなく、対処できるコンパイルエラーになります。

### TextBlock

Claude からのプレーンテキストの内容。

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

拡張思考が有効なときの Claude の内部推論。暗号署名を含みます。

```java
record ThinkingBlock(
    String thinking,   // Thinking content
    String signature   // Cryptographic signature for the thinking block
) implements ContentBlock
```

### ToolUseBlock

Claude によるツール呼び出し。

```java
record ToolUseBlock(
    String id,                          // Tool use ID (matches ToolResultBlock.toolUseId)
    String name,                        // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

ツール実行の結果。

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content (String or structured)
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

API がモデルの代わりに実行するサーバーサイドのツール呼び出しで、呼び出し側が結果を返すことはありません。`advisor`、`web_search`、`web_fetch`、`code_execution`、`bash_code_execution`、`text_editor_code_execution`、`tool_search_tool_regex`、`tool_search_tool_bm25` で使われます。

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // One of ServerToolName values (kept as raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`name` フィールドは判別子です — これで分岐すればどのサーバーツールが呼ばれたか分かります。`ServerToolName` 列挙型が認識済みの値を列挙しています。

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

サーバーサイドのツール呼び出しに対して返される結果ブロックです。形は `ToolResultBlock` と同じで、`content` は API からの生のマップであり、この層にとっては不透明です — 特定のサーバーツールの結果スキーマが必要な呼び出し側は `content.get("type")` を調べられます。

CLI はこれらを `advisor_tool_result` コンテンツブロックとして出力し（現在出荷されている唯一のサーバーツール結果型）、`ServerToolResultBlock` にパースされます。

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content (e.g. {"type":"advisor_result", ...})
) implements ContentBlock
```

advisor ツールに限れば、`content` マップの `type` フィールドは次のいずれかになります。
- `"advisor_result"` — `text` フィールドを持つテキスト結果
- `"advisor_redacted_result"` — `encrypted_content` フィールドを持つ暗号化ブロブ
- `"advisor_tool_result_error"` — エラーペイロード

### ImageBlock と DocumentBlock

どちらも同じ流れ、つまり `Read` ツールでの PDF 読み取りから届きます。`tool_result` 自体はページ数を知らせるだけで、ファイルは**別のユーザーメッセージ**で続きます。CLI はそのメッセージに 2 つの形のどちらかを使い、CLI 2.1.218 では同じ 1.5 MB のファイルに対して実行ごとに両方が観測されています。

- レンダリングされたページごとに 1 つの `image` ブロック（`image/jpeg`、base64）、または
- PDF 全体を保持する単一の `document` ブロック（`application/pdf`、base64）。

```java
record ImageBlock(Map<String, Object> source) implements ContentBlock
record DocumentBlock(Map<String, Object> source) implements ContentBlock
```

API は `base64`、`url`、`file`、`text`、`content` というソース形式を定義しており、さらに増える可能性があるため、`source` は生のマップのままにしてあります。次の 3 つのアクセサーが base64 のケースをカバーし、キーが無いか文字列でない場合はそれぞれ `null` を返します。

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

| メソッド | 戻り値 |
|---|---|
| `sourceType()` | `source["type"]`、たとえば `"base64"` |
| `mediaType()` | `source["media_type"]`、たとえば `"image/jpeg"` や `"application/pdf"` |
| `data()` | `source["data"]`、base64 のペイロード |

> **PDF を読むには `maxBufferSize` を上げる必要があります。** これらのブロックは base64（3 バイトが 4 バイトに）で、CLI 標準出力の 1 行に収まるため、1.5 MB の PDF はおよそ 2.1 MB の 1 行になり、デフォルトの 1 MB を超えます。デフォルト値は変更していない（Python SDK に合わせている）ので、どんなサイズのファイルであれ読み取る側は明示的に設定してください。
>
> ```java
> ClaudeAgentOptions options = ClaudeAgentOptions.builder()
>     .allowedTools(List.of("Read"))
>     .maxBufferSize(16 * 1024 * 1024)
>     .build();
> ```
>
> これがないと、PDF のディレクトリに対して `Read` を与えたエージェントは実行の途中で失敗します。

### UnknownBlock

この SDK バージョンがモデル化していないブロック型への前方互換性。

```java
record UnknownBlock(
    String type,             // The unrecognised discriminator
    Map<String, Object> raw  // The block, preserved whole
) implements ContentBlock
```

`MessageParser` は認識できない*メッセージ*型に対してすでに `null` を返しており、新しい CLI が古い SDK をクラッシュさせないようになっています。コンテンツブロックも例外をスローする代わりに同じ振る舞いをします。認識できないブロックは丸ごと保持され、`in.vidyalai.claude.sdk.internal.MessageParser` から型ごとに 1 回 `WARNING` で記録されます。

これはまさに `image` と `document` に必要だったのに無かったものです。以前の挙動は `MessageParseException` をスローし、リーダースレッドを終了させ、そのメッセージの他のブロック — モデルがすでに生成していたテキストを含む — をすべて捨て、呼び出し側が要求もしていない型名を挙げた JSON デコード失敗として表面化していました。

## パターンマッチング

Java のパターンマッチングにより、メッセージ処理は簡潔で型安全になります。

### switch 式

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

`Message` は封印されているため、このように `default` のない `switch` は許可されたすべての型を列挙しなければならず、コンパイラがそれを強制します。それこそが狙いです。新しいメッセージ型が追加されたとき、実行時に黙ってフレームを落とすのではなく、網羅的な switch ごとにコンパイルエラーが出ます。

トレードオフとして、型の追加はそうしたコードにとってソース互換性を壊す変更になります。`ConversationResetMessage` は v0.1.23 でまさにそれをしました。将来の追加を黙って吸収したいなら、`default` 分岐を足してください。

```java
String result = switch (message) {
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    default -> "(other)";        // future message types land here
};
```

### ネストしたパターンマッチング

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

### パターン変数付きの instanceof

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

## 例

### 例 1：すべてのメッセージを処理する

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

### 例 2：特定の情報を取り出す

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

### 例 3：エラーの処理

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

### 例 4：ツール使用の追跡

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

### 例 5：コストとトークンの分析

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

### 例 6：サブエージェントのメッセージをフィルタする

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

## 関連ドキュメント

- [単純なクエリ](./feature-simple-queries.md) — クエリからのメッセージ処理
- [対話型の会話](./feature-interactive-conversations.md) — クライアントからのメッセージ処理
- [ストリーミングイベント](./feature-streaming-events.md) — StreamEvent の詳細
- [API リファレンス：メッセージ型](./api-message-types.md) — 完全な API ドキュメント
