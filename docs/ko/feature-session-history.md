# 세션 히스토리

CLI를 실행하지 않고 과거 Claude Code 대화 세션을 읽고 살펴봅니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-session-history.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [세션 메타데이터](#세션-메타데이터)
- [세션 메시지](#세션-메시지)
- [세션 목록 보기](#세션-목록-보기)
- [단일 세션 조회하기](#단일-세션-조회하기)
- [세션 메시지 읽기](#세션-메시지-읽기)
- [세션 이름 바꾸기](#세션-이름-바꾸기)
- [세션에 태그 달기](#세션에-태그-달기)
- [세션 삭제하기](#세션-삭제하기)
- [세션 포크하기](#세션-포크하기)
- [예제](#예제)
- [모범 사례](#모범-사례)

## 개요

Claude Code는 모든 대화를 `~/.claude/projects/` 아래의 JSONL 파일로 저장합니다. 세션 히스토리 API로
다음을 할 수 있습니다:

- **세션 목록 보기**: 모든 프로젝트를 아우르거나, 특정 작업 디렉터리로 걸러서
- **메시지 읽기**: 과거 어떤 세션에서든 대화 트랜스크립트 전체를

모든 읽기는 CLI와 무관하게 디스크에서 곧바로 이루어집니다. 프로세스는 생성되지 않습니다.

**성능:** 목록을 만들 때는 각 세션 파일의 앞뒤 64 KB만 읽습니다(JSONL 전체를 파싱하지 않습니다).
완전한 파싱은 `getSessionMessages`로 메시지를 가져올 때만 수행됩니다.

> **원격/다중 호스트 백엔드를 찾고 계신가요?** [Session Store](./feature-session-store.md)를
> 참고하세요. 이 페이지의 모든 메서드에는 `SessionStore` 어댑터(S3, Postgres, Redis, 사용자 구현)를
> 상대로 동작하는 `*FromStore`(읽기) 또는 `*ViaStore`(변경) 대응물이 `ClaudeSDK`에 있습니다. 여기서
> 설명하는 로컬 디스크 API가 여전히 표준 경로이며, SessionStore API는 추가적인 것으로 이식성을 위해
> 같은 디스크 배치를 대상으로 합니다.

## 세션 메타데이터

`SDKSessionInfo`는 세션 하나의 메타데이터를 담습니다:

```java
record SDKSessionInfo(
    String sessionId,              // UUID identifying the session
    String summary,                // display title (custom title, AI title, lastPrompt, summary, or first prompt)
    long lastModified,             // last-modified time in milliseconds since epoch
    @Nullable Long fileSize,       // session file size in bytes (null for remote storage backends)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

`summary` 필드는 다음 우선순위로 결정됩니다: 사용자 지정 제목 > AI 제목 > lastPrompt > 자동 생성 요약
> 첫 프롬프트.

`tag`와 `createdAt`이 없는 하위 호환 생성자도 있습니다.

## 세션 메시지

`SessionMessage`는 세션 트랜스크립트의 메시지 하나를 담습니다:

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId,    // spawning Agent tool_use id (subagent reads only)
    @Nullable String parentAgentId       // spawning subagent id (nested subagents only)
)
```

최상위 대화 메시지만 반환됩니다 — 도구 사용의 곁가지 메시지, 메타 메시지, 서브에이전트 메시지는
걸러집니다.

`getSessionMessages()` / `getSessionMessagesFromStore()` 결과에서는 `parentToolUseId`와
`parentAgentId`가 항상 null입니다. 이들이 채워지는 것은 `getSubagentMessages()` /
`getSubagentMessagesFromStore()`의 경우로, `parentToolUseId`는 그 서브에이전트를 만들어 낸 부모
세션의 Agent `tool_use` 블록 id이고, `parentAgentId`는 한 서브에이전트가 다른 서브에이전트를 만들었을
때 그 생성자를 가리킵니다. 둘 다 해당 서브에이전트의 `agent-<agentId>.meta.json` 사이드카(저장소
읽기에서는 그것을 대신하는 `agent_metadata` 항목)에서 오므로, 그 메타데이터가 없거나 쓸 수 없으면
둘 다 null입니다. 특정 서브에이전트 트랜스크립트의 모든 메시지는 같은 한 쌍의 값을 갖습니다.

### 메시지 내용에 접근하기

`message` 필드는 Anthropic API 와이어 형식에 대응하는 원본 `Map<String, Object>`입니다:

```java
SessionMessage msg = ...;
if (msg.message() instanceof Map<?, ?> m) {
    Object content = m.get("content");
    if (content instanceof String text) {
        System.out.println(text);
    } else if (content instanceof List<?> blocks) {
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

## 세션 목록 보기

### 모든 세션

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

모든 프로젝트의 세션을 가장 최근에 수정된 순서로 반환합니다.

### 특정 프로젝트의 세션

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

작업 디렉터리가 `projectDir`와 일치하는 세션만 걸러냅니다.

### 개수 제한과 worktree

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true`이면 `git worktree list`를 실행해 저장소의 모든 worktree에 있는 세션까지
포함합니다.

### 오프셋 페이지네이션

```java
// Page 1: first 50 sessions
List<SDKSessionInfo> page1 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 0, true);

// Page 2: next 50 sessions
List<SDKSessionInfo> page2 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 50, true);
```

## 단일 세션 조회하기

`getSessionInfo`를 쓰면 모든 세션 파일을 훑지 않고 ID로 세션 하나를 조회할 수 있습니다. 세션 UUID를
이미 알고 있다면 `listSessions`보다 효율적입니다.

### 세션 ID로(모든 프로젝트 검색)

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo("550e8400-e29b-41d4-a716-446655440000");
if (info != null) {
    System.out.println("Session: " + info.summary());
    if (info.tag() != null) {
        System.out.println("Tag: " + info.tag());
    }
    if (info.createdAt() != null) {
        String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(info.createdAt()));
        System.out.println("Created: " + created);
    }
}
```

### 프로젝트 디렉터리로 한정하기

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);
```

세션을 찾지 못했거나, 곁가지 세션이거나, 요약을 뽑아낼 수 없으면 `null`을 반환합니다.

## 세션 메시지 읽기

### 전체 트랜스크립트

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

모든 프로젝트 디렉터리에서 그 세션 UUID를 찾습니다.

### 프로젝트로 한정하기

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### 페이지네이션과 함께

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

## 세션 이름 바꾸기

세션의 JSONL 파일에 사용자 지정 제목 항목을 덧붙여 이름을 바꿉니다. 가장 최근의 이름 변경이 항상
이기므로 여러 번 호출해도 안전합니다.

```java
// Rename by session ID (searches all projects)
ClaudeSDK.renameSession("550e8400-e29b-41d4-a716-446655440000", "My Feature Branch Session");

// Rename scoped to a specific project directory
ClaudeSDK.renameSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "My Feature Branch Session",
    Path.of("/my/project")
);
```

**제약:**
- `sessionId`는 올바른 UUID(소문자 16진수와 하이픈)여야 합니다.
- `title`은 앞뒤 공백을 제거한 뒤 비어 있으면 안 됩니다.
- 세션 JSONL 파일을 찾지 못하면 `FileNotFoundException`을 던집니다.
- 파일 쓰기에 실패하면 `IOException`을 던집니다.

이름을 바꾸면 `listSessions()`가 `SDKSessionInfo`의 `summary`와 `customTitle` 필드에 새 제목을
반환합니다.

## 세션에 태그 달기

정리와 필터링을 위해 세션에 태그를 답니다. 기존 태그를 지우려면 `null`을 넘기세요. 태그는 CLI 필터와
호환되도록 저장 전에 유니코드 정제를 거칩니다.

```java
// Tag a session (searches all projects)
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", "production");

// Clear a tag
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", null);

// Tag scoped to a specific project directory
ClaudeSDK.tagSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "staging",
    Path.of("/my/project")
);
```

**제약:**
- `sessionId`는 올바른 UUID여야 합니다.
- `tag`는 유니코드 정제와 공백 제거 후 비어 있으면 안 됩니다(지우려면 `null`).
- 위험한 유니코드 문자(폭 없는 문자, 방향 표시, 사용자 영역 문자)를 담은 태그는 자동으로 정제됩니다.
- 세션 JSONL 파일을 찾지 못하면 `FileNotFoundException`을 던집니다.
- 파일 쓰기에 실패하면 `IOException`을 던집니다.

**동시성 안전성:** 그 세션이 현재 CLI 프로세스에서 열려 있다면, CLI는 다음에 메타데이터를 다시 덧붙일
때 SDK가 쓴 항목을 캐시로 흡수합니다. 가장 최근의 쓰기가 이깁니다.

## 세션 삭제하기

JSONL 파일을 지워 세션을 영구히 삭제합니다. 서브에이전트 트랜스크립트를 담은 형제 디렉터리
`<sessionId>/`도 재귀적으로 제거됩니다(최선 노력이며, 없어도 괜찮습니다).

```java
// Delete by session ID (searches all projects)
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000");

// Delete scoped to a specific project directory
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000", Path.of("/my/project"));
```

**제약:**
- `sessionId`는 올바른 UUID여야 합니다.
- 세션 파일을 찾지 못하면 `FileNotFoundException`을 던집니다.
- 소프트 삭제 의미가 필요하면 대신 `tagSession(id, "__hidden")`을 쓰고 목록에서 걸러내세요.

## 서브에이전트 트랜스크립트 읽기

세션이 (`Task` 도구나 프로그래밍 방식 에이전트 정의로) 서브에이전트를 만들면, 각 서브에이전트는 자신의
트랜스크립트를 `~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`에 씁니다.
서브에이전트 트랜스크립트는 `subagents/workflows/<runId>/` 같은 중첩 디렉터리에 놓일 수도 있습니다.

```java
// Enumerate subagent IDs for a session
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000");

// Or scoped to a specific project
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project"));

// Read a subagent's full conversation
List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123");

// With limit and offset
List<SessionMessage> page = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123",
    Path.of("/my/project"),
    50,    // limit (null or 0 = no limit)
    0);    // offset
```

**동작:**
- `listSubagents`는 `subagents/` 트리를 재귀적으로 훑어 `agent-<id>.jsonl`에 맞는 파일을 찾고,
  디렉터리 순회 순서대로 ID를 반환합니다.
- `getSubagentMessages`는 잎에서부터 `parentUuid` 링크를 따라가 사슬을 재구성합니다. 서브에이전트
  트랜스크립트는 선형이므로(압축도 곁가지도 없음) 반환되는 목록이 시간순의 완전한 대화입니다.
- 손상된 JSONL 줄은 조용히 건너뜁니다.
- 잘못된 UUID, 없는 세션, 없는 에이전트, 빈 에이전트 ID는 모두 빈 목록을 반환합니다(예외를 던지지
  않습니다).

## 세션 포크하기

세션을 새 UUID를 가진 새 분기로 포크합니다. 원본 세션의 트랜스크립트 메시지를 복사하면서 각 메시지의
UUID를 다시 매기고 `parentUuid` 사슬을 보존합니다. 포크된 세션에는 실행 취소 기록이 없습니다.

```java
// Fork a session (searches all projects)
ForkSessionResult result = ClaudeSDK.forkSession("550e8400-e29b-41d4-a716-446655440000");
System.out.println("New session: " + result.sessionId());

// Fork scoped to a project directory
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);

// Fork from a specific message (truncate transcript)
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    null,                                       // search all projects
    "660e8400-e29b-41d4-a716-446655440001",    // slice transcript at this message
    "My Fork Title"                            // custom title (null = original + " (fork)")
);
```

**`ForkSessionResult`**가 담는 것:
- `sessionId` — 새로 만들어진 포크 세션의 UUID

**제약:**
- `sessionId`와 선택적인 `upToMessageId`는 올바른 UUID여야 합니다.
- 원본 세션을 찾지 못하면 `FileNotFoundException`을 던집니다.
- 세션에 메시지가 없거나 `upToMessageId`를 찾지 못하면 `IllegalArgumentException`을 던집니다.

`forkSession()`은 CLI를 실행하지 않고 오프라인 사본을 만듭니다. 더 이른 지점에서 *재개해* 대화를
이어 가려면 대신 잘라내기 재개를 사용하세요.

## 잘라내기 재개

`resumeSessionAt`은 재개하는 대화를 주어진 트랜스크립트 항목 UUID까지만(해당 항목 포함) 불러오고 그
뒤는 모두 버립니다. `forkSession(true)`와 함께 쓰면 새 세션으로 분기하고 원본은 손대지 않습니다.

`resumeDropsTurn`은 그 잘라내기를 **안전하게** 만듭니다. 버리려는 턴의 사용자 프롬프트 UUID를 주면,
CLI가 불러오는 시점에 분기점 이후의 모든 항목이 그 턴에 속하는지 검증하고, 아니면 거부합니다. 이것이
없으면 세션이 턴 도중에 흡수했지만 여러분이 보지 못한 대기 중인 사용자 메시지나 백그라운드 작업 알림이
조용히 사라집니다.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(projectDir)
    .resume(sessionId)
    .forkSession(true)               // branch; leave the source session intact
    .resumeSessionAt(keepAtUuid)     // last transcript entry of the turn to keep
    .resumeDropsTurn(nextPromptUuid) // prompt UUID of the turn being discarded
    .build();

for (Message msg : ClaudeSDK.query("Reply with exactly: three", options)) {
    if (msg instanceof ResultMessage r) {
        System.out.println("Forked session: " + r.sessionId());
    }
}
```

**두 UUID 고르기.** `resumeSessionAt`에는 남기려는 턴의 *마지막* 트랜스크립트 항목을(형식은 무엇이든)
두고, `resumeDropsTurn`에는 바로 그다음 턴의 프롬프트 UUID를 둡니다. 둘 다
`ClaudeSDK.getSessionMessages(sessionId, cwd)`에서 읽을 수 있습니다. 남기고 싶은 항목을 찾은 뒤,
`type()`이 `"user"`인 다음 `SessionMessage`의 `uuid()`를 가져오세요. 실시간으로 관찰한
`AssistantMessage.uuid()`도 분기점으로 쓸 수 있습니다.

구조화 출력(`outputFormat`)이나 턴을 끝내는 MCP 도구를 쓰는 경우, 남기는 턴은 마지막 어시스턴트 메시지
*이후의* 항목에서 끝납니다 — 그래서 그런 경우에는 어시스턴트 UUID에서 포크하는 것이 설계상 거부됩니다.

**거부 처리하기.** 거부는 메시지에 `Resume rejected by --resume-drops-turn:`이 들어 있는 예외로
도착합니다:

```java
try {
    ClaudeSDK.query(prompt, options);
} catch (ClaudeSDKException e) {
    if (String.valueOf(e.getMessage()).contains("Resume rejected by --resume-drops-turn:")) {
        // Deterministic — the transcript is not what we assumed.
        // Clear the fork target and resume plainly; do not retry as-is.
    }
}
```

이는 결정론적인 결과로 다루세요. 똑같은 요청을 다시 보내면 똑같이 실패합니다. 예전의, 검증 없는
잘라내기 동작을 유지하려면 `resumeDropsTurn`을 설정하지 마세요.

두 옵션 모두 `SessionStore`에서 재개할 때도 전달됩니다. 실행 가능한 전 과정 시연은
[`TruncatingResumeExample`](../../examples/src/main/java/examples/TruncatingResumeExample.java)을
참고하세요.

## 예제

### 예제 1: 최근 세션 목록 보기

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

for (SDKSessionInfo session : sessions) {
    String time = fmt.format(Instant.ofEpochMilli(session.lastModified()));
    System.out.printf("[%s] %s%n", time, session.summary());
    System.out.printf("  id:  %s%n", session.sessionId());
    if (session.cwd() != null) {
        System.out.printf("  cwd: %s%n", session.cwd());
    }
    if (session.gitBranch() != null) {
        System.out.printf("  git: %s%n", session.gitBranch());
    }
}
```

### 예제 2: 현재 프로젝트의 세션

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    String sizeStr = (session.fileSize() != null)
            ? String.format("%.1f KB", session.fileSize() / 1024.0) : "N/A";
    System.out.printf("  %s (%s)%n", session.summary(), sizeStr);
}
```

### 예제 3: 가장 최근 세션의 메시지 읽기

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (sessions.isEmpty()) {
    System.out.println("No sessions found.");
    return;
}

SDKSessionInfo recent = sessions.get(0);
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(recent.sessionId());

System.out.printf("Session: %s (%d messages)%n",
    recent.summary(), messages.size());

for (SessionMessage msg : messages) {
    System.out.printf("%n[%s]%n", msg.type().toUpperCase());
    if (msg.message() instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content instanceof String text) {
            System.out.println(text);
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = ((List<?>) blocks).get(0);
            if (first instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

### 예제 4: 프롬프트 키워드로 세션 찾기

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### 예제 5: 가장 최근 세션 이름 바꾸기

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### 예제 6: 프로젝트 단계별로 세션에 태그 달기

```java
// Tag a session after a query completes, using the result's session ID
List<Message> messages = ClaudeSDK.query(prompt, options);
for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        ClaudeSDK.tagSession(result.sessionId(), "sprint-42");
        break;
    }
}
```

### 예제 7: 태그 지우기

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## 모범 사례

### 가능하면 디렉터리로 걸러내기

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### 빈 결과 확인하기

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### CLAUDE_CONFIG_DIR가 없을 때 다루기

기본적으로 세션은 `~/.claude/projects/` 아래에 저장됩니다. 환경 변수 `CLAUDE_CONFIG_DIR`가 설정되어
있으면 그 위치가 우선합니다. SDK는 이 변수를 자동으로 존중합니다.

### limit으로 지나치게 큰 결과 피하기

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## 관련 항목

- [API 레퍼런스: ClaudeSDK](./api-claude-sdk.md#세션-히스토리-메서드) — 메서드 시그니처
- [Session Store](./feature-session-store.md) — 외부 저장소 기반 대응물(`*FromStore`/`*ViaStore`)과 쓰기 시 미러링 통합
- [세션 목록 예제](../../examples/src/main/java/examples/SessionListingExample.java) — 실행 가능한 전체 예제
- [대화형 세션](./feature-interactive-conversations.md) — 진행 중인 세션 관리하기
