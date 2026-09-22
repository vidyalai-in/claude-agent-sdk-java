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
```

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

**매개변수**: `model` — 모델 이름(예: "claude-opus-4-6")

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

파일을 지정한 사용자 메시지 시점의 상태로 되돌립니다(체크포인트 필요).

**매개변수**: `userMessageId` — 되돌릴 대상 메시지 ID

### getMcpStatus()

```java
public Map<String, Object> getMcpStatus()
```

MCP 서버의 연결 상태를 얻습니다.

**반환**: `Map<String, Object>` — 상태 정보

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

서버 초기화 정보를 얻습니다.

**반환**: `Map<String, Object>` — 서버 정보

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
