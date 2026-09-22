# 메시지 타입

Claude 대화를 처리하기 위한 메시지 타입 시스템 이해하기.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-message-types.md)보다 오래되었을 수 있으며, 내용이 다를 경우 영어판이 우선합니다. 코드 블록은 영어 원문과 동일하게 유지하며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [상위 호환성](#상위-호환성)
- [메시지 타입 계층](#메시지-타입-계층)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [태스크 메시지](#태스크-메시지)
- [MirrorErrorMessage](#mirrorerrormessage)
- [HookEventMessage](#hookeventmessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [RateLimitEvent](#ratelimitevent)
- [콘텐츠 블록](#콘텐츠-블록)
- [패턴 매칭](#패턴-매칭)
- [예제](#예제)

## 개요

SDK는 타입 안전한 메시지 처리를 위해 봉인 인터페이스 계층을 사용합니다. 모든 메시지가 `Message` 봉인 인터페이스를 구현하므로 빠짐없는 패턴 매칭이 가능합니다.

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}
```

## 상위 호환성

`MessageParser`는 새로운 CLI 버전과의 상위 호환성을 염두에 두고 설계되었습니다. SDK가 모르는 메시지 타입을 CLI가 내보내면 파서는 예외를 던지지 않고 `null`을 반환합니다. 메시지 이터레이터는 `null` 메시지를 자동으로 건너뛰므로, 새 메시지 타입을 내보내는 최신 CLI에 연결해도 코드는 계속 올바르게 동작합니다.

```java
// MessageParser.parse() returns @Nullable Message
// Unknown types return null and are silently skipped by the iterator
for (Message msg : ClaudeSDK.query(prompt)) {
    // Only known message types arrive here; unknown types are silently skipped
    switch (msg) { ... }
}
```

**알 수 없는 타입과 잘못된 내용은 다릅니다.** `null`을 반환하는 것은 *인식하지 못한 메시지 타입*에만 해당합니다. 구조가 잘못된 *알려진* 타입(`user` / `assistant`)은 다른 경우로, 파서가 조용히 버리는 대신 `MessageParseException`을 일으켜 실제 프로토콜 위반이 숨겨지지 않게 합니다. 0.1.18부터는 이것이 명시적으로 강제됩니다. `content`가 리스트가 아닌 `assistant` 메시지(예: 그냥 문자열)는 `"Invalid assistant content (expected list, got …)"`를, `content` 리스트의 원소가 객체가 아닌 경우(`user`와 `assistant` 모두)는 `"Invalid content block (expected dict, got …)"`를 일으킵니다. 예전에는 이런 상황이 원시 `ClassCastException`으로 드러났지만, 이제는 파서의 다른 구조 검증과 마찬가지로 일관되게 `MessageParseException`으로 보고됩니다.

## 메시지 타입 계층

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

> **호환성 참고(v0.1.23).** `ConversationResetMessage`가 이 봉인 유니온을 넓혔기 때문에,
> `default` 분기가 없는 빠짐없는 `switch`는 case를 추가하기 전까지 컴파일되지 않습니다.
> [패턴 매칭](#패턴-매칭)을 참고하세요.

## UserMessage

사용자가 보낸 메시지를 나타냅니다. 내용은 단순한 문자열일 수도 있고 구조화된 콘텐츠 블록의 리스트일 수도 있습니다(예: 도구 결과가 포함된 경우).

### 필드

```java
record UserMessage(
    Object content,                          // String or List<ContentBlock>
    @Nullable String uuid,                   // Unique message identifier
    @Nullable String parentToolUseId,        // Set when inside a subagent tool use
    @Nullable Map<String, Object> toolUseResult  // Tool execution metadata (file edits, etc.)
) implements Message
```

### 메서드

| 메서드 | 설명 |
|--------|-------------|
| `type()` | `"user"` 반환 |
| `contentAsString()` | 내용을 String으로 반환. 구조화된 경우 null |
| `contentAsBlocks()` | 내용을 `List<ContentBlock>`으로 반환. 문자열인 경우 null |

### 예제

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

Claude가 보낸 메시지를 나타내며, 하나 이상의 콘텐츠 블록을 담습니다.

### 필드

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

`usage` 맵은 가능한 경우 턴별 API 토큰 소비 데이터를 담으며 `input_tokens`, `output_tokens`, 캐시 관련 키 등이 들어갑니다. `messageId`, `stopReason`, `sessionId`, `uuid` 필드는 API 수준의 식별자를 담습니다. 이들은 null이 될 수 있고 부분/스트리밍 메시지에는 없습니다. 새 필드가 없는 하위 호환 생성자도 제공됩니다.

### 메서드

| 메서드 | 설명 |
|--------|-------------|
| `type()` | `"assistant"` 반환 |
| `getTextContent()` | 모든 `TextBlock` 콘텐츠 블록의 텍스트를 이어 붙임 |
| `hasToolUse()` | `ToolUseBlock`이 하나 이상 있으면 `true` |

### AssistantMessageError 값

| 열거형 상수 | 문자열 값 | 설명 |
|---------------|-------------|-------------|
| `AUTHENTICATION_FAILED` | `"authentication_failed"` | API 키가 잘못되었거나 없음 |
| `BILLING_ERROR` | `"billing_error"` | 결제 문제 |
| `RATE_LIMIT` | `"rate_limit"` | 요청 한도 초과 |
| `INVALID_REQUEST` | `"invalid_request"` | 잘못된 형식의 요청 |
| `SERVER_ERROR` | `"server_error"` | 서버 내부 오류 |
| `UNKNOWN` | `"unknown"` | 알 수 없거나 인식되지 않는 오류 |

### 예제

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

CLI가 보내는 시스템 수준의 알림과 이벤트입니다.

### 필드

```java
record SystemMessage(
    String subtype,           // Message subtype (e.g., "init")
    Map<String, Object> data  // Full raw message data
) implements Message
```

### 예제

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System event: " + system.subtype());
    System.out.println("Data: " + system.data());
}
```

## 태스크 메시지

태스크 메시지는 서브에이전트 태스크의 수명 주기 이벤트 중에 나오는, 타입이 지정된 시스템 메시지 하위 유형입니다. `Message`를 직접 구현하며 `type()`은 `"system"`을 반환합니다. 기존의 `instanceof SystemMessage` 검사는 이들과 **일치하지 않습니다** — 구체적인 타입을 사용하세요.

### TaskStartedMessage

태스크(서브에이전트)가 시작될 때 나옵니다.

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

| 메서드 | 설명 |
|--------|-------------|
| `type()` | `"system"` 반환 |
| `get(String key)` | 원시 data 맵에서 값을 가져옴 |

### TaskProgressMessage

태스크가 실행되는 동안 주기적으로 나옵니다.

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

| 메서드 | 설명 |
|--------|-------------|
| `type()` | `"system"` 반환 |
| `get(String key)` | 원시 data 맵에서 값을 가져옴 |

### TaskNotificationMessage

태스크가 완료되거나 실패하거나 중지될 때 나옵니다.

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

백그라운드 태스크가 수명 주기를 거치는 동안 `system`/`task_updated` 이벤트로 나옵니다. `patch`는 변경된 필드(예: `status`, `end_time`)를 담습니다.

태스크의 종료 상태가 `TaskNotificationMessage` 없이 **`TaskUpdatedMessage`로만** 도착하는 경우가 있습니다. 예를 들어 `TaskStop`으로 중지한 태스크는 여기서 `status="killed"`를 보고하고, 대응하는 알림은 억제되기도 합니다. 활성 태스크 ID를 추적하는 쪽은 **둘 중 어느** 메시지에서든 종료 상태가 오면 정리해야 합니다.

파싱은 방어적입니다 — `patch`가 없거나 맵이 아니면 빈 맵으로, 알 수 없거나 없는 상태는 `null`로 대체되므로 수명 주기 이벤트가 파싱을 무너뜨리는 일은 없습니다.

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

| 메서드 | 설명 |
|--------|-------------|
| `type()` | `"system"` 반환 |
| `isTerminal()` | `status`가 있고 `TERMINAL_TASK_STATUSES`에 속하면 `true` |
| `get(String key)` | 원시 data 맵에서 값을 가져옴 |
| `TaskUpdatedMessage.TERMINAL_TASK_STATUSES` | `Set.of("completed", "failed", "stopped", "killed")` — 두 태스크 수명 주기 용어를 아우르는 종료 상태 |

### TaskUpdatedStatus

`task_updated`는 원시 값 `KILLED`를 보고합니다. CLI는 `task_notification`을 내보낼 때만 그것을 `STOPPED`(`TaskNotificationStatus`)로 매핑합니다.

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

`TaskUpdatedStatus.fromValueOrNull(String)`은 알 수 없는 값이나 `null`에도 예외를 던지지 않고 원시 상태를 해석합니다(방어적 파싱에 사용). `fromValue(String)`은 알 수 없는 값에 예외를 던집니다.

### TaskUsage

태스크의 사용량 통계:

```java
record TaskUsage(
    int totalTokens,   // total tokens used
    int toolUses,      // number of tool invocations
    int durationMs     // task duration in milliseconds
)
```

### 예제: 태스크 메시지 처리하기

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

재시도를 모두 소진한 뒤(`MIRROR_APPEND_MAX_ATTEMPTS=3`) `SessionStore.append()` 호출이 실패했을 때 나오는, 치명적이지 않은 시스템 메시지입니다. 로컬 디스크의 트랜스크립트는 이미 안전하게 저장되어 있으므로 세션은 영향을 받지 않습니다 — 외부 저장소의 미러 사본에서 실패한 배치가 빠질 뿐입니다.

```java
record MirrorErrorMessage(
    String subtype,                    // always "mirror_error"
    Map<String, Object> data,          // raw payload
    @Nullable SessionKey key,          // store key the failed append targeted (null if pre-resolution)
    String error                       // failure description
) implements Message
```

최상위 `Message` 봉인 인터페이스의 멤버로 모델링되어 있습니다(Java 레코드는 레코드를 상속할 수 없습니다). `subtype`은 항상 `"mirror_error"`이며, 기본 `data` 필드는 `SystemMessage` 방식의 다운캐스트를 위해 원시 페이로드를 담습니다.

### 예제: 미러 오류에서 복구하기

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

전체 미러링 흐름과 재시도 방식은 [Session Store](./feature-session-store.md)를 참고하세요.

## HookEventMessage

메시지 스트림에 드러나는 훅 수명 주기 이벤트입니다. `ClaudeAgentOptions`에 `includeHookEvents(true)`를 설정했을 때만 나옵니다. [훅 → 스트림의 훅 수명 주기 이벤트](./feature-hooks.md#스트림의-훅-수명-주기-이벤트)를 참고하세요.

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

`type()`은 `SystemMessage`와의 대칭성을 위해 `"system"`을 반환하지만, `HookEventMessage`는 별개의 봉인 인터페이스 멤버입니다 — `instanceof SystemMessage`는 **일치하지 않습니다**. `HookEventMessage`로 직접 분기하세요.

`subtype` 값:

- `"hook_started"` — 훅 실행이 시작될 때.
- `"hook_response"` — 훅이 완료될 때. `data`에 `output`, `exit_code`, `outcome` 키가 들어 있습니다.

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

각 대화 턴이 끝날 때 전송되는 최종 결과로, 소요 시간·비용·사용량 정보를 담습니다.

### 필드

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

`errors` 필드는 CLI가 보낸 오류 메시지 목록으로, 0이 아닌 종료 코드를 진단할 때 유용합니다. `modelUsage` 필드는 모델별 토큰 내역을 `ModelUsage` 타입으로 제공합니다 — [API 참조 → ModelUsage](./api-message-types.md#modelusage)를 보세요. `deferredToolUse` 필드는 `PreToolUse` 훅이 `permissionDecision: "defer"`를 반환했을 때 설정됩니다 — [훅 → 권한 결정 `"defer"`](./feature-hooks.md#권한-결정-defer)를 보세요. `apiErrorStatus` 필드는 `isError=true`이고 `subtype="success"`일 때(즉 API는 실패했지만 세션 자체는 완료된 경우) 실패한 API 호출의 HTTP 상태 코드(예: `429`, `500`, `529`)를 담습니다. 메시지 내용을 담지 않으므로 로그에 남겨도 안전합니다. 새 필드가 없는 하위 호환 생성자도 제공됩니다.

`terminalReason` 필드는 쿼리 루프가 끝난 이유를 알려 줍니다 — `"completed"`, `"max_turns"`, `"aborted_streaming"`, `"aborted_tools"`. 두 `aborted_*` 값은 해당 턴이 `ClaudeSDKClient.interrupt()`로 취소되었다는 뜻이며, 별도의 결과 subtype 없이도 호출자가 명시적인 취소 표시를 얻게 해 줍니다.

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

CLI가 종료 이유를 보고하지 않으면 `null`입니다 — 오래된 CLI 버전이거나, 로컬 슬래시 명령처럼 쿼리 루프를 거치지 않은 결과일 때입니다.

### DeferredToolUse

```java
record DeferredToolUse(
    String id,                       // unique identifier of the deferred tool call
    String name,                     // tool name
    Map<String, Object> input        // tool input arguments
)
```

### 흔한 subtype

- `"success"` — 대화가 정상적으로 완료됨
- `"error_max_budget_usd"` — 예산 한도에 도달
- `"error_max_turns"` — 최대 턴 수에 도달

### 예제

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

스트리밍 중의 부분 메시지 업데이트입니다. `includePartialMessages`를 켰을 때만 나옵니다.

### 필드

```java
record StreamEvent(
    String uuid,                             // Unique event identifier
    String sessionId,                        // Session identifier
    Map<String, Object> event,               // Raw Anthropic API stream event data
    @Nullable String parentToolUseId         // Set when inside a subagent tool use
) implements Message
```

### 메서드

| 메서드 | 설명 |
|--------|-------------|
| `type()` | `"stream_event"` 반환 |
| `eventType()` | 내부 event 맵에서 이벤트 타입 문자열을 반환. 없으면 null |

### 흔한 이벤트 타입(`event.get("type")` 기준)

- `"content_block_start"` — 새 콘텐츠 블록 시작
- `"content_block_delta"` — 콘텐츠 증분 업데이트
- `"content_block_stop"` — 콘텐츠 블록 완료
- `"message_start"` — 메시지 시작
- `"message_delta"` — 메시지 업데이트
- `"message_stop"` — 메시지 완료

### 예제

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

요청 한도 상태가 바뀔 때마다 CLI가 내보냅니다. 사용자가 하드 리밋에 도달하기 전에 경고하거나, 한도를 초과했을 때 부드럽게 물러나는 데 쓰세요.

### 필드

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

| 열거형 상수 | 문자열 값 | 설명 |
|---------------|-------------|-------------|
| `ALLOWED` | `"allowed"` | 한도 내. 조치 필요 없음 |
| `ALLOWED_WARNING` | `"allowed_warning"` | 한도에 가까워짐 — 사용자에게 경고 |
| `REJECTED` | `"rejected"` | 한도에 도달 — 요청이 거부됨 |

### RateLimitType

| 열거형 상수 | 문자열 값 | 설명 |
|---------------|-------------|-------------|
| `FIVE_HOUR` | `"five_hour"` | 5시간 이동 창 |
| `SEVEN_DAY` | `"seven_day"` | 7일 이동 창 |
| `SEVEN_DAY_OPUS` | `"seven_day_opus"` | Opus 모델용 7일 이동 창 |
| `SEVEN_DAY_SONNET` | `"seven_day_sonnet"` | Sonnet 모델용 7일 이동 창 |
| `OVERAGE` | `"overage"` | 초과분/종량제 한도 |

### 예제

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

연결을 끝내지 않은 채 세션의 대화가 교체될 때 나옵니다 — `/clear` 이후나, 세션 도중 트랜스크립트를 버리는 다른 흐름 이후입니다. v0.1.23 이전에는 파서가 이 프레임을 조용히 버렸기 때문에 애플리케이션은 자신이 일으키지 않은 리셋을 포함해 어떤 리셋도 볼 수 없었습니다.

### 필드

```java
record ConversationResetMessage(
    String newConversationId,  // Opaque id for the fresh conversation
    String uuid,               // Unique identifier of this message
    String sessionId           // The session that was reset (the outgoing one)
) implements Message
```

### 왜 중요한가

리셋은 대화 기록을 비우고, *또한* 이후의 `ResultMessage`가 보고하는 누적값을 0으로 되돌립니다. 오래 살아 있는 스트리밍 세션에서 비용이나 토큰 사용량을 누적하고 있다면, 이 프레임이 값이 0으로 되돌아가기 전에 스냅샷을 찍을 유일한 신호입니다.

또한 세션 ID의 경계를 나타냅니다. `newConversationId`는 UI가 빈 트랜스크립트를 걸어 둘 키이지, 뒤따르는 메시지의 `sessionId`가 **아닙니다**. 리셋 이후의 메시지는 새 `sessionId`를 가지므로 다음 메시지에서 읽으세요.

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

## 메시지 출처

`UserMessage.origin()`과 `ResultMessage.origin()`은 어떤 턴이 *왜* 시작되었는지를 드러냅니다. 스트리밍 입력 모드에서는 하나의 연결 안에서 애플리케이션이 보내는 턴과 세션이 스스로 끼워 넣는 턴 — 백그라운드 태스크 알림, 발동된 예약 태스크 프롬프트, MCP 채널 메시지, 동료 세션에서 중계된 메시지 — 이 뒤섞입니다. `ResultMessage`에서 이 필드는 그 턴을 *촉발한* 메시지의 출처를 알려 주며, 덕분에 "이건 내 프롬프트에 대한 답"과 "이건 백그라운드 태스크에 대한 답"을 구분할 수 있습니다.

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
    render(origin.subkind());   // scheduled-trigger / peer-send-message, or null
}
```

CLI가 메시지에 출처를 부여하지 않으면 `origin`은 null입니다 — 여러분이 보내는 프롬프트에서는 이것이 정상입니다. 자신의 턴에도 출처를 남기려면 메시지 맵에 직접 표시한 뒤 `ClaudeSDKClient.query(Iterator)`로 보내세요.

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", prompt));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));

client.query(List.of(message).iterator());
```

SDK 호스트에서 인정되는 kind는 `human`뿐이며 Claude Code >= 2.1.210이 필요합니다. 도구 결과 성격의 사용자 메시지에는 출처가 붙지 않습니다.

인식되지 않는 kind는 오류가 되는 대신 그대로 보입니다. `kind()`는 null이고 `kindValue()`가 전송된 문자열을 담으며 `isHuman()`은 false입니다 — 즉 알 수 없는 출처는 항상 "사람이 아님"으로 읽힙니다. CLI가 보낸 객체 전체는 `raw()`에 보존됩니다. 필드별 참조: [MessageOrigin](./api-message-types.md#messageorigin).

`from`, `name`, `fromSession` 같은 필드는 **보낸 쪽이 주장한 값**입니다 — 답장 라우팅과 표시에는 쓰되, 신원의 증거로는 절대 쓰지 마세요.

## 콘텐츠 블록

어시스턴트 메시지(그리고 구조화된 사용자 메시지)는 콘텐츠 블록을 담습니다.

### ContentBlock 계층

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock,
    ServerToolUseBlock, ServerToolResultBlock,
    ImageBlock, DocumentBlock, UnknownBlock {}
```

인터페이스가 봉인되어 있으므로 `permits` 절이 늘어날 때마다 이에 대한 빠짐없는 `switch`는 컴파일되지 않습니다. `default` 분기를 추가하기보다 `UnknownBlock`을 명시적으로 처리하세요 — 그래야 다음에 새 블록 타입이 생겼을 때 조용히 지나치는 대신 조치할 수 있는 컴파일 오류로 드러납니다.

### TextBlock

Claude가 보낸 일반 텍스트 내용.

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

확장 사고가 켜져 있을 때 Claude의 내부 추론. 암호학적 서명을 포함합니다.

```java
record ThinkingBlock(
    String thinking,   // Thinking content
    String signature   // Cryptographic signature for the thinking block
) implements ContentBlock
```

### ToolUseBlock

Claude의 도구 호출.

```java
record ToolUseBlock(
    String id,                          // Tool use ID (matches ToolResultBlock.toolUseId)
    String name,                        // Tool name (e.g., "Bash", "Read")
    @Nullable Map<String, Object> input // Tool input parameters
) implements ContentBlock
```

### ToolResultBlock

도구 실행 결과.

```java
record ToolResultBlock(
    String toolUseId,          // Corresponding ToolUseBlock.id
    @Nullable Object content,  // Result content (String or structured)
    @Nullable Boolean isError  // Whether this is an error result
) implements ContentBlock
```

### ServerToolUseBlock

API가 모델을 대신해 실행하는 서버 측 도구 호출로, 호출자는 결과를 돌려주지 않습니다. `advisor`, `web_search`, `web_fetch`, `code_execution`, `bash_code_execution`, `text_editor_code_execution`, `tool_search_tool_regex`, `tool_search_tool_bm25`에 쓰입니다.

```java
record ServerToolUseBlock(
    String id,                  // Server tool use ID
    String name,                // One of ServerToolName values (kept as raw String for forward compat)
    Map<String, Object> input   // Tool input parameters
) implements ContentBlock
```

`name` 필드는 판별자입니다 — 이것으로 분기하면 어떤 서버 도구가 호출되었는지 알 수 있습니다. `ServerToolName` 열거형이 인식되는 값을 나열합니다.

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

서버 측 도구 호출에 대해 반환되는 결과 블록입니다. 형태는 `ToolResultBlock`과 같고, `content`는 API가 보낸 원시 맵이라 이 계층에서는 불투명합니다 — 특정 서버 도구의 결과 스키마가 필요한 호출자는 `content.get("type")`을 살펴볼 수 있습니다.

CLI는 이것을 `advisor_tool_result` 콘텐츠 블록으로 내보내며(현재 출시된 유일한 서버 도구 결과 타입), `ServerToolResultBlock`으로 파싱됩니다.

```java
record ServerToolResultBlock(
    String toolUseId,            // Matches the corresponding ServerToolUseBlock.id
    Map<String, Object> content  // Raw result content (e.g. {"type":"advisor_result", ...})
) implements ContentBlock
```

advisor 도구에 한해서는 `content` 맵의 `type` 필드가 다음 중 하나입니다.
- `"advisor_result"` — `text` 필드가 있는 텍스트 결과
- `"advisor_redacted_result"` — `encrypted_content` 필드가 있는 암호화된 덩어리
- `"advisor_tool_result_error"` — 오류 페이로드

### ImageBlock과 DocumentBlock

둘 다 같은 흐름, 즉 `Read` 도구로 PDF를 읽을 때 도착합니다. `tool_result` 자체는 페이지 수만 알리고, 파일은 **별도의 사용자 메시지**로 뒤따릅니다. CLI는 그 메시지에 두 가지 형태 중 하나를 쓰며, CLI 2.1.218에서 같은 1.5 MB 파일에 대해 실행마다 두 형태가 모두 관측되었습니다.

- 렌더링된 페이지마다 `image` 블록 하나(`image/jpeg`, base64), 또는
- PDF 전체를 담은 단일 `document` 블록(`application/pdf`, base64).

```java
record ImageBlock(Map<String, Object> source) implements ContentBlock
record DocumentBlock(Map<String, Object> source) implements ContentBlock
```

API가 `base64`, `url`, `file`, `text`, `content` 형태의 소스를 정의하고 앞으로 더 늘어날 수 있으므로 `source`는 원시 맵 그대로 둡니다. 다음 세 접근자가 base64 경우를 다루며, 키가 없거나 문자열이 아니면 각각 `null`을 반환합니다.

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

| 메서드 | 반환값 |
|---|---|
| `sourceType()` | `source["type"]`, 예: `"base64"` |
| `mediaType()` | `source["media_type"]`, 예: `"image/jpeg"` 또는 `"application/pdf"` |
| `data()` | `source["data"]`, base64 페이로드 |

> **PDF를 읽으려면 `maxBufferSize`를 올려야 합니다.** 이 블록들은 base64(3바이트가 4바이트로)이고 CLI 표준 출력의 한 줄에 들어가므로, 1.5 MB짜리 PDF는 대략 2.1 MB짜리 한 줄이 되어 기본값 1 MB를 넘습니다. 기본값은 그대로 두었으므로(Python SDK와 맞춤), 크기와 무관하게 파일을 읽는 호출자는 반드시 명시적으로 설정해야 합니다.
>
> ```java
> ClaudeAgentOptions options = ClaudeAgentOptions.builder()
>     .allowedTools(List.of("Read"))
>     .maxBufferSize(16 * 1024 * 1024)
>     .build();
> ```
>
> 이렇게 하지 않으면 PDF 디렉터리에 `Read` 권한을 받은 에이전트는 실행 도중 실패합니다.

### UnknownBlock

이 SDK 버전이 모델링하지 않은 블록 타입에 대한 상위 호환성.

```java
record UnknownBlock(
    String type,             // The unrecognised discriminator
    Map<String, Object> raw  // The block, preserved whole
) implements ContentBlock
```

`MessageParser`는 인식하지 못한 *메시지* 타입에 이미 `null`을 반환해 새 CLI가 예전 SDK를 무너뜨리지 못하게 합니다. 이제 콘텐츠 블록도 예외를 던지는 대신 같은 방식으로 동작합니다. 인식하지 못한 블록은 통째로 보존되고, `in.vidyalai.claude.sdk.internal.MessageParser`에서 타입마다 한 번씩 `WARNING`으로 기록됩니다.

이것이야말로 `image`와 `document`에 필요했지만 없었던 것입니다. 이전 동작은 `MessageParseException`을 던져 읽기 스레드를 죽이고, 그 메시지의 다른 블록 — 모델이 이미 만들어 낸 텍스트까지 — 을 모두 버렸으며, 호출자가 요청한 적도 없는 타입 이름을 언급하는 JSON 디코딩 실패로 드러났습니다.

## 패턴 매칭

Java의 패턴 매칭은 메시지 처리를 우아하고 타입 안전하게 만들어 줍니다.

### switch 표현식

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

`Message`가 봉인되어 있으므로 이처럼 `default`가 없는 `switch`는 허용된 모든 타입을 나열해야 하고, 컴파일러가 이를 강제합니다. 그것이 핵심입니다. 새 메시지 타입이 추가되면 런타임에 조용히 프레임을 흘리는 대신, 빠짐없는 switch마다 컴파일 오류가 납니다.

대가로, 타입 추가는 그런 코드에 소스 호환성을 깨는 변경이 됩니다. v0.1.23의 `ConversationResetMessage`가 정확히 그랬습니다. 앞으로의 추가를 조용히 흡수하고 싶다면 `default` 분기를 추가하세요.

```java
String result = switch (message) {
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    default -> "(other)";        // future message types land here
};
```

### 중첩 패턴 매칭

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

### 패턴 변수를 쓰는 instanceof

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

## 예제

### 예제 1: 모든 메시지 처리하기

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

### 예제 2: 특정 정보 추출하기

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

### 예제 3: 오류 처리하기

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

### 예제 4: 도구 사용 추적하기

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

### 예제 5: 비용과 토큰 분석

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

### 예제 6: 서브에이전트 메시지 걸러내기

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

## 함께 보기

- [간단한 쿼리](./feature-simple-queries.md) — 쿼리에서 온 메시지 처리하기
- [대화형 세션](./feature-interactive-conversations.md) — 클라이언트에서 온 메시지 처리하기
- [스트리밍 이벤트](./feature-streaming-events.md) — StreamEvent 상세
- [API 참조: 메시지 타입](./api-message-types.md) — 전체 API 문서
