# 훅 시스템

Claude 대화의 수명 주기 이벤트를 가로채고 그에 응답합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-hooks.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

훅을 사용하면 대화 수명 주기의 특정 지점에서 사용자 코드를 실행할 수 있습니다. 수신할 수 있는 훅
이벤트는 10가지입니다.

## 훅 이벤트

- **PRE_TOOL_USE** — 도구 실행 전
- **POST_TOOL_USE** — 도구가 성공적으로 실행된 후
- **POST_TOOL_USE_FAILURE** — 도구 실행이 실패한 후
- **USER_PROMPT_SUBMIT** — 사용자가 메시지를 제출할 때
- **STOP** — 세션이 중지될 때
- **SUBAGENT_START** — 서브에이전트가 시작될 때
- **SUBAGENT_STOP** — 서브에이전트가 중지될 때
- **PRE_COMPACT** — 메시지 압축 전
- **NOTIFICATION** — 알림 이벤트가 발생할 때
- **PERMISSION_REQUEST** — 권한이 요청될 때

## 기본 사용법

```java
var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "Read", context -> {
                System.out.println("About to read: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Pre-tool log"))
                );
            })
        )
    ))
    .build();
```

## HookMatcher

```java
new HookMatcher(
    String toolName,           // null for all tools
    String matchPattern,       // Tool name pattern
    Function<HookContext, CompletableFuture<HookOutput>> handler
)
```

> **디스패치 순서:** 같은 이벤트에 여러 매처가 등록되어 있으면, CLI는 일치하는 훅 콜백을 순차적으로가
> 아니라 **동시에**(병렬로) 디스패치합니다. 각 훅은 서로 독립적이도록 설계하세요 — 하나가 끝난 뒤에
> 다른 하나가 시작된다고 가정하지 마세요(예: 통과 순서를 전제로 하는 속도 제한 훅을 엮지 마세요).

## 훅 입력 필드

도구와 관련된 모든 훅 입력(`PreToolUseHookInput`, `PostToolUseHookInput`,
`PostToolUseFailureHookInput`, `PermissionRequestHookInput`)에는 서브에이전트 맥락을 나타내는 선택
필드가 두 개 있습니다:

| 필드 | 타입 | 설명 |
|-------|------|-------------|
| `agentId` | `@Nullable String` | 서브에이전트 식별자. 작업으로 생성된 서브에이전트 안에서만 존재하며, 메인 스레드에서는 null입니다. |
| `agentType` | `@Nullable String` | 에이전트 타입 이름(예: `"general-purpose"`). 서브에이전트 안에서, 또는 `--agent`로 시작한 메인 스레드에서 존재합니다. |

```java
new HookMatcher(null, null, context -> {
    PreToolUseHookInput input = (PreToolUseHookInput) context.input();
    if (input.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + input.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
})
```

## HookOutput

```java
// Empty output
HookOutput.empty()

// With logs
HookOutput.logs(List.of("Log message"))

// With messages
HookOutput.messages(List.of(
    Map.of("role", "user", "content", "Message")
))

// With permission updates
HookOutput.permissionUpdates(List.of(update))

// Combined
HookOutput.builder()
    .logs(List.of("Log"))
    .messages(List.of(message))
    .build()
```

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
HookEvent.POST_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
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
    })
)
```

## 권한 결정 `"defer"`

`PreToolUse` 훅은 (`PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput`을 통해)
`permissionDecision: "defer"`를 반환해 도구를 실행하지 않고 실행을 중단할 수 있습니다. CLI는 보류된
호출을 `ResultMessage.deferredToolUse`에 실어 주므로, SDK 사용자가 이를 살펴보고 재개할지 결정할 수
있습니다.

```java
HookEvent.PRE_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
        if (looksDangerous(input.toolInput())) {
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
    })
)

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
않고도 가시성(모든 훅 발동을 기록)을 확보할 수 있어 유용합니다.

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
        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                // Log all tool uses
                HookEvent.PRE_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
                        System.out.println("Tool: " + input.toolName());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of(
                                "Executing: " + input.toolName()
                            ))
                        );
                    })
                ),
                
                // Track tool results
                HookEvent.POST_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseHookInput input = (PostToolUseHookInput) context.input();
                        System.out.println("Result: " + input.result());
                        return CompletableFuture.completedFuture(
                            HookOutput.empty()
                        );
                    })
                ),
                
                // Handle errors
                HookEvent.POST_TOOL_USE_FAILURE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseFailureHookInput input = 
                            (PostToolUseFailureHookInput) context.input();
                        System.err.println("Error: " + input.error());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of("Tool failed"))
                        );
                    })
                )
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
- [Hooks 예제](../../examples/src/main/java/examples/Hooks.java)
