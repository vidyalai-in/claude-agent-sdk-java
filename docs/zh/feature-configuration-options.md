# 配置选项

使用 `ClaudeAgentOptions` 配置 Claude SDK 行为的完整指南。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-configuration-options.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [构建器模式](#构建器模式)
- [工具配置](#工具配置)
- [系统提示词](#系统提示词)
- [MCP 服务器](#mcp-服务器)
- [权限设置](#权限设置)
- [会话管理](#会话管理)
- [上限](#上限)
- [模型配置](#模型配置)
- [工作目录与 CLI](#工作目录与-cli)
- [环境变量](#环境变量)
- [回调](#回调)
- [钩子](#钩子)
- [高级功能](#高级功能)
- [完整示例](#完整示例)

## 概览

`ClaudeAgentOptions` 提供 30 多个配置项来控制 Claude SDK 的行为。它采用不可变的构建器模式，实现类型安全的配置。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();
```

## 构建器模式

### 创建选项

```java
// Start with builder
ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();

// Configure
builder.model("claude-sonnet-5")
       .maxTurns(10);

// Build immutable instance
ClaudeAgentOptions options = builder.build();
```

### 默认选项

```java
// Use defaults
ClaudeAgentOptions options = ClaudeAgentOptions.defaults();
```

### 修改已有选项

```java
// Create from existing
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .model("claude-opus-4-6")
    .build();
```

## 工具配置

### tools()

指定 Claude 可以使用哪些工具。

```java
// Use all available tools (default)
.tools(null)

// Specify list of tool names
.tools(List.of("Read", "Write", "Bash"))

// Use preset
.tools(new ToolsPreset("code-editing"))
```

**工具名称**：
- `Read` —— 读取文件
- `Write` —— 写入／创建文件
- `Edit` —— 编辑已有文件
- `Bash` —— 执行 bash 命令
- `Grep` —— 搜索文件内容
- `Glob` —— 按模式查找文件
- `Task` —— 启动子 agent
- `WebFetch` —— 抓取网页内容
- `WebSearch` —— 搜索网络
- MCP 工具：`mcp__<server>__<tool>`

### allowedTools()

把特定工具加入白名单。

```java
.allowedTools(List.of(
    "Read",
    "Grep",
    "Glob",
    "mcp__calc__add"
))
```

### disallowedTools()

把特定工具加入黑名单。

```java
.disallowedTools(List.of(
    "Bash",      // Block shell access
    "Write",     // Block file writing
    "WebFetch"   // Block web access
))
```

**优先级**：`disallowedTools` 优先于 `allowedTools`。

## 系统提示词

### systemPrompt()

设置自定义系统提示词来引导 Claude 的行为。

```java
// String prompt
.systemPrompt("You are a code reviewer. Focus on security and performance.")

// Multi-line prompt
.systemPrompt("""
    You are a helpful coding assistant.
    - Be concise
    - Provide working code examples
    - Explain your reasoning
    """)

// Use Claude Code preset
.systemPrompt(SystemPromptPreset.claudeCode())

// Use Claude Code preset with additional instructions
.systemPrompt(SystemPromptPreset.claudeCode("Always respond in JSON format."))

// Use Claude Code preset with exclude_dynamic_sections for cross-user caching
.systemPrompt(SystemPromptPreset.claudeCode("Custom instructions", true))

// Custom prompt in the form that can also set snapshot (see below)
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))

// Use prompt from file
.systemPrompt(new SystemPromptFile("/path/to/prompt.md"))
```

| 形式 | 发送给 CLI 的方式 |
|------|--------------------|
| `String` | `--system-prompt <text>` |
| `SystemPromptCustom` | `--system-prompt <prompt>`，外加 `initialize` 请求上的 `snapshot` |
| `SystemPromptPreset` | 设置了 `append` 时为 `--append-system-prompt <append>`；`excludeDynamicSections` 与 `snapshot` 放在 `initialize` 请求上 |
| `SystemPromptFile` | `--system-prompt-file <path>` |
| 未设置（`null`） | `--system-prompt ""`（不使用系统提示词） |

#### 快照（snapshot）

默认情况下，Claude Code 会在会话的第一次请求时构建系统提示词并将其记录下来，之后的每次请求（包括恢复会话之后）都复用它。因此，修改后的自定义提示词，或预设上修改后的 `append` 文本，在会话被压缩或开启新会话之前都不会生效。如果希望每次请求都重新构建提示词（例如在反复调整措辞时），请将 `snapshot` 设为 `false`：

```java
.systemPrompt(SystemPromptCustom.of("You are a release bot.", false))
.systemPrompt(SystemPromptPreset.claudeCode("Be concise.").withSnapshot(false))
```

`snapshot` 通过 `initialize` 控制请求以 `systemPromptSnapshot` 的形式发送。只要设置了就会发送（包括 `false`），为 `null` 时则省略，此时采用 CLI 的默认值：`true`；但在 bare 模式（`--bare`）下其行为相当于 `false`。只有预设形式和自定义形式才携带该字段。需要 Claude Code CLI 2.1.257 或更高版本；在 2.1.265 之前，带有 `append` 或自定义提示词的会话只有在 `snapshot` 为 `true` 时才会记录它。

## MCP 服务器

### mcpServers()

为自定义工具配置 Model Context Protocol 服务器。

```java
// SDK MCP server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

.mcpServers(Map.of("tools", sdkServer))

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("server.js"),
    Map.of("NODE_ENV", "production")
);

.mcpServers(Map.of(
    "sdk", sdkServer,
    "external", externalServer
))

// From file path (passed as-is to --mcp-config; Java does not expand "~")
.mcpServers(Path.of(System.getProperty("user.home"), ".claude", "mcp_servers.json"))

// From an inline JSON string (also passed as-is to --mcp-config)
.mcpServersJson("""
    {"mcpServers": {
        "server1": {"type": "stdio", "command": "node", "args": ["server.js"]}
    }}
    """)
```

map 在传给 `--mcp-config` 之前会被序列化为 `{"mcpServers": {...}}`；路径或 JSON 字符串则原样传递，因此 JSON 字符串必须使用同样的顶层 `mcpServers` 键。

## 权限设置

### permissionMode()

控制工具权限的处理方式。

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**模式**：
- `DEFAULT`（CLI 的默认值）—— 标准权限行为
- `ACCEPT_EDITS` —— 自动接受文件编辑，其他情况询问
- `PLAN` —— 规划模式；不执行任何工具
- `BYPASS_PERMISSIONS` —— 完全跳过权限检查
- `DONT_ASK` —— 不询问；拒绝所有未被 allow 规则预先批准的操作
- `AUTO` —— 由模型分类器批准或拒绝每次工具调用

### permissionPromptToolName()

指定用于权限提示的工具（高级用法，通常自动设置）。

```java
.permissionPromptToolName("stdio")
```

## 会话管理

### continueConversation()

继续之前的会话。

```java
.continueConversation(true)  // Continue from last session
.continueConversation(false) // Start fresh (default)
```

### resume()

按 ID 恢复特定会话。

```java
.resume("session-12345")
```

### sessionId()

为新会话指定会话 ID。

```java
.sessionId("my-custom-session-id")
```

### forkSession()

把恢复的会话分叉成新会话（保留上下文，使用新 ID）。

```java
.resume("session-12345")
.forkSession(true)
```

### sessionStore()

把会话记录镜像到外部存储（S3、Postgres、Redis 或自定义后端）。设置后，SDK 会在调用 CLI 时加上 `--session-mirror`，并把每一行记录转发给 `store.appendAsync(...)`。恢复会话时搭配 `sessionStore`，会把内容从存储物化到一个临时的 `CLAUDE_CONFIG_DIR`，让 CLI 能在本地接上这段会话。完整功能见 [Session Store 指南](./feature-session-store.md)。

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .build();
```

**校验防护**（在启动子进程前就以 `IllegalArgumentException` 拒绝）：
- `continueConversation + sessionStore` 要求 `store.implementsListSessions()`。
- `sessionStore + enableFileCheckpointing` 会被拒绝 —— 检查点只支持本地磁盘。

### sessionStoreFlush()

控制何时把 transcript-mirror 条目刷写到配置的 `sessionStore`。默认是 `SessionStoreFlushMode.BATCHED`（每轮或缓冲区溢出时刷写一次）。使用 `SessionStoreFlushMode.EAGER` 会在每一帧之后安排一次后台刷写，实现近乎实时的投递 —— 追加仍按入队顺序串行执行，但慢速适配器不会阻塞读取循环。未设置 `sessionStore` 时该项被忽略。

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

取舍详见[刷写模式（BATCHED 与 EAGER）](./feature-session-store.md#刷写模式batched-与-eager)。

### loadTimeoutMs()

恢复物化期间，对 `store.loadAsync()` 与 `listSubkeysAsync()` 每次调用的超时时间（毫秒）。默认为 `60_000`。如果适配器在该窗口内没有完成，查询会以清晰的错误失败，而不是让迭代器一直挂起。

```java
.loadTimeoutMs(30_000)  // 30 seconds
```

## 上限

### maxTurns()

最大会话轮数。

```java
.maxTurns(10)  // Limit to 10 turns
```

**适用场景**：
- 预算控制
- 防止会话失控
- 快速查询：`.maxTurns(1)`

### maxBudgetUsd()

以美元计的最高花费。

```java
.maxBudgetUsd(1.0)  // Limit to $1.00
```

超出预算时停止执行。

### taskBudget()

以 token 计的 API 侧任务预算。设置后，模型会知道自己剩余的 token 预算。

```java
.taskBudget(new TaskBudget(100000))  // 100K token budget
```

### maxBufferSize()

缓冲 CLI stdout 的最大字节数。

```java
.maxBufferSize(10 * 1024 * 1024)  // 10MB
```

默认值：100MB。输出很大时可以调高。

### thinking()

**新增**：以细粒度配置控制扩展思考行为。

```java
// Adaptive thinking (32K token default)
.thinking(new ThinkingConfigAdaptive())

// Fixed token budget
.thinking(new ThinkingConfigEnabled(10000))

// Disable thinking
.thinking(new ThinkingConfigDisabled())
```

**类型**：
- `ThinkingConfigAdaptive` —— 自适应思考，默认 32,000 token
- `ThinkingConfigEnabled(int budgetTokens)` —— 固定 token 预算（必须大于 0）
- `ThinkingConfigDisabled` —— 不使用思考 token

**注意**：本选项优先于已弃用的 `maxThinkingTokens()`。

完整指南见[扩展思考配置](./feature-thinking-config.md)。

### effort()

设置思考的深度／强度级别。有两个重载 —— 可以传入原始字符串，
也可以传入类型安全的 [`EffortLevel`](#effortlevel-枚举) 枚举。

```java
// String overload
.effort("low")     // Minimal thinking, fastest responses
.effort("medium")  // Moderate thinking
.effort("high")    // Deep reasoning (default)
.effort("xhigh")   // Extended depth (Opus 4.7 only; falls back to "high")
.effort("max")     // Maximum reasoning

// Enum overload (recommended for type safety)
.effort(EffortLevel.HIGH)
.effort(EffortLevel.XHIGH)
.effort((EffortLevel) null)  // clear
```

**有效取值**：`"low"`、`"medium"`、`"high"`、`"xhigh"`、`"max"`

`"xhigh"` 专属于 Opus 4.7，在其他模型上会回退为 `"high"`。

与 `thinking()` 配合使用来控制推理深度。

示例见[扩展思考配置](./feature-thinking-config.md)。

### EffortLevel 枚举

位于 `in.vidyalai.claude.sdk.types.config.EffortLevel` 的公开枚举，对应 Python
的 `EffortLevel` 类型别名。之所以公开，是为了让下游的 SDK 封装可以直接引用该类型。

| 常量 | 线上取值 | 说明 |
|----------|------------|-------------|
| `EffortLevel.LOW` | `"low"` | 最少思考，响应最快 |
| `EffortLevel.MEDIUM` | `"medium"` | 适度思考 |
| `EffortLevel.HIGH` | `"high"` | 深度推理（默认） |
| `EffortLevel.XHIGH` | `"xhigh"` | 扩展推理（仅 Opus 4.7；其他模型回退为 `HIGH`） |
| `EffortLevel.MAX` | `"max"` | 最大投入 |

辅助方法：
- `EffortLevel.getValue()` 返回小写的线上取值（同时也是 `@JsonValue` 序列化器）。
- `EffortLevel.fromValue(String)` 把线上取值解析回枚举常量；遇到未知值抛出 `IllegalArgumentException`。

```java
EffortLevel level = EffortLevel.fromValue("xhigh");
String wire = level.getValue(); // "xhigh"
```

### maxThinkingTokens()

**已弃用**：请改用 `thinking()`：可设为自适应、带 token 预算的启用，或禁用。

思考块的最大 token 数。在较新的模型上，该值只被视为开/关（0 = 禁用，其他任何值 = 自适应）。

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

消息队列的最大长度。

```java
.maxMsgQSize(1000)
```

高吞吐场景可以调高。

## 模型配置

### model()

设置 AI 模型。

```java
.model("claude-sonnet-5")
```

**可用模型**：
- `claude-opus-4-6` —— 能力最强，价格最高
- `claude-sonnet-4-5` —— 均衡（默认）
- `claude-haiku-4-5` —— 快速、经济

### fallbackModel()

主模型不可用时的回退模型。

```java
.model("claude-opus-4-6")
.fallbackModel("claude-sonnet-4-5")
```

### betas()

启用 beta 功能。

```java
.betas(List.of(
    SdkBeta.PROMPT_CACHING,
    SdkBeta.EXTENDED_THINKING
))
```

参见 [Anthropic API Beta Headers](https://docs.anthropic.com/en/api/beta-headers)。

## 工作目录与 CLI

### cwd()

设置文件操作的工作目录。

```java
.cwd(Path.of("/path/to/project"))
```

**重要**：涉及文件操作时务必设置，以确保路径正确。

### cliPath()

Claude Code CLI 的自定义路径。

```java
.cliPath(Path.of("/custom/path/to/claude"))
```

默认：在系统 PATH 中搜索。

**Windows**：`.bat`/`.cmd` 路径（npm 的 `claude.cmd` 垫片）会被拒绝 —— 操作系统会通过 `cmd.exe` 运行它，而 `cmd.exe` 会重新解析命令行。请指向 `claude.exe`，或参见下面的 `allowUnsafeWindowsBatchCli()`。

### allowUnsafeWindowsBatchCli()

为无法迁移到原生 `claude.exe` 的部署放宽 Windows 批处理脚本的拒绝策略。默认为 `false`。

```java
.cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
.allowUnsafeWindowsBatchCli(true)
```

这**不是**简单的绕过 —— 单纯的豁免会重新打开 `cmd.exe` 的重解析漏洞。启用后还会额外：

1. **要求 JVM 上设置 `-Djdk.lang.Process.allowAmbiguousCommands=false`**。该属性默认为 `true`，此时 JDK 在批处理启动时只对空白字符加引号；设为 `false` 会让它对 `" < > & | ^` 加引号，并拒绝包含引号的参数。如果缺少该标志，`connect()` 会抛出 `CLIConnectionException`。
2. **拒绝每个 CLI 参数中的 `& | < > ^ % ! "` 以及 CR/LF**，抛出 `IllegalArgumentException` 并指明违规的选项。`%` 和 `!` 不在 JDK 的转义集合中，而且加引号也挡不住 `%VAR%` 展开。
3. **记录一条 `WARNING`**，说明已接受的风险。

**残余风险**：cmd.exe 仍会从环境变量展开 `%VAR%`。仅在 CLI 路径和每个参数值都由管理员控制的场景下使用。在 POSIX 上会被忽略。参见[传输层 → 批处理 CLI 选择开关](./feature-transport-layer.md#windows批处理-cli-选择开关0122)。

### settings()

额外设置 JSON 文件的路径，或内联 JSON 字符串。

```java
.settings("/path/to/settings.json")
.settings("{\"permissions\": {\"allow\": [\"Read\"]}}")
```

未设置 `sandbox()` 时，该值会原样传给 `--settings`。同时设置了 `sandbox()` 时，两者会合并为一个 JSON 字符串：以 `{` 开头并以 `}` 结尾的值按 JSON 解析，其他值按文件路径读取（文件不存在或无法读取时会记录日志，并且只传递沙箱设置）。这些设置会加载到 CLI 的"标志设置"（flag settings）层，它在用户可控的设置中优先级最高。

### addDirs()

要加入上下文的额外目录。

```java
.addDirs(List.of(
    Path.of("/path/to/lib"),
    Path.of("/path/to/docs")
))
```

## 环境变量

### env()

为 CLI 进程设置环境变量。

```java
.env(Map.of(
    "API_KEY", "secret-key",
    "DEBUG", "true",
    "NODE_ENV", "production"
))
```

该 map 会合并到父进程的环境之上：这里的条目覆盖继承来的值，并且会从继承的变量集中去掉 `CLAUDECODE`。传输层还会设置：

| 变量 | 何时设置 | 能否在此覆盖 |
|----------|------|------------------|
| `CLAUDE_CODE_ENTRYPOINT=sdk-java` | 始终 | 能 |
| `CLAUDE_AGENT_SDK_VERSION` | 始终 | 不能 |
| `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` | 除非此 map 或继承的环境中已经出现该变量（不区分大小写） | 能 |
| `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true` | `enableFileCheckpointing(true)` | — |
| `PWD` | 设置了 `cwd(...)` | — |
| `TRACEPARENT` / `TRACESTATE` | 存在活动的 OpenTelemetry span（参见 [Trace Context](./feature-trace-context.md)） | 能 |

这里有两个变量用于调节一次性查询为钩子和 SDK MCP 调用保持 stdin 打开的时长（参见[架构 → stdin 的生命周期](./architecture.md#stdin-的生命周期与一次运行的结束)）：`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS` 限定轮次之间的等待时间（默认 `600000`，`0` 表示不限制），`CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` 则让你选择接收 CLI 的 `session_state_changed` 帧。设置 `CLAUDE_AGENT_SDK_CLIENT_APP`（例如 `"my-app/1.0.0"`）可以在 User-Agent 头中标识你的应用。

### extraArgs()

传入任意 CLI 标志。

```java
.extraArgs(Map.of(
    "replay-user-messages", "",   // flag with no value: --replay-user-messages
    "debug", "api"                // flag with a value: --debug api
))
```

键是**不带**前导 `--` 的标志名；传输层会自动加上。值为 `null` 或空白时输出一个不带值的标志。以 `-` 开头的值会以 `--flag=value` 形式发送，以免被解析成另一个独立的标志；其他值则作为两个 token 发送。

## 回调

### canUseTool()

工具的自定义权限回调。与 `permissionPromptToolName` 互斥。

```java
.canUseTool((toolName, input, context) -> {
    // Check permission
    if (isAllowed(toolName)) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Tool not allowed")
        );
    }
})
```

**签名**：
```java
BiFunction<String, Object, ToolPermissionContext, CompletableFuture<PermissionResult>>
```

### stderrCallback()

接收 CLI 的 stderr 输出。CLI 每输出一行 stderr 就调用一次（只有设置了该回调时才会接管管道）。

```java
.stderrCallback(line -> {
    System.err.println("CLI stderr: " + line);
})
```

**异常隔离**：如果你的回调抛出异常，异常会被捕获、以 `FINE` 级别记录（`java.util.logging`），stderr 读取继续进行。有 bug 的回调不再会悄悄终止读取循环，导致后续整个会话的 stderr 行全部丢失。

## 钩子

### hooks()

为生命周期事件注册钩子回调。

```java
.hooks(Map.of(
    HookEvent.PRE_TOOL_USE, List.of(
        new HookMatcher(null, "Read", (context) -> {
            System.out.println("About to read file");
            return CompletableFuture.completedFuture(
                HookOutput.empty()
            );
        })
    )
))
```

**可用事件**：
- `PRE_TOOL_USE` —— 工具执行前
- `POST_TOOL_USE` —— 工具成功后
- `POST_TOOL_USE_FAILURE` —— 工具失败后
- `USER_PROMPT_SUBMIT` —— 用户提交消息
- `STOP` —— 会话停止
- `SUBAGENT_START` —— 子 agent 启动
- `SUBAGENT_STOP` —— 子 agent 停止
- `PRE_COMPACT` —— 消息压缩前
- `NOTIFICATION` —— 通知事件
- `PERMISSION_REQUEST` —— 请求权限

## 高级功能

### user()

设置用于追踪的用户标识。

```java
.user("user-12345")
```

### includePartialMessages()

启用部分消息流式传输。

```java
.includePartialMessages(true)
```

在内容生成过程中接收带增量的 `StreamEvent` 消息，每个 API 流事件对应一条。

### verbatimPrompts()

把每条提示词原样投递给 Claude。

```java
.verbatimPrompts(true)
```

Claude Code 通常会把提示词文本中的 `@/absolute/path` 展开为该文件的内容（即使文件位于工作目录之外，也无需工具调用），并把开头的 `/command` 当作斜杠命令分派。这适合用户亲手输入的文本，却不适合你的应用从别处拼装而来的文本（之前的轮次、工具结果、第三方内容）。开启此选项后，SDK 写出的每条用户消息都会被标记为 `client_composed: true`，Claude Code 会原封不动地投递它。适用范围包括 `ClaudeSDK.query` 的字符串提示词和流式提示词，以及 `ClaudeSDKClient.connect(String)`、`query(String)` 和 `query(Iterator)`。

- 标记加在副本上；你的消息 map 永远不会被修改。
- 选项开启期间，它会覆盖流式消息上已有的任何 `client_composed` 值。如需按轮次控制，请保持该选项关闭，并在单条流式消息上放置 `"client_composed": true`；SDK 会原样透传。
- 在当前的 Claude Code 版本上，原样投递的轮次还会跳过轮次开始时的附件处理：`@server:resource` 形式的 MCP 提及不会被展开，提示词发送时也不会附带通常随之附加的上下文（嵌套的 `CLAUDE.md` 与规则文件、技能与工具列表、其他每轮提醒）。这些上下文大多会改为在该轮第一次工具调用之后到达。
- 需要 Claude Code 2.1.248 或更高版本。旧版本会忽略该字段，传输层在连接时检测到旧版本会记录一条 `WARNING`。

参见 `examples/VerbatimPromptsExample.java`。

### forwardSubagentText()

把子 agent 的文本块与思考块转发到消息流中。

```java
.forwardSubagentText(true)
```

默认情况下，只有子 agent 的 `tool_use` / `tool_result` 块会到达父级流，形式是
`parentToolUseId` 为派生该子 agent 的 Agent `tool_use` 块 id 的
`AssistantMessage` / `UserMessage` 对象 —— 足以作为进度心跳，但不足以呈现子 agent 说了什么。
启用后，它的文本块与思考块也会以同样的方式到达。

该项通过 `initialize` 控制请求发送给 CLI，而不是作为命令行标志，并且只在启用时发送；
较旧的 CLI 会忽略它。参见
[Agents → 观察子 agent 的输出](./feature-agents.md#观察子-agent-的输出)。

### agents()

定义自定义的 agent 配置。

```java
.agents(Map.of(
    "my-agent", new AgentDefinition(
        "Custom agent",
        "claude-sonnet-4-5",
        List.of("Read", "Write"),
        "You are a specialized agent"
    )
))
```

### settingSources()

控制加载哪些设置文件。

```java
.settingSources(List.of(
    SettingSource.USER,     // ~/.claude/
    SettingSource.PROJECT,  // .claude/ in project
    SettingSource.LOCAL     // .claude.local/
))
```

**空列表会禁用所有来源。** 传入 `List.of()` 会向 CLI 发送 `--setting-sources=`（空值），从而抑制所有文件系统设置来源。而当**完全省略**该选项时（默认行为），不会添加 `--setting-sources` 标志，CLI 会应用自己的默认值。

### skills() / skillsAll()

主会话的顶层技能白名单。SDK 会自动把匹配的 `Skill(name)` 条目注入 `allowedTools`，并把 `settingSources` 默认设为 user/project，这样 CLI 无需额外配置就能发现已安装的技能。该列表也会通过 initialize 控制请求传递，以便支持该特性的 CLI 过滤加载进系统提示词的技能（较旧的 CLI 会忽略该字段）。

```java
// Enable every discovered skill
.skillsAll()

// Enable only the listed skills
.skills(List.of("commit", "review"))

// Suppress every skill from the listing
.skills(List.of())
```

三种模式：

| 构建器调用 | `allowedTools` 注入 | `settingSources` 默认值 | initialize 线上字段 |
|---|---|---|---|
| _省略_（null） | 无 | 无 | 省略 |
| `.skillsAll()` | 添加裸 `Skill` | `[user, project]` | 省略 |
| `.skills(List.of("a", "b"))` | 添加 `Skill(a)`、`Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | 无 | `[user, project]` | `[]` |

行为细节：
- **幂等注入** —— 如果 `allowedTools` 已经包含 `Skill` 或 `Skill(name)`，SDK 不会重复添加。
- **不修改原对象** —— 应用技能默认值时会构建新列表；原始的 `ClaudeAgentOptions` 不会被修改。
- **显式的 `settingSources` 优先** —— 如果你在 `.skills(...)` 之外还设置了 `.settingSources(...)`，你的值会被保留。
- **名称会被校验**（0.1.22）—— 列出的每个名称必须是技能 SKILL.md 中的 `name` / 目录名，或者 `plugin:skill`。规则分隔符（圆括号、逗号）、控制字符、通配符（`"*"`、`"pdf:*"`）、前导的 `/` 以及首尾空白，都会在 `connect()` 时抛出 `IllegalArgumentException`。**破坏性变更**：`skills(List.of("*"))` 和 `skills(List.of("plugin:*"))` 以前会构建通配符规则，现在会抛异常 —— 请改用 `.skillsAll()`。参见 [Skills → 名称校验](./feature-skills.md#名称校验0122)。
- **它是上下文过滤器，不是沙箱** —— 未列出的技能不会出现在模型的列表中，也不能通过 `Skill` 工具调用，但它们的文件仍在磁盘上；拥有 `Read`/`Bash` 的会话仍可以直接访问 `.claude/skills/**`。

### sandbox()

配置 bash 命令的沙箱。

启用后，命令会在限制文件系统与网络访问的沙箱环境中执行。工具层面的文件系统和网络限制仍通过权限规则配置（文件系统用 `Read`/`Edit`，网络用 `WebFetch`）；下文的 `network` 设置配置的是沙箱自身针对沙箱化 bash 命令的网络隔离。设置沙箱还会改变 `settings()` 的传递方式；参见 [settings()](#settings)。

```java
// Minimal: just enable sandboxing.
.sandbox(new SandboxSettings(true))
```

若需要更细粒度的控制，请提供完整的 record：

```java
SandboxNetworkConfig network = new SandboxNetworkConfig(
    List.of("api.example.com", "*.npmjs.org"),  // allowedDomains
    List.of("malicious.example.com"),           // deniedDomains (always blocked)
    /* allowManagedDomainsOnly */ false,
    List.of("/tmp/ssh-agent.sock"),             // allowUnixSockets
    /* allowAllUnixSockets */ false,
    /* allowLocalBinding */ true,
    List.of("com.apple.PowerManagement.control"),  // allowMachLookup (macOS only)
    /* httpProxyPort */ null,
    /* socksProxyPort */ null);

SandboxSettings sandbox = new SandboxSettings(
    /* enabled */ true,
    /* autoAllowBashIfSandboxed */ true,
    /* excludedCommands */ List.of("git"),
    /* allowUnsandboxedCommands */ null,
    network,
    /* ignoreViolations */ null,
    /* enableWeakerNestedSandbox */ false);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sandbox(sandbox)
    .build();
```

`SandboxNetworkConfig` 字段：

- `allowedDomains` —— 沙箱进程可以访问的域名。
- `deniedDomains` —— 始终阻止的覆盖项；拒绝优先于允许。
- `allowManagedDomainsOnly` —— 在受管设置中为 `true` 时，只尊重受管设置中的 `allowedDomains`。
- `allowMachLookup` —— 仅限 macOS 的 XPC/Mach 服务名；支持结尾通配符。
- `allowUnixSockets`、`allowAllUnixSockets`、`allowLocalBinding`、`httpProxyPort`、`socksProxyPort` —— 原有字段。

对于不需要域名白名单或 Mach 查找字段的调用方，保留了向后兼容的 5 参数构造函数 `(allowUnixSockets, allowAllUnixSockets, allowLocalBinding, httpProxyPort, socksProxyPort)` —— 这些字段默认为 `null`。

### plugins()

从本地目录加载 Claude Code 插件。

```java
.plugins(List.of(
    ClaudeAgentOptions.SdkPluginConfig.local("/path/to/my-plugin")
))
```

每个 `local` 插件都会变成 `--plugin-dir <path>`。参见[插件系统](./feature-plugin-system.md)。

### outputFormat()

结构化输出格式（Messages API 风格）。

```java
.outputFormat(Map.of(
    "type", "json_schema",
    "schema", Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string"),
            "age", Map.of("type", "integer")
        ),
        "required", List.of("name")
    )
))
```

### enableFileCheckpointing()

启用文件检查点以支持回退。

```java
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
```

会在 CLI 进程上设置 `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=true`，并允许使用 `ClaudeSDKClient.rewindFiles(userMessageId)`。回放用户消息才能拿到用于回退的 `uuid`。不能与 `sessionStore()` 同时使用。

## 完整示例

### 示例 1：只读的代码分析

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .cwd(Path.of("/path/to/codebase"))
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(10)
    .maxBudgetUsd(0.50)
    .systemPrompt("You are a code analyzer. Only read and analyze code.")
    .build();
```

### 示例 2：交互式开发

```java
var calcServer = ClaudeSDK.createSdkMcpServer("calc", new Calculator());

var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .cwd(Path.of("/project"))
    .allowedTools(List.of(
        "Read", "Write", "Edit", "Grep", "Glob",
        "mcp__calc__add", "mcp__calc__multiply"
    ))
    .mcpServers(Map.of("calc", calcServer))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(50)
    .enableFileCheckpointing(true)
    .systemPrompt("""
        You are a development assistant.
        - Write clean, tested code
        - Follow project conventions
        - Ask before major changes
        """)
    .build();
```

### 示例 3：注重预算的批处理

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Cheapest model
    .maxTurns(1)                // Single turn only
    .maxBudgetUsd(0.10)         // 10 cent limit
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .systemPrompt("Be extremely concise.")
    .build();

for (String item : batchItems) {
    String result = ClaudeSDK.queryForText(item, options);
    processResult(result);
}
```

### 示例 4：带钩子的自定义工具

```java
var tools = new MyCustomTools();
var server = ClaudeSDK.createSdkMcpServer("tools", tools);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__process"))
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Processing: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Started processing"))
                );
            })
        ),
        HookEvent.POST_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Completed processing");
                return CompletableFuture.completedFuture(
                    HookOutput.empty()
                );
            })
        )
    ))
    .build();
```

### 示例 5：恢复会话

```java
// First session
var options1 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
    // Note session ID from messages
}

// Resume later with context
var options2 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-5")
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more about lambdas");
    // Has context from previous session
}
```

### 示例 6：带权限回调的流式传输

```java
var options = ClaudeAgentOptions.builder()
    .canUseTool((toolName, input, context) -> {
        // Custom permission logic
        boolean allowed = checkPermission(toolName, context.path());

        if (allowed) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("Access denied to " + context.path())
            );
        }
    })
    .build();

// Must use streaming mode
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Read sensitive.txt"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

## 最佳实践

### 1. 文件操作务必设置工作目录

```java
// ✅ Good
.cwd(Path.of("/project/root"))

// ❌ Bad: Undefined behavior
// No cwd set, files relative to CLI process directory
```

### 2. 选用合适的模型

```java
// ✅ Good: Match model to task
.model("claude-haiku-4-5")  // Simple tasks
.model("claude-sonnet-5") // Balanced
.model("claude-opus-4-6")   // Complex reasoning

// ❌ Bad: Always using most expensive
.model("claude-opus-4-6")  // For everything!
```

### 3. 设置预算上限

```java
// ✅ Good: Protect against unexpected costs
.maxBudgetUsd(1.0)
.maxTurns(10)

// ❌ Bad: No limits
// Could get expensive!
```

### 4. 妥善配置工具

```java
// ✅ Good: Explicit tool control
.allowedTools(List.of("Read", "Grep"))
.disallowedTools(List.of("Bash"))

// ❌ Bad: All tools allowed by default
// Potential security risk
```

### 5. 使用系统提示词

```java
// ✅ Good: Guide behavior
.systemPrompt("You are a code reviewer. Focus on security.")

// ❌ Bad: No guidance
// Claude may not understand context
```

### 6. 文件操作时启用检查点

```java
// ✅ Good: Enable for safety
.enableFileCheckpointing(true)
.extraArgs(Map.of("replay-user-messages", ""))

// Allows rewinding if mistakes: pass the uuid of a replayed UserMessage
client.rewindFiles(userMessageUuid);
```

### 7. 谨慎处理敏感数据

```java
// ✅ Good: Don't pass secrets in env
.env(Map.of("CONFIG_PATH", "/path/to/config"))

// ❌ Bad: Secrets in environment
.env(Map.of("API_KEY", "secret-123"))  // Logged!
```

## 另见

- [简单查询](./feature-simple-queries.md) —— 在查询中使用选项
- [交互式会话](./feature-interactive-conversations.md) —— 在客户端中使用选项
- [MCP 服务器](./feature-mcp-servers.md) —— 配置 MCP 服务器
- [钩子](./feature-hooks.md) —— 钩子配置
- [权限](./feature-permissions.md) —— 权限系统
