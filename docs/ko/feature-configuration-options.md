# 설정 옵션

`ClaudeAgentOptions`로 Claude SDK의 동작을 설정하는 방법에 대한 완전한 안내서입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-configuration-options.md)보다 오래되었을 수 있으며, 내용이 다를 경우 영어판이 우선합니다. 코드 블록은 영어 원문과 동일하게 유지하며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [빌더 패턴](#빌더-패턴)
- [도구 설정](#도구-설정)
- [시스템 프롬프트](#시스템-프롬프트)
- [MCP 서버](#mcp-서버)
- [권한 설정](#권한-설정)
- [세션 관리](#세션-관리)
- [한도](#한도)
- [모델 설정](#모델-설정)
- [작업 디렉터리와 CLI](#작업-디렉터리와-cli)
- [환경 변수](#환경-변수)
- [콜백](#콜백)
- [훅](#훅)
- [고급 기능](#고급-기능)
- [전체 예제](#전체-예제)

## 개요

`ClaudeAgentOptions`는 Claude SDK의 동작을 제어하는 30개 이상의 설정 옵션을 제공합니다. 타입 안전한 설정을 위해 불변 빌더 패턴을 사용합니다.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();
```

## 빌더 패턴

### 옵션 만들기

```java
// Start with builder
ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();

// Configure
builder.model("claude-sonnet-5")
       .maxTurns(10);

// Build immutable instance
ClaudeAgentOptions options = builder.build();
```

### 기본 옵션

```java
// Use defaults
ClaudeAgentOptions options = ClaudeAgentOptions.defaults();
```

### 기존 옵션 수정하기

```java
// Create from existing
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .model("claude-opus-4-6")
    .build();
```

## 도구 설정

### tools()

Claude가 사용할 수 있는 도구를 지정합니다.

```java
// Use all available tools (default)
.tools(null)

// Specify list of tool names
.tools(List.of("Read", "Write", "Bash"))

// Use preset
.tools(new ToolsPreset("code-editing"))
```

**도구 이름**:
- `Read` — 파일 읽기
- `Write` — 파일 쓰기/생성
- `Edit` — 기존 파일 편집
- `Bash` — bash 명령 실행
- `Grep` — 파일 내용 검색
- `Glob` — 패턴으로 파일 찾기
- `Task` — 서브에이전트 생성
- `WebFetch` — 웹 콘텐츠 가져오기
- `WebSearch` — 웹 검색
- MCP 도구: `mcp__<server>__<tool>`

### allowedTools()

특정 도구를 허용 목록에 넣습니다.

```java
.allowedTools(List.of(
    "Read",
    "Grep",
    "Glob",
    "mcp__calc__add"
))
```

### disallowedTools()

특정 도구를 차단 목록에 넣습니다.

```java
.disallowedTools(List.of(
    "Bash",      // Block shell access
    "Write",     // Block file writing
    "WebFetch"   // Block web access
))
```

**우선순위**: `disallowedTools`가 `allowedTools`보다 우선합니다.

## 시스템 프롬프트

### systemPrompt()

Claude의 동작을 안내하는 사용자 정의 시스템 프롬프트를 설정합니다.

```java
// String prompt
.systemPrompt("You are a code reviewer. Focus on security and performance.")

// Multi-line prompt
.systemPrompt("""
    You are a helpful coding assistant.
    - Be concise
    - Provide working code examples
    - Explain your reasoning
    """)

// Use Claude Code preset
.systemPrompt(SystemPromptPreset.claudeCode())

// Use Claude Code preset with additional instructions
.systemPrompt(SystemPromptPreset.claudeCode("Always respond in JSON format."))

// Use Claude Code preset with exclude_dynamic_sections for cross-user caching
.systemPrompt(SystemPromptPreset.claudeCode("Custom instructions", true))

// Custom prompt in the form that can also set snapshot (see below)
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))

// Use prompt from file
.systemPrompt(new SystemPromptFile("/path/to/prompt.md"))
```

| 형식 | CLI에 전달되는 방식 |
|------|--------------------|
| `String` | `--system-prompt <text>` |
| `SystemPromptCustom` | `--system-prompt <prompt>`, 그리고 `initialize` 요청의 `snapshot` |
| `SystemPromptPreset` | `append`가 설정되면 `--append-system-prompt <append>`, `initialize` 요청의 `excludeDynamicSections`와 `snapshot` |
| `SystemPromptFile` | `--system-prompt-file <path>` |
| 설정 안 함(`null`) | `--system-prompt ""` (시스템 프롬프트 없음) |

#### 스냅샷

기본적으로 Claude Code는 세션의 첫 요청에서 시스템 프롬프트를 구성해 기록해 두고, 세션을
재개한 뒤를 포함해 이후의 모든 요청에서 이를 재사용합니다. 따라서 사용자 정의 프롬프트를
바꾸거나 프리셋의 `append` 텍스트를 바꾸더라도, 세션이 압축(compact)되거나 새 세션을 시작하기
전까지는 효과가 없습니다. 예를 들어 프롬프트 문구를 반복해서 다듬는 중이라서 매 요청마다
프롬프트를 다시 구성하고 싶다면 `snapshot`을 `false`로 설정하세요:

```java
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))
.systemPrompt(SystemPromptPreset.claudeCode("Be concise.").withSnapshot(false))
```

`snapshot`은 `initialize` 제어 요청에 `systemPromptSnapshot`으로 실려 전달됩니다.
`false`를 포함해 설정되어 있으면 항상 전송되고, `null`이면 생략되어 CLI의 기본값이 적용됩니다:
기본값은 `true`이지만, bare 모드(`--bare`)에서는 `false`처럼 동작합니다. 프리셋과 사용자 정의
형식만 이 값을 가질 수 있습니다. Claude Code CLI 2.1.257 이상이 필요하며, 2.1.265 이전에는
`append` 또는 사용자 정의 프롬프트를 쓰는 세션이 `snapshot`이 `true`일 때만 프롬프트를
기록했습니다.

## MCP 서버

### mcpServers()

사용자 정의 도구를 위한 Model Context Protocol 서버를 설정합니다.

```java
// SDK MCP server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

.mcpServers(Map.of("tools", sdkServer))

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("server.js"),
    Map.of("NODE_ENV", "production")
);

.mcpServers(Map.of(
    "sdk", sdkServer,
    "external", externalServer
))

// From file path (passed as-is to --mcp-config; Java does not expand "~")
.mcpServers(Path.of(System.getProperty("user.home"), ".claude", "mcp_servers.json"))

// From an inline JSON string (also passed as-is to --mcp-config)
.mcpServersJson("""
    {"mcpServers": {
        "server1": {"type": "stdio", "command": "node", "args": ["server.js"]}
    }}
    """)
```

Map은 `--mcp-config`에 도달하기 전에 `{"mcpServers": {...}}` 형태로 직렬화됩니다. 경로나
JSON 문자열은 손대지 않고 그대로 전달되므로, JSON 문자열도 같은 최상위 `mcpServers` 키를
사용해야 합니다.

## 권한 설정

### permissionMode()

도구 권한을 어떻게 처리할지 제어합니다.

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**모드**:
- `DEFAULT`(CLI의 기본값) — 표준 권한 동작
- `ACCEPT_EDITS` — 파일 편집은 자동 승인, 나머지는 확인
- `PLAN` — 계획 모드. 도구를 실행하지 않음
- `BYPASS_PERMISSIONS` — 권한 검사를 완전히 건너뜀
- `DONT_ASK` — 묻지 않음. 허용 규칙으로 미리 승인되지 않은 것은 모두 거부
- `AUTO` — 모델 분류기가 도구 호출마다 승인 또는 거부

### permissionPromptToolName()

권한 확인에 사용할 도구를 지정합니다(고급 기능, 보통 자동으로 설정됩니다).

```java
.permissionPromptToolName("stdio")
```

## 세션 관리

### continueConversation()

이전 대화를 이어갑니다.

```java
.continueConversation(true)  // Continue from last session
.continueConversation(false) // Start fresh (default)
```

### resume()

ID로 특정 세션을 재개합니다.

```java
.resume("session-12345")
```

### sessionId()

새 세션에 사용할 세션 ID를 지정합니다.

```java
.sessionId("my-custom-session-id")
```

### forkSession()

재개한 세션을 새 세션으로 포크합니다(컨텍스트는 유지, ID는 새로 발급).

```java
.resume("session-12345")
.forkSession(true)
```

### sessionStore()

세션 트랜스크립트를 외부 저장소(S3, Postgres, Redis, 직접 만든 백엔드)에 미러링합니다. 설정하면 SDK는 CLI 호출에 `--session-mirror`를 추가하고 모든 트랜스크립트 줄을 `store.appendAsync(...)`로 전달합니다. 재개 시 `sessionStore`를 함께 쓰면 저장소의 내용을 임시 `CLAUDE_CONFIG_DIR`로 구체화하여 CLI가 로컬에서 대화를 이어받을 수 있게 합니다. 전체 기능은 [Session Store 안내서](./feature-session-store.md)를 참고하세요.

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .build();
```

**검증 가드**(서브프로세스를 띄우기 전에 `IllegalArgumentException`으로 거부):
- `continueConversation + sessionStore`에는 `store.implementsListSessions()`가 필요합니다.
- `sessionStore + enableFileCheckpointing`은 거부됩니다 — 체크포인트는 로컬 디스크 전용입니다.

### sessionStoreFlush()

트랜스크립트 미러 항목을 설정된 `sessionStore`로 언제 내보낼지 제어합니다. 기본값은 `SessionStoreFlushMode.BATCHED`(턴마다 또는 버퍼가 넘칠 때 한 번 플러시)입니다. `SessionStoreFlushMode.EAGER`를 쓰면 프레임마다 백그라운드 플러시를 예약해 거의 실시간으로 전달합니다 — 추가 작업은 큐에 넣은 순서대로 직렬화되지만, 느린 어댑터가 읽기 루프를 막지는 않습니다. `sessionStore`가 설정되지 않았다면 무시됩니다.

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

장단점은 [플러시 모드 (BATCHED 대 EAGER)](./feature-session-store.md#플러시-모드-batched-대-eager)를 참고하세요.

### loadTimeoutMs()

재개 구체화 과정에서 `store.loadAsync()`와 `listSubkeysAsync()` 호출마다 적용되는 타임아웃(밀리초)입니다. 기본값은 `60_000`. 어댑터가 이 시간 안에 끝나지 않으면 이터레이터가 멈춰 있는 대신 명확한 오류로 쿼리가 실패합니다.

```java
.loadTimeoutMs(30_000)  // 30 seconds
```

## 한도

### maxTurns()

대화의 최대 턴 수입니다.

```java
.maxTurns(10)  // Limit to 10 turns
```

**사용 사례**:
- 예산 관리
- 대화가 통제를 벗어나는 것 방지
- 짧은 쿼리: `.maxTurns(1)`

### maxBudgetUsd()

미국 달러 기준 최대 비용입니다.

```java
.maxBudgetUsd(1.0)  // Limit to $1.00
```

예산을 넘기면 실행을 중단합니다.

### taskBudget()

토큰 단위의 API 측 작업 예산입니다. 설정하면 모델이 남은 토큰 예산을 인식합니다.

```java
.taskBudget(new TaskBudget(100000))  // 100K token budget
```

### maxBufferSize()

CLI의 stdout을 버퍼링할 최대 바이트 수입니다.

```java
.maxBufferSize(10 * 1024 * 1024)  // 10MB
```

기본값: 100MB. 출력이 클 때는 늘리세요.

### thinking()

**신규**: 확장 사고 동작을 세밀하게 설정합니다.

```java
// Adaptive thinking (32K token default)
.thinking(new ThinkingConfigAdaptive())

// Fixed token budget
.thinking(new ThinkingConfigEnabled(10000))

// Disable thinking
.thinking(new ThinkingConfigDisabled())
```

**타입**:
- `ThinkingConfigAdaptive` — 적응형 사고, 기본 32,000 토큰
- `ThinkingConfigEnabled(int budgetTokens)` — 고정 토큰 예산(0보다 커야 합니다)
- `ThinkingConfigDisabled` — 사고 토큰 없음

**참고**: 이 옵션은 더 이상 권장하지 않는 `maxThinkingTokens()`보다 우선합니다.

전체 안내는 [확장 사고 설정](./feature-thinking-config.md)을 참고하세요.

### effort()

사고의 깊이/강도를 설정합니다. 오버로드가 두 개이며, 원시 문자열이나
타입 안전한 [`EffortLevel`](#effortlevel-열거형) 열거형 중 하나를 넘길 수 있습니다.

```java
// String overload
.effort("low")     // Minimal thinking, fastest responses
.effort("medium")  // Moderate thinking
.effort("high")    // Deep reasoning (default)
.effort("xhigh")   // Extended depth (Opus 4.7 only; falls back to "high")
.effort("max")     // Maximum reasoning

// Enum overload (recommended for type safety)
.effort(EffortLevel.HIGH)
.effort(EffortLevel.XHIGH)
.effort((EffortLevel) null)  // clear
```

**유효한 값**: `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`

`"xhigh"`는 Opus 4.7 전용이며 다른 모델에서는 `"high"`로 대체됩니다.

추론 깊이를 제어하려면 `thinking()`과 함께 사용합니다.

예제는 [확장 사고 설정](./feature-thinking-config.md)을 참고하세요.

### EffortLevel 열거형

`in.vidyalai.claude.sdk.types.config.EffortLevel`에 있는 공개 열거형으로, Python의
`EffortLevel` 타입 별칭에 대응합니다. 하위 SDK 래퍼가 이 타입을 직접 참조할 수 있도록
공개되어 있습니다.

| 상수 | 전송 값 | 설명 |
|----------|------------|-------------|
| `EffortLevel.LOW` | `"low"` | 최소한의 사고, 가장 빠른 응답 |
| `EffortLevel.MEDIUM` | `"medium"` | 적당한 사고 |
| `EffortLevel.HIGH` | `"high"` | 깊은 추론(기본값) |
| `EffortLevel.XHIGH` | `"xhigh"` | 확장 추론(Opus 4.7 전용, 그 외에는 `HIGH`로 대체) |
| `EffortLevel.MAX` | `"max"` | 최대한의 노력 |

도우미:
- `EffortLevel.getValue()`는 소문자 전송 값을 반환합니다(`@JsonValue` 직렬화기이기도 합니다).
- `EffortLevel.fromValue(String)`은 전송 값을 열거형 상수로 되돌립니다. 알 수 없는 값에는 `IllegalArgumentException`을 던집니다.

```java
EffortLevel level = EffortLevel.fromValue("xhigh");
String wire = level.getValue(); // "xhigh"
```

### maxThinkingTokens()

**더 이상 권장하지 않음**: 대신 `thinking()`을 사용하세요. adaptive, 토큰 예산을 지정한 활성화,
비활성화 중에서 고를 수 있습니다.

사고 블록의 최대 토큰 수입니다. 최신 모델에서는 이 값이 켜기/끄기로만 취급됩니다(0 = 비활성화,
그 밖의 값 = adaptive).

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

메시지 큐의 최대 크기입니다.

```java
.maxMsgQSize(1000)
```

처리량이 많은 상황에서는 늘리세요.

## 모델 설정

### model()

AI 모델을 설정합니다.

```java
.model("claude-sonnet-5")
```

**사용 가능한 모델**:
- `claude-opus-4-6` — 가장 뛰어나지만 비쌈
- `claude-sonnet-4-5` — 균형(기본값)
- `claude-haiku-4-5` — 빠르고 경제적

### fallbackModel()

기본 모델을 쓸 수 없을 때의 대체 모델입니다.

```java
.model("claude-opus-4-6")
.fallbackModel("claude-sonnet-4-5")
```

### betas()

베타 기능을 켭니다.

```java
.betas(List.of(
    SdkBeta.PROMPT_CACHING,
    SdkBeta.EXTENDED_THINKING
))
```

[Anthropic API Beta Headers](https://docs.anthropic.com/en/api/beta-headers)를 참고하세요.

## 작업 디렉터리와 CLI

### cwd()

파일 작업의 작업 디렉터리를 설정합니다.

```java
.cwd(Path.of("/path/to/project"))
```

**중요**: 경로가 올바르게 해석되도록 파일 작업에는 항상 설정하세요.

### cliPath()

Claude Code CLI의 사용자 지정 경로입니다.

```java
.cliPath(Path.of("/custom/path/to/claude"))
```

기본값: 시스템 PATH에서 찾습니다.

**Windows**: `.bat`/`.cmd` 경로(npm의 `claude.cmd` 심)는 거부됩니다 — OS가 이를 `cmd.exe`로 실행하고, `cmd.exe`가 명령줄을 다시 파싱하기 때문입니다. `claude.exe`를 가리키거나 아래의 `allowUnsafeWindowsBatchCli()`를 참고하세요.

### allowUnsafeWindowsBatchCli()

네이티브 `claude.exe`로 옮기기 어려운 배포 환경을 위해 Windows 배치 스크립트 거부를 면제합니다. 기본값은 `false`입니다.

```java
.cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
.allowUnsafeWindowsBatchCli(true)
```

이것은 단순한 우회가 **아닙니다** — 아무 조건 없는 면제라면 `cmd.exe` 재파싱 구멍이 그대로 되살아납니다. 이 옵션을 켜면 추가로 다음이 적용됩니다.

1. **JVM에 `-Djdk.lang.Process.allowAmbiguousCommands=false`가 필요합니다.** 이 속성의 기본값은 `true`이며, 그 상태에서는 JDK가 배치 실행 시 공백 주위만 따옴표로 감쌉니다. `false`로 두면 `" < > & | ^`를 따옴표로 감싸고 따옴표가 포함된 인자를 거부합니다. 이 플래그가 없으면 `connect()`가 `CLIConnectionException`을 던집니다.
2. **모든 CLI 인자에서 `& | < > ^ % ! "`와 CR/LF를 거부**하고, 문제가 된 옵션을 알려 주는 `IllegalArgumentException`을 던집니다. `%`와 `!`는 JDK의 이스케이프 대상에 없고, 따옴표로 감싸도 `%VAR%` 확장을 막지 못합니다.
3. **감수한 위험을 알리는 `WARNING`을 기록**합니다.

**남는 위험**: cmd.exe는 여전히 환경에서 `%VAR%`를 확장합니다. CLI 경로와 모든 인자 값이 관리자 통제 아래에 있는 곳에서만 사용하세요. POSIX에서는 무시됩니다. [전송 계층 → 배치 CLI 옵트인](./feature-transport-layer.md#windows-배치-cli-옵트인-0122)을 참고하세요.

### settings()

추가 설정 JSON 파일의 경로, 또는 인라인 JSON 문자열입니다.

```java
.settings("/path/to/settings.json")
.settings("{\"permissions\": {\"allow\": [\"Read\"]}}")
```

`sandbox()`가 없으면 값은 `--settings`에 그대로 전달됩니다. `sandbox()`도 설정되어 있으면 두 값이
하나의 JSON 문자열로 병합됩니다: `{`로 시작하고 `}`로 끝나는 값은 JSON으로 파싱되고, 그 밖의 값은
파일 경로로 읽힙니다(파일이 없거나 읽을 수 없으면 로그를 남기고 샌드박스 설정만 전달합니다). 이 설정은
CLI의 "플래그 설정(flag settings)" 계층으로 로드되며, 사용자가 제어하는 설정 중 우선순위가 가장
높습니다.

### addDirs()

컨텍스트에 추가할 디렉터리입니다.

```java
.addDirs(List.of(
    Path.of("/path/to/lib"),
    Path.of("/path/to/docs")
))
```

## 환경 변수

### env()

CLI 프로세스의 환경 변수를 설정합니다.

```java
.env(Map.of(
    "API_KEY", "secret-key",
    "DEBUG", "true",
    "NODE_ENV", "production"
))
```

이 Map은 부모 프로세스의 환경 위에 병합됩니다: 여기 지정한 항목이 상속된 값을 재정의하고,
상속된 집합에서 `CLAUDECODE`는 제거됩니다. 전송 계층은 다음 변수도 설정합니다:

| 변수 | 설정 시점 | 여기서 재정의 가능 |
|----------|------|------------------|
| `CLAUDE_CODE_ENTRYPOINT=sdk-java` | 항상 | 예 |
| `CLAUDE_AGENT_SDK_VERSION` | 항상 | 아니요 |
| `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` | 이 Map이나 상속된 환경에 (대소문자와 무관하게) 이미 이름이 있지 않은 경우 | 예 |
| `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` | `enableFileCheckpointing(true)` | — |
| `PWD` | `cwd(...)`가 설정된 경우 | — |
| `TRACEPARENT` / `TRACESTATE` | OpenTelemetry 스팬이 활성화된 경우([트레이스 컨텍스트](./feature-trace-context.md) 참고) | 예 |

여기서 설정하는 두 변수는 일회성 질의가 훅과 SDK MCP 호출을 위해 stdin을 얼마나 오래 열어 두는지를
조정합니다([아키텍처 → stdin 수명 주기](./architecture.md#stdin-수명-주기와-실행의-끝) 참고):
`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`는 턴 사이의 대기 시간 상한을 정하고(기본값 `600000`,
`0`이면 제한 없음), `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`은 CLI의 `session_state_changed`
프레임을 받도록 옵트인합니다. User-Agent 헤더에서 애플리케이션을 식별하려면
`CLAUDE_AGENT_SDK_CLIENT_APP`(예: `"my-app/1.0.0"`)을 설정하세요.

### extraArgs()

임의의 CLI 플래그를 전달합니다.

```java
.extraArgs(Map.of(
    "replay-user-messages", "",   // flag with no value: --replay-user-messages
    "debug", "api"                // flag with a value: --debug api
))
```

키는 앞의 `--`를 **뺀** 플래그 이름이며, `--`는 전송 계층이 붙입니다. 값이 `null`이거나 비어
있으면 값 없는 플래그로 내보냅니다. `-`로 시작하는 값은 별도의 플래그로 파싱되지 않도록
`--flag=value` 형태로 보내고, 그 밖의 값은 두 개의 토큰으로 보냅니다.

## 콜백

### canUseTool()

도구에 대한 사용자 정의 권한 콜백입니다. `permissionPromptToolName`과 함께 쓸 수 없습니다.

```java
.canUseTool((toolName, input, context) -> {
    // Check permission
    if (isAllowed(toolName)) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Tool not allowed")
        );
    }
})
```

**시그니처**:
```java
BiFunction<String, Object, ToolPermissionContext, CompletableFuture<PermissionResult>>
```

### stderrCallback()

CLI의 stderr 출력을 받습니다. CLI가 내보내는 stderr 한 줄마다 한 번씩 호출됩니다(이 콜백을 설정했을 때만 파이프됩니다).

```java
.stderrCallback(line -> {
    System.err.println("CLI stderr: " + line);
})
```

**예외 격리**: 콜백이 예외를 던지더라도 예외는 잡혀서 `FINE` 수준으로 기록되고(`java.util.logging`), stderr 읽기는 계속됩니다. 버그가 있는 콜백이 조용히 읽기 루프를 끝내고 이후 세션 내내 stderr 줄을 전부 잃게 만드는 일은 더 이상 없습니다.

## 훅

### hooks()

수명 주기 이벤트에 훅 콜백을 등록합니다.

```java
.hooks(Map.of(
    HookEvent.PRE_TOOL_USE, List.of(
        new HookMatcher(null, "Read", (context) -> {
            System.out.println("About to read file");
            return CompletableFuture.completedFuture(
                HookOutput.empty()
            );
        })
    )
))
```

**사용 가능한 이벤트**:
- `PRE_TOOL_USE` — 도구 실행 전
- `POST_TOOL_USE` — 도구 성공 후
- `POST_TOOL_USE_FAILURE` — 도구 실패 후
- `USER_PROMPT_SUBMIT` — 사용자가 메시지를 보냄
- `STOP` — 세션 중지
- `SUBAGENT_START` — 서브에이전트 시작
- `SUBAGENT_STOP` — 서브에이전트 중지
- `PRE_COMPACT` — 메시지 압축 전
- `NOTIFICATION` — 알림 이벤트
- `PERMISSION_REQUEST` — 권한 요청

## 고급 기능

### user()

추적용 사용자 식별자를 설정합니다.

```java
.user("user-12345")
```

### includePartialMessages()

부분 메시지 스트리밍을 켭니다.

```java
.includePartialMessages(true)
```

콘텐츠가 생성되는 동안 델타가 담긴 `StreamEvent` 메시지를 API 스트림 이벤트마다 하나씩 받습니다.

### verbatimPrompts()

모든 프롬프트를 작성된 그대로 Claude에 전달합니다.

```java
.verbatimPrompts(true)
```

Claude Code는 보통 프롬프트 텍스트 안의 `@/absolute/path`를 그 파일의 내용으로 확장하며, 이는 작업
디렉터리 바깥이라도 도구 호출 없이 일어납니다. 또한 맨 앞의 `/command`는 슬래시 명령으로
디스패치합니다. 사용자가 직접 입력한 텍스트에는 알맞은 동작이지만, 애플리케이션이 다른 곳(이전 턴,
도구 결과, 서드파티 콘텐츠)에서 조립한 텍스트에는 맞지 않습니다. 이 옵션을 켜면 SDK가 쓰는 모든
사용자 메시지에 `client_composed: true` 표시가 붙고, Claude Code는 이를 주어진 그대로 전달합니다.
`ClaudeSDK.query`의 문자열 프롬프트와 스트리밍 프롬프트, 그리고 `ClaudeSDKClient.connect(String)`,
`query(String)`, `query(Iterator)`가 모두 해당됩니다.

- 표시는 사본에 붙습니다. 여러분의 메시지 Map은 절대 수정되지 않습니다.
- 옵션이 켜져 있는 동안에는 스트리밍 메시지의 `client_composed` 값을 모두 덮어씁니다. 턴 단위로
  제어하려면 옵션을 끈 채로 개별 스트리밍 메시지에 `"client_composed": true`를 넣으세요. SDK는
  이를 손대지 않고 그대로 전달합니다.
- 현재 Claude Code 버전에서는 verbatim 턴이 턴 시작 시의 첨부 단계도 건너뜁니다:
  `@server:resource` MCP 멘션은 확장되지 않고, 보통 함께 첨부되는 컨텍스트(중첩된 `CLAUDE.md`와
  규칙 파일, 스킬 및 도구 목록, 그 밖의 턴별 알림) 없이 프롬프트가 전송됩니다. 그 컨텍스트의 대부분은
  대신 턴의 첫 도구 호출 이후에 도착합니다.
- Claude Code 2.1.248 이상이 필요합니다. 더 오래된 버전은 이 필드를 무시하며, 전송 계층은 연결 시점에
  그런 버전을 감지하면 `WARNING`을 기록합니다.

`examples/VerbatimPromptsExample.java`를 참고하세요.

### forwardSubagentText()

서브에이전트의 텍스트 블록과 사고 블록을 메시지 스트림으로 전달합니다.

```java
.forwardSubagentText(true)
```

기본적으로는 서브에이전트의 `tool_use` / `tool_result` 블록만 상위 스트림에 도달하며,
`parentToolUseId`가 그 서브에이전트를 만든 Agent `tool_use` 블록의 id인
`AssistantMessage` / `UserMessage` 객체 형태로 전달됩니다. 진행 상황을 알리기에는 충분하지만
서브에이전트가 무슨 말을 했는지 보여주기에는 부족합니다. 이 옵션을 켜면 텍스트와 사고 블록도
같은 방식으로 도착합니다.

플래그가 아니라 `initialize` 제어 요청으로 CLI에 전달되며, 켜져 있을 때만 보냅니다.
오래된 CLI는 무시합니다.
[Agents → 서브에이전트 출력 관찰하기](./feature-agents.md#서브에이전트-출력-관찰하기)를 참고하세요.

### agents()

사용자 정의 에이전트 구성을 정의합니다.

```java
.agents(Map.of(
    "my-agent", new AgentDefinition(
        "Custom agent",
        "claude-sonnet-4-5",
        List.of("Read", "Write"),
        "You are a specialized agent"
    )
))
```

### settingSources()

어떤 설정 파일을 읽을지 제어합니다.

```java
.settingSources(List.of(
    SettingSource.USER,     // ~/.claude/
    SettingSource.PROJECT,  // .claude/ in project
    SettingSource.LOCAL     // .claude.local/
))
```

**빈 목록은 모든 소스를 끕니다.** `List.of()`를 넘기면 CLI에 `--setting-sources=`(빈 값)가 전달되어 파일 시스템의 모든 설정 소스가 억제됩니다. 옵션을 **완전히 생략하면**(기본값) `--setting-sources` 플래그 자체가 붙지 않고 CLI가 자체 기본값을 적용합니다.

### skills() / skillsAll()

메인 세션을 위한 최상위 스킬 허용 목록입니다. SDK는 해당하는 `Skill(name)` 항목을 `allowedTools`에 자동으로 주입하고 `settingSources`를 user/project로 기본 설정하므로, CLI가 별도 설정 없이 설치된 스킬을 찾아냅니다. 이 목록은 initialize 제어 요청으로도 전달되어, 이를 지원하는 CLI가 시스템 프롬프트에 어떤 스킬을 올릴지 걸러낼 수 있습니다(오래된 CLI는 이 필드를 무시합니다).

```java
// Enable every discovered skill
.skillsAll()

// Enable only the listed skills
.skills(List.of("commit", "review"))

// Suppress every skill from the listing
.skills(List.of())
```

세 가지 모드:

| 빌더 호출 | `allowedTools` 주입 | `settingSources` 기본값 | initialize 전송 필드 |
|---|---|---|---|
| _생략_(null) | 없음 | 없음 | 생략 |
| `.skillsAll()` | 맨 `Skill` 추가 | `[user, project]` | 생략 |
| `.skills(List.of("a", "b"))` | `Skill(a)`, `Skill(b)` 추가 | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | 없음 | `[user, project]` | `[]` |

동작 세부 사항:
- **멱등한 주입** — `allowedTools`에 이미 `Skill`이나 `Skill(name)`이 있으면 SDK는 중복해서 넣지 않습니다.
- **원본을 바꾸지 않음** — 스킬 기본값을 적용할 때 새 목록을 만듭니다. 원래의 `ClaudeAgentOptions`는 수정되지 않습니다.
- **명시적 `settingSources`가 우선** — `.skills(...)`와 함께 `.settingSources(...)`를 설정했다면 그 값이 유지됩니다.
- **이름을 검증합니다**(0.1.22) — 나열한 이름은 스킬 SKILL.md의 `name`이나 디렉터리 이름, 또는 `plugin:skill`이어야 합니다. 규칙 구분자(괄호, 쉼표), 제어 문자, 와일드카드(`"*"`, `"pdf:*"`), 앞의 `/`, 앞뒤 공백은 `connect()`에서 `IllegalArgumentException`을 발생시킵니다. **호환성 깨짐**: `skills(List.of("*"))`와 `skills(List.of("plugin:*"))`은 예전에는 와일드카드 규칙을 만들었지만 이제 예외를 던집니다 — `.skillsAll()`을 사용하세요. [Skills → 이름 검증](./feature-skills.md#이름-검증-0122)을 참고하세요.
- **샌드박스가 아니라 컨텍스트 필터** — 나열되지 않은 스킬은 모델의 목록에서 숨겨지고 `Skill` 도구로 호출할 수 없지만, 파일은 디스크에 그대로 있습니다. `Read`/`Bash`가 있는 세션은 `.claude/skills/**`에 직접 접근할 수 있습니다.

### sandbox()

bash 명령의 샌드박싱을 설정합니다.

활성화하면 명령이 파일 시스템과 네트워크 접근이 제한된 샌드박스 환경에서 실행됩니다. 도구 수준의
파일 시스템 및 네트워크 제한은 여전히 권한 규칙으로 설정합니다(파일 시스템은 `Read`/`Edit`, 네트워크는
`WebFetch`). 아래의 `network` 설정은 샌드박스 안에서 실행되는 bash 명령에 대한 샌드박스 자체의 네트워크
격리를 구성합니다. 샌드박스를 설정하면 `settings()`가 전달되는 방식도 달라집니다.
[settings()](#settings)를 참고하세요.

```java
// Minimal: just enable sandboxing.
.sandbox(new SandboxSettings(true))
```

더 세밀하게 제어하려면 전체 레코드를 넘기세요.

```java
SandboxNetworkConfig network = new SandboxNetworkConfig(
    List.of("api.example.com", "*.npmjs.org"),  // allowedDomains
    List.of("malicious.example.com"),           // deniedDomains (always blocked)
    /* allowManagedDomainsOnly */ false,
    List.of("/tmp/ssh-agent.sock"),             // allowUnixSockets
    /* allowAllUnixSockets */ false,
    /* allowLocalBinding */ true,
    List.of("com.apple.PowerManagement.control"),  // allowMachLookup (macOS only)
    /* httpProxyPort */ null,
    /* socksProxyPort */ null);

SandboxSettings sandbox = new SandboxSettings(
    /* enabled */ true,
    /* autoAllowBashIfSandboxed */ true,
    /* excludedCommands */ List.of("git"),
    /* allowUnsandboxedCommands */ null,
    network,
    /* ignoreViolations */ null,
    /* enableWeakerNestedSandbox */ false);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sandbox(sandbox)
    .build();
```

`SandboxNetworkConfig` 필드:

- `allowedDomains` — 샌드박스 프로세스가 접근할 수 있는 도메인.
- `deniedDomains` — 항상 차단되는 재정의. 차단이 허용보다 우선합니다.
- `allowManagedDomainsOnly` — 관리 설정에서 `true`이면 관리 설정의 `allowedDomains`만 적용됩니다.
- `allowMachLookup` — macOS 전용 XPC/Mach 서비스 이름. 끝에 오는 와일드카드를 지원합니다.
- `allowUnixSockets`, `allowAllUnixSockets`, `allowLocalBinding`, `httpProxyPort`, `socksProxyPort` — 기존 필드.

도메인 허용 목록이나 Mach 조회 필드가 필요 없는 호출자를 위해 하위 호환 5인자 생성자 `(allowUnixSockets, allowAllUnixSockets, allowLocalBinding, httpProxyPort, socksProxyPort)`가 유지됩니다. 나머지 필드는 `null`이 됩니다.

### plugins()

로컬 디렉터리에서 Claude Code 플러그인을 로드합니다.

```java
.plugins(List.of(
    ClaudeAgentOptions.SdkPluginConfig.local("/path/to/my-plugin")
))
```

각 `local` 플러그인은 `--plugin-dir <path>`가 됩니다. [플러그인 시스템](./feature-plugin-system.md)을 참고하세요.

### outputFormat()

구조화된 출력 형식(Messages API 스타일)입니다.

```java
.outputFormat(Map.of(
    "type", "json_schema",
    "schema", Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string"),
            "age", Map.of("type", "integer")
        ),
        "required", List.of("name")
    )
))
```

### enableFileCheckpointing()

되돌리기를 위한 파일 체크포인트를 켭니다.

```java
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
```

CLI 프로세스에 `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true`를 설정하고
`ClaudeSDKClient.rewindFiles(userMessageId)`를 쓸 수 있게 합니다. 되돌릴 대상인 `uuid`는 사용자
메시지 재생(replay)을 통해 얻습니다. `sessionStore()`와 함께 쓸 수 없습니다.

## 전체 예제

### 예제 1: 읽기 전용 코드 분석

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .cwd(Path.of("/path/to/codebase"))
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(10)
    .maxBudgetUsd(0.50)
    .systemPrompt("You are a code analyzer. Only read and analyze code.")
    .build();
```

### 예제 2: 대화형 개발

```java
var calcServer = ClaudeSDK.createSdkMcpServer("calc", new Calculator());

var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .cwd(Path.of("/project"))
    .allowedTools(List.of(
        "Read", "Write", "Edit", "Grep", "Glob",
        "mcp__calc__add", "mcp__calc__multiply"
    ))
    .mcpServers(Map.of("calc", calcServer))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(50)
    .enableFileCheckpointing(true)
    .systemPrompt("""
        You are a development assistant.
        - Write clean, tested code
        - Follow project conventions
        - Ask before major changes
        """)
    .build();
```

### 예제 3: 예산을 고려한 일괄 처리

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Cheapest model
    .maxTurns(1)                // Single turn only
    .maxBudgetUsd(0.10)         // 10 cent limit
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .systemPrompt("Be extremely concise.")
    .build();

for (String item : batchItems) {
    String result = ClaudeSDK.queryForText(item, options);
    processResult(result);
}
```

### 예제 4: 훅이 달린 사용자 정의 도구

```java
var tools = new MyCustomTools();
var server = ClaudeSDK.createSdkMcpServer("tools", tools);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__process"))
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Processing: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Started processing"))
                );
            })
        ),
        HookEvent.POST_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Completed processing");
                return CompletableFuture.completedFuture(
                    HookOutput.empty()
                );
            })
        )
    ))
    .build();
```

### 예제 5: 세션 재개

```java
// First session
var options1 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
    // Note session ID from messages
}

// Resume later with context
var options2 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more about lambdas");
    // Has context from previous session
}
```

### 예제 6: 권한 콜백과 함께 스트리밍하기

```java
var options = ClaudeAgentOptions.builder()
    .canUseTool((toolName, input, context) -> {
        // Custom permission logic
        boolean allowed = checkPermission(toolName, context.path());

        if (allowed) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("Access denied to " + context.path())
            );
        }
    })
    .build();

// Must use streaming mode
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Read sensitive.txt"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

## 모범 사례

### 1. 파일 작업에는 항상 작업 디렉터리를 설정하세요

```java
// ✅ Good
.cwd(Path.of("/project/root"))

// ❌ Bad: Undefined behavior
// No cwd set, files relative to CLI process directory
```

### 2. 알맞은 모델을 쓰세요

```java
// ✅ Good: Match model to task
.model("claude-haiku-4-5")  // Simple tasks
.model("claude-sonnet-5") // Balanced
.model("claude-opus-4-6")   // Complex reasoning

// ❌ Bad: Always using most expensive
.model("claude-opus-4-6")  // For everything!
```

### 3. 예산 한도를 설정하세요

```java
// ✅ Good: Protect against unexpected costs
.maxBudgetUsd(1.0)
.maxTurns(10)

// ❌ Bad: No limits
// Could get expensive!
```

### 4. 도구를 적절히 설정하세요

```java
// ✅ Good: Explicit tool control
.allowedTools(List.of("Read", "Grep"))
.disallowedTools(List.of("Bash"))

// ❌ Bad: All tools allowed by default
// Potential security risk
```

### 5. 시스템 프롬프트를 사용하세요

```java
// ✅ Good: Guide behavior
.systemPrompt("You are a code reviewer. Focus on security.")

// ❌ Bad: No guidance
// Claude may not understand context
```

### 6. 파일 작업에는 체크포인트를 켜세요

```java
// ✅ Good: Enable for safety
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))

// Allows rewinding if mistakes: pass the uuid of a replayed UserMessage
client.rewindFiles(userMessageUuid);
```

### 7. 민감한 데이터를 조심해서 다루세요

```java
// ✅ Good: Don't pass secrets in env
.env(Map.of("CONFIG_PATH", "/path/to/config"))

// ❌ Bad: Secrets in environment
.env(Map.of("API_KEY", "secret-123"))  // Logged!
```

## 함께 보기

- [간단한 쿼리](./feature-simple-queries.md) — 쿼리에서 옵션 사용하기
- [대화형 세션](./feature-interactive-conversations.md) — 클라이언트에서 옵션 사용하기
- [MCP 서버](./feature-mcp-servers.md) — MCP 서버 설정
- [훅](./feature-hooks.md) — 훅 설정
- [권한](./feature-permissions.md) — 권한 시스템
