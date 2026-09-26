# 아키텍처 개요

이 문서는 Claude Agent SDK for Java의 아키텍처, 설계 패턴, 내부 구조를 두루 설명합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../architecture.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록과 구조도는 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 목차
- [상위 수준 아키텍처](#상위-수준-아키텍처)
- [핵심 구성 요소](#핵심-구성-요소)
- [설계 패턴](#설계-패턴)
- [데이터 흐름](#데이터-흐름)
- [동시성 모델](#동시성-모델)
- [타입 시스템](#타입-시스템)
- [의존성](#의존성)

## 상위 수준 아키텍처

SDK는 관심사를 뚜렷하게 나눈 계층형 아키텍처를 따릅니다:

```
┌─────────────────────────────────────────────────────────┐
│           Public API Layer                              │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   ClaudeSDK      │    │  ClaudeSDKClient       │   │
│  │   (Facade)       │    │  (Interactive Client)  │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                        │                   │
│            └────────────────────────┘                   │
│                     │                                   │
├─────────────────────┼───────────────────────────────────┤
│                     ▼                                   │
│           Configuration Layer                           │
│  ┌─────────────────────────────────────────────────┐  │
│  │       ClaudeAgentOptions (Builder)              │  │
│  └─────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│           Protocol & Control Layer                      │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   QueryHandler   │◄───┤   MessageParser        │   │
│  │  (Control Proto) │    │   (JSON Parsing)       │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           Transport Layer                               │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Transport Interface                            │  │
│  │  └─ SubprocessCLITransport (default impl)      │  │
│  └─────────────────────────────────────────────────┘  │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           External Process                              │
│  ┌─────────────────────────────────────────────────┐  │
│  │         Claude Code CLI Process                 │  │
│  │         (stdin/stdout communication)            │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 핵심 구성 요소

### 1. 공개 API 계층

#### ClaudeSDK (파사드)
- **목적**: 간단하고 무상태인 질의를 위한 정적 파사드
- **사용 사례**: 일회성 질문, 일괄 처리, 보내고 잊는 작업
- **주요 메서드**:
  - `query(String prompt)` — 기본값으로 하는 간단한 질의
  - `query(String prompt, ClaudeAgentOptions options)` — 사용자 옵션이 있는 질의
  - `query(Iterator<Map> stream, ClaudeAgentOptions options)` — 스트리밍 질의
  - `queryForText()` / `queryForResult()` — 편의 메서드
  - `createClient()` — ClaudeSDKClient의 팩토리 메서드
  - `createSdkMcpServer()` — MCP 서버의 팩토리

**설계 패턴**: Facade + Factory

#### ClaudeSDKClient
- **목적**: 멀티턴 대화를 위한 대화형·상태 유지 클라이언트
- **사용 사례**: 채팅 인터페이스, REPL 같은 상호작용, 오래 도는 세션
- **주요 메서드**:
  - `connect()` — 연결 맺기
  - `sendMessage()` / `query()` — 메시지 보내기
  - `receiveMessages()` / `receiveResponse()` — 메시지 받기
  - 제어 메서드: `interrupt()`, `setModel()`, `setPermissionMode()` 등
- **스레드 안전성**: 문서화된 보장 아래 부분적으로 스레드 안전
- **자원 관리**: 올바른 정리를 위해 AutoCloseable 구현

**설계 패턴**: Builder + 자원 관리(try-with-resources)

### 2. 구성 계층

#### ClaudeAgentOptions
- **목적**: 빌더 패턴을 쓰는 불변 구성 객체
- **특징**:
  - 30가지가 넘는 구성 옵션
  - 열거형과 sealed 인터페이스로 타입 안전한 API
  - 수정을 위한 `toBuilder()`를 갖춘 유창한 빌더
- **주요 구성 영역**:
  - 도구: `tools()`, `allowedTools()`, `disallowedTools()`
  - 권한: `permissionMode()`, `canUseTool()`
  - 세션: `continueConversation()`, `resume()`, `forkSession()`, `sessionStore()`, `loadTimeoutMs()`
  - 한도: `maxTurns()`, `maxBudgetUsd()`, `maxThinkingTokens()`
  - 모델: `model()`, `fallbackModel()`, `betas()`
  - 환경: `cwd()`, `env()`, `cliPath()`
  - 훅: `hooks()`
  - MCP: `mcpServers()`
  - 에이전트: `agents()` (stdin의 initialize 요청으로 전송, 크기 제한 없음)
  - 시스템 프롬프트: `systemPrompt()` — 문자열, `SystemPromptPreset`, `SystemPromptCustom` 또는 `SystemPromptFile`
  - 고급: `sandbox()`, `outputFormat()`, `enableFileCheckpointing()`, `forwardSubagentText()`, `verbatimPrompts()`

**설계 패턴**: Builder + 불변 객체

### 3. 프로토콜과 제어 계층

#### QueryHandler
- **목적**: Transport 위에서 양방향 제어 프로토콜을 관리
- **책임**:
  - 제어 요청/응답 라우팅
  - 훅 콜백
  - 도구 권한 콜백
  - 메시지 스트리밍
  - 초기화 핸드셰이크(훅, 에이전트 정의, `excludeDynamicSections`, `systemPromptSnapshot`, skills 허용 목록, `forwardSubagentText` 포함)
  - **프롬프트 표시**: `verbatimPrompts`가 켜져 있으면 `streamInput()`이 쓰는 모든 사용자 메시지에
    `client_composed: true`가 붙습니다(`stampUserMessage`, 원본을 변경하지 않고 복사함). `ClaudeSDKClient`도
    자신이 쓰는 메시지에 같은 방식으로 표시를 붙입니다
  - **실행 종료 추적**: CLI의 `session_state_changed` 프레임, 진행 중 작업 장부, 턴 사이 상한 시간을 바탕으로
    stdin을 닫을 수 있는 시점을 판단하고, SDK가 요청한 `sdk_host_only` 상태 프레임은 걸러 냅니다
    ([stdin 수명 주기](#stdin-수명-주기와-실행의-끝) 참고)
  - MCP 서버 수명 주기 관리
  - **실질적인 오류로 교체**: 스트림을 읽으면서 가장 최근 오류 결과의 페이로드를 추적합니다.
    `is_error=true`인 result 뒤에 `ProcessException`이 오면, 일반적인
    `"Command failed with exit code N"` 대신 그 페이로드와
    `"Claude Code returned an error result: <text>"` 메시지(결과의 `errors` 배열, 다음으로 `result`
    텍스트, 다음으로 `success`가 아닌 `subtype`, 마지막으로 API 오류 상태로 구성)를 담은
    `ResultException`으로 교체됩니다. result도 `session_state_changed`도 아닌 트래픽이 오면 초기화됩니다.
    이 예외 객체는 합성된 `{"type":"error"}` 프레임에 실려 가므로, 소비자 이터레이터가 타입과 페이로드를
    그대로 유지한 채 다시 던집니다.
- **스레드 안전성**: 원자적 연산과 동기화로 완전히 스레드 안전
- **주요 기능**:
  - CompletableFuture를 쓰는 비동기 제어 프로토콜
  - 요청 ID 생성과 추적
  - 크기를 설정할 수 있는 메시지 큐
  - 백그라운드 읽기 스레드(Java 21+에서는 가상 스레드)
  - 비동기 콜백을 위한 제어 실행기

**설계 패턴**: 비동기 요청/응답 + Observer(훅용)

#### MessageParser
- **목적**: CLI의 JSON 메시지를 파싱해 타입이 지정된 Message 객체로 변환
- **특징**:
  - Jackson 기반 JSON 파싱
  - 모든 메시지 타입 지원(user, assistant, system, result, stream_event)
  - 콘텐츠 블록 파싱(text, thinking, tool_use, tool_result)
  - 오류 처리와 검증

**설계 패턴**: Parser + Factory

### 4. 전송 계층

#### Transport 인터페이스
- **목적**: Claude Code와 통신하기 위한 추상 I/O 계층
- **기본 구현**: SubprocessCLITransport
- **사용자 구현**: 원격 Claude Code 연결을 가능하게 함
- **주요 메서드**:
  - `connect()` — 연결 맺기
  - `write(String data)` — 데이터 보내기
  - `readMessages()` — 메시지를 이터레이터로 받기
  - `endInput()` — 입력 스트림 닫기
  - `isReady()` — 연결 상태 확인
  - `close()` — 자원 정리

**설계 패턴**: Strategy + Template Method

#### SubprocessCLITransport
- **목적**: Claude Code CLI를 서브프로세스로 쓰는 기본 전송
- **특징**:
  - CLI 서브프로세스 수명 주기 관리
  - stdin/stdout 통신
  - 한도를 설정할 수 있는 버퍼링 읽기
  - 줄 단위 예외 격리를 갖춘 stderr 콜백 지원(예외를 던지는 사용자 콜백이 더는 읽기 루프를 죽이지 않음)
  - 프로세스 자동 정리
  - **JVM 종료 훅**: 정적 `ConcurrentHashMap.newKeySet()`이 생성된 모든 `Process`를 추적하고, 클래스
    초기화 시 등록된 `Runtime.addShutdownHook`이 살아 있는 각 자식에 `destroy()`를 호출합니다. 덕분에
    부모 JVM이 `close()`보다 먼저 끝나도 떠도는 `claude` 서브프로세스가 새어 나가지 않습니다. Python
    SDK의 `atexit` 핸들러에 대응합니다.
- **구현 세부 사항**:
  - 서브프로세스 관리에 ProcessBuilder 사용
  - stdout 읽기에 전용 스레드(Java 21+에서는 가상 스레드)
  - BufferedReader로 줄 단위 파싱
  - JSON 직렬화/역직렬화에 Jackson

**설계 패턴**: 서브프로세스 관리 + 버퍼링 I/O

### 5. MCP(Model Context Protocol) 지원

#### SdkMcpServer
- **목적**: 사용자 정의 도구를 위한 인프로세스 MCP 서버
- **외부 서버 대비 장점**:
  - IPC 오버헤드 없음(같은 프로세스)
  - 배포가 단순함
  - 디버깅이 쉬움
  - 애플리케이션 상태에 직접 접근
- **특징**:
  - 도구 등록과 실행
  - @Tool 어노테이션에서 스키마 자동 생성
  - CompletableFuture 기반 비동기 실행
  - 서버 정보, 그리고 `2025-06-18` / `2024-11-05` 사이의 `initialize` 버전 협상
  - MCP 프로토콜 메시지(`initialize`, `ping`, `tools/list`, `tools/call`)
  - 각 도구의 `inputSchema`에 대한 인자 검증(서버 생성 시 한 번 컴파일)
  - 취소: `notifications/cancelled`가 보류 중인 호출을 마무리하고 핸들러에 알림
- **메시지 분류**: `id`가 있는 `method`는 요청이고 응답됩니다. `id`가 없는 `method`는 알림이며, JSON-RPC가
  요구하는 대로 *결코* 응답하지 않습니다 — 대신 바깥의 제어 요청이 확인됩니다. `method`가 없는 메시지는
  응답이거나 쓰레기이며 무시됩니다. 이 서버는 CLI에 요청을 보내지 않으므로, 그런 식으로 오는 것은 이
  서버가 짝지을 대상이 아닙니다.
- **실패 분류**: `tools/call`이 마주칠 수 있는 모든 것은 *도구 실행 오류*, 즉 `isError: true`를 담은
  결과입니다 — 알 수 없는 도구, 스키마에 맞지 않는 인자, 예외를 던진 핸들러까지 포함해서요. JSON-RPC
  오류는 *모델*이 결코 일으키지도, 보지도 않는 것에만 남겨 둡니다: 구현되지 않은 메서드(`-32601`),
  잘못된 `params`(`-32602`), CLI가 취소한 호출(`-32800`)입니다. `isError` 결과는 모델이 읽고 고칠 수 있는
  도구 출력으로 전달되지만, JSON-RPC 오류는 그 요청이 아예 처리될 수 없었다는 뜻입니다.
- **실패 시 닫는 검증**: 각 `inputSchema`는 생성 시점에 자신의 방언의 메타 스키마에 대조해 검사되고,
  통과하지 못한 도구는 기록되고 호출 불가가 됩니다. 그러지 않으면 검증기가 잘못된 스키마를 받아들이고
  그것을 기준으로 잘못 검증하게 됩니다 — `{"type": "bogus"}`는 아무것과도 일치하지 않고,
  `"properties": "a string"`은 무시됩니다 — 그 결과 아무도 검사하지 않은 인자로 핸들러가 돌거나, 모든
  호출이 엉뚱한 이유를 대며 실패합니다.

#### McpMessageHandler
- **목적**: `McpSdkServerConfig`가 실제로 담고 있는 이음매로, 애플리케이션이 `SdkMcpServer` 대신 스스로
  MCP를 제공할 수 있게 합니다 — 리소스, 프롬프트, 자동완성, 또는 서드파티 MCP 라이브러리 위의 어댑터.
- **계약**: `handleMessage`는 요청에 대해 JSON-RPC 응답을 반환하고, 답을 기대하지 않는 것에는 `null`을
  반환합니다. `close()`는 "너를 쓰던 연결이 사라진다"는 뜻이지 "멈춰라"가 아닙니다. 하나의 핸들러가 여러
  클라이언트를 섬길 수 있으므로 멱등해야 하고 계속 쓸 수 있어야 합니다.

#### ToolCallContext
- **목적**: 실행 중인 도구가 자신의 호출이 취소되었음을 알 수 있게 합니다.
- **왜 있어야 하는가**: `CompletableFuture.cancel(true)`는 실행 중인 작업을 중단시키지 않습니다 —
  future를 완료시킬 뿐 작업은 계속됩니다. 명시적인 신호가 없으면 취소는 *기다림*만 멈추고 *작업*은
  멈추지 않아, 부작용이 있는 도구는 CLI가 포기한 뒤에도 그 부작용을 계속 적용하게 됩니다.

#### SdkMcpTool
- **목적**: 도구 정의와 실행의 래퍼
- **생성 방법**:
  - `SdkMcpTool.create()` — 프로그래밍 방식 생성
  - `@Tool` 어노테이션 — 선언적 생성
- **특징**:
  - 입력의 제네릭 타입 매개변수
  - CompletableFuture 기반 비동기 실행
  - 입력 검증을 위한 JSON Schema
  - 매개변수 자동 추출

**설계 패턴**: Command + Factory + 어노테이션 처리

### 6. SessionStore 하위 시스템

#### SessionStore (어댑터 프로토콜)
- **목적**: 세션 트랜스크립트를 외부 저장소(S3, Postgres, Redis, 사용자 백엔드)로 미러링해, 세션이 로컬
  디스크를 넘어 오래 남고 호스트를 넘나들며 재개될 수 있게 합니다.
- **필수 메서드**: `append(SessionKey, List<SessionStoreEntry>)`, `load(SessionKey)`.
- **선택 메서드**(`implements*()` 기능 탐지 포함): `listSessions`, `listSessionSummaries`, `delete`,
  `listSubkeys`.
- **동기 + 비동기 API**: 모든 메서드에 `*Async`(`CompletableFuture`) 변형이 있습니다. 네이티브 논블로킹
  클라이언트를 쓰는 어댑터(AWS SDK v2 async, R2DBC, Lettuce reactive)는 `*Async`를 직접 재정의해 스레드
  도약을 피합니다. 기본 실행기는 `SessionStoreExecutor`로 구성합니다(작업마다 스레드 하나, Java 21+에서는
  가상, 그 밖에는 데몬 플랫폼 스레드).

**설계 패턴**: Adapter + 기능 협상 + 이중 API(동기/비동기)

#### TranscriptMirrorBatcher (내부)
- **목적**: CLI가 stdout에 내보내는 `transcript_mirror` 프레임을 버퍼링해
  `store.appendAsync(...)`로 플러시합니다.
- **주요 동작**:
  - 즉시 플러시 임계값: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`.
  - 각 `result` 메시지 전과 스트림 끝/닫기에서 명시적 플러시.
  - `filePath`별로 프레임을 합쳐 플러시마다 고유 파일당 `append` 호출을 한 번으로 만듭니다.
  - 제한된 재시도: `MIRROR_APPEND_MAX_ATTEMPTS=3`회, `[200ms, 800ms]` 백오프. 타임아웃은 재시도하지
    않습니다(진행 중인 호출이 나중에 도착할 수 있음).
  - 경로가 설정된 `projectsDir` 밖에 있는 프레임은 경고와 함께 버려집니다.
  - 실패는 소비자의 스트림에 `MirrorErrorMessage`로 드러납니다 — 대화를 막지 않습니다.

**설계 패턴**: 생산자-소비자 버퍼 + 지수 백오프 재시도

#### SessionResume (내부)
- **목적**: 저장된 세션을 임시 `CLAUDE_CONFIG_DIR`로 구체화해, CLI 서브프로세스가 로컬 디스크에서 재개할
  수 있게 합니다.
- **흐름**:
  1. `store.loadAsync()`로 항목을 불러옵니다(`continueConversation`이면 곁가지가 아닌 가장 최근 수정
     세션을 고릅니다).
  2. `~/.claude/`처럼 배치된 임시 디렉터리에 JSONL을 씁니다.
  3. `.credentials.json`(임시 디렉터리에서 토큰이 소모되지 않도록 `refreshToken` 제거)과
     `.claude.json`을 복사합니다.
  4. 저장소가 `listSubkeys`를 구현하면 서브에이전트 트랜스크립트와 `.meta.json` 사이드카를 구체화합니다.
  5. `CLAUDE_CONFIG_DIR=<temp dir>`로 CLI를 띄웁니다.
  6. 연결이 끊길 때 정리하며, Windows 백신/색인기의 일시적 잠금에는 재시도합니다.

**설계 패턴**: 구체화 뷰 + 재시도 정리

#### SessionStoreValidation (내부)
- **목적**: 서브프로세스 생성 전의 사전 옵션 검사. 잘못된 조합을 `IllegalArgumentException`으로
  거부합니다:
  - `continueConversation + sessionStore`에는 `store.implementsListSessions()`가 필요합니다.
  - `sessionStore + enableFileCheckpointing`은 거부됩니다(체크포인트는 로컬 전용).

**설계 패턴**: 빠른 실패 검증

#### SessionStoreConformance (공개 테스트 보조)
- **위치**: `in.vidyalai.claude.sdk.testing.SessionStoreConformance`
- **목적**: `SessionStore` 어댑터를 위한, 프레임워크에 비종속인 14개 계약 행동 테스트 모음. 평범한
  `AssertionError`를 쓰므로 어떤 테스트 프레임워크에서도 동작합니다(JUnit, TestNG, Spock, 평범한
  `main`).

**설계 패턴**: 계약 테스트

## 설계 패턴

### 1. sealed 인터페이스 (패턴 매칭)
타입 안전한 메시지 처리에 널리 쓰입니다:

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}

// Usage with pattern matching
switch (message) {
    case UserMessage u -> handleUser(u);
    case AssistantMessage a -> handleAssistant(a);
    case ResultMessage r -> handleResult(r);
    case MirrorErrorMessage m -> handleMirrorError(m);
    case HookEventMessage h -> handleHookEvent(h);
    case SystemMessage s -> handleSystem(s);
    case StreamEvent e -> handleStreamEvent(e);
    // ... task and rate-limit cases
}
```

**장점**:
- 컴파일 시점의 완전한 패턴 매칭
- default 케이스가 필요 없음
- 타입 안전성이 보장됨
- 타입 계층이 명확함

### 2. 빌더 패턴
구성 객체에 쓰입니다:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .build();

// Modify existing options
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .build();
```

**장점**:
- 읽기 쉬운 구성
- 선택적 매개변수
- 불변 객체
- 연쇄 가능한 API

### 3. 파사드 패턴
ClaudeSDK가 단순한 인터페이스를 제공합니다:

```java
// Simple facade
List<Message> messages = ClaudeSDK.query("Hello");

// Hides complexity of:
// - Transport creation
// - QueryHandler setup
// - Message parsing
// - Resource cleanup
```

**장점**:
- 흔한 경우의 API가 단순함
- 내부 복잡성을 감춤
- 단일 진입점

### 4. 가상 스레드 (동시성)
런타임이 제공하는 곳에서 Project Loom을 활용해 가벼운 동시성을 얻습니다.

SDK는 Java 17을 대상으로 컴파일되며 거기에는 `Thread.ofVirtual()`이 없으므로, 모든 스레드와 실행기는
`internal.Threads`를 통해 만들어집니다. 이 클래스는 Java 21 진입점을 리플렉션으로 한 번 해석해
`static final` 메서드 핸들에 담고, 없으면 이름 붙은 데몬 플랫폼 스레드로 물러납니다:

```java
// Background reader thread
Thread reader = Threads.start("ClaudeSDK-Reader-", () -> readLoop());

// Executor for control protocol
ExecutorService executor = Threads.newSingleThreadExecutor("ClaudeSDK-Reader-");
```

스레드 이름은 두 경로에서 동일하므로, 런타임과 무관하게 스레드 덤프가 같은 식으로 읽힙니다.
`-Dclaude.sdk.virtualThreads=false`를 설정하면 어떤 JDK에서든 플랫폼 경로를 강제할 수 있습니다.

**Java 21+에서의 장점**:
- 가벼운 스레드(수천 개도 가능)
- 스레드 풀을 고갈시키지 않는 블로킹 I/O
- 더 단순한 비동기 코드
- 더 나은 자원 활용

**Java 17-20에서는**: 같은 코드가 데몬 플랫폼 스레드에서 돕니다. 실행기는 고정 풀이 되는 대신
*무제한*으로 남습니다 — `QueryHandler`의 제어 실행기는 SDK MCP 도구 호출 동안 스레드를 붙잡아 두고, 그것을
끝내는 취소는 별도의 작업으로 도착하므로, 제한된 풀은 교착에 빠집니다. 대가는 진행 중인 제어 요청마다
가상 스레드가 아니라 OS 스레드 하나씩입니다.

### 5. CompletableFuture (비동기 작업)
비동기 콜백과 제어 프로토콜에 쓰입니다:

```java
// Permission callback
CompletableFuture<PermissionResult> future =
    canUseTool.apply(toolName, input, context);

// Control protocol request/response
CompletableFuture<ControlResponse> response =
    sendControlRequest(request);
```

**장점**:
- 논블로킹 작업
- 조합 가능한 비동기 체인
- 오류 처리
- 타임아웃 지원

## 데이터 흐름

### 질의 실행 흐름

```
User Code
    │
    ├─► ClaudeSDK.query(prompt, options)
    │       │
    │       ├─► Validate options
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       │       │
    │       │       ├─► Start reader thread
    │       │       └─► Process messages
    │       │               │
    │       │               ├─► Parse JSON
    │       │               ├─► Handle control protocol
    │       │               ├─► Invoke hooks
    │       │               ├─► Check permissions
    │       │               └─► Add to message queue
    │       │
    │       └─► Collect messages
    │               │
    └─────────────► Return List<Message>
```

### 대화형 클라이언트 흐름

```
User Code
    │
    ├─► ClaudeSDKClient.connect()
    │       │
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       ├─► Start reader thread
    │       └─► Initialize (control protocol handshake)
    │
    ├─► client.sendMessage("Hello")
    │       │
    │       └─► Write to transport stdin
    │
    ├─► client.receiveResponse()
    │       │
    │       └─► Iterator reads from message queue
    │               │
    │               ├─► Blocks until message available
    │               ├─► Returns messages until ResultMessage
    │               └─► Auto-closes iterator
    │
    └─► client.close()
            │
            ├─► Close QueryHandler
            ├─► Close Transport
            └─► Cleanup resources
```

### 훅 호출 흐름

```
CLI Process
    │
    ├─► Sends hook request via stdout
    │       │
    │       └─► {"type": "control", "method": "sdk.hook_callback", ...}
    │
QueryHandler
    │
    ├─► Receives hook request
    │       │
    │       ├─► Parse hook event and input
    │       ├─► Match against registered hooks
    │       ├─► Invoke matching hooks in parallel
    │       │       │
    │       │       └─► CompletableFuture.allOf(...)
    │       │
    │       └─► Collect results
    │               │
    │               └─► Combine outputs (logs, messages, updates)
    │
    └─► Send hook response via stdin
            │
            └─► {"id": "...", "result": {...}}
```

### 제어 요청 실패 처리

들어오는 모든 `control_request`는 각자의 스레드에서 처리되며 `ExecutorService.submit(...)`으로
제출됩니다 — 그 `Future`를 읽는 곳은 없습니다. 그래서 핸들러를 빠져나온 `Throwable`은 예전에는 흔적도 없이
사라졌고, CLI는 짝이 되는 `control_response`를 받을 때까지 막혀 있으므로 실행이 양쪽 어디에도 진단 없이
멈춰 버렸습니다.

그래서 `handleControlRequest`는 `Exception`이 아니라 `Throwable`을 잡습니다. 어떤 실패든 기록되고(일반
예외는 `WARNING`, `Error`는 `SEVERE`) 오류 제어 응답으로 답해 CLI가 하염없이 기다리지 않게 하며, 진짜
`Error`는 삼키지 않고 다시 던집니다. 복구 가능한 `request_id` 없이 도착한 요청은 답할 대상이 없으므로
기록만 할 수 있습니다.

이는 이론적인 이야기가 아닙니다. "unresolved compilation problem"이라는 `Error`를 품은, IDE가 컴파일한
낡은 클래스 하나 때문에, 이 잡는 범위를 넓히기 전까지 모든 SDK MCP 제어 요청이 조용히 멈춰 있었습니다.

### stdin 수명 주기와 실행의 끝

> **`controlExecutor`는 작업마다 스레드여야 합니다.** SDK MCP 도구 호출은 도구가 답할 때까지 제어
> 스레드를 붙잡고, 그것을 끝내는 `notifications/cancelled`는 *별개의* 제어 요청으로 도착합니다. 어떤
> 제한된 풀에서든 그 취소는 자신이 취소해야 할 바로 그 호출 뒤에 줄을 서게 되어 교착에 빠집니다. 어떤
> 테스트도 잡아내지 못합니다 — 크기 2짜리 고정 풀은 모든 테스트를 통과합니다.

훅, SDK MCP 서버, `canUseTool` 권한 콜백이 등록되어 있으면 제어 프로토콜은 CLI가 다시 호출할 가능성이
남아 있는 동안 내내 stdin이 열려 있어야 하므로, `QueryHandler.streamInput()`은 **실행이 끝날 때까지**
기다린 뒤에야 `transport.endInput()`을 호출합니다. 셋 다 같은 방식으로 처리됩니다 — CLI가
`control_request`를 쓰고 SDK가 짝이 되는 `control_response`를 stdin에 쓸 때까지 막힙니다 — 그래서 셋 다
양방향 필요(`hasBidirectionalNeeds()`)로 셉니다. 셋 중 아무것도 없으면 프롬프트를 쓰자마자 stdin을
닫습니다. 너무 일찍 닫는 것은 무해하지 않고, 닫기를 그냥 `close()`까지 미룰 수도 없습니다:
stream-json 모드의 CLI는 stdin EOF에서**만** 종료하므로, 그러면 일회성 `query()`가 영원히 멈춥니다.

미묘한 점은 **`result` 프레임이 끝내는 것은 한 턴이지 실행 전체가 아니라는 것**입니다. 백그라운드
서브에이전트는 그 뒤로도 계속 돌고, 끝나면 그 완료가 부모를 깨워 후속 턴을 돌게 합니다. 그 턴의 훅, 권한,
SDK MCP 요청에도 stdin이 필요합니다. 너무 일찍 닫으면 그 요청들이 `"Stream closed"`로 실패했고, 더
조용하게는 `PreToolUse` 훅을 건너뛰어 내장 도구가 콜백 없이 실행되고 거부 관문 훅이 관문 노릇을 멈췄습니다.

`QueryHandler`는 두 가지 출처로 실행이 끝났는지를 판단합니다.

**1. CLI의 세션 상태(주 신호).** 전송 계층은 CLI 프로세스에 `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`을
설정합니다(호출자의 `options.env`나 상속된 환경에 대소문자와 무관하게 이미 그 이름이 있으면 설정하지
않습니다). 이를 따르는 CLI는 `sdk_host_only: true`가 표시된 `system` / `session_state_changed` 프레임을
보냅니다: 백그라운드 에이전트가 살아 있거나 그 완료에 대해 아직 턴이 남아 있는 동안에는 `running`,
호스트를 기다리는 동안에는 `requires_action`을 유지하고, 더 이상 남은 턴이 없으면 `idle`을 보고합니다.
읽기 쪽은 최신 상태를 추적하고, **`sdk_host_only`가 표시된 프레임은 소비자에게 닿기 전에 걸러 냅니다**.
표시가 없는 프레임 — 호출자가 `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS`로 옵트인해서 보내지는 것 — 도
같은 방식으로 실행 종료를 이끌며, 그대로 전달됩니다.

**2. 작업 장부(대체 수단이자 안전장치).** `QueryHandler`는 `system` 작업 수명 주기 프레임으로 채워지는
진행 중 작업 장부를 둡니다:

```
system: task_started (task_type ∈ DEFERRING_TASK_TYPES)  ─►  add task_id
system: task_notification                                ─►  remove task_id
system: task_updated (patch.status ∈ TERMINAL_TASK_STATUSES) ─► remove task_id
```

실행 종료 규칙은 모두 하나의 `runLock` 아래에서 적용됩니다:

```
result frame
    ├─ state is null (CLI sends none) or "idle", or no bidirectional needs
    │      └─ ledger empty      ─►  end the run  ─►  endInput()
    │      └─ ledger non-empty  ─►  keep stdin open (log at FINE)
    ├─ state is "requires_action" ─►  wait (a request is being answered)
    └─ state is "running"         ─►  wait, and arm the run-end ceiling

session_state_changed
    ├─ "idle" after a result  ─►  end the run, unless the ledger is non-empty
    ├─ "idle" before a result ─►  nothing (the prompt's run has not produced a result yet)
    ├─ "requires_action"      ─►  reopen the run if it had ended; stop the ceiling
    └─ "running" (or other)   ─►  reopen the run if it had ended; restart the ceiling if past a result
```

어떤 호스트는 result *직전에* `idle`을 보내는데, 이때는 result가 실행을 끝냅니다. 상태를 전혀 보내지 않는
CLI(오래된 CLI, 그리고 아직 `CLAUDE_CODE_SDK_READS_SESSION_STATE`를 따르지 않는 Claude Code 2.1.283)에서는
상태가 `null`로 남으므로, 장부가 빈 상태의 첫 result가 실행을 끝냅니다 — 0.2.3 이전의 동작입니다.

**실행 종료 상한 시간.** CLI 자체의 백그라운드 대기 상한은 stdin이 닫힌 뒤에야 시간을 세기 시작하므로,
SDK 쪽에 자체 한도가 없으면 끝나지 않는 작업이 `running` 상태를 — 그리고 stdin을 — 영원히 붙잡아 둘
것입니다. CLI가 여전히 `running`을 보고하는 상태에서 result가 오면, `QueryHandler`는 `Threads`에서 얻은
스레드로 슬리퍼를 시작합니다. 슬리퍼가 깨어나기 전에 새 턴이 시작되지 않으면 실행을 끝냅니다. 지속 시간은
CLI가 보게 될 방식 그대로 — 먼저 `options.env`, 다음으로 프로세스 환경 — 읽은
`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`입니다. `0`은 제한 없음을 뜻하고, 음이 아닌 정수가 아닌 값은 CLI
기본값인 600000 ms(10분)로 대체되며, `Integer.MAX_VALUE` ms(약 24.8일)를 넘는 값은 그 값으로 잘립니다.
상한 시간은 턴 **사이의** 대기만 셉니다:

- 메인 스레드의 `assistant` 또는 `stream_event` 프레임(`parent_tool_use_id == null`)은 턴이 진행 중임을
  뜻합니다: 상한 타이머가 멈추고, 상한이 이미 실행을 끝냈더라도 실행이 다시 열립니다. 서브에이전트 자신의
  메시지는 타이머를 멈추지 **않습니다** — 그것이 바로 상한이 제한하는 작업이기 때문입니다.
- result가 오면 타이머가 다시 시작되고, result 이후에 도착한 `running`도 마찬가지입니다.
- `requires_action`은 뒤따르는 `running`이 올 때까지 타이머를 멈춥니다.
- 타이머가 발동할 때 아직 진행 중인 추적 대상 에이전트는 잘리지 않습니다. 장부가 비면 상한 시간이 처음부터
  다시 시작됩니다.

각 슬리퍼는 세대 번호를 지니며, 상한을 해제하거나 다시 걸면 그 번호가 올라가고 슬리퍼가 인터럽트되므로,
늦게 깨어난 슬리퍼는 물러납니다.

**다시 열기.** 실행을 끝내면 `CompletableFuture`가 완료됩니다. 그 뒤에 CLI가 맡는 작업(끝난 백그라운드
작업이 CLI를 깨우는 경우)은 새 future로 교체되므로, 아직 대기를 시작하지 않은 대기자 — 여전히 프롬프트를
쓰고 있는 `streamInput` — 도 새 작업을 기다립니다. `streamInput`이 쓰는 각 프롬프트는 저마다 하나의 실행을
가집니다: 쓰기 전에 실행을 다시 열고 "result 수신" 상태를 초기화하므로, 여러 메시지로 된 프롬프트는 첫
프롬프트가 아니라 *마지막* 프롬프트의 실행을 기다립니다. stdin이 닫히거나 읽기 쪽이 종료되면 실행은
최종 상태가 되어 끝난 채로 남고, CLI가 마무리되는 동안 도착하는 프레임에 대해서는 상한 타이머를 걸지
않습니다. `close()`와 읽기 쪽의 `finally` 블록이 모두 실행을 끝내므로 대기자가 멈출 일은 없습니다.

이 대기에는 다른 타임아웃이 없습니다. 예전에는 Java가 `CLAUDE_CODE_STREAM_CLOSE_TIMEOUT`(60초)으로도
대기를 제한해 1분 넘게 도는 백그라운드 에이전트를 잘라 냈습니다. 그 제한은 사라졌고, 이제
`CLAUDE_CODE_STREAM_CLOSE_TIMEOUT`은 `initialize` 타임아웃만 설정합니다.

`DEFERRING_TASK_TYPES`는 `{"local_agent", "local_workflow"}`입니다. 제외는 실수가 아니라 의도입니다.
백그라운드 셸(`local_bash`)과 모니터는 설계상 무기한 돌고, teammate는 평생 `running`으로 남으므로 어느
것도 종료 상태에 확실히 도달하지 않습니다. 그중 하나라도 추적하면 닫기가 잠깐이 아니라 *영원히* 보류되고
— 게다가 프로세스가 끝나지 않으니 읽기 쪽의 `finally`조차 돌지 않습니다. 이 집합에 더할 것은 반드시
확실하게 종료되는 타입이어야 합니다.

`background_tasks_changed` 프레임은 양방향으로 무시됩니다. 그 페이로드는 살아 있는 *백그라운드* 집합이지만,
서브에이전트는 포그라운드에서 등록되었다가 두 번째 `task_started` 없이 나중에 백그라운드로 바뀝니다 —
그래서 그것으로 좁히면 이 장부가 지키려는 바로 그 에이전트를 놓치고, 그것으로 넓히면 이후 어떤 프레임도
지우지 않는 id를 들일 수 있습니다.

알려진 한계: 세션 상태를 보고하지 않는 CLI에서는, 여러 메시지로 된 프롬프트 이터레이터의 앞선 프롬프트에
대한 result가, 뒤의 프롬프트가 이미 CLI 쪽에 대기 중이더라도 실행을 끝냅니다. 그래서 그 뒤 턴의 제어
요청이 닫힌 stdin을 만날 수 있습니다. 단일 메시지 프롬프트와 문자열 프롬프트 — 흔한 일회성 형태 — 는
완전히 처리됩니다.

## 동시성 모델

### 스레드 구조

SDK는 다중 스레드 구조를 씁니다(Java 21+에서는 가상 스레드, 17-20에서는 데몬 플랫폼 스레드):

1. **메인 스레드**: 사용자 애플리케이션의 스레드
2. **읽기 스레드**: CLI의 stdout에서 읽음
3. **제어 실행기**: 비동기 제어 프로토콜 작업을 위한 스레드 풀
4. **스트리밍 실행기**: 입력 메시지를 스트리밍하는 선택적 스레드
5. **훅 실행기**: 진행 중인 훅이나 도구 호출마다 스레드 하나

### 스레드 안전성

- **AtomicBoolean**: 연결 상태와 닫힘 상태에 사용
- **volatile**: QueryHandler와 Transport의 가시성에 사용
- **동기화**: 경쟁 상태를 막기 위해 connect()에 사용
- **BlockingQueue**: 스레드 안전한 메시지 큐
- **ConcurrentHashMap**: 스레드 안전한 제어 요청 추적

### 자원 관리

모든 자원이 AutoCloseable을 구현합니다:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
} // Automatic cleanup: QueryHandler, Transport, Executors
```

## 타입 시스템

### 메시지 타입 계층

```
Message (sealed interface)
    ├── UserMessage (record)
    ├── AssistantMessage (record)
    │       └── content: List<ContentBlock>   (sealed interface)
    │               ├── TextBlock
    │               ├── ThinkingBlock
    │               ├── ToolUseBlock
    │               ├── ToolResultBlock
    │               ├── ServerToolUseBlock
    │               ├── ServerToolResultBlock
    │               ├── ImageBlock        - PDF page render
    │               ├── DocumentBlock     - whole PDF
    │               └── UnknownBlock      - forward-compat fallback
    ├── SystemMessage (record)
    ├── ResultMessage (record)
    │       └── modelUsage: Map<String, ModelUsage>
    └── StreamEvent (record)
```

두 sealed 계층 모두 완전한 `switch`에 친화적이며, 이는 구성원을 추가하는 일이 호출자에게 의도적인 소스
호환성 파괴 사건임을 뜻합니다. `ContentBlock`은 0.1.20에서 세 구성원을 얻었습니다. `UnknownBlock`이 있는
덕분에 *모델링되지 않은* 타입은 더 이상 SDK 변경을 전혀 요구하지 않습니다 — 파서가 그것들을 통째로
보존하고 타입마다 한 번 기록할 뿐, 예외를 던지지 않습니다.

### 구성 타입

```
ClaudeAgentOptions
    ├── PermissionMode (enum)
    ├── ToolsPreset (record)
    ├── SystemPromptPreset (record)
    ├── SdkBeta (enum)
    ├── SettingSource (enum)
    ├── SandboxSettings (record)
    ├── ThinkingConfig (sealed interface)
    │       ├── ThinkingConfigAdaptive (record)
    │       ├── ThinkingConfigEnabled (record)
    │       └── ThinkingConfigDisabled (record)
    ├── McpServerConfig (sealed interface)
    │       ├── McpStdioServerConfig
    │       ├── McpSseServerConfig
    │       ├── McpHttpServerConfig
    │       └── McpSdkServerConfig
    ├── HookEvent (enum) - 10 events
    ├── HookMatcher (record)
    └── AgentDefinition (record)
```

### 권한 타입

```
PermissionResult (sealed interface)
    ├── PermissionResultAllow (record)
    └── PermissionResultDeny (record)
            └── reason: String
```

### 훅 타입

```
HookInput (sealed interface)
    ├── PreToolUseHookInput
    ├── PostToolUseHookInput
    ├── PostToolUseFailureHookInput
    ├── UserPromptSubmitHookInput
    ├── StopHookInput
    ├── SubagentStopHookInput
    ├── SubagentStartHookInput
    ├── PreCompactHookInput
    ├── NotificationHookInput
    └── PermissionRequestHookInput
```

## 의존성

### 런타임 의존성

1. **Jackson** (2.21.0)
   - `jackson-databind` — JSON 직렬화/역직렬화
   - `jackson-annotations` — JSON 어노테이션
   - 목적: CLI의 JSON 메시지 파싱, 제어 프로토콜 직렬화

2. **JSpecify** (1.0.0)
   - null 가능성 어노테이션(`@Nullable`, `@NonNull`)
   - 목적: 더 나은 null 안전성과 IDE 지원

3. **networknt json-schema-validator** (2.0.4)
   - 목적: MCP 명세가 서버에 요구하는 대로, 핸들러가 돌기 전에 SDK MCP 도구의 인자를 도구가 선언한
     `inputSchema`에 대조해 검증
   - 2.x 계열로 일부러 고정: 3.x는 Jackson 3(`tools.jackson`)을 대상으로 빌드되어 위의 Jackson 2 옆에
     완전한 JSON 스택을 하나 더 두게 됩니다. 2.0.4는 우리 databind를 재사용하는 가장 최신 릴리스입니다.
   - YAML 스키마 리더는 제외했고(도구 스키마는 파싱된 맵으로 도착합니다), 실수로 compile 스코프로 선언된
     Surefire 리포트 포매터도 제외했습니다
   - `slf4j-api`(2.0.17)를 전이적으로 들여옵니다. SDK는 `java.util.logging`으로 로그를 남기며 SLF4J
     바인딩은 **제공하지 않습니다** — 무엇을 고를지는 애플리케이션의 몫입니다. 프로바이더가 없는
     애플리케이션은 SDK MCP 서버를 처음 만들 때 표준 오류에 `No SLF4J providers were found`라는 일회성
     알림을 봅니다. 아무 바인딩이나 추가하면 사라집니다.

### 테스트 의존성

1. **JUnit 5** (6.0.2)
   - 테스트 프레임워크
   - 목적: 단위 테스트와 통합 테스트

2. **AssertJ** (3.27.7)
   - 유창한 단언 라이브러리
   - 목적: 읽기 쉬운 테스트 단언

3. **Mockito** (5.21.0)
   - 모킹 프레임워크
   - 목적: 테스트에서 의존성 모킹

### 빌드 의존성

1. **Maven Compiler Plugin** (3.14.1)
   - `-parameters` 플래그를 곁들인 Java 17 컴파일(`<release>17</release>`)
   - 목적: @Tool 어노테이션을 위해 매개변수 이름 보존

2. **Flatten Maven Plugin** (1.7.3)
   - `${revision}` 속성 해석
   - 목적: CI 친화적인 버전 관리

3. **Templating Maven Plugin** (3.1.0)
   - 템플릿에서 SdkVersion.java 생성
   - 목적: 빌드 시점에 버전 주입

## 설계 원칙

1. **타입 안전성**: Java의 타입 시스템(sealed 인터페이스, record, 열거형)을 활용
2. **불변성**: 구성 객체는 불변
3. **스레드 안전성**: 스레드 안전성 보장을 문서화하고 지킴
4. **자원 관리**: 올바른 정리를 위한 AutoCloseable
5. **빌더 패턴**: 유창하고 읽기 쉬운 구성
6. **빠른 실패**: 일찍 검증하고 의미 있는 예외를 던짐
7. **패턴 매칭**: 더 깔끔한 코드를 위해 현대 Java 기능 사용
8. **가상 스레드**: Java 21+에서 투명하게 가벼운 동시성
9. **관심사 분리**: 계층 경계를 명확히
10. **확장성**: 플러그인 시스템과 사용자 정의 전송

## 성능 고려 사항

1. **가상 스레드**: Java 21+에서 수천 개의 동시 작업 가능
2. **버퍼링 I/O**: 서브프로세스 통신의 시스템 호출을 줄임
3. **메시지 큐**: 메모리와 처리량의 균형을 위해 크기 설정 가능
4. **지연 초기화**: QueryHandler는 필요할 때만 생성
5. **자원 재사용**: 여러 작업에 걸쳐 ExecutorService 재사용
6. **다이렉트 메모리**: Jackson이 효율적인 버퍼 처리를 함
7. **복사 최소화**: 메시지 객체는 record(방어적 복사 없음)

## 앞으로의 확장성

이 아키텍처는 앞으로의 개선을 지원합니다:

1. **사용자 정의 전송**: 원격 Claude Code를 위해 Transport 인터페이스 구현
2. **추가 메시지 타입**: sealed 인터페이스 계층에 추가
3. **새 훅 이벤트**: HookEvent 열거형에 추가
4. **플러그인 시스템**: 사용자 확장을 위한 SdkPluginConfig
5. **대체 프로토콜**: 제어 프로토콜 구현 교체
6. **스트리밍 개선**: 부분 메시지 지원 강화
7. **캐싱**: SDK와 CLI 사이에 캐시 계층 추가
8. **메트릭**: 원격 측정과 성능 모니터링 추가
