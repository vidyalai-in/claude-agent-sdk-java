# ClaudeSDK API 레퍼런스

간단한 질의와 클라이언트 생성을 위한 정적 파사드입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../api-claude-sdk.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 클래스 개요

```java
public final class ClaudeSDK
```

일반적인 SDK 작업을 위한 정적 메서드를 제공하는 유틸리티 클래스입니다.

## 질의 메서드

### query(String prompt)

```java
public static List<Message> query(String prompt)
```

기본 옵션으로 질의를 실행합니다.

**반환**: `List<Message>`

### query(String prompt, ClaudeAgentOptions options)

```java
public static List<Message> query(
    String prompt,
    ClaudeAgentOptions options
)
```

사용자 지정 옵션으로 질의를 실행합니다.

**매개변수**:
- `prompt` — 프롬프트
- `options` — 구성 옵션

**반환**: `List<Message>`

**던짐**:
- `IllegalArgumentException` — canUseTool과 permissionPromptToolName이 둘 다 설정된 경우
- `CLIConnectionException` — 연결 실패
- `ProcessException` — CLI 프로세스 실패
- `QueryFailedException` — 실행이 오류 결과로 끝난 경우(`error_max_turns`, `error_max_budget_usd`, `resumeDropsTurn`으로 거부된 재개). 그 전에 모은 메시지를 담고 있습니다 — 아래를 보세요.

### query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

```java
public static List<Message> query(
    Iterator<Map<String, Object>> messageStream,
    ClaudeAgentOptions options
)
```

여러 메시지로 스트리밍 질의를 실행합니다.

**매개변수**:
- `messageStream` — 메시지 딕셔너리의 이터레이터
- `options` — 구성 옵션

**반환**: `List<Message>`

**던짐**: 위와 같으며 `QueryFailedException`을 포함합니다.

스트림의 각 메시지는 저마다 하나의 실행을 가집니다: 훅, `canUseTool` 콜백, SDK MCP 서버 중 하나라도
구성되어 있으면 *마지막* 메시지의 실행이 끝날 때까지 stdin이 열려 있습니다([질의가 반환되는
시점](#질의가-반환되는-시점) 참고). `verbatimPrompts(true)`이면 각 메시지는 사본에
`client_composed: true`가 붙은 채로 기록되며, 여러분의 Map은 수정되지 않습니다.

### query(..., Transport transport)

```java
public static List<Message> query(String prompt, ClaudeAgentOptions options, Transport transport)
public static List<Message> query(Iterator<Map<String, Object>> messageStream,
                                  ClaudeAgentOptions options, Transport transport)
```

위와 같지만, CLI 서브프로세스를 띄우는 대신 사용자 정의 [`Transport`](./feature-transport-layer.md#사용자-정의-전송)를
사용합니다(`null`이면 기본값). SDK는 그 `connect()`를 호출하고, `write()`로 프롬프트를 전달하며,
훅·에이전트·그 밖의 `initialize` 요청 설정을 제어 프로토콜로 보냅니다. 명령줄 옵션(model, cwd,
권한 모드, 도구, `env` 등)은 사용자 정의 전송에 적용되지 않으며, 저장소 기반 세션 재개도 건너뜁니다.

### 질의가 반환되는 시점

`query(...)`는 CLI의 출력이 끝나면 반환하며, 이는 SDK가 CLI의 stdin을 닫은 뒤에 일어납니다. 훅,
`canUseTool`, SDK MCP 서버가 없으면 프롬프트를 쓰자마자 stdin을 닫습니다. 그중 하나라도 있으면
CLI가 `result` 이후에도 — 예를 들어 끝난 백그라운드 서브에이전트가 깨우는 후속 턴에서 — 다시 호출할
수 있으므로, SDK는 실행이 끝날 때까지 stdin을 열어 둡니다:

- CLI가 세션 상태를 보고하는 경우, `idle`에서;
- 그렇지 않으면 진행 중인 백그라운드 작업이 없는 첫 `result`에서;
- 또는 CLI가 계속 `running`을 보고하는 동안, 새 턴 없이
  `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`(기본 10분, `0` = 제한 없음)가 지난 뒤.

[아키텍처 → stdin 수명 주기](./architecture.md#stdin-수명-주기와-실행의-끝)와
[에이전트 → 백그라운드 서브에이전트와 콜백](./feature-agents.md#백그라운드-서브에이전트와-콜백)을
참고하세요.

### 오류 결과와 부분 메시지

CLI는 `error_max_turns`와 `error_max_budget_usd`를, *완전한* 턴 — 어시스턴트 메시지와 하위 타입·비용·
사용량을 담은 마지막 `ResultMessage` — 을 내보낸 다음에야, 셸 사용자를 위해 일부러 0이 아닌 코드로
종료하는 방식으로 알립니다.

메시지를 모으는 이 메서드들은 리스트를 반환하거나 예외를 던지는 것 중 하나만 할 수 있으므로, 그런 일이
생기면 `QueryFailedException`을 던지고 모아 둔 메시지를 거기에 실어 돌려줍니다. 아무것도 잃지 않습니다:

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (QueryFailedException e) {
    ResultMessage result = e.resultMessage();       // the final result, or null
    List<Message> partial = e.partialMessages();    // everything received first
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped after $%.4f%n", result.totalCostUsd());
    }
}
```

`maxTurns`나 `maxBudgetUsd`를 설정했다면 언제나 이 예외를 잡으세요 — 직접 설정한 한도에 도달하는 것은
충돌이 아니라 예상된 결과입니다. 모아 둔 메시지가 아니라 결과의 페이로드(`subtype()`,
`terminalReason()`, `apiErrorStatus()` 등)가 필요하다면 cause에서 읽으세요. 그것이
[`ResultException`](./api-exceptions.md#resultexception)입니다.

[`ClaudeSDKClient`](./api-claude-sdk-client.md)의 스트리밍 API에는 애초에 이 예외가 필요 없었습니다.
메시지를 도착하는 대로 넘겨주고, `receiveResponse()`는 `ResultMessage`에서 멈추니까요 — 그 `isError()`와
`subtype()`을 직접 확인하세요. [예외](./api-exceptions.md#queryfailedexception)를 참고하세요.

## 편의 메서드

### queryForText(String prompt, ClaudeAgentOptions options)

```java
public static String queryForText(
    String prompt,
    ClaudeAgentOptions options
)
```

어시스턴트 메시지의 텍스트 내용만 얻습니다.

**반환**: `String` — 이어 붙인 텍스트

### queryForResult(String prompt, ClaudeAgentOptions options)

```java
public static ResultMessage queryForResult(
    String prompt,
    ClaudeAgentOptions options
)
```

결과 메시지만 얻습니다.

**반환**: `ResultMessage` 또는 null

## 클라이언트 팩토리 메서드

### createClient()

```java
public static ClaudeSDKClient createClient()
```

기본 옵션으로 클라이언트를 만듭니다.

**반환**: `ClaudeSDKClient`

### createClient(ClaudeAgentOptions options)

```java
public static ClaudeSDKClient createClient(
    ClaudeAgentOptions options
)
```

사용자 지정 옵션으로 클라이언트를 만듭니다.

**반환**: `ClaudeSDKClient`

## MCP 서버 팩토리 메서드

### createSdkMcpServer(String name, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    List<SdkMcpTool<?>> tools
)
```

도구 목록에서 SDK MCP 서버를 만듭니다.

**매개변수**:
- `name` — 서버 이름
- `tools` — 도구 목록

**반환**: `McpSdkServerConfig`

### createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    String version,
    List<SdkMcpTool<?>> tools
)
```

버전을 지정해 SDK MCP 서버를 만듭니다.

### createSdkMcpServer(String name, Object instance)

```java
public static McpSdkMcpServer createSdkMcpServer(
    String name,
    Object instance
)
```

@Tool 어노테이션이 붙은 메서드에서 SDK MCP 서버를 만듭니다.

**매개변수**:
- `name` — 서버 이름
- `instance` — @Tool 메서드를 가진 객체

**반환**: `McpSdkServerConfig`

## 세션 히스토리 메서드

### listSessions()

```java
public static List<SDKSessionInfo> listSessions()
```

모든 프로젝트의 모든 세션을 가장 최근에 수정된 순서로 나열합니다. `~/.claude/projects/`에서 읽되 JSONL
파일을 완전히 파싱하지는 않습니다 — 파일마다 앞뒤 64 KB만 읽습니다.

**반환**: 수정 시각 내림차순으로 정렬된 `List<SDKSessionInfo>`

### listSessions(Path directory)

```java
public static List<SDKSessionInfo> listSessions(Path directory)
```

특정 프로젝트 디렉터리의 세션을 나열합니다.

**매개변수**:
- `directory` — 걸러낼 기준이 되는 프로젝트 작업 디렉터리

**반환**: `List<SDKSessionInfo>`

### listSessions(Path directory, Integer limit, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    boolean includeWorktrees
)
```

완전히 제어하며 세션을 나열합니다.

**매개변수**:
- `directory` — 걸러낼 프로젝트 디렉터리(null = 모든 프로젝트)
- `limit` — 반환할 최대 세션 수(null = 제한 없음)
- `includeWorktrees` — git worktree 디렉터리를 포함할지 여부

**반환**: `List<SDKSessionInfo>`

### getSessionInfo(String sessionId)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(String sessionId)
```

ID로 세션 하나를 조회합니다. `~/.claude/projects/` 아래의 모든 프로젝트 디렉터리를 검색합니다. O(n)
디렉터리 훑기는 하지 않고 대상 세션 파일만 읽습니다.

**매개변수**:
- `sessionId` — 조회할 세션의 UUID

**반환**: 해당 세션의 `SDKSessionInfo`. 찾지 못했거나, 곁가지 세션이거나, 요약을 뽑아낼 수 없으면
`null`

### getSessionInfo(String sessionId, Path directory)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(
    String sessionId,
    Path directory
)
```

특정 프로젝트 디렉터리 안에서 ID로 세션 하나를 조회합니다.

**매개변수**:
- `sessionId` — 조회할 세션의 UUID
- `directory` — 검색할 프로젝트 작업 디렉터리

**반환**: `SDKSessionInfo` 또는 `null`

### getSessionMessages(String sessionId)

```java
public static List<SessionMessage> getSessionMessages(String sessionId)
```

세션의 전체 대화 메시지를 반환합니다. 모든 프로젝트 디렉터리를 검색합니다.

**매개변수**:
- `sessionId` — 세션의 UUID

**반환**: 대화 순서의 `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory
)
```

특정 프로젝트의 세션 메시지를 반환합니다.

**매개변수**:
- `sessionId` — 세션의 UUID
- `directory` — 검색할 프로젝트 작업 디렉터리

**반환**: `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory,
    Integer limit,
    int offset
)
```

필터링을 완전히 제어하며 메시지를 반환합니다.

**매개변수**:
- `sessionId` — 세션의 UUID
- `directory` — 검색할 프로젝트 디렉터리(null = 모든 프로젝트)
- `limit` — 반환할 최대 메시지 수(null = 제한 없음)
- `offset` — 앞에서 건너뛸 메시지 수

**반환**: `List<SessionMessage>`

## 서브에이전트 트랜스크립트 메서드

세션이 (`Task` 도구나 프로그래밍 방식 에이전트 정의로) 서브에이전트를 만들면, 각 서브에이전트의
트랜스크립트가 `~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`에 기록됩니다.
이 파일들은 `subagents/workflows/<runId>/` 같은 중첩 디렉터리에 놓일 수도 있습니다.

### listSubagents(String sessionId)

```java
public static List<String> listSubagents(String sessionId)
```

모든 프로젝트 디렉터리에 걸쳐 그 세션의 `subagents/` 디렉터리를 훑어 서브에이전트 ID를 나열합니다.

**매개변수**:
- `sessionId` — 부모 세션의 UUID

**반환**: 서브에이전트 ID의 `List<String>`. 세션을 찾지 못했거나, `sessionId`가 올바른 UUID가 아니거나,
그 세션에 서브에이전트가 없으면 비어 있습니다.

### listSubagents(String sessionId, Path directory)

```java
public static List<String> listSubagents(String sessionId, Path directory)
```

특정 프로젝트 디렉터리로 한정해 서브에이전트 ID를 나열합니다.

**매개변수**:
- `sessionId` — 부모 세션의 UUID
- `directory` — 그 세션을 찾을 프로젝트 작업 디렉터리

### getSubagentMessages(String sessionId, String agentId)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId
)
```

서브에이전트의 JSONL 트랜스크립트에서 user/assistant 메시지를 읽습니다. `parentUuid` 링크를 따라가
사슬을 재구성합니다. 각 메시지의 `parentToolUseId`는 그 서브에이전트를 만든 부모 세션의 Agent
`tool_use`이고, 중첩된 서브에이전트에서는 `parentAgentId`가 생성한 서브에이전트를 가리킵니다. 둘 다
트랜스크립트 옆의 `agent-<agentId>.meta.json` 사이드카에서 오며(트랜스크립트의 줄 자체에는 기록되지
않기 때문입니다), 그 사이드카가 없거나 쓸 수 없으면 둘 다 null입니다.

**매개변수**:
- `sessionId` — 부모 세션의 UUID
- `agentId` — 서브에이전트 ID(`listSubagents`가 반환한 값)

**반환**: 시간순의 `List<SessionMessage>`. 세션이나 서브에이전트를 찾지 못했거나, `sessionId`가 올바른
UUID가 아니거나, 트랜스크립트에 user/assistant 메시지가 없으면 비어 있습니다.

### getSubagentMessages(String sessionId, String agentId, Path directory)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    Path directory
)
```

특정 프로젝트 디렉터리로 한정해 서브에이전트의 메시지를 읽습니다.

### getSubagentMessages(String sessionId, String agentId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset
)
```

필터링과 페이지네이션을 완전히 제어하며 서브에이전트 메시지를 읽습니다.

**매개변수**:
- `sessionId` — 부모 세션의 UUID
- `agentId` — 서브에이전트 ID
- `directory` — 검색할 프로젝트 디렉터리(null = 모든 프로젝트)
- `limit` — 반환할 최대 메시지 수(null 또는 `0` = 제한 없음)
- `offset` — 앞에서 건너뛸 메시지 수

## 세션 변경 메서드

### renameSession(String sessionId, String title)

```java
public static void renameSession(
    String sessionId,
    String title
) throws IOException
```

사용자 지정 제목 항목을 덧붙여 세션 이름을 바꿉니다. 가장 최근의 변경이 이깁니다. 모든 프로젝트
디렉터리를 검색합니다.

**매개변수**:
- `sessionId` — 이름을 바꿀 세션의 UUID
- `title` — 새 세션 제목(앞뒤 공백은 제거됨)

**던짐**:
- `IllegalArgumentException` — `sessionId`가 올바른 UUID가 아니거나 `title`이 비었을 때
- `FileNotFoundException` — 세션 파일을 찾지 못했을 때
- `IOException` — 쓰기가 실패했을 때

### renameSession(String sessionId, String title, Path directory)

```java
public static void renameSession(
    String sessionId,
    String title,
    Path directory
) throws IOException
```

특정 프로젝트 디렉터리로 한정해 세션 이름을 바꿉니다.

**매개변수**:
- `sessionId` — 이름을 바꿀 세션의 UUID
- `title` — 새 세션 제목
- `directory` — 검색할 프로젝트 작업 디렉터리

### tagSession(String sessionId, String tag)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag
) throws IOException
```

세션에 태그를 답니다. `null`을 넘기면 기존 태그를 지웁니다. 태그는 저장 전에 유니코드 정제를 거칩니다.
모든 프로젝트 디렉터리를 검색합니다.

**매개변수**:
- `sessionId` — 태그를 달 세션의 UUID
- `tag` — 태그 문자열, 또는 지우려면 `null`. (`null`이 아니라면) 정제 후 비어 있으면 안 됩니다.

**던짐**:
- `IllegalArgumentException` — `sessionId`가 잘못되었거나 정제 후 `tag`가 비었을 때
- `FileNotFoundException` — 세션 파일을 찾지 못했을 때
- `IOException` — 쓰기가 실패했을 때

### tagSession(String sessionId, String tag, Path directory)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag,
    Path directory
) throws IOException
```

특정 프로젝트 디렉터리로 한정해 세션에 태그를 답니다.

**매개변수**:
- `sessionId` — 태그를 달 세션의 UUID
- `tag` — 태그 문자열, 또는 지우려면 `null`
- `directory` — 검색할 프로젝트 작업 디렉터리

### deleteSession(String sessionId)

```java
public static void deleteSession(String sessionId) throws IOException
```

JSONL 파일을 지워 세션을 영구히 삭제합니다. 서브에이전트 트랜스크립트를 담은 형제 디렉터리
`<sessionId>/`도 (있다면) 재귀적으로 제거합니다. 소프트 삭제가 필요하다면 대신
`tagSession(id, "__hidden")`을 쓰고 목록에서 걸러내세요.

**매개변수**:
- `sessionId` — 삭제할 세션의 UUID

**던짐**:
- `IllegalArgumentException` — `sessionId`가 올바른 UUID가 아닐 때
- `FileNotFoundException` — 세션 파일을 찾지 못했을 때
- `IOException` — 삭제가 실패했을 때(서브에이전트 디렉터리 정리는 최선 노력이며 호출을 실패시키지
  않습니다)

### deleteSession(String sessionId, Path directory)

```java
public static void deleteSession(
    String sessionId,
    Path directory
) throws IOException
```

특정 프로젝트 디렉터리로 한정해 세션을 삭제합니다.

### forkSession(String sessionId)

```java
public static ForkSessionResult forkSession(String sessionId) throws IOException
```

세션을 새 UUID를 가진 새 분기로 포크합니다.

**반환**: 새 세션의 UUID를 담은 `ForkSessionResult`

**던짐**:
- `IllegalArgumentException` — `sessionId`가 올바른 UUID가 아닐 때
- `FileNotFoundException` — 세션 파일을 찾지 못했을 때
- `IOException` — 포크가 실패했을 때

### forkSession(String sessionId, Path directory)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    Path directory
) throws IOException
```

특정 프로젝트 디렉터리로 한정해 세션을 포크합니다.

### forkSession(String sessionId, Path directory, String upToMessageId, String title)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    @Nullable Path directory,
    @Nullable String upToMessageId,
    @Nullable String title
) throws IOException
```

선택적 잘라내기 지점과 사용자 지정 제목을 지정해 세션을 포크합니다.

**매개변수**:
- `sessionId` — 원본 세션의 UUID
- `directory` — 프로젝트 디렉터리(null이면 모든 프로젝트 검색)
- `upToMessageId` — 이 메시지 UUID에서 트랜스크립트를 자릅니다(해당 항목 포함). null이면 전부 복사
- `title` — 포크의 사용자 지정 제목. null이면 원래 제목에 " (fork)"를 붙여 만듭니다

### listSessions(Path directory, Integer limit, int offset, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    int offset,
    boolean includeWorktrees
)
```

오프셋 페이지네이션을 지원하며 세션을 나열합니다.

**매개변수**:
- `directory` — 프로젝트 디렉터리(null이면 모든 프로젝트)
- `limit` — 반환할 최대 세션 수
- `offset` — 건너뛸 세션 수(페이지네이션용)
- `includeWorktrees` — git worktree의 세션을 포함

## SessionStore 기반 메서드

이 메서드들은 로컬 `~/.claude/projects/` 파일시스템이 아니라 `SessionStore` 어댑터를 통해 세션을 읽고
씁니다. 기능 전체 문서는 [Session Store 가이드](./feature-session-store.md)를 참고하세요.

### projectKeyForDirectory(Path directory)

```java
public static String projectKeyForDirectory(@Nullable Path directory)
```

CLI가 쓰는 것과 같은 realpath + NFC 정규화 + djb2 해시 정제를 사용해 디렉터리의 `SessionStore`
`project_key`를 계산합니다. `directory == null`이면 현재 작업 디렉터리를 기본으로 합니다.

**반환**: `SessionKey.projectKey()`에 쓸 수 있는 정제된 프로젝트 키 문자열.

### listSessionsFromStore(SessionStore, Path, Integer, int)

```java
public static List<SDKSessionInfo> listSessionsFromStore(
    SessionStore sessionStore,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset)
```

`SessionStore`에서 세션을 나열합니다. `store.implementsListSessionSummaries()`가 `true`를 반환하면 빠른
경로를 쓰고, 아니면 동시성을 16으로 제한한 세션별 로드로 물러납니다.

**던짐**: 저장소가 `listSessionSummaries()`도 `listSessions()`도 구현하지 않으면
`IllegalStateException`.

### getSessionInfoFromStore(SessionStore, String, Path)

```java
public static @Nullable SDKSessionInfo getSessionInfoFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

저장소에서 세션 하나의 메타데이터를 읽습니다. 잘못된 UUID, 없는 세션, 곁가지 세션, 요약을 뽑아낼 수
없는 세션에는 `null`을 반환합니다.

### getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)

```java
public static List<SessionMessage> getSessionMessagesFromStore(
    SessionStore sessionStore, String sessionId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

저장소에서 세션의 전체 대화 트랜스크립트를 읽습니다. 잘못된 UUID나 없는 세션에는 빈 목록을 반환합니다.

### listSubagentsFromStore(SessionStore, String, Path)

```java
public static List<String> listSubagentsFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

`subagents/agent-<id>` 아래의 저장소 하위 키를 열거해 세션의 서브에이전트 ID를 나열합니다.

**던짐**: 저장소가 `listSubkeys()`를 구현하지 않으면 `IllegalStateException`.

### getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)

```java
public static List<SessionMessage> getSubagentMessagesFromStore(
    SessionStore sessionStore, String sessionId, String agentId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

저장소에서 서브에이전트의 트랜스크립트를 읽습니다. 합성된 `agent_metadata` 항목은 메시지로 반환되지
않으며, 각 메시지의 `parentToolUseId`(그 서브에이전트를 만든 Agent `tool_use`)와
`parentAgentId`(중첩 서브에이전트의 경우 만들어 낸 서브에이전트)를 채우기 위해 읽힙니다. 그 항목이
없거나 id가 문자열이 아니면 둘 다 null입니다.

### renameSessionViaStore(SessionStore, String, String, Path)

```java
public static void renameSessionViaStore(
    SessionStore sessionStore, String sessionId, String title,
    @Nullable Path directory)
```

저장소의 해당 세션에 `custom-title` 항목을 덧붙입니다.

**던짐**: `sessionId`가 올바른 UUID가 아니거나 `title`이 비었거나 공백뿐이면
`IllegalArgumentException`.

### tagSessionViaStore(SessionStore, String, String, Path)

```java
public static void tagSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable String tag,
    @Nullable Path directory)
```

`tag` 항목을 덧붙입니다. `tag`에 `null`을 넘기면 지웁니다. 태그는 저장 전에 유니코드 정제를 거칩니다.

**던짐**: 잘못된 UUID나 정제 후 비어 버리는 태그에는 `IllegalArgumentException`.

### deleteSessionViaStore(SessionStore, String, Path)

```java
public static void deleteSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

저장소에서 세션을 삭제합니다. 저장소가 `delete()`를 구현하지 않으면 아무 일도 하지 않습니다(WORM/추가
전용 백엔드에 적절합니다).

### forkSessionViaStore(SessionStore, String, Path, String, String)

```java
public static ForkSessionResult forkSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory,
    @Nullable String upToMessageId, @Nullable String title) throws java.io.IOException
```

저장소를 통해 세션을 새 UUID를 가진 새 분기로 포크합니다. 디스크 포크와 같은 UUID 재매핑 변환을
수행합니다 — 저장 계층의 복사만으로는 **충분하지 않습니다**.

**던짐**: 잘못된 UUID에는 `IllegalArgumentException`. 저장소에서 원본 세션을 찾지 못하면
`FileNotFoundException`.

### importSessionToStore(String, SessionStore, Path)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory)
    throws java.io.IOException
```

로컬 디스크의 세션 트랜스크립트를 `SessionStore`로 재생합니다. `includeSubagents=true`와 기본 배치
크기(`TranscriptMirrorBatcher.MAX_PENDING_ENTRIES = 500`)를 쓰는 편의 오버로드입니다.

### importSessionToStore(String, SessionStore, Path, boolean, int)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory,
    boolean includeSubagents, int batchSize) throws java.io.IOException
```

옵션을 명시한 전체 버전입니다.

**매개변수**:
- `includeSubagents` — `<sessionDir>/subagents/**/*.jsonl`과 `.meta.json` 사이드카를 재귀적으로 가져옴
- `batchSize` — `store.append()` 호출마다의 항목 수. `≤ 0`인 값은 기본값을 사용

**던짐**: 잘못된 UUID에는 `IllegalArgumentException`. 세션 파일을 찾지 못하면 `NoSuchFileException`.

## 버전 메서드

### getVersion()

```java
public static String getVersion()
```

SDK 버전 문자열을 얻습니다.

**반환**: 버전(예: "0.1.3-SNAPSHOT")

## 관련 항목
- [간단한 질의 가이드](./feature-simple-queries.md)
- [MCP 서버 가이드](./feature-mcp-servers.md)
- [세션 히스토리 가이드](./feature-session-history.md)
- [Session Store 가이드](./feature-session-store.md)
