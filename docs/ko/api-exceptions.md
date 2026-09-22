# 예외 타입 API 레퍼런스

오류 처리와 예외 계층 구조입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../api-exceptions.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 예외 계층 구조

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
├── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

## ClaudeSDKException

모든 SDK 오류의 기반 예외입니다.

```java
public class ClaudeSDKException extends RuntimeException {
    public ClaudeSDKException(String message);
    public ClaudeSDKException(String message, Throwable cause);
}
```

## CLIConnectionException

Claude Code CLI 연결에 실패했습니다.

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**원인**:
- CLI를 찾을 수 없음
- 프로세스 시작 실패
- 연결 시간 초과
- 네트워크 문제(원격 전송)

## CLINotFoundException

Claude Code CLI 실행 파일을 찾을 수 없습니다.

```java
public class CLINotFoundException extends ClaudeSDKException {
    public CLINotFoundException(String message);
}
```

**해결 방법**:
- Claude Code CLI 설치
- `.cliPath()`로 사용자 지정 경로 지정

## ProcessException

CLI 프로세스가 실패했거나 비정상 종료했습니다.

```java
public class ProcessException extends ClaudeSDKException {
    public ProcessException(String message);
    public ProcessException(String message, Throwable cause);
}
```

**원인**:
- CLI 비정상 종료
- 잘못된 인자
- 자원 고갈

**오류 결과로 종료된 뒤의 실질적인 오류**: CLI가 `isError=true`인 `ResultMessage`(예:
`error_max_turns`, `error_during_execution`, 또는 `apiErrorStatus`가 설정된 `success` 하위 타입)를
내보내면, 그다음 일부러 0이 아닌 코드로 종료합니다. 뒤따르는 `ProcessException`은
`"Command failed with exit code N"`만 담고 있어 실질적인 도움이 되지 않으므로, 리더가 이를
`ResultException`(아래 참고)으로 교체합니다. 이 교체는 턴 단위이며, 실행 후반에 새로 발생한 비정상
종료는 원래의 `ProcessException` 메시지를 유지합니다.

## ResultException

CLI가 종료성 오류 결과를 보고하고 종료했습니다. `ProcessException`의 하위 클래스이므로 기존의
`catch (ProcessException e)` 처리는 그대로 동작합니다.

```java
public class ResultException extends ProcessException {
    public ResultException(String message, @Nullable Map<String, Object> data,
                           @Nullable Integer exitCode);

    @Nullable public String subtype();          // "error_max_turns", "error_during_execution",
                                                // ... or "success" for a mid-turn API failure
    public List<String> errors();               // never null; empty for API failures
    @Nullable public String result();           // result text; the "API Error: ..." prose
    @Nullable public Integer apiErrorStatus();  // HTTP status of the failing API call
    @Nullable public String terminalReason();   // e.g. "api_error", "max_turns"
    @Nullable public String sessionId();
    public Map<String, Object> data();          // raw result payload, unmodifiable
}
```

메시지는 `"Claude Code returned an error result: <text>"`에 `ProcessException`의
`" (exit code: N)"` 접미사가 붙은 형태입니다. `<text>`는 결과의 `errors` 배열을 `"; "`로 이은 것이며,
없으면 결과 텍스트, 그다음 `success`가 아닌 `subtype`, 마지막으로 `"API error (HTTP <status>)"`로
대체됩니다. 0이 아닌 종료에 해당하는 원래의 `ProcessException`이 `getCause()`입니다.

텍스트가 아니라 페이로드를 기준으로 분기하세요:

```java
} catch (ResultException e) {
    if ("api_error".equals(e.terminalReason())) {
        retry();
    } else if ("error_max_turns".equals(e.subtype())) {
        // ...
    }
}
```

**어디에서 나타나는가:**

- 메시지를 모으는 `ClaudeSDK.query(...)` 계열은 실패 전에 받은 메시지가 유실되지 않도록 이를
  `QueryFailedException`으로 감쌉니다. 이때 `ResultException`이 그 예외의 `getCause()`입니다. 대개
  이런 식으로 만나게 됩니다.
- 실패한 제어 요청에서 직접 — 가장 중요한 경우는 CLI가 시작할 때 거부하는 `initialize`
  (`resumeDropsTurn`으로 거부된 재개)입니다. 이는 메시지를 하나도 모으기 전에 일어나므로 감싸지지
  않습니다.
- `ClaudeSDKClient.receiveResponse()`에서는 나타나지 **않습니다**: 이 메서드는 `ResultMessage`에서
  끝나므로(Python SDK의 `receive_response()`와 똑같이) CLI의 종료를 관찰하지 않습니다. 거기서는
  대신 `ResultMessage.isError()`를 확인하세요. `receiveMessages()`는 스트림 끝까지 진행하며 실제로
  예외를 던지지만, 살아 있는 클라이언트에서는 stdin이 열린 채로 있으므로 세션 도중의 오류 결과가
  스트림을 끝내지는 않습니다.

## CLIJSONDecodeException

CLI에서 온 JSON 파싱에 실패했습니다.

```java
public class CLIJSONDecodeException extends ClaudeSDKException {
    public CLIJSONDecodeException(String message, Throwable cause);
}
```

**원인**:
- 잘못된 JSON
- 예상치 못한 형식
- CLI 버전 불일치

## MessageParseException

메시지를 타입이 지정된 객체로 파싱하는 데 실패했습니다.

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**원인**:
- 알 수 없는 메시지 타입
- 필수 필드 누락
- 타입 변환 오류

## QueryFailedException

메시지를 모으는 질의가 오류 결과로 끝났습니다. 그때까지 도착한 메시지를 담고 있습니다.

```java
public class QueryFailedException extends ClaudeSDKException {
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages);

    public List<Message> partialMessages();   // never null; unmodifiable
    public ResultMessage resultMessage();     // last ResultMessage received, or null
}
```

**원인**:
- `error_max_turns` — `maxTurns`에 도달
- `error_max_budget_usd` — `maxBudgetUsd`에 도달
- `error_during_execution` — `resumeDropsTurn`으로 거부된 재개 포함

**왜 존재하는가**: CLI는 이런 상황을 *완전한* 턴 — 어시스턴트 메시지와 하위 타입·비용·사용량을 담은
마지막 `ResultMessage` — 을 내보낸 다음에야, 셸 사용자를 위해 일부러 0이 아닌 코드로 종료하는 방식으로
알립니다. 스트리밍 API(`ClaudeSDKClient.receiveMessages()`와 `receiveResponse()`)는 각 메시지를
도착하는 대로 소비자에게 넘기고 마지막에만 예외를 던지므로 그쪽에서는 아무것도 잃지 않습니다. 반면
모아서 돌려주는 호출은 리스트를 반환하거나 예외를 던지는 것 중 하나만 할 수 있는데, 이 예외를 던지면
오류와 메시지를 함께 실어 나를 수 있어 `ClaudeSDK.query(...)`도 스트리밍 경로만큼 많은 정보를 줍니다.

메시지를 모으는 `ClaudeSDK.query(...)` 계열(여기에 위임하는 `queryForText`와 `queryForResult` 포함)
에서만 던져집니다. `ClaudeSDKException`을 상속하므로 기존의 `catch (ClaudeSDKException e)` 블록은
바꾸지 않아도 계속 동작합니다.

```java
try {
    List<Message> messages = ClaudeSDK.query("Summarize the README", options);
    // ... normal path
} catch (QueryFailedException e) {
    // The turn is usually complete — inspect what actually happened.
    ResultMessage result = e.resultMessage();
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped by the budget cap after $%.4f%n", result.totalCostUsd());
    }
    for (Message msg : e.partialMessages()) {
        if (msg instanceof AssistantMessage a) {
            System.out.println(a.getTextContent());
        }
    }
}
```

실행이 무언가를 만들어 내기 전에 실패하면(예: 시작하지 못한 CLI) `partialMessages()`는 비어 있습니다.
이 값은 직렬화되지 않습니다 — `Message`가 `Serializable`로 선언되어 있지 않기 때문에, 역직렬화된
인스턴스는 null이 아니라 빈 목록을 보고합니다.

`maxTurns`나 `maxBudgetUsd`를 설정했다면 반드시 이 예외를 잡으세요. 직접 설정한 한도에 도달하는 것은
충돌이 아니라 예상된 결과입니다.

## 오류 처리 예제

### 기본 try-catch

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (CLINotFoundException e) {
    System.err.println("Claude CLI not installed");
} catch (CLIConnectionException e) {
    System.err.println("Connection failed: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI crashed: " + e.getMessage());
} catch (QueryFailedException e) {
    // Run stopped at a limit; the messages so far are still available.
    System.err.println("Run ended early: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

순서가 중요합니다: `QueryFailedException`은 `ClaudeSDKException`의 하위 클래스이므로 그보다 먼저
잡아야 합니다.

### 자원 관리와 함께

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
} catch (CLIConnectionException e) {
    log.error("Failed to connect", e);
    throw new ApplicationException("Service unavailable", e);
} catch (ClaudeSDKException e) {
    log.error("SDK error", e);
    throw new ApplicationException("Internal error", e);
}
```

### 재시도 로직

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return ClaudeSDK.query(prompt, options);
    } catch (CLIConnectionException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(1000 * (i + 1));  // Exponential backoff
    }
}
```

## 관련 항목
- [오류 처리 예제](../../examples/src/main/java/examples/ErrorHandling.java)
