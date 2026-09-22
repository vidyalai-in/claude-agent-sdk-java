# Session Store (외부 트랜스크립트 미러링)

Claude Code 세션 트랜스크립트를 외부 저장소(S3, Postgres, Redis, 직접 만든 백엔드)로 미러링해, 세션이
로컬 디스크를 넘어 오래 남고 어디서든 재개될 수 있게 합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-session-store.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [SessionStore를 쓸 때](#sessionstore를-쓸-때)
- [빠른 시작](#빠른-시작)
- [SessionStore 인터페이스](#sessionstore-인터페이스)
- [동기 API와 비동기 API](#동기-api와-비동기-api)
- [비동기 실행기 구성하기](#비동기-실행기-구성하기)
- [기본 제공 참조 어댑터](#기본-제공-참조-어댑터)
- [SessionStore 기반 읽기 API](#sessionstore-기반-읽기-api)
- [SessionStore 기반 변경 작업](#sessionstore-기반-변경-작업)
- [저장소에서 재개하기](#저장소에서-재개하기)
- [미러 오류](#미러-오류)
- [플러시 모드 (BATCHED 대 EAGER)](#플러시-모드-batched-대-eager)
- [로컬 세션을 저장소로 가져오기](#로컬-세션을-저장소로-가져오기)
- [적합성 테스트 하네스](#적합성-테스트-하네스)
- [내부 런타임 구성 요소](#내부-런타임-구성-요소)
- [모범 사례](#모범-사례)
- [API 레퍼런스](#api-레퍼런스)

## 개요

기본적으로 Claude Code CLI는 모든 세션을 `~/.claude/projects/` 아래의 JSONL 파일로 씁니다. SDK는 여기에
더해 트랜스크립트의 모든 줄을 여러분이 고른 외부 저장소로 미러링할 수 있습니다 — 다음과 같은 경우에
유용합니다:

- **오래 도는 세션의 내구성** — 서버리스/오토스케일링 플랫폼에서 로컬 디스크는 일시적입니다.
- **다중 호스트 재개** — 호스트 A에서 시작한 세션을 호스트 B에서 재개합니다.
- **감사/규정 준수 보존** — 직접 정한 TTL 정책을 적용합니다(S3 라이프사이클, Postgres 파티션, Redis TTL).
- **멀티테넌트 배포** — `project_key`로 트랜스크립트 범위를 나눠 테넌트를 격리합니다.

SDK가 제공하는 것:

- `SessionStore` 인터페이스(동기 + 비동기 변형)
- `InMemorySessionStore` 참조 어댑터
- 런타임 미러 통합(투명함 — options에 `sessionStore`를 설정하면 나머지는 SDK가 처리)
- 기존 디스크 세션을 옮기기 위한 `importSessionToStore()`

로컬 디스크 트랜스크립트는 언제나 먼저 기록됩니다. 미러링은 부차적인 내구성 경로입니다. 미러 실패가
세션을 막는 일은 결코 없습니다 — 치명적이지 않은 `MirrorErrorMessage`로 드러납니다.

## SessionStore를 쓸 때

| 시나리오 | 권장 |
|---|---|
| 워크스테이션의 단일 사용자 CLI | 필요 없음 — 로컬 JSONL로 충분 |
| 오래 도는 서버, 세션이 여러 요청에 걸침 | `SessionStore` 사용 |
| 규정 준수/규제된 보존 | 네이티브 라이프사이클 정책이 있는 `SessionStore` 사용 |
| 다중 호스트 클러스터/클라우드 오토스케일링 | 어느 호스트에서도 재개할 수 있도록 `SessionStore` 사용 |
| 많은 세션에 걸친 감사/재생 | 중앙에서 질의하기 위해 `SessionStore` 사용 |

## 빠른 시작

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)            // mirror every transcript line here
    .build();

ClaudeSDK.query("Hello!", options);
// All transcript entries from this turn are now in `store`
```

SDK는 CLI 호출에 `--session-mirror`를 덧붙이고, CLI의 stdout에서 `transcript_mirror` 프레임을 떼어내어
배치로 `store.append(...)`에 전달합니다.

다른 호스트에서 저장소로부터 재개하려면:

```java
ClaudeAgentOptions resumeOptions = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume("previous-session-uuid")
    .build();

ClaudeSDK.query("Continue where we left off", resumeOptions);
```

SDK는 저장된 트랜스크립트를 임시 `CLAUDE_CONFIG_DIR`에 불러와 CLI 서브프로세스가 대화를 이어받게 합니다.

## SessionStore 인터페이스

`in.vidyalai.claude.sdk.types.session.SessionStore`는 Java 인터페이스입니다. 두 메서드가 필수이고,
나머지는 선택이며 `implements*()` 탐지 플래그가 있어 호출자가 `instanceof` 없이 무엇이 지원되는지 알 수
있습니다.

### 필수 메서드

```java
void append(SessionKey key, List<SessionStoreEntry> entries);

@Nullable
List<SessionStoreEntry> load(SessionKey key);
```

- `append` — 트랜스크립트 항목 배치를 미러링합니다. 로컬 디스크 쓰기가 성공한 *뒤에* 호출되므로 내구성은
  이미 로컬에서 보장됩니다. 어댑터는 `entry.uuid()`를 멱등 키로 다뤄야 합니다(사용자 지정 제목이나
  태그처럼 `uuid`가 없는 항목은 중복 제거 없이 덧붙여야 합니다).
- `load` — 그 키의 모든 항목을 반환합니다(덧붙인 것과 깊이 같아야 하며, 바이트 단위로 같을 필요는
  없습니다). 한 번도 쓰인 적 없는 키에는 `null`을 반환합니다.

### 선택 메서드(기본은 `UnsupportedOperationException` 던지기)

```java
default List<SessionStoreListEntry> listSessions(String projectKey);
default List<SessionSummaryEntry> listSessionSummaries(String projectKey);
default void delete(SessionKey key);
default List<String> listSubkeys(SessionListSubkeysKey key);
```

### 기능 탐지

```java
default boolean implementsListSessions() { return false; }
default boolean implementsListSessionSummaries() { return false; }
default boolean implementsDelete() { return false; }
default boolean implementsListSubkeys() { return false; }
```

해당하는 선택 메서드를 구현했다면 이들을 재정의해 `true`를 반환하세요. SDK는 (`try/catch`가 아니라) 이
탐지를 사용해 선택 메서드를 호출할지 결정합니다.

### 키 타입

```java
public record SessionKey(
    String projectKey,             // caller-defined scope (default: sanitized cwd)
    String sessionId,              // session UUID
    @Nullable String subpath        // null for main; "subagents/agent-x" for subagent
);

public record SessionListSubkeysKey(String projectKey, String sessionId);

public record SessionStoreListEntry(String sessionId, long mtime);

public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data);
```

`SessionStoreEntry`는 `Map<String, Object>`를 감싼 얇은 래퍼로 `type` 필드를 요구하며, 나머지는 불투명한
그대로 전달됩니다:

```java
SessionStoreEntry entry = SessionStoreEntry.of(Map.of(
    "type", "user",
    "uuid", "u1",
    "message", Map.of(
        "content", List.of(Map.of("type", "text", "text", "Hello"))),
    "timestamp", "2026-04-27T00:00:00Z"
));

entry.type();      // "user"
entry.uuid();      // "u1"
entry.timestamp(); // "2026-04-27T00:00:00Z"
entry.<String>get("custom_field"); // typed convenience accessor
entry.asMap();     // unmodifiable map view
```

## 동기 API와 비동기 API

`SessionStore`의 모든 메서드에는 동기 버전과 `*Async` 버전(`CompletableFuture` 반환)이 있습니다:

```java
// Sync (required to implement; or default to *Async().join() if you only override async)
void append(SessionKey key, List<SessionStoreEntry> entries);

// Async with default executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries);

// Async with explicit executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor);
```

내부의 미러 배처와 재개 구체화기는 `*Async` 버전을 호출합니다 — 그래서 네이티브 논블로킹 클라이언트를
쓰는 어댑터(AWS SDK v2 async, R2DBC, Lettuce reactive)는 `*Async` 메서드를 재정의해 끝에서 끝까지 병렬성을
유지할 수 있습니다.

### 기본 위임

- **동기** 메서드만 재정의하면(JDBC, Jedis, 블로킹 S3 SDK v1에서 흔한 경우), `*Async` 기본 구현이 설정된
  실행기 위에서 `CompletableFuture.supplyAsync(...)`로 여러분의 동기 호출을 감쌉니다(작업마다 스레드
  하나, Java 21+에서는 가상 스레드).
- **비동기** 메서드만 재정의한다면(AWS SDK v2 async / Lettuce reactive / R2DBC에 권장), 동기 메서드는
  `appendAsync(key, entries).join()`으로 구현해 두 호출 지점이 모두 동작하게 하세요.

```java
public class S3AsyncStore implements SessionStore {
    private final S3AsyncClient s3;

    @Override
    public void append(SessionKey key, List<SessionStoreEntry> entries) {
        appendAsync(key, entries).join();
    }

    @Override
    public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        // Native async — no thread hop
        return s3.putObject(/* ... */).thenApply(r -> null);
    }

    @Override
    public List<SessionStoreEntry> load(SessionKey key) { /* ... */ }
}
```

## 비동기 실행기 구성하기

기본적으로 비동기 래퍼는 **작업마다 스레드 하나**로, `session-store-<n>`이라는 이름의 스레드에서
실행됩니다. Java 21+에서는 가상 스레드이고, Java 17-20에서는 SDK가 무제한 캐시 풀의 데몬 플랫폼 스레드로
물러납니다. SDK는 Java 17을 목표로 하면서 런타임에 더 나은 쪽을 고르므로, 가상 스레드를 강제하지 않고도
그 이점을 누립니다.

시작할 때 `SessionStoreExecutor`로 한 번 덮어쓸 수 있습니다:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

// Your own virtual-thread executor (needs Java 21+ in *your* project)
ExecutorService mine = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("my-store-", 0).factory());
SessionStoreExecutor.setDefault(mine);

// Or a bounded platform-thread pool
SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));

// Reset to the built-in default
SessionStoreExecutor.reset();
```

호출마다 실행기를 넘길 수도 있습니다:

```java
store.appendAsync(key, entries, customExecutor)
```

설정된 실행기는 명시적 실행기를 받지 않는 모든 `*Async` 기본 구현이 사용합니다. `*Async`를 직접 재정의한
어댑터는 이를 완전히 우회합니다 — 실행기는 동기→비동기 래핑 경로에만 적용됩니다.

## 기본 제공 참조 어댑터

### `InMemorySessionStore`

테스트와 프로토타이핑에 적합한, 스레드 안전한 인메모리 구현입니다:

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

// All optional methods implemented
store.implementsListSessions();         // true
store.implementsListSessionSummaries(); // true
store.implementsDelete();               // true
store.implementsListSubkeys();          // true

// Test helpers
store.size();             // count of main-transcript sessions
store.snapshotSummaries();// LinkedHashMap snapshot of summary sidecars
store.clear();            // wipe everything
```

`append()` 안에서 증분 `SessionSummaryEntry` 사이드카를 유지하므로 `listSessionSummaries()`가 O(1)로
동작하며, 트랜스크립트를 다시 읽지 않습니다.

### 경로 → 키 헬퍼

`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)`는 디스크의 트랜스크립트 경로를 다시
`SessionKey`로 매핑하는 정적 헬퍼입니다:

```java
SessionKey k = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123.jsonl",
    "/home/u/.claude/projects");
// k = SessionKey("myproj", "abc-123", null)

SessionKey sub = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123/subagents/agent-x.jsonl",
    "/home/u/.claude/projects");
// sub = SessionKey("myproj", "abc-123", "subagents/agent-x")
```

`projectsDir` 바깥의 경로나 알아볼 수 없는 배치에는 `null`을 반환합니다. 미러 배처가 내부적으로
사용하며, 같은 매핑이 필요한 어댑터 구현을 위해 공개되어 있습니다.

### 운영용 어댑터 (S3, Redis, Postgres 등)

SDK는 운영용 어댑터를 함께 제공하지 않습니다 — 그런 것들은 무거운 클라이언트 라이브러리(AWS SDK,
Lettuce, JDBC, R2DBC)에 의존하는데, 이를 전이 의존성으로 들이고 싶지 않기 때문입니다. 직접 구현하고
`SessionStoreConformance`(아래 참고)로 검증하세요. 프로토콜은 작고 안정적입니다.

## SessionStore 기반 읽기 API

CLI를 거치지 않고 저장소에서 바로 세션을 읽습니다:

```java
import in.vidyalai.claude.sdk.ClaudeSDK;

// List all sessions in the store for the current cwd
List<SDKSessionInfo> sessions =
    ClaudeSDK.listSessionsFromStore(store, /* directory */ null, /* limit */ 50, /* offset */ 0);

// Single-session metadata
SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);

// Full transcript
List<SessionMessage> messages =
    ClaudeSDK.getSessionMessagesFromStore(store, sessionId, null, null, 0);

// Subagent transcript discovery + reading
List<String> agentIds = ClaudeSDK.listSubagentsFromStore(store, sessionId, null);
List<SessionMessage> subAgent =
    ClaudeSDK.getSubagentMessagesFromStore(store, sessionId, agentIds.get(0), null, null, 0);

// Each message is attributed to the Agent tool_use that spawned the subagent,
// read from the mirrored `agent_metadata` entry (null if it is absent).
String spawnedBy = subAgent.get(0).parentToolUseId();
```

저장소가 `listSessionSummaries`를 구현하면 `listSessionsFromStore`에 빠른 경로가 있습니다. 한 번의 배치
요약 호출과 값싼 `listSessions` 열거로, 사이드카가 없거나 오래된 세션만 메웁니다.
`listSessionSummaries`가 구현되지 않았다면 세션마다 한 번의 `loadAsync()`로 물러나되, **동시 호출을
16개로 제한**합니다(Python SDK와 동일). 큰 프로젝트를 나열할 때 어댑터의 커넥션 풀이 고갈되지 않도록
하기 위함입니다.

`listSessions`와 `listSessionSummaries`가 둘 다 구현되지 않았다면 이 호출은
`IllegalStateException`을 던집니다. 어댑터의 `loadAsync` 실패는 목록 전체를 실패시키는 대신 해당 행만 빈
요약 항목으로 낮춥니다.

## SessionStore 기반 변경 작업

디스크 변경 API와 모양은 같지만, 저장소에 씁니다:

```java
ClaudeSDK.renameSessionViaStore(store, sessionId, "My New Title", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, "important", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, null, null);   // clear tag
ClaudeSDK.deleteSessionViaStore(store, sessionId, null);

ForkSessionResult fork = ClaudeSDK.forkSessionViaStore(
    store, sessionId, /* directory */ null,
    /* upToMessageId */ null,                  // null copies full transcript
    /* title */ "My Fork");
```

내부 동작:

- `renameSessionViaStore`는 `custom-title` 항목을 덧붙입니다.
- `tagSessionViaStore`는 `tag` 항목을 덧붙이며, `null`은 빈 문자열로 지웁니다.
- 저장소가 `delete()`를 구현하지 않으면 `deleteSessionViaStore`는 아무 일도 하지 않습니다(원시 S3 같은
  WORM/추가 전용 백엔드에 적절합니다).
- `forkSessionViaStore`는 디스크 포크와 같은 UUID 재매핑 변환을 수행합니다(공통
  `SessionMutations.buildForkLines`) — 저장 계층의 복사만으로는 **충분하지 않습니다**.

`listSubagentsFromStore`는 `listSubkeys()`를 필요로 하며, 없으면 `IllegalStateException`을 던집니다.

## 저장소에서 재개하기

`options.sessionStore`가 `options.resume`(또는 `options.continueConversation`)과 함께 설정되면 SDK는
다음을 합니다:

1. 요청된 세션 ID로 `store.load()`를 호출합니다(`continueConversation`의 경우
   `store.listSessions()`로 곁가지가 아닌 세션 중 가장 최근에 수정된 것을 고릅니다).
2. 항목들을 `~/.claude/`와 똑같이 배치된 임시 디렉터리에 씁니다.
3. 서브프로세스가 인증하고 평소처럼 동작할 수 있도록 실제 설정 디렉터리에서 임시 디렉터리에 씨앗을
   뿌립니다 — `.credentials.json`(`refreshToken`은 제거), `.claude.json`, 그리고 사용자
   `settings.json` / `cowork_settings.json`. [무엇이 뿌려지는가](#무엇이-뿌려지는가)를 참고하세요.
4. 저장소에서 서브에이전트 트랜스크립트와 `.meta.json` 사이드카를 구체화합니다(`listSubkeys`가 구현된
   경우).
5. `CLAUDE_CONFIG_DIR=<temp dir>`로 CLI를 띄워 평소처럼 로컬 디스크에서 재개하게 합니다.
6. 연결을 끊을 때 임시 디렉터리를 정리합니다(Windows 백신/색인기의 일시적 잠금에는 재시도).

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume(previousSessionId)
    .loadTimeoutMs(60_000)        // per-call timeout for store.load() / listSubkeys()
    .build();
```

`loadTimeoutMs` 옵션(기본 60 000)은 구체화 중 개별 저장소 호출마다 상한을 둡니다. 어댑터가 이 시간 안에
끝나지 않으면, 이터레이터를 매달아 두는 대신 분명한 오류와 함께 질의가 빠르게 실패합니다.

### 무엇이 뿌려지는가

서브프로세스는 방향이 바뀐 `CLAUDE_CONFIG_DIR` 아래에서 돌기 때문에, 그대로 두면 여러분의 설정을 전혀
보지 못합니다. SDK는 호출자의 설정 디렉터리에서 네 파일을 복사합니다 — 설정 디렉터리는
`options.env["CLAUDE_CONFIG_DIR"]` → 프로세스 환경 → `~/.claude` 순으로 결정됩니다(설정되어 있으면
`.claude.json`은 `$CLAUDE_CONFIG_DIR/.claude.json`에 있고, 아니면 `~/.claude.json`이며
*`~/.claude/.claude.json`이 아닙니다*):

| 파일 | 왜 중요한가 |
|------|----------------|
| `.credentials.json` | OAuth 자격 증명, `claudeAiOauth.refreshToken`은 제거됨 |
| `.claude.json` | 사용자 수준 CLI 상태 |
| `settings.json` | `apiKeyHelper`, 그리고 여러분의 `env`, `hooks`, `permissions` |
| `cowork_settings.json` | cowork-plugins 모드에서 읽는 대체 설정 파일 이름 |

`settings.json`을 뿌리는 것은 보기보다 중요합니다. `apiKeyHelper`는 자격 증명 파일, macOS 키체인, 환경
변수와 나란히 놓인 네 번째 인증 수단입니다. v0.1.23 이전에는 복사되지 않아, `apiKeyHelper`만으로 인증하던
호스트가 저장소에서 재개하는 순간 **"Not logged in"**으로 실패했습니다.

두 설정 파일 모두, 방향이 바뀐 설정 디렉터리 아래에서 오작동하는 키만 떨어뜨리는 변환을 거칩니다:

- `enabledPlugins`와 `extraKnownMarketplaces` — 이들은 언제나 비어 있는 임시 플러그인 캐시와 대조되며,
  재개할 때마다 선언된 모든 마켓플레이스를 네트워크로 설치하게 만듭니다.
- `env.CLAUDE_CONFIG_DIR` — 서브프로세스의 설정 읽기를 임시 디렉터리 밖으로 되돌려 버립니다.

나머지는 모두 보존됩니다. UTF-8 BOM(PowerShell이 붙입니다)은 허용되며, 유효한 UTF-8이 아니거나 JSON
객체로 파싱되지 않는 내용은 서브프로세스가 CLI가 읽었을 것과 똑같은 것을 보도록 바이트 단위로 복사됩니다.
파일은 소유자 전용(`0700`) 임시 디렉터리 안에 소유자 전용(`0600`)으로 기록됩니다.

씨앗 뿌리기는 최선 노력입니다. "없음" 외의 이유로 읽을 수 없는 파일 — 권한 오류, 또는 파일이 있어야 할
자리의 디렉터리나 FIFO — 은 그렇지 않았다면 성공했을 재개를 중단시키는 대신 기록하고 건너뜁니다. 도중에
실패한 복사는 서브프로세스가 잘린 파일을 잘못 해석하지 않도록 부분적인 대상 파일을 지웁니다.

### 검증 가드

서브프로세스 작업을 시작하기 전에 SDK가 잘못된 조합을 거부합니다:

- `continueConversation + sessionStore`에는 `store.implementsListSessions()`가 필요합니다.
- `sessionStore + enableFileCheckpointing`은 거부됩니다 — 체크포인트는 로컬 디스크 전용이라 미러링된
  트랜스크립트와 어긋나게 됩니다.

이들은 즉시 `IllegalArgumentException`을 던집니다.

## 미러 오류

미러 추가 실패는 치명적이지 않습니다 — 로컬 디스크 트랜스크립트는 이미 안전하므로 세션은 영향 없이
계속됩니다. SDK는 각 배치를 `[200ms, 800ms]` 백오프로 최대 3회 재시도한 뒤 버리고, 여러분의 메시지
스트림에 `MirrorErrorMessage`를 내보냅니다:

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query("Hello", options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            // Non-fatal — log and consider importing the local file later
            System.err.println("Mirror error for " + (err.key() != null
                    ? err.key().sessionId() : "<unknown>")
                    + ": " + err.error());
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { /* ignore */ }
    }
}
```

`MirrorErrorMessage`는 `Message` sealed 인터페이스의 구성원입니다(`AssistantMessage`,
`SystemMessage` 등과 나란히). `subtype`은 언제나 `"mirror_error"`, `error`는 실패 메시지, `key`(null
가능)는 실패한 배치가 겨냥하던 `SessionKey`입니다.

타임아웃은 재시도하지 **않습니다**(진행 중인 호출이 나중에 도착할 수 있어, 재시도하면 동시에 중복이
생깁니다). 어댑터는 `entry.uuid()`로 중복을 제거해, 부분 성공 후의 재시도가 중복에 안전하도록 하세요.

## 플러시 모드 (BATCHED 대 EAGER)

기본적으로 `TranscriptMirrorBatcher`는 모든 `transcript_mirror` 프레임을 버퍼링했다가 턴마다 한 번씩
(`result` 메시지에서), 또는 대기 버퍼가 `MAX_PENDING_ENTRIES=500`개 / `MAX_PENDING_BYTES=1 MiB`를 넘을 때
플러시합니다. 이렇게 하면 어댑터 지연을 스트리밍의 뜨거운 경로에서 떼어 놓을 수 있어, 거의 모든 배포에
알맞은 선택입니다.

항목이 1초 미만의 지연으로 저장소에 닿아야 한다면 `sessionStoreFlush` 옵션으로 즉시 미러링으로 바꿀 수
있습니다:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

| 모드 | 언제 플러시되는가 | 쓸 때 |
|---|---|---|
| `BATCHED`(기본) | `result` 메시지마다 한 번, 또는 대기가 500개 / 1 MiB를 넘을 때 | 거의 모든 운영 워크로드 — 어댑터 지연을 스트리밍의 뜨거운 경로에서 떼어 놓습니다 |
| `EAGER` | 프레임을 큐에 넣을 때마다 백그라운드 배출을 예약 | 클라이언트로의 실시간 트랜스크립트 스트리밍, 실시간 감사 파이프라인, `result`까지 기다릴 수 없는 아주 큰 턴 |

`EAGER`는 배처의 대기 임계값을 0으로 만듭니다 — 큐에 넣는 모든 프레임이 설정된 `SessionStoreExecutor`를
통해 백그라운드 플러시를 예약합니다(작업마다 이름 붙은 스레드 하나, Java 21+에서는 가상 스레드). 추가는
여전히 큐 순서대로 직렬화됩니다. 느린 어댑터가 읽기 루프를 막지는 않지만, 바쁜 동안에는 프레임이 합쳐져
보입니다. `sessionStore`가 설정되지 않으면 이 옵션은 무시됩니다.

## 로컬 세션을 저장소로 가져오기

기존 디스크 세션을 저장소로 옮기거나, `MirrorErrorMessage`가 드러낸 구멍을 나중에 메웁니다:

```java
ClaudeSDK.importSessionToStore(sessionId, store, /* directory */ null);
// or with explicit options:
ClaudeSDK.importSessionToStore(
    sessionId, store, /* directory */ null,
    /* includeSubagents */ true,
    /* batchSize */ 500);
```

이 헬퍼는:

- 로컬 JSONL을 한 줄씩 흘려 읽습니다(빈 줄은 건너뜀).
- `batchSize`개 항목마다(기본 500) 또는 줄 바이트가 1 MiB에 이를 때마다, 먼저 오는 쪽으로
  `store.append(key, batch)`를 호출합니다.
- `includeSubagents=true`이면 `<sessionDir>/subagents/**/*.jsonl`과 `.meta.json` 사이드카를 재귀적으로
  가져옵니다(`.meta.json`은 `agent_metadata` 항목이 됩니다).
- 잘못된 UUID에는 `IllegalArgumentException`을, 세션 파일을 찾지 못하면 `NoSuchFileException`을 던집니다.

대상 `project_key`는 디스크의 프로젝트 디렉터리 이름 — `filePathToSessionKey`가 만들어 내는 것과 같은 키 —
이므로, 가져온 세션은 실시간으로 미러링된 것과 구별되지 않고 원래 `cwd`에서 재개할 수 있습니다.

어댑터는 `entry.uuid()`를 멱등 키로 다뤄, 다시 가져오기가 중복에 안전하도록 해야 합니다.

## 적합성 테스트 하네스

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`는 공개된, 테스트 프레임워크에 비종속인 테스트
하네스로, 모든 어댑터가 만족해야 하는 14가지 행동 계약을 검사합니다. 직접 만든 구현을 검증하는 데
쓰세요:

```java
import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
import org.junit.jupiter.api.Test;

class MyRedisStoreConformanceTest {
    @Test
    void satisfiesContract() {
        SessionStoreConformance.run(MyRedisStore::new);
    }
}
```

구현하지 않은 선택 메서드를 건너뛰려면:

```java
SessionStoreConformance.run(WormStore::new,
    EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
```

하네스는 평범한 `AssertionError`를 쓰므로(테스트 프레임워크 의존성 없음) JUnit, TestNG, Spock, 심지어
단순한 `main()` 스모크 테스트에서도 동작합니다.

14가지 계약이 다루는 내용:

| # | 계약 |
|---|---|
| 1 | `append` 후 `load`가 같은 순서로 같은 항목을 반환 |
| 2 | 알 수 없는 키에 대한 `load`가 `null`을 반환 |
| 3 | 여러 번의 `append`가 순서를 보존 |
| 4 | `append([])`가 no-op |
| 5 | subpath 키가 main과 독립적으로 저장됨 |
| 6 | `project_key` 격리 |
| 7 | `listSessions`가 프로젝트의 세션 ID를 반환하고 mtime이 epoch 밀리초 |
| 8 | `listSessions`가 서브에이전트 subpath를 제외 |
| 9 | `delete` 후 `load`가 `null`을 반환 |
| 10 | main 키의 `delete`가 하위 키로 연쇄 |
| 11 | subpath가 있는 `delete`가 그 하위 키만 제거 |
| 12 | `listSubkeys`가 subpath를 반환 |
| 13 | `listSubkeys`가 main 트랜스크립트를 제외 |
| 14 | `listSessionSummaries`가 `foldSessionSummary`를 통해 왕복 |

## 내부 런타임 구성 요소

이들은 `in.vidyalai.claude.sdk.internal`에 있으며 공개 API가 아니지만, 이해해 두면 미러 동작을 디버깅할 때
도움이 됩니다.

### `TranscriptMirrorBatcher`

CLI가 stdout에 내보내는 `transcript_mirror` 프레임을 버퍼링했다가 `store.appendAsync(...)`로
플러시합니다:

- 즉시 플러시 임계값: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`.
  `sessionStoreFlush(EAGER)`에서는 두 임계값이 0이 되어, 큐에 넣는 모든 프레임이 백그라운드 배출을
  예약합니다([플러시 모드](#플러시-모드-batched-대-eager) 참고).
- 각 `result` 메시지 전에 명시적으로 플러시하고, 스트림 끝/닫기에서 한 번 더 합니다.
- `filePath`별로 프레임을 합쳐, 플러시마다 고유 파일당 `append` 호출이 한 번이 되게 합니다.
- 경로가 `projectsDir` 밖인 프레임은 경고와 함께 버려집니다(부모와 서브프로세스의 `CLAUDE_CONFIG_DIR`가
  다를 때 일어납니다).
- `MIRROR_APPEND_MAX_ATTEMPTS=3`회, `[200ms, 800ms]` 백오프로 재시도합니다. 타임아웃은 재시도하지
  않습니다.
- `maxPendingEntries()` / `maxPendingBytes()` 테스트 접근자가 설정된 임계값을 드러냅니다(Python의 공개
  속성과 대응).

### `SessionResume`

저장된 세션을 임시 `CLAUDE_CONFIG_DIR`로 구체화해 CLI가 재개할 수 있게 합니다:

- `materializeResumeSession(options)` — 주 진입점.
- `applyMaterializedOptions(options, materialized)` — `CLAUDE_CONFIG_DIR`를 주입하고 `resume`을 설정하며
  `continueConversation`을 해제한 options 사본을 만듭니다.
- `buildMirrorBatcher(store, materialized, env, onError)` — 올바른 `projectsDir`로 배처를 만듭니다(기본은
  `BATCHED` 플러시 모드). 5인자 오버로드
  `buildMirrorBatcher(store, materialized, env, onError, flushMode)`는 `flushMode == EAGER`일 때 배처의
  임계값을 0으로 만듭니다.
- `MaterializedResume.cleanup()` — 최선 노력의 재귀 삭제. Windows 백신/색인기의 일시적 잠금에는
  재시도합니다.

### `SessionStoreValidation`

서브프로세스를 띄우기 전에 호출되는 사전 옵션 검사입니다(잘못된 설정을 `IllegalArgumentException`으로
거부).

### `SessionSummary`

어댑터가 `append()` 안에서 트랜스크립트를 다시 읽지 않고 증분 요약 사이드카를 유지하는 데 쓸 수 있는 순수
헬퍼입니다:

```java
SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
    /* prev */ existing, key, entries);
// stamp folded.mtime() with the adapter's storage write time, then persist.
```

`SessionSummary.summaryEntryToSdkInfo(entry, projectPath)`는 목록을 위해 사이드카를 다시
`SDKSessionInfo`로 바꿉니다.

## 모범 사례

### 어댑터 구현

- **`append`와 `load`는 반드시 구현하세요.** 필수입니다.
- 백엔드가 목록 연산을 지원한다면 `append()` 안에서 `SessionSummary.foldSessionSummary`로 **요약
  사이드카를 유지**하세요. 그러면 `listSessionsFromStore`가 O(N)번의 load 대신 O(1)이 됩니다. `subpath`가
  있는 키에서는 fold를 건너뛰세요 — 서브에이전트 트랜스크립트는 main 세션의 요약에 기여하면 안 됩니다.
- **`entry.uuid()`를 멱등 키로 다루세요.** upsert 의미나 "있으면 건너뛰기"를 쓰세요. SDK는 실패한 배치를
  재시도하며 부분적으로 성공할 수 있습니다.
- **요약의 `mtime`에는 여러분 저장소의 쓰기 시각을 찍으세요**, 항목의 타임스탬프가 아니라. 빠른 경로의
  신선도 검사는 같은 세션의 `listSessions().mtime`과 요약 mtime을 비교합니다 — 항목 타임스탬프를 쓰면 모든
  사이드카가 오래된 것처럼 보입니다.
- main 트랜스크립트 키에서 모든 하위 키(서브에이전트 트랜스크립트)로 **삭제를 연쇄**시키세요.
- **CI에서 적합성 하네스를 돌리세요.**

### 언제 `*Async` 메서드를 재정의할까

- 클라이언트가 태생적으로 비동기일 때(AWS SDK v2 async, R2DBC, Lettuce reactive) — 스레드 도약을 피하려면
  `*Async`를 재정의하세요.
- 클라이언트가 동기일 때(JDBC, Jedis, AWS SDK v1) — 동기 메서드만 구현하면 되고, 기본 `*Async` 래퍼로
  충분합니다.

### 언제 `importSessionToStore`를 쓸까

- 기존 로컬 세션을 저장소로 한 번 옮길 때.
- `MirrorErrorMessage` 이후 따라잡을 때(로컬 파일을 다시 가져오기. `uuid` 기반 멱등성 덕분에
  안전합니다).

### 피할 것

- `sessionStore`와 `enableFileCheckpointing`을 함께 쓰기(어차피 검증 시점에 거부됩니다 — 체크포인트는
  로컬 전용입니다).
- 보존 통제 없이 비밀이나 개인정보를 저장하기. SDK는 자동으로 삭제하지 않습니다. 저장소의 라이프사이클을
  설정하세요.
- `load()`에서 바이트 단위로 같은 직렬화를 기대하기. 계약은 깊은 동등성입니다. 예를 들어 Postgres의
  `jsonb`는 키 순서를 바꿉니다.

## API 레퍼런스

### `SessionStore` 인터페이스

`in.vidyalai.claude.sdk.types.session.SessionStore`

| 메서드 | 필수 | 기본 | 참고 |
|---|---|---|---|
| `void append(SessionKey, List<SessionStoreEntry>)` | ✅ | — | 배치 미러링. 로컬 쓰기 후에 호출됨 |
| `List<SessionStoreEntry> load(SessionKey)` | ✅ | — | 항목 또는 `null` 반환 |
| `List<SessionStoreListEntry> listSessions(String)` | 선택 | 던짐 | subpath 항목 제외 |
| `List<SessionSummaryEntry> listSessionSummaries(String)` | 선택 | 던짐 | `listSessionsFromStore`의 빠른 경로 |
| `void delete(SessionKey)` | 선택 | 던짐 | main 키는 하위 키로 연쇄 |
| `List<String> listSubkeys(SessionListSubkeysKey)` | 선택 | 던짐 | 재개 구체화에서 사용 |
| `boolean implementsListSessions()` | — | `false` | 지원을 선언하려면 재정의 |
| `boolean implementsListSessionSummaries()` | — | `false` | 지원을 선언하려면 재정의 |
| `boolean implementsDelete()` | — | `false` | 지원을 선언하려면 재정의 |
| `boolean implementsListSubkeys()` | — | `false` | 지원을 선언하려면 재정의 |
| `CompletableFuture<Void> appendAsync(...)` | 선택 | 동기를 감쌈 | 네이티브 비동기 클라이언트용으로 재정의 |
| `CompletableFuture<List<SessionStoreEntry>> loadAsync(...)` | 선택 | 동기를 감쌈 | 네이티브 비동기 클라이언트용으로 재정의 |
| `CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(...)` | 선택 | 동기를 감쌈 | — |
| `CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(...)` | 선택 | 동기를 감쌈 | — |
| `CompletableFuture<Void> deleteAsync(...)` | 선택 | 동기를 감쌈 | — |
| `CompletableFuture<List<String>> listSubkeysAsync(...)` | 선택 | 동기를 감쌈 | — |

각 `*Async` 메서드에는 인자 없는 오버로드(설정된 기본 실행기 사용)와 `Executor`를 받는 오버로드(호출별
제어)가 모두 있습니다.

### `ClaudeSDK` 정적 메서드

| 메서드 | 설명 |
|---|---|
| `String projectKeyForDirectory(@Nullable Path)` | 디렉터리를 `project_key`로 정제 |
| `List<SDKSessionInfo> listSessionsFromStore(SessionStore, @Nullable Path, @Nullable Integer, int)` | 저장소의 세션 목록 |
| `SDKSessionInfo getSessionInfoFromStore(SessionStore, String, @Nullable Path)` | 단일 세션 메타데이터 읽기 |
| `List<SessionMessage> getSessionMessagesFromStore(SessionStore, String, @Nullable Path, @Nullable Integer, int)` | 전체 트랜스크립트 읽기 |
| `List<String> listSubagentsFromStore(SessionStore, String, @Nullable Path)` | 서브에이전트 ID 찾기 |
| `List<SessionMessage> getSubagentMessagesFromStore(SessionStore, String, String, @Nullable Path, @Nullable Integer, int)` | 서브에이전트 트랜스크립트 읽기 |
| `void renameSessionViaStore(SessionStore, String, String, @Nullable Path)` | `custom-title` 항목 추가 |
| `void tagSessionViaStore(SessionStore, String, @Nullable String, @Nullable Path)` | `tag` 항목 추가. `null`은 지움 |
| `void deleteSessionViaStore(SessionStore, String, @Nullable Path)` | 삭제(`delete` 미구현이면 no-op) |
| `ForkSessionResult forkSessionViaStore(SessionStore, String, @Nullable Path, @Nullable String, @Nullable String)` | UUID 재매핑 포크 |
| `void importSessionToStore(String, SessionStore, @Nullable Path)` | 로컬→저장소 재생(기본 옵션) |
| `void importSessionToStore(String, SessionStore, @Nullable Path, boolean, int)` | `includeSubagents`와 `batchSize`를 명시한 재생 |

### `ClaudeAgentOptions` 빌더 메서드

| 메서드 | 기본값 | 설명 |
|---|---|---|
| `Builder sessionStore(@Nullable SessionStore)` | `null` | 트랜스크립트를 이 저장소로 미러링 |
| `Builder loadTimeoutMs(long)` | `60_000` | 재개 구체화 중 호출별 타임아웃 |

### `SessionStoreExecutor`

`in.vidyalai.claude.sdk.types.session.SessionStoreExecutor`

| 메서드 | 설명 |
|---|---|
| `Executor getDefault()` | 현재 기본 실행기 |
| `void setDefault(Executor)` | 덮어쓰기. `null`이면 내장으로 초기화 |
| `void reset()` | 내장 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-store-", 0).factory())`로 초기화 |

### `SessionStoreConformance`

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`

| 메서드 | 설명 |
|---|---|
| `void run(Supplier<SessionStore>)` | 14가지 계약 모두 실행 |
| `void run(Supplier<SessionStore>, Set<OptionalMethod>)` | 나열한 선택 메서드를 건너뜀 |

`OptionalMethod` 열거형: `LIST_SESSIONS`, `LIST_SESSION_SUMMARIES`, `DELETE`, `LIST_SUBKEYS`.

## 관련 항목

- [세션 히스토리](./feature-session-history.md) — 로컬 디스크 대응물(`listSessions`, `getSessionMessages` 등)
- [메시지 타입](./feature-message-types.md) — `MirrorErrorMessage` 통합
- [ClaudeAgentOptions](./api-claude-agent-options.md) — `sessionStore`와 `loadTimeoutMs`
- [ClaudeSDK](./api-claude-sdk.md) — 공개 API 진입점
- `examples/` 모듈의 `SessionStoreExample.java`
