# Claude Agent SDK for Java

[English](../../README.md) · **简体中文** · [日本語](../ja/README.md) · [한국어](../ko/README.md) · [Português](../pt/README.md) · [Español](../es/README.md)

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../../README.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。详见 [docs/TRANSLATIONS.md](../TRANSLATIONS.md)。

面向 Claude Agent 的 Java SDK。本 SDK 提供了一套完整的 Java API，用于与 Claude Code 交互，让你能够构建由 Claude 能力驱动的 AI 应用。

## 环境要求

- Java 17 或更高版本（使用了密封接口和 record）
  - 在 Java 21+ 上，SDK 的后台任务运行在虚拟线程上；在 17-20 上则改用具名守护
    平台线程。无需任何配置。
- Maven 3.6+

**注意：** Claude Code CLI 需要单独安装：
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

或者指定自定义路径：
```java
ClaudeAgentOptions.builder()
    .cliPath(Path.of("/path/to/claude"))
    .build();
```

## 安装

已发布至 Maven Central，因此无需配置仓库或身份认证。

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

### 备选方案：GitHub Packages

为了兼容已经依赖该渠道的使用者，各版本也会镜像发布到 GitHub Packages。即使制品是公开的，
这条路径仍然需要 GitHub 个人访问令牌，因此除非你有特殊理由，否则请优先使用 Maven Central。

<details>
<summary>GitHub Packages 配置</summary>

在 `pom.xml` 中添加仓库：

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java</url>
    </repository>
</repositories>
```

或者添加到 `build.gradle.kts`：

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

然后配置身份认证。对于 Maven，在 `~/.m2/settings.xml` 中添加：

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

对于 Gradle，创建或更新 `~/.gradle/gradle.properties`：

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

请在 https://github.com/settings/tokens 生成具有 `read:packages` 权限的个人访问令牌。

</details>

## 快速开始

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

## 基础用法：ClaudeSDK.query()

`ClaudeSDK.query()` 用于简单的一次性查询。它返回包含全部响应消息的 `List<Message>`。

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

### 便捷方法

```java
// Get just the text response
String text = ClaudeSDK.queryForText("What is the capital of France?");
System.out.println(text);  // "Paris"

// Get just the result message
ResultMessage result = ClaudeSDK.queryForResult("Do something", options);
System.out.println("Cost: $" + result.totalCostUsd());
```

### 使用工具

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

### 工作目录

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/path/to/project"))
    .build();
```

### 流式输入（多条消息）

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

### 系统提示词

默认情况下，Claude Code 会在会话的第一次请求时构建系统提示词并将其记录下来，之后的每次请求（包括恢复会话之后）都复用它。因此，修改后的自定义提示词，或 `claude_code` 预设上修改后的 `append` 文本，在会话被压缩或开启新会话之前都不会生效。如果希望每次请求都重新构建提示词（例如在反复调整措辞时），请在 `SystemPromptCustom` 或 `SystemPromptPreset` 上将 `snapshot` 设为 `false`：

```java
var options = ClaudeAgentOptions.builder()
    .systemPrompt(SystemPromptCustom.of("You are a release bot.", false))
    // or: .systemPrompt(SystemPromptPreset.claudeCode("Be concise.").withSnapshot(false))
    .build();
```

需要 Claude Code CLI 2.1.257 或更高版本。在 2.1.265 之前，带有 `append` 或自定义提示词的会话只有在 `snapshot` 为 `true` 时才会记录它。详情请参阅 [修改系统提示词](https://code.claude.com/docs/en/agent-sdk/modifying-system-prompts#change-the-prompt-of-an-existing-session)。

## ClaudeSDKClient

`ClaudeSDKClient` 支持与 Claude Code 进行双向的交互式会话。与 `query()` 不同，它支持
**多轮会话**、**自定义工具**、**钩子（hooks）**以及**实时交互**。

### 基础用法

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

### 手动管理连接

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

### 中断执行

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Do a long task");

    // In another thread or after some condition
    client.interrupt();  // Sends interrupt signal
}
```

### 动态切换模型

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start with default model");

    // Switch to a different model mid-conversation
    client.setModel("claude-sonnet-5");

    client.sendMessage("Continue with new model");
}
```

### 变更权限模式

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start in default mode");

    // Change permission mode during conversation
    client.setPermissionMode(PermissionMode.BYPASS_PERMISSIONS);

    client.sendMessage("Now run dangerous commands");
}
```

### MCP 服务器状态

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

### SDK 实用方法

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

## 自定义工具（SDK MCP 服务器）

创建直接运行在你的 Java 应用进程内的 MCP 服务器。

### 使用 @Tool 注解

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

### 直接使用 SdkMcpTool

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

### 工具结果类型

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

### 取消长时间运行的工具

当某个工具的执行时间超过 MCP 超时限制时，CLI 会放弃等待并发送
`notifications/cancelled`。该调用会立即得到应答而不再等待，但处理函数会继续运行，
除非它主动去检查——`CompletableFuture` 无法从外部中断。在参数之外再接收一个
`ToolCallContext`，即可感知取消：

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

只接收参数的处理函数不受影响。对于无法轮询的工作（例如阻塞式读取），可以使用
`context.onCancel(...)`。

### 接入你自己的 MCP 服务器

`McpSdkServerConfig` 持有一个 `McpMessageHandler`，因此当应用需要内置服务器并未提供的
MCP 能力（资源、提示词、补全）时，可以自行实现该接口并以同样的方式注册。
参见 [docs/feature-mcp-servers.md](../feature-mcp-servers.md#custom-mcp-handlers)。

### 相比外部 MCP 服务器的优势

- **无需管理子进程** —— 与你的应用运行在同一个 JVM 中
- **性能更好** —— 工具调用没有进程间通信开销
- **部署更简单** —— 单个 Java 进程，而非多个进程
- **调试更容易** —— 所有代码运行在同一进程内
- **类型安全** —— 直接的 Java 方法调用

## 钩子（Hooks）

钩子是 Claude Code 在 agent 循环的特定节点上调用的回调。它们可以实现确定性的处理逻辑和
自动化反馈。

### 钩子事件

| 事件 | 说明 |
|-------|-------------|
| `PreToolUse` | 工具执行之前 |
| `PostToolUse` | 工具执行完成之后 |
| `UserPromptSubmit` | 用户提交提示词时 |
| `Stop` | 会话停止时 |
| `SubagentStop` | 子 agent 停止时 |
| `PreCompact` | 上下文压缩之前 |

### 示例：拦截危险命令

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

### 示例：记录所有工具调用

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

## 权限回调

用自定义的权限逻辑来控制工具的执行。

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

### 修改工具输入

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

### 权限更新

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

## 配置选项

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

### 权限模式

```java
PermissionMode.DEFAULT           // CLI prompts for dangerous tools
PermissionMode.ACCEPT_EDITS      // Auto-accept file edits
PermissionMode.PLAN              // Show plans before execution
PermissionMode.BYPASS_PERMISSIONS // Allow all tools (use with caution)
```

### 思考（Thinking）配置

使用 `ThinkingConfig` 类型控制扩展思考行为（自 v0.1.36 起新增）：

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

**注意：** `thinking` 字段的优先级高于已废弃的 `maxThinkingTokens` 字段。

### MCP 服务器配置

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

### 沙箱配置

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

## 消息类型

SDK 使用密封接口来实现类型安全的消息处理，并支持模式匹配。

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

`Message` 是一个密封接口，因此当新增消息类型时，没有 `default` 分支的穷尽式 `switch`
将无法通过编译。如果你希望默默忽略未来新增的类型，请添加 `default ->` 分支。

### 消息来源（Message Origin）

在流式输入模式下，同一个连接会把你发送的轮次与会话自行注入的轮次交织在一起——后台任务
通知、被触发的定时任务提示词、MCP 通道消息、从对等会话转发来的消息。`UserMessage` 和
`ResultMessage` 上的 `origin()` 可以区分它们：

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

当 CLI 未标注消息来源时，`origin()` 为 null。通过 `query()` 发送的提示词就属于这种情况，
除非你自己在流式消息 map 上标注 `"origin": {"kind": "human"}`——SDK 宿主端只有 `human`
这一种取值会被接受。若某个 kind 比本 SDK 建模的更新，`kind()` 会是 null，但仍可通过
`kindValue()` 读取其原始协议字符串，并且它永远不会被视为 human。

### 内容块（Content Blocks）

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

## 错误处理

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

### 异常层次结构

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

当 CLI 以发出 `is_error: true` 的 `result` 消息并随后以非零状态码退出的方式结束一次失败
运行时，会抛出 `ResultException`。对于这种情况，它取代了原本那个只有"退出码 1"的
`ProcessException`，并携带该 result 的负载——`subtype()`、`errors()`、`result()`、
`apiErrorStatus()`、`terminalReason()`、`sessionId()` 以及原始的 `data()`——以便调用方能够
根据运行失败的*原因*分别处理。请注意，因 API 故障而结束的运行，其 `subtype()` 为
`"success"`，`terminalReason()` 为 `"api_error"`，说明文字则在 `result()` 中。现有的
`catch (ProcessException e)` 处理逻辑仍然有效。

通常你会以 `QueryFailedException.getCause()` 的形式遇到它，而不是单独遇到：进行收集的
`query(...)` 会将其包装起来，以免已经收到的消息丢失。只有在控制请求失败时它才会被直接
抛出，例如 CLI 在启动时拒绝了某个 `initialize`。`ClaudeSDKClient.receiveResponse()`
根本不会抛出它——该迭代器在 `ResultMessage` 处停止，因此在那里请改为检查
`ResultMessage.isError()`。

`QueryFailedException` 是进行收集的 `ClaudeSDK.query(...)` 系列方法所特有的。对于
`error_max_turns` 和 `error_max_budget_usd` 这类情况，CLI 的报告方式是发出一个完整的轮次
——包括携带 subtype 和费用的最终 `ResultMessage`——*然后*才以非零状态码退出。流式消费者
（`ClaudeSDKClient.receiveMessages()` / `receiveResponse()`）在抛出之前能看到上述每一条
消息；而进行收集的调用要么返回一个列表、要么抛出异常，因此它选择抛出本异常，并通过
`partialMessages()` 以及便捷访问器 `resultMessage()` 把已收集的消息交还给你；它的
`getCause()` 就是上文所述的 `ResultException`。只要你设置了 `maxTurns` 或
`maxBudgetUsd`，就应当捕获它：达到你自己配置的上限是预期内的结果，而不是崩溃。

## 流式事件

启用部分消息流式传输，以获得实时更新。

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

## 文件检查点

跟踪文件变更并回退到之前的状态。

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

## 自定义 Agent

定义具备特定能力的自定义 agent。

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

## 示例

完整的可运行示例请参见 `examples/` 模块：

- `QuickStart.java` —— 基础用法
- `MultiTurnConversation.java` —— 交互式会话
- `ToolUsage.java` —— 使用内置工具
- `McpServer.java` —— 创建自定义 MCP 工具
- `AutoSchemaGeneration.java` —— 为工具自动生成 schema
- `Hooks.java` —— 钩子回调（包含新增的钩子事件：Notification、SubagentStart、PermissionRequest）
- `PermissionCallbacks.java` —— 自定义权限逻辑
- `StreamingEvents.java` —— 实时流式传输
- `StructuredOutputExample.java` —— 带 JSON Schema 校验的结构化输出（简单、嵌套、枚举、配合工具）
- `DynamicControlExample.java` —— 动态控制功能（setPermissionMode、setModel、interrupt）
- `ErrorHandling.java` —— 异常处理
- `AdvancedFeatures.java` —— 检查点、沙箱、结构化输出
- `ToolsConfigurationExample.java` —— 工具配置（数组、预设、空）
- `MaxBudgetExample.java` —— 预算限制与成本控制
- `SettingSourcesExample.java` —— 设置来源（user、project、local）
- `StderrCallbackExample.java` —— 捕获 CLI 的 stderr 输出
- `PluginsExample.java` —— 插件系统用法
- `AgentsExample.java` —— 以编程方式定义子 agent
- `FilesystemAgentsExample.java` —— 基于文件系统的 agent 配置
- `LargeAgentsExample.java` —— 通过 initialize 请求传递大型 agent 定义（260KB+）
- `SystemPromptExample.java` —— 自定义系统提示词用法
- `IncludePartialMessagesExample.java` —— 带部分消息更新的流式传输

### 运行示例

examples 模块是一个依赖 SDK 的独立 Maven 模块。从仓库根目录构建时，该依赖由 reactor
提供；单独构建 `examples/` 时，则从 Maven Central 解析，无需任何仓库或身份认证配置。

#### 方式一：从根目录运行示例（推荐）

构建所有模块并运行某个示例：

```bash
# Build all modules (SDK + examples)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples

# Run different examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
```

#### 方式二：从 examples 目录运行

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

#### 方式三：使用本地开发版 SDK 运行示例

若要针对本地开发版（而非已发布版本）的 SDK 测试示例：

1. 在本地安装 SDK：
   ```bash
   cd sdk
   mvn clean install -DskipTests
   cd ..
   ```

2. 更新 `examples/pom.xml` 以使用 SNAPSHOT 版本：
   ```xml
   <dependency>
       <groupId>in.vidyalai</groupId>
       <artifactId>claude-agent-sdk-java</artifactId>
       <version>0.1.1-SNAPSHOT</version>
   </dependency>
   ```

3. 按方式一或方式二运行示例。

**注意：** 你也可以把 `examples/pom.xml` 中依赖的 `<version>` 设为某个已发布版本
（例如 `0.2.2`），从而将 examples 模块固定到该版本。它会从 Maven Central 解析，因此无需
任何仓库或身份认证配置。

## 线程安全

- `ClaudeSDKClient` **不是线程安全的**。请为每个线程使用独立的 client，或对访问加以同步。
- `ClaudeSDK.query()` 系列方法会创建新的连接，可以安全地从多个线程调用。
- 回调（钩子、权限）可能在不同线程上被调用；请确保你的回调实现是线程安全的。

## 并发模型：Python 与 Java 对比

Java SDK 采用的并发模型与 Python SDK 有本质区别。理解这些差异有助于移植代码或对照示例。

### Python SDK：Async/Await 模型

Python SDK 使用 Python 的 `async`/`await` 语法，配合 asyncio 或 trio：

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

**Python 的关键特性：**
- 用于非阻塞操作的 `async`/`await` 关键字
- 用于遍历异步可迭代对象的 `async for`
- 用于异步上下文管理器的 `async with`
- 异步可迭代对象/生成器（`async def` 配合 `yield`）
- 相关库：asyncio、trio

### Java SDK：同步 + CompletableFuture 模型

Java SDK 使用同步 API，并以 CompletableFuture 处理异步操作：

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

**Java 的关键特性：**
- **同步迭代器**（`Iterator<Message>`）而非异步可迭代对象
- **try-with-resources**（`try (...)`）而非异步上下文管理器
- **CompletableFuture** 用于异步回调（钩子、权限）
- **虚拟线程**：在 Java 21+ 上高效地执行阻塞式 I/O，在 17-20 上回退为平台线程
- **ExecutorService** 用于后台任务管理

### 为什么 Python 的异步示例无法直接对应

有些 Python SDK 示例没有直接的 Java 对应物，因为它们演示的是异步特有的模式：

| Python 示例 | 为何 Java 中没有 | Java 等价做法 |
|----------------|-----------------|-----------------|
| `streaming_mode_ipython.py` | IPython 特有的异步 REPL 集成 | 使用 Java REPL（jshell）配合同步 API |
| `streaming_mode_trio.py` | Trio 特有的并发库 | 使用标准 Java 并发设施（ExecutorService、虚拟线程） |
| `test_connect_with_async_iterable` | 异步生成器模式 | `client.query(Iterator<Map>)` 配合普通 Iterator |
| `test_concurrent_send_receive` | 异步并发操作 | 使用 `Thread.startVirtualThread()` 或 ExecutorService |

### Java 中的并发操作

对于在 Java 中确实需要真正并发的场景：

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

### 性能考量

- **Python**：异步 I/O 对 I/O 密集型操作很高效，但需要显式的 `await` 点
- **Java**：虚拟线程（Project Loom）让阻塞式 I/O 达到与异步相当的效率，且无需改变语法
- **Java**：同步 API 比异步代码更易于使用和调试
- **Python**：Trio 提供结构化并发；Java 则通过 try-with-resources 和 ExecutorService 达成类似效果

### 迁移指南：Python 到 Java

| Python 写法 | Java 等价写法 |
|----------------|-----------------|
| `async with client:` | `try (var client = ...) {` |
| `await client.connect()` | `client.connect()`（同步） |
| `async for msg in client.receive():` | `for (Message msg : client.receiveResponse())` |
| `await asyncio.sleep(1)` | `Thread.sleep(1000)` |
| `asyncio.create_task()` | `Thread.startVirtualThread(() -> ...)` |
| `async def generator():` + `yield` | 实现 `Iterator<T>` |
| `CompletableFuture.completed()` | `CompletableFuture.completedFuture()` |

**结论：** Java SDK 优先考虑简洁性，采用同步 API 并借助虚拟线程实现高效并发；而 Python
SDK 使用 async/await 来完成非阻塞操作。两者以各自语言的惯用方式实现了相似的功能。

## 最佳实践

1. **始终关闭 client** —— 使用 try-with-resources，或在 finally 块中调用 `disconnect()`。
2. **优雅地处理错误** —— 捕获具体的异常类型，以获得更清晰的错误信息。
3. **设置合理的上限** —— 使用 `maxTurns` 和 `maxBudgetUsd` 限制执行规模。
4. **用权限回调来保障安全** —— 不要只依赖 `permissionMode`。
5. **优先使用 SDK MCP 服务器** —— 它们比外部进程更快、更易于调试。

## 文档

- **[Python SDK 功能对等分析](../PYTHON_SDK_PARITY.md)** —— Python 与 Java SDK 的全面对比，
  包括功能对等状态、类型系统对比、示例覆盖情况和实现细节。（英文）
- **[技术文档索引](./index.md)** —— 架构、功能指南与 API 参考（本语言版本）。
- **[翻译说明](../TRANSLATIONS.md)** —— 翻译范围、同步策略与贡献方式。（英文）

## 许可证

[MIT](../../LICENSE)
