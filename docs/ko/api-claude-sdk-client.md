# ClaudeSDKClient API 레퍼런스

양방향 대화를 위한 대화형 클라이언트입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../api-claude-sdk-client.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 클래스 개요

```java
public class ClaudeSDKClient implements AutoCloseable
```

Claude와 상태를 유지하는 대화형 세션을 위한 클라이언트입니다.

## 생성자

```java
public ClaudeSDKClient()
public ClaudeSDKClient(ClaudeAgentOptions options)
public ClaudeSDKClient(ClaudeAgentOptions options, Transport transport)
```

`transport`가 null이 아니면 클라이언트는 CLI 서브프로세스를 띄우는 대신 이를 사용합니다. SDK는
그 `connect()`를 호출하고, `write()`로 프롬프트를 쓰며, 훅·에이전트·그 밖의 initialize 요청 설정을
제어 프로토콜로 보냅니다. CLI 플래그 옵션(model, cwd, 권한 모드, 도구, 그리고 서브프로세스 전송이
플래그로 바꾸는 나머지 옵션)은 사용자 정의 전송에는 적용되지 않으며, 저장소 기반 세션 재개도
건너뜁니다.

## 연결 메서드

### connect()

```java
public void connect() throws CLIConnectionException
```

Claude Code CLI와의 연결을 맺습니다.

**스레드 안전성**: 스레드 안전, 멱등

**던짐**: close() 이후에 호출하면 `IllegalStateException`

### connect(String initialMessage)

```java
public void connect(String initialMessage) throws CLIConnectionException
```

연결하고 초기 메시지를 보냅니다.

### isConnected()

```java
public boolean isConnected()
```

연결되었는지 확인합니다.

**반환**: `boolean`

### disconnect() / close()

```java
public void disconnect()
public void close()
```

연결을 닫고 자원을 정리합니다.

**스레드 안전성**: 스레드 안전, 멱등

## 메시지 보내기

### sendMessage(String prompt)

```java
public void sendMessage(String prompt)
```

메시지를 보내고 계속 수신합니다.

### sendMessage(String prompt, String sessionId)

```java
public void sendMessage(String prompt, String sessionId)
```

특정 세션에 메시지를 보냅니다.

### query(String prompt)

```java
public void query(String prompt)
```

메시지를 보냅니다. 프롬프트가 기록되는 즉시 반환합니다 — 응답은
[`receiveResponse()`](#receiveresponse) 또는 [`receiveMessages()`](#receivemessages)로 읽으세요.

옵션에 `verbatimPrompts(true)`가 설정되어 있으면 이 클라이언트가 쓰는 모든 프롬프트 —
`connect(String)`, `sendMessage`, 두 가지 `query` 형태 모두 — 에 `client_composed: true` 표시가
붙어, Claude Code가 작성된 그대로 전달합니다(`@path` 확장도, 슬래시 명령 디스패치도 없음).
스트리밍 메시지는 사본에 표시가 붙습니다.

`query(prompt, "default")`와 같습니다.

### query(String prompt, String sessionId)

```java
public void query(String prompt, String sessionId)
```

특정 세션에 질의합니다.

### query(Iterator&lt;Map&lt;String, Object&gt;&gt; messageStream)

```java
public void query(Iterator<Map<String, Object>> messageStream)
public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
```

원본 메시지 맵을 보냅니다. 문자열 형태로는 만들 수 없는 필드가 메시지에 필요할 때 — `origin` 귀속,
구조화된 콘텐츠 블록, 명시적인 `uuid` — `query(String)` 대신 이것을 사용하세요:

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", "Reply with exactly: one"));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));   // attribute the turn

client.query(List.of(message).iterator());
for (Message msg : client.receiveResponse()) { /* ... */ }
```

`session_id`를 빠뜨린 메시지에는 `"default"`가 — 두 인자 오버로드에서는 `sessionId`가 — 채워집니다.
이 필드를 추가할 때 호출자의 맵은 변경되지 않고 복사되므로, 불변 맵을 넘겨도 안전합니다.

`query(String)`과 마찬가지로 이 메서드는 CLI의 stdin을 열어 둔 채로 두므로, 한 세션 안에서 반복해
호출할 수 있고 문자열 오버로드와 자유롭게 섞어 쓸 수 있습니다.

> **v0.1.23에서 변경됨.** 이 메서드는 예전에 이터레이터를 내부의 일회성 스트리밍 경로에 넘겼는데,
> 그 경로는 이터레이터가 소진되면 stdin을 닫습니다. 그러면 세션이 끝나 버려 CLI가 종료되고, 다음
> `query()`나 `sendMessage()`가 `ProcessTransport is not ready for writing`으로 실패했습니다.
> 이제는 Python SDK와 동일하게 곧바로 기록합니다. 세션을 끝내려고 예전 동작에 의존했다면, 대신
> `disconnect()`를 호출하세요(또는 try-with-resources를 사용하세요).

메시지는 호출이 반환되기 전에 기록되므로 연속된 호출의 순서가 유지됩니다. 이터레이터가 아직 값을
만들어 내는 동안 응답을 읽어야 한다면, 지연되거나 무한한 이터레이터를 여러분의 스레드에서 구동하세요.

## 메시지 받기

### receiveMessages()

```java
public Iterator<Message> receiveMessages()
```

모든 메시지를 순회하는 이터레이터를 얻습니다(연속적).

**스레드 안전성**: 스레드 안전하지만 메시지가 여러 이터레이터에 나뉘어 분배됩니다

**반환**: `Iterator<Message>`

### receiveResponse()

```java
public Iterable<Message> receiveResponse()
```

다음 ResultMessage까지의 메시지를 얻습니다(자동으로 닫힘).

**스레드 안전성**: 스레드 안전

**반환**: `Iterable<Message>`

## 제어 메서드

### interrupt()

```java
public void interrupt()
```

현재 실행을 중단합니다.

**스레드 안전성**: 스레드 안전

### setModel(String model)

```java
public void setModel(String model)
```

AI 모델을 변경합니다.

**매개변수**: `model` — 모델 이름 또는 별칭(예: `"claude-sonnet-5"`, `"haiku"`), 기본값을 쓰려면 `null`. CLI는 전체 모델 ID를 API에 대조해 확인하므로 폐기된 ID는 실패하고, 별칭은 현재 모델로 매핑됩니다.

### setPermissionMode(PermissionMode mode)

```java
public void setPermissionMode(PermissionMode mode)
```

권한 모드를 변경합니다.

**매개변수**: `mode` — 새 권한 모드

### rewindFiles(String userMessageId)

```java
public void rewindFiles(String userMessageId)
```

추적 중인 파일을 지정한 사용자 메시지 시점의 상태로 되돌립니다.

파일 변경을 추적하려면 `enableFileCheckpointing(true)`가 필요하고, 스트림의 `UserMessage` 객체가
되돌릴 대상 `uuid`를 담도록 `extraArgs(Map.of("replay-user-messages", ""))`도 필요합니다.

**매개변수**: `userMessageId` — 되돌릴 대상 사용자 메시지의 UUID

### getMcpStatus()

```java
public McpStatusResponse getMcpStatus()
```

MCP 서버의 연결 상태를 얻습니다.

**반환**: `McpStatusResponse` — 그 `mcpServers()`가 서버마다 `McpServerStatus`를 하나씩 나열합니다

### reconnectMcpServer(String serverName)

```java
public void reconnectMcpServer(String serverName)
```

연결에 실패했거나 연결이 끊긴 MCP 서버에 다시 연결을 시도합니다.

### toggleMcpServer(String serverName, boolean enabled)

```java
public void toggleMcpServer(String serverName, boolean enabled)
```

MCP 서버를 활성화하거나 비활성화합니다. 비활성화하면 연결을 끊고 그 도구를 사용 가능한 집합에서
제거하며, 활성화하면 다시 연결해 도구를 다시 사용할 수 있게 합니다.

### stopTask(String taskId)

```java
public void stopTask(String taskId)
```

실행 중인 백그라운드 작업을 중지합니다.

**매개변수**: `taskId` — `TaskStartedMessage`에서 얻은 작업 ID

이 메서드가 반환된 뒤 CLI는 작업의 종료를 상태가 종료 상태(중지된 작업이면 `"killed"`)인
`TaskUpdatedMessage`로 알립니다. 그 뒤에 상태가 `"stopped"`인 `TaskNotificationMessage`가 올 수도
있지만 때로는 생략되므로, 두 메시지 중 어느 쪽이든 종료 상태가 오면 끝으로 간주하세요
(`TaskUpdatedMessage.TERMINAL_TASK_STATUSES` 참고).

### getContextUsage()

```java
public ContextUsageResponse getContextUsage()
```

현재 컨텍스트 창 사용량을 범주별로 나누어 얻습니다.

CLI의 `/context` 명령이 보여 주는 것과 같은 데이터를 반환하며, 범주별 토큰 수, 총 사용량, 그리고 MCP
도구·메모리 파일·에이전트의 세부 내역을 담고 있습니다.

**반환**: 다음 필드를 가진 `ContextUsageResponse`
- `categories` — `ContextUsageCategory` 목록(name, tokens, color)
- `totalTokens` — 컨텍스트 창의 총 토큰 수
- `maxTokens` — 실효 컨텍스트 한도
- `percentage` — 사용한 컨텍스트의 비율(0~100)
- `model` — 모델 이름
- 그리고 선택 필드: `autoCompactThreshold`, `memoryFiles`, `mcpTools`, `agents` 등

**던짐**: 연결되어 있지 않으면 `CLIConnectionException`

### getServerInfo()

```java
public Map<String, Object> getServerInfo()
```

서버 초기화 정보를 얻습니다: 사용 가능한 명령, 출력 스타일, 서버 기능.

**반환**: `Map<String, Object>` — `initialize` 응답에 담긴 정보(CLI가 아무것도 반환하지 않은 경우에만
`null`)

## 스레드 안전성

- **connect()**: 스레드 안전, 동기화됨
- **전송 메서드**: 스레드 안전
- **수신 메서드**: 스레드 안전하지만 큐를 공유함
- **제어 메서드**: 스레드 안전
- **close()**: 스레드 안전, 멱등

## 자원 관리

항상 try-with-resources를 사용하세요:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
}
```

## 관련 항목
- [대화형 세션 가이드](./feature-interactive-conversations.md)
