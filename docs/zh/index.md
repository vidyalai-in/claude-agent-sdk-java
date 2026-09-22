# Claude Agent SDK for Java - 技术文档

[English](../README.md) · **简体中文** · [日本語](../ja/index.md) · [한국어](../ko/index.md) · [Português](../pt/index.md) · [Español](../es/index.md)

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../README.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。详见 [docs/TRANSLATIONS.md](../TRANSLATIONS.md)。

欢迎阅读 Claude Agent SDK for Java 的技术文档。本文档全面介绍了该 SDK 的架构、功能与用法。

## 概览

Claude Agent SDK for Java 是一个将 Claude AI 能力集成到 Java 应用中的完整库。它提供了类型安全的
现代 Java API 用于与 Claude Code CLI 交互，既支持简单的一次性查询，也支持复杂的多轮会话。

**核心亮点：**
- 🎯 **类型安全的 API**：使用密封接口与 record，因此在 Java 21+ 的使用方代码中可以进行穷尽式模式匹配
- ⚡ **虚拟线程**：在 Java 21+ 上后台任务运行于 Project Loom 虚拟线程，在 17-20 上则运行于守护平台线程
- 🔧 **灵活的架构**：同时支持无状态查询与有状态会话
- 🛠️ **自定义工具**：使用 MCP（Model Context Protocol）创建自定义工具
- 🔌 **插件系统**：面向自定义功能的可扩展架构
- 🎨 **Builder 模式**：流畅的配置 API

## 文档索引

### 架构与设计
- **[架构概览](./architecture.md)** —— 系统架构、设计模式与内部结构
  - 高层架构图
  - 核心组件（API 层、配置、协议、传输层）
  - 设计模式（密封接口、builder、facade、虚拟线程）
  - 数据流图与并发模型
  - 类型系统层次结构与依赖关系

### 核心功能
- **[简单查询](./feature-simple-queries.md)** —— 使用 ClaudeSDK facade 进行一次性查询
  - 基础用法示例
  - 查询方法总览
  - 配置选项
  - 消息处理模式
  - 最佳实践

- **[交互式会话](./feature-interactive-conversations.md)** —— 使用 ClaudeSDKClient 进行多轮会话
  - 连接管理
  - 发送与接收消息
  - 控制方法
  - 会话管理
  - 线程安全
  - 完整示例

- **[配置选项](./feature-configuration-options.md)** —— ClaudeAgentOptions builder 完全指南
  - 全部 30 多个配置选项
  - 工具配置
  - 权限设置
  - 模型配置
  - 环境变量
  - 钩子与回调
  - 常见模式的完整示例

- **[消息类型](./feature-message-types.md)** —— 理解消息类型系统
  - UserMessage、AssistantMessage、SystemMessage、ResultMessage、StreamEvent、RateLimitEvent
  - 任务生命周期消息（TaskStartedMessage、TaskProgressMessage、TaskNotificationMessage、TaskUpdatedMessage）
  - HookEventMessage（启用 `includeHookEvents` 时）
  - ResultMessage 上的 DeferredToolUse；`apiErrorStatus` HTTP 状态字段
  - ConversationResetMessage —— 会话在进行中被替换（例如 `/clear`）
  - 消息来源 —— 区分自己的轮次与会话注入的轮次
  - 内容块（Text、Thinking、ToolUse、ToolResult）
  - 模式匹配
  - 示例与最佳实践

- **[MCP 服务器](./feature-mcp-servers.md)** —— 使用 Model Context Protocol 创建自定义工具
  - SDK MCP 服务器（进程内）
  - 外部 MCP 服务器（stdio/SSE/HTTP）
  - @Tool 注解用法
  - 以编程方式创建工具
  - 工具 schema，以及针对工具 `inputSchema` 的参数校验
  - 失败语义（工具调用遇到的一切情况都以 `isError` 结果返回）
  - 使用 `ToolCallContext` 取消正在运行的工具
  - 协议细节（版本协商、通知、方法）
  - 通过 `McpMessageHandler` 实现自定义 MCP 处理器
  - 异步执行模式
  - 完整示例（计算器、数据库、API 集成）

- **[Agent 定义](./feature-agents.md)** —— 具备专用提示词、工具和模型的自定义子 agent
  - 内联 agent 定义
  - 基于文件系统的 agent
  - 大型 agent 支持（通过 stdin 传递 260KB+）
  - AgentDefinition API（含 skills、内存作用域与 MCP 服务器字段）
  - 观察子 agent 的输出，以及 `forwardSubagentText`

- **[会话历史](./feature-session-history.md)** —— 从磁盘读取并管理历史 Claude Code 会话
  - 列出所有项目的会话，或按目录筛选
  - 按 ID 查找单个会话（`getSessionInfo`）
  - 读取完整的会话记录
  - 读取子 agent 记录（`listSubagents`、`getSubagentMessages`），
    并归属到派生它们的 Agent `tool_use`
  - 重命名会话（`renameSession`）
  - 为会话打标签以便整理（`tagSession`）
  - 删除会话（`deleteSession`）—— 会级联删除子 agent 记录目录
  - 带 UUID 重映射的会话分叉（`forkSession`）
  - 截断式恢复（`resumeSessionAt` / `resumeDropsTurn`）—— 安全地回退到更早的时点
  - SDKSessionInfo（含 tag、createdAt、可为 null 的 fileSize）与 SessionMessage 类型
  - 基于 offset 的分页与 worktree 支持

- **[Session Store](./feature-session-store.md)** —— 将会话记录镜像到 S3 / Postgres / Redis / 自定义后端
  - `SessionStore` 适配器协议，含同步与异步（`CompletableFuture`）两种变体
  - 通过 `SessionStoreExecutor` 配置虚拟线程执行器
  - 内置 `InMemorySessionStore` 参考适配器与 `filePathToSessionKey` 辅助方法
  - 读取 API：`listSessionsFromStore`、`getSessionInfoFromStore`、`getSessionMessagesFromStore`、`listSubagentsFromStore`、`getSubagentMessagesFromStore`
  - 变更 API：`renameSessionViaStore`、`tagSessionViaStore`、`deleteSessionViaStore`、`forkSessionViaStore`
  - 用于本地→存储回放的 `importSessionToStore`；用于非致命追加失败的 `MirrorErrorMessage`
  - 公开的 `SessionStoreConformance` 测试套件（14 项契约，与测试框架无关）
  - 从存储恢复（子进程获得临时的 `CLAUDE_CONFIG_DIR`）；会话记录镜像批处理器

- **[Skills](./feature-skills.md)** —— 面向主会话的顶层 `skills` 选项
  - 三种模式：`skillsAll()`、`skills(List)`、`skills(List.of())`
  - 自动向 `allowedTools` 注入 `Skill(name)`，并为 `settingSources` 设置默认值
  - 通过 initialize 控制请求在协议层传播
  - 幂等注入；显式设置始终优先
  - Skill 名称校验 —— 阻止 `--allowedTools` 规则注入，拒绝永远无法匹配的名称

- **[W3C Trace Context 传播](./feature-trace-context.md)** —— 跨 SDK 与 CLI 的分布式追踪
  - 尽力而为地将 `TRACEPARENT`/`TRACESTATE` 注入 CLI 子进程
  - 运行时对 OpenTelemetry 零依赖（基于反射）
  - 清理陈旧环境变量、仅含 baggage 的上下文、传播器错误

### 高级功能
- **[扩展思考配置](./feature-thinking-config.md)** —— 控制 Claude 的推理深度
  - ThinkingConfig 类型（Adaptive、Enabled、Disabled）
  - 努力级别（low、medium、high、max）
  - 预算控制与优化
  - 完整使用示例

- **[钩子系统](./feature-hooks.md)** —— 拦截并响应生命周期事件
  - 10 个钩子事件
  - HookMatcher 与 HookOutput
  - PostToolUse 的 `updatedToolOutput`（替换任意工具的输出）与 `updatedMCPToolOutput`
  - `PermissionDecision.DEFER` 与 ResultMessage 上的 `DeferredToolUse`
  - `includeHookEvents` 与 HookEventMessage 消息流
  - 常见用例示例

- **[权限系统](./feature-permissions.md)** —— 自定义权限回调与模式
  - 权限模式
  - 自定义权限回调（仅在 `"ask"` 决策时触发）
  - 遮蔽（shadowing），以及用 `settingSources(List.of())` 保持回调的确定性
  - 增强的 `ToolPermissionContext`（`title`、`displayName`、`description`、`decisionReason`、`blockedPath`）
  - 基于路径、基于时间以及用户确认的示例

- **[流式事件](./feature-streaming-events.md)** —— 实时的部分消息更新
  - 启用流式传输
  - 处理流事件
  - UI 集成示例

- **[传输层](./feature-transport-layer.md)** —— 自定义传输实现
  - Transport 接口
  - 默认实现
  - 自定义传输示例
  - Windows 批处理脚本的拒绝策略，以及针对 npm `claude.cmd` 部署的显式选择开关

- **[插件系统](./feature-plugin-system.md)** —— 创建和使用插件
  - SdkPluginConfig
  - 用例与示例

### API 参考
- **[ClaudeSDK](./api-claude-sdk.md)** —— 用于简单查询的静态 facade
  - 查询方法
  - 客户端工厂方法
  - MCP 服务器工厂方法
  - 便捷方法

- **[ClaudeSDKClient](./api-claude-sdk-client.md)** —— 用于会话的交互式客户端
  - 连接方法
  - 发送/接收消息
  - 控制方法
  - 线程安全说明

- **[ClaudeAgentOptions](./api-claude-agent-options.md)** —— 配置构建器
  - 全部配置选项
  - Builder 方法

- **[消息类型](./api-message-types.md)** —— 完整的消息类型层次结构
  - 所有消息类型与内容块
  - 字段文档

- **[异常类型](./api-exceptions.md)** —— 错误处理与异常
  - 异常层次结构
  - `ResultException` 与终止性错误结果的负载
  - 每种异常实际出现的位置
  - 错误处理示例

### 项目资源
- **[CHANGELOG](../CHANGELOG.md)** —— 版本历史与发布说明（仅英文）
- **[Python SDK 对等性](../PYTHON_SDK_PARITY.md)** —— 与 Python SDK 的功能对比（仅英文）
- **[翻译说明](../TRANSLATIONS.md)** —— 翻译范围、同步策略与贡献方式（英文）

## 项目结构

这是一个多模块 Maven 项目：

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to Maven Central; mirrored to GitHub Packages)
│   ├── src/main/java/in/vidyalai/claude/sdk/
│   │   ├── ClaudeSDK.java              # Main facade
│   │   ├── ClaudeSDKClient.java        # Interactive client
│   │   ├── ClaudeAgentOptions.java     # Configuration builder
│   │   ├── exceptions/                 # Exception types
│   │   ├── transport/                  # Transport layer
│   │   ├── internal/                   # Internal implementation
│   │   ├── mcp/                        # MCP server support
│   │   └── types/                      # Type definitions
│   └── src/test/java/                  # SDK tests
└── examples/         # Usage examples (separate module)
    └── src/main/java/examples/
        ├── QuickStart.java
        ├── MultiTurnConversation.java
        ├── McpServer.java
        └── ... (15+ examples)
```

## 快速上手

### 前置条件
- Java 17 或更高版本（在 21+ 上会自动使用虚拟线程）
- Maven 3.6+
- 单独安装的 Claude Code CLI

### 安装

添加到你的 `pom.xml` —— 无需配置仓库或身份认证：

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

为了兼容已经指向 GitHub Packages 的使用者，各版本也会镜像发布到那里。即使制品是公开的，该路径
仍然需要个人访问令牌，因此除非你有特殊理由，否则请优先使用 Maven Central —— 仓库与身份认证的
配置方式参见[根 README](./README.md#备选方案github-packages)。

### Hello World

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;

// Simple query
List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        System.out.println(assistant.getTextContent());
    }
}
```

### 交互式会话

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Hello!");

    for (var msg : client.receiveResponse()) {
        // Process messages
    }

    client.sendMessage("Tell me more");
    for (var msg : client.receiveResponse()) {
        // Process follow-up
    }
}
```

## 示例

本 SDK 包含 15 个以上的完整示例，涵盖：
- 基础查询与会话
- 自定义 MCP 工具
- 权限回调
- 钩子系统
- 流式事件
- 错误处理
- 高级功能（检查点、沙箱、输出格式）
- 以及更多……

请参见仓库中的 `examples/` 目录。

## 如何使用本文档

1. **新用户**：从本 README 开始，了解安装与快速上手
2. **理解架构**：阅读[架构概览](./architecture.md)
3. **简单用例**：跟随[简单查询](./feature-simple-queries.md)
4. **自定义工具**：学习 [MCP 服务器](./feature-mcp-servers.md)
5. **高级功能**：按需查阅其他功能指南

## 文档状态

### ✅ 已完成 —— 全部核心文档
- 含快速上手与概览的主 README
- 架构概览（内容完整，配有图示；SessionStore 子系统；控制请求失败处理）
- 全部功能指南：
  - 简单查询
  - 交互式会话
  - 配置选项（包括 `sessionStore` 与 `loadTimeoutMs`）
  - 消息类型（任务消息、服务端工具块、MirrorErrorMessage）
  - MCP 服务器（含 ToolAnnotations、工具标题、状态类型、输入校验、失败语义、取消与自定义处理器）
  - Agent 定义
  - 扩展思考配置（含 `ThinkingDisplay`）
  - 钩子系统（含 agentId/agentType 字段）
  - 权限系统
  - 流式事件
  - 传输层（含 `--session-mirror`、`--thinking-display`，移除 `--debug-to-stderr`）
  - 插件系统
  - 会话历史（listSessions / getSessionMessages）
  - Session Store（将会话记录镜像到 S3/Postgres/Redis/自定义后端）
- 完整的 API 参考（5 篇文档）：
  - ClaudeSDK（包含会话历史方法）
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - 消息类型
  - 异常类型
- 代码示例（examples/ 目录中 20 多个示例）
- Python SDK 对等性文档

## 为文档做贡献

新增文档时：
1. 遵循既有的结构与格式
2. 提供可以运行的代码示例
3. 对照实际实现验证所有代码示例
4. 添加指向相关文档的交叉引用
5. 在文档索引中登记新文档
6. 遵循文档规范：
   - 清晰的目录
   - 实用的示例
   - 最佳实践章节
   - 带链接的"另见"章节

## 文档原则

本项目的所有文档都遵循以下原则：
1. **准确**：所有代码示例必须可运行，并与实际 API 一致
2. **完整**：覆盖所有主要用例与场景
3. **清晰**：使用明确的语言，解释复杂概念
4. **示例**：提供实用、可运行的代码示例
5. **交叉引用**：链接到相关文档
6. **最佳实践**：包含推荐模式与反模式
7. **保持最新**：与代码变更同步

## 支持与资源

- **GitHub 仓库**：https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**：在 GitHub Issues 上报告缺陷与功能需求
- **示例代码**：参见仓库中的 `examples/` 目录
- **MCP 规范**：https://spec.modelcontextprotocol.io/
- **许可证**：MIT License
- **Python SDK**：可作对比，参见 https://github.com/anthropics/anthropic-sdk-python
- **Claude Agent Python SDK 文档**：https://platform.claude.com/docs/en/agent-sdk/python

## 贡献

欢迎贡献！请参阅仓库中的贡献指南。

## 版本

当前发布版本以 [Maven Central](https://central.sonatype.com/artifact/in.vidyalai/claude-agent-sdk-java)
上列出的为准 —— 这里刻意不再重复，因为手工维护的版本号副本曾经落后了四个发布版本。

版本历史与发布说明请参见 [CHANGELOG.md](../CHANGELOG.md)（仅英文）。
