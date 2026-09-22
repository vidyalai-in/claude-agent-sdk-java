# 권한 시스템

도구 사용에 대한 사용자 정의 권한 제어입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-permissions.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

권한 시스템은 Claude가 어떤 도구를 쓸 수 있는지, 그리고 권한 요청을 어떻게 처리할지 제어합니다.

## 권한 모드

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

### 사용 가능한 모드

- **PROMPT**(기본) — 권한마다 사용자에게 묻습니다
- **ACCEPT_ALL** — 모든 권한을 자동으로 승인합니다
- **ACCEPT_EDITS** — 파일 편집은 자동 승인하고 나머지는 묻습니다
- **BYPASS_PERMISSIONS** — 권한 검사를 완전히 건너뜁니다
- **DONT_ASK** — 묻지 않고 모든 도구를 허용합니다
- **AUTO** — 적절한 권한 모드를 자동으로 결정합니다

## 사용자 정의 권한 콜백

세밀하게 제어하려면 `canUseTool` 콜백을 사용하세요. 모든 진입점에서 동작합니다 — 문자열 프롬프트도
내부적으로는 stdin을 통해 스트리밍되므로, 권한 요청을 실어 나르는 제어 프로토콜을 거기서도 쓸 수
있습니다. `permissionPromptToolName`과는 함께 쓸 수 없습니다:

```java
.canUseTool((toolName, input, context) -> {
    // Custom logic
    if (shouldAllow(toolName, context.blockedPath())) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Access denied: " + context.blockedPath())
        );
    }
})
```

> **`canUseTool`은 `"ask"` 결정에서만 발동합니다.** 이 콜백은 대화형 권한 프롬프트를 대신하는 SDK의
> 수단이며, CLI의 권한 규칙이 `"ask"`로 평가될 때만 실행됩니다. `allowedTools`, `permissionMode`(예:
> `ACCEPT_EDITS`, `BYPASS_PERMISSIONS`), 설정의 `permissions.allow` 규칙으로 이미 허용된 도구 호출에
> 대해서는 **호출되지 않습니다** — 그런 호출은 애초에 프롬프트까지 가지 않습니다. 권한 규칙과 무관하게
> **모든** 도구 호출을 관찰하거나 통제하려면, 대신 `hooks(...)`로 `PreToolUse` 훅을 등록하세요.

### 섀도잉 경고

`canUseTool`은 다른 옵션으로 이미 허용된 호출에서는 절대 발동하지 않으므로, SDK는 눈에 띄게 가려진
콜백을 감지하면 연결 시점에 **참고용 경고**를 냅니다. 이 검사는 질의를 구성할 때마다 한 번
실행되며(`ClaudeSDKClient.connect()`가 시작될 때, 또는 어떤 `ClaudeSDK.query(...)`든 준비될 때),
`java.util.logging`을 통해 `in.vidyalai.claude.sdk.internal.CanUseToolShadow`라는 이름의 로거에
`WARNING`을 기록합니다.

`canUseTool` 콜백은 다음 중 하나와 함께 설정되면 가려진 것으로 보고됩니다:

- **`permissionMode(PermissionMode.BYPASS_PERMISSIONS)`** — 콜백에 묻기 전에 모든 도구 호출이 자동
  승인됩니다(명시적 거부 규칙은 예외).
- **도구 *전체*를 허용하는 `allowedTools` 항목** — 지정자가 없는 항목(`"Read"`), 지정자가 빈
  항목(`"Read()"`), 지정자가 와일드카드 하나뿐인 항목(`"Read(*)"`)입니다. `"Bash(ls:*)"`처럼 범위를
  좁히는 지정자는 콜백을 **가리지 않습니다**. 일치하지 않는 호출은 여전히 콜백으로 내려오기
  때문입니다. 경고는 가려진 도구를 하나하나 밝혀 줍니다.

`skills("all")`(`Builder.skillsAll()` 경유)도 고려됩니다. 이는 전송 계층이 맨 `Skill` 허용 규칙을
주입하게 하므로, 손으로 쓴 `"Skill"` 항목과 똑같이 콜백을 가립니다. 이름이 지정된
skill(`skills(List.of("reviewer"))`)은 `Skill(name)` 지정자를 주입하므로 가리지 않습니다.

이 경고는 **참고용일 뿐이며 결코 예외를 던지지 않습니다**. 가려짐이 의도적일 수도 있습니다(예:
`allowedTools`에 *없는* 도구만을 위해 쓰는 콜백). 권한 규칙과 무관하게 모든 도구 호출을 관찰하거나
통제하려면 대신 `PreToolUse` 훅을 사용하세요 — 다만 *허용* 결정을 반환하는 `PreToolUse` 훅 역시
`canUseTool`을 건너뛴다는 점에 유의하세요. 설정 파일에 있는 허용 규칙도 콜백을 가릴 수 있지만, 이
검사에서는 보이지 않습니다.

경고를 끄려면 `in.vidyalai.claude.sdk.internal.CanUseToolShadow` 로거의 레벨을 `WARNING`보다 높이
올리세요:

```java
java.util.logging.Logger
    .getLogger("in.vidyalai.claude.sdk.internal.CanUseToolShadow")
    .setLevel(java.util.logging.Level.SEVERE);
```

### 콜백을 결정론적으로 유지하기

설정 파일 규칙은 이 참고 경고가 볼 수 없는 유일한 가려짐 사례이자, 가장 걸려 넘어지기 쉬운
경우입니다. `~/.claude/settings.json`의 `permissions.allow` 아래에 맨 `Write(*)` 한 줄만 있어도
`canUseTool` 콜백이 아예 발동하지 않게 되며, 어제까지 잘 되던 기계에서 그렇게 됩니다. 아무 오류도
나지 않습니다 — 콜백은 그저 한 번도 조회되지 않고, 처리한 프롬프트 수를 세는 코드는 0을 보고합니다.

콜백이 예측 가능하게 발동해야 하는 상황(데모, 테스트, 재현 가능한 스크립트)에서는 설정 파일을 전혀
불러오지 마세요:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .canUseTool(callback)
        // Load no settings files: an allow rule in the user's or the project's
        // settings.json shadows the callback exactly like an allowedTools
        // entry does, and the advisory above cannot see those rules.
        .settingSources(List.of())
        .permissionMode(PermissionMode.DEFAULT)
        .build();
```

또한 `allowedTools`에 올라 있는 도구는 프롬프트까지 가지 않으므로, 그 도구를 통제하려는 콜백이라면
목록에서 빼 두어야 합니다. examples 모듈의 `PermissionCallbacks.java`는 두 가지를 모두 합니다.

> **관용 표현에 관한 참고:** Python SDK는 이 상황을 `CanUseToolShadowedWarning`(`UserWarning`의
> 하위 클래스)으로, TypeScript SDK는 `CLAUDE_SDK_CAN_USE_TOOL_SHADOWED` 프로세스 경고로 보고합니다.
> Java SDK는 전용 경고 타입 대신 관용적인 경고 채널인 `java.util.logging`을 사용합니다.

## ToolPermissionContext

CLI는 권한 컨텍스트를 풍부하게 채워, 콜백이 원본 도구 입력에서 다시 조립하지 않고도 의미 있는
프롬프트를 그릴 수 있게 합니다:

```java
record ToolPermissionContext(
    @Nullable Object signal,                 // reserved for future abort signal support (always null today)
    List<PermissionUpdate> suggestions,      // permission suggestions from the CLI
    @Nullable String toolUseId,              // unique tool call ID within the assistant message
    @Nullable String agentId,                // sub-agent's ID if running inside a sub-agent
    @Nullable String blockedPath,            // file path that triggered the request (e.g. Bash hitting a denied path)
    @Nullable String decisionReason,         // why this prompt was triggered (e.g. PreToolUse hook's permissionDecisionReason)
    @Nullable String title,                  // full prompt sentence ("Claude wants to read foo.txt") — use as primary prompt text
    @Nullable String displayName,            // short noun phrase ("Read file") for buttons / compact UI
    @Nullable String description             // human-readable subtitle for the permission UI
)
```

풍부한 필드가 생기기 전에 작성된 코드를 위해 하위 호환 생성자가 유지됩니다:

- `new ToolPermissionContext()` — 빈 컨텍스트
- `new ToolPermissionContext(suggestions)` — suggestions만
- `new ToolPermissionContext(signal, suggestions)` — signal + suggestions
- `new ToolPermissionContext(signal, suggestions, toolUseId, agentId)` — 확장 이전의 4인자 형태

```java
.canUseTool((toolName, input, context) -> {
    // Prefer the CLI-supplied prompt text when present.
    String prompt = context.title() != null
        ? context.title()
        : "Allow " + toolName + "?";
    String why = context.decisionReason();
    if (why != null) prompt += " (" + why + ")";

    boolean ok = askUser(prompt);
    return CompletableFuture.completedFuture(
        ok ? new PermissionResultAllow()
           : new PermissionResultDeny("user declined"));
})
```

## PermissionDecision (PreToolUse 훅에서)

`PermissionDecision`은 `PreToolUse` 훅이 `PreToolUseHookSpecificOutput.permissionDecision`에서
반환하는 값입니다:

| 상수 | 와이어 값 | 효과 |
|----------|------------|--------|
| `ALLOW` | `"allow"` | 묻지 않고 도구를 실행합니다. |
| `DENY` | `"deny"` | 도구를 차단합니다. |
| `ASK` | `"ask"` | SDK의 `canUseTool` 콜백(또는 CLI 프롬프트)을 발동합니다. |
| `DEFER` | `"defer"` | 도구를 실행하지 않고 실행을 중단합니다. 보류된 호출은 `ResultMessage.deferredToolUse`에 나타납니다. [훅 → 권한 결정 `"defer"`](./feature-hooks.md#권한-결정-defer)를 참고하세요. |

## PermissionResult

```java
// Allow
new PermissionResultAllow()

// Deny with reason
new PermissionResultDeny("Reason for denial")
```

## 예제

### 경로 기반 권한

```java
.canUseTool((toolName, input, context) -> {
    // blockedPath is set by the CLI when the request was triggered by a
    // path violation (e.g. a Bash command touching a denied directory).
    // For tools like Read / Write the path is in `input` instead.
    String path = context.blockedPath() != null
        ? context.blockedPath()
        : (String) input.get("file_path");

    // Allow read-only in /src
    if (toolName.equals("Read") && path != null && path.startsWith("/src")) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    }

    // Deny write to sensitive dirs
    if (toolName.equals("Write") && path != null && path.contains("/config")) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Cannot write to config")
        );
    }

    // Default allow
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### 시간 기반 권한

```java
.canUseTool((toolName, input, context) -> {
    // Only allow during business hours
    int hour = LocalTime.now().getHour();
    if (hour < 9 || hour > 17) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Outside business hours")
        );
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### 사용자 확인

```java
.canUseTool((toolName, input, context) -> {
    // Prompt user for dangerous operations
    if (toolName.equals("Bash")) {
        boolean approved = promptUser("Allow bash: " + input + "?");
        if (approved) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("User rejected")
            );
        }
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

## 관련 항목
- [구성 옵션](./feature-configuration-options.md#권한-설정)
- [권한 콜백 예제](../../examples/src/main/java/examples/PermissionCallbacks.java)
