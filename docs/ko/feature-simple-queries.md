# 간단한 질의

간단한 질의는 `ClaudeSDK` 파사드를 이용해 일회성·무상태 작업으로 Claude와 상호작용하는 가장 직관적인
방법입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-simple-queries.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [간단한 질의를 쓸 때](#간단한-질의를-쓸-때)
- [기본 사용법](#기본-사용법)
- [질의 메서드](#질의-메서드)
- [구성 옵션](#구성-옵션)
- [메시지 처리](#메시지-처리)
- [예제](#예제)
- [모범 사례](#모범-사례)

## 개요

`ClaudeSDK` 클래스는 간단하고 무상태인 질의를 위한 정적 메서드를 제공합니다. 다음의 복잡한 일들을
모두 알아서 처리합니다:
- 전송 생성과 관리
- QueryHandler 준비
- 메시지 파싱
- 자원 정리

**주요 특징:**
- **단방향**: 메시지를 한꺼번에 보내고 응답을 한꺼번에 받습니다
- **무상태**: 각 질의는 서로 독립적입니다
- **간단함**: 보내고 잊는 방식
- **중단 불가**: 중단할 수도, 후속 메시지를 보낼 수도 없습니다
- **자동 정리**: 자원은 내부에서 관리됩니다

## 간단한 질의를 쓸 때

### ✅ 잘 맞는 경우

1. **일회성 질문**
   ```java
   ClaudeSDK.query("What is the capital of France?");
   ```

2. **일괄 처리**
   ```java
   for (String prompt : prompts) {
       List<Message> result = ClaudeSDK.query(prompt, options);
       processResult(result);
   }
   ```

3. **코드 생성**
   ```java
   String code = ClaudeSDK.queryForText(
       "Generate a Java function to reverse a string",
       options);
   ```

4. **CI/CD 파이프라인**
   ```java
   String review = ClaudeSDK.queryForText(
       "Review this code for security issues: " + code,
       options);
   ```

5. **자동화 스크립트**
   ```java
   ResultMessage result = ClaudeSDK.queryForResult(
       "Analyze this log file",
       options);
   System.out.println("Cost: $" + result.totalCostUsd());
   ```

### ❌ 맞지 않는 경우

1. **대화형 세션** — 대신 `ClaudeSDKClient`를 사용하세요
2. **채팅 인터페이스** — 멀티턴에는 `ClaudeSDKClient`를
3. **후속 질문** — 맥락이 필요하면 `ClaudeSDKClient`를
4. **중단 기능** — 제어가 필요하면 `ClaudeSDKClient`를
5. **오래 도는 세션** — 상태가 필요하면 `ClaudeSDKClient`를

## 기본 사용법

### 가장 단순한 질의(기본 옵션)

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;

List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
```

### 옵션이 있는 질의

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
```

### 텍스트만 얻기

```java
String answer = ClaudeSDK.queryForText(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println(answer); // "4"
```

### 결과 메시지 얻기

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Stop reason: " + result.stopReason());
```

## 질의 메서드

### 1. query(String prompt)

기본 옵션으로 질의를 실행합니다.

```java
List<Message> messages = ClaudeSDK.query("Hello, Claude!");
```

**반환**: `List<Message>` — 그 대화의 모든 메시지

### 2. query(String prompt, ClaudeAgentOptions options)

사용자 지정 옵션으로 질의를 실행합니다.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("Hello!", options);
```

**매개변수**:
- `prompt` — 질문 또는 지시
- `options` — 구성 옵션

**반환**: `List<Message>` — 그 대화의 모든 메시지

**던짐**:
- `IllegalArgumentException` — `canUseTool`과 `permissionPromptToolName`이 둘 다 설정된 경우
- `CLIConnectionException` — 연결에 실패한 경우
- `ProcessException` — CLI 프로세스가 실패한 경우

### 3. query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

여러 메시지로 스트리밍 질의를 실행합니다.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

**매개변수**:
- `messageStream` — 메시지 딕셔너리의 이터레이터
- `options` — 구성 옵션

**반환**: `List<Message>` — 그 대화의 모든 메시지

**메시지 형식**:
```java
{
    "type": "user",
    "session_id": "default",
    "message": {
        "role": "user",
        "content": "Your message here"
    }
}
```

### 4. queryForText(String prompt, ClaudeAgentOptions options)

어시스턴트 메시지의 텍스트 내용만 돌려주는 편의 메서드입니다.

```java
String text = ClaudeSDK.queryForText(
    "What is the capital of France?",
    ClaudeAgentOptions.defaults()
);
```

**반환**: `String` — 모든 어시스턴트 메시지의 텍스트 내용을 이어 붙인 것

### 5. queryForResult(String prompt, ClaudeAgentOptions options)

결과 메시지만 돌려주는 편의 메서드입니다.

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "Analyze this code",
    options
);

System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Input tokens: " + result.usageInput());
System.out.println("Output tokens: " + result.usageOutput());
```

**반환**: `ResultMessage` — 최종 결과, 없으면 null

## 구성 옵션

### 간단한 질의에 꼭 필요한 옵션

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model selection
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-4-5")

    // Limits
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .maxThinkingTokens(10000)

    // System prompt
    .systemPrompt("You are a helpful assistant. Be concise.")

    // Tools
    .allowedTools(List.of("Read", "Grep"))
    .disallowedTools(List.of("Write", "Edit"))

    // Permissions
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)

    // Working directory
    .cwd(Path.of("/path/to/project"))

    // Environment
    .env(Map.of("KEY", "value"))

    .build();
```

### 자주 쓰는 패턴

#### 빠른 읽기 전용 질의
```java
var options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .maxTurns(5)
    .build();
```

#### 예산이 제한된 질의
```java
var options = ClaudeAgentOptions.builder()
    .maxBudgetUsd(0.10)  // Limit to 10 cents
    .maxTurns(3)
    .model("claude-haiku-4-5")  // Use cheaper model
    .build();
```

#### 빠른 단일 턴 질의
```java
var options = ClaudeAgentOptions.builder()
    .maxTurns(1)
    .model("claude-haiku-4-5")
    .systemPrompt("Be extremely concise.")
    .build();
```

## 메시지 처리

### 모든 메시지 처리하기

```java
List<Message> messages = ClaudeSDK.query("Hello!", options);

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());
            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Used tool: " + tool.name());
                }
            }
        }

        case ResultMessage result ->
            System.out.println("Cost: $" + result.totalCostUsd());

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            // Usually not present in simple queries
            System.out.println("Stream event: " + event);
    }
}
```

### 특정 정보 뽑아내기

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Get last assistant message
AssistantMessage lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second)
    .orElse(null);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n"));

// Get result
ResultMessage result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst()
    .orElse(null);
```

## 예제

### 예제 1: 코드 리뷰

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class CodeReview {
    public static void main(String[] args) {
        String code = """
            public void processUser(User user) {
                db.save(user);  // No null check!
            }
            """;

        var options = ClaudeAgentOptions.builder()
            .systemPrompt("You are a code reviewer. Focus on bugs and security.")
            .maxTurns(1)
            .build();

        String review = ClaudeSDK.queryForText(
            "Review this code for issues:\n" + code,
            options
        );

        System.out.println(review);
    }
}
```

### 예제 2: 일괄 번역

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class Translator {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-haiku-4-5")  // Fast and cheap
            .maxTurns(1)
            .systemPrompt("Translate to French. Return only the translation.")
            .build();

        List<String> phrases = List.of(
            "Hello, how are you?",
            "The weather is nice today.",
            "I love programming."
        );

        for (String phrase : phrases) {
            String translation = ClaudeSDK.queryForText(phrase, options);
            System.out.println(phrase + " -> " + translation);
        }
    }
}
```

### 예제 3: 로그 분석

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import java.nio.file.Path;

public class LogAnalyzer {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .cwd(Path.of("/path/to/logs"))
            .allowedTools(List.of("Read", "Grep"))
            .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
            .maxTurns(10)
            .build();

        String analysis = ClaudeSDK.queryForText(
            "Analyze error.log and summarize all ERROR level messages",
            options
        );

        System.out.println(analysis);
    }
}
```

### 예제 4: 비용을 의식한 질의

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

public class CostAwareQuery {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .maxBudgetUsd(0.05)  // 5 cent limit
            .build();

        ResultMessage result = ClaudeSDK.queryForResult(
            "Explain quantum computing",
            options
        );

        if (result != null) {
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Input tokens: " + result.usageInput());
            System.out.println("Output tokens: " + result.usageOutput());
            System.out.println("Stop reason: " + result.stopReason());
        }
    }
}
```

## 모범 사례

### 1. 적절한 옵션 사용하기

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .build();

// ❌ Bad: No limits
ClaudeSDK.query(longComplexTask);  // Could be expensive!
```

### 2. 모든 메시지 타입 다루기

```java
// ✅ Good: Pattern matching handles all types
switch (message) {
    case AssistantMessage a -> process(a);
    case ResultMessage r -> logCost(r);
    case UserMessage u -> log(u);
    case SystemMessage s -> log(s);
    case StreamEvent e -> log(e);
}

// ❌ Bad: Only handling one type
if (message instanceof AssistantMessage) {
    // Missing other types!
}
```

### 3. 적절할 때 편의 메서드 쓰기

```java
// ✅ Good: Simple use case
String answer = ClaudeSDK.queryForText(prompt, options);

// ❌ Overkill: Manual extraction
List<Message> messages = ClaudeSDK.query(prompt, options);
String answer = messages.stream()...  // Complex extraction
```

### 4. 파일 작업에는 작업 디렉터리 지정하기

```java
// ✅ Good: Explicit working directory
var options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/project/root"))
    .allowedTools(List.of("Read", "Write"))
    .build();

// ❌ Bad: Using current directory (unpredictable)
ClaudeSDK.query("Read config.json", options);
```

### 5. 알맞은 모델 고르기

```java
// ✅ Good: Match model to task
var fastOptions = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Quick, simple tasks
    .build();

var complexOptions = ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")  // Complex reasoning
    .build();

// ❌ Bad: Using opus for simple tasks (expensive)
ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")
    .build();
ClaudeSDK.query("What is 2+2?", options);  // Overkill!
```

### 6. 멀티턴 상황에는 스트리밍 쓰기

```java
// ✅ Good: Streaming for multiple messages
var messages = List.of(
    Map.of("type", "user", ...),
    Map.of("type", "user", ...)
);
ClaudeSDK.query(messages.iterator(), options);

// ❌ Bad: Multiple separate queries (loses context)
ClaudeSDK.query("First question", options);
ClaudeSDK.query("Follow-up", options);  // No context!
```

### 7. 오류 처리하기

```java
// ✅ Good: Handle exceptions
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
    // Process messages
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI process failed: " + e.getMessage());
}

// ❌ Bad: No error handling
ClaudeSDK.query(prompt, options);  // Could throw!
```

## 관련 항목

- [대화형 세션](./feature-interactive-conversations.md) — 멀티턴 대화용
- [구성 옵션](./feature-configuration-options.md) — 전체 옵션 가이드
- [메시지 타입](./feature-message-types.md) — 메시지 이해하기
- [ClaudeSDK API 레퍼런스](./api-claude-sdk.md) — 상세 API 문서
