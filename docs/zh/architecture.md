# 架构概览

本文档全面介绍 Claude Agent SDK for Java 的架构、设计模式与内部结构。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../architecture.md)；若两者不一致，以英文为准。代码块与结构图保持与英文原文完全一致，未作翻译。

## 目录
- [高层架构](#高层架构)
- [核心组件](#核心组件)
- [设计模式](#设计模式)
- [数据流](#数据流)
- [并发模型](#并发模型)
- [类型系统](#类型系统)
- [依赖](#依赖)

## 高层架构

SDK 采用分层架构，各层职责清晰分离：

```
┌─────────────────────────────────────────────────────────┐
│           Public API Layer                              │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   ClaudeSDK      │    │  ClaudeSDKClient       │   │
│  │   (Facade)       │    │  (Interactive Client)  │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                        │                   │
│            └────────────────────────┘                   │
│                     │                                   │
├─────────────────────┼───────────────────────────────────┤
│                     ▼                                   │
│           Configuration Layer                           │
│  ┌─────────────────────────────────────────────────┐  │
│  │       ClaudeAgentOptions (Builder)              │  │
│  └─────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│           Protocol & Control Layer                      │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   QueryHandler   │◄───┤   MessageParser        │   │
│  │  (Control Proto) │    │   (JSON Parsing)       │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           Transport Layer                               │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Transport Interface                            │  │
│  │  └─ SubprocessCLITransport (default impl)      │  │
│  └─────────────────────────────────────────────────┘  │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           External Process                              │
│  ┌─────────────────────────────────────────────────┐  │
│  │         Claude Code CLI Process                 │  │
│  │         (stdin/stdout communication)            │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 核心组件

### 1. 公开 API 层

#### ClaudeSDK（facade）
- **用途**：用于简单、无状态查询的静态 facade
- **适用场景**：一次性提问、批处理、发完即走的操作
- **关键方法**：
  - `query(String prompt)` —— 使用默认值的简单查询
  - `query(String prompt, ClaudeAgentOptions options)` —— 带自定义选项的查询
  - `query(Iterator<Map> stream, ClaudeAgentOptions options)` —— 流式查询
  - `queryForText()` / `queryForResult()` —— 便捷方法
  - `createClient()` —— ClaudeSDKClient 的工厂方法
  - `createSdkMcpServer()` —— MCP 服务器的工厂方法

**设计模式**：Facade + Factory

#### ClaudeSDKClient
- **用途**：用于多轮会话的交互式、有状态客户端
- **适用场景**：聊天界面、类 REPL 的交互、长时运行的会话
- **关键方法**：
  - `connect()` —— 建立连接
  - `sendMessage()` / `query()` —— 发送消息
  - `receiveMessages()` / `receiveResponse()` —— 接收消息
  - 控制方法：`interrupt()`、`setModel()`、`setPermissionMode()` 等
- **线程安全**：部分线程安全，并有明确文档化的保证
- **资源管理**：实现 AutoCloseable 以便正确清理

**设计模式**：Builder + 资源管理（try-with-resources）

### 2. 配置层

#### ClaudeAgentOptions
- **用途**：使用 builder 模式的不可变配置对象
- **特性**：
  - 30 多个配置选项
  - 借助枚举与密封接口实现类型安全的 API
  - 流畅的 builder，并提供 `toBuilder()` 以便修改
- **主要配置领域**：
  - 工具：`tools()`、`allowedTools()`、`disallowedTools()`
  - 权限：`permissionMode()`、`canUseTool()`
  - 会话：`continueConversation()`、`resume()`、`forkSession()`、`sessionStore()`、`loadTimeoutMs()`
  - 限制：`maxTurns()`、`maxBudgetUsd()`、`maxThinkingTokens()`
  - 模型：`model()`、`fallbackModel()`、`betas()`
  - 环境：`cwd()`、`env()`、`cliPath()`
  - 钩子：`hooks()`
  - MCP：`mcpServers()`
  - Agent：`agents()`（通过 stdin 的 initialize 请求发送，无大小限制）
  - 高级：`sandbox()`、`outputFormat()`、`checkpointFiles()`

**设计模式**：Builder + 不可变对象

### 3. 协议与控制层

#### QueryHandler
- **用途**：在 Transport 之上管理双向控制协议
- **职责**：
  - 控制请求/响应的路由
  - 钩子回调
  - 工具权限回调
  - 消息流式传输
  - 初始化握手（包含钩子、agent 定义与 excludeDynamicSections）
  - MCP 服务器的生命周期管理
  - **可操作的错误替换**：在读取流的过程中跟踪最近一次错误结果的负载；当 `ProcessException` 跟在一个
    `is_error=true` 的 result 之后时，它会被替换为携带该负载的 `ResultException`，其消息为
    `"Claude Code returned an error result: <text>"`（依次由该 result 的 `errors` 数组、`result`
    文本、非 `success` 的 `subtype`、API 错误状态构造），而不是通用的
    `"Command failed with exit code N"`。遇到任何非 result、非 `session_state_changed` 的流量时重置。
    该异常对象会随合成的 `{"type":"error"}` 帧一同传递，因此消费者迭代器会连同其类型与负载一起
    重新抛出。
- **线程安全**：借助原子操作与同步实现完全线程安全
- **关键特性**：
  - 使用 CompletableFuture 的异步控制协议
  - 请求 ID 的生成与跟踪
  - 大小可配置的消息队列
  - 后台读取线程（在 Java 21+ 上为虚拟线程）
  - 用于异步回调的控制执行器

**设计模式**：异步请求/响应 + Observer（用于钩子）

#### MessageParser
- **用途**：解析来自 CLI 的 JSON 消息并转换为类型化的 Message 对象
- **特性**：
  - 基于 Jackson 的 JSON 解析
  - 支持所有消息类型（user、assistant、system、result、stream_event）
  - 内容块解析（text、thinking、tool_use、tool_result）
  - 错误处理与校验

**设计模式**：Parser + Factory

### 4. 传输层

#### Transport 接口
- **用途**：与 Claude Code 通信的抽象 I/O 层
- **默认实现**：SubprocessCLITransport
- **自定义实现**：支持连接远程的 Claude Code
- **关键方法**：
  - `connect()` —— 建立连接
  - `write(String data)` —— 发送数据
  - `readMessages()` —— 以迭代器形式接收消息
  - `endInput()` —— 关闭输入流
  - `isReady()` —— 检查连接状态
  - `close()` —— 清理资源

**设计模式**：Strategy + Template Method

#### SubprocessCLITransport
- **用途**：使用子进程运行 Claude Code CLI 的默认传输
- **特性**：
  - 管理 CLI 子进程的生命周期
  - stdin/stdout 通信
  - 带可配置上限的缓冲读取
  - 支持 stderr 回调，并逐行隔离异常（抛出异常的用户回调不再会杀死读取循环）
  - 自动清理进程
  - **JVM 关闭钩子**：一个静态的 `ConcurrentHashMap.newKeySet()` 跟踪每个被派生的 `Process`；在类初始化
    时注册的 `Runtime.addShutdownHook` 会对每个仍存活的子进程调用 `destroy()`，从而在父 JVM 先于
    `close()` 退出时不会泄漏游离的 `claude` 子进程。它对应 Python SDK 的 `atexit` 处理器。
- **实现细节**：
  - 使用 ProcessBuilder 管理子进程
  - 专用线程读取 stdout（在 Java 21+ 上为虚拟线程）
  - 使用 BufferedReader 按行解析
  - 使用 Jackson 进行 JSON 序列化/反序列化

**设计模式**：子进程管理 + 缓冲 I/O

### 5. MCP（Model Context Protocol）支持

#### SdkMcpServer
- **用途**：用于自定义工具的进程内 MCP 服务器
- **相对外部服务器的优势**：
  - 没有 IPC 开销（同一进程）
  - 部署更简单
  - 调试更容易
  - 可直接访问应用状态
- **特性**：
  - 工具注册与执行
  - 从 @Tool 注解自动生成 schema
  - 基于 CompletableFuture 的异步执行
  - 服务器信息，以及在 `2025-06-18` / `2024-11-05` 之间进行的 `initialize` 版本协商
  - MCP 协议消息（`initialize`、`ping`、`tools/list`、`tools/call`）
  - 针对每个工具 `inputSchema` 的参数校验，在服务器构造时一次性编译
  - 取消：`notifications/cancelled` 会结清挂起的调用，并向处理函数发出信号
- **消息分类**：带 `id` 的 `method` 是请求，会被应答；不带 `id` 的 `method` 是通知，按 JSON-RPC 的要求
  *绝不*应答 —— 取而代之的是对外层控制请求作确认。没有 `method` 的消息是响应或垃圾，会被忽略：本服务器
  不会向 CLI 发送任何请求，因此以那种方式到来的东西都不该由它来匹配。
- **失败分类**：`tools/call` 可能遇到的一切都是*工具执行错误* —— 一个携带 `isError: true` 的结果 ——
  包括未知工具、不符合 schema 的参数以及抛出异常的处理函数。JSON-RPC 错误则专门留给*模型*既不会造成也
  永远看不到的情形：未实现的方法（`-32601`）、格式错误的 `params`（`-32602`），以及被 CLI 取消的调用
  （`-32800`）。`isError` 结果会作为模型可读并可据以纠正的工具输出送达；而 JSON-RPC 错误表示该请求
  根本无法被处理。
- **失败即关闭的校验**：每个 `inputSchema` 都会在构造时对照其自身方言的元 schema 做检查，未通过的工具
  会被记录并变为不可调用。否则校验器会接受一个格式错误的 schema 并据此做出错误的校验 ——
  `{"type": "bogus"}` 什么都匹配不上，`"properties": "a string"` 会被忽略 —— 于是处理函数会在无人
  检查过的参数上运行，或者每次调用都以错误的理由失败。

#### McpMessageHandler
- **用途**：这正是 `McpSdkServerConfig` 实际持有的接缝，使应用可以自行提供 MCP 服务 —— 资源、提示词、
  补全，或者对第三方 MCP 库的适配 —— 而不必使用 `SdkMcpServer`。
- **契约**：`handleMessage` 对请求返回 JSON-RPC 响应，对任何不期待回复的消息返回 `null`。`close()` 的
  含义是"使用你的那个连接要走了"，而不是"关停"：一个处理器可以服务多个客户端，因此它必须是幂等的，
  并且保持可用。

#### ToolCallContext
- **用途**：让正在运行的工具得知它的调用已被取消。
- **它为何必须存在**：`CompletableFuture.cancel(true)` 并不会中断正在运行的任务 —— 它只是完成那个
  future，而工作照旧继续。若没有显式信号，取消只会终止*等待*而不会终止*工作*，于是带副作用的工具会在
  CLI 放弃之后继续施加这些副作用。

#### SdkMcpTool
- **用途**：工具定义与执行的包装
- **创建方式**：
  - `SdkMcpTool.create()` —— 以编程方式创建
  - `@Tool` 注解 —— 声明式创建
- **特性**：
  - 输入的泛型类型参数
  - 基于 CompletableFuture 的异步执行
  - 用于输入校验的 JSON Schema
  - 自动提取参数

**设计模式**：Command + Factory + 注解处理

### 6. SessionStore 子系统

#### SessionStore（适配器协议）
- **用途**：把会话记录镜像到外部存储（S3、Postgres、Redis、自定义后端），使会话的持久性超越本地磁盘，
  并可跨主机恢复。
- **必需方法**：`append(SessionKey, List<SessionStoreEntry>)`、`load(SessionKey)`。
- **可选方法**（带 `implements*()` 能力探测）：`listSessions`、`listSessionSummaries`、`delete`、
  `listSubkeys`。
- **同步 + 异步 API**：每个方法都有 `*Async`（`CompletableFuture`）变体。拥有原生非阻塞客户端的适配器
  （AWS SDK v2 async、R2DBC、Lettuce reactive）可直接覆写 `*Async` 方法以避免线程跳转。默认执行器通过
  `SessionStoreExecutor` 配置（每个任务一个线程；在 Java 21+ 上是虚拟线程，否则是守护平台线程）。

**设计模式**：Adapter + 能力协商 + 双 API（同步/异步）

#### TranscriptMirrorBatcher（内部）
- **用途**：缓冲 CLI 在 stdout 上发出的 `transcript_mirror` 帧，并把它们刷写到
  `store.appendAsync(...)`。
- **关键行为**：
  - 立即刷写的阈值：`MAX_PENDING_ENTRIES=500`、`MAX_PENDING_BYTES=1 MiB`。
  - 在每条 `result` 消息之前以及流结束 / 关闭时显式刷写。
  - 按 `filePath` 合并帧，使每个唯一文件在每次刷写时只产生一次 `append` 调用。
  - 有界重试：`MIRROR_APPEND_MAX_ATTEMPTS=3` 次尝试，退避为 `[200ms, 800ms]`。超时不重试（进行中的
    调用仍可能落地）。
  - 路径落在所配置 `projectsDir` 之外的帧会被丢弃并给出警告。
  - 失败会以 `MirrorErrorMessage` 出现在消费者的消息流中 —— 绝不阻塞会话。

**设计模式**：生产者-消费者缓冲 + 指数退避重试

#### SessionResume（内部）
- **用途**：把已存储的会话物化到临时的 `CLAUDE_CONFIG_DIR`，以便 CLI 子进程能从本地磁盘恢复。
- **流程**：
  1. 通过 `store.loadAsync()` 加载条目（对于 `continueConversation`，则选出最近修改过的非支线会话）。
  2. 把 JSONL 写入一个布局类似 `~/.claude/` 的临时目录。
  3. 复制 `.credentials.json`（其中的 `refreshToken` 会被抹去，以防从临时目录消耗令牌）与
     `.claude.json`。
  4. 当存储实现了 `listSubkeys` 时，物化子 agent 的会话记录与 `.meta.json` 附属文件。
  5. 以 `CLAUDE_CONFIG_DIR=<temp dir>` 派生 CLI。
  6. 断开连接时清理，遇到 Windows 杀软/索引器的临时锁定会重试。

**设计模式**：物化视图 + 重试清理

#### SessionStoreValidation（内部）
- **用途**：在派生子进程之前做预检选项检查。对无效组合抛出 `IllegalArgumentException` 予以拒绝：
  - `continueConversation + sessionStore` 要求 `store.implementsListSessions()`。
  - `sessionStore + enableFileCheckpointing` 会被拒绝（检查点只存在于本地）。

**设计模式**：快速失败校验

#### SessionStoreConformance（公开的测试辅助）
- **位置**：`in.vidyalai.claude.sdk.testing.SessionStoreConformance`
- **用途**：面向 `SessionStore` 适配器、与测试框架无关的 14 项行为契约测试套件。它使用纯粹的
  `AssertionError`，因此在任何测试框架下都能工作（JUnit、TestNG、Spock，或普通的 `main`）。

**设计模式**：契约测试

## 设计模式

### 1. 密封接口（模式匹配）
被广泛用于类型安全的消息处理：

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}

// Usage with pattern matching
switch (message) {
    case UserMessage u -> handleUser(u);
    case AssistantMessage a -> handleAssistant(a);
    case ResultMessage r -> handleResult(r);
    case MirrorErrorMessage m -> handleMirrorError(m);
    case HookEventMessage h -> handleHookEvent(h);
    case SystemMessage s -> handleSystem(s);
    case StreamEvent e -> handleStreamEvent(e);
    // ... task and rate-limit cases
}
```

**好处**：
- 编译期的穷尽式模式匹配
- 不需要 default 分支
- 保证类型安全
- 类型层次结构清晰

### 2. Builder 模式
用于配置对象：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .build();

// Modify existing options
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .build();
```

**好处**：
- 配置可读性好
- 支持可选参数
- 对象不可变
- 可链式调用的 API

### 3. Facade 模式
ClaudeSDK 提供了简化的接口：

```java
// Simple facade
List<Message> messages = ClaudeSDK.query("Hello");

// Hides complexity of:
// - Transport creation
// - QueryHandler setup
// - Message parsing
// - Resource cleanup
```

**好处**：
- 常见用例的 API 很简单
- 隐藏内部复杂性
- 单一入口

### 4. 虚拟线程（并发）
在运行时提供的前提下，利用 Project Loom 实现轻量级并发。

SDK 以 Java 17 为编译目标，那里并不存在 `Thread.ofVirtual()`，因此每个线程与执行器都通过
`internal.Threads` 创建。它会一次性地以反射解析 Java 21 的入口点，存入 `static final` 方法句柄，并在
它们不存在时回退为具名的守护平台线程：

```java
// Background reader thread
Thread reader = Threads.start("ClaudeSDK-Reader-", () -> readLoop());

// Executor for control protocol
ExecutorService executor = Threads.newSingleThreadExecutor("ClaudeSDK-Reader-");
```

两条路径上的线程名完全相同，因此无论运行时如何，线程转储读起来都是一样的。设置
`-Dclaude.sdk.virtualThreads=false` 可在任何 JDK 上强制走平台线程路径。

**在 Java 21+ 上的好处**：
- 轻量级线程（可达数千个）
- 阻塞式 I/O 不会耗尽线程池
- 异步代码更简单
- 资源利用率更好

**在 Java 17-20 上**：同样的代码运行在守护平台线程上。这些执行器保持*无界*，而不是变成固定大小的池 ——
`QueryHandler` 的控制执行器会在 SDK MCP 工具调用期间驻留一个线程，而终止它的取消操作是作为另一个任务
到来的，因此有界的池会死锁。代价是每个进行中的控制请求占用一个操作系统线程，而不是一个虚拟线程。

### 5. CompletableFuture（异步操作）
用于异步回调与控制协议：

```java
// Permission callback
CompletableFuture<PermissionResult> future =
    canUseTool.apply(toolName, input, context);

// Control protocol request/response
CompletableFuture<ControlResponse> response =
    sendControlRequest(request);
```

**好处**：
- 非阻塞操作
- 可组合的异步链
- 错误处理
- 支持超时

## 数据流

### 查询执行流程

```
User Code
    │
    ├─► ClaudeSDK.query(prompt, options)
    │       │
    │       ├─► Validate options
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       │       │
    │       │       ├─► Start reader thread
    │       │       └─► Process messages
    │       │               │
    │       │               ├─► Parse JSON
    │       │               ├─► Handle control protocol
    │       │               ├─► Invoke hooks
    │       │               ├─► Check permissions
    │       │               └─► Add to message queue
    │       │
    │       └─► Collect messages
    │               │
    └─────────────► Return List<Message>
```

### 交互式客户端流程

```
User Code
    │
    ├─► ClaudeSDKClient.connect()
    │       │
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       ├─► Start reader thread
    │       └─► Initialize (control protocol handshake)
    │
    ├─► client.sendMessage("Hello")
    │       │
    │       └─► Write to transport stdin
    │
    ├─► client.receiveResponse()
    │       │
    │       └─► Iterator reads from message queue
    │               │
    │               ├─► Blocks until message available
    │               ├─► Returns messages until ResultMessage
    │               └─► Auto-closes iterator
    │
    └─► client.close()
            │
            ├─► Close QueryHandler
            ├─► Close Transport
            └─► Cleanup resources
```

### 钩子调用流程

```
CLI Process
    │
    ├─► Sends hook request via stdout
    │       │
    │       └─► {"type": "control", "method": "sdk.hook_callback", ...}
    │
QueryHandler
    │
    ├─► Receives hook request
    │       │
    │       ├─► Parse hook event and input
    │       ├─► Match against registered hooks
    │       ├─► Invoke matching hooks in parallel
    │       │       │
    │       │       └─► CompletableFuture.allOf(...)
    │       │
    │       └─► Collect results
    │               │
    │               └─► Combine outputs (logs, messages, updates)
    │
    └─► Send hook response via stdin
            │
            └─► {"id": "...", "result": {...}}
```

### 控制请求的失败处理

每一个入站的 `control_request` 都在各自的线程上处理，并通过 `ExecutorService.submit(...)` 提交 ——
而它返回的 `Future` 无人读取。因此从处理函数中逃逸的 `Throwable` 过去会不留痕迹地消失；又因为 CLI 会
阻塞直到收到匹配的 `control_response`，整个运行就会挂起，而两侧都没有任何诊断信息。

因此 `handleControlRequest` 捕获的是 `Throwable` 而不是 `Exception`。任何失败都会被记录（普通异常为
`WARNING`，`Error` 为 `SEVERE`），并以一个错误控制响应作答，使 CLI 不会一直等待；真正的 `Error` 随后
会被重新抛出，而不是被吞掉。对于无法恢复出 `request_id` 的请求，就只能记录日志，因为没有可作答的对象。

这并非纸上谈兵。一个陈旧的、由 IDE 编译出的、携带"unresolved compilation problem" `Error` 的类，曾让
每一个 SDK MCP 控制请求都无声地挂起，直到这里的捕获范围被放宽。

### stdin 的生命周期与进行中的任务

> **`controlExecutor` 必须保持"每任务一线程"。** SDK MCP 工具调用会驻留其控制线程直到工具作答，而终止
> 它的 `notifications/cancelled` 是作为*另一个*控制请求到来的。在任何有界池下，那个取消都会排在它本
> 想取消的那次调用后面，从而死锁。没有测试能抓到它 —— 一个大小为二的固定池能通过所有测试。

当注册了钩子、SDK MCP 服务器或 `canUseTool` 权限回调时，控制协议需要在整个会话期间保持 stdin 打开，
因此 `QueryHandler.streamInput()` 会等待一个能结束运行的 `result` 帧，然后才调用
`transport.endInput()`。这三者的服务方式相同 —— CLI 写出一个 `control_request` 并阻塞，直到 SDK 把
匹配的 `control_response` 写入 stdin —— 因此三者都算作双向需求（`hasBidirectionalNeeds()`）。过早关闭
并非无害：处于 stream-json 模式的 CLI **只**在 stdin EOF 时退出，所以也不能简单地把关闭推迟到
`close()` —— 那会让一次性的 `query()` 永远挂住。

微妙之处在于：**一个 `result` 帧结束的是一轮，而不是整个运行**。后台任务会在它之后继续运行，并且仍然
需要 stdin 来传递钩子与 SDK-MCP 的控制响应。在第一个 result 处就关闭，会让仍在运行的子 agent 的
SDK-MCP 工具调用以 `"Stream closed"` 失败，而且 —— 更隐蔽地 —— 它的 `PreToolUse` 钩子从未被投递，于是
内置工具照常执行，拒绝型的门控钩子也就不再门控。

因此 `QueryHandler` 维护了一份进行中任务的台账，由 `system` 任务生命周期帧填充，并且只有当台账为空时
才把某个 result 视为运行结束：

```
system: task_started (task_type ∈ DEFERRING_TASK_TYPES)  ─►  add task_id
system: task_notification                                ─►  remove task_id
system: task_updated (patch.status ∈ TERMINAL_TASK_STATUSES) ─► remove task_id

result frame
    ├─ ledger empty     ─►  complete firstResultEvent  ─►  endInput()
    └─ ledger non-empty ─►  keep stdin open, log at FINE
```

每个任务完成都会唤醒父级进行一次后续轮次，而它又以另一个 result 帧结束，因此关闭仍会及时发生 ——
并且链式的后台任务也能正常工作，因为台账只有在最后一个任务结清之后才会清空。

`DEFERRING_TASK_TYPES` 是 `{"local_agent", "local_workflow"}`。这些排除是刻意为之而非疏漏：后台 shell
（`local_bash`）与监视器在设计上会无限期运行，而 teammate 在其整个生命周期内都保持 `running`，因此它们
都不会可靠地到达终态。跟踪其中任何一个，都会*永远*扣住关闭而不是短暂推迟 —— 而且由于进程不会退出，
连读取器的 `finally` 都不会运行。任何要加入这个集合的类型，都必须是能够可靠终止的类型。

`background_tasks_changed` 帧在两个方向上都会被忽略。那份负载是实时的*后台*集合，但子 agent 是在前台
注册的，之后才转为后台，并且不会再有第二次 `task_started` —— 因此依据它来收窄，恰恰会漏掉这份台账本
想保护的那个 agent；而依据它来扩大，则可能引入一个后续任何帧都不会清除的 id。

这是一种缓解手段，而不是完整的答案：台账为空意味着"就我们所知没有东西在运行"，这与"运行已经结束"并
不相同。若某个任务在其所在轮次的 result 帧*之前*结清，那么在那个 result 处台账就是空的。没有哪种台账
能弥合这一缝隙 —— 那需要来自 CLI 的运行边界信号 —— 但常见的顺序（任务的存活时间超过派生它的那一轮）
已经得到修复。

## 并发模型

### 线程架构

SDK 采用多线程架构（在 Java 21+ 上为虚拟线程，在 17-20 上为守护平台线程）：

1. **主线程**：用户应用的线程
2. **读取线程**：从 CLI 的 stdout 读取
3. **控制执行器**：用于异步控制协议操作的线程池
4. **流式执行器**：用于流式输入消息的可选线程
5. **钩子执行器**：每个进行中的钩子或工具调用一个线程

### 线程安全

- **AtomicBoolean**：用于连接状态与关闭状态
- **volatile**：用于 QueryHandler 与 Transport 的可见性
- **同步**：用于 connect() 以避免竞态条件
- **BlockingQueue**：线程安全的消息队列
- **ConcurrentHashMap**：线程安全的控制请求跟踪

### 资源管理

所有资源都实现了 AutoCloseable：

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
} // Automatic cleanup: QueryHandler, Transport, Executors
```

## 类型系统

### 消息类型层次结构

```
Message (sealed interface)
    ├── UserMessage (record)
    ├── AssistantMessage (record)
    │       └── content: List<ContentBlock>   (sealed interface)
    │               ├── TextBlock
    │               ├── ThinkingBlock
    │               ├── ToolUseBlock
    │               ├── ToolResultBlock
    │               ├── ServerToolUseBlock
    │               ├── ServerToolResultBlock
    │               ├── ImageBlock        - PDF page render
    │               ├── DocumentBlock     - whole PDF
    │               └── UnknownBlock      - forward-compat fallback
    ├── SystemMessage (record)
    ├── ResultMessage (record)
    │       └── modelUsage: Map<String, ModelUsage>
    └── StreamEvent (record)
```

两个密封层次结构都适合穷尽式 `switch`，这意味着新增成员对调用方而言是一次刻意的源码破坏事件。
`ContentBlock` 在 0.1.20 中新增了三个成员；而 `UnknownBlock` 的存在，使得*未建模*的类型根本不再需要
SDK 的改动 —— 解析器会把它们整体保留，并针对每种类型记录一次日志，而不是抛出异常。

### 配置类型

```
ClaudeAgentOptions
    ├── PermissionMode (enum)
    ├── ToolsPreset (record)
    ├── SystemPromptPreset (record)
    ├── SdkBeta (enum)
    ├── SettingSource (enum)
    ├── SandboxSettings (record)
    ├── ThinkingConfig (sealed interface)
    │       ├── ThinkingConfigAdaptive (record)
    │       ├── ThinkingConfigEnabled (record)
    │       └── ThinkingConfigDisabled (record)
    ├── McpServerConfig (sealed interface)
    │       ├── McpStdioServerConfig
    │       ├── McpSseServerConfig
    │       ├── McpHttpServerConfig
    │       └── McpSdkServerConfig
    ├── HookEvent (enum) - 10 events
    ├── HookMatcher (record)
    └── AgentDefinition (record)
```

### 权限类型

```
PermissionResult (sealed interface)
    ├── PermissionResultAllow (record)
    └── PermissionResultDeny (record)
            └── reason: String
```

### 钩子类型

```
HookInput (sealed interface)
    ├── PreToolUseHookInput
    ├── PostToolUseHookInput
    ├── PostToolUseFailureHookInput
    ├── UserPromptSubmitHookInput
    ├── StopHookInput
    ├── SubagentStopHookInput
    ├── SubagentStartHookInput
    ├── PreCompactHookInput
    ├── NotificationHookInput
    └── PermissionRequestHookInput
```

## 依赖

### 运行时依赖

1. **Jackson**（2.21.0）
   - `jackson-databind` —— JSON 序列化/反序列化
   - `jackson-annotations` —— JSON 注解
   - 用途：解析 CLI 的 JSON 消息，序列化控制协议

2. **JSpecify**（1.0.0）
   - 可空性注解（`@Nullable`、`@NonNull`）
   - 用途：更好的空安全性与 IDE 支持

3. **networknt json-schema-validator**（2.0.4）
   - 用途：按照 MCP 规范对服务器的要求，在处理函数运行之前，针对工具声明的 `inputSchema` 校验 SDK MCP
     工具的参数
   - 刻意锁定在 2.x 线上：3.x 是针对 Jackson 3（`tools.jackson`）构建的，会在上述 Jackson 2 之外再放
     入一整套 JSON 技术栈。2.0.4 是复用我们 databind 的最新版本。
   - 它的 YAML schema 读取器被排除（工具 schema 以已解析的 map 形式到达），一个它错误地声明为
     compile 作用域的 Surefire 报告格式化器同样被排除
   - 会传递引入 `slf4j-api`（2.0.17）。SDK 通过 `java.util.logging` 记录日志，并且**不**附带任何 SLF4J
     绑定 —— 选择哪一个是应用的决定。没有 provider 的应用，会在首次构造 SDK MCP 服务器时在 stderr 上看到
     一次性的 `No SLF4J providers were found` 提示；添加任意绑定即可消除它。

### 测试依赖

1. **JUnit 5**（6.0.2）
   - 测试框架
   - 用途：单元测试与集成测试

2. **AssertJ**（3.27.7）
   - 流畅的断言库
   - 用途：可读性好的测试断言

3. **Mockito**（5.21.0）
   - 模拟框架
   - 用途：在测试中模拟依赖

### 构建依赖

1. **Maven Compiler Plugin**（3.14.1）
   - 以 Java 17 编译（`<release>17</release>`），并带 `-parameters` 标志
   - 用途：为 @Tool 注解保留参数名

2. **Flatten Maven Plugin**（1.7.3）
   - 解析 `${revision}` 属性
   - 用途：CI 友好的版本管理

3. **Templating Maven Plugin**（3.1.0）
   - 从模板生成 SdkVersion.java
   - 用途：在构建时注入版本号

## 设计原则

1. **类型安全**：充分利用 Java 的类型系统（密封接口、record、枚举）
2. **不可变性**：配置对象是不可变的
3. **线程安全**：记录并落实线程安全保证
4. **资源管理**：用 AutoCloseable 保证正确清理
5. **Builder 模式**：流畅、可读的配置方式
6. **快速失败**：尽早校验并抛出有意义的异常
7. **模式匹配**：使用现代 Java 特性写出更清晰的代码
8. **虚拟线程**：在 Java 21+ 上透明地实现轻量级并发
9. **关注点分离**：层与层之间边界清晰
10. **可扩展性**：插件系统与自定义传输

## 性能考量

1. **虚拟线程**：在 Java 21+ 上可实现数千个并发操作
2. **缓冲 I/O**：减少子进程通信的系统调用
3. **消息队列**：大小可配置，以平衡内存与吞吐量
4. **惰性初始化**：仅在需要时创建 QueryHandler
5. **资源复用**：跨操作复用 ExecutorService
6. **直接内存**：Jackson 采用高效的缓冲处理
7. **最少拷贝**：消息对象使用 record（无防御性拷贝）

## 未来的可扩展性

该架构支持未来的增强：

1. **自定义传输**：实现 Transport 接口以连接远程的 Claude Code
2. **更多消息类型**：添加到密封接口层次结构中
3. **新的钩子事件**：添加到 HookEvent 枚举中
4. **插件系统**：用 SdkPluginConfig 实现自定义扩展
5. **替代协议**：替换控制协议的实现
6. **流式改进**：增强的部分消息支持
7. **缓存**：在 SDK 与 CLI 之间加入缓存层
8. **指标**：加入遥测与性能监控
