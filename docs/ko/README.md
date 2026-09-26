# Claude Agent SDK for Java

[English](../../README.md) · [简体中文](../zh/README.md) · [日本語](../ja/README.md) · **한국어** · [Português](../pt/README.md) · [Español](../es/README.md)

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../../README.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다. 자세한 내용은 [docs/TRANSLATIONS.md](../TRANSLATIONS.md)를 참고하세요.

Claude Agent를 위한 Java SDK입니다. 이 SDK는 Claude Code와 상호작용하기 위한 포괄적인 Java API를 제공하여, Claude의 기능을 활용한 AI 애플리케이션을 만들 수 있게 해줍니다.

## 요구 사항

- Java 17 이상 (sealed 인터페이스와 record를 사용합니다)
  - Java 21 이상에서는 SDK가 백그라운드 작업을 가상 스레드에서 실행합니다. 17~20에서는
    대신 이름이 지정된 데몬 플랫폼 스레드를 사용합니다. 별도의 설정은 필요 없습니다.
- Maven 3.6+

**참고:** Claude Code CLI는 별도로 설치해야 합니다:
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

또는 사용자 지정 경로를 지정합니다:
```java
ClaudeAgentOptions.builder()
    .cliPath(Path.of("/path/to/claude"))
    .build();
```

## 설치

Maven Central에 배포되어 있으므로 저장소나 인증 설정이 필요하지 않습니다.

### Maven

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

### Gradle

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("in.vidyalai:claude-agent-sdk-java:0.2.2")
}
```

### 대안: GitHub Packages

이미 GitHub Packages에 의존하고 있는 사용자를 위해 릴리스는 그곳에도 미러링됩니다.
아티팩트가 공개되어 있음에도 이 경로는 GitHub 개인 액세스 토큰을 요구하므로, 특별한 이유가
없다면 Maven Central을 사용하세요.

<details>
<summary>GitHub Packages 설정</summary>

`pom.xml`에 저장소를 추가합니다:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java</url>
    </repository>
</repositories>
```

또는 `build.gradle.kts`에 추가합니다:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
        }
    }
}
```

그런 다음 인증을 설정합니다. Maven의 경우 `~/.m2/settings.xml`에 다음을 추가합니다:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

Gradle의 경우 `~/.gradle/gradle.properties`를 만들거나 수정합니다:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

`read:packages` 범위를 가진 개인 액세스 토큰은 https://github.com/settings/tokens 에서 생성하세요.

</details>

## 빠른 시작

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.Message;
import in.vidyalai.claude.sdk.types.AssistantMessage;

public class QuickStart {
    public static void main(String[] args) {
        // Simple one-shot query
        for (Message message : ClaudeSDK.query("What is 2 + 2?")) {
            if (message instanceof AssistantMessage assistant) {
                System.out.println(assistant.getTextContent());
            }
        }
    }
}
```

## 기본 사용법: ClaudeSDK.query()

`ClaudeSDK.query()`는 간단한 일회성 질의를 위한 것입니다. 모든 응답 메시지를 담은
`List<Message>`를 반환합니다.

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.*;

// Simple query
List<Message> messages = ClaudeSDK.query("Hello Claude");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        for (ContentBlock block : assistant.content()) {
            if (block instanceof TextBlock text) {
                System.out.println(text.text());
            }
        }
    }
}

// With options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .systemPrompt("You are a helpful assistant")
    .maxTurns(1)
    .build();

List<Message> response = ClaudeSDK.query("Tell me a joke", options);
```

### 편의 메서드

```java
// Get just the text response
String text = ClaudeSDK.queryForText("What is the capital of France?");
System.out.println(text);  // "Paris"

// Get just the result message
ResultMessage result = ClaudeSDK.queryForResult("Do something", options);
System.out.println("Cost: $" + result.totalCostUsd());
```

### 도구 사용하기

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write", "Bash"))
    .permissionMode(PermissionMode.ACCEPT_EDITS)  // Auto-accept file edits
    .build();

List<Message> messages = ClaudeSDK.query("Create a hello.py file", options);

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        if (assistant.hasToolUse()) {
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock toolUse) {
                    System.out.println("Tool: " + toolUse.name());
                    System.out.println("Input: " + toolUse.input());
                }
            }
        }
    }
}
```

### 작업 디렉터리

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/path/to/project"))
    .build();
```

### 스트리밍 입력 (여러 메시지)

```java
// Send multiple messages in sequence
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up question"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

### 시스템 프롬프트

기본적으로 Claude Code는 세션의 첫 요청에서 시스템 프롬프트를 구성해 기록해 두고, 세션을 재개한 뒤를
포함해 이후의 모든 요청에서 이를 재사용합니다. 따라서 사용자 정의 프롬프트를 바꾸거나 `claude_code`
프리셋의 `append` 텍스트를 바꾸더라도, 세션이 압축(compact)되거나 새 세션을 시작하기 전까지는 효과가
없습니다. 예를 들어 프롬프트 문구를 반복해서 다듬는 중이라서 매 요청마다 프롬프트를 다시 구성하고 싶다면,
`SystemPromptCustom` 또는 `SystemPromptPreset`에서 `snapshot`을 `false`로 설정하세요:

```java
var options = ClaudeAgentOptions.builder()
    .systemPrompt(SystemPromptCustom.of("You are a release bot.", false))
    // or: .systemPrompt(SystemPromptPreset.claudeCode("Be concise.").withSnapshot(false))
    .build();
```

Claude Code CLI 2.1.257 이상이 필요합니다. 2.1.265 이전에는 `append` 또는 사용자 정의 프롬프트를 쓰는
세션이 `snapshot`이 `true`일 때만 프롬프트를 기록했습니다. 자세한 내용은
[시스템 프롬프트 수정하기](https://code.claude.com/docs/en/agent-sdk/modifying-system-prompts#change-the-prompt-of-an-existing-session)를
참고하세요.

## ClaudeSDKClient

`ClaudeSDKClient`는 Claude Code와의 양방향 대화형 세션을 지원합니다. `query()`와 달리
**멀티턴 대화**, **사용자 정의 도구**, **훅(hooks)**, **실시간 상호작용**을 가능하게 합니다.

### 기본 사용법

```java
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.*;

// Using try-with-resources (recommended)
try (var client = ClaudeSDK.createClient()) {
    client.connect("Hello, Claude!");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}

// Multi-turn conversation
try (var client = ClaudeSDK.createClient()) {
    // First turn
    client.connect("What is machine learning?");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }

    // Follow-up
    client.sendMessage("Can you give me an example?");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }
}
```

### 연결 수동 관리

```java
ClaudeSDKClient client = new ClaudeSDKClient();
try {
    client.connect("Start a conversation");

    // Receive messages
    for (Message msg : client.receiveResponse()) {
        process(msg);
    }

    // Send follow-up
    client.sendMessage("Continue...");
    for (Message msg : client.receiveResponse()) {
        process(msg);
    }
} finally {
    client.disconnect();
}
```

### 실행 중단

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Do a long task");

    // In another thread or after some condition
    client.interrupt();  // Sends interrupt signal
}
```

### 모델 동적 전환

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start with default model");

    // Switch to a different model mid-conversation
    client.setModel("claude-sonnet-5");

    client.sendMessage("Continue with new model");
}
```

### 권한 모드 변경

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start in default mode");

    // Change permission mode during conversation
    client.setPermissionMode(PermissionMode.BYPASS_PERMISSIONS);

    client.sendMessage("Now run dangerous commands");
}
```

### MCP 서버 상태

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    // Query MCP server connection status
    Map<String, Object> status = client.getMcpStatus();
    List<?> servers = (List<?>) status.get("mcpServers");

    for (Object server : servers) {
        Map<?, ?> serverInfo = (Map<?, ?>) server;
        System.out.println("Server: " + serverInfo.get("name") +
                          " Status: " + serverInfo.get("status"));
    }
}
```

### SDK 유틸리티

```java
// Get SDK version
String version = ClaudeSDK.getVersion();
System.out.println("Claude SDK Version: " + version);

// Check if client is connected
try (var client = ClaudeSDK.createClient()) {
    System.out.println("Connected: " + client.isConnected());  // false

    client.connect();
    System.out.println("Connected: " + client.isConnected());  // true
}
```

## 사용자 정의 도구 (SDK MCP 서버)

Java 애플리케이션 내부에서 직접 실행되는 인프로세스 MCP 서버를 만듭니다.

### @Tool 어노테이션 사용

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;

public class MyTools {

    @Tool(name = "greet", description = "Greet a user by name")
    public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
        String name = (String) args.get("name");
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }

    @Tool(name = "calculate", description = "Perform a calculation")
    public CompletableFuture<ToolResult> calculate(Map<String, Object> args) {
        int a = ((Number) args.get("a")).intValue();
        int b = ((Number) args.get("b")).intValue();
        String op = (String) args.get("operation");

        int result = switch (op) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> a / b;
            default -> throw new IllegalArgumentException("Unknown operation: " + op);
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(String.valueOf(result))
        );
    }
}

// Create SDK MCP server from annotated methods
McpSdkServerConfig serverConfig = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", serverConfig))
    .allowedTools(List.of("mcp__tools__greet", "mcp__tools__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Greet Alice and calculate 5 + 3");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }
}
```

### SdkMcpTool 직접 사용

```java
import in.vidyalai.claude.sdk.mcp.*;

// Create tools programmatically
SdkMcpTool<Map<String, Object>> greetTool = SdkMcpTool.create(
    "greet",
    "Greet a user",
    Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string", "description", "Name to greet")
        ),
        "required", List.of("name")
    ),
    args -> {
        String name = (String) args.get("name");
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
);

// Create server
SdkMcpServer server = SdkMcpServer.create("my-server", "1.0.0", List.of(greetTool));
McpSdkServerConfig config = server.toConfig();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("my-server", config))
    .allowedTools(List.of("mcp__my-server__greet"))
    .build();
```

### 도구 결과 타입

```java
// Text result
ToolResult.text("Operation completed successfully")

// Error result
ToolResult.error("File not found: /path/to/file")

// Image result (base64 encoded)
ToolResult.image(base64Data, "image/png")

// Structured data, serialized into a text block
ToolResult.json(Map.of("status", "ok", "rows", 42))

// Several blocks, including a link the CLI renders as text
ToolResult.builder()
    .addText("Summary:")
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build()
```

### 오래 실행되는 도구 취소하기

CLI는 MCP 타임아웃을 넘긴 도구를 포기하고 `notifications/cancelled`를 보냅니다. 호출은
기다리지 않고 응답되지만, 핸들러 자신이 확인하지 않는 한 계속 실행됩니다 —
`CompletableFuture`는 외부에서 인터럽트할 수 없기 때문입니다. 인자와 함께
`ToolCallContext`를 받으면 취소를 감지할 수 있습니다:

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
    "crawl", "Fetch every page under a URL", schema,
    (args, context) -> CompletableFuture.supplyAsync(() -> {
        List<String> pages = new ArrayList<>();
        for (String url : urlsFrom(args)) {
            if (context.isCancelled()) {
                break;                       // nobody is waiting any more
            }
            pages.add(fetch(url));
        }
        return ToolResult.text(String.join("\n", pages));
    }));
```

인자만 받는 핸들러는 영향을 받지 않습니다. 블로킹 읽기처럼 폴링할 수 없는 작업에는
`context.onCancel(...)`을 사용하세요.

### 직접 만든 MCP 서버 사용하기

`McpSdkServerConfig`는 `McpMessageHandler`를 담고 있습니다. 따라서 내장 서버가 제공하지 않는
MCP 기능(리소스, 프롬프트, 자동완성)이 필요한 애플리케이션은 이 인터페이스를 직접 구현해
동일한 방식으로 등록할 수 있습니다.
[docs/feature-mcp-servers.md](../feature-mcp-servers.md#custom-mcp-handlers)를 참고하세요.

### 외부 MCP 서버 대비 장점

- **서브프로세스 관리 불필요** — 애플리케이션과 같은 JVM에서 실행됩니다
- **더 나은 성능** — 도구 호출에 IPC 오버헤드가 없습니다
- **더 간단한 배포** — 여러 프로세스 대신 단일 Java 프로세스
- **더 쉬운 디버깅** — 모든 코드가 같은 프로세스에서 실행됩니다
- **타입 안전성** — Java 메서드를 직접 호출합니다

## 훅 (Hooks)

훅은 Claude Code가 에이전트 루프의 특정 지점에서 호출하는 콜백입니다. 결정론적인 처리와
자동화된 피드백을 가능하게 합니다.

### 훅 이벤트

| 이벤트 | 설명 |
|-------|-------------|
| `PreToolUse` | 도구 실행 전 |
| `PostToolUse` | 도구 완료 후 |
| `UserPromptSubmit` | 사용자가 프롬프트를 제출할 때 |
| `Stop` | 세션이 중지될 때 |
| `SubagentStop` | 서브에이전트가 중지될 때 |
| `PreCompact` | 컨텍스트 압축 전 |

### 예시: 위험한 명령 차단

```java
import in.vidyalai.claude.sdk.types.*;
import java.util.concurrent.CompletableFuture;

HookMatcher.HookCallback checkBashCommand = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        if ("Bash".equals(preToolUse.toolName())) {
            String command = (String) preToolUse.toolInput().get("command");

            // Block dangerous patterns
            List<String> blockedPatterns = List.of("rm -rf", "sudo", "chmod 777");
            for (String pattern : blockedPatterns) {
                if (command.contains(pattern)) {
                    return CompletableFuture.completedFuture(
                        HookOutput.builder()
                            .hookSpecificOutput(
                                HookSpecificOutput.preToolUse()
                                    .permissionDecision("deny")
                                    .permissionDecisionReason("Command contains blocked pattern: " + pattern)
                                    .build()
                            )
                            .build()
                    );
                }
            }
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

HookMatcher bashMatcher = new HookMatcher("Bash", List.of(checkBashCommand));

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Bash"))
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(bashMatcher)))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    // This will be blocked
    client.connect("Run: rm -rf /");
    for (Message msg : client.receiveResponse()) {
        System.out.println(msg);
    }
}
```

### 예시: 모든 도구 사용 기록하기

```java
HookMatcher.HookCallback logToolUse = (input, context) -> {
    if (input instanceof PostToolUseHookInput postToolUse) {
        System.out.printf("Tool '%s' completed with response: %s%n",
            postToolUse.toolName(),
            postToolUse.toolResponse());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logToolUse)))
    ))
    .build();
```

## 권한 콜백

사용자 정의 권한 로직으로 도구 실행을 제어합니다.

```java
import in.vidyalai.claude.sdk.types.*;
import java.util.concurrent.CompletableFuture;

ClaudeAgentOptions.CanUseTool permissionCallback = (toolName, input, context) -> {
    // Check tool name
    if ("Bash".equals(toolName)) {
        String command = (String) input.get("command");

        // Allow safe commands
        if (command.startsWith("ls") || command.startsWith("cat") || command.startsWith("echo")) {
            return CompletableFuture.completedFuture(new PermissionResultAllow());
        }

        // Deny dangerous commands
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Bash command not allowed: " + command, false)
        );
    }

    // Allow all other tools
    return CompletableFuture.completedFuture(new PermissionResultAllow());
};

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .canUseTool(permissionCallback)
    .allowedTools(List.of("Bash", "Read", "Write"))
    .build();
```

### 도구 입력 수정

```java
ClaudeAgentOptions.CanUseTool sanitizeInput = (toolName, input, context) -> {
    if ("Bash".equals(toolName)) {
        // Modify the input
        Map<String, Object> modifiedInput = new HashMap<>(input);
        modifiedInput.put("timeout", 30);  // Add timeout

        return CompletableFuture.completedFuture(
            new PermissionResultAllow(modifiedInput, null)
        );
    }
    return CompletableFuture.completedFuture(new PermissionResultAllow());
};
```

### 권한 업데이트

```java
ClaudeAgentOptions.CanUseTool upgradePermissions = (toolName, input, context) -> {
    // Grant bypass permissions for this session
    List<PermissionUpdate> updates = List.of(
        PermissionUpdate.setMode(
            PermissionMode.BYPASS_PERMISSIONS,
            PermissionUpdateDestination.SESSION
        )
    );

    return CompletableFuture.completedFuture(
        new PermissionResultAllow(null, updates)
    );
};
```

## 구성 옵션

### ClaudeAgentOptions

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model configuration
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-3-5")

    // Thinking configuration (new in v0.1.36 - takes precedence over maxThinkingTokens)
    .thinking(new ThinkingConfigEnabled(10_000))  // Enable with 10k token budget
    // Or: .thinking(new ThinkingConfigAdaptive())  // Adaptive (32k default)
    // Or: .thinking(new ThinkingConfigDisabled())  // Disable thinking
    .effort("medium")  // Thinking depth: "low", "medium", "high", "max"

    // Legacy thinking config (deprecated - use .thinking() instead)
    .maxThinkingTokens(8000)

    // Tool configuration
    .tools(List.of("Bash", "Read", "Write", "Edit"))
    .allowedTools(List.of("Read"))
    .disallowedTools(List.of("Execute"))

    // Permission configuration
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .canUseTool(permissionCallback)

    // System prompt
    .systemPrompt("You are a helpful coding assistant")
    // Or use preset
    .systemPrompt(SystemPromptPreset.claudeCode("Be concise."))

    // Session configuration
    .continueConversation(true)
    .resume("session-id")
    .forkSession(false)
    // Truncating resume: branch from an earlier transcript entry, and let the
    // CLI refuse the fork if anything other than the named turn would be lost
    .resumeSessionAt("transcript-entry-uuid")
    .resumeDropsTurn("discarded-prompt-uuid")

    // Limits
    .maxTurns(10)
    .maxBudgetUsd(1.0)

    // Working directory
    .cwd(Path.of("/path/to/project"))
    .addDirs(List.of(Path.of("/other/path")))

    // MCP servers
    .mcpServers(Map.of("server", serverConfig))

    // Hooks
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher)))

    // Sandbox
    .sandbox(new SandboxSettings(true))

    // Environment
    .env(Map.of("MY_VAR", "value"))

    // Beta features
    .betas(List.of("context-1m-2025-08-07"))

    // Streaming
    .includePartialMessages(true)

    // File checkpointing
    .enableFileCheckpointing(true)

    // Output format (structured output)
    .outputFormat(Map.of(
        "type", "json_schema",
        "schema", schemaMap
    ))

    .build();
```

### 권한 모드

```java
PermissionMode.DEFAULT           // CLI prompts for dangerous tools
PermissionMode.ACCEPT_EDITS      // Auto-accept file edits
PermissionMode.PLAN              // Show plans before execution
PermissionMode.BYPASS_PERMISSIONS // Allow all tools (use with caution)
```

### 확장 사고(Thinking) 설정

`ThinkingConfig` 타입으로 확장 사고 동작을 제어합니다 (v0.1.36에서 추가):

```java
import in.vidyalai.claude.sdk.types.config.*;

// Adaptive thinking - uses 32,000 token budget by default
ThinkingConfig adaptive = new ThinkingConfigAdaptive();

// Enabled thinking - specify exact token budget
ThinkingConfig enabled = new ThinkingConfigEnabled(10_000);

// Disabled thinking - no extended thinking
ThinkingConfig disabled = new ThinkingConfigDisabled();

// Use with ClaudeAgentOptions
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(enabled)
    .effort("medium")  // Control thinking depth: "low", "medium", "high", "max"
    .build();
```

**참고:** `thinking` 필드는 더 이상 권장되지 않는 `maxThinkingTokens` 필드보다 우선합니다.

### MCP 서버 구성

```java
// Stdio server (subprocess)
StdioMcpServerConfig stdio = new StdioMcpServerConfig(
    "node",
    List.of("server.js", "--port", "3000"),
    Map.of("NODE_ENV", "production")
);

// SSE server
SseMcpServerConfig sse = new SseMcpServerConfig(
    "https://api.example.com/sse",
    Map.of("Authorization", "Bearer token")
);

// HTTP server
HttpMcpServerConfig http = new HttpMcpServerConfig(
    "https://api.example.com/mcp",
    Map.of("X-API-Key", "key123")
);

// SDK server (in-process)
McpSdkServerConfig sdk = ClaudeSDK.createSdkMcpServer("name", toolInstance);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "stdio-server", stdio,
        "sse-server", sse,
        "http-server", http,
        "sdk-server", sdk
    ))
    .build();
```

### 샌드박스 구성

```java
SandboxSettings sandbox = new SandboxSettings(
    true,   // enabled
    true,   // autoAllowBashIfSandboxed
    List.of("git", "docker"),  // excludedCommands
    Path.of("/sandbox"),       // sandboxDir
    new SandboxNetworkConfig(
        List.of("/tmp/ssh-agent.sock"),  // allowUnixSockets
        false,  // allowAllUnixSockets
        true,   // allowLocalBinding
        8080,   // httpProxyPort
        8081    // socksProxyPort
    ),
    new SandboxIgnoreViolations(
        List.of("/tmp"),  // file paths to ignore
        List.of("localhost")  // network hosts to ignore
    ),
    false  // allowNestedSandbox
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sandbox(sandbox)
    .build();
```

## 메시지 타입

SDK는 타입 안전한 메시지 처리를 위해 sealed 인터페이스를 사용하며, 패턴 매칭을 지원합니다.

```java
import in.vidyalai.claude.sdk.types.*;

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user -> {
            System.out.println("User: " + user.contentAsString());
        }
        case AssistantMessage assistant -> {
            System.out.println("Assistant: " + assistant.getTextContent());
            if (assistant.hasToolUse()) {
                System.out.println("(used tools)");
            }
        }
        case SystemMessage system -> {
            System.out.println("System: " + system.subtype());
        }
        case ResultMessage result -> {
            System.out.println("Result: " + result.subtype());
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Turns: " + result.numTurns());
        }
        case StreamEvent event -> {
            System.out.println("Stream event: " + event.eventType());
        }
        case ConversationResetMessage reset -> {
            // /clear (or another transcript-discarding flow) replaced the
            // conversation; the CLI's running totals restart from zero.
            System.out.println("Conversation reset: " + reset.newConversationId());
        }
    }
}
```

`Message`는 sealed 인터페이스이므로, 새 메시지 타입이 추가되면 `default`가 없는 완전한
`switch`는 더 이상 컴파일되지 않습니다. 앞으로 추가되는 타입을 조용히 흡수하고 싶다면
`default ->` 분기를 추가하세요.

### 메시지 출처 (Message Origin)

스트리밍 입력 모드에서는 하나의 연결이 여러분이 보낸 턴과 세션이 스스로 주입하는 턴 —
백그라운드 작업 알림, 실행된 예약 작업 프롬프트, MCP 채널 메시지, 피어 세션에서 중계된
메시지 — 을 번갈아 전달합니다. `UserMessage`와 `ResultMessage`의 `origin()`이 둘을
구분해 줍니다:

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

CLI가 메시지에 출처를 표시하지 않았다면 `origin()`은 null입니다. `query()`로 보낸 프롬프트는
스트리밍 메시지 맵에 직접 `"origin": {"kind": "human"}`을 붙이지 않는 한 그렇게 도착합니다 —
SDK 호스트에서 인정되는 종류는 `human`뿐입니다. 이 SDK가 모델링한 것보다 새로운 종류라면
`kind()`는 null이 되지만 프로토콜 문자열은 여전히 `kindValue()`로 읽을 수 있으며, 결코
human으로 간주되지 않습니다.

### 콘텐츠 블록

```java
for (ContentBlock block : assistant.content()) {
    switch (block) {
        case TextBlock text -> System.out.println(text.text());
        case ThinkingBlock thinking -> System.out.println("Thinking: " + thinking.thinking());
        case ToolUseBlock toolUse -> {
            System.out.println("Tool: " + toolUse.name());
            System.out.println("Input: " + toolUse.input());
        }
        case ToolResultBlock result -> {
            System.out.println("Result: " + result.content());
            if (result.isError()) {
                System.out.println("(error)");
            }
        }
    }
}
```

## 오류 처리

```java
import in.vidyalai.claude.sdk.exceptions.*;

try {
    List<Message> messages = ClaudeSDK.query("Hello");
} catch (CLINotFoundException e) {
    System.err.println("Claude Code CLI not found. Install with:");
    System.err.println("  curl -fsSL https://claude.ai/install.sh | bash");
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect to CLI: " + e.getMessage());
} catch (ResultException e) {
    // A control request failed on a terminal error result — in practice an
    // `initialize` the CLI refused during startup (e.g. a resume rejected by
    // resumeDropsTurn). An error result *during* the run arrives as a
    // QueryFailedException instead; see below.
    System.err.println("Startup failed: " + e.subtype() + " / " + e.terminalReason());
} catch (ProcessException e) {
    System.err.println("Process failed with exit code: " + e.getExitCode());
    System.err.println("Stderr: " + e.getStderr());
} catch (CLIJSONDecodeException e) {
    System.err.println("Failed to parse JSON response: " + e.getMessage());
    System.err.println("Raw line: " + e.getLine());
} catch (MessageParseException e) {
    System.err.println("Failed to parse message: " + e.getMessage());
    System.err.println("Data: " + e.getData());
} catch (QueryFailedException e) {
    // The run ended in an error result (max turns, max budget, an API
    // failure). The messages that arrived before it are still available...
    System.err.println("Run ended early: " + e.getMessage());
    ResultMessage result = e.resultMessage();
    if (result != null) {
        System.err.println("Stopped by: " + result.subtype());
        System.err.println("Spent: $" + result.totalCostUsd());
    }
    // ...and the typed payload is on the cause.
    if (e.getCause() instanceof ResultException cause) {
        System.err.println("Why: " + cause.terminalReason()
                + " (HTTP " + cause.apiErrorStatus() + ")");
    }
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

### 예외 계층 구조

```
ClaudeSDKException (base)
├── CLIConnectionException
│   └── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

`ResultException`은 CLI가 실패한 실행을 `is_error: true`인 `result` 메시지로 끝내고 0이 아닌
코드로 종료할 때 발생합니다. 이 경우 단순한 "종료 코드 1" `ProcessException`을 대체하며,
해당 result의 페이로드 — `subtype()`, `errors()`, `result()`, `apiErrorStatus()`,
`terminalReason()`, `sessionId()` 및 원본 `data()` — 를 담고 있어 호출자가 실행이 실패한
*이유*에 따라 분기할 수 있습니다. API 장애로 끝난 실행은 `subtype() == "success"`이면서
`terminalReason() == "api_error"`로 도착하고, 설명 문구는 `result()`에 들어 있다는 점에
유의하세요. 기존의 `catch (ProcessException e)` 처리는 그대로 동작합니다.

보통은 단독으로가 아니라 `QueryFailedException.getCause()`로 만나게 됩니다. 메시지를 모으는
`query(...)`가 이를 감싸서 이미 받은 메시지가 유실되지 않게 하기 때문입니다. 단독으로
던져지는 경우는 CLI가 시작 시 거부한 `initialize`처럼 제어 요청이 실패했을 때뿐입니다.
`ClaudeSDKClient.receiveResponse()`는 이 예외를 전혀 던지지 않습니다 — 그 이터레이터는
`ResultMessage`에서 멈추므로, 거기서는 대신 `ResultMessage.isError()`를 확인하세요.

`QueryFailedException`은 메시지를 모으는 `ClaudeSDK.query(...)` 계열에 특화된 예외입니다.
CLI는 `error_max_turns`나 `error_max_budget_usd` 같은 상황을 하나의 완전한 턴 — subtype과
비용을 담은 마지막 `ResultMessage` 포함 — 을 내보낸 *다음에* 0이 아닌 코드로 종료하는
방식으로 알립니다. 스트리밍 소비자(`ClaudeSDKClient.receiveMessages()` /
`receiveResponse()`)는 예외가 발생하기 전에 그 메시지들을 모두 볼 수 있습니다. 반면 모아서
돌려주는 호출은 리스트를 반환하거나 예외를 던지는 것 중 하나만 할 수 있으므로 이 예외를
던지고, 모아 둔 메시지는 `partialMessages()`와 편의 접근자 `resultMessage()`를 통해
돌려줍니다. 그 `getCause()`가 바로 위에서 설명한 `ResultException`입니다. `maxTurns`나
`maxBudgetUsd`를 설정했다면 반드시 이 예외를 잡으세요. 직접 설정한 한도에 도달하는 것은
충돌이 아니라 예상된 결과입니다.

## 스트리밍 이벤트

실시간 업데이트를 위해 부분 메시지 스트리밍을 활성화합니다.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .includePartialMessages(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Write a long story");

    for (Message msg : client.receiveMessages()) {
        if (msg instanceof StreamEvent event) {
            // Process streaming delta
            System.out.print(event.event().get("delta"));
        } else if (msg instanceof AssistantMessage assistant) {
            // Complete message
            System.out.println("\n--- Complete ---");
            System.out.println(assistant.getTextContent());
        } else if (msg instanceof ResultMessage result) {
            break;  // Done
        }
    }
}
```

## 파일 체크포인트

파일 변경을 추적하고 이전 상태로 되돌립니다.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .enableFileCheckpointing(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Modify some files");

    String checkpointId = null;
    for (Message msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user && user.uuid() != null) {
            checkpointId = user.uuid();  // Save checkpoint
        }
    }

    // Later, rewind to checkpoint
    if (checkpointId != null) {
        client.rewindFiles(checkpointId);
    }
}
```

## 사용자 정의 에이전트

특정 역량을 갖춘 사용자 정의 에이전트를 정의합니다.

```java
AgentDefinition codeReviewer = new AgentDefinition(
    "Code Review Agent",
    "You are an expert code reviewer. Focus on security, performance, and best practices.",
    List.of("Read", "Grep", "Glob"),
    "sonnet"
);

AgentDefinition testWriter = new AgentDefinition(
    "Test Writer Agent",
    "You write comprehensive unit tests.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "code-reviewer", codeReviewer,
        "test-writer", testWriter
    ))
    .build();
```

## 예제

완전히 동작하는 예제는 `examples/` 모듈을 참고하세요:

- `QuickStart.java` — 기본 사용법
- `MultiTurnConversation.java` — 대화형 세션
- `ToolUsage.java` — 내장 도구 사용
- `McpServer.java` — 사용자 정의 MCP 도구 만들기
- `AutoSchemaGeneration.java` — 도구 스키마 자동 생성
- `Hooks.java` — 훅 콜백 (새 훅 이벤트 Notification, SubagentStart, PermissionRequest 포함)
- `PermissionCallbacks.java` — 사용자 정의 권한 로직
- `StreamingEvents.java` — 실시간 스트리밍
- `StructuredOutputExample.java` — JSON Schema 검증을 곁들인 구조화 출력 (단순, 중첩, 열거형, 도구 병용)
- `DynamicControlExample.java` — 동적 제어 기능 (setPermissionMode, setModel, interrupt)
- `ErrorHandling.java` — 예외 처리
- `AdvancedFeatures.java` — 체크포인트, 샌드박스, 구조화 출력
- `ToolsConfigurationExample.java` — 도구 구성 (배열, 프리셋, 빈 값)
- `MaxBudgetExample.java` — 예산 제한과 비용 관리
- `SettingSourcesExample.java` — 설정 출처 (user, project, local)
- `StderrCallbackExample.java` — CLI stderr 출력 수집
- `PluginsExample.java` — 플러그인 시스템 사용법
- `AgentsExample.java` — 프로그래밍 방식의 서브에이전트 정의
- `FilesystemAgentsExample.java` — 파일시스템 기반 에이전트 구성
- `LargeAgentsExample.java` — initialize 요청을 통한 대용량 에이전트 정의 (260KB 이상)
- `SystemPromptExample.java` — 사용자 정의 시스템 프롬프트 사용법
- `IncludePartialMessagesExample.java` — 부분 메시지 업데이트를 포함한 스트리밍

### 예제 실행하기

examples 모듈은 SDK에 의존하는 별도의 Maven 모듈입니다. 저장소 루트에서 빌드하면 그 의존성은
리액터에서 충족되고, `examples/`만 따로 빌드하면 Maven Central에서 해석되므로 저장소나 인증
설정이 필요하지 않습니다.

#### 방법 1: 루트 디렉터리에서 예제 실행 (권장)

모든 모듈을 빌드하고 예제를 실행합니다:

```bash
# Build all modules (SDK + examples)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples

# Run different examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
```

#### 방법 2: examples 디렉터리에서 실행

```bash
# Navigate to examples directory
cd examples

# Build examples (resolves the published SDK from Maven Central)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart"

# Or use java -cp
java -cp target/classes:target/dependency/* examples.QuickStart
```

#### 방법 3: 로컬 개발 버전 SDK로 예제 실행

배포된 버전이 아니라 로컬에서 개발 중인 SDK로 예제를 시험하려면:

1. SDK를 로컬에 설치합니다:
   ```bash
   cd sdk
   mvn clean install -DskipTests
   cd ..
   ```

2. `examples/pom.xml`을 SNAPSHOT 버전으로 수정합니다:
   ```xml
   <dependency>
       <groupId>in.vidyalai</groupId>
       <artifactId>claude-agent-sdk-java</artifactId>
       <version>0.1.1-SNAPSHOT</version>
   </dependency>
   ```

3. 방법 1 또는 방법 2대로 예제를 실행합니다.

**참고:** `examples/pom.xml`의 의존성 `<version>`을 배포된 버전(예: `0.2.2`)으로 지정해
examples 모듈을 해당 릴리스에 고정할 수도 있습니다. Maven Central에서 해석되므로 저장소나
인증 설정은 필요하지 않습니다.

## 스레드 안전성

- `ClaudeSDKClient`는 **스레드 안전하지 않습니다**. 스레드마다 하나의 client를 쓰거나 접근을
  동기화하세요.
- `ClaudeSDK.query()` 메서드들은 새 연결을 만들므로 여러 스레드에서 안전하게 호출할 수 있습니다.
- 콜백(훅, 권한)은 서로 다른 스레드에서 호출될 수 있습니다. 콜백 구현이 스레드 안전한지
  확인하세요.

## 동시성 모델: Python과 Java

Java SDK는 Python SDK와 근본적으로 다른 동시성 모델을 사용합니다. 이 차이를 이해하면 코드를
이식하거나 예제를 비교할 때 도움이 됩니다.

### Python SDK: Async/Await 모델

Python SDK는 asyncio 또는 trio와 함께 Python의 `async`/`await` 문법을 사용합니다:

```python
# Python SDK - async/await with asyncio
async def example():
    async with ClaudeSDKClient() as client:
        await client.connect("Hello")
        async for message in client.receive_response():
            print(message)

# Python SDK - streaming with async iterables
async def message_stream():
    yield {"type": "user", "message": {"role": "user", "content": "Hi"}}
    yield {"type": "user", "message": {"role": "user", "content": "Bye"}}

await client.connect(message_stream())
```

**Python의 주요 특징:**
- 논블로킹 작업을 위한 `async`/`await` 키워드
- 비동기 이터러블을 순회하는 `async for`
- 비동기 컨텍스트 매니저를 위한 `async with`
- 비동기 이터러블/제너레이터 (`async def`와 `yield`)
- 라이브러리: asyncio, trio

### Java SDK: 동기 + CompletableFuture 모델

Java SDK는 동기 API를 사용하고, 비동기 작업에는 CompletableFuture를 씁니다:

```java
// Java SDK - synchronous with try-with-resources
try (var client = ClaudeSDK.createClient()) {
    client.connect("Hello");
    for (Message message : client.receiveResponse()) {
        System.out.println(message);
    }
}

// Java SDK - streaming with Iterator
List<Map<String, Object>> messages = List.of(
    Map.of("type", "user", "message", Map.of("role", "user", "content", "Hi")),
    Map.of("type", "user", "message", Map.of("role", "user", "content", "Bye"))
);
client.query(messages.iterator());
```

**Java의 주요 특징:**
- 비동기 이터러블 대신 **동기 이터레이터** (`Iterator<Message>`)
- 비동기 컨텍스트 매니저 대신 **try-with-resources** (`try (...)`)
- 비동기 콜백(훅, 권한)을 위한 **CompletableFuture**
- Java 21 이상에서 블로킹 I/O를 효율적으로 처리하는 **가상 스레드** (17~20에서는 플랫폼 스레드로 대체)
- 백그라운드 작업 관리를 위한 **ExecutorService**

### Python의 비동기 예제가 그대로 옮겨지지 않는 이유

Python SDK 예제 중 일부는 비동기 고유의 패턴을 보여 주기 때문에 Java에 직접 대응하는 것이
없습니다:

| Python 예제 | Java에 없는 이유 | Java에서의 대응 |
|----------------|-----------------|-----------------|
| `streaming_mode_ipython.py` | IPython 전용 비동기 REPL 통합 | Java REPL(jshell)과 동기 API 사용 |
| `streaming_mode_trio.py` | Trio 전용 동시성 라이브러리 | 표준 Java 동시성(ExecutorService, 가상 스레드) 사용 |
| `test_connect_with_async_iterable` | 비동기 제너레이터 패턴 | 일반 Iterator를 쓰는 `client.query(Iterator<Map>)` |
| `test_concurrent_send_receive` | 비동기 동시 작업 | `Thread.startVirtualThread()` 또는 ExecutorService 사용 |

### Java에서의 동시 작업

Java에서 진짜 동시성이 필요한 작업의 경우:

```java
// Concurrent send and receive using virtual threads
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Receive in background thread
    Thread receiveThread = Thread.startVirtualThread(() -> {
        for (Message msg : client.receiveMessages()) {
            if (msg instanceof ResultMessage) break;
            System.out.println("Received: " + msg);
        }
    });

    // Send from main thread
    client.sendMessage("First message");
    Thread.sleep(1000);
    client.sendMessage("Second message");

    receiveThread.join();
}
```

### 성능 고려 사항

- **Python**: 비동기 I/O는 I/O 바운드 작업에 효율적이지만 명시적인 `await` 지점이 필요합니다
- **Java**: 가상 스레드(Project Loom) 덕분에 문법을 바꾸지 않고도 블로킹 I/O가 비동기만큼 효율적입니다
- **Java**: 동기 API는 비동기 코드보다 사용하고 디버깅하기 쉽습니다
- **Python**: Trio는 구조적 동시성을 제공하고, Java는 try-with-resources와 ExecutorService로 비슷한 것을 이룹니다

### 마이그레이션 가이드: Python에서 Java로

| Python 패턴 | Java 대응 |
|----------------|-----------------|
| `async with client:` | `try (var client = ...) {` |
| `await client.connect()` | `client.connect()` (동기) |
| `async for msg in client.receive():` | `for (Message msg : client.receiveResponse())` |
| `await asyncio.sleep(1)` | `Thread.sleep(1000)` |
| `asyncio.create_task()` | `Thread.startVirtualThread(() -> ...)` |
| `async def generator():` + `yield` | `Iterator<T>` 구현 |
| `CompletableFuture.completed()` | `CompletableFuture.completedFuture()` |

**요약:** Java SDK는 단순함을 우선해 동기 API와 가상 스레드로 효율적인 동시성을 얻고,
Python SDK는 논블로킹 작업을 위해 async/await를 사용합니다. 둘 다 각 언어의 관용적인 방식으로
비슷한 기능을 달성합니다.

## 모범 사례

1. **client는 항상 닫기** — try-with-resources를 쓰거나 finally 블록에서 `disconnect()`를 호출하세요.
2. **오류를 우아하게 처리하기** — 구체적인 예외를 잡으면 더 나은 오류 메시지를 얻습니다.
3. **적절한 한도 설정하기** — `maxTurns`와 `maxBudgetUsd`로 실행량을 제한하세요.
4. **보안에는 권한 콜백 사용하기** — `permissionMode`에만 의존하지 마세요.
5. **SDK MCP 서버 선호하기** — 외부 프로세스보다 빠르고 디버깅하기 쉽습니다.

## 문서

- **[Python SDK 기능 동등성 분석](../PYTHON_SDK_PARITY.md)** — Python SDK와 Java SDK의 포괄적인
  비교로, 기능 동등성 현황, 타입 시스템 비교, 예제 커버리지, 구현 세부 사항을 담고 있습니다. (영어)
- **[기술 문서 색인](./index.md)** — 아키텍처, 기능 가이드, API 레퍼런스 (이 언어판).
- **[번역 안내](../TRANSLATIONS.md)** — 번역 범위, 동기화 정책, 기여 방법. (영어)

## 라이선스

[MIT](../../LICENSE)
