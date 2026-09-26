# ClaudeSDKClient API 参考

用于双向会话的交互式客户端。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../api-claude-sdk-client.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 类概览

```java
public class ClaudeSDKClient implements AutoCloseable
```

用于与 Claude 进行有状态、交互式会话的客户端。

## 构造函数

```java
public ClaudeSDKClient()
public ClaudeSDKClient(ClaudeAgentOptions options)
public ClaudeSDKClient(ClaudeAgentOptions options, Transport transport)
```

传入非 null 的 `transport` 时，客户端会使用它，而不是启动 CLI 子进程。SDK 会调用它的 `connect()`，通过 `write()` 写出提示词，并经由控制协议发送钩子、agent 以及其他 initialize 请求中的设置。CLI 标志类选项（model、cwd、权限模式、tools，以及子进程传输层会转换成标志的其余选项）不会应用到自定义传输层上，基于存储的会话恢复也会被跳过。

## 连接方法

### connect()

```java
public void connect() throws CLIConnectionException
```

建立与 Claude Code CLI 的连接。

**线程安全性**：线程安全，幂等

**抛出**：如果在 close() 之后调用，抛出 `IllegalStateException`

### connect(String initialMessage)

```java
public void connect(String initialMessage) throws CLIConnectionException
```

连接并发送初始消息。

### isConnected()

```java
public boolean isConnected()
```

检查是否已连接。

**返回**：`boolean`

### disconnect() / close()

```java
public void disconnect()
public void close()
```

关闭连接并清理资源。

**线程安全性**：线程安全，幂等

## 发送消息

### sendMessage(String prompt)

```java
public void sendMessage(String prompt)
```

发送消息并继续接收。

### sendMessage(String prompt, String sessionId)

```java
public void sendMessage(String prompt, String sessionId)
```

向指定会话发送消息。

### query(String prompt)

```java
public void query(String prompt)
```

发送一条消息。提示词写出后即返回 —— 请用
[`receiveResponse()`](#receiveresponse) 或 [`receiveMessages()`](#receivemessages) 读取回复。

在选项上设置 `verbatimPrompts(true)` 后，该客户端写出的每条提示词（来自 `connect(String)`、`sendMessage` 以及两种 `query` 形式）都会被标记为 `client_composed: true`，因此 Claude Code 会原样投递（不做 `@path` 展开，也不分派斜杠命令）。流式消息的标记加在副本上。

等价于 `query(prompt, "default")`。

### query(String prompt, String sessionId)

```java
public void query(String prompt, String sessionId)
```

向指定会话发起查询。

### query(Iterator&lt;Map&lt;String, Object&gt;&gt; messageStream)

```java
public void query(Iterator<Map<String, Object>> messageStream)
public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
```

发送原始的消息 map。当消息需要字符串形式无法构造的字段时 —— `origin` 归属、结构化内容块、显式的
`uuid` —— 请使用这个方法而不是 `query(String)`：

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", "Reply with exactly: one"));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));   // attribute the turn

client.query(List.of(message).iterator());
for (Message msg : client.receiveResponse()) { /* ... */ }
```

对于省略了 `session_id` 的消息，该字段会被填入 `"default"` —— 在双参数重载中则填入 `sessionId`。
添加该字段时，调用方的 map 会被复制而不是被修改，因此传入不可变 map 是安全的。

与 `query(String)` 一样，本方法会保持 CLI 的 stdin 处于打开状态，因此可以在一次会话中反复调用，
并与字符串重载自由混用。

> **v0.1.23 起有变更。** 本方法此前会把迭代器交给内部的一次性流式路径，而该路径会在迭代器耗尽时
> 关闭 stdin。那会结束会话：CLI 退出，随后的 `query()` 或 `sendMessage()` 会以
> `ProcessTransport is not ready for writing` 失败。现在它会直接写出，与 Python SDK 保持一致。
> 若你此前依赖旧行为来终止会话，请改为调用 `disconnect()`（或使用 try-with-resources）。

这些消息会在调用返回之前写出，从而保证连续调用之间的顺序。如果你需要在迭代器仍在产出时读取响应，
请在你自己的线程中驱动惰性或无界的迭代器。

## 接收消息

### receiveMessages()

```java
public Iterator<Message> receiveMessages()
```

获取遍历所有消息的迭代器（持续）。

**线程安全性**：线程安全，但消息会分散到多个迭代器上

**返回**：`Iterator<Message>`

### receiveResponse()

```java
public Iterable<Message> receiveResponse()
```

获取消息直到下一个 ResultMessage（自动关闭）。

**线程安全性**：线程安全

**返回**：`Iterable<Message>`

## 控制方法

### interrupt()

```java
public void interrupt()
```

中断当前执行。

**线程安全性**：线程安全

### setModel(String model)

```java
public void setModel(String model)
```

更换 AI 模型。

**参数**：`model` —— 模型名称或别名（例如 `"claude-sonnet-5"`、`"haiku"`），传入 `null` 表示使用默认模型。CLI 会通过 API 校验完整的模型 ID，因此已退役的 ID 会失败；别名则会映射到当前的模型。

### setPermissionMode(PermissionMode mode)

```java
public void setPermissionMode(PermissionMode mode)
```

更改权限模式。

**参数**：`mode` —— 新的权限模式

### rewindFiles(String userMessageId)

```java
public void rewindFiles(String userMessageId)
```

把被跟踪的文件回退到某条用户消息时的状态。

需要 `enableFileCheckpointing(true)` 来跟踪文件变更，并需要 `extraArgs(Map.of("replay-user-messages", ""))`，以便流中的 `UserMessage` 对象携带用于回退的 `uuid`。

**参数**：`userMessageId` —— 回退到的目标用户消息的 UUID

### getMcpStatus()

```java
public McpStatusResponse getMcpStatus()
```

获取 MCP 服务器的连接状态。

**返回**：`McpStatusResponse` —— 其 `mcpServers()` 为每个服务器列出一个 `McpServerStatus`

### reconnectMcpServer(String serverName)

```java
public void reconnectMcpServer(String serverName)
```

重试连接一个连接失败或已断开的 MCP 服务器。

### toggleMcpServer(String serverName, boolean enabled)

```java
public void toggleMcpServer(String serverName, boolean enabled)
```

启用或禁用一个 MCP 服务器。禁用会断开它，并把它的工具从可用集合中移除；启用会重新连接它，并让这些工具重新可用。

### stopTask(String taskId)

```java
public void stopTask(String taskId)
```

停止一个正在运行的后台任务。

**参数**：`taskId` —— 来自 `TaskStartedMessage` 的任务 ID

该方法返回后，CLI 会以一条状态为终态（被停止的任务为 `"killed"`）的 `TaskUpdatedMessage` 报告任务结束。随后可能还会有一条状态为 `"stopped"` 的 `TaskNotificationMessage`，但它有时会被抑制，所以应把这两种消息中任意一条的终态视为任务结束（参见 `TaskUpdatedMessage.TERMINAL_TASK_STATUSES`）。

### getContextUsage()

```java
public ContextUsageResponse getContextUsage()
```

按类别获取当前上下文窗口使用情况的细分。

返回的数据与 CLI 中 `/context` 命令所显示的相同，包括各类别的 token 数量、总用量，以及 MCP 工具、
内存文件和 agent 的详细细分。

**返回**：`ContextUsageResponse`，字段如下：
- `categories` —— `ContextUsageCategory` 列表（name、tokens、color）
- `totalTokens` —— 上下文窗口中的总 token 数
- `maxTokens` —— 实际生效的上下文上限
- `percentage` —— 已使用上下文的百分比（0-100）
- `model` —— 模型名称
- 以及可选字段：`autoCompactThreshold`、`memoryFiles`、`mcpTools`、`agents` 等

**抛出**：若未连接，抛出 `CLIConnectionException`

### getServerInfo()

```java
public Map<String, Object> getServerInfo()
```

获取服务端初始化信息：可用命令、输出样式以及服务端能力。

**返回**：`Map<String, Object>` —— 来自 `initialize` 响应的信息（仅当 CLI 未返回任何信息时为 `null`）

## 线程安全

- **connect()**：线程安全，已同步
- **发送类方法**：线程安全
- **接收类方法**：线程安全，但共享同一个队列
- **控制类方法**：线程安全
- **close()**：线程安全，幂等

## 资源管理

请始终使用 try-with-resources：

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
}
```

## 另见
- [交互式会话指南](./feature-interactive-conversations.md)
