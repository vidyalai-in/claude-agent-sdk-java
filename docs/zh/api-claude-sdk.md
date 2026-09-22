# ClaudeSDK API 参考

用于简单查询与创建客户端的静态 facade。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../api-claude-sdk.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 类概览

```java
public final class ClaudeSDK
```

提供常用 SDK 操作静态方法的工具类。

## 查询方法

### query(String prompt)

```java
public static List<Message> query(String prompt)
```

以默认选项执行查询。

**返回**：`List<Message>`

### query(String prompt, ClaudeAgentOptions options)

```java
public static List<Message> query(
    String prompt,
    ClaudeAgentOptions options
)
```

以自定义选项执行查询。

**参数**：
- `prompt` —— 提示词
- `options` —— 配置选项

**返回**：`List<Message>`

**抛出**：
- `IllegalArgumentException` —— 如果同时设置了 canUseTool 与 permissionPromptToolName
- `CLIConnectionException` —— 连接失败
- `ProcessException` —— CLI 进程失败
- `QueryFailedException` —— 本次运行以错误结果结束（`error_max_turns`、`error_max_budget_usd`，或被 `resumeDropsTurn` 拒绝的恢复）。它携带着此前已收集的消息 —— 见下文。

### query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

```java
public static List<Message> query(
    Iterator<Map<String, Object>> messageStream,
    ClaudeAgentOptions options
)
```

以多条消息执行流式查询。

**参数**：
- `messageStream` —— 消息字典的迭代器
- `options` —— 配置选项

**返回**：`List<Message>`

**抛出**：与上文相同，包括 `QueryFailedException`。

### 错误结果与部分消息

CLI 报告 `error_max_turns` 与 `error_max_budget_usd` 的方式，是发出一个*完整*的轮次 —— 助手消息，
加上携带 subtype、费用与用量的最终 `ResultMessage` —— 然后才有意以非零状态码退出，这是为了照顾
shell 使用者。

这些进行收集的方法只能二选一：返回一个列表，或者抛出异常。因此在这种情况下它们会抛出
`QueryFailedException`，并把已收集的消息一并交还。什么都不会丢失：

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (QueryFailedException e) {
    ResultMessage result = e.resultMessage();       // the final result, or null
    List<Message> partial = e.partialMessages();    // everything received first
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped after $%.4f%n", result.totalCostUsd());
    }
}
```

只要你设置了 `maxTurns` 或 `maxBudgetUsd`，就应当捕获它 —— 达到你自己配置的上限是预期内的结果，
而不是崩溃。当你需要的是该 result 的负载（`subtype()`、`terminalReason()`、`apiErrorStatus()` 等）
而不是已收集的消息时，请从 cause 中读取，它是一个
[`ResultException`](./api-exceptions.md#resultexception)。

[`ClaudeSDKClient`](./api-claude-sdk-client.md) 上的流式 API 从一开始就不需要这个异常：它们会在每条
消息到达时就把它交给你，而 `receiveResponse()` 会在 `ResultMessage` 处停止 —— 直接检查它的
`isError()` 与 `subtype()` 即可。参见[异常](./api-exceptions.md#queryfailedexception)。

## 便捷方法

### queryForText(String prompt, ClaudeAgentOptions options)

```java
public static String queryForText(
    String prompt,
    ClaudeAgentOptions options
)
```

仅获取助手消息中的文本内容。

**返回**：`String` —— 拼接后的文本

### queryForResult(String prompt, ClaudeAgentOptions options)

```java
public static ResultMessage queryForResult(
    String prompt,
    ClaudeAgentOptions options
)
```

仅获取结果消息。

**返回**：`ResultMessage` 或 null

## 客户端工厂方法

### createClient()

```java
public static ClaudeSDKClient createClient()
```

以默认选项创建客户端。

**返回**：`ClaudeSDKClient`

### createClient(ClaudeAgentOptions options)

```java
public static ClaudeSDKClient createClient(
    ClaudeAgentOptions options
)
```

以自定义选项创建客户端。

**返回**：`ClaudeSDKClient`

## MCP 服务器工厂方法

### createSdkMcpServer(String name, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    List<SdkMcpTool<?>> tools
)
```

从工具列表创建 SDK MCP 服务器。

**参数**：
- `name` —— 服务器名称
- `tools` —— 工具列表

**返回**：`McpSdkServerConfig`

### createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    String version,
    List<SdkMcpTool<?>> tools
)
```

创建带版本号的 SDK MCP 服务器。

### createSdkMcpServer(String name, Object instance)

```java
public static McpSdkMcpServer createSdkMcpServer(
    String name,
    Object instance
)
```

从带 @Tool 注解的方法创建 SDK MCP 服务器。

**参数**：
- `name` —— 服务器名称
- `instance` —— 含有 @Tool 方法的对象

**返回**：`McpSdkServerConfig`

## 会话历史方法

### listSessions()

```java
public static List<SDKSessionInfo> listSessions()
```

列出所有项目下的全部会话，按最近修改时间倒序排列。它从 `~/.claude/projects/` 读取，且不会完整解析
JSONL 文件 —— 每个文件只读首尾各 64 KB。

**返回**：按最近修改时间降序排列的 `List<SDKSessionInfo>`

### listSessions(Path directory)

```java
public static List<SDKSessionInfo> listSessions(Path directory)
```

列出指定项目目录的会话。

**参数**：
- `directory` —— 用于筛选的项目工作目录

**返回**：`List<SDKSessionInfo>`

### listSessions(Path directory, Integer limit, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    boolean includeWorktrees
)
```

完全可控地列出会话。

**参数**：
- `directory` —— 用于筛选的项目目录（null = 所有项目）
- `limit` —— 最多返回的会话数（null = 不限）
- `includeWorktrees` —— 是否包含 git worktree 目录

**返回**：`List<SDKSessionInfo>`

### getSessionInfo(String sessionId)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(String sessionId)
```

按 ID 查找单个会话。它会搜索 `~/.claude/projects/` 下的所有项目目录。不做 O(n) 的目录扫描 ——
只读取目标会话文件。

**参数**：
- `sessionId` —— 要查找的会话 UUID

**返回**：该会话的 `SDKSessionInfo`；若未找到、属于支线会话，或无法提取摘要，则为 `null`

### getSessionInfo(String sessionId, Path directory)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(
    String sessionId,
    Path directory
)
```

在指定项目目录内按 ID 查找单个会话。

**参数**：
- `sessionId` —— 要查找的会话 UUID
- `directory` —— 要搜索的项目工作目录

**返回**：`SDKSessionInfo` 或 `null`

### getSessionMessages(String sessionId)

```java
public static List<SessionMessage> getSessionMessages(String sessionId)
```

返回某个会话的完整会话消息。它会搜索所有项目目录。

**参数**：
- `sessionId` —— 会话的 UUID

**返回**：按会话顺序排列的 `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory
)
```

返回指定项目中某个会话的消息。

**参数**：
- `sessionId` —— 会话的 UUID
- `directory` —— 要搜索的项目工作目录

**返回**：`List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory,
    Integer limit,
    int offset
)
```

完全可控地返回筛选后的消息。

**参数**：
- `sessionId` —— 会话的 UUID
- `directory` —— 要搜索的项目目录（null = 所有项目）
- `limit` —— 最多返回的消息数（null = 不限）
- `offset` —— 从开头起跳过的消息数

**返回**：`List<SessionMessage>`

## 子 agent 记录方法

当会话派生子 agent 时（通过 `Task` 工具或以编程方式定义的 agent），每个子 agent 的记录会被写到
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`。这些文件也可能位于诸如
`subagents/workflows/<runId>/` 这样的嵌套目录中。

### listSubagents(String sessionId)

```java
public static List<String> listSubagents(String sessionId)
```

通过扫描所有项目目录中该会话的 `subagents/` 目录，列出会话的子 agent ID。

**参数**：
- `sessionId` —— 父会话的 UUID

**返回**：子 agent ID 的 `List<String>`。当会话未找到、`sessionId` 不是合法 UUID，或该会话没有子
agent 时，返回空列表。

### listSubagents(String sessionId, Path directory)

```java
public static List<String> listSubagents(String sessionId, Path directory)
```

在指定项目目录范围内列出子 agent ID。

**参数**：
- `sessionId` —— 父会话的 UUID
- `directory` —— 用于查找该会话的项目工作目录

### getSubagentMessages(String sessionId, String agentId)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId
)
```

从某个子 agent 的 JSONL 记录中读取其 user/assistant 消息。它会沿 `parentUuid` 链回溯以重建整条链。
每条消息的 `parentToolUseId` 是父会话中派生该子 agent 的那个 Agent `tool_use`，而对于嵌套的子 agent，
`parentAgentId` 指明派生方子 agent；两者都来自记录文件旁的 `agent-<agentId>.meta.json` 附属文件
——因为记录文件的行本身并不记录它们——并且当该附属文件缺失或不可用时两者都为 null。

**参数**：
- `sessionId` —— 父会话的 UUID
- `agentId` —— 子 agent ID（由 `listSubagents` 返回）

**返回**：按时间顺序排列的 `List<SessionMessage>`。当会话或子 agent 未找到、`sessionId` 不是合法
UUID，或记录中没有 user/assistant 消息时，返回空列表。

### getSubagentMessages(String sessionId, String agentId, Path directory)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    Path directory
)
```

在指定项目目录范围内读取某个子 agent 的消息。

### getSubagentMessages(String sessionId, String agentId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset
)
```

完全可控地读取子 agent 消息，支持筛选与分页。

**参数**：
- `sessionId` —— 父会话的 UUID
- `agentId` —— 子 agent ID
- `directory` —— 要搜索的项目目录（null = 所有项目）
- `limit` —— 最多返回的消息数（null 或 `0` = 不限）
- `offset` —— 从开头起跳过的消息数

## 会话变更方法

### renameSession(String sessionId, String title)

```java
public static void renameSession(
    String sessionId,
    String title
) throws IOException
```

通过追加一条自定义标题条目来重命名会话。最近一次重命名生效。它会搜索所有项目目录。

**参数**：
- `sessionId` —— 要重命名的会话 UUID
- `title` —— 新的会话标题（会去除首尾空白）

**抛出**：
- `IllegalArgumentException` —— 如果 `sessionId` 不是合法 UUID，或 `title` 为空
- `FileNotFoundException` —— 如果找不到会话文件
- `IOException` —— 如果写入失败

### renameSession(String sessionId, String title, Path directory)

```java
public static void renameSession(
    String sessionId,
    String title,
    Path directory
) throws IOException
```

在指定项目目录范围内重命名会话。

**参数**：
- `sessionId` —— 要重命名的会话 UUID
- `title` —— 新的会话标题
- `directory` —— 要搜索的项目工作目录

### tagSession(String sessionId, String tag)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag
) throws IOException
```

为会话打标签。传入 `null` 可清除已有标签。标签在存储前会经过 Unicode 净化。它会搜索所有项目目录。

**参数**：
- `sessionId` —— 要打标签的会话 UUID
- `tag` —— 标签字符串，或传 `null` 以清除。净化后必须非空（除非为 `null`）。

**抛出**：
- `IllegalArgumentException` —— 如果 `sessionId` 非法，或 `tag` 在净化后为空
- `FileNotFoundException` —— 如果找不到会话文件
- `IOException` —— 如果写入失败

### tagSession(String sessionId, String tag, Path directory)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag,
    Path directory
) throws IOException
```

在指定项目目录范围内为会话打标签。

**参数**：
- `sessionId` —— 要打标签的会话 UUID
- `tag` —— 标签字符串，或传 `null` 以清除
- `directory` —— 要搜索的项目工作目录

### deleteSession(String sessionId)

```java
public static void deleteSession(String sessionId) throws IOException
```

通过删除 JSONL 文件永久删除会话。同时会递归删除存放子 agent 记录的同级 `<sessionId>/` 目录
（若存在）。需要软删除的调用方，应改用 `tagSession(id, "__hidden")` 并在列举时过滤。

**参数**：
- `sessionId` —— 要删除的会话 UUID

**抛出**：
- `IllegalArgumentException` —— 如果 `sessionId` 不是合法 UUID
- `FileNotFoundException` —— 如果找不到会话文件
- `IOException` —— 如果删除失败（子 agent 目录的清理是尽力而为的，绝不会导致调用失败）

### deleteSession(String sessionId, Path directory)

```java
public static void deleteSession(
    String sessionId,
    Path directory
) throws IOException
```

在指定项目目录范围内删除会话。

### forkSession(String sessionId)

```java
public static ForkSessionResult forkSession(String sessionId) throws IOException
```

把会话分叉成一个带全新 UUID 的新分支。

**返回**：包含新会话 UUID 的 `ForkSessionResult`

**抛出**：
- `IllegalArgumentException` —— 如果 `sessionId` 不是合法 UUID
- `FileNotFoundException` —— 如果找不到会话文件
- `IOException` —— 如果分叉失败

### forkSession(String sessionId, Path directory)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    Path directory
) throws IOException
```

在指定项目目录范围内分叉会话。

### forkSession(String sessionId, Path directory, String upToMessageId, String title)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    @Nullable Path directory,
    @Nullable String upToMessageId,
    @Nullable String title
) throws IOException
```

带可选截断点与自定义标题地分叉会话。

**参数**：
- `sessionId` —— 源会话的 UUID
- `directory` —— 项目目录（null 表示搜索所有项目）
- `upToMessageId` —— 在该消息 UUID 处截断会话记录（含该条）；null 表示全部复制
- `title` —— 分叉的自定义标题；null 表示由原标题加 " (fork)" 派生

### listSessions(Path directory, Integer limit, int offset, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    int offset,
    boolean includeWorktrees
)
```

列出会话，并支持基于偏移量的分页。

**参数**：
- `directory` —— 项目目录（null 表示所有项目）
- `limit` —— 最多返回的会话数
- `offset` —— 跳过的会话数（用于分页）
- `includeWorktrees` —— 是否包含 git worktree 中的会话

## 基于 SessionStore 的方法

这些方法通过 `SessionStore` 适配器读写会话，而不是本地的 `~/.claude/projects/` 文件系统。完整的功能
文档请参见 [Session Store 指南](./feature-session-store.md)。

### projectKeyForDirectory(Path directory)

```java
public static String projectKeyForDirectory(@Nullable Path directory)
```

用与 CLI 相同的方式（realpath + NFC 归一化 + djb2 哈希净化）计算某个目录的 `SessionStore`
`project_key`。当 `directory == null` 时默认取当前工作目录。

**返回**：适合用作 `SessionKey.projectKey()` 的净化后项目键字符串。

### listSessionsFromStore(SessionStore, Path, Integer, int)

```java
public static List<SDKSessionInfo> listSessionsFromStore(
    SessionStore sessionStore,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset)
```

从 `SessionStore` 列出会话。当 `store.implementsListSessionSummaries()` 返回 `true` 时走快速路径；
否则回退为每个会话一次加载，并限制并发数为 16。

**抛出**：如果该存储既未实现 `listSessionSummaries()` 也未实现 `listSessions()`，抛出
`IllegalStateException`。

### getSessionInfoFromStore(SessionStore, String, Path)

```java
public static @Nullable SDKSessionInfo getSessionInfoFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

从存储中读取单个会话的元数据。对于非法 UUID、不存在的会话、支线会话，或无法提取摘要的会话，
返回 `null`。

### getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)

```java
public static List<SessionMessage> getSessionMessagesFromStore(
    SessionStore sessionStore, String sessionId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

从存储中读取某个会话的完整会话记录。对于非法 UUID 或不存在的会话，返回空列表。

### listSubagentsFromStore(SessionStore, String, Path)

```java
public static List<String> listSubagentsFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

通过枚举 `subagents/agent-<id>` 下的存储子键，列出某个会话的子 agent ID。

**抛出**：如果该存储未实现 `listSubkeys()`，抛出 `IllegalStateException`。

### getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)

```java
public static List<SessionMessage> getSubagentMessagesFromStore(
    SessionStore sessionStore, String sessionId, String agentId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

从存储中读取某个子 agent 的记录。合成的 `agent_metadata` 条目不会作为消息返回；读取它是为了填充每条
消息的 `parentToolUseId`（派生该子 agent 的 Agent `tool_use`）与 `parentAgentId`（对于嵌套子 agent，
指派生方子 agent）。当该条目缺失或其中的 id 不是字符串时，两者都为 null。

### renameSessionViaStore(SessionStore, String, String, Path)

```java
public static void renameSessionViaStore(
    SessionStore sessionStore, String sessionId, String title,
    @Nullable Path directory)
```

向存储中的该会话追加一条 `custom-title` 条目。

**抛出**：如果 `sessionId` 不是合法 UUID，或 `title` 为空/仅含空白，抛出 `IllegalArgumentException`。

### tagSessionViaStore(SessionStore, String, String, Path)

```java
public static void tagSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable String tag,
    @Nullable Path directory)
```

追加一条 `tag` 条目。`tag` 传 `null` 表示清除；标签在存储前会经过 Unicode 净化。

**抛出**：对于非法 UUID 或净化后为空的标签，抛出 `IllegalArgumentException`。

### deleteSessionViaStore(SessionStore, String, Path)

```java
public static void deleteSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

从存储中删除会话。如果该存储未实现 `delete()`，则为空操作（适合 WORM/仅追加的后端）。

### forkSessionViaStore(SessionStore, String, Path, String, String)

```java
public static ForkSessionResult forkSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory,
    @Nullable String upToMessageId, @Nullable String title) throws java.io.IOException
```

通过存储把会话分叉成一个带全新 UUID 的新分支。它执行与磁盘分叉相同的 UUID 重映射变换 ——
仅在存储层做拷贝是**不够**的。

**抛出**：对非法 UUID 抛出 `IllegalArgumentException`；若在存储中找不到源会话，抛出
`FileNotFoundException`。

### importSessionToStore(String, SessionStore, Path)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory)
    throws java.io.IOException
```

把本地磁盘上的会话记录回放进 `SessionStore`。这是使用 `includeSubagents=true` 与默认批大小
（`TranscriptMirrorBatcher.MAX_PENDING_ENTRIES = 500`）的便捷重载。

### importSessionToStore(String, SessionStore, Path, boolean, int)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory,
    boolean includeSubagents, int batchSize) throws java.io.IOException
```

带显式选项的完整版本。

**参数**：
- `includeSubagents` —— 递归导入 `<sessionDir>/subagents/**/*.jsonl` 与 `.meta.json` 附属文件
- `batchSize` —— 每次 `store.append()` 调用的条目数；取值 `≤ 0` 时使用默认值

**抛出**：对非法 UUID 抛出 `IllegalArgumentException`；找不到会话文件时抛出 `NoSuchFileException`。

## 版本方法

### getVersion()

```java
public static String getVersion()
```

获取 SDK 版本字符串。

**返回**：版本号（例如 "0.1.3-SNAPSHOT"）

## 另见
- [简单查询指南](./feature-simple-queries.md)
- [MCP 服务器指南](./feature-mcp-servers.md)
- [会话历史指南](./feature-session-history.md)
- [Session Store 指南](./feature-session-store.md)
