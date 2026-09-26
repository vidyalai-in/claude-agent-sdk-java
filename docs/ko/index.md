# Claude Agent SDK for Java - 기술 문서

[English](../README.md) · [简体中文](../zh/index.md) · [日本語](../ja/index.md) · **한국어** · [Português](../pt/index.md) · [Español](../es/index.md)

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../README.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다. 자세한 내용은 [docs/TRANSLATIONS.md](../TRANSLATIONS.md)를 참고하세요.

Claude Agent SDK for Java 기술 문서에 오신 것을 환영합니다. 이 문서는 SDK의 아키텍처, 기능,
사용법을 포괄적으로 설명합니다.

## 개요

Claude Agent SDK for Java는 Claude AI 기능을 Java 애플리케이션에 통합하기 위한 포괄적인
라이브러리입니다. Claude Code CLI와 상호작용하기 위한 타입 안전하고 현대적인 Java API를
제공하며, 간단한 일회성 질의부터 복잡한 멀티턴 대화까지 지원합니다.

**주요 특징:**
- 🎯 **타입 안전한 API**: sealed 인터페이스와 record를 사용하므로 Java 21+ 사용자 코드에서 완전한 패턴 매칭이 동작합니다
- ⚡ **가상 스레드**: 백그라운드 작업이 Java 21+에서는 Project Loom 가상 스레드에서, 17~20에서는 데몬 플랫폼 스레드에서 실행됩니다
- 🔧 **유연한 아키텍처**: 무상태 질의와 상태 있는 대화를 모두 지원
- 🛠️ **사용자 정의 도구**: MCP(Model Context Protocol)로 나만의 도구 만들기
- 🔌 **플러그인**: 로컬 디렉터리에서 Claude Code 플러그인(명령, 에이전트, 스킬, 훅) 로드
- 🎨 **빌더 패턴**: 구성을 위한 유창한 API

## 문서 색인

### 아키텍처 및 설계
- **[아키텍처 개요](./architecture.md)** — 시스템 아키텍처, 설계 패턴, 내부 구조
  - 상위 수준 아키텍처 다이어그램
  - 핵심 구성 요소 (API 계층, 구성, 프로토콜, 전송 계층)
  - 설계 패턴 (sealed 인터페이스, 빌더, 파사드, 가상 스레드)
  - 데이터 흐름 다이어그램과 동시성 모델
  - stdin 수명 주기: 실행이 끝나는 시점 (세션 상태, 작업 장부, 턴 사이 상한 시간)
  - 타입 시스템 계층 구조와 의존성

### 핵심 기능
- **[간단한 질의](./feature-simple-queries.md)** — ClaudeSDK 파사드를 이용한 일회성 질의
  - 기본 사용 예제
  - 질의 메서드 개요
  - 구성 옵션
  - 메시지 처리 패턴
  - 모범 사례

- **[대화형 세션](./feature-interactive-conversations.md)** — ClaudeSDKClient를 이용한 멀티턴 대화
  - 연결 관리
  - 메시지 송수신
  - 제어 메서드
  - 세션 관리
  - 스레드 안전성
  - 전체 예제

- **[구성 옵션](./feature-configuration-options.md)** — ClaudeAgentOptions 빌더 완전 가이드
  - 30가지가 넘는 모든 구성 옵션
  - 도구 구성
  - 시스템 프롬프트 형식과 `snapshot`
  - 권한 설정
  - 모델 구성
  - 환경 변수, 그리고 SDK가 직접 설정하는 환경 변수
  - `verbatimPrompts` — `@path` 확장이나 슬래시 명령 없이 프롬프트 전달
  - 훅과 콜백
  - 자주 쓰는 패턴의 전체 예제

- **[메시지 타입](./feature-message-types.md)** — 메시지 타입 시스템 이해하기
  - UserMessage, AssistantMessage, SystemMessage, ResultMessage, StreamEvent, RateLimitEvent
  - 작업 수명 주기 메시지 (TaskStartedMessage, TaskProgressMessage, TaskNotificationMessage, TaskUpdatedMessage)
  - HookEventMessage (`includeHookEvents`가 켜진 경우)
  - ResultMessage의 DeferredToolUse, `apiErrorStatus` HTTP 상태 필드
  - ConversationResetMessage — 세션 도중 대화가 교체된 경우 (예: `/clear`)
  - 메시지 출처 — 내 턴과 세션이 주입한 턴 구별하기
  - 콘텐츠 블록 (Text, Thinking, ToolUse, ToolResult)
  - 패턴 매칭
  - 예제와 모범 사례

- **[MCP 서버](./feature-mcp-servers.md)** — Model Context Protocol로 사용자 정의 도구 만들기
  - SDK MCP 서버 (인프로세스)
  - 외부 MCP 서버 (stdio/SSE/HTTP)
  - @Tool 어노테이션 사용법
  - 프로그래밍 방식의 도구 생성
  - 도구 스키마와 도구의 `inputSchema`에 대한 인자 검증
  - 실패 의미론 (도구 호출이 마주치는 모든 상황에 대한 `isError` 결과)
  - `ToolCallContext`로 실행 중인 도구 취소하기
  - 프로토콜 세부 사항 (버전 협상, 알림, 메서드)
  - `McpMessageHandler`를 통한 사용자 정의 MCP 핸들러
  - 비동기 실행 패턴
  - 전체 예제 (계산기, 데이터베이스, API 연동)

- **[에이전트 정의](./feature-agents.md)** — 전용 프롬프트, 도구, 모델을 갖춘 사용자 정의 서브에이전트
  - 인라인 에이전트 정의
  - 파일시스템 기반 에이전트
  - 대용량 에이전트 지원 (stdin을 통한 260KB 이상)
  - AgentDefinition API (skills, 메모리 범위, MCP 서버 필드 포함)
  - 서브에이전트 출력 관찰과 `forwardSubagentText`

- **[세션 히스토리](./feature-session-history.md)** — 디스크에 저장된 과거 Claude Code 대화 세션 읽기 및 관리
  - 전체 프로젝트 또는 디렉터리로 필터링한 세션 목록
  - ID로 단일 세션 조회 (`getSessionInfo`)
  - 전체 대화 트랜스크립트 읽기
  - 서브에이전트 트랜스크립트 읽기 (`listSubagents`, `getSubagentMessages`),
    이를 생성한 Agent `tool_use`에 귀속
  - 세션 이름 변경 (`renameSession`)
  - 정리를 위한 세션 태깅 (`tagSession`)
  - 세션 삭제 (`deleteSession`) — 서브에이전트 트랜스크립트 디렉터리까지 연쇄 삭제
  - UUID 재매핑을 동반한 세션 포크 (`forkSession`)
  - 잘라내기 재개 (`resumeSessionAt` / `resumeDropsTurn`) — 이전 지점으로 안전하게 되감기
  - SDKSessionInfo (tag, createdAt, null 허용 fileSize 포함)와 SessionMessage 타입
  - offset 기반 페이지네이션과 worktree 지원

- **[Session Store](./feature-session-store.md)** — 트랜스크립트를 S3 / Postgres / Redis / 사용자 백엔드로 미러링
  - 동기 및 비동기(`CompletableFuture`) 변형을 갖춘 `SessionStore` 어댑터 프로토콜
  - `SessionStoreExecutor`를 통한 가상 스레드 실행기 구성
  - 기본 제공 `InMemorySessionStore` 참조 어댑터와 `filePathToSessionKey` 헬퍼
  - 읽기 API: `listSessionsFromStore`, `getSessionInfoFromStore`, `getSessionMessagesFromStore`, `listSubagentsFromStore`, `getSubagentMessagesFromStore`
  - 변경 API: `renameSessionViaStore`, `tagSessionViaStore`, `deleteSessionViaStore`, `forkSessionViaStore`
  - 로컬→저장소 재생을 위한 `importSessionToStore`, 치명적이지 않은 추가 실패를 알리는 `MirrorErrorMessage`
  - 공개된 `SessionStoreConformance` 테스트 하네스 (14개 계약, 프레임워크 비종속)
  - 저장소에서 재개 (서브프로세스는 임시 `CLAUDE_CONFIG_DIR`를 받습니다), 트랜스크립트 미러 배처

- **[Skills](./feature-skills.md)** — 메인 세션을 위한 최상위 `skills` 옵션
  - 세 가지 모드: `skillsAll()`, `skills(List)`, `skills(List.of())`
  - `allowedTools`에 `Skill(name)` 자동 주입 및 `settingSources` 기본값 설정
  - initialize 제어 요청을 통한 와이어 프로토콜 전파
  - 멱등적 주입, 명시적 설정이 항상 우선
  - Skill 이름 검증 — `--allowedTools` 규칙 주입 차단, 결코 매칭될 수 없는 이름 거부

- **[W3C Trace Context 전파](./feature-trace-context.md)** — SDK와 CLI에 걸친 분산 추적
  - CLI 서브프로세스로의 `TRACEPARENT`/`TRACESTATE` 최선 노력 주입
  - OpenTelemetry에 대한 런타임 의존성 없음 (리플렉션 기반)
  - 오래된 환경 변수 제거, baggage만 있는 컨텍스트, 전파기 오류

### 고급 기능
- **[확장 사고 구성](./feature-thinking-config.md)** — Claude의 추론 깊이 제어하기
  - ThinkingConfig 타입 (Adaptive, Enabled, Disabled)
  - 노력 수준 (low, medium, high, max)
  - 예산 제어와 최적화
  - 전체 사용 예제

- **[훅 시스템](./feature-hooks.md)** — 수명 주기 이벤트 가로채기 및 응답
  - 10가지 훅 이벤트
  - HookMatcher와 HookOutput
  - PostToolUse의 `updatedToolOutput` (모든 도구의 출력 교체)과 `updatedMCPToolOutput`
  - `PermissionDecision.DEFER`와 ResultMessage의 `DeferredToolUse`
  - `includeHookEvents`와 HookEventMessage 스트림
  - 자주 쓰는 사례의 예제

- **[권한 시스템](./feature-permissions.md)** — 사용자 정의 권한 콜백과 모드
  - 권한 모드
  - 사용자 정의 권한 콜백 (`"ask"` 결정에서만 발동)
  - 섀도잉과 `settingSources(List.of())`로 콜백을 결정론적으로 유지하기
  - 보강된 `ToolPermissionContext` (`title`, `displayName`, `description`, `decisionReason`, `blockedPath`)
  - 경로 기반, 시간 기반, 사용자 확인 예제

- **[스트리밍 이벤트](./feature-streaming-events.md)** — 실시간 부분 메시지 업데이트
  - 스트리밍 활성화
  - 스트림 이벤트 처리
  - UI 통합 예제

- **[전송 계층](./feature-transport-layer.md)** — 사용자 정의 전송 구현
  - Transport 인터페이스
  - 기본 구현
  - 사용자 정의 전송 예제
  - Windows 배치 스크립트 거부와 npm `claude.cmd` 배포를 위한 명시적 옵트인

- **[플러그인 시스템](./feature-plugin-system.md)** — Claude Code 플러그인 로드하기
  - `SdkPluginConfig.local(path)` → `--plugin-dir`
  - 플러그인 레이아웃과 플러그인 로드 여부 확인

### API 레퍼런스
- **[ClaudeSDK](./api-claude-sdk.md)** — 간단한 질의를 위한 정적 파사드
  - 질의 메서드
  - 클라이언트 팩토리 메서드
  - MCP 서버 팩토리 메서드
  - 편의 메서드

- **[ClaudeSDKClient](./api-claude-sdk-client.md)** — 대화를 위한 대화형 클라이언트
  - 연결 메서드
  - 메시지 송수신
  - 제어 메서드
  - 스레드 안전성 참고 사항

- **[ClaudeAgentOptions](./api-claude-agent-options.md)** — 구성 빌더
  - 모든 구성 옵션
  - 빌더 메서드

- **[메시지 타입](./api-message-types.md)** — 완전한 메시지 타입 계층 구조
  - 모든 메시지 타입과 콘텐츠 블록
  - 필드 문서

- **[예외 타입](./api-exceptions.md)** — 오류 처리와 예외
  - 예외 계층 구조
  - `ResultException`과 종료 오류 결과의 페이로드
  - 각 예외가 실제로 나타나는 지점
  - 오류 처리 예제

### 프로젝트 리소스
- **[CHANGELOG](../CHANGELOG.md)** — 버전 이력과 릴리스 노트 (영어 전용)
- **[Python SDK 동등성](../PYTHON_SDK_PARITY.md)** — Python SDK와의 기능 비교 (영어 전용)
- **[번역 안내](../TRANSLATIONS.md)** — 번역 범위, 동기화 정책, 기여 방법 (영어)

## 프로젝트 구조

여러 모듈로 구성된 Maven 프로젝트입니다:

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to Maven Central; mirrored to GitHub Packages)
│   ├── src/main/java/in/vidyalai/claude/sdk/
│   │   ├── ClaudeSDK.java              # Main facade
│   │   ├── ClaudeSDKClient.java        # Interactive client
│   │   ├── ClaudeAgentOptions.java     # Configuration builder
│   │   ├── exceptions/                 # Exception types
│   │   ├── transport/                  # Transport layer
│   │   ├── internal/                   # Internal implementation
│   │   ├── mcp/                        # MCP server support
│   │   └── types/                      # Type definitions
│   └── src/test/java/                  # SDK tests
└── examples/         # Usage examples (separate module)
    └── src/main/java/examples/
        ├── QuickStart.java
        ├── MultiTurnConversation.java
        ├── McpServer.java
        └── ... (15+ examples)
```

## 시작하기

### 사전 요구 사항
- Java 17 이상 (21 이상에서는 가상 스레드가 자동으로 사용됩니다)
- Maven 3.6+
- 별도로 설치한 Claude Code CLI

### 설치

`pom.xml`에 추가하세요 — 저장소나 인증 설정은 필요하지 않습니다:

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

이미 GitHub Packages를 바라보고 있는 사용자를 위해 릴리스는 그곳에도 미러링됩니다. 그 경로는
아티팩트가 공개되어 있음에도 개인 액세스 토큰을 요구하므로, 특별한 이유가 없다면 Maven Central을
사용하세요 — 저장소와 인증 설정은 [루트 README](./README.md#대안-github-packages)를 참고하세요.

### Hello World

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;

// Simple query
List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        System.out.println(assistant.getTextContent());
    }
}
```

### 대화형 세션

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Hello!");

    for (var msg : client.receiveResponse()) {
        // Process messages
    }

    client.sendMessage("Tell me more");
    for (var msg : client.receiveResponse()) {
        // Process follow-up
    }
}
```

## 예제

이 SDK에는 다음을 다루는 30개 이상의 실행 가능한 예제가 들어 있습니다:
- 기본 질의와 대화
- 사용자 정의 MCP 도구
- 권한 콜백
- 백그라운드 서브에이전트의 후속 턴에서 처리되는 훅을 포함한 훅 시스템 (`BackgroundAgentHooksExample`)
- 스트리밍 이벤트
- 오류 처리
- `snapshot`을 포함한 시스템 프롬프트 (`SystemPromptExample`)
- 프롬프트를 있는 그대로 전달하기 (`VerbatimPromptsExample`)
- 고급 기능 (체크포인트, 샌드박스, 출력 형식)
- 그 외 다수

저장소의 `examples/` 디렉터리를 참고하세요.

## 이 문서를 활용하는 법

1. **처음 오신 분**: 설치와 빠른 시작은 이 README부터
2. **아키텍처 이해**: [아키텍처 개요](./architecture.md) 읽기
3. **간단한 사용 사례**: [간단한 질의](./feature-simple-queries.md) 따라 하기
4. **사용자 정의 도구**: [MCP 서버](./feature-mcp-servers.md) 익히기
5. **고급 기능**: 필요에 따라 다른 기능 가이드 살펴보기

## 문서 현황

### ✅ 완료 — 모든 핵심 문서
- 빠른 시작과 개요를 담은 메인 README
- 아키텍처 개요 (다이어그램을 포함한 포괄적 내용, SessionStore 하위 시스템, 제어 요청 실패 처리, stdin 수명 주기와 실행 종료 감지)
- 모든 기능 가이드:
  - 간단한 질의
  - 대화형 세션
  - 구성 옵션 (`sessionStore`, `loadTimeoutMs`, `verbatimPrompts`, 시스템 프롬프트 `snapshot` 포함)
  - 메시지 타입 (작업 메시지, 서버 도구 블록, MirrorErrorMessage)
  - MCP 서버 (ToolAnnotations, 도구 제목, 상태 타입, 입력 검증, 실패 의미론, 취소, 사용자 정의 핸들러 포함)
  - 에이전트 정의
  - 확장 사고 구성 (`ThinkingDisplay` 포함)
  - 훅 시스템 (agentId/agentType 필드 포함)
  - 권한 시스템
  - 스트리밍 이벤트
  - 전송 계층 (`--session-mirror`, `--thinking-display` 포함, `--debug-to-stderr` 제거)
  - 플러그인 시스템
  - 세션 히스토리 (listSessions / getSessionMessages)
  - Session Store (트랜스크립트를 S3/Postgres/Redis/사용자 백엔드로 미러링, 제한된 실행기)
- 완전한 API 레퍼런스 (문서 5종):
  - ClaudeSDK (세션 히스토리 메서드 포함)
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - 메시지 타입
  - 예외 타입
- 코드 예제 (examples/ 디렉터리에 20개 이상)
- Python SDK 동등성 문서

## 문서 기여하기

새 문서를 추가할 때는:
1. 기존 구조와 형식을 따르세요
2. 실제로 동작하는 코드 예제를 포함하세요
3. 모든 코드 예제를 실제 구현과 대조해 검증하세요
4. 관련 문서로의 상호 참조를 추가하세요
5. 문서 색인에 새 문서를 등록하세요
6. 문서 기준을 따르세요:
   - 명확한 목차
   - 실용적인 예제
   - 모범 사례 절
   - 링크가 달린 "관련 항목" 절

## 문서 원칙

이 프로젝트의 모든 문서는 다음 원칙을 따릅니다:
1. **정확성**: 모든 코드 예제는 동작해야 하고 실제 API와 일치해야 합니다
2. **완전성**: 주요 사용 사례와 시나리오를 모두 다룹니다
3. **명료성**: 분명한 표현을 쓰고 복잡한 개념을 설명합니다
4. **예제**: 실용적이고 실행 가능한 코드 예제를 포함합니다
5. **상호 참조**: 관련 문서로 링크합니다
6. **모범 사례**: 권장 패턴과 안티패턴을 포함합니다
7. **최신성**: 코드 변경에 맞춰 유지합니다

## 지원 및 리소스

- **GitHub 저장소**: https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**: 버그와 기능 요청은 GitHub Issues에 등록해 주세요
- **예제 코드**: 저장소의 `examples/` 디렉터리 참고
- **MCP 명세**: https://spec.modelcontextprotocol.io/
- **라이선스**: MIT License
- **Python SDK**: 비교용으로 https://github.com/anthropics/anthropic-sdk-python 참고
- **Claude Agent Python SDK 문서**: https://platform.claude.com/docs/en/agent-sdk/python

## 기여

기여를 환영합니다! 기여 지침은 저장소를 참고해 주세요.

## 버전

현재 릴리스는 [Maven Central](https://central.sonatype.com/artifact/in.vidyalai/claude-agent-sdk-java)에
올라와 있는 것이 기준입니다 — 손으로 관리하던 사본이 네 개 릴리스나 뒤처진 적이 있어 여기에는
일부러 다시 적지 않습니다.

버전 이력과 릴리스 노트는 [CHANGELOG.md](../CHANGELOG.md)(영어 전용)를 참고하세요.
