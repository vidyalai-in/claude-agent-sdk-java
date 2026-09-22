# Session Store（会话记录的外部镜像）

把 Claude Code 的会话记录镜像到外部存储（S3、Postgres、Redis，或你自己的后端），使会话的持久性超越
本地磁盘，并可从任何地方恢复。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-session-store.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [何时使用 SessionStore](#何时使用-sessionstore)
- [快速上手](#快速上手)
- [SessionStore 接口](#sessionstore-接口)
- [同步与异步 API](#同步与异步-api)
- [配置异步执行器](#配置异步执行器)
- [内置的参考适配器](#内置的参考适配器)
- [基于 SessionStore 的读取 API](#基于-sessionstore-的读取-api)
- [基于 SessionStore 的变更操作](#基于-sessionstore-的变更操作)
- [从存储恢复](#从存储恢复)
- [镜像错误](#镜像错误)
- [刷写模式（BATCHED 与 EAGER）](#刷写模式batched-与-eager)
- [把本地会话导入存储](#把本地会话导入存储)
- [一致性测试套件](#一致性测试套件)
- [内部运行时组件](#内部运行时组件)
- [最佳实践](#最佳实践)
- [API 参考](#api-参考)

## 概览

默认情况下，Claude Code CLI 会把每个会话写成 `~/.claude/projects/` 下的 JSONL 文件。SDK 还可以把每一行
会话记录额外镜像到你选择的外部存储 —— 这在以下场景很有用：

- **长时运行会话的持久化** —— 在 serverless / 自动扩缩平台上，本地磁盘是易失的。
- **跨主机恢复** —— 在主机 A 上开始会话，在主机 B 上恢复。
- **审计 / 合规留存** —— 应用你自己的 TTL 策略（S3 生命周期、Postgres 分区、Redis TTL）。
- **多租户部署** —— 按 `project_key` 划定会话记录范围以隔离租户。

SDK 提供：

- `SessionStore` 接口（同步 + 异步两种变体）
- `InMemorySessionStore` 参考适配器
- 运行时镜像集成（对使用者透明 —— 在 options 上设置 `sessionStore`，其余由 SDK 处理）
- 用于迁移既有磁盘会话的 `importSessionToStore()`

本地磁盘的会话记录始终会先写入；镜像是次级的持久化路径。镜像失败绝不会阻塞会话 —— 它会以非致命的
`MirrorErrorMessage` 呈现。

## 何时使用 SessionStore

| 场景 | 建议 |
|---|---|
| 工作站上的单用户 CLI | 不必费事 —— 本地 JSONL 就够了 |
| 长时运行的服务端，会话跨越多个请求 | 使用 `SessionStore` |
| 合规 / 受监管的留存要求 | 使用带原生生命周期策略的 `SessionStore` |
| 多主机集群 / 云端自动扩缩 | 使用 `SessionStore`，让任意主机都能恢复 |
| 跨大量会话的审计 / 回放 | 使用 `SessionStore` 以便集中查询 |

## 快速上手

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)            // mirror every transcript line here
    .build();

ClaudeSDK.query("Hello!", options);
// All transcript entries from this turn are now in `store`
```

SDK 会在调用 CLI 时加上 `--session-mirror`，从 CLI 的 stdout 中剥离 `transcript_mirror` 帧，并分批转发
给 `store.append(...)`。

要在另一台主机上从存储恢复：

```java
ClaudeAgentOptions resumeOptions = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume("previous-session-uuid")
    .build();

ClaudeSDK.query("Continue where we left off", resumeOptions);
```

SDK 会把已存储的会话记录加载进一个临时的 `CLAUDE_CONFIG_DIR`，以便 CLI 子进程接上这段会话。

## SessionStore 接口

`in.vidyalai.claude.sdk.types.session.SessionStore` 是一个 Java 接口。其中两个方法是必需的；其余是
可选的，并带有 `implements*()` 探测标志，使调用方无需 `instanceof` 就能判断支持了哪些能力。

### 必需方法

```java
void append(SessionKey key, List<SessionStoreEntry> entries);

@Nullable
List<SessionStoreEntry> load(SessionKey key);
```

- `append` —— 镜像一批会话记录条目。它在本地磁盘写入成功*之后*才被调用，因此本地的持久性已经得到保证。
  适配器应把 `entry.uuid()` 当作幂等键（像自定义标题或标签这类没有 `uuid` 的条目，应当直接追加而不去重）。
- `load` —— 返回某个键的全部条目（与追加进去的内容深度相等；不要求字节相等）。对于从未写入过的键返回
  `null`。

### 可选方法（默认抛出 `UnsupportedOperationException`）

```java
default List<SessionStoreListEntry> listSessions(String projectKey);
default List<SessionSummaryEntry> listSessionSummaries(String projectKey);
default void delete(SessionKey key);
default List<String> listSubkeys(SessionListSubkeysKey key);
```

### 能力探测

```java
default boolean implementsListSessions() { return false; }
default boolean implementsListSessionSummaries() { return false; }
default boolean implementsDelete() { return false; }
default boolean implementsListSubkeys() { return false; }
```

当你实现了对应的可选方法时，请覆写这些方法返回 `true`。SDK 会用这些探测（而不是 `try/catch`）来决定
是否调用该可选方法。

### 键类型

```java
public record SessionKey(
    String projectKey,             // caller-defined scope (default: sanitized cwd)
    String sessionId,              // session UUID
    @Nullable String subpath        // null for main; "subagents/agent-x" for subagent
);

public record SessionListSubkeysKey(String projectKey, String sessionId);

public record SessionStoreListEntry(String sessionId, long mtime);

public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data);
```

`SessionStoreEntry` 是对 `Map<String, Object>` 的一层薄包装，要求含有 `type` 字段；其余内容都是不透明的
原样透传：

```java
SessionStoreEntry entry = SessionStoreEntry.of(Map.of(
    "type", "user",
    "uuid", "u1",
    "message", Map.of(
        "content", List.of(Map.of("type", "text", "text", "Hello"))),
    "timestamp", "2026-04-27T00:00:00Z"
));

entry.type();      // "user"
entry.uuid();      // "u1"
entry.timestamp(); // "2026-04-27T00:00:00Z"
entry.<String>get("custom_field"); // typed convenience accessor
entry.asMap();     // unmodifiable map view
```

## 同步与异步 API

`SessionStore` 上的每个方法都有同步与 `*Async`（返回 `CompletableFuture`）两种变体：

```java
// Sync (required to implement; or default to *Async().join() if you only override async)
void append(SessionKey key, List<SessionStoreEntry> entries);

// Async with default executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries);

// Async with explicit executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor);
```

内部的镜像批处理器与恢复物化器调用的是 `*Async` 变体 —— 因此拥有原生非阻塞客户端的适配器
（AWS SDK v2 async、R2DBC、Lettuce reactive）可以覆写 `*Async` 方法，从而端到端地保持并行性。

### 默认的委派方式

- 如果你只覆写**同步**方法（JDBC、Jedis、阻塞式 S3 SDK v1 的典型情形），`*Async` 的默认实现会在所配置的
  执行器上用 `CompletableFuture.supplyAsync(...)` 包装你的同步调用（每个任务一个线程；在 Java 21+ 上是
  虚拟线程）。
- 如果你只覆写**异步**方法（推荐用于 AWS SDK v2 async / Lettuce reactive / R2DBC），请把同步方法实现为
  `appendAsync(key, entries).join()`，这样两种调用点都能工作。

```java
public class S3AsyncStore implements SessionStore {
    private final S3AsyncClient s3;

    @Override
    public void append(SessionKey key, List<SessionStoreEntry> entries) {
        appendAsync(key, entries).join();
    }

    @Override
    public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        // Native async — no thread hop
        return s3.putObject(/* ... */).thenApply(r -> null);
    }

    @Override
    public List<SessionStoreEntry> load(SessionKey key) { /* ... */ }
}
```

## 配置异步执行器

默认情况下，异步包装会以**每个任务一个线程**的方式运行，线程名为 `session-store-<n>`。在 Java 21+ 上
它们是虚拟线程；在 Java 17-20 上 SDK 会回退为来自无界缓存池的守护平台线程。SDK 以 Java 17 为目标，并在
运行时选择更优的方案，因此你无需强制要求虚拟线程也能享受到它们。

你可以在启动时通过 `SessionStoreExecutor` 覆盖一次：

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

// Your own virtual-thread executor (needs Java 21+ in *your* project)
ExecutorService mine = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("my-store-", 0).factory());
SessionStoreExecutor.setDefault(mine);

// Or a bounded platform-thread pool
SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));

// Reset to the built-in default
SessionStoreExecutor.reset();
```

你也可以逐次调用时传入执行器：

```java
store.appendAsync(key, entries, customExecutor)
```

所配置的执行器会被每个不接受显式执行器参数的 `*Async` 默认实现使用。直接覆写 `*Async` 的适配器完全绕过
这一点 —— 该执行器只作用于同步→异步的包装路径。

## 内置的参考适配器

### `InMemorySessionStore`

一个线程安全的内存实现，适合测试与原型开发：

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

// All optional methods implemented
store.implementsListSessions();         // true
store.implementsListSessionSummaries(); // true
store.implementsDelete();               // true
store.implementsListSubkeys();          // true

// Test helpers
store.size();             // count of main-transcript sessions
store.snapshotSummaries();// LinkedHashMap snapshot of summary sidecars
store.clear();            // wipe everything
```

它在 `append()` 内部维护一个增量的 `SessionSummaryEntry` 附属记录，因此 `listSessionSummaries()` 的
复杂度是 O(1) —— 从不重新读取会话记录。

### 路径 → 键 的辅助方法

`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)` 是一个静态辅助方法，把磁盘上的会话
记录路径映射回 `SessionKey`：

```java
SessionKey k = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123.jsonl",
    "/home/u/.claude/projects");
// k = SessionKey("myproj", "abc-123", null)

SessionKey sub = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123/subagents/agent-x.jsonl",
    "/home/u/.claude/projects");
// sub = SessionKey("myproj", "abc-123", "subagents/agent-x")
```

对于 `projectsDir` 之外的路径或无法识别的布局，返回 `null`。镜像批处理器内部会使用它；把它公开出来是
为了让需要同样映射的适配器实现可以复用。

### 生产级适配器（S3、Redis、Postgres 等）

SDK 不提供生产级适配器 —— 它们依赖重量级的客户端库（AWS SDK、Lettuce、JDBC、R2DBC），我们不希望把它们
作为传递依赖引入。请自行实现，并用 `SessionStoreConformance`（见下文）验证。该协议很小且稳定。

## 基于 SessionStore 的读取 API

无需牵涉 CLI，直接从存储中读取会话：

```java
import in.vidyalai.claude.sdk.ClaudeSDK;

// List all sessions in the store for the current cwd
List<SDKSessionInfo> sessions =
    ClaudeSDK.listSessionsFromStore(store, /* directory */ null, /* limit */ 50, /* offset */ 0);

// Single-session metadata
SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);

// Full transcript
List<SessionMessage> messages =
    ClaudeSDK.getSessionMessagesFromStore(store, sessionId, null, null, 0);

// Subagent transcript discovery + reading
List<String> agentIds = ClaudeSDK.listSubagentsFromStore(store, sessionId, null);
List<SessionMessage> subAgent =
    ClaudeSDK.getSubagentMessagesFromStore(store, sessionId, agentIds.get(0), null, null, 0);

// Each message is attributed to the Agent tool_use that spawned the subagent,
// read from the mirrored `agent_metadata` entry (null if it is absent).
String spawnedBy = subAgent.get(0).parentToolUseId();
```

当存储实现了 `listSessionSummaries` 时，`listSessionsFromStore` 有一条快速路径：一次批量的摘要调用，
加上一次廉价的 `listSessions` 枚举，用来补齐那些附属记录缺失或过期的会话。若未实现
`listSessionSummaries`，它会回退为每个会话一次 `loadAsync()`，且**并发上限为 16 次调用**
（与 Python SDK 一致），以免大型项目的列举耗尽适配器的连接池。

如果 `listSessions` 与 `listSessionSummaries` 都未实现，该调用会抛出 `IllegalStateException`。
适配器 `loadAsync` 的失败会把个别行降级为空摘要条目，而不是让整个列表失败。

## 基于 SessionStore 的变更操作

与磁盘上的变更 API 形状相同，只是写入目标是存储：

```java
ClaudeSDK.renameSessionViaStore(store, sessionId, "My New Title", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, "important", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, null, null);   // clear tag
ClaudeSDK.deleteSessionViaStore(store, sessionId, null);

ForkSessionResult fork = ClaudeSDK.forkSessionViaStore(
    store, sessionId, /* directory */ null,
    /* upToMessageId */ null,                  // null copies full transcript
    /* title */ "My Fork");
```

内部实现：

- `renameSessionViaStore` 追加一条 `custom-title` 条目。
- `tagSessionViaStore` 追加一条 `tag` 条目；`null` 会通过空字符串来清除。
- 如果存储未实现 `delete()`，`deleteSessionViaStore` 就是空操作（对于原始 S3 这类 WORM/仅追加的后端很合适）。
- `forkSessionViaStore` 会执行与磁盘分叉相同的 UUID 重映射变换（共用 `SessionMutations.buildForkLines`）
  —— 仅在存储层做拷贝是**不够**的。

`listSubagentsFromStore` 需要 `listSubkeys()`，否则抛出 `IllegalStateException`。

## 从存储恢复

当 `options.sessionStore` 与 `options.resume`（或 `options.continueConversation`）同时设置时，SDK 会：

1. 对请求的会话 ID 调用 `store.load()`（对于 `continueConversation`，则通过 `store.listSessions()`
   选出最近修改过的非支线会话）。
2. 把这些条目写入一个布局与 `~/.claude/` 完全一致的临时目录。
3. 从你真实的配置目录为该临时目录播种，使子进程能够完成认证并表现如常 —— `.credentials.json`
   （其中的 `refreshToken` 会被抹去）、`.claude.json`，以及你的用户级 `settings.json` /
   `cowork_settings.json`。参见[会播种哪些内容](#会播种哪些内容)。
4. 从存储中物化所有子 agent 的会话记录以及 `.meta.json` 附属文件（当实现了 `listSubkeys` 时）。
5. 以 `CLAUDE_CONFIG_DIR=<temp dir>` 派生 CLI，使其像往常一样从本地磁盘恢复。
6. 在断开连接时清理该临时目录（遇到 Windows 杀软/索引器的临时锁定会重试）。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume(previousSessionId)
    .loadTimeoutMs(60_000)        // per-call timeout for store.load() / listSubkeys()
    .build();
```

`loadTimeoutMs` 选项（默认 60 000）为物化期间的每一次存储调用设定上限；如果适配器在这个时间窗内没有
完成，查询会带着清晰的错误快速失败，而不是让迭代器一直挂着。

### 会播种哪些内容

由于子进程运行在被重定向的 `CLAUDE_CONFIG_DIR` 之下，它本来什么配置都看不到。SDK 会从调用方的配置目录
复制四个文件 —— 配置目录按 `options.env["CLAUDE_CONFIG_DIR"]` → 进程环境 → `~/.claude` 的顺序解析
（设置了该变量时 `.claude.json` 位于 `$CLAUDE_CONFIG_DIR/.claude.json`，否则位于 `~/.claude.json`，
*而不是* `~/.claude/.claude.json`）：

| 文件 | 为何重要 |
|------|----------------|
| `.credentials.json` | OAuth 凭据，其中的 `claudeAiOauth.refreshToken` 已被移除 |
| `.claude.json` | 用户级的 CLI 状态 |
| `settings.json` | `apiKeyHelper`，以及你的 `env`、`hooks` 和 `permissions` |
| `cowork_settings.json` | 在 cowork-plugins 模式下读取的备用设置文件名 |

播种 `settings.json` 比看上去更重要：除了凭据文件、macOS 钥匙串和环境变量之外，`apiKeyHelper` 是第四种
认证机制。在 v0.1.23 之前它不会被复制，因此仅通过 `apiKeyHelper` 认证的主机，一旦从存储恢复就会以
**"Not logged in"** 失败。

两个设置文件都会经过一层变换，只丢弃那些在被重定向的配置目录下会出问题的键：

- `enabledPlugins` 与 `extraKnownMarketplaces` —— 它们会与始终为空的临时插件缓存做协调，并在每次恢复时
  联网安装所有声明过的 marketplace。
- `env.CLAUDE_CONFIG_DIR` —— 它会把子进程的配置读取重新指回临时目录之外。

其余内容都会保留。UTF-8 BOM（PowerShell 会写入一个）是可以容忍的；而不是有效 UTF-8、或无法解析成 JSON
对象的内容，会被逐字节复制，从而让子进程看到的与 CLI 原本会读到的完全一致。这些文件在仅属主可访问
（`0700`）的临时目录中以仅属主可读写（`0600`）的方式写入。

播种是尽力而为的：某个文件若因"缺失"以外的任何原因无法读取 —— 权限错误，或本该是文件的位置上是目录或
FIFO —— 会被记录并跳过，而不是中止一次本来能成功的恢复。中途失败的复制会删除残缺的目标文件，以免子进程
错误解析被截断的文件。

### 校验防护

在任何子进程工作开始之前，SDK 会拒绝无效的组合：

- `continueConversation + sessionStore` 要求 `store.implementsListSessions()`。
- `sessionStore + enableFileCheckpointing` 会被拒绝 —— 检查点只存在于本地磁盘，会与镜像的会话记录产生
  分歧。

它们会立即抛出 `IllegalArgumentException`。

## 镜像错误

镜像追加失败是非致命的 —— 本地磁盘的会话记录已经持久化，因此会话不受影响地继续。SDK 会以
`[200ms, 800ms]` 的退避对每一批最多重试 3 次，然后丢弃它，并向你的消息流呈现一条
`MirrorErrorMessage`：

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query("Hello", options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            // Non-fatal — log and consider importing the local file later
            System.err.println("Mirror error for " + (err.key() != null
                    ? err.key().sessionId() : "<unknown>")
                    + ": " + err.error());
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { /* ignore */ }
    }
}
```

`MirrorErrorMessage` 是 `Message` 密封接口的成员（与 `AssistantMessage`、`SystemMessage` 等并列）——
`subtype` 始终为 `"mirror_error"`，`error` 是失败信息，`key`（可为 null）是失败批次所针对的
`SessionKey`。

超时**不会**重试（进行中的调用仍可能落地 —— 重试会引发一次并发的重复写入）。适配器应基于
`entry.uuid()` 去重，使部分成功后的重试对重复写入是安全的。

## 刷写模式（BATCHED 与 EAGER）

默认情况下，`TranscriptMirrorBatcher` 会缓冲每一个 `transcript_mirror` 帧，并在每轮结束时（在 `result`
消息上）刷写一次，或者当待处理缓冲区超过 `MAX_PENDING_ENTRIES=500` 条 / `MAX_PENDING_BYTES=1 MiB` 时
刷写。这样可以把适配器的延迟挡在流式热路径之外，对几乎所有部署来说都是正确的选择。

当你需要条目以亚秒级延迟落到存储中时，`sessionStoreFlush` 选项可以切换到即时镜像：

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

| 模式 | 何时刷写条目 | 适用场景 |
|---|---|---|
| `BATCHED`（默认） | 每条 `result` 消息刷写一次，或待处理量超过 500 条 / 1 MiB 时 | 几乎所有生产工作负载 —— 把适配器延迟挡在流式热路径之外 |
| `EAGER` | 每入队一帧就调度一次后台排空 | 向客户端实时流式传输会话记录、实时审计管道、无法等到 `result` 的超大轮次 |

`EAGER` 会把批处理器的待处理阈值清零 —— 每入队一帧都会通过所配置的 `SessionStoreExecutor` 调度一次后台
刷写（每个任务一个具名线程；在 Java 21+ 上是虚拟线程）。追加仍按入队顺序串行进行；慢速适配器不会拖住
读取循环，但在它忙碌期间会看到帧被合并。未设置 `sessionStore` 时该选项会被忽略。

## 把本地会话导入存储

把既有的磁盘会话迁移进存储，或在 `MirrorErrorMessage` 暴露出缺口之后让存储追上进度：

```java
ClaudeSDK.importSessionToStore(sessionId, store, /* directory */ null);
// or with explicit options:
ClaudeSDK.importSessionToStore(
    sessionId, store, /* directory */ null,
    /* includeSubagents */ true,
    /* batchSize */ 500);
```

该辅助方法：

- 逐行流式读取本地 JSONL（跳过空行）。
- 每 `batchSize` 条（默认 500）或每 1 MiB 的行字节数（以先到者为准）调用一次 `store.append(key, batch)`。
- 当 `includeSubagents=true` 时，递归导入 `<sessionDir>/subagents/**/*.jsonl` 与 `.meta.json` 附属文件
  （`.meta.json` 会变成一条 `agent_metadata` 条目）。
- 对无效 UUID 抛出 `IllegalArgumentException`，找不到会话文件时抛出 `NoSuchFileException`。

目标 `project_key` 就是磁盘上的项目目录名 —— 与 `filePathToSessionKey` 产生的键相同 —— 因此导入的会话
与实时镜像的会话别无二致，并且可以从原来的 `cwd` 恢复。

适配器应把 `entry.uuid()` 当作幂等键，这样重复导入是安全的。

## 一致性测试套件

`in.vidyalai.claude.sdk.testing.SessionStoreConformance` 是一个公开的、与测试框架无关的测试套件，它会
检验每个适配器都必须满足的 14 项行为契约。用它来验证你自己的实现：

```java
import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
import org.junit.jupiter.api.Test;

class MyRedisStoreConformanceTest {
    @Test
    void satisfiesContract() {
        SessionStoreConformance.run(MyRedisStore::new);
    }
}
```

若要跳过你未实现的可选方法：

```java
SessionStoreConformance.run(WormStore::new,
    EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
```

该套件使用纯粹的 `AssertionError`（不依赖任何测试框架），因此在 JUnit、TestNG、Spock，甚至一个普通的
`main()` 冒烟测试中都能工作。

这 14 项契约涵盖：

| # | 契约 |
|---|---|
| 1 | `append` 之后 `load` 返回相同顺序的相同条目 |
| 2 | 对未知键 `load` 返回 `null` |
| 3 | 多次 `append` 调用保持顺序 |
| 4 | `append([])` 是空操作 |
| 5 | 带 subpath 的键独立于主键存储 |
| 6 | `project_key` 隔离 |
| 7 | `listSessions` 返回项目下的会话 ID，mtime 为 epoch 毫秒 |
| 8 | `listSessions` 排除子 agent 的 subpath |
| 9 | `delete` 之后 `load` 返回 `null` |
| 10 | 对主键 `delete` 会级联到子键 |
| 11 | 带 subpath 的 `delete` 只移除那个子键 |
| 12 | `listSubkeys` 返回各个 subpath |
| 13 | `listSubkeys` 排除主会话记录 |
| 14 | `listSessionSummaries` 能通过 `foldSessionSummary` 往返一致 |

## 内部运行时组件

它们位于 `in.vidyalai.claude.sdk.internal`，不属于公开 API，但理解它们有助于调试镜像行为。

### `TranscriptMirrorBatcher`

缓冲 CLI 在 stdout 上发出的 `transcript_mirror` 帧，并把它们刷写到 `store.appendAsync(...)`：

- 立即刷写的阈值：`MAX_PENDING_ENTRIES=500`、`MAX_PENDING_BYTES=1 MiB`。设置了
  `sessionStoreFlush(EAGER)` 时两个阈值都会清零，于是每入队一帧都会调度一次后台排空
  （参见[刷写模式](#刷写模式batched-与-eager)）。
- 在每条 `result` 消息之前显式刷写，并在流结束 / 关闭时再刷写一次。
- 按 `filePath` 合并帧，使每个唯一文件在每次刷写时只产生一次 `append` 调用。
- 路径落在 `projectsDir` 之外的帧会被丢弃并给出警告（当父进程与子进程的 `CLAUDE_CONFIG_DIR` 不一致时
  会发生这种情况）。
- `MIRROR_APPEND_MAX_ATTEMPTS=3`，以 `[200ms, 800ms]` 的退避重试；超时不重试。
- `maxPendingEntries()` / `maxPendingBytes()` 测试访问器暴露所配置的阈值（与 Python 的公开属性对应）。

### `SessionResume`

把已存储的会话物化到临时的 `CLAUDE_CONFIG_DIR`，以便 CLI 能够恢复：

- `materializeResumeSession(options)` —— 主入口。
- `applyMaterializedOptions(options, materialized)` —— 复制 options，注入 `CLAUDE_CONFIG_DIR`、设置
  `resume`、清除 `continueConversation`。
- `buildMirrorBatcher(store, materialized, env, onError)` —— 用正确的 `projectsDir` 构造批处理器
  （默认为 `BATCHED` 刷写模式）。5 参数重载
  `buildMirrorBatcher(store, materialized, env, onError, flushMode)` 会在 `flushMode == EAGER` 时把
  批处理器的阈值清零。
- `MaterializedResume.cleanup()` —— 尽力而为的递归删除，遇到 Windows 杀软/索引器的临时锁定会重试。

### `SessionStoreValidation`

在派生子进程之前调用的预检选项检查（对错误配置抛出 `IllegalArgumentException` 予以拒绝）。

### `SessionSummary`

纯粹的辅助方法，适配器可以在 `append()` 内部使用它们来维护增量摘要附属记录，而无需重新读取会话记录：

```java
SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
    /* prev */ existing, key, entries);
// stamp folded.mtime() with the adapter's storage write time, then persist.
```

`SessionSummary.summaryEntryToSdkInfo(entry, projectPath)` 把附属记录转换回 `SDKSessionInfo` 以便列举。

## 最佳实践

### 适配器实现

- **务必实现 `append` + `load`。** 它们是必需的。
- 如果你的后端支持列举操作，请在 `append()` 内部通过 `SessionSummary.foldSessionSummary`
  **维护一份摘要附属记录**；这会让 `listSessionsFromStore` 的复杂度从 O(N) 次 load 变为 O(1)。对带
  `subpath` 的键要跳过折叠 —— 子 agent 的会话记录不应计入主会话的摘要。
- **把 `entry.uuid()` 当作幂等键。** 使用 upsert 语义或"已存在则跳过"。SDK 会重试失败的批次，并且可能
  部分成功。
- **摘要的 `mtime` 要盖上你存储的写入时间**，而不是条目的时间戳。快速路径的新鲜度检查会把摘要的 mtime
  与同一会话的 `listSessions().mtime` 作比较 —— 若用条目时间戳，会让每份附属记录都显得过期。
- **级联删除**：从主会话记录的键级联到所有子键（子 agent 的会话记录）。
- **在 CI 中运行一致性套件。**

### 何时覆写 `*Async` 方法

- 你的客户端原生就是异步的（AWS SDK v2 async、R2DBC、Lettuce reactive）—— 覆写 `*Async` 以避免线程跳转。
- 你的客户端是同步的（JDBC、Jedis、AWS SDK v1）—— 只实现同步方法即可，默认的 `*Async` 包装就够用。

### 何时使用 `importSessionToStore`

- 一次性把既有的本地会话迁移到存储。
- 在 `MirrorErrorMessage` 之后追平进度（重新导入本地文件；基于 `uuid` 的幂等性让这样做是安全的）。

### 应避免

- 把 `sessionStore` 与 `enableFileCheckpointing` 组合使用（无论如何都会在校验时被拒绝 —— 检查点只存在于
  本地）。
- 在没有留存控制的情况下存放机密或个人信息。SDK 不会自动删除；请配置你的存储的生命周期策略。
- 依赖 `load()` 的字节级相等序列化。契约是深度相等；例如 Postgres 的 `jsonb` 会重排键的顺序。

## API 参考

### `SessionStore` 接口

`in.vidyalai.claude.sdk.types.session.SessionStore`

| 方法 | 必需 | 默认实现 | 说明 |
|---|---|---|---|
| `void append(SessionKey, List<SessionStoreEntry>)` | ✅ | — | 镜像批次；在本地写入之后调用 |
| `List<SessionStoreEntry> load(SessionKey)` | ✅ | — | 返回条目或 `null` |
| `List<SessionStoreListEntry> listSessions(String)` | 可选 | 抛出异常 | 排除带 subpath 的条目 |
| `List<SessionSummaryEntry> listSessionSummaries(String)` | 可选 | 抛出异常 | `listSessionsFromStore` 的快速路径 |
| `void delete(SessionKey)` | 可选 | 抛出异常 | 主键会级联到子键 |
| `List<String> listSubkeys(SessionListSubkeysKey)` | 可选 | 抛出异常 | 供恢复物化使用 |
| `boolean implementsListSessions()` | — | `false` | 覆写以声明支持 |
| `boolean implementsListSessionSummaries()` | — | `false` | 覆写以声明支持 |
| `boolean implementsDelete()` | — | `false` | 覆写以声明支持 |
| `boolean implementsListSubkeys()` | — | `false` | 覆写以声明支持 |
| `CompletableFuture<Void> appendAsync(...)` | 可选 | 包装同步实现 | 为原生异步客户端覆写 |
| `CompletableFuture<List<SessionStoreEntry>> loadAsync(...)` | 可选 | 包装同步实现 | 为原生异步客户端覆写 |
| `CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(...)` | 可选 | 包装同步实现 | — |
| `CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(...)` | 可选 | 包装同步实现 | — |
| `CompletableFuture<Void> deleteAsync(...)` | 可选 | 包装同步实现 | — |
| `CompletableFuture<List<String>> listSubkeysAsync(...)` | 可选 | 包装同步实现 | — |

每个 `*Async` 方法都有一个无参重载（使用所配置的默认执行器）和一个接受 `Executor` 的重载
（可逐次调用控制）。

### `ClaudeSDK` 静态方法

| 方法 | 说明 |
|---|---|
| `String projectKeyForDirectory(@Nullable Path)` | 把目录净化成 `project_key` |
| `List<SDKSessionInfo> listSessionsFromStore(SessionStore, @Nullable Path, @Nullable Integer, int)` | 列出存储中的会话 |
| `SDKSessionInfo getSessionInfoFromStore(SessionStore, String, @Nullable Path)` | 读取单个会话的元数据 |
| `List<SessionMessage> getSessionMessagesFromStore(SessionStore, String, @Nullable Path, @Nullable Integer, int)` | 读取完整的会话记录 |
| `List<String> listSubagentsFromStore(SessionStore, String, @Nullable Path)` | 发现子 agent 的 ID |
| `List<SessionMessage> getSubagentMessagesFromStore(SessionStore, String, String, @Nullable Path, @Nullable Integer, int)` | 读取子 agent 的会话记录 |
| `void renameSessionViaStore(SessionStore, String, String, @Nullable Path)` | 追加 `custom-title` 条目 |
| `void tagSessionViaStore(SessionStore, String, @Nullable String, @Nullable Path)` | 追加 `tag` 条目；`null` 表示清除 |
| `void deleteSessionViaStore(SessionStore, String, @Nullable Path)` | 删除（若未实现 `delete` 则为空操作） |
| `ForkSessionResult forkSessionViaStore(SessionStore, String, @Nullable Path, @Nullable String, @Nullable String)` | 带 UUID 重映射的分叉 |
| `void importSessionToStore(String, SessionStore, @Nullable Path)` | 本地→存储回放（默认选项） |
| `void importSessionToStore(String, SessionStore, @Nullable Path, boolean, int)` | 显式指定 `includeSubagents` 与 `batchSize` 的回放 |

### `ClaudeAgentOptions` 的 builder 方法

| 方法 | 默认值 | 说明 |
|---|---|---|
| `Builder sessionStore(@Nullable SessionStore)` | `null` | 把会话记录镜像到该存储 |
| `Builder loadTimeoutMs(long)` | `60_000` | 恢复物化期间每次调用的超时 |

### `SessionStoreExecutor`

`in.vidyalai.claude.sdk.types.session.SessionStoreExecutor`

| 方法 | 说明 |
|---|---|
| `Executor getDefault()` | 当前的默认执行器 |
| `void setDefault(Executor)` | 覆盖；传 `null` 则重置为内置实现 |
| `void reset()` | 重置为内置的 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-store-", 0).factory())` |

### `SessionStoreConformance`

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`

| 方法 | 说明 |
|---|---|
| `void run(Supplier<SessionStore>)` | 运行全部 14 项契约 |
| `void run(Supplier<SessionStore>, Set<OptionalMethod>)` | 跳过所列出的可选方法 |

`OptionalMethod` 枚举：`LIST_SESSIONS`、`LIST_SESSION_SUMMARIES`、`DELETE`、`LIST_SUBKEYS`。

## 另见

- [会话历史](./feature-session-history.md) —— 本地磁盘的等价方法（`listSessions`、`getSessionMessages` 等）
- [消息类型](./feature-message-types.md) —— `MirrorErrorMessage` 的集成
- [ClaudeAgentOptions](./api-claude-agent-options.md) —— `sessionStore` 与 `loadTimeoutMs`
- [ClaudeSDK](./api-claude-sdk.md) —— 公开 API 的入口
- `examples/` 模块中的 `SessionStoreExample.java`
