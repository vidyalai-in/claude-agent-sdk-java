# 异常类型 API 参考

错误处理与异常层次结构。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../api-exceptions.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 异常层次结构

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
├── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

## ClaudeSDKException

所有 SDK 错误的基类异常。

```java
public class ClaudeSDKException extends RuntimeException {
    public ClaudeSDKException(String message);
    public ClaudeSDKException(String message, Throwable cause);
}
```

## CLIConnectionException

连接 Claude Code CLI 失败。

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**原因**：
- 找不到 CLI
- 进程启动失败
- 连接超时
- 网络问题（远程传输）

## CLINotFoundException

找不到 Claude Code CLI 可执行文件。

```java
public class CLINotFoundException extends ClaudeSDKException {
    public CLINotFoundException(String message);
}
```

**解决办法**：
- 安装 Claude Code CLI
- 用 `.cliPath()` 指定自定义路径

## ProcessException

CLI 进程失败或崩溃。

```java
public class ProcessException extends ClaudeSDKException {
    public ProcessException(String message);
    public ProcessException(String message, Throwable cause);
}
```

**原因**：
- CLI 崩溃
- 参数无效
- 资源耗尽

**错误结果退出后的可操作错误**：当 CLI 发出 `isError=true` 的 `ResultMessage`（例如
`error_max_turns`、`error_during_execution`，或设置了 `apiErrorStatus` 的 `success` 子类型）时，
它随后会有意以非零状态码退出。尾随的 `ProcessException` 只会携带
`"Command failed with exit code N"`，这并不可操作，因此读取器会把它替换为 `ResultException`
（见下文）。这种替换是按轮次进行的 —— 运行后期新出现的崩溃仍保留其原本的 `ProcessException` 消息。

## ResultException

CLI 报告了一个终止性错误结果并退出。它是 `ProcessException` 的子类，因此现有的
`catch (ProcessException e)` 处理逻辑仍然有效。

```java
public class ResultException extends ProcessException {
    public ResultException(String message, @Nullable Map<String, Object> data,
                           @Nullable Integer exitCode);

    @Nullable public String subtype();          // "error_max_turns", "error_during_execution",
                                                // ... or "success" for a mid-turn API failure
    public List<String> errors();               // never null; empty for API failures
    @Nullable public String result();           // result text; the "API Error: ..." prose
    @Nullable public Integer apiErrorStatus();  // HTTP status of the failing API call
    @Nullable public String terminalReason();   // e.g. "api_error", "max_turns"
    @Nullable public String sessionId();
    public Map<String, Object> data();          // raw result payload, unmodifiable
}
```

消息内容是 `"Claude Code returned an error result: <text>"` 再加上 `ProcessException` 的
`" (exit code: N)"` 后缀。`<text>` 是该 result 的 `errors` 数组以 `"; "` 连接而成，若无则依次回退到
result 文本、非 `success` 的 `subtype`，最后是 `"API error (HTTP <status>)"`。非零退出所对应的原始
`ProcessException` 就是 `getCause()`。

请根据负载而不是文本来分支处理：

```java
} catch (ResultException e) {
    if ("api_error".equals(e.terminalReason())) {
        retry();
    } else if ("error_max_turns".equals(e.subtype())) {
        // ...
    }
}
```

**它会在哪里出现：**

- 进行收集的 `ClaudeSDK.query(...)` 系列会把它包装进 `QueryFailedException`，以免失败前收到的消息
  丢失；此时 `ResultException` 就是那个异常的 `getCause()`。这是最常见的遇见方式。
- 直接出现，来自失败的控制请求 —— 最重要的是 CLI 在启动时拒绝的 `initialize`（被
  `resumeDropsTurn` 拒绝的恢复）。这发生在收集任何消息之前，因此不会被包装。
- **不会**来自 `ClaudeSDKClient.receiveResponse()`：该方法在 `ResultMessage` 处结束（与 Python SDK
  的 `receive_response()` 完全一致），因此永远观察不到 CLI 的退出。请在那里改为检查
  `ResultMessage.isError()`。`receiveMessages()` 会一直运行到流结束，确实会抛出异常，但在活跃的
  client 上 stdin 保持打开，因此会话中途的错误结果并不会结束该流。

## CLIJSONDecodeException

解析来自 CLI 的 JSON 失败。

```java
public class CLIJSONDecodeException extends ClaudeSDKException {
    public CLIJSONDecodeException(String message, Throwable cause);
}
```

**原因**：
- JSON 格式错误
- 格式不符合预期
- CLI 版本不匹配

## MessageParseException

把消息解析为类型化对象失败。

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**原因**：
- 未知的消息类型
- 缺少必需字段
- 类型转换错误

## QueryFailedException

一次进行收集的查询以错误结果结束。它携带着此前已经到达的消息。

```java
public class QueryFailedException extends ClaudeSDKException {
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages);

    public List<Message> partialMessages();   // never null; unmodifiable
    public ResultMessage resultMessage();     // last ResultMessage received, or null
}
```

**原因**：
- `error_max_turns` —— 达到 `maxTurns`
- `error_max_budget_usd` —— 达到 `maxBudgetUsd`
- `error_during_execution` —— 包括被 `resumeDropsTurn` 拒绝的恢复

**它为什么存在**：CLI 报告这些情况的方式，是发出一个*完整*的轮次 —— 助手消息，加上携带 subtype、
费用与用量的最终 `ResultMessage` —— 然后才有意以非零状态码退出，这是为了照顾 shell 使用者。流式
API（`ClaudeSDKClient.receiveMessages()` 与 `receiveResponse()`）会在每条消息到达时就交给消费者，
只在最后才抛出，因此那里不会丢失任何东西。而进行收集的调用只能二选一：返回一个列表，或者抛出异常；
抛出本异常可以同时携带错误与消息，从而让 `ClaudeSDK.query(...)` 和流式路径一样信息完整。

只有进行收集的 `ClaudeSDK.query(...)` 系列会抛出它（包括委托给它的 `queryForText` 与
`queryForResult`）。由于它继承自 `ClaudeSDKException`，现有的 `catch (ClaudeSDKException e)` 代码
块无需改动即可继续工作。

```java
try {
    List<Message> messages = ClaudeSDK.query("Summarize the README", options);
    // ... normal path
} catch (QueryFailedException e) {
    // The turn is usually complete — inspect what actually happened.
    ResultMessage result = e.resultMessage();
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped by the budget cap after $%.4f%n", result.totalCostUsd());
    }
    for (Message msg : e.partialMessages()) {
        if (msg instanceof AssistantMessage a) {
            System.out.println(a.getTextContent());
        }
    }
}
```

当运行在产出任何内容之前就失败时（例如 CLI 根本无法启动），`partialMessages()` 为空。它不会被序列化
—— 反序列化得到的实例会报告空列表而不是 null，因为 `Message` 并未声明为 `Serializable`。

只要你设置了 `maxTurns` 或 `maxBudgetUsd`，就应当捕获它：达到你自己配置的上限是预期内的结果，
而不是崩溃。

## 错误处理示例

### 基本的 try-catch

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (CLINotFoundException e) {
    System.err.println("Claude CLI not installed");
} catch (CLIConnectionException e) {
    System.err.println("Connection failed: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI crashed: " + e.getMessage());
} catch (QueryFailedException e) {
    // Run stopped at a limit; the messages so far are still available.
    System.err.println("Run ended early: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

顺序很重要：`QueryFailedException` 必须在 `ClaudeSDKException` 之前捕获，因为它是后者的子类。

### 配合资源管理

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
} catch (CLIConnectionException e) {
    log.error("Failed to connect", e);
    throw new ApplicationException("Service unavailable", e);
} catch (ClaudeSDKException e) {
    log.error("SDK error", e);
    throw new ApplicationException("Internal error", e);
}
```

### 重试逻辑

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return ClaudeSDK.query(prompt, options);
    } catch (CLIConnectionException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(1000 * (i + 1));  // Exponential backoff
    }
}
```

## 另见
- [错误处理示例](../../examples/src/main/java/examples/ErrorHandling.java)
