# ClaudeAgentOptions API 레퍼런스

구성 옵션 빌더입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../api-claude-agent-options.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 클래스 개요

```java
public final class ClaudeAgentOptions
```

빌더 패턴을 사용하는 불변 구성 객체입니다.

## 옵션 만들기

```java
// Builder
ClaudeAgentOptions.builder()
    .option1(value)
    .build()

// Defaults
ClaudeAgentOptions.defaults()

// From existing
options.toBuilder()
    .modifiedOption(newValue)
    .build()
```

## 모든 구성 옵션

### 도구 구성
- `tools(Object)` — 도구 목록 또는 프리셋
- `allowedTools(List<String>)` — 허용 목록
- `disallowedTools(List<String>)` — 차단 목록

### 시스템 프롬프트
- `systemPrompt(String)` — 사용자 정의 시스템 프롬프트(`--system-prompt`)
- `systemPrompt(SystemPromptPreset)` — Claude Code 프리셋. 선택적으로 `append`, `excludeDynamicSections`, `snapshot`을 함께 지정할 수 있습니다
- `systemPrompt(SystemPromptCustom)` — `snapshot`도 설정할 수 있는 사용자 정의 프롬프트. `SystemPromptCustom.of(prompt, snapshot)`
- `systemPrompt(SystemPromptFile)` — 파일에서 불러온 프롬프트(`--system-prompt-file`)

`snapshot`(프리셋과 사용자 정의 형식에만 해당)은 세션이 첫 요청에서 기록한 프롬프트를 유지할지(`true`), 매 요청마다 다시 구성할지(`false`)를 제어합니다. `initialize` 요청에 `systemPromptSnapshot`으로 전송되며, `null`이면 생략됩니다. Claude Code 2.1.257 이상이 필요합니다. [구성 옵션 → 스냅샷](./feature-configuration-options.md#스냅샷)을 참고하세요.

### MCP 서버
- `mcpServers(Map<String, McpServerConfig>)` — 서버 구성. `--mcp-config`용으로 `{"mcpServers": {...}}` 형태로 직렬화됩니다
- `mcpServers(Path)` / `mcpServersPath(Path)` — MCP 구성 파일. `--mcp-config`에 그대로 전달됩니다
- `mcpServersJson(String)` — 인라인 MCP 구성 JSON. `--mcp-config`에 그대로 전달됩니다
- `strictMcpConfig(boolean)` — `true`이면 CLI는 프로젝트의 `.mcp.json`, 사용자/전역 설정, 플러그인이 제공하는 MCP 서버를 무시하고 `mcpServers(...)`로 전달된 서버만 로드합니다. `--strict-mcp-config`에 대응합니다.

### 권한
- `permissionMode(PermissionMode)` — 권한 모드
- `permissionPromptToolName(String)` — 프롬프트에 사용할 도구
- `canUseTool(CanUseTool)` — 사용자 정의 콜백. **`"ask"` 결정에서만 발동합니다** — `allowedTools`, `permissionMode`, `permissions.allow` 규칙으로 이미 허용된 도구 호출에서는 발동하지 않습니다. 결정과 무관하게 모든 호출을 통제하려면 `PreToolUse` 훅을 사용하세요. 이 콜백이 도구 전체를 허용하는 `allowedTools` 항목이나 `BYPASS_PERMISSIONS`에 의해 눈에 띄게 가려지면, SDK는 연결 시점에 참고용 `WARNING`을 기록합니다. [섀도잉 경고](feature-permissions.md#섀도잉-경고)를 참고하세요.

### 세션
- `continueConversation(boolean)` — 마지막 세션 이어가기
- `resume(String)` — 특정 세션 재개
- `forkSession(boolean)` — 재개한 세션을 포크
- `resumeSessionAt(String)` — 잘라내기 재개: 재개하는 대화를 이 트랜스크립트 항목 UUID까지만(해당 항목 포함) 불러와 그보다 이른 지점에서 분기합니다. `resume`과 함께, 보통 `forkSession`과도 함께 사용합니다. 어떤 트랜스크립트 항목 UUID든 받을 수 있습니다 — 보통은 실시간으로 관찰한 `AssistantMessage.uuid()`이거나 `ClaudeSDK.getSessionMessages(...)`에서 얻은 `SessionMessage.uuid()`입니다. `--resume-session-at=<value>`로 전달됩니다. [잘라내기 재개](./feature-session-history.md#잘라내기-재개)를 참고하세요.
- `resumeDropsTurn(String)` — `resumeSessionAt`과 함께: 이번 잘라내기로 버리려는 턴의 사용자 프롬프트 UUID입니다. 그러면 CLI는 불러오는 시점에 분기점 이후의 *모든* 항목이 그 턴에 속하는지 검증하고, 아니면 거부합니다 — 세션이 턴 도중에 흡수한 대기 중인 사용자 메시지나 작업 알림이 조용히 사라지는 일은 결코 없습니다. 거부는 메시지에 `Resume rejected by --resume-drops-turn:`가 들어 있는 예외로 나타납니다. 이는 결정론적인 결과이므로 재시도하지 말고 그냥 평범하게 재개하세요. null이 아니면 항상 전달되므로 빈 문자열도 CLI에 도달해 잘못된 형식으로 거부되며, 보호 장치가 조용히 무력화되지 않습니다. `--resume-drops-turn=<value>`로 전달됩니다.
- `sessionStore(SessionStore)` — 트랜스크립트를 외부 저장소로 미러링하고 거기서 재개합니다([Session Store](./feature-session-store.md) 참고). 설정하면 SDK가 CLI에 `--session-mirror`를 전달하고 `transcript_mirror` 프레임을 `store.appendAsync(...)`로 보냅니다. 사전 검증은 `listSessions()`를 지원하지 않는 상태의 `continueConversation + sessionStore`와 `sessionStore + enableFileCheckpointing`를 거부합니다.
- `sessionStoreFlush(SessionStoreFlushMode)` — 트랜스크립트 미러 항목을 `sessionStore`로 플러시하는 시점입니다. `BATCHED`(기본)는 항목을 모아 턴마다 또는 버퍼가 500개 항목 / 1 MiB를 넘을 때 한 번 플러시합니다. `EAGER`는 프레임마다 백그라운드 플러시를 예약해 거의 실시간으로 전달합니다. `sessionStore`가 설정되지 않으면 무시됩니다. [플러시 모드](./feature-session-store.md#플러시-모드-batched-대-eager)를 참고하세요.
- `loadTimeoutMs(long)` — 재개를 구체화하는 동안 `store.loadAsync()` / `listSubkeysAsync()` 호출마다 적용되는 타임아웃(밀리초, 기본 `60_000`). `0`은 즉시 타임아웃을 뜻하고, 아주 큰 값은 사실상 비활성화합니다.

### 한도
- `maxTurns(Integer)` — 최대 대화 턴 수
- `maxBudgetUsd(Double)` — 최대 비용(USD)
- `maxBufferSize(Integer)` — stdout 버퍼 최대 바이트 수
- `thinking(ThinkingConfig)` — 확장 사고 구성
- `effort(String)` — 사고 깊이 수준(`"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`). `"xhigh"`는 Opus 4.7 전용이며 다른 모델에서는 `"high"`로 대체됩니다.
- `effort(EffortLevel)` — 위와 같지만 [`EffortLevel`](feature-configuration-options.md#effortlevel-열거형) 열거형(`LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`)을 사용하는 타입 안전한 형태입니다. `null`을 넘기면 해제됩니다.
- `maxThinkingTokens(Integer)` — **더 이상 권장되지 않음**. `thinking()`을 사용하세요. 최신 모델에서는 이 값이 켜기/끄기로만 취급됩니다(0 = 비활성화, 그 밖의 값 = adaptive)
- `maxMsgQSize(Integer)` — 메시지 큐 최대 크기

### 모델
- `model(String)` — AI 모델 이름
- `fallbackModel(String)` — 대체 모델
- `betas(List<SdkBeta>)` — 베타 기능

### 환경
- `cwd(Path)` — 작업 디렉터리
- `cliPath(Path)` — 사용자 지정 CLI 경로 (Windows의 `.bat`/`.cmd` 경로는 거부됩니다. 아래 참고)
- `allowUnsafeWindowsBatchCli(boolean)` — Windows 배치 스크립트 거부를 해제합니다. 아울러 `-Djdk.lang.Process.allowAmbiguousCommands=false`가 필요하며, 모든 인자에서 cmd.exe 메타문자를 거부합니다(기본 `false`)
- `settings(String)` — 설정 파일 경로 또는 인라인 JSON 문자열. `--settings`에 그대로 전달되며, 샌드박스가 설정된 경우에는 `sandbox`와 병합되어 하나의 JSON 문자열이 됩니다
- `addDirs(List<Path>)` — 추가 컨텍스트 디렉터리
- `env(Map<String, String>)` — 환경 변수. 상속된 환경 위에 병합됩니다. SDK는 또한 `CLAUDE_CODE_ENTRYPOINT`(재정의 가능), `CLAUDE_AGENT_SDK_VERSION`을 설정하고, 이미 지정되어 있지 않으면 `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`도 설정합니다. [env()](./feature-configuration-options.md#env)를 참고하세요
- `extraArgs(Map<String, String>)` — 추가 CLI 플래그. 키에는 앞의 `--`를 붙이지 않으며, 값 없는 플래그는 빈 값으로 지정합니다

### 콜백
- `stderrCallback(Consumer<String>)` — stderr 콜백

### 훅
- `hooks(Map<HookEvent, List<HookMatcher>>)` — 훅 콜백
- `includeHookEvents(boolean)` — `true`이면 CLI가 훅 수명 주기 이벤트(`PreToolUse`, `PostToolUse`, `Stop` 등)를 `HookEventMessage` 객체로 메시지 스트림에 흘려보냅니다. `--include-hook-events`에 대응합니다. [훅 → 스트림의 훅 수명 주기 이벤트](./feature-hooks.md#스트림의-훅-수명-주기-이벤트)를 참고하세요.

### 고급
- `user(String)` — 사용자 식별자
- `includePartialMessages(boolean)` — API 스트림 이벤트마다 `StreamEvent`를 하나씩 내보냅니다(`--include-partial-messages`)
- `verbatimPrompts(boolean)` — SDK가 보내는 모든 사용자 메시지에 `client_composed` 표시를 붙여, Claude Code가 작성된 그대로 전달하게 합니다. `@path` 확장도, 슬래시 명령 디스패치도 일어나지 않습니다. 켜져 있는 동안에는 메시지별 값을 덮어씁니다. Claude Code 2.1.248 이상이 필요합니다(더 오래된 CLI에서는 SDK가 경고를 남깁니다). [verbatimPrompts()](./feature-configuration-options.md#verbatimprompts)를 참고하세요
- `forwardSubagentText(boolean)` — `true`이면 서브에이전트의 텍스트 블록과 사고 블록이, 항상 전달되는 `tool_use` / `tool_result` 블록과 함께 메시지 스트림으로 전달됩니다. `initialize` 제어 요청으로 전송되며 CLI 플래그는 없습니다. [에이전트 → 서브에이전트 출력 관찰하기](./feature-agents.md#서브에이전트-출력-관찰하기)를 참고하세요.
- `agents(Map<String, AgentDefinition>)` — 사용자 정의 에이전트
- `settingSources(List<SettingSource>)` — 설정 출처(빈 목록은 `--setting-sources=`로 모든 출처를 비활성화하고, 생략하면 CLI 기본값을 유지합니다)
- `skills(List<String>)` — Skills 허용 목록(`allowedTools`에 `Skill(name)`을 자동 주입하고 `settingSources`를 user/project로 기본 설정합니다). 이름은 정확히 일치해야 하며, 와일드카드·규칙 구분자·앞뒤 공백은 `connect()`에서 `IllegalArgumentException`을 던집니다
- `skillsAll()` — 발견된 모든 skill을 활성화합니다(맨 `Skill` 도구를 자동 주입)
- `sandbox(SandboxSettings)` — bash 명령용 샌드박스 구성. `network` 키는 샌드박스 자체의 네트워크 격리를 구성합니다(도구 수준의 제한은 권한 규칙에 남습니다)
- `plugins(List<SdkPluginConfig>)` — 로컬 플러그인 디렉터리. `SdkPluginConfig.local(path)` → `--plugin-dir`
- `outputFormat(Map<String, Object>)` — 출력 형식
- `enableFileCheckpointing(boolean)` — `rewindFiles()`를 위한 파일 체크포인트 활성화. `sessionStore`와 함께 쓸 수 없습니다

## 관련 항목
- [구성 옵션 가이드](./feature-configuration-options.md)
