# 훅 시스템

Claude 대화의 수명 주기 이벤트를 가로채고 그에 응답합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-hooks.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

훅은 에이전트 루프의 특정 지점에서 사용자 코드를 실행합니다. `HookEvent`에 콜백을 등록해 두면, CLI가 그
지점에 도달했을 때 SDK에 훅 요청을 보내고, SDK는 타입 지정된 `HookInput`으로 콜백을 호출하며, 콜백은
허용하거나 차단하거나 맥락을 추가할 수 있는 `HookOutput`으로 응답합니다.

콜백을 등록할 수 있는 훅 이벤트는 10가지입니다.

## 훅 이벤트

`HookEvent`(패키지 `in.vidyalai.claude.sdk.types.hook`):

| 상수 | 이벤트 이름 | 발동 시점 | 입력 타입 |
|----------|------------|---------------|------------|
| `PRE_TOOL_USE` | `PreToolUse` | 도구 실행 전 | `PreToolUseHookInput` |
| `POST_TOOL_USE` | `PostToolUse` | 도구가 성공한 후 | `PostToolUseHookInput` |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | 도구가 실패한 후 | `PostToolUseFailureHookInput` |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | 프롬프트가 제출될 때 | `UserPromptSubmitHookInput` |
| `STOP` | `Stop` | 세션이 중지되려 할 때 | `StopHookInput` |
| `SUBAGENT_START` | `SubagentStart` | 서브에이전트가 시작될 때 | `SubagentStartHookInput` |
| `SUBAGENT_STOP` | `SubagentStop` | 서브에이전트가 중지될 때 | `SubagentStopHookInput` |
| `PRE_COMPACT` | `PreCompact` | 컨텍스트 압축 전 | `PreCompactHookInput` |
| `NOTIFICATION` | `Notification` | 알림이 발생할 때 | `NotificationHookInput` |
| `PERMISSION_REQUEST` | `PermissionRequest` | 권한이 요청될 때 | `PermissionRequestHookInput` |

## 기본 사용법

```java
HookMatcher.HookCallback logReads = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        System.out.println("About to read: " + preToolUse.toolInput().get("file_path"));
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Read", List.of(logReads)))
    ))
    .build();
```

## HookMatcher

```java
record HookMatcher(
    @Nullable String matcher,        // which tools/events to match; null matches all
    List<HookCallback> hooks,        // callbacks to run
    @Nullable Double timeoutSeconds  // timeout for all hooks in this matcher (CLI default: 60)
)

new HookMatcher(List<HookCallback> hooks)                  // matcher = null
new HookMatcher(String matcher, List<HookCallback> hooks)  // timeoutSeconds = null
```

도구 이벤트에서 `matcher`는 도구 이름(`"Bash"`), `|`로 이은 여러 이름(`"Write|MultiEdit|Edit"`), 또는
`"*"`입니다. 이벤트별 매처 문법은
[Claude Code 훅 레퍼런스](https://docs.anthropic.com/en/docs/claude-code/hooks#structure)를 참고하세요.

콜백은 `HookMatcher.HookCallback`입니다:

```java
@FunctionalInterface
interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {}
```

콜백은 타입 지정된 입력과 `HookContext`를 받습니다:

```java
record HookContext(
    @Nullable String toolUseId,  // the tool use ID, for tool events
    @Nullable Object signal      // reserved for future abort support; always null
)
```

SDK는 반환된 future가 완료될 때까지 기다린 뒤 CLI에 응답합니다. 동기 작업이라면
`CompletableFuture.completedFuture(...)`를 반환하세요.

> **디스패치 순서:** 같은 이벤트에 여러 매처가 등록되어 있으면, CLI는 일치하는 훅 콜백을 순차적으로가
> 아니라 **동시에**(병렬로) 디스패치합니다. 각 훅은 서로 독립적이도록 설계하세요 — 하나가 끝난 뒤에
> 다른 하나가 시작된다고 가정하지 마세요(예: 통과 순서를 전제로 하는 속도 제한 훅을 엮지 마세요).

## 훅 입력

`HookInput`은 sealed 인터페이스입니다. `instanceof`로 구체 레코드에 따라 분기하세요. 모든 입력에는 다음
필드가 있습니다:

| 접근자 | 타입 | 설명 |
|----------|------|-------------|
| `hookEventName()` | `String` | 이벤트 이름(예: `"PreToolUse"`) |
| `sessionId()` | `String` | 세션 ID |
| `transcriptPath()` | `String` | 세션 트랜스크립트 경로 |
| `cwd()` | `String` | 작업 디렉터리 |
| `permissionMode()` | `@Nullable String` | 현재 권한 모드 |
| `promptId()` | `@Nullable String` | 처리 중인 사용자 프롬프트의 UUID. OpenTelemetry 이벤트의 `prompt.id` 속성과 일치합니다. 첫 사용자 입력 전까지는 null입니다 |
| `effort()` | `@Nullable HookEffort` | 현재 턴에 적용된 노력 수준으로, `HookEffort(String level)` 형태입니다(예: `"low"`, `"high"`, `"max"`. 선택한 모델에 맞춰 하향 조정된 뒤의 값). 노력 수준을 지원하는 모델에서 도구 사용 맥락 안에서 발동하는 훅에 존재합니다 |

이벤트별 필드:

| 입력 | 필드 |
|-------|--------|
| `PreToolUseHookInput` | `toolName`, `toolInput`, `toolUseId`, `agentId`, `agentType` |
| `PostToolUseHookInput` | `toolName`, `toolInput`, `toolResponse`, `toolUseId`, `agentId`, `agentType`, `durationMs` |
| `PostToolUseFailureHookInput` | `toolName`, `toolInput`, `toolUseId`, `error`, `isInterrupt`, `agentId`, `agentType`, `durationMs` |
| `PermissionRequestHookInput` | `toolName`, `toolInput`, `permissionSuggestions`, `agentId`, `agentType` |
| `UserPromptSubmitHookInput` | `prompt` |
| `StopHookInput` | `stopHookActive`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `SubagentStartHookInput` | `agentId`, `agentType` |
| `SubagentStopHookInput` | `stopHookActive`, `agentId`, `agentTranscriptPath`, `agentType`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `PreCompactHookInput` | `trigger` (`CompactTriggerType`), `customInstructions` |
| `NotificationHookInput` | `message`, `title`, `notificationType` |

CLI가 생략할 수 있는 필드는 없을 때 `null`입니다: `permissionMode`, `promptId`, `effort`, 도구 입력의
`agentId`/`agentType`, `isInterrupt`, `durationMs`, `lastAssistantMessage`, `backgroundTasks`,
`sessionCrons`, `permissionSuggestions`, `title`, `customInstructions`.

### 서브에이전트 맥락

도구와 관련된 입력(`PreToolUseHookInput`, `PostToolUseHookInput`, `PostToolUseFailureHookInput`,
`PermissionRequestHookInput`)에는 서브에이전트 맥락을 나타내는 선택 필드가 두 개 있습니다:

| 필드 | 타입 | 설명 |
|-------|------|-------------|
| `agentId` | `@Nullable String` | 서브에이전트 식별자. 작업으로 생성된 서브에이전트 안에서만 존재하며, 메인 스레드에서는 null입니다. |
| `agentType` | `@Nullable String` | 에이전트 타입 이름(예: `"general-purpose"`). 서브에이전트 안에서, 또는 `--agent`로 시작한 메인 스레드에서 존재합니다. |

```java
HookMatcher.HookCallback subagentAware = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && preToolUse.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + preToolUse.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

### 도구 실행 시간

`PostToolUseHookInput.durationMs()`와 `PostToolUseFailureHookInput.durationMs()`는 권한 프롬프트와 훅에
걸린 시간을 제외한 도구의 실행 시간을 밀리초 단위로 보고합니다.

### Stop과 SubagentStop 시점의 백그라운드 작업

`StopHookInput`과 `SubagentStopHookInput`은 세션(또는 서브에이전트)이 중지될 때 아직 실행 중인 것을
보고합니다:

- `lastAssistantMessage` — 중지 직전 마지막 어시스턴트 메시지의 텍스트.
- `backgroundTasks` — 진행 중인 백그라운드 작업으로, `BackgroundTaskSummary` 레코드입니다: `id`,
  `type`(예: `"shell"`, `"subagent"`, `"monitor"`, `"workflow"`), `status`, `description`, 그리고
  `command`(셸 작업), `agentType`(서브에이전트 작업), `server`와 `tool`(모니터 / MCP 작업), `name`(워크플로
  작업). 진행 중인 것이 없으면 비어 있습니다.
- `sessionCrons` — 나중에 세션을 재개할, 세션 범위의 예약된 깨우기로, `SessionCronSummary` 레코드입니다:
  `id`, `schedule`(cron 표현식), `recurring`(일회성 깨우기는 `false`), `prompt`.

서브에이전트의 경우 두 목록 모두 부모 세션 범위입니다. `description`, `command`, cron의 `prompt`는 최대
1000자로 제한되며, 잘린 값은 문자열 안에 `"… [+N chars]"` 표시로 끝납니다.

```java
HookMatcher.HookCallback onStop = (input, context) -> {
    if (input instanceof StopHookInput stop && stop.backgroundTasks() != null) {
        for (BackgroundTaskSummary task : stop.backgroundTasks()) {
            System.out.printf("Still running: %s (%s, %s)%n", task.description(), task.type(), task.status());
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

## HookOutput

콜백은 빌더로 만든 `HookOutput`(패키지 `in.vidyalai.claude.sdk.types.hook.output`)을 반환합니다:

```java
HookOutput.empty()                 // no fields: the CLI proceeds as normal
HookOutput.async(30_000)           // defer: {"async": true, "asyncTimeout": 30000}

HookOutput.builder()
    .shouldContinue(false)         // "continue": whether Claude proceeds after the hook
    .stopReason("Budget exhausted")// shown when continue is false
    .suppressOutput(true)          // hide stdout from transcript mode
    .decision("block")             // "block" to indicate blocking behavior
    .systemMessage("Heads up")     // warning displayed to the user
    .reason("Blocked by policy")   // feedback for Claude about the decision
    .hookSpecificOutput(specific)  // event-specific controls, see below
    .build();
```

설정한 필드만 전송됩니다. `async`가 설정되면 `async`와 `asyncTimeout`만 전송됩니다.

## 훅별 출력

`HookSpecificOutput`은 이벤트마다 레코드 하나를 둔 sealed 인터페이스입니다. 각각은 `hookEventName`과
설정한 필드를 함께 직렬화합니다(`null` 필드는 생략됨):

| 레코드 | 필드 |
|--------|--------|
| `PreToolUseHookSpecificOutput` | `permissionDecision` (`PermissionDecision`), `permissionDecisionReason`, `updatedInput`, `additionalContext` |
| `PostToolUseHookSpecificOutput` | `additionalContext`, `updatedToolOutput`, `updatedMCPToolOutput` |
| `PostToolUseFailureHookSpecificOutput` | `additionalContext` |
| `UserPromptSubmitHookSpecificOutput` | `additionalContext`, `sessionTitle`, `suppressOriginalPrompt` |
| `NotificationHookSpecificOutput` | `additionalContext` |
| `SubagentStartHookSpecificOutput` | `additionalContext` |
| `PermissionRequestHookSpecificOutput` | `decision` (Map) |
| `StopHookSpecificOutput` | `additionalContext` |
| `SubagentStopHookSpecificOutput` | `additionalContext` |
| `SessionStartHookSpecificOutput` | `additionalContext`, `initialUserMessage`, `sessionTitle`, `watchPaths`, `reloadSkills` |
| `UserPromptExpansionHookSpecificOutput` | `additionalContext`, `suppressOriginalPrompt` |
| `PostToolBatchHookSpecificOutput` | `additionalContext` |
| `PermissionDeniedHookSpecificOutput` | `retry` |
| `PreModelSwitchHookSpecificOutput` | `permissionDecision`, `permissionDecisionReason` |
| `PostModelSwitchHookSpecificOutput` | `additionalContext` |
| `ElicitationHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `ElicitationResultHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `CwdChangedHookSpecificOutput` | `watchPaths` |
| `FileChangedHookSpecificOutput` | `watchPaths` |
| `WorktreeCreateHookSpecificOutput` | `worktreePath` (필수) |
| `MessageDisplayHookSpecificOutput` | `displayContent` |

> **콜백이 쓸 수 있는 것.** SDK 콜백은 위의 `HookEvent` 10가지에만 등록되므로, 콜백이 받을 수 있는
> 이벤트에 응답하는 레코드는 처음 아홉 개뿐입니다. 나머지(`SessionStart`부터 `MessageDisplay`까지)는
> `HookEvent`가 아직 나열하지 않은 이벤트의 출력을 기술합니다. 같은 형태를 정의하는 Python 및 TypeScript
> SDK와의 동등성을 위해 타입으로 정의되어 있습니다.

필드 참고 사항:

- **`UserPromptSubmitHookSpecificOutput`** — `sessionTitle`은 세션 제목을 설정합니다.
  `suppressOriginalPrompt`는 출력의 `decision`이 `"block"`일 때 차단 메시지에서 원래 프롬프트를
  빼냅니다. 인자 하나짜리 생성자 `(additionalContext)`도 남아 있습니다.
- **`StopHookSpecificOutput` / `SubagentStopHookSpecificOutput`** — `additionalContext`는 모델(또는
  서브에이전트)에 전달되는 오류가 아닌 피드백입니다. 모델이 그에 따라 행동할 수 있도록 대화가 계속됩니다.
- **`SessionStartHookSpecificOutput`** — `initialUserMessage`는 세션의 첫 사용자 메시지가 됩니다.
  `sessionTitle`은 세션이 `clear`나 `compact`에서 시작될 때 무시됩니다. `watchPaths`는 FileChanged
  이벤트를 위해 감시할 절대 경로입니다. `reloadSkills`는 SessionStart 훅이 끝난 뒤 스킬과 명령
  디렉터리를 다시 스캔합니다.
- **`PermissionDeniedHookSpecificOutput`** — `retry`는 거부된 도구 호출을 다시 시도해도 된다고 모델에
  알립니다. 분류기 판정이 없는 거부에는 무시됩니다.
- **`PreModelSwitchHookSpecificOutput`** — `ALLOW`는 전환을 진행하고 `DENY`는 취소합니다. `ASK`는
  대화형 세션 밖에서는 거절로 처리됩니다. 생성자는 `PermissionDecision.DEFER`를 거부합니다.
- **`PostModelSwitchHookSpecificOutput`** — `additionalContext`는 새 모델이 처리하는 다음 요청과 함께
  모델에 전달됩니다.
- **`ElicitationHookSpecificOutput`** — MCP elicitation 요청을 수락, 거절 또는 취소합니다. `content`는
  폼 필드 값을 담으며 `ElicitationAction.ACCEPT`와 함께일 때만 사용됩니다.
  `ElicitationResultHookSpecificOutput`은 응답이 MCP 서버에 닿기 전에 action이나 content를 덮어씁니다.
- **`CwdChangedHookSpecificOutput` / `FileChangedHookSpecificOutput`** — `watchPaths`는 현재의 동적 감시
  목록을 대체합니다.
- **`MessageDisplayHookSpecificOutput`** — 표시 전용입니다: `displayContent`는 저장된 메시지나 모델이 보는
  내용을 바꾸지 않고 화면의 텍스트만 대체합니다. 원래 내용을 표시하려면 생략하세요.

### 도구 호출 차단하기

```java
HookMatcher.HookCallback blockRm = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse
            && String.valueOf(preToolUse.toolInput().get("command")).contains("rm -rf")) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                PermissionDecision.DENY,
                "rm -rf is not allowed",
                null,   // updatedInput
                null))  // additionalContext
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Bash", List.of(blockRm)))))
    .build();
```

### Stop 훅에서 모델이 계속 작업하게 하기

```java
HookMatcher.HookCallback keepGoing = (input, context) -> {
    if (input instanceof StopHookInput stop && !stop.stopHookActive()) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new StopHookSpecificOutput("The test suite still fails; fix it before stopping."))
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

`stopHookActive()`를 확인하면 모델을 끝없이 작업으로 되돌려 보내는 일을 막을 수 있습니다: 세션이 이미
Stop 훅 때문에 계속되고 있으면 `true`입니다.

## PostToolUse 출력 교체

`PostToolUseHookSpecificOutput`을 쓰면 `PostToolUse` 훅이 도구 출력을 모델에 닿기 전에 대체할 수
있습니다.

```java
record PostToolUseHookSpecificOutput(
    @Nullable String additionalContext,    // extra context for the model
    @Nullable Object updatedToolOutput,    // replacement for any tool's output
    @Nullable Object updatedMCPToolOutput  // replacement for MCP tool output only
)
```

- **`updatedToolOutput`** — 모든 도구(내장 포함)의 출력을 대체합니다. 내장 도구의 경우 값이 그 도구의
  출력 스키마와 맞아야 합니다(예: `Bash`는 `{"stdout": ..., "stderr": ..., "interrupted": ...}`).
  형태가 맞지 않으면 거부되고 원래 출력이 유지됩니다.
- **`updatedMCPToolOutput`** — MCP 도구의 출력만 대체합니다. 모든 도구에서 동작하는
  `updatedToolOutput`을 권장합니다.
- `updatedToolOutput`이 생기기 전에 작성된 코드를 위해 하위 호환 2인자 생성자
  `(additionalContext, updatedMCPToolOutput)`가 유지됩니다.

```java
HookMatcher.HookCallback redact = (input, context) -> {
    // Redact secrets from Bash output before the model sees it.
    Map<String, Object> redacted = Map.of(
        "stdout", "[redacted]",
        "stderr", "",
        "interrupted", false
    );
    return CompletableFuture.completedFuture(
        HookOutput.builder()
            .hookSpecificOutput(new PostToolUseHookSpecificOutput(null, redacted, null))
            .build()
    );
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.POST_TOOL_USE, List.of(new HookMatcher("Bash", List.of(redact)))))
    .build();
```

## 권한 결정: `"defer"`

`PreToolUse` 훅은 (`PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput`을 통해)
`permissionDecision: "defer"`를 반환해 도구를 실행하지 않고 실행을 중단할 수 있습니다. CLI는 보류된
호출을 `ResultMessage.deferredToolUse`에 실어 주므로, SDK 사용자가 이를 살펴보고 재개할지 결정할 수
있습니다.

```java
HookMatcher.HookCallback deferRisky = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && looksDangerous(preToolUse.toolInput())) {
        return CompletableFuture.completedFuture(
            HookOutput.builder()
                .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                    PermissionDecision.DEFER,
                    "Needs operator review",
                    null,
                    null))
                .build()
        );
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

// Caller side — inspect the deferred call from the result message.
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof ResultMessage r && r.deferredToolUse() != null) {
        DeferredToolUse d = r.deferredToolUse();
        System.out.printf("Deferred %s (id=%s) input=%s%n", d.name(), d.id(), d.input());
    }
}
```

`DeferredToolUse`는 `id`, `name`, `input`을 담고 있습니다. `ResultMessage`의 전체 형태는
[메시지 타입](./feature-message-types.md#resultmessage)을 참고하세요.

## 스트림의 훅 수명 주기 이벤트

`ClaudeAgentOptions`에 `includeHookEvents(true)`를 설정하면 훅 수명 주기 이벤트를
`HookEventMessage` 객체로 메시지 스트림에서도 받을 수 있습니다. 관찰하고 싶은 이벤트마다 훅을 등록하지
않고도 가시성(모든 훅 발동을 기록)을 확보할 수 있어 유용합니다. CLI는 이 설정과 상관없이 `SessionStart`와
`Setup` 훅 이벤트를 내보냅니다.

```java
var options = ClaudeAgentOptions.builder()
    .includeHookEvents(true)
    .hooks(Map.of(/* still register hooks normally */))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof HookEventMessage hook) {
        // subtype is "hook_started" or "hook_response"
        System.out.printf("[%s] %s session=%s%n",
            hook.subtype(), hook.hookEventName(), hook.sessionId());
        // Full raw payload (output, exit_code, outcome on hook_response)
        Object outcome = hook.get("outcome");
        if (outcome != null) {
            System.out.println("  outcome: " + outcome);
        }
    }
}
```

`HookEventMessage`의 필드:

| 필드 | 타입 | 설명 |
|-------|------|-------------|
| `subtype` | `String` | 훅이 시작될 때는 `"hook_started"`, 완료될 때는 `"hook_response"` |
| `data` | `Map<String, Object>` | CLI가 보낸 원본 이벤트 딕셔너리 전체(`hook_response`에는 `output`, `exit_code`, `outcome`) |
| `hookEventName` | `String` | 훅 이벤트 이름(예: `"PreToolUse"`, `"PostToolUse"`, `"Stop"`) |
| `sessionId` | `@Nullable String` | 이 이벤트가 속한 세션 ID |
| `uuid` | `@Nullable String` | 고유 이벤트 ID |

`HookEventMessage.type()`은 `"system"`을 반환하지만 `instanceof SystemMessage`에는 일치하지
**않습니다** — `HookEventMessage`로 직접 분기하세요.

## 전체 예제

```java
public class HooksExample {
    public static void main(String[] args) {
        // Log every tool call before it runs
        HookMatcher.HookCallback logPre = (input, context) -> {
            if (input instanceof PreToolUseHookInput pre) {
                System.out.println("Tool: " + pre.toolName());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Track tool results and timing
        HookMatcher.HookCallback logPost = (input, context) -> {
            if (input instanceof PostToolUseHookInput post) {
                System.out.println("Result: " + post.toolResponse() + " in " + post.durationMs() + "ms");
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Handle errors
        HookMatcher.HookCallback logFailure = (input, context) -> {
            if (input instanceof PostToolUseFailureHookInput failure) {
                System.err.println("Error: " + failure.error());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("*", List.of(logPre))),
                HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logPost))),
                HookEvent.POST_TOOL_USE_FAILURE, List.of(new HookMatcher("*", List.of(logFailure)))
            ))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("List files in current directory");
            for (var msg : client.receiveResponse()) {
                // Process
            }
        }
    }
}
```

## 백그라운드 서브에이전트가 끝난 뒤의 훅

일회성 `ClaudeSDK.query(...)`에서 백그라운드 서브에이전트의 완료가 깨운 턴의 훅은, CLI가 그 훅을 호출할 때
stdin이 아직 열려 있어야만 실행됩니다. SDK는 첫 result에서가 아니라 실행이 끝날 때까지 stdin을 열어 둡니다.
규칙과 CLI 버전 관련 주의 사항은 [에이전트 → 백그라운드 서브에이전트와 콜백](./feature-agents.md#백그라운드-서브에이전트와-콜백)을,
실행 가능한 데모는 `BackgroundAgentHooksExample`을 참고하세요.

## 관련 항목
- [구성 옵션](./feature-configuration-options.md#훅)
- [권한](./feature-permissions.md)
- [Hooks 예제](../../examples/src/main/java/examples/Hooks.java)
