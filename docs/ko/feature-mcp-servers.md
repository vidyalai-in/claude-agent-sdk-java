# MCP 서버 (Model Context Protocol)

MCP(Model Context Protocol)를 사용하면 대화 도중 Claude가 쓸 수 있는 사용자 정의 도구를 만들 수 있습니다. SDK는 인프로세스 SDK 서버와 외부 stdio/SSE/HTTP 서버를 모두 지원합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-mcp-servers.md)보다 오래되었을 수 있으며, 내용이 다를 경우 영어판이 우선합니다. 코드 블록은 영어 원문과 동일하게 유지하며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [SDK MCP 서버와 외부 서버](#sdk-mcp-서버와-외부-서버)
- [SDK MCP 서버 만들기](#sdk-mcp-서버-만들기)
- [@Tool 애너테이션 사용하기](#tool-애너테이션-사용하기)
- [도구 제목과 애너테이션](#도구-제목과-애너테이션)
- [프로그램으로 도구 만들기](#프로그램으로-도구-만들기)
- [도구 스키마](#도구-스키마)
- [도구 실행](#도구-실행)
- [프로토콜 세부 사항](#프로토콜-세부-사항)
- [사용자 정의 MCP 핸들러](#사용자-정의-mcp-핸들러)
- [외부 MCP 서버](#외부-mcp-서버)
- [MCP 서버 상태](#mcp-서버-상태)
- [예제](#예제)
- [모범 사례](#모범-사례)

## 개요

MCP(Model Context Protocol)는 대화 중에 Claude가 호출할 수 있는 사용자 정의 도구를 정의하는 표준적인 방법을 제공합니다. SDK는 다음을 지원합니다.

1. **SDK MCP 서버**(인프로세스) — 애플리케이션 안에서 바로 실행
2. **외부 MCP 서버** — 별도 프로세스로 실행(stdio/SSE/HTTP)

**주요 장점:**
- 사용자 정의 기능으로 Claude의 능력을 확장
- 애플리케이션의 상태와 API에 접근
- 타입 안전한 도구 정의
- 스키마 자동 생성
- CompletableFuture 기반의 비동기 실행

## SDK MCP 서버와 외부 서버

### SDK MCP 서버(인프로세스)

**장점:**
- ✅ **더 나은 성능**: IPC 오버헤드 없음
- ✅ **더 단순한 배포**: 단일 프로세스
- ✅ **더 쉬운 디버깅**: 같은 프로세스, 같은 디버거
- ✅ **직접 접근**: 애플리케이션 상태에 바로 접근
- ✅ **타입 안전성**: Java 타입 시스템
- ✅ **직렬화 불필요**: 직접 메서드 호출

**사용 사례:**
- 애플리케이션 전용 도구
- 데이터베이스 접근
- 비즈니스 로직
- 내부 API
- 테스트와 프로토타이핑

### 외부 MCP 서버

**장점:**
- ✅ **언어 무관**: 어떤 언어로든 작성 가능
- ✅ **격리**: 별도의 프로세스 공간
- ✅ **재사용성**: 여러 애플리케이션이 공유
- ✅ **보안**: 프로세스 샌드박싱

**사용 사례:**
- 서드파티 도구
- 특정 언어의 라이브러리(Node.js, Python)
- 공유 도구 서버
- 레거시 시스템

## SDK MCP 서버 만들기

SDK MCP 서버를 만드는 방법은 세 가지입니다.

1. `@Tool` 애너테이션 사용(선언적)
2. `SdkMcpTool.create()` 사용(프로그램적)
3. `SdkMcpServer.create()` 사용(수동)

### 빠른 시작

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import java.util.concurrent.CompletableFuture;

public class MyTools {
    @Tool(name = "greet", description = "Greet a user")
    public CompletableFuture<ToolResult> greet(String name) {
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
}

// Create server from annotated class
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

// Use in options
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__greet"))
    .build();
```

## @Tool 애너테이션 사용하기

`@Tool` 애너테이션은 도구를 선언적으로 정의하는 방법을 제공합니다.

### 기본 애너테이션

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

public class Calculator {

    @Tool(name = "add", description = "Add two numbers")
    public CompletableFuture<ToolResult> add(double a, double b) {
        double result = a + b;
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + result)
        );
    }

    @Tool(name = "multiply", description = "Multiply two numbers")
    public CompletableFuture<ToolResult> multiply(Map<String, Object> args) {
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + (a * b))
        );
    }
}
```

## 도구 제목과 애너테이션

### 도구 제목

기술적인 도구 이름과 구분되는 친숙한 표시 이름을 주려면 `title` 속성을 사용하세요.

```java
@Tool(
    name = "fetch_user_data",
    title = "User Data Fetcher",
    description = "Fetch user data from the database"
)
public CompletableFuture<ToolResult> fetchUserData(String userId) {
    // ...
}
```

제목은 MCP 2025-06-18이 두는 위치인 도구의 최상위와, 이전 개정판이 찾는 위치인 `annotations` 안쪽에 모두 전송됩니다 — 최상위 필드보다 오래된 클라이언트는 모르는 것을 떼어내기 때문입니다. 도구가 애너테이션을 선언했든 아니든 제목은 `tools/list`에 전달됩니다.

### 도구 애너테이션(의미적 힌트)

도구에 동작 힌트를 붙이려면 `annotations` 속성을 사용하세요. `ToolAnnotations` 인터페이스를 구현합니다.

```java
import in.vidyalai.claude.sdk.mcp.ToolAnnotations;

public class ReadOnlyHints implements ToolAnnotations {
    @Override
    public Boolean readOnlyHint() { return true; }
}

@Tool(
    name = "read_file",
    title = "File Reader",
    description = "Read the contents of a file",
    annotations = ReadOnlyHints.class
)
public CompletableFuture<ToolResult> readFile(String path) {
    // ...
}
```

사용할 수 있는 애너테이션 힌트:

| 힌트 | 설명 |
|------|-------------|
| `readOnlyHint` | 도구가 데이터를 읽기만 하고 상태를 바꾸지 않음 |
| `destructiveHint` | 도구가 되돌릴 수 없는 작업을 수행함 |
| `idempotentHint` | 같은 입력으로 반복 호출하면 같은 결과가 나옴 |
| `openWorldHint` | 도구가 외부 시스템에 질의하며 결과에 상한이 없음 |
| `maxResultSizeChars` | CLI가 임시 파일로 흘려보내기 전 결과의 최대 문자 수 |

### maxResultSizeChars (Anthropic 전용 힌트)

`maxResultSizeChars` 애너테이션은 CLI의 2계층 도구 결과 스필 임계값을 제어합니다. 기본적으로 CLI는 약 50K자를 넘는 도구 결과를 임시 파일로 흘려보냅니다. 이 애너테이션을 설정하면 특정 도구에 대해 그 임계값을 올리거나 내릴 수 있습니다.

MCP SDK의 Zod 스키마가 알 수 없는 애너테이션 필드를 제거하므로, `maxResultSizeChars`는 `tools/list` JSONRPC 응답에서 네임스페이스가 붙은 키 `anthropic/maxResultSizeChars`로 `_meta`를 통해 전달됩니다.

```java
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .maxResultSizeChars(200_000)  // Allow up to 200K chars
    .build();

SdkMcpTool<Map<String, Object>> bigResultTool = SdkMcpTool.builder("large_query", "Query returning large results")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text(runLargeQuery((String) args.get("query")))
    ))
    .annotations(hints)
    .build();
```

### 메서드 시그니처

애너테이션을 붙인 메서드는 두 가지 시그니처를 가질 수 있습니다.

#### 1. 타입이 지정된 매개변수(권장)

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(String firstName, String lastName) {
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + firstName + " " + lastName + "!")
    );
}
```

**요구 사항:**
- 매개변수 이름을 보존하려면 `-parameters` 플래그로 컴파일하세요
- 매개변수는 JSON Schema로 자동 매핑됩니다
- 타입 매핑:
  - `String` → `"string"`
  - `int`, `Integer`, `long`, `Long` → `"integer"`
  - `double`, `Double`, `float`, `Float` → `"number"`
  - `boolean`, `Boolean` → `"boolean"`
  - `Map<String, Object>` → `"object"`

#### 2. Map 매개변수

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
    String name = (String) args.get("name");
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + name + "!")
    );
}
```

**다음과 같은 경우에 사용하세요:**
- 매개변수를 직접 꺼내고 싶을 때
- 스키마가 복잡할 때
- 선택적 매개변수가 필요할 때

### 스키마 자동 생성

타입이 지정된 매개변수를 쓰면 SDK가 JSON Schema를 자동으로 생성합니다.

```java
@Tool(name = "search", description = "Search for items")
public CompletableFuture<ToolResult> search(String query, int limit) {
    // Implementation
}
```

생성되는 스키마:
```json
{
    "type": "object",
    "properties": {
        "query": {
            "type": "string"
        },
        "limit": {
            "type": "integer"
        }
    },
    "required": ["query", "limit"]
}
```

### 명시적 스키마

복잡한 스키마에는 명시적인 JSON을 제공하세요.

```java
@Tool(
    name = "search",
    description = "Search for items",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results",
                    "default": 10,
                    "minimum": 1,
                    "maximum": 100
                },
                "filters": {
                    "type": "object",
                    "properties": {
                        "category": {"type": "string"},
                        "minPrice": {"type": "number"}
                    }
                }
            },
            "required": ["query"]
        }
        """
)
public CompletableFuture<ToolResult> search(Map<String, Object> args) {
    // Implementation
}
```

### 애너테이션으로 서버 만들기

```java
// Create server from annotated instance
Calculator calculator = new Calculator();
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    calculator
);

// Or with version
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    "1.0.0",
    calculator
);
```

## 프로그램으로 도구 만들기

동적으로 도구를 만들려면 `SdkMcpTool.create()`나 빌더를 사용하세요.

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import java.util.concurrent.CompletableFuture;

// Simple creation
SdkMcpTool<Map<String, Object>> uppercaseTool = SdkMcpTool.create(
    "uppercase",                           // Tool name
    "Convert text to uppercase",           // Description
    Map.of(                                // JSON Schema
        "type", "object",
        "properties", Map.of(
            "text", Map.of(
                "type", "string",
                "description", "The text to convert"
            )
        ),
        "required", List.of("text")
    ),
    args -> {                              // Handler function
        String text = (String) args.get("text");
        return CompletableFuture.completedFuture(
            ToolResult.text(text.toUpperCase())
        );
    }
);

// With title and annotations using builder
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .idempotentHint(true)
    .build();

SdkMcpTool<Map<String, Object>> searchTool = SdkMcpTool.builder("search", "Search records")
    .title("Record Search")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text("Results for: " + args.get("query"))
    ))
    .annotations(hints)
    .build();
```

### 도구로 서버 만들기

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

// Create multiple tools
List<SdkMcpTool<?>> tools = List.of(
    uppercaseTool,
    lowercaseTool,
    reverseTool
);

// Create server
SdkMcpServer server = SdkMcpServer.create(
    "text-tools",  // Server name
    "1.0.0",       // Version
    tools          // Tool list
);

// Get config for options
McpSdkServerConfig config = server.toConfig();
```

## 도구 스키마

### JSON Schema 형식

도구의 `inputSchema`는 JSON Schema입니다. 핸들러가 실행되기 전에 인자가 이에 대해 검증됩니다([인자 검증](#인자-검증) 참고). 방언은 스키마의 `$schema` 키워드가 있으면 거기서 가져오고, 없으면 Draft 2020-12 — MCP 명세가 기준으로 삼는 방언 — 으로 간주합니다. Draft 4, 6, 7, 2019-09, 2020-12 모두 이해합니다.

```java
Map<String, Object> schema = Map.of(
    "type", "object",
    "properties", Map.of(
        "name", Map.of(
            "type", "string",
            "description", "User's name",
            "minLength", 1
        ),
        "age", Map.of(
            "type", "integer",
            "description", "User's age",
            "minimum", 0,
            "maximum", 150
        ),
        "email", Map.of(
            "type", "string",
            "format", "email"
        )
    ),
    "required", List.of("name", "email")
);
```

### 지원하는 타입

- `string` — 텍스트 값
- `integer` — 정수
- `number` — 부동소수점 수
- `boolean` — true/false
- `object` — 중첩 객체
- `array` — 값의 목록
- `null` — 널 값

### 제약

```java
Map.of(
    // String constraints
    "minLength", 1,
    "maxLength", 100,
    "pattern", "^[A-Z][a-z]+$",
    "format", "email",  // email, uri, date-time, etc.

    // Number constraints
    "minimum", 0,
    "maximum", 100,
    "exclusiveMinimum", true,
    "multipleOf", 5,

    // Array constraints
    "minItems", 1,
    "maxItems", 10,
    "uniqueItems", true,

    // Enum values
    "enum", List.of("red", "green", "blue")
);
```

## 도구 실행

### ToolResult

도구는 `ToolResult`를 반환해야 합니다(CompletableFuture로 감싸서).

```java
import in.vidyalai.claude.sdk.mcp.ToolResult;

// Text result
ToolResult.text("Hello, world!");

// JSON result — serialized into a single text block
ToolResult.json(Map.of("status", "success", "data", data));

// Image result (Base64)
ToolResult.image(base64Data, "image/png");

// Several content blocks
ToolResult.builder()
    .addText("Result:")
    .addJson(data)
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build();

// From raw MCP content blocks, normalized (see below)
ToolResult.ofContent(List.of(
    Map.of("type", "text", "text", "Result:"),
    Map.of("type", "resource_link", "name", "Docs", "uri", "https://example.com")
));

// Error result
ToolResult.error("Failed to process request");
```

#### 콘텐츠 블록

MCP는 CLI가 렌더링할 수 있는 것보다 더 많은 콘텐츠 타입을 정의하므로, CLI가 보여줄 수 없는 것들은 텍스트로 접힙니다 — Python SDK가 하는 변환과 같습니다.

| 블록 | 이렇게 됨 |
|---|---|
| `text` | 그대로 |
| `image` | 그대로 |
| `resource_link` | 텍스트: 이름, URI, 설명이 각각 한 줄씩. 비어 있는 항목은 건너뜀(모두 없으면 `Resource link`) |
| `text`를 담은 `resource` | 그 텍스트 |
| 바이너리 데이터를 담은 `resource` | 버리고 `WARNING`으로 기록 |
| 그 밖의 모든 것 | 버리고 `WARNING`으로 기록 |

`addResourceLink(...)`와 `addResource(...)`도 같은 규칙을 적용하므로, 핸들러는 그 규칙을 몰라도 결과를 블록 단위로 조립할 수 있습니다.

### 비동기 실행

도구는 CompletableFuture를 사용해 비동기로 실행됩니다.

```java
@Tool(name = "fetch_data", description = "Fetch data from API")
public CompletableFuture<ToolResult> fetchData(String url) {
    // Async HTTP request
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()))
        .exceptionally(e -> ToolResult.error(e.getMessage()));
}
```

### 오류 처리

모델이 봐야 할 실패는 `ToolResult.error(...)`를 반환해 알리세요. 이렇게 하면 `isError: true`가 붙은 결과가 만들어집니다.

```java
@Tool(name = "divide", description = "Divide two numbers")
public CompletableFuture<ToolResult> divide(double a, double b) {
    if (b == 0) {
        return CompletableFuture.completedFuture(
            ToolResult.error("Cannot divide by zero")
        );
    }

    return CompletableFuture.completedFuture(
        ToolResult.text("Result: " + (a / b))
    );
}
```

모든 것을 직접 잡을 필요는 없습니다. 예외를 던지는 핸들러나 예외적으로 완료되는 `CompletableFuture`도 같은 방식으로 보고됩니다(아래 [실패 방식](#실패-방식) 참고).

### 인자 검증

핸들러가 실행되기 전에 `tools/call`의 인자가 그 도구가 선언한 `inputSchema`에 대해 검증됩니다. 이는 MCP 서버에 요구되는 사항이며 — *"서버는 모든 도구 입력을 검증**해야 한다**"* — 핸들러는 자신이 공표한 계약에 맞는 인자만 보게 된다는 뜻이기도 합니다.

맞지 않는 호출은 `isError: true`인 도구 결과로 돌아오고, 텍스트는 `Input validation error:`로 시작하며 **핸들러는 호출되지 않습니다**.

```
{"count": 21}            -> handler runs, returns its result
{}                       -> Input validation error: required property 'count' not found
{"count": "twenty-one"}  -> Input validation error: /count string found, integer expected
```

믿고 기댈 만한 두 가지 결과가 있습니다.

- 부작용이 있는 도구가, 받아들이기로 하지도 않은 인자 때문에 실패하기 전에 작업을 절반만 적용해 버리는 일이 없습니다.
- 모델은 문제가 된 속성을 지목하는 문장을 받아 그에 따라 행동할 수 있습니다. 핸들러가 없는 값이나 타입이 틀린 값을 읽다가 우연히 던진 예외가 아니라요.

스키마가 없거나 비어 있는 도구는 검증되지 않습니다 — 대조할 대상이 없으니까요.

#### 올바른 JSON Schema가 아닌 스키마

검증은 Python SDK처럼 **닫힌 방향으로 실패**합니다. 서버를 구성할 때 각 `inputSchema`를 해당 방언의 메타 스키마에 대해 검사하며, 통과하지 못한 도구는 `WARNING`으로 기록되고 이후 그 도구에 대한 모든 호출은 핸들러를 실행하지 않은 채 `isError`로 돌아옵니다.

```
{"type": "object", "properties": "not-an-object"}
    -> Tool 'x' has an inputSchema this server cannot use, so it cannot be
       called: /properties string found, object expected
```

이것은 보이는 것보다 중요합니다. 검증기는 잘못된 스키마를 순순히 받아들인 다음 그에 따라 잘못 검증합니다. `{"type": "bogus"}`는 컴파일되지만 아무것도 매칭하지 않아서, 모든 호출이 진짜 결함이 아니라 호출자의 인자를 지목하며 실패합니다. 반대로 `"properties": "a string"`은 아예 무시되어 모든 호출을 검사 없이 통과시킵니다. 어느 쪽도 핸들러가 실행되어도 되는 상태가 아닙니다.

이 텍스트는 의도적으로 `Input validation error:`로 **시작하지 않습니다**. 그 접두사는 모델에게 인자가 틀렸다고 알리는 것이지만, 깨진 스키마는 모델이 우회할 수 없는 서버의 결함이며, 잘못 이름 붙이면 끝없는 재시도를 부릅니다. 알 수 없는 키워드는 여전히 합법입니다 — `x-vendor` 확장을 담은 스키마는 문제없이 통과합니다.

### 실패 방식

각 실패가 호출자에게 어떻게 전달되는지:

| 상황 | 응답 | 모델이 보는 것 |
|---|---|---|
| 핸들러가 `ToolResult.error(msg)`를 반환 | 결과, `isError: true` | `msg` |
| 핸들러가 던지거나 future가 실패 | 결과, `isError: true` | 예외 메시지, 메시지가 null/공백이면 클래스 이름 |
| 인자가 `inputSchema`와 맞지 않음 | 결과, `isError: true` | `Input validation error: …`(핸들러 미실행) |
| `inputSchema`가 올바른 JSON Schema가 아님 | 결과, `isError: true` | `… inputSchema this server cannot use …`(핸들러 미실행) |
| 도구 이름이 등록되지 않음 | 결과, `isError: true` | `Tool '<name>' not found` |
| 호출이 취소됨 | JSON-RPC 오류 `-32800` | 없음. CLI는 이미 포기함 |
| 이 서버가 구현하지 않은 메서드 | JSON-RPC 오류 `-32601` | 없음. 모델은 이런 것을 보내지 않음 |
| `params`가 없거나 잘못됨 | JSON-RPC 오류 `-32602` | 없음. 위와 동일 |

*도구 호출*이 마주칠 수 있는 모든 것은 **도구 실행 오류**입니다. 호출은 처리되었고 그 결과가 마침 실패를 설명할 뿐이므로, 그 텍스트는 모델이 읽고 적응할 수 있는 출력으로 전달됩니다. JSON-RPC 오류는 요청 자체를 처리할 수 없었다는 뜻이고 모델은 그것을 결코 보지 못합니다 — 알 수 없는 도구도 결과로 보고되는 이유이며, Python SDK와 일치합니다. 이 방식은 SDK 자체의 규약이므로 도구는 두 SDK에서 동일하게 동작합니다.

### 실행 중인 도구 취소하기

CLI는 MCP 도구 호출에 자체 타임아웃(`MCP_TOOL_TIMEOUT`)을 적용합니다. 타임아웃이 발생하면 CLI는 기다리기를 멈추고 MCP의 `notifications/cancelled`를 보냅니다. SDK는 대기 중인 호출에 `-32800`으로 답하고, 핸들러가 결국 반환하는 것은 버립니다.

핸들러 자신은 들여다보지 않는 한 계속 실행됩니다. `CompletableFuture`는 바깥에서 중단할 수 없고 — `cancel(true)`는 future를 완료시킬 뿐 작업은 그대로 둡니다 — 따라서 오래 걸리거나 부작용이 있는 도구라면 인자와 함께 `ToolCallContext`를 받아야 합니다.

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
        "crawl", "Fetch every page under a URL", schema,
        (args, context) -> CompletableFuture.supplyAsync(() -> {
            List<String> pages = new ArrayList<>();
            for (String url : urlsFrom(args)) {
                if (context.isCancelled()) {
                    break;              // nobody is waiting for this any more
                }
                pages.add(fetch(url));
            }
            return ToolResult.text(String.join("\n", pages));
        }));
```

`context.onCancel(runnable)`은 폴링할 수 없는 작업 — 블로킹 읽기, 다른 서비스 호출 — 을 위해 리소스를 닫을 자리를 제공합니다. 호출이 이미 취소되었다면 즉시 실행됩니다. `context.throwIfCancelled()`는 그냥 풀고 나가고 싶은 핸들러를 위한 체크포인트 형태입니다.

인자만 받는 핸들러는 예전과 똑같이 동작합니다. 단지 취소를 관찰하지 못할 뿐입니다. `@Tool`이 붙은 메서드는 시그니처 어디에서든 `ToolCallContext` 매개변수를 선언할 수 있습니다 — 주입되며, 도구가 공표하는 스키마에는 나타나지 않습니다.

연결을 끊어도 효과는 같습니다. 클라이언트를 닫으면 진행 중이던 호출이 포기되므로, 아무도 중단할 수 없는 도구 때문에 종료가 지연되지 않습니다.

핸들러의 실패는 스택 트레이스와 함께 로컬에서도 `WARNING`으로 기록되므로, 망가진 도구를 디버깅할 때 모델의 트랜스크립트만이 유일한 기록이 되는 일은 없습니다.

### 오래 걸리는 작업

```java
@Tool(name = "process_large_file", description = "Process a large file")
public CompletableFuture<ToolResult> processFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Long-running operation
            byte[] data = Files.readAllBytes(Path.of(path));
            String result = processData(data);
            return ToolResult.text("Processed: " + result);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    });
}
```

## 프로토콜 세부 사항

### 프로토콜 버전

서버는 `2025-06-18`과 `2024-11-05`를 최신 순으로 공표합니다. `initialize`에서는 클라이언트가 요청한 버전을 서버가 말할 수 있으면 그대로 돌려주고, 아니면 자신이 아는 가장 최신 버전으로 답합니다 — 명세가 규정하는 핸드셰이크입니다.

`2025-03-26`은 의도적으로 주장하지 않습니다. 그 개정판은 JSON-RPC 배치를 *수신*하는 것을 필수로 만들었는데, 배치는 최상위 배열이고 이 메시지들을 나르는 제어 요청은 그것을 맵으로 타입 지정하므로 표현할 수 없습니다. SDK가 지킬 수 없는 단 하나의 필수 변경이 있는 버전을 주장하는 것은, 클라이언트가 믿고 행동할 약속을 하는 셈입니다.

### 메서드

`initialize`, `ping`, `tools/list`, `tools/call`이 구현되어 있습니다. 그 밖의 것은 모두 `-32601`로 답하는데, 이는 빠진 것이 아니라 올바른 동작입니다. 서버는 `tools` 능력만 공표하므로 규격에 맞는 클라이언트는 resources, prompts, completions를 요청하지 않습니다. (CLI로 검증함: `tools`만 선언한 서버에는 `resources/list`나 `prompts/list`가 오지 않습니다.)

### 알림

JSON-RPC 알림 — `method`가 있고 `id`가 없는 메시지 — 에는 JSON-RPC가 요구하는 대로 결코 응답하지 않습니다. `notifications/initialized`와 `notifications/cancelled`는 처리하고, 그 밖의 것은 `FINE`으로 기록한 뒤 버립니다. 그 알림을 실어 온 *제어 요청*에는 `{"jsonrpc": "2.0", "result": {}}`로 확인 응답을 보냅니다. 그러지 않으면 CLI가 영원히 기다립니다.

`method`가 아예 없는 메시지는 JSON-RPC 응답이거나 쓰레기입니다. SDK는 CLI에 요청을 보내지 않으므로 그렇게 도착한 것은 짝지을 대상이 없습니다. 답하지 않고 무시합니다.

## 사용자 정의 MCP 핸들러

`McpSdkServerConfig`가 담는 것은 `McpMessageHandler`이지, 꼭 `SdkMcpServer`인 것은 아닙니다. `SdkMcpServer`가 다루지 않는 MCP 영역 — resources, prompts, completions — 을 제공하거나 서드파티 MCP 라이브러리를 맞추려면 이 인터페이스를 직접 구현하세요.

```java
public class MyMcpServer implements McpMessageHandler {

    @Override
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        // Return the JSON-RPC response for a request, or null for a
        // notification, which must never be answered.
        ...
    }

    @Override
    public void close() {
        // Optional: the connection using this handler is going away.
    }
}

var options = ClaudeAgentOptions.builder()
        .mcpServers(Map.of("mine", new McpSdkServerConfig("mine", new MyMcpServer())))
        .build();
```

CLI가 무엇을 보낼지는 `initialize`가 반환하는 `capabilities`가 결정하므로, resources를 공표한 핸들러에는 resources 요청이 옵니다.

`close()`는 "당신을 쓰던 연결이 사라진다"는 뜻이지 "종료하라"가 아닙니다. 하나의 핸들러가 여러 클라이언트에 등록될 수 있으므로 멱등해야 하고 그 뒤에도 사용 가능해야 합니다. 같은 이유로 연결마다 `SdkMcpServer`를 하나씩 등록하세요 — 두 개의 살아 있는 연결이 하나의 서버를 공유하면 같은 JSON-RPC id를 낼 수 있고, 그럴 경우 응답이 엉뚱한 호출자에게 갈 위험을 감수하는 대신 두 번째 호출이 `-32603`으로 거부됩니다.

## 외부 MCP 서버

### Stdio 서버

```java
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;

McpStdioServerConfig server = new McpStdioServerConfig(
    "node",                              // Command
    List.of("path/to/server.js"),        // Arguments
    Map.of("NODE_ENV", "production")     // Environment variables
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("external", server))
    .build();
```

### SSE 서버

```java
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;

McpSseServerConfig server = new McpSseServerConfig(
    "http://localhost:8080/sse"  // SSE endpoint URL
);
```

### HTTP 서버

```java
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;

McpHttpServerConfig server = new McpHttpServerConfig(
    "http://localhost:8080"  // Base URL
);
```

### 혼합 서버

SDK 서버와 외부 서버를 함께 쓸 수 있습니다.

```java
// SDK server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "app-tools",
    new MyTools()
);

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("external-server.js"),
    Map.of()
);

// Configure both
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "app", sdkServer,
        "external", externalServer
    ))
    .allowedTools(List.of(
        "mcp__app__my_tool",
        "mcp__external__their_tool"
    ))
    .build();
```

### 엄격한 MCP 설정

기본적으로 CLI는 `mcpServers(...)`로 넘긴 것 외에도 프로젝트의 `.mcp.json`, 사용자/전역 설정, 각종 플러그인에서 MCP 서버를 읽어 옵니다. `strictMcpConfig(true)`를 설정하면 넘긴 서버 외에는 모두 무시합니다.

```java
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("app", sdkServer))
    .strictMcpConfig(true)   // ignore project / user / plugin MCP configs
    .build();
```

CLI의 `--strict-mcp-config` 플래그에 대응합니다. 어떤 MCP 서버에 닿을 수 있는지 정확히 통제하고 싶은 재현 가능한 배포나 테스트 격리에 유용합니다.

## 예제

### 예제 1: 계산기

```java
public class Calculator {

    @Tool(name = "calculate", description = "Perform calculations")
    public CompletableFuture<ToolResult> calculate(
            double a, double b, String operation) {

        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> {
                if (b == 0) {
                    return CompletableFuture.completedFuture(
                        ToolResult.error("Cannot divide by zero")
                    );
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException(
                "Unknown operation: " + operation
            );
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(a + " " + operation + " " + b + " = " + result)
        );
    }
}

// Usage
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    new Calculator()
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", server))
    .allowedTools(List.of("mcp__calc__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Calculate 15 * 7, then 100 / 4");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 예제 2: 데이터베이스 접근

```java
public class DatabaseTools {
    private final DataSource dataSource;

    public DatabaseTools(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "query_users", description = "Query users from database")
    public CompletableFuture<ToolResult> queryUsers(String filter) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE name LIKE ?")) {

                stmt.setString(1, "%" + filter + "%");
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(Map.of(
                        "id", rs.getInt("id"),
                        "name", rs.getString("name"),
                        "email", rs.getString("email")
                    ));
                }

                return ToolResult.json(Map.of(
                    "count", users.size(),
                    "users", users
                ));

            } catch (SQLException e) {
                return ToolResult.error("Database error: " + e.getMessage());
            }
        });
    }
}
```

### 예제 3: API 연동

```java
public class WeatherTools {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public WeatherTools(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "get_weather", description = "Get current weather")
    public CompletableFuture<ToolResult> getWeather(String city) {
        String url = "https://api.weather.com/weather?city=" + city +
                     "&key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                // Parse JSON response
                Map<String, Object> data = parseJson(response.body());
                return ToolResult.json(data);
            })
            .exceptionally(e -> ToolResult.error(
                "Failed to fetch weather: " + e.getMessage()
            ));
    }
}
```

## 모범 사례

### 1. 알맞은 반환 타입 쓰기

```java
// ✅ Good: Specific result types
ToolResult.text("Simple text response");
ToolResult.json(Map.of("key", "value"));
ToolResult.error("Error message");

// ❌ Bad: Always using text for structured data
ToolResult.text("{\"key\":\"value\"}");  // Should use json()
```

### 2. 오류를 우아하게 처리하기

```java
// ✅ Good: Proper error handling
@Tool(name = "read_file", description = "Read a file")
public CompletableFuture<ToolResult> readFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            String content = Files.readString(Path.of(path));
            return ToolResult.text(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    });
}

// ❌ Bad: Throwing exceptions
public CompletableFuture<ToolResult> readFile(String path) {
    String content = Files.readString(Path.of(path));  // Throws!
    return CompletableFuture.completedFuture(ToolResult.text(content));
}
```

### 3. 좋은 설명 쓰기

```java
// ✅ Good: Descriptive and clear
@Tool(
    name = "search_products",
    description = "Search for products by name, category, or price range. " +
                  "Returns a list of matching products with details."
)

// ❌ Bad: Vague description
@Tool(name = "search", description = "Search")
```

### 4. 복잡한 입력에는 명시적 스키마 쓰기

SDK가 인자를 검증하는 기준은 선언된 스키마이므로, 입력을 정확하게 기술할수록 핸들러가 당연하게 여길 수 있는 것이 많아지고, 모델이 도구를 잘못 호출했을 때 돌려받는 메시지도 더 쓸모 있어집니다. 스키마가 없는 도구는 전혀 검증되지 않습니다.

```java
// ✅ Good: Explicit schema with validation
@Tool(
    name = "create_user",
    description = "Create a new user",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "email": {"type": "string", "format": "email"},
                "age": {"type": "integer", "minimum": 18}
            },
            "required": ["email"]
        }
        """
)

// ❌ Bad: No validation
@Tool(name = "create_user", description = "Create user")
public CompletableFuture<ToolResult> createUser(Map<String, Object> args)
```

### 5. 도구는 한 가지 일에 집중하기

```java
// ✅ Good: Single responsibility
@Tool(name = "add_numbers", description = "Add two numbers")
@Tool(name = "multiply_numbers", description = "Multiply two numbers")

// ❌ Bad: Too much in one tool
@Tool(name = "math", description = "Do any math operation")
```

### 6. I/O 작업에는 비동기 쓰기

```java
// ✅ Good: Async I/O
@Tool(name = "fetch", description = "Fetch URL")
public CompletableFuture<ToolResult> fetch(String url) {
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()));
}

// ❌ Bad: Blocking I/O
public CompletableFuture<ToolResult> fetch(String url) {
    String result = blockingHttpCall(url);  // Blocks!
    return CompletableFuture.completedFuture(ToolResult.text(result));
}
```

### 7. 도구 권한 설정하기

```java
// ✅ Good: Explicitly allow tools
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .allowedTools(List.of(
        "mcp__calc__add",
        "mcp__calc__subtract"
    ))
    .build();

// ❌ Bad: Allowing all tools (security risk)
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .build();  // All tools allowed!
```

## MCP 서버 상태

`ClaudeSDKClient.getMcpStatus()`는 설정된 모든 MCP 서버의 현재 연결 상태를 담은 `McpStatusResponse`를 반환합니다.

### McpStatusResponse

```java
record McpStatusResponse(
    List<McpServerStatus> mcpServers   // list of server status entries
)
```

### McpServerStatus

```java
record McpServerStatus(
    String name,                               // server name as configured
    McpServerConnectionStatus status,          // connection state
    @Nullable McpServerInfo serverInfo,        // info from MCP handshake (when connected)
    @Nullable String error,                    // error message (when status = FAILED)
    @Nullable McpServerStatusConfig config,    // server configuration
    @Nullable String scope,                    // config scope (project, user, local)
    @Nullable List<McpToolInfo> tools          // available tools (when connected)
)
```

### McpServerConnectionStatus

```java
enum McpServerConnectionStatus {
    CONNECTED,    // server is connected and ready
    FAILED,       // connection attempt failed
    NEEDS_AUTH,   // server requires authentication
    PENDING,      // connection in progress
    DISABLED      // server is disabled
}
```

### McpServerInfo

```java
record McpServerInfo(
    String name,      // server name from MCP handshake
    String version    // server version from MCP handshake
)
```

### McpToolInfo

```java
record McpToolInfo(
    String name,                               // tool name
    @Nullable String description,              // tool description
    @Nullable McpToolAnnotations annotations   // behavioral hints
)
```

### McpServerStatusConfig (봉인 인터페이스)

상태 응답에 담긴 서버 설정을 나타냅니다. 다형적이므로 패턴 매칭을 쓰세요.

```java
switch (server.config()) {
    case McpStdioServerConfig c -> System.out.println("stdio: " + c.command());
    case McpSseServerConfig c -> System.out.println("sse: " + c.url());
    case McpHttpServerConfig c -> System.out.println("http: " + c.url());
    case McpSdkServerConfigStatus c -> System.out.println("sdk: " + c.name());
    case McpClaudeAIProxyServerConfig c -> System.out.println("proxy: " + c.url());
    case null -> {}
}
```

### 예제: MCP 상태 확인하기

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    McpStatusResponse status = client.getMcpStatus();
    for (McpServerStatus server : status.mcpServers()) {
        System.out.printf("[%s] %s%n", server.status(), server.name());
        if (server.status() == McpServerConnectionStatus.CONNECTED) {
            if (server.tools() != null) {
                server.tools().forEach(t -> System.out.println("  - " + t.name()));
            }
        } else if (server.status() == McpServerConnectionStatus.FAILED) {
            System.err.println("  Error: " + server.error());
        }
    }
}
```

## 함께 보기

- [설정 옵션](./feature-configuration-options.md) — mcpServers와 tools 옵션
- [도구 사용 예제](../../examples/src/main/java/examples/McpServer.java) — 완전한 예제
- [스키마 자동 생성 예제](../../examples/src/main/java/examples/AutoSchemaGeneration.java)
- [MCP 명세](https://spec.modelcontextprotocol.io/) — 공식 MCP 프로토콜 문서
