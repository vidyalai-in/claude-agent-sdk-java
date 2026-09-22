# メッセージ型 API リファレンス

Claude のメッセージの型階層です。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../api-message-types.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## MessageParser

`MessageParser` クラスは、CLI から届いた生の JSON map を型付きの `Message` オブジェクトへ変換します。

```java
public final class MessageParser {
    // Returns null for unrecognized message types (forward compatibility)
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException;
}
```

認識できないメッセージ型に対しては例外を投げずに `null` を返すので、新しいメッセージ型を出しうる新しい
CLI バージョンとも SDK は互換を保てます。

## Message インターフェース

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent,
    ConversationResetMessage {
    String type();
}
```

> **互換性に関する注記（v0.1.23）。** `ConversationResetMessage` がこの合併型を広げました。`Message`
> は sealed なので、`default` 分岐のない網羅的な `switch` は、`ConversationResetMessage` のケースを
> 追加するまでコンパイルできなくなります。将来の追加を黙って受け流したい場合は、代わりに
> `default ->` 分岐を足してください。

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

`origin` を持たない後方互換の 4 引数コンストラクタも引き続き利用できます。`origin` は、差し込まれた
ターン（タスク通知、チャネル／ピアのメッセージ）と、CLI が再生するユーザーメッセージで埋められます。
ツール結果のメッセージが持つことはありません。[MessageOrigin](#messageorigin) を参照してください。

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

新しいフィールドを必要としないコードのために、後方互換のコンストラクタも用意されています：

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

実行が終端のエラー結果で終わると CLI も非ゼロで終了し、SDK はそれを、このメッセージと同じペイロードを
持つ [`ResultException`](./api-exceptions.md#resultexception) として報告します —— どの API 面がそれを
現し、どれが現さないかについてはそのページを参照してください。

**`errors` の解析に関する注記：** CLI はここに文字列のリストを送ります。裸の文字列は許容されて 1 要素
のリストとして保持され、それ以外の形は結果フレーム全体を拒否するのではなく無視されます。Python SDK は
このフィールドに型チェックを一切行わないので、不正な値のせいで呼び出し側が結果全体を失ってはなりません。

新しいフィールドを必要としないコードのために、後方互換のコンストラクタも用意されています。元の 11 引数
コンストラクタ（`modelUsage`、`permissionDenials`、`deferredToolUse`、`errors`、`apiErrorStatus`、
`uuid`、`terminalReason`、`origin` なし）は引き続き動作します。以前の形に合わせて書かれた呼び出し側の
ために、15 引数（`deferredToolUse`、`apiErrorStatus`、`terminalReason`、`origin` なし）、17 引数
（`terminalReason`、`origin` なし）、18 引数（`origin` なし）のオーバーロードも利用できます。

### terminalReason

クエリのループが終わった理由です。CLI から観測される値には `"completed"`、`"max_turns"`、
`"aborted_streaming"`、`"aborted_tools"` があります。

`"aborted_streaming"` と `"aborted_tools"` は、そのターンが —— `ClaudeSDKClient.interrupt()` または
`interrupt` 制御リクエストによって —— 取り消されたことを意味し、結果のサブタイプを別に設けることなく、
呼び出し側に明示的な「取り消された」印を与えます：

```java
ResultMessage result = ClaudeSDK.queryForResult("Long task", options);
if ("aborted_streaming".equals(result.terminalReason())
        || "aborted_tools".equals(result.terminalReason())) {
    System.out.println("Turn was interrupted");
}
```

CLI が終端理由を報告しなかった場合は `null` です —— 古い CLI バージョンや、ローカルのスラッシュ
コマンドのようにクエリループを経由しなかった結果などです。TypeScript SDK の
`SDKResultMessage.terminal_reason` に対応します。

### ModelUsage

モデルごとのトークンとコストの内訳で、`ResultMessage.modelUsage()` ではモデル文字列がキーになります。

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

**JSON の命名：** CLI から受け取るデータとしては珍しく、この型は camelCase の JSON キーを使います。CLI が
`modelUsage` の値をそのまま通すため、そのキーは `ResultMessage` の他の場所で使われる snake_case では
なく、TypeScript SDK の `ModelUsage` の形に一致します。Java のアクセサは `costUsd()`、JSON のキーは
`costUSD` です。

`canonicalModel` は、プロバイダ固有の id やエイリアスをまたいでクライアント側の料金表を引くための安定
したキーを与えます（Bedrock の ARN は `claude-opus-4-7` に対応します）。これにより、生のモデル文字列を
解析しなくてもコストのずれを検出できます。`provider` は `firstParty`、`bedrock`、`vertex`、`foundry`、
`anthropicAws`、`anthropicGoogleCloud`、`mantle`、`gateway` のいずれかです。どちらも、これらを出さない
CLI バージョンでは `null` です。

```java
ResultMessage result = ClaudeSDK.queryForResult("Analyze this", options);
if (result.modelUsage() != null) {
    result.modelUsage().forEach((model, usage) ->
        System.out.printf("%s: %d in / %d out, $%.4f%n",
            model, usage.inputTokens(), usage.outputTokens(), usage.costUsd()));
}
```

解析は意図的に寛容です。CLI が出さなかったカウンタはフレームを失敗させる代わりに `0` として読まれ、値が
オブジェクトでないエントリは結果メッセージ全体を拒否せずスキップされます。`raw()` はそのままの map を
保持するので、新しい CLI が追加したフィールドも SDK を上げずに手が届きます。

### DeferredToolUse

`permissionDecision: "defer"` を返した `PreToolUse` フックによって保留されたツール呼び出しです。CLI は
実行を止め、保留された呼び出しをここに現すので、SDK の利用側が再開するかどうかを判断できます。

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

## コンテンツブロック

### ContentBlock インターフェース

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock
```

どのブロックも、CLI の生の判別文字列である `type()` を公開します。

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

サーバー側のツール呼び出し（advisor、web_search、web_fetch、code_execution など）です。API がモデルに
代わって実行するので、呼び出し側が結果を返すことはありません。

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // ServerToolName value (raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`ServerToolName` 列挙型の値：`ADVISOR`、`WEB_SEARCH`、`WEB_FETCH`、`CODE_EXECUTION`、
`BASH_CODE_EXECUTION`、`TEXT_EDITOR_CODE_EXECUTION`、`TOOL_SEARCH_TOOL_REGEX`、
`TOOL_SEARCH_TOOL_BM25`。

### ServerToolResultBlock

サーバー側のツール呼び出しに対して返る結果ブロックです。CLI はこれらを `advisor_tool_result` の
コンテンツブロックとして出します。`content` は不透明です（advisor の結果型には `advisor_result`、
`advisor_redacted_result`、`advisor_tool_result_error` があります）。

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content
) implements ContentBlock
```

### ImageBlock

`Read` ツールが PDF のページを描画したときに出ます。ツール結果そのものはページ数を伝えるだけで、
ファイルは*別の*ユーザーメッセージとして届き、描画されたページごとに 1 つの `image` ブロックを持ちます。

```java
record ImageBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`source` をあえて生の map のままにしているのは、API が `base64`、`url`、`file`、`text`、`content` の
source 形を定義しており、さらに増えうるからです。base64 のケースをカバーする 3 つの便利アクセサがあり、
キーが存在しないか文字列でない場合はそれぞれ `null` を返します：

| メソッド | 返すもの |
|---|---|
| `sourceType()` | `source["type"]`（例：`"base64"`） |
| `mediaType()` | `source["media_type"]`（例：`"image/jpeg"`） |
| `data()` | `source["data"]`、base64 のペイロード |

### DocumentBlock

同じ「PDF を Read する」流れで CLI が使うもう 1 つの形です。ページごとに画像を出す代わりに、ファイル
全体を 1 つの `document` ブロックが保持します。CLI 2.1.218 で同じファイルに対して実行ごとに両方の形が
観測されているため、どちらもモデル化されています。

```java
record DocumentBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`ImageBlock` と同じ `sourceType()` / `mediaType()` / `data()` アクセサを公開します。`mediaType()` は
通常 `"application/pdf"` です。

### UnknownBlock

この SDK バージョンがモデル化していないブロック型に対する前方互換のフォールバックです。
`MessageParser.parse()` はすでに認識できない*メッセージ*型に対して `null` を返すので、新しい CLI が
古い SDK をクラッシュさせることはありません。コンテンツブロックも例外を投げる代わりに同じ扱いを
受けます。

```java
record UnknownBlock(
    String type,               // The unrecognised discriminator
    Map<String, Object> raw    // The block, preserved whole
) implements ContentBlock
```

パーサは認識できない型ごとに 1 回、`in.vidyalai.claude.sdk.internal.MessageParser` ロガーから
`WARNING` で記録します。これが存在する前は、モデル化されていないブロックが `MessageParseException` を
投げてリーダースレッドを殺し、そのメッセージの他のブロックを —— モデルがすでに生成していたテキストも
含めて —— すべて捨てていました。

> **注意：** `ContentBlock` は sealed です。`permits` 句が増えると、呼び出し側コードでの網羅的な
> `switch` はコンパイルできなくなります。`default` 分岐を足すのではなく `UnknownBlock` を明示的に
> 扱ってください。そうすれば次の追加も、黙って素通りするのではなくコンパイルエラーのままになります。

## MirrorErrorMessage

致命的でない SessionStore の追記失敗です。バッチャーの再試行予算が尽きたあとに現れます。ローカル
ディスクのトランスクリプトはすでに永続化されています。

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

フックのライフサイクルイベントです。`ClaudeAgentOptions` に `includeHookEvents(true)` を設定したとき
だけ出ます。

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

`HookEventMessage` は sealed インターフェースのトップレベルのメンバーです（`instanceof SystemMessage`
には一致しません）。`hook_response` では `data` の map が `output`、`exit_code`、`outcome` のキーを
持ちます。

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

接続を終わらせずにセッションの会話が置き換えられたときに出ます —— `/clear` の後や、セッション途中で
トランスクリプトを捨てる他の流れの後です。

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message {
    String type();  // Returns "conversation_reset"
}
```

ストリーミング入力モードでは 1 本の接続が多くのユーザーターンを運び、リセットは会話履歴を消すと同時に、
以降の `ResultMessage` で報告される累計値（たとえば `totalCostUsd`）をゼロに戻します。長命なセッション
でそれらを積み上げているなら、このメッセージが届いた時点でスナップショットを取ってください。

`newConversationId` は、UI が空のトランスクリプトを掛けておく（そしてキャッシュしたセッションタイトルを
捨てる）ためのキーです。以降のメッセージの `sessionId` では**ありません** —— そちらは新しい値を運ぶ
次のメッセージから読んでください。

```java
case ConversationResetMessage reset -> {
    System.out.printf("session %s reset -> new conversation %s%n",
            reset.sessionId(), reset.newConversationId());
    snapshotTotals();   // subsequent results restart their counters at zero
}
```

3 つの必須フィールドのいずれかが欠けていると `MessageParseException` が発生します。

## MessageOrigin

ユーザーロールのメッセージの出所、そして `ResultMessage` ではそのターンを引き起こしたメッセージの
出所です。

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

ストリーミング入力モードでは、1 本の接続があなたのアプリケーションが送るターンと、セッションが自ら
差し込むターン —— バックグラウンドタスクの通知、発火したスケジュールタスクのプロンプト、MCP チャネルの
メッセージ、ピアセッションから中継されたメッセージ —— を交互に運びます。`origin` がそれらを区別します：

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

`origin` が null なら、CLI がそのメッセージに出所を付与しなかったということです。`ClaudeSDK.query()`
や `ClaudeSDKClient.query(String)` で送ったプロンプトは、`ClaudeSDKClient.query(Iterator)` を使って
メッセージ map に自分で `"origin": {"kind": "human"}` を付けない限りその形で届きます —— SDK ホスト
からは `human` という種別だけが受け付けられ、それには Claude Code >= 2.1.210 が必要です。

**前方互換性。** この SDK がモデル化しているより新しい `kind` の場合、`kind()` は null になりますが
`kindValue()` はワイヤーの文字列を保持し、`isHuman()` はそれに対して false です —— 認識できないものは
すべて「human ではない」と扱ってください。`subkind()` も同様です。CLI の完全なオブジェクトは `raw()` に
保持されるので、このバージョンがモデル化していないキーにも手が届きます。

`from`、`name`、`fromSession` は**送信者の自己申告**です。返信のルーティングと表示に使い、身元の証明と
しては決して使わないでください。

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

`kind == TASK_NOTIFICATION` のときに存在し、通常のバックグラウンドタスク通知では存在しません。

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

## タスク関連のメッセージ型

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

バックグラウンドタスクがライフサイクルを進むにつれて `system`/`task_updated` イベントとして出ます。
タスクの終端状態が、付随する `TaskNotificationMessage` を伴わず `task_updated` のパッチ**だけ**で届く
ことがあります（たとえば `TaskStop` で停止したタスクは、ここで `status="killed"` を報告します）。解析は
防御的で、`patch` が欠けていたり map でなかったりすれば空の map に、状態が不明または欠落なら `null` に
フォールバックするので、ライフサイクルのイベントが解析をクラッシュさせることはありません。

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

## 関連項目
- [メッセージ型のガイド](./feature-message-types.md)
