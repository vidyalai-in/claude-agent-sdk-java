# ClaudeAgentOptions API 参考

配置选项构建器。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../api-claude-agent-options.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 类概览

```java
public final class ClaudeAgentOptions
```

使用 builder 模式的不可变配置对象。

## 创建选项

```java
// Builder
ClaudeAgentOptions.builder()
    .option1(value)
    .build()

// Defaults
ClaudeAgentOptions.defaults()

// From existing
options.toBuilder()
    .modifiedOption(newValue)
    .build()
```

## 全部配置选项

### 工具配置
- `tools(Object)` —— 工具列表或预设
- `allowedTools(List<String>)` —— 白名单
- `disallowedTools(List<String>)` —— 黑名单

### 系统提示词
- `systemPrompt(String)` —— 自定义系统提示词（`--system-prompt`）
- `systemPrompt(SystemPromptPreset)` —— Claude Code 预设，可选带上 `append`、`excludeDynamicSections` 与 `snapshot`
- `systemPrompt(SystemPromptCustom)` —— 还能设置 `snapshot` 的自定义提示词；`SystemPromptCustom.of(prompt, snapshot)`
- `systemPrompt(SystemPromptFile)` —— 从文件加载的提示词（`--system-prompt-file`）

`snapshot`（仅限预设形式和自定义形式）控制会话是保留其在第一次请求时记录的提示词（`true`），还是每次请求都重新构建（`false`）；它以 `systemPromptSnapshot` 的形式随 `initialize` 请求发送，为 `null` 时省略。需要 Claude Code 2.1.257+。参见[配置选项 → 快照](./feature-configuration-options.md#快照snapshot)。

### MCP 服务器
- `mcpServers(Map<String, McpServerConfig>)` —— 服务器配置，序列化为 `{"mcpServers": {...}}` 后传给 `--mcp-config`
- `mcpServers(Path)` / `mcpServersPath(Path)` —— MCP 配置文件，原样传给 `--mcp-config`
- `mcpServersJson(String)` —— 内联的 MCP 配置 JSON，原样传给 `--mcp-config`
- `strictMcpConfig(boolean)` —— 为 `true` 时，CLI 会忽略项目的 `.mcp.json`、用户/全局设置以及插件提供的 MCP 服务器 —— 只加载通过 `mcpServers(...)` 传入的服务器。对应 `--strict-mcp-config`。

### 权限
- `permissionMode(PermissionMode)` —— 权限模式
- `permissionPromptToolName(String)` —— 用于提示的工具
- `canUseTool(CanUseTool)` —— 自定义回调。**仅在 `"ask"` 决策时触发** —— 对于已经被 `allowedTools`、`permissionMode` 或 `permissions.allow` 规则放行的工具调用不会触发。若要不论决策如何都拦截每一次调用，请使用 `PreToolUse` 钩子。如果该回调明显被整工具级的 `allowedTools` 条目或 `BYPASS_PERMISSIONS` 遮蔽，SDK 会在连接时记录一条提示性的 `WARNING`；参见[遮蔽警告](feature-permissions.md#遮蔽警告)。

### 会话
- `continueConversation(boolean)` —— 继续上一个会话
- `resume(String)` —— 恢复指定会话
- `forkSession(boolean)` —— 分叉所恢复的会话
- `resumeSessionAt(String)` —— 截断式恢复：所恢复的会话只加载到该会话记录条目 UUID（含）为止，从更早的时点分叉。需与 `resume` 配合使用，通常还要配合 `forkSession`。接受任意会话记录条目 UUID —— 通常是实时观察到的 `AssistantMessage.uuid()`，或来自 `ClaudeSDK.getSessionMessages(...)` 的 `SessionMessage.uuid()`。以 `--resume-session-at=<value>` 形式传出。参见[截断式恢复](./feature-session-history.md#截断式恢复)。
- `resumeDropsTurn(String)` —— 与 `resumeSessionAt` 一起使用：本次截断打算丢弃的那一轮用户提示词的 UUID。CLI 随后会在加载时校验分叉点之后的*每一个*条目都属于该轮次，否则拒绝 —— 这样一来，会话在轮次中途吸收的排队用户消息或任务通知就绝不会被悄悄丢掉。被拒绝时会抛出异常，其消息中含有 `Resume rejected by --resume-drops-turn:`；应把它视为确定性的结果，直接改为普通恢复，而不是重试。只要非 null 就会转发，因此空字符串也会抵达 CLI，并在那里作为格式错误被拒绝，而不是悄悄使该保护失效。以 `--resume-drops-turn=<value>` 形式传出。
- `sessionStore(SessionStore)` —— 将会话记录镜像到外部存储，并可从中恢复（参见 [Session Store](./feature-session-store.md)）。设置后，SDK 会向 CLI 传递 `--session-mirror`，并把 `transcript_mirror` 帧路由到 `store.appendAsync(...)`。预检校验会拒绝在不支持 `listSessions()` 的情况下使用 `continueConversation + sessionStore`，以及 `sessionStore + enableFileCheckpointing`。
- `sessionStoreFlush(SessionStoreFlushMode)` —— 会话记录镜像条目何时刷写到 `sessionStore`。`BATCHED`（默认）会合并条目，并在每轮结束时、或缓冲区超过 500 条 / 1 MiB 时刷写一次；`EAGER` 会在每一帧之后调度一次后台刷写，以实现接近实时的投递。未设置 `sessionStore` 时该项被忽略。参见[刷写模式](./feature-session-store.md#刷写模式batched-与-eager)。
- `loadTimeoutMs(long)` —— 在恢复物化期间，`store.loadAsync()` / `listSubkeysAsync()` 每次调用的超时（毫秒，默认 `60_000`）。取值 `0` 表示立即超时；取很大的值相当于禁用超时。

### 限制
- `maxTurns(Integer)` —— 最大会话轮数
- `maxBudgetUsd(Double)` —— 最大花费（美元）
- `maxBufferSize(Integer)` —— stdout 缓冲区最大字节数
- `thinking(ThinkingConfig)` —— 扩展思考配置
- `effort(String)` —— 思考深度级别（`"low"`、`"medium"`、`"high"`、`"xhigh"`、`"max"`）。`"xhigh"` 是 Opus 4.7 专有的，在其他模型上会回退为 `"high"`。
- `effort(EffortLevel)` —— 同上，但使用 [`EffortLevel`](feature-configuration-options.md#effortlevel-枚举) 枚举（`LOW`、`MEDIUM`、`HIGH`、`XHIGH`、`MAX`）以获得类型安全。传入 `null` 可清除。
- `maxThinkingTokens(Integer)` —— **已废弃**，请改用 `thinking()`。在较新的模型上，该值只被视为开/关（0 = 禁用，其他任何值 = 自适应）
- `maxMsgQSize(Integer)` —— 消息队列最大长度

### 模型
- `model(String)` —— AI 模型名称
- `fallbackModel(String)` —— 回退模型
- `betas(List<SdkBeta>)` —— Beta 功能

### 环境
- `cwd(Path)` —— 工作目录
- `cliPath(Path)` —— 自定义 CLI 路径（Windows 的 `.bat`/`.cmd` 路径会被拒绝；见下文）
- `allowUnsafeWindowsBatchCli(boolean)` —— 放弃对 Windows 批处理脚本的拒绝策略；同时还要求设置 `-Djdk.lang.Process.allowAmbiguousCommands=false`，并会拒绝任何参数中的 cmd.exe 元字符（默认 `false`）
- `settings(String)` —— 设置文件路径或内联 JSON 字符串；原样传给 `--settings`，设置了沙箱时则与 `sandbox` 合并为一个 JSON 字符串
- `addDirs(List<Path>)` —— 额外的上下文目录
- `env(Map<String, String>)` —— 环境变量，合并到继承的环境之上。SDK 还会设置 `CLAUDE_CODE_ENTRYPOINT`（可覆盖）、`CLAUDE_AGENT_SDK_VERSION`，以及 `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`（除非已经出现该变量）；参见 [env()](./feature-configuration-options.md#env)
- `extraArgs(Map<String, String>)` —— 额外的 CLI 标志；键不带前导 `--`，值为空白时表示不带值的标志

### 回调
- `stderrCallback(Consumer<String>)` —— stderr 回调

### 钩子
- `hooks(Map<HookEvent, List<HookMatcher>>)` —— 钩子回调
- `includeHookEvents(boolean)` —— 为 `true` 时，CLI 会把钩子的生命周期事件（`PreToolUse`、`PostToolUse`、`Stop` 等）作为 `HookEventMessage` 对象推送到消息流中。对应 `--include-hook-events`。参见[钩子 → 消息流上的钩子生命周期事件](./feature-hooks.md#消息流上的钩子生命周期事件)。

### 高级
- `user(String)` —— 用户身份
- `includePartialMessages(boolean)` —— 每个 API 流事件输出一条 `StreamEvent`（`--include-partial-messages`）
- `verbatimPrompts(boolean)` —— 把 SDK 发送的每条用户消息都标记为 `client_composed`，让 Claude Code 原样投递：不做 `@path` 展开，也不分派斜杠命令。开启期间会覆盖单条消息上的取值。需要 Claude Code 2.1.248+（在更旧的 CLI 上 SDK 会发出警告）。参见 [verbatimPrompts()](./feature-configuration-options.md#verbatimprompts)
- `forwardSubagentText(boolean)` —— 为 `true` 时，子 agent 的文本块与思考块会与始终转发的 `tool_use` / `tool_result` 块一起被转发到消息流中。通过 `initialize` 控制请求发送（没有对应的 CLI 标志）。参见 [Agent → 观察子 agent 的输出](./feature-agents.md#观察子-agent-的输出)。
- `agents(Map<String, AgentDefinition>)` —— 自定义 agent
- `settingSources(List<SettingSource>)` —— 设置来源（空列表会通过 `--setting-sources=` 禁用所有来源；不设置则保留 CLI 默认值）
- `skills(List<String>)` —— Skills 白名单（会自动向 `allowedTools` 注入 `Skill(name)`，并把 `settingSources` 默认设为 user/project）。名称必须完全精确 —— 通配符、规则分隔符以及首尾空白都会在 `connect()` 时抛出 `IllegalArgumentException`
- `skillsAll()` —— 启用所有被发现的 skill（自动注入裸的 `Skill` 工具）
- `sandbox(SandboxSettings)` —— bash 命令的沙箱配置；其中的 `network` 键配置沙箱自身的网络隔离（工具层面的限制仍放在权限规则中）
- `plugins(List<SdkPluginConfig>)` —— 本地插件目录，`SdkPluginConfig.local(path)` → `--plugin-dir`
- `outputFormat(Map<String, Object>)` —— 输出格式
- `enableFileCheckpointing(boolean)` —— 为 `rewindFiles()` 启用文件检查点；不能与 `sessionStore` 同时使用

## 另见
- [配置选项指南](./feature-configuration-options.md)
