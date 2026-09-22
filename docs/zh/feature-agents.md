# Agent 定义

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-agents.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

自定义 agent 让你可以定义带有各自系统提示词、工具和模型的专用子 agent。Claude 可以在会话过程中
派生这些 agent 来处理特定任务。

## 目录
- [概览](#概览)
- [AgentDefinition record](#agentdefinition-record)
- [内联 agent 定义](#内联-agent-定义)
- [基于文件系统的 agent](#基于文件系统的-agent)
- [大型 agent 定义](#大型-agent-定义)
- [观察子 agent 的输出](#观察子-agent-的输出)
- [示例](#示例)

## 概览

agent 是 Claude 在会话中可以使用的具名子 agent。每个 agent 具有：

- **description** —— 该 agent 做什么（在 Claude 判断该用哪个 agent 时展示给它）
- **系统提示词** —— 该 agent 的行为指令
- **工具** —— 该 agent 被允许使用的工具列表（为 null 时继承父级）
- **模型** —— 该 agent 运行所用的 Claude 模型变体（为 null 时继承父级）
- **Skills** —— 该 agent 可用的 skill 名称列表（为 null 时继承父级）
- **Memory** —— 该 agent 的内存作用域（为 null 时继承父级）
- **MCP 服务器** —— 该 agent 可使用的 MCP 服务器引用（为 null 时继承父级）

agent 通过 `ClaudeAgentOptions.agents()` 以 `Map<String, AgentDefinition>` 的形式注册，其中键是
agent 的名称。

## AgentDefinition record

```java
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.MemoryScope;

// Full constructor
AgentDefinition agent = new AgentDefinition(
    "Reviews code for quality and bugs",   // description
    "You are a code review expert...",     // system prompt
    List.of("Read", "Grep"),               // tools (null = inherit)
    "sonnet",                              // model (null = inherit)
    List.of("commit", "review"),           // skills (null = inherit)
    MemoryScope.PROJECT,                   // memory scope (null = inherit)
    List.of("my-mcp-server")              // MCP servers (null = inherit)
);

// Shorthand: description + prompt only (all other fields inherit from parent)
AgentDefinition simple = new AgentDefinition(
    "Summarizes text",
    "You are a concise summarizer."
);

// Backwards-compatible: description, prompt, tools, model
AgentDefinition compat = new AgentDefinition(
    "Reviews code",
    "You are a code reviewer.",
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);
```

**字段：**

| 字段 | 类型 | 说明 |
|-------|------|-------------|
| `description` | `String` | 展示给 Claude 的可读描述 |
| `prompt` | `String` | 定义该 agent 行为的系统提示词 |
| `tools` | `List<String>`（可为 null） | 允许使用的工具名称；为 null 时继承父级的工具 |
| `disallowedTools` | `List<String>`（可为 null） | 该 agent 不能使用的工具；为 null 表示没有 |
| `model` | `String`（可为 null） | 模型别名（"sonnet"、"opus"、"haiku"、"inherit"）或完整模型 ID |
| `skills` | `List<String>`（可为 null） | 该 agent 可用的 skill 名称；为 null 时继承 |
| `memory` | `MemoryScope`（可为 null） | 内存作用域；为 null 时继承父级 |
| `mcpServers` | `List<Object>`（可为 null） | MCP 服务器引用（名称或内联配置）；为 null 时继承 |
| `initialPrompt` | `String`（可为 null） | agent 启动时发送的初始提示词 |
| `maxTurns` | `Integer`（可为 null） | 该 agent 的最大轮数；为 null 表示不限 |
| `background` | `Boolean`（可为 null） | 在后台运行该 agent |
| `effort` | `String`（可为 null） | 努力级别：`"low"`、`"medium"`、`"high"`、`"xhigh"`、`"max"`。`"xhigh"` 是 Opus 4.7 专有的，在其他模型上会回退为 `"high"`。另请参见 [`EffortLevel`](feature-configuration-options.md#effortlevel-枚举) 枚举。 |
| `permissionMode` | `String`（可为 null） | 该 agent 的权限模式 |

**model 字段：** `model` 字段接受短别名（`"sonnet"`、`"opus"`、`"haiku"`、`"inherit"`）或完整的
模型 ID（例如 `"claude-sonnet-4-5"`）。

### MemoryScope 枚举

控制 agent 在哪个内存作用域中运行：

```java
import in.vidyalai.claude.sdk.types.config.MemoryScope;

MemoryScope.USER     // "user" — user-level memory
MemoryScope.PROJECT  // "project" — project-scoped memory
MemoryScope.LOCAL    // "local" — local/session-scoped memory
```

## 内联 agent 定义

通过 `ClaudeAgentOptions` 以编程方式注册 agent：

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;

AgentDefinition codeReviewer = new AgentDefinition(
    "Reviews code for best practices and potential issues",
    """
    You are a code reviewer. Analyze code for bugs, performance issues,
    security vulnerabilities, and adherence to best practices.
    Provide constructive feedback.
    """,
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("code-reviewer", codeReviewer))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Use the code-reviewer agent to review MyClass.java");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 多个 agent

你可以在同一个会话中定义多个 agent：

```java
AgentDefinition analyzer = new AgentDefinition(
    "Analyzes code structure and patterns",
    "You are a code analyzer. Examine code structure, patterns, and architecture.",
    List.of("Read", "Grep", "Glob"),
    null  // inherit model from parent
);

AgentDefinition tester = new AgentDefinition(
    "Creates and runs tests",
    "You are a testing expert. Write comprehensive tests and ensure code quality.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "analyzer", analyzer,
        "tester", tester
    ))
    .build();
```

## 基于文件系统的 agent

agent 也可以借助 `settingSources` 从磁盘上的 markdown 文件加载。把 agent 定义文件放在项目目录的
`.claude/agents/` 下：

```
.claude/
  agents/
    code-reviewer.md
    test-writer.md
```

然后启用文件系统 agent 加载：

```java
import in.vidyalai.claude.sdk.types.config.SettingSource;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .settingSources(List.of(SettingSource.PROJECT))
    .cwd(Path.of("/path/to/project"))
    .build();

try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
    client.connect();
    // Agents defined in .claude/agents/*.md are now available
}
```

你可以通过检查 `SystemMessage` 的 init 事件来确认加载了哪些 agent：

```java
for (Message msg : client.receiveResponse()) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        List<String> agents = system.get("agents");
        System.out.println("Loaded agents: " + agents);
    }
}
```

## 大型 agent 定义

agent 是通过 SDK 控制协议的 initialize 请求（经由 stdin）发送的，而不是作为 CLI 参数。这意味着
agent 定义**没有大小限制** —— 你可以放心传递 260KB 以上的 agent 数据。

这一行为与 TypeScript 和 Python SDK 的实现一致，并且避开了各平台特有的命令行参数长度限制
（ARG_MAX）。

```java
// Large agents work reliably via stdin
Map<String, AgentDefinition> agents = new HashMap<>();
for (int i = 0; i < 20; i++) {
    String largePrompt = "You are agent #" + i + ". " + "x".repeat(13 * 1024);
    agents.put("agent-" + i, new AgentDefinition("Agent " + i, largePrompt));
}

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(agents)
    .maxTurns(1)
    .build();

// Works for both query() and createClient()
for (Message msg : ClaudeSDK.query("List available agents", options)) {
    // ...
}
```

## 观察子 agent 的输出

子 agent 会运行它自己的会话，其中只有一部分会抵达父级消息流。抵达的内容表现为普通的
`AssistantMessage` / `UserMessage` 对象，它们的 `parentToolUseId` 就是派生该子 agent 的那个
Agent `tool_use` 块的 id —— 正是这个字段让你能把子 agent 的消息与主会话的区分开来，并在同时运行
多个子 agent 时判断消息属于哪一个。

默认情况下只转发子 agent 的 `tool_use` 与 `tool_result` 块：足以表明它在推进，但不足以呈现它说了
什么。设置 `forwardSubagentText(true)` 可以让它的文本块与思考块也以同样的方式被转发：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .forwardSubagentText(true)
    .agents(Map.of("greeter", greeter))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof AssistantMessage assistant && assistant.parentToolUseId() != null) {
        // From a subagent — attribute it to the spawning Agent tool_use id.
        System.out.println("[" + assistant.parentToolUseId() + "] " + assistant.getTextContent());
    }
}
```

该选项是通过 `initialize` 控制请求发送的，而不是 CLI 标志，并且只在启用时才发送，因此不会影响旧版
CLI。

读取*已结束*子 agent 的完整会话记录是另一条路径 —— 关于 `listSubagents()` 与
`getSubagentMessages()`，请参见[会话历史](./feature-session-history.md)；它们的结果同样带有
`parentToolUseId`，并为嵌套子 agent 额外提供 `parentAgentId`。

## 示例

完整可运行的演示请参见示例文件：

- [`AgentsExample.java`](../../examples/src/main/java/examples/AgentsExample.java) —— 代码评审者、文档撰写者与多 agent
- [`FilesystemAgentsExample.java`](../../examples/src/main/java/examples/FilesystemAgentsExample.java) —— 从 `.claude/agents/` 文件加载 agent
- [`LargeAgentsExample.java`](../../examples/src/main/java/examples/LargeAgentsExample.java) —— 260KB 以上 agent 负载的压力测试
- [`ForwardSubagentTextExample.java`](../../examples/src/main/java/examples/ForwardSubagentTextExample.java) —— 同一次运行在关闭与开启子 agent 文本转发时的对比

## 另见

- [配置选项](./feature-configuration-options.md) —— `agents` 与 `settingSources` 选项
- [交互式会话](./feature-interactive-conversations.md) —— 在多轮会话中使用 agent
