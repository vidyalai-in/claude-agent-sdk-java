# 메시지 타입 API 레퍼런스

Claude 메시지의 타입 계층 구조입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../api-message-types.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## MessageParser

`MessageParser` 클래스는 CLI에서 온 원본 JSON 맵을 타입이 지정된 `Message` 객체로 바꿉니다.

```java
public final class MessageParser {
    // Returns null for unrecognized message types (forward compatibility)
    @Nullable
    public static Message parse(Map<String, Object> data) throws MessageParseException;
}
```

알 수 없는 메시지 타입에는 예외를 던지는 대신 `null`을 반환하므로, 새 메시지 타입을 내보낼 수 있는 더
새로운 CLI 버전과도 SDK가 호환을 유지합니다.

## Message 인터페이스

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent,
    ConversationResetMessage {
    String type();
}
```

> **호환성 참고(v0.1.23).** `ConversationResetMessage`가 이 합집합을 넓혔습니다. `Message`는
> sealed이므로 `default` 분기가 없는 완전한 `switch`는 `ConversationResetMessage` 케이스를 추가할
> 때까지 컴파일되지 않습니다. 앞으로의 추가를 조용히 흡수하고 싶다면 대신 `default ->` 분기를
> 넣으세요.

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

`origin`이 없는 4인자 하위 호환 생성자도 계속 쓸 수 있습니다. `origin`은 주입된 턴(작업 알림, 채널/피어
메시지)과 CLI가 재생하는 사용자 메시지에서 채워집니다. 도구 결과 메시지는 결코 이를 담지 않습니다.
[MessageOrigin](#messageorigin)을 참고하세요.

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

새 필드가 필요 없는 코드를 위해 하위 호환 생성자도 제공됩니다:

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

실행이 종료성 오류 결과로 끝나면 CLI도 0이 아닌 코드로 종료하며, SDK는 이를 이 메시지와 같은 페이로드를
담은 [`ResultException`](./api-exceptions.md#resultexception)으로 보고합니다 — 어떤 API 표면이 그것을
드러내고 어떤 것이 그러지 않는지는 그 페이지를 참고하세요.

**`errors` 파싱 참고:** CLI는 여기에 문자열 목록을 보냅니다. 맨 문자열도 허용되어 한 원소짜리 목록으로
보존되며, 그 밖의 형태는 결과 프레임 전체를 거부하는 대신 무시됩니다. Python SDK는 이 필드에 타입 검사를
전혀 하지 않으므로, 잘못된 값 때문에 호출자가 결과 전체를 잃어서는 안 됩니다.

새 필드가 필요 없는 코드를 위해 하위 호환 생성자도 제공됩니다. 원래의 11인자 생성자(`modelUsage`,
`permissionDenials`, `deferredToolUse`, `errors`, `apiErrorStatus`, `uuid`, `terminalReason`,
`origin` 없음)는 계속 동작합니다. 이전 형태에 맞춰 작성된 호출자를 위해 15인자(`deferredToolUse`,
`apiErrorStatus`, `terminalReason`, `origin` 없음), 17인자(`terminalReason`, `origin` 없음),
18인자(`origin` 없음) 오버로드도 있습니다.

### terminalReason

질의 루프가 끝난 이유입니다. CLI에서 관찰되는 값으로는 `"completed"`, `"max_turns"`,
`"aborted_streaming"`, `"aborted_tools"`가 있습니다.

`"aborted_streaming"`과 `"aborted_tools"`는 그 턴이 — `ClaudeSDKClient.interrupt()`나 `interrupt`
제어 요청으로 — 취소되었음을 뜻하며, 결과 하위 타입을 따로 만들지 않고도 호출자에게 명시적인 취소 표시를
줍니다:

```java
ResultMessage result = ClaudeSDK.queryForResult("Long task", options);
if ("aborted_streaming".equals(result.terminalReason())
        || "aborted_tools".equals(result.terminalReason())) {
    System.out.println("Turn was interrupted");
}
```

CLI가 종료 사유를 보고하지 않았다면 `null`입니다 — 예전 CLI 버전이거나, 로컬 슬래시 명령처럼 질의 루프를
우회한 결과인 경우입니다. TypeScript SDK의 `SDKResultMessage.terminal_reason`에 대응합니다.

### ModelUsage

모델별 토큰과 비용 내역이며, `ResultMessage.modelUsage()`에서 모델 문자열이 키가 됩니다.

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

**JSON 명명:** CLI에서 받는 데이터로는 드물게, 이 타입은 camelCase JSON 키를 씁니다. CLI가
`modelUsage` 값을 그대로 통과시키기 때문에, 그 키는 `ResultMessage`의 다른 곳에서 쓰는 snake_case가
아니라 TypeScript SDK의 `ModelUsage` 형태와 일치합니다. Java 접근자는 `costUsd()`이고 JSON 키는
`costUSD`입니다.

`canonicalModel`은 제공자별 id와 별칭을 가로질러 클라이언트 쪽 요율표를 찾기 위한 안정적인 키를
제공합니다(Bedrock ARN이 `claude-opus-4-7`로 매핑됩니다). 덕분에 원본 모델 문자열을 파싱하지 않고도
비용 변동을 감지할 수 있습니다. `provider`는 `firstParty`, `bedrock`, `vertex`, `foundry`,
`anthropicAws`, `anthropicGoogleCloud`, `mantle`, `gateway` 중 하나입니다. 이들을 내보내지 않는 CLI
버전에서는 둘 다 `null`입니다.

```java
ResultMessage result = ClaudeSDK.queryForResult("Analyze this", options);
if (result.modelUsage() != null) {
    result.modelUsage().forEach((model, usage) ->
        System.out.printf("%s: %d in / %d out, $%.4f%n",
            model, usage.inputTokens(), usage.outputTokens(), usage.costUsd()));
}
```

파싱은 의도적으로 너그럽습니다. CLI가 내보내지 않은 카운터는 프레임을 실패시키는 대신 `0`으로 읽히고,
값이 객체가 아닌 항목은 결과 메시지 전체를 거부하는 대신 건너뜁니다. `raw()`가 원본 맵을 그대로
보존하므로, 더 새로운 CLI가 추가한 필드도 SDK를 올리지 않고 접근할 수 있습니다.

### DeferredToolUse

`permissionDecision: "defer"`를 반환한 `PreToolUse` 훅에 의해 보류된 도구 호출입니다. CLI는 실행을
멈추고 보류된 호출을 여기에 드러내어, SDK 사용자가 재개할지 결정할 수 있게 합니다.

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

## 콘텐츠 블록

### ContentBlock 인터페이스

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock
```

모든 블록은 CLI의 원본 판별 문자열인 `type()`을 노출합니다.

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

서버 쪽 도구 호출(advisor, web_search, web_fetch, code_execution 등)입니다. API가 모델을 대신해
실행하므로 호출자는 결코 결과를 돌려주지 않습니다.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // ServerToolName value (raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`ServerToolName` 열거형 값: `ADVISOR`, `WEB_SEARCH`, `WEB_FETCH`, `CODE_EXECUTION`,
`BASH_CODE_EXECUTION`, `TEXT_EDITOR_CODE_EXECUTION`, `TOOL_SEARCH_TOOL_REGEX`,
`TOOL_SEARCH_TOOL_BM25`.

### ServerToolResultBlock

서버 쪽 도구 호출에 대해 반환되는 결과 블록입니다. CLI는 이를 `advisor_tool_result` 콘텐츠 블록으로
내보내며, `content`는 불투명합니다(advisor 결과 타입에는 `advisor_result`,
`advisor_redacted_result`, `advisor_tool_result_error`가 있습니다).

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content
) implements ContentBlock
```

### ImageBlock

`Read` 도구가 PDF 페이지를 렌더링할 때 나옵니다. 도구 결과 자체는 페이지 수만 알리고, 파일은 *별도의*
사용자 메시지로 도착하며 렌더링된 페이지마다 `image` 블록 하나를 담습니다.

```java
record ImageBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`source`를 일부러 원본 맵으로 둔 이유는, API가 `base64`, `url`, `file`, `text`, `content` 형태의
source를 정의하며 더 늘어날 수 있기 때문입니다. base64 경우를 다루는 편의 접근자가 셋 있으며, 키가
없거나 문자열이 아니면 각각 `null`을 반환합니다:

| 메서드 | 반환 |
|---|---|
| `sourceType()` | `source["type"]`, 예: `"base64"` |
| `mediaType()` | `source["media_type"]`, 예: `"image/jpeg"` |
| `data()` | `source["data"]`, base64 페이로드 |

### DocumentBlock

CLI가 같은 "PDF 읽기" 흐름에서 쓰는 또 다른 형태입니다. 페이지마다 이미지를 내는 대신 파일 전체를 담은
`document` 블록 하나를 씁니다. CLI 2.1.218에서 같은 파일에 대해 실행마다 두 형태가 모두 관찰되었으므로
둘 다 모델링했습니다.

```java
record DocumentBlock(
    Map<String, Object> source   // Raw source map, kept verbatim
) implements ContentBlock
```

`ImageBlock`과 같은 `sourceType()` / `mediaType()` / `data()` 접근자를 노출하며, `mediaType()`은 보통
`"application/pdf"`입니다.

### UnknownBlock

이 SDK 버전이 모델링하지 않은 블록 타입을 위한 앞으로의 호환성 대비책입니다. `MessageParser.parse()`는
이미 알 수 없는 *메시지* 타입에 `null`을 반환하므로 더 새로운 CLI가 예전 SDK를 무너뜨리지 않습니다.
콘텐츠 블록도 예외를 던지는 대신 같은 대접을 받습니다.

```java
record UnknownBlock(
    String type,               // The unrecognised discriminator
    Map<String, Object> raw    // The block, preserved whole
) implements ContentBlock
```

파서는 알 수 없는 타입마다 한 번씩 `in.vidyalai.claude.sdk.internal.MessageParser` 로거에서 `WARNING`
수준으로 기록합니다. 이 장치가 생기기 전에는 모델링되지 않은 블록이 `MessageParseException`을 던져 읽기
스레드를 죽이고, 그 메시지의 다른 블록 — 모델이 이미 만들어 낸 텍스트까지 — 을 모두 버렸습니다.

> **참고:** `ContentBlock`은 sealed입니다. `permits` 절이 늘어나면 호출자 코드의 완전한 `switch`는
> 컴파일되지 않습니다. `default` 분기를 넣는 대신 `UnknownBlock`을 명시적으로 처리하세요. 그래야 다음
> 추가도 조용히 흘러가지 않고 여전히 컴파일 오류로 드러납니다.

## MirrorErrorMessage

치명적이지 않은 SessionStore 추가 실패입니다. 배처의 재시도 예산이 소진된 뒤에 드러나며, 로컬 디스크
트랜스크립트는 이미 안전합니다.

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

훅 수명 주기 이벤트입니다. `ClaudeAgentOptions`에 `includeHookEvents(true)`를 설정했을 때만 나옵니다.

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

`HookEventMessage`는 sealed 인터페이스의 최상위 구성원입니다(`instanceof SystemMessage`에 일치하지
않습니다). `hook_response`에서는 `data` 맵이 `output`, `exit_code`, `outcome` 키를 담습니다.

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

연결을 끝내지 않고 세션의 대화가 교체될 때 나옵니다 — `/clear` 이후, 또는 세션 도중 트랜스크립트를
버리는 다른 흐름 이후입니다.

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message {
    String type();  // Returns "conversation_reset"
}
```

스트리밍 입력 모드에서는 하나의 연결이 여러 사용자 턴을 나르며, 초기화는 대화 기록을 지우는 *동시에*
이후 `ResultMessage`에 보고되는 누적값(예: `totalCostUsd`)을 0으로 되돌립니다. 오래 사는 세션에서 그
값들을 누적하고 있다면, 이 메시지가 도착할 때 스냅숏을 남기세요.

`newConversationId`는 UI가 빈 트랜스크립트를 걸어 둘(그리고 캐시된 세션 제목을 버릴) 키입니다. 이후
메시지의 `sessionId`가 **아닙니다** — 그것은 새 값을 담고 오는 다음 메시지에서 읽으세요.

```java
case ConversationResetMessage reset -> {
    System.out.printf("session %s reset -> new conversation %s%n",
            reset.sessionId(), reset.newConversationId());
    snapshotTotals();   // subsequent results restart their counters at zero
}
```

세 필수 필드 중 하나라도 없으면 `MessageParseException`이 발생합니다.

## MessageOrigin

사용자 역할 메시지의 출처이며, `ResultMessage`에서는 그 턴을 촉발한 메시지의 출처입니다.

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

스트리밍 입력 모드에서는 하나의 연결이 여러분의 애플리케이션이 보내는 턴과 세션이 스스로 주입하는 턴 —
백그라운드 작업 알림, 발동된 예약 작업 프롬프트, MCP 채널 메시지, 피어 세션에서 중계된 메시지 — 을
번갈아 나릅니다. `origin`이 이들을 구분해 줍니다:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

`origin`이 null이면 CLI가 그 메시지에 출처를 붙이지 않았다는 뜻입니다. `ClaudeSDK.query()`나
`ClaudeSDKClient.query(String)`로 보낸 프롬프트는, `ClaudeSDKClient.query(Iterator)`를 통해 메시지 맵에
직접 `"origin": {"kind": "human"}`을 붙이지 않는 한 그렇게 도착합니다 — SDK 호스트에서 인정되는 종류는
`human`뿐이고, 그렇게 하려면 Claude Code >= 2.1.210이 필요합니다.

**앞으로의 호환성.** 이 SDK가 모델링한 것보다 새로운 `kind`는 `kind()`를 null로 남기지만
`kindValue()`가 와이어 문자열을 그대로 담고 있으며, 그에 대해 `isHuman()`은 false입니다 — 알아보지 못한
것은 모두 "사람이 아님"으로 다루세요. `subkind()`도 마찬가지입니다. CLI의 완전한 객체는 `raw()`에
보존되므로, 이 버전이 모델링하지 않은 키에도 접근할 수 있습니다.

`from`, `name`, `fromSession`은 **보낸 쪽이 주장하는** 값입니다. 회신 라우팅과 표시에 쓰되, 신원 증명으로
는 절대 쓰지 마세요.

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

`kind == TASK_NOTIFICATION`일 때 존재하며, 평범한 백그라운드 작업 알림에는 없습니다.

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

## 작업 메시지 타입

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

백그라운드 작업이 수명 주기를 지나갈 때 `system`/`task_updated` 이벤트로 나옵니다. 작업의 종료 상태가
함께 오는 `TaskNotificationMessage` 없이 `task_updated` 패치**로만** 도착하기도 합니다(예: `TaskStop`으로
멈춘 작업은 여기서 `status="killed"`를 보고합니다). 파싱은 방어적입니다 — `patch`가 없거나 맵이 아니면 빈
맵으로, 알 수 없거나 없는 상태는 `null`로 대체되므로 수명 주기 이벤트가 파싱을 무너뜨리는 일은 없습니다.

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

## 관련 항목
- [메시지 타입 가이드](./feature-message-types.md)
