# 대화형 세션

대화형 세션은 `ClaudeSDKClient` 클래스를 사용해 Claude와 여러 차례에 걸친 상태 유지 대화를 주고받는 방식입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-interactive-conversations.md)보다 오래되었을 수 있으며, 내용이 다를 경우 영어판이 우선합니다. 코드 블록은 영어 원문과 동일하게 유지하며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [ClaudeSDKClient를 사용할 때](#claudesdkclient를-사용할-때)
- [기본 사용법](#기본-사용법)
- [연결 관리](#연결-관리)
- [메시지 보내기](#메시지-보내기)
- [메시지 받기](#메시지-받기)
- [제어 메서드](#제어-메서드)
- [세션 관리](#세션-관리)
- [스레드 안전성](#스레드-안전성)
- [리소스 관리](#리소스-관리)
- [예제](#예제)
- [모범 사례](#모범-사례)

## 개요

`ClaudeSDKClient`는 Claude와의 양방향 대화를 완전히 제어할 수 있게 해줍니다. 단순한
`ClaudeSDK.query()` 파사드와 달리 이 클라이언트는 다음과 같습니다.

- **상태 유지**: 여러 메시지에 걸쳐 대화 컨텍스트가 보존됩니다
- **양방향**: 언제든지 메시지를 주고받을 수 있습니다
- **대화형**: 응답을 바탕으로 후속 질문을 보낼 수 있습니다
- **제어 가능**: 실행 중에 중단, 모델 변경, 권한 변경이 가능합니다
- **세션 인식**: 세션 재개와 포크를 지원합니다

## ClaudeSDKClient를 사용할 때

### ✅ 아주 잘 맞는 경우

1. **채팅 인터페이스**
   ```java
   try (var client = ClaudeSDK.createClient()) {
       client.connect();
       while (userInput = getUserInput()) {
           client.sendMessage(userInput);
           for (var msg : client.receiveResponse()) {
               display(msg);
           }
       }
   }
   ```

2. **REPL 형태의 인터페이스**
   ```java
   while (true) {
       String command = console.readLine();
       client.sendMessage(command);
       processResponse(client.receiveResponse());
   }
   ```

3. **여러 차례의 대화**
   ```java
   client.sendMessage("What is Python?");
   // ... process response
   client.sendMessage("Show me a code example");
   // ... context preserved
   ```

4. **대화형 디버깅**
   ```java
   client.sendMessage("Analyze this error");
   var response = client.receiveResponse();
   if (needsMoreInfo) {
       client.sendMessage("Here's more context...");
   }
   ```

5. **장시간 실행되는 세션**
   ```java
   try (var client = ClaudeSDK.createClient(options)) {
       client.connect();
       // Hours-long session with state
   }
   ```

### ❌ 잘 맞지 않는 경우

- 단순한 일회성 질문 → `ClaudeSDK.query()`를 사용하세요
- 배치 처리 → `ClaudeSDK.query()`를 사용하세요
- 한 번 실행하고 끝나는 스크립트 → `ClaudeSDK.query()`를 사용하세요

## 기본 사용법

### 생성과 연결

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Create with default options
ClaudeSDKClient client = ClaudeSDK.createClient();

// Or with custom options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(20)
    .build();
ClaudeSDKClient client = ClaudeSDK.createClient(options);

// Connect (establishes subprocess)
client.connect();
```

### 간단한 대화

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // First message
    client.sendMessage("What is 2 + 2?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }

    // Follow-up (context preserved)
    client.sendMessage("What about 3 + 3?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 초기 메시지와 함께 연결하기

```java
// Connect and send initial message in one call
client.connect("Hello, Claude!");

for (var msg : client.receiveResponse()) {
    // Process initial response
}
```

## 연결 관리

### connect()

Claude Code CLI와의 연결을 맺습니다.

```java
client.connect();  // No initial message
client.connect("Initial prompt");  // With initial message
```

**스레드 안전성**: 스레드 안전하고 멱등합니다. 동시 호출로부터 보호됩니다.

**예외**:
- `IllegalStateException` — `close()` 이후에 호출한 경우
- `CLIConnectionException` — 연결에 실패한 경우

### isConnected()

클라이언트가 연결되어 있는지 확인합니다.

```java
if (client.isConnected()) {
    client.sendMessage("Hello");
}
```

### disconnect() / close()

연결을 닫고 리소스를 정리합니다.

```java
client.disconnect();  // Explicit disconnect
// or
client.close();  // AutoCloseable

// Best practice: use try-with-resources
try (var client = ClaudeSDK.createClient()) {
    // Use client
}  // Automatically closed
```

**스레드 안전성**: 스레드 안전하고 멱등합니다. 여러 번 호출해도 안전합니다.

## 메시지 보내기

### sendMessage(String prompt)

메시지를 보내고 계속 수신합니다.

```java
client.sendMessage("Hello, Claude!");
```

**사용 시점**: 메시지를 보내면서 모든 이벤트를 계속 듣고 싶을 때.

### sendMessage(String prompt, String sessionId)

특정 세션에 메시지를 보냅니다.

```java
client.sendMessage("Hello!", "session-1");
```

### query(String prompt)

메시지를 보내고 그에 대한 응답만 받습니다(ResultMessage까지 블로킹).

```java
List<Message> response = client.query("What is 2 + 2?");
```

**사용 시점**: 요청/응답 패턴이 필요할 때(보내고 완전한 응답을 기다림).

### query(String prompt, String sessionId)

특정 세션 ID로 쿼리합니다.

```java
List<Message> response = client.query("Question", "session-1");
```

### query(Iterator<Map<String, Object>> messageStream)

여러 메시지를 스트림으로 보냅니다.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Second"))
);

List<Message> responses = client.query(messages.iterator());
```

## 메시지 받기

### receiveMessages()

**모든** 메시지를 순회하는 이터레이터를 반환합니다(연속 스트림).

```java
Iterator<Message> messages = client.receiveMessages();

while (messages.hasNext()) {
    Message msg = messages.next();
    // Process each message as it arrives

    if (shouldStop(msg)) {
        break;
    }
}
```

**사용 시점**:
- 메시지를 계속해서 처리하고 싶을 때
- 여러 세션을 다룰 때
- 시스템 메시지를 포함한 모든 이벤트를 봐야 할 때

**특징**:
- 이터레이터는 메시지가 올 때까지 블로킹합니다
- 스트림이 끝날 때까지 메시지를 계속 반환합니다
- 여러 이터레이터가 같은 큐를 공유합니다(메시지가 나뉩니다)

### receiveResponse()

다음 ResultMessage에서 멈추는 이터레이터를 반환합니다.

```java
Iterable<Message> response = client.receiveResponse();

for (Message msg : response) {
    // Process messages until ResultMessage
}
// Iterator auto-closes when ResultMessage received
```

**사용 시점**:
- 요청/응답 패턴이 필요할 때
- 한 번의 쿼리가 완료되기를 기다릴 때
- ResultMessage에서 자동으로 멈추기를 원할 때

**특징**:
- 메시지가 올 때까지 블로킹합니다
- ResultMessage에서 멈추고 자동으로 닫힙니다
- 한 번의 응답에 해당하는 모든 메시지를 반환합니다

### 메시지 처리

```java
for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());

            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Tool: " + tool.name());
                }
            }
        }

        case ResultMessage result -> {
            System.out.println("Done! Cost: $" + result.totalCostUsd());
            System.out.println("Stop reason: " + result.stopReason());
        }

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            System.out.println("Partial: " + event.delta());
    }
}
```

## 제어 메서드

### interrupt()

현재 실행을 중단합니다.

```java
// In another thread
client.interrupt();
```

**사용 사례**:
- 사용자가 작업을 취소할 때
- 타임아웃에 도달했을 때
- 비용이 큰 작업을 멈춰야 할 때

### setModel(String model)

대화 도중에 AI 모델을 바꿉니다.

```java
client.setModel("claude-opus-4-6");
```

**사용 사례**:
- 복잡한 작업에는 더 강력한 모델로 전환
- 단순한 질문에는 더 저렴한 모델로 전환

### setPermissionMode(PermissionMode mode)

대화 중에 권한 모드를 바꿉니다.

```java
client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
```

**사용 가능한 모드**:
- `DEFAULT` — 표준 권한 동작(CLI의 기본값)
- `ACCEPT_EDITS` — 파일 편집은 자동 승인, 나머지는 확인
- `PLAN` — 계획 모드. 도구를 실행하지 않음
- `BYPASS_PERMISSIONS` — 모든 권한 검사를 건너뜀
- `DONT_ASK` — 허용 규칙으로 미리 승인되지 않은 것은 모두 거부
- `AUTO` — 모델 분류기가 도구 호출마다 승인 또는 거부

### rewindFiles(String userMessageId)

파일을 특정 사용자 메시지 시점으로 되돌립니다(체크포인트가 활성화된 경우).

```java
// Enable checkpointing in options
var options = ClaudeAgentOptions.builder()
    .enableFileCheckpointing(true)
    .extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Create file.txt");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user) {
            String messageId = user.uuid();
            // Save ID for later
        }
    }

    // Later: rewind to that message
    client.rewindFiles(messageId);
}
```

### getMcpStatus()

MCP 서버 연결 상태를 가져옵니다.

```java
McpStatusResponse status = client.getMcpStatus();
for (McpServerStatus server : status.mcpServers()) {
    System.out.println(server.name() + ": " + server.status());
}
```

### getServerInfo()

서버 초기화 정보를 가져옵니다.

```java
Map<String, Object> info = client.getServerInfo();
System.out.println("Commands: " + info.get("commands"));
```

## 세션 관리

### 기본 세션

기본적으로 모든 메시지는 "default" 세션을 사용합니다.

```java
client.sendMessage("Hello");  // Uses "default" session
```

### 여러 세션

서로 다른 세션으로 메시지를 보냅니다.

```java
// Session 1
client.sendMessage("Analyze code.java", "session-1");

// Session 2
client.sendMessage("Write tests", "session-2");

// Receive from all sessions
for (var msg : client.receiveMessages()) {
    // Process messages from any session
}
```

### 이전 세션 재개하기

```java
// First conversation
var options1 = ClaudeAgentOptions.builder()
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
}

// Later: resume with context
var options2 = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more");
    // Has context from previous session
}
```

### 세션 포크

포크는 기존 세션으로부터 새 세션을 만듭니다.

```java
var options = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .forkSession(true)  // Fork instead of continue
    .build();
```

**차이점**:
- `resume(id)` — 같은 세션을 이어갑니다
- `resume(id) + forkSession(true)` — 같은 컨텍스트로 새 세션을 만듭니다

## 스레드 안전성

`ClaudeSDKClient`는 **부분적으로 스레드 안전**합니다.

### 스레드 안전한 작업

```java
// ✅ Safe: Multiple threads can send
Thread t1 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 1"));
Thread t2 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 2"));

// ✅ Safe: Control methods
client.interrupt();
client.setModel("claude-sonnet-4-5");
client.setPermissionMode(PermissionMode.ACCEPT_ALL);

// ✅ Safe: connect() is synchronized
client.connect();  // Only one connection established
```

### 공유 상태

```java
// ⚠️ Warning: Multiple iterators share the queue
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();

// Messages distributed across both iterators!
// Typically use only one iterator per client
```

### 모범 사례

```java
// ✅ Good: One receive loop per client
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Dedicated receive thread
    Thread.ofVirtual().start(() -> {
        for (var msg : client.receiveMessages()) {
            processMessage(msg);
        }
    });

    // Main thread sends
    client.sendMessage("Question 1");
    client.sendMessage("Question 2");
}
```

## 리소스 관리

### AutoCloseable

항상 try-with-resources를 사용하세요.

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
}  // Automatically cleaned up
```

### 수동 정리

try-with-resources를 쓰지 않는 경우:

```java
ClaudeSDKClient client = ClaudeSDK.createClient();
try {
    client.connect();
    // Use client
} finally {
    client.close();  // Important!
}
```

### 정리되는 리소스

`close()` 시 클라이언트는 다음을 정리합니다.
- QueryHandler와 스레드 풀
- 스트리밍 실행기
- 전송 계층과 CLI 서브프로세스
- 메시지 큐와 이터레이터

## 예제

### 예제 1: 대화형 채팅

```java
import java.util.Scanner;

public class Chat {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options);
             var scanner = new Scanner(System.in)) {

            client.connect();
            System.out.println("Chat started! Type 'exit' to quit.");

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                client.sendMessage(input);

                System.out.print("Claude: ");
                for (var msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.print(assistant.getTextContent());
                    }
                }
                System.out.println();
            }
        }
    }
}
```

### 예제 2: 오래 걸리는 작업 중단하기

```java
import java.util.concurrent.TimeUnit;

public class InterruptExample {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start long operation in background
            Thread.ofVirtual().start(() -> {
                client.sendMessage("Analyze all files in this large codebase");
                for (var msg : client.receiveResponse()) {
                    System.out.println(msg);
                }
            });

            // Wait 5 seconds then interrupt
            TimeUnit.SECONDS.sleep(5);
            System.out.println("Interrupting...");
            client.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 예제 3: 동적 모델 전환

```java
public class ModelSwitching {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Simple question with Haiku
            client.setModel("claude-haiku-4-5");
            client.sendMessage("What is 2+2?");
            processResponse(client.receiveResponse());

            // Complex question with Opus
            client.setModel("claude-opus-4-6");
            client.sendMessage("Explain quantum entanglement");
            processResponse(client.receiveResponse());

            // Back to Sonnet for balanced tasks
            client.setModel("claude-sonnet-4-5");
            client.sendMessage("Write a Java function");
            processResponse(client.receiveResponse());
        }
    }
}
```

### 예제 4: 다중 세션 관리

```java
public class MultiSession {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start multiple tasks in different sessions
            client.sendMessage("Review code.java for bugs", "review");
            client.sendMessage("Write tests for util.java", "testing");
            client.sendMessage("Document api.java", "docs");

            // Process responses from all sessions
            for (var msg : client.receiveMessages()) {
                switch (msg) {
                    case AssistantMessage a ->
                        System.out.println("[Session] " + a.getTextContent());
                    case ResultMessage r ->
                        System.out.println("[Done] Cost: $" + r.totalCostUsd());
                    default -> {}
                }

                // Stop when all three sessions complete
                if (allSessionsComplete()) {
                    break;
                }
            }
        }
    }
}
```

### 예제 5: 파일 체크포인트

```java
public class Checkpointing {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .enableFileCheckpointing(true)
            .extraArgs(Map.of("replay-user-messages", ""))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String checkpointId = null;

            // Create a file and save checkpoint
            client.sendMessage("Create test.txt with 'Hello'");
            for (var msg : client.receiveResponse()) {
                if (msg instanceof UserMessage user) {
                    checkpointId = user.uuid();
                }
            }

            // Modify the file
            client.sendMessage("Append 'World' to test.txt");
            for (var msg : client.receiveResponse()) {}

            // Rewind to original state
            if (checkpointId != null) {
                client.rewindFiles(checkpointId);
                System.out.println("Rewound to checkpoint");
            }
        }
    }
}
```

## 모범 사례

### 1. try-with-resources 사용하기

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    client.connect();
}

// ❌ Bad: Resource leak
var client = ClaudeSDK.createClient();
client.connect();
// Forgot to close!
```

### 2. 연결 오류 처리하기

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    try {
        client.connect();
    } catch (CLIConnectionException e) {
        System.err.println("Failed to connect: " + e.getMessage());
        return;
    }
    // Use client
}
```

### 3. 요청/응답에는 receiveResponse() 사용하기

```java
// ✅ Good: Clean request/response
client.sendMessage("Question");
for (var msg : client.receiveResponse()) {
    // Processes until ResultMessage
}

// ❌ Bad: Manual ResultMessage checking
for (var msg : client.receiveMessages()) {
    if (msg instanceof ResultMessage) break;
}
```

### 4. 수신 이터레이터를 여러 개 만들지 않기

```java
// ✅ Good: Single iterator
Iterator<Message> messages = client.receiveMessages();

// ❌ Bad: Messages split across iterators
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();
```

### 5. 적절한 한도 설정하기

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(50)  // Long conversation
    .maxBudgetUsd(5.0)
    .build();

// ❌ Bad: No limits in interactive session
var client = ClaudeSDK.createClient();  // Could be expensive!
```

### 6. 모든 메시지 타입 처리하기

```java
// ✅ Good: Exhaustive pattern matching
for (var msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage u -> handleUser(u);
        case AssistantMessage a -> handleAssistant(a);
        case ResultMessage r -> handleResult(r);
        case SystemMessage s -> handleSystem(s);
        case StreamEvent e -> handleStream(e);
    }
}
```

### 7. 제어 메서드를 적절히 사용하기

```java
// ✅ Good: Switch models based on task complexity
if (isComplexTask) {
    client.setModel("claude-opus-4-6");
}

client.sendMessage(task);

// ✅ Good: Interrupt on timeout
CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
    .execute(() -> client.interrupt());
```

## 함께 보기

- [간단한 쿼리](./feature-simple-queries.md) — 일회성 쿼리용
- [설정 옵션](./feature-configuration-options.md) — 모든 ClaudeAgentOptions
- [메시지 타입](./feature-message-types.md) — 메시지 이해하기
- [ClaudeSDKClient API 참조](./api-claude-sdk-client.md) — 전체 API 문서
