# Claude Agent SDK for Java

[English](../../README.md) · [简体中文](../zh/README.md) · **日本語** · [한국어](../ko/README.md) · [Português](../pt/README.md) · [Español](../es/README.md)

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../../README.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。詳細は [docs/TRANSLATIONS.md](../TRANSLATIONS.md) を参照してください。

Claude Agent 用の Java SDK です。本 SDK は Claude Code とやり取りするための包括的な Java API を提供し、Claude の能力を活かした AI アプリケーションを構築できるようにします。

## 動作要件

- Java 17 以降（sealed インターフェースと record を使用しています）
  - Java 21 以降では、SDK はバックグラウンド処理を仮想スレッド上で実行します。17〜20
    では代わりに名前付きのデーモン・プラットフォームスレッドを使用します。設定は不要です。
- Maven 3.6+

**注意：** Claude Code CLI は別途インストールする必要があります：
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

または、独自のパスを指定します：
```java
ClaudeAgentOptions.builder()
    .cliPath(Path.of("/path/to/claude"))
    .build();
```

## インストール

Maven Central で公開されているため、リポジトリや認証の設定は不要です。

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

### 代替手段：GitHub Packages

すでに GitHub Packages を利用している方のために、リリースはそちらにもミラーされています。
成果物自体は公開されているにもかかわらず、この経路では GitHub のパーソナルアクセストークンが
必要になるため、特別な理由がない限り Maven Central を優先してください。

<details>
<summary>GitHub Packages の設定</summary>

`pom.xml` にリポジトリを追加します：

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java</url>
    </repository>
</repositories>
```

または `build.gradle.kts` に追加します：

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

次に認証を設定します。Maven の場合は `~/.m2/settings.xml` に以下を追加します：

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

Gradle の場合は `~/.gradle/gradle.properties` を作成または更新します：

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

`read:packages` スコープを持つパーソナルアクセストークンを https://github.com/settings/tokens で発行してください。

</details>

## クイックスタート

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

## 基本的な使い方：ClaudeSDK.query()

`ClaudeSDK.query()` は単発のシンプルなクエリ向けです。すべての応答メッセージを含む
`List<Message>` を返します。

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

### 便利メソッド

```java
// Get just the text response
String text = ClaudeSDK.queryForText("What is the capital of France?");
System.out.println(text);  // "Paris"

// Get just the result message
ResultMessage result = ClaudeSDK.queryForResult("Do something", options);
System.out.println("Cost: $" + result.totalCostUsd());
```

### ツールの利用

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

### 作業ディレクトリ

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/path/to/project"))
    .build();
```

### ストリーミング入力（複数メッセージ）

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

## ClaudeSDKClient

`ClaudeSDKClient` は Claude Code との双方向・対話的な会話をサポートします。`query()` とは
異なり、**マルチターンの会話**、**カスタムツール**、**フック**、**リアルタイムのやり取り**
を可能にします。

### 基本的な使い方

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

### 接続の手動管理

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

### 実行の中断

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Do a long task");

    // In another thread or after some condition
    client.interrupt();  // Sends interrupt signal
}
```

### モデルの動的切り替え

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start with default model");

    // Switch to a different model mid-conversation
    client.setModel("claude-sonnet-4-5");

    client.sendMessage("Continue with new model");
}
```

### 権限モードの変更

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect("Start in default mode");

    // Change permission mode during conversation
    client.setPermissionMode(PermissionMode.BYPASS_PERMISSIONS);

    client.sendMessage("Now run dangerous commands");
}
```

### MCP サーバーの状態

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

### SDK ユーティリティ

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

## カスタムツール（SDK MCP サーバー）

Java アプリケーションのプロセス内で直接動作する MCP サーバーを作成します。

### @Tool アノテーションを使う

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

### SdkMcpTool を直接使う

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

### ツール結果の型

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

### 長時間実行されるツールのキャンセル

MCP のタイムアウトを超えたツールについて、CLI は待機を打ち切り
`notifications/cancelled` を送信します。呼び出しは待たずに応答されますが、ハンドラ自身が
確認しない限り動き続けます——`CompletableFuture` は外部から割り込むことができないためです。
引数に加えて `ToolCallContext` を受け取ることで、キャンセルを検知できます：

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

引数のみを受け取るハンドラは影響を受けません。ブロッキング読み取りなど、ポーリングできない
処理には `context.onCancel(...)` を使います。

### 独自の MCP サーバーを持ち込む

`McpSdkServerConfig` は `McpMessageHandler` を保持しています。そのため、組み込みサーバーが
提供していない MCP の機能（リソース、プロンプト、補完）が必要なアプリケーションは、この
インターフェースを自前で実装し、同じ方法で登録できます。
[docs/feature-mcp-servers.md](../feature-mcp-servers.md#custom-mcp-handlers) を参照してください。

### 外部 MCP サーバーに対する利点

- **サブプロセス管理が不要** —— アプリケーションと同じ JVM 上で動作します
- **より良い性能** —— ツール呼び出しに IPC のオーバーヘッドがありません
- **デプロイが簡単** —— 複数プロセスではなく単一の Java プロセスで済みます
- **デバッグが容易** —— すべてのコードが同一プロセス内で動作します
- **型安全** —— Java のメソッドを直接呼び出します

## フック（Hooks）

フックは、Claude Code がエージェントループの特定の地点で呼び出すコールバックです。
決定論的な処理と自動化されたフィードバックを実現します。

### フックイベント

| イベント | 説明 |
|-------|-------------|
| `PreToolUse` | ツールの実行前 |
| `PostToolUse` | ツールの完了後 |
| `UserPromptSubmit` | ユーザーがプロンプトを送信したとき |
| `Stop` | セッションが停止したとき |
| `SubagentStop` | サブエージェントが停止したとき |
| `PreCompact` | コンテキスト圧縮の前 |

### 例：危険なコマンドのブロック

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

### 例：すべてのツール利用をログに記録する

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

## 権限コールバック

独自の権限ロジックでツールの実行を制御します。

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

### ツール入力の変更

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

### 権限の更新

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

## 設定オプション

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

### 権限モード

```java
PermissionMode.DEFAULT           // CLI prompts for dangerous tools
PermissionMode.ACCEPT_EDITS      // Auto-accept file edits
PermissionMode.PLAN              // Show plans before execution
PermissionMode.BYPASS_PERMISSIONS // Allow all tools (use with caution)
```

### 拡張思考（Thinking）の設定

`ThinkingConfig` 型で拡張思考の挙動を制御します（v0.1.36 で追加）：

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

**注意：** `thinking` フィールドは、非推奨の `maxThinkingTokens` フィールドより優先されます。

### MCP サーバーの設定

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

### サンドボックスの設定

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

## メッセージ型

SDK は型安全なメッセージ処理のために sealed インターフェースを使用しており、パターン
マッチングが利用できます。

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

`Message` は sealed インターフェースなので、新しいメッセージ型が追加されると `default` の
ない網羅的な `switch` はコンパイルできなくなります。将来の追加を黙って受け流したい場合は
`default ->` 分岐を追加してください。

### メッセージの出所（Message Origin）

ストリーミング入力モードでは、1 本の接続の中に、あなたが送ったターンとセッションが自律的に
差し込むターン——バックグラウンドタスクの通知、発火したスケジュールタスクのプロンプト、
MCP チャネルのメッセージ、ピアセッションから中継されたメッセージ——が交互に現れます。
`UserMessage` と `ResultMessage` の `origin()` がそれらを区別します：

```java
MessageOrigin origin = result.origin();
if (origin == null || origin.isHuman()) {
    // a turn this application submitted
} else if (origin.kind() == MessageOriginKind.TASK_NOTIFICATION) {
    // follow-up turn driven by a background task
}
```

CLI がメッセージに出所を付与しなかった場合、`origin()` は null になります。`query()` 経由で
送ったプロンプトは、ストリーミングするメッセージの map に自分で
`"origin": {"kind": "human"}` を付けない限り、この形で届きます——SDK ホスト側から受け付け
られるのは `human` という種別だけです。この SDK がモデル化しているより新しい種別の場合、
`kind()` は null になりますが、プロトコル上の文字列は `kindValue()` から読み取れます。また
それが human とみなされることはありません。

### コンテンツブロック

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

## エラー処理

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

### 例外の階層

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

`ResultException` は、CLI が失敗した実行を `is_error: true` の `result` メッセージで終え、
その後に非ゼロで終了したときに送出されます。このケースにおいては、単なる「終了コード 1」の
`ProcessException` を置き換えるものであり、その result のペイロード——`subtype()`、
`errors()`、`result()`、`apiErrorStatus()`、`terminalReason()`、`sessionId()`、および生の
`data()`——を保持します。これにより、呼び出し側は実行が失敗した*理由*に応じて分岐できます。
なお、API の障害で終了した実行は `subtype() == "success"` かつ
`terminalReason() == "api_error"` として届き、説明文は `result()` に入ります。既存の
`catch (ProcessException e)` の処理はそのまま動作します。

通常、この例外は単独ではなく `QueryFailedException.getCause()` として現れます。収集を行う
`query(...)` がこれをラップし、すでに受信したメッセージが失われないようにするためです。
単独で送出されるのは、CLI が起動時に拒否した `initialize` のように、制御リクエストが失敗
した場合だけです。`ClaudeSDKClient.receiveResponse()` はこれをまったく送出しません——その
イテレータは `ResultMessage` で停止するため、そこでは代わりに `ResultMessage.isError()`
を確認してください。

`QueryFailedException` は、収集を行う `ClaudeSDK.query(...)` 系のメソッドに固有のものです。
CLI は `error_max_turns` や `error_max_budget_usd` といった状況を、完全な 1 ターン——
subtype とコストを持つ最終的な `ResultMessage` を含む——を発行し、*その後に*非ゼロで終了する
という形で報告します。ストリーミングの消費者
（`ClaudeSDKClient.receiveMessages()` / `receiveResponse()`）は送出前にそれらのメッセージを
すべて受け取れます。一方、収集を行う呼び出しはリストを返すか例外を投げるかの二択なので、
この例外を投げ、収集済みメッセージを `partialMessages()` と便利アクセサ `resultMessage()`
経由で返します。その `getCause()` は、上で説明した `ResultException` です。`maxTurns` や
`maxBudgetUsd` を設定したときは必ずこれを捕捉してください。自分で設定した上限に達することは
クラッシュではなく、想定内の結果です。

## ストリーミングイベント

リアルタイム更新のために、部分メッセージのストリーミングを有効にします。

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

## ファイルチェックポイント

ファイルの変更を追跡し、以前の状態に巻き戻します。

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

## カスタムエージェント

特定の能力を持つカスタムエージェントを定義します。

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

## サンプル

動作する完全なサンプルは `examples/` モジュールを参照してください：

- `QuickStart.java` —— 基本的な使い方
- `MultiTurnConversation.java` —— 対話的な会話
- `ToolUsage.java` —— 組み込みツールの利用
- `McpServer.java` —— カスタム MCP ツールの作成
- `AutoSchemaGeneration.java` —— ツールのスキーマ自動生成
- `Hooks.java` —— フックのコールバック（新しいフックイベント Notification、SubagentStart、PermissionRequest を含む）
- `PermissionCallbacks.java` —— 独自の権限ロジック
- `StreamingEvents.java` —— リアルタイムのストリーミング
- `StructuredOutputExample.java` —— JSON Schema 検証付きの構造化出力（シンプル、ネスト、列挙、ツール併用）
- `DynamicControlExample.java` —— 動的制御機能（setPermissionMode、setModel、interrupt）
- `ErrorHandling.java` —— 例外処理
- `AdvancedFeatures.java` —— チェックポイント、サンドボックス、構造化出力
- `ToolsConfigurationExample.java` —— ツール設定（配列、プリセット、空）
- `MaxBudgetExample.java` —— 予算制限とコスト管理
- `SettingSourcesExample.java` —— 設定の取得元（user、project、local）
- `StderrCallbackExample.java` —— CLI の stderr 出力の取得
- `PluginsExample.java` —— プラグインシステムの利用
- `AgentsExample.java` —— プログラムによるサブエージェント定義
- `FilesystemAgentsExample.java` —— ファイルシステムベースのエージェント設定
- `LargeAgentsExample.java` —— initialize リクエスト経由の大きなエージェント定義（260KB 超）
- `SystemPromptExample.java` —— カスタムシステムプロンプトの利用
- `IncludePartialMessagesExample.java` —— 部分メッセージ更新付きのストリーミング

### サンプルの実行

examples モジュールは SDK に依存する独立した Maven モジュールです。リポジトリのルートから
ビルドすればその依存はリアクターから解決されます。`examples/` を単独でビルドした場合は
Maven Central から解決され、リポジトリや認証の設定は不要です。

#### 方法 1：ルートディレクトリからサンプルを実行（推奨）

すべてのモジュールをビルドしてサンプルを実行します：

```bash
# Build all modules (SDK + examples)
mvn clean package -DskipTests

# Run an example using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples

# Run different examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
```

#### 方法 2：examples ディレクトリからサンプルを実行

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

#### 方法 3：ローカル開発版の SDK でサンプルを実行

公開版ではなく、ローカルで開発中の SDK に対してサンプルを試すには：

1. SDK をローカルにインストールします：
   ```bash
   cd sdk
   mvn clean install -DskipTests
   cd ..
   ```

2. `examples/pom.xml` を SNAPSHOT バージョンに更新します：
   ```xml
   <dependency>
       <groupId>in.vidyalai</groupId>
       <artifactId>claude-agent-sdk-java</artifactId>
       <version>0.1.1-SNAPSHOT</version>
   </dependency>
   ```

3. 方法 1 または方法 2 の手順でサンプルを実行します。

**注意：** `examples/pom.xml` の依存の `<version>` をリリース版（例：`0.2.2`）に設定して、
examples モジュールをそのリリースに固定することもできます。Maven Central から解決されるため、
リポジトリや認証の設定は不要です。

## スレッド安全性

- `ClaudeSDKClient` は**スレッドセーフではありません**。スレッドごとに 1 つの client を使うか、
  アクセスを同期してください。
- `ClaudeSDK.query()` 系のメソッドは新しい接続を作るため、複数スレッドから安全に呼び出せます。
- コールバック（フック、権限）は異なるスレッドから呼ばれる可能性があります。コールバックの
  実装がスレッドセーフであることを確認してください。

## 並行処理モデル：Python と Java

Java SDK は Python SDK とは根本的に異なる並行処理モデルを採用しています。この違いを理解して
おくと、コードの移植やサンプルの比較に役立ちます。

### Python SDK：async/await モデル

Python SDK は asyncio や trio とともに Python の `async`/`await` 構文を使います：

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

**Python の主な特徴：**
- 非ブロッキング操作のための `async`/`await` キーワード
- 非同期イテラブルを反復する `async for`
- 非同期コンテキストマネージャのための `async with`
- 非同期イテラブル／ジェネレータ（`async def` と `yield`）
- ライブラリ：asyncio、trio

### Java SDK：同期 + CompletableFuture モデル

Java SDK は同期 API を用い、非同期処理には CompletableFuture を使います：

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

**Java の主な特徴：**
- 非同期イテラブルではなく**同期イテレータ**（`Iterator<Message>`）
- 非同期コンテキストマネージャではなく **try-with-resources**（`try (...)`）
- 非同期コールバック（フック、権限）のための **CompletableFuture**
- Java 21 以降でブロッキング I/O を効率的に扱う**仮想スレッド**（17〜20 ではプラットフォームスレッドにフォールバック）
- バックグラウンドタスク管理のための **ExecutorService**

### Python の非同期サンプルがそのまま対応しない理由

Python SDK のサンプルの中には、非同期特有のパターンを示しているために Java に直接の対応物が
ないものがあります：

| Python のサンプル | Java にない理由 | Java での等価な手段 |
|----------------|-----------------|-----------------|
| `streaming_mode_ipython.py` | IPython 特有の非同期 REPL 統合 | Java の REPL（jshell）と同期 API を使う |
| `streaming_mode_trio.py` | Trio 特有の並行処理ライブラリ | 標準の Java 並行処理（ExecutorService、仮想スレッド）を使う |
| `test_connect_with_async_iterable` | 非同期ジェネレータのパターン | 通常の Iterator を使う `client.query(Iterator<Map>)` |
| `test_concurrent_send_receive` | 非同期の並行処理 | `Thread.startVirtualThread()` または ExecutorService を使う |

### Java での並行処理

Java で本当に並行性が必要な処理の場合：

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

### 性能面の考慮

- **Python**：非同期 I/O は I/O バウンドな処理に効率的ですが、明示的な `await` 地点が必要です
- **Java**：仮想スレッド（Project Loom）により、構文を変えずにブロッキング I/O を非同期と同等の効率で扱えます
- **Java**：同期 API は非同期コードより使いやすくデバッグしやすいです
- **Python**：Trio は構造化並行性を提供します。Java は try-with-resources と ExecutorService で同様のことを実現します

### 移行ガイド：Python から Java へ

| Python のパターン | Java での等価な書き方 |
|----------------|-----------------|
| `async with client:` | `try (var client = ...) {` |
| `await client.connect()` | `client.connect()`（同期） |
| `async for msg in client.receive():` | `for (Message msg : client.receiveResponse())` |
| `await asyncio.sleep(1)` | `Thread.sleep(1000)` |
| `asyncio.create_task()` | `Thread.startVirtualThread(() -> ...)` |
| `async def generator():` + `yield` | `Iterator<T>` の実装 |
| `CompletableFuture.completed()` | `CompletableFuture.completedFuture()` |

**まとめ：** Java SDK はシンプルさを重視し、同期 API と仮想スレッドで効率的な並行処理を
実現します。一方 Python SDK は非ブロッキング操作のために async/await を使います。どちらも
それぞれの言語のイディオムで同等の機能を達成しています。

## ベストプラクティス

1. **client は必ず閉じる** —— try-with-resources を使うか、finally ブロックで `disconnect()` を呼びます。
2. **エラーを適切に処理する** —— 具体的な例外を捕捉すると、より分かりやすいエラーメッセージが得られます。
3. **適切な上限を設定する** —— `maxTurns` と `maxBudgetUsd` で実行量を制限します。
4. **セキュリティには権限コールバックを使う** —— `permissionMode` だけに頼らないでください。
5. **SDK MCP サーバーを優先する** —— 外部プロセスより高速でデバッグも容易です。

## ドキュメント

- **[Python SDK 機能パリティ分析](../PYTHON_SDK_PARITY.md)** —— Python SDK と Java SDK の
  包括的な比較。機能パリティの状況、型システムの比較、サンプルの網羅状況、実装の詳細を含みます。（英語）
- **[技術ドキュメント索引](./index.md)** —— アーキテクチャ、機能ガイド、API リファレンス（この言語版）。
- **[翻訳について](../TRANSLATIONS.md)** —— 翻訳の範囲、同期の方針、貢献方法。（英語）

## ライセンス

[MIT](../../LICENSE)
