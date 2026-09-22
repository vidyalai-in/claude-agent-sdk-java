# 交互式会话

交互式会话让你可以使用 `ClaudeSDKClient` 类与 Claude 进行多轮、有状态的交互。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-interactive-conversations.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [何时使用 ClaudeSDKClient](#何时使用-claudesdkclient)
- [基础用法](#基础用法)
- [连接管理](#连接管理)
- [发送消息](#发送消息)
- [接收消息](#接收消息)
- [控制方法](#控制方法)
- [会话管理](#会话管理)
- [线程安全](#线程安全)
- [资源管理](#资源管理)
- [示例](#示例)
- [最佳实践](#最佳实践)

## 概览

`ClaudeSDKClient` 提供了对与 Claude 双向会话的完全掌控。与简单的 `ClaudeSDK.query()` facade 不同，
该客户端：

- **保持状态**：会话上下文在多条消息之间得以保留
- **双向**：可随时发送和接收消息
- **交互式**：可根据响应发送追问
- **可控制**：可动态中断、更换模型、调整权限
- **会话感知**：支持恢复与分叉会话

## 何时使用 ClaudeSDKClient

### ✅ 非常适合

1. **聊天界面**
   ```java
   try (var client = ClaudeSDK.createClient()) {
       client.connect();
       while (userInput = getUserInput()) {
           client.sendMessage(userInput);
           for (var msg : client.receiveResponse()) {
               display(msg);
           }
       }
   }
   ```

2. **类 REPL 的界面**
   ```java
   while (true) {
       String command = console.readLine();
       client.sendMessage(command);
       processResponse(client.receiveResponse());
   }
   ```

3. **多轮会话**
   ```java
   client.sendMessage("What is Python?");
   // ... process response
   client.sendMessage("Show me a code example");
   // ... context preserved
   ```

4. **交互式调试**
   ```java
   client.sendMessage("Analyze this error");
   var response = client.receiveResponse();
   if (needsMoreInfo) {
       client.sendMessage("Here's more context...");
   }
   ```

5. **长时运行的会话**
   ```java
   try (var client = ClaudeSDK.createClient(options)) {
       client.connect();
       // Hours-long session with state
   }
   ```

### ❌ 不太合适

- 简单的一次性提问 → 请用 `ClaudeSDK.query()`
- 批处理 → 请用 `ClaudeSDK.query()`
- 发完即走的脚本 → 请用 `ClaudeSDK.query()`

## 基础用法

### 创建与连接

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Create with default options
ClaudeSDKClient client = ClaudeSDK.createClient();

// Or with custom options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(20)
    .build();
ClaudeSDKClient client = ClaudeSDK.createClient(options);

// Connect (establishes subprocess)
client.connect();
```

### 简单会话

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // First message
    client.sendMessage("What is 2 + 2?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }

    // Follow-up (context preserved)
    client.sendMessage("What about 3 + 3?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 连接时附带初始消息

```java
// Connect and send initial message in one call
client.connect("Hello, Claude!");

for (var msg : client.receiveResponse()) {
    // Process initial response
}
```

## 连接管理

### connect()

建立与 Claude Code CLI 的连接。

```java
client.connect();  // No initial message
client.connect("Initial prompt");  // With initial message
```

**线程安全**：线程安全且幂等。多个并发调用会受到保护。

**抛出**：
- `IllegalStateException` —— 如果在 `close()` 之后调用
- `CLIConnectionException` —— 如果连接失败

### isConnected()

检查客户端是否已连接。

```java
if (client.isConnected()) {
    client.sendMessage("Hello");
}
```

### disconnect() / close()

关闭连接并清理资源。

```java
client.disconnect();  // Explicit disconnect
// or
client.close();  // AutoCloseable

// Best practice: use try-with-resources
try (var client = ClaudeSDK.createClient()) {
    // Use client
}  // Automatically closed
```

**线程安全**：线程安全且幂等。可以安全地多次调用。

## 发送消息

### sendMessage(String prompt)

发送一条消息并继续接收。

```java
client.sendMessage("Hello, Claude!");
```

**适用时机**：你想发送一条消息，并继续监听所有事件。

### sendMessage(String prompt, String sessionId)

向指定会话发送消息。

```java
client.sendMessage("Hello!", "session-1");
```

### query(String prompt)

发送一条消息并只接收其响应（会阻塞直到 ResultMessage）。

```java
List<Message> response = client.query("What is 2 + 2?");
```

**适用时机**：你想要请求/响应模式（发送后等待完整响应）。

### query(String prompt, String sessionId)

针对指定会话 ID 发起查询。

```java
List<Message> response = client.query("Question", "session-1");
```

### query(Iterator<Map<String, Object>> messageStream)

以流的形式发送多条消息。

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Second"))
);

List<Message> responses = client.query(messages.iterator());
```

## 接收消息

### receiveMessages()

返回遍历**所有**消息的迭代器（持续的消息流）。

```java
Iterator<Message> messages = client.receiveMessages();

while (messages.hasNext()) {
    Message msg = messages.next();
    // Process each message as it arrives

    if (shouldStop(msg)) {
        break;
    }
}
```

**适用时机**：
- 你想持续处理消息
- 你在处理多个会话
- 你需要看到包括系统消息在内的所有事件

**特征**：
- 迭代器会阻塞直到有消息可用
- 会一直返回消息，直到流结束
- 多个迭代器共享同一个队列（消息会被分散）

### receiveResponse()

返回一个在下一个 ResultMessage 处停止的迭代器。

```java
Iterable<Message> response = client.receiveResponse();

for (Message msg : response) {
    // Process messages until ResultMessage
}
// Iterator auto-closes when ResultMessage received
```

**适用时机**：
- 你想要请求/响应模式
- 你在等待某次查询完成
- 你希望在 ResultMessage 处自动停止

**特征**：
- 阻塞直到有消息可用
- 在 ResultMessage 处停止并自动关闭
- 返回一次完整响应的全部消息

### 处理消息

```java
for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());

            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Tool: " + tool.name());
                }
            }
        }

        case ResultMessage result -> {
            System.out.println("Done! Cost: $" + result.totalCostUsd());
            System.out.println("Stop reason: " + result.stopReason());
        }

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            System.out.println("Partial: " + event.delta());
    }
}
```

## 控制方法

### interrupt()

中断当前执行。

```java
// In another thread
client.interrupt();
```

**适用场景**：
- 用户取消操作
- 达到超时
- 停止昂贵的操作

### setModel(String model)

在会话中途更换 AI 模型。

```java
client.setModel("claude-opus-4-6");
```

**适用场景**：
- 为复杂任务切换到更强的模型
- 为简单问题切换到更便宜的模型

### setPermissionMode(PermissionMode mode)

在会话期间更改权限模式。

```java
client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
```

**可用模式**：
- `ACCEPT_ALL` —— 自动接受所有权限
- `ACCEPT_EDITS` —— 自动接受编辑，其他情况询问
- `BYPASS_PERMISSIONS` —— 跳过所有权限检查
- `PROMPT` —— 所有权限都询问（默认）

### rewindFiles(String userMessageId)

把文件回退到某条用户消息时的状态（需要启用检查点）。

```java
// Enable checkpointing in options
var options = ClaudeAgentOptions.builder()
    .checkpointFiles(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Create file.txt");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user) {
            String messageId = user.id();
            // Save ID for later
        }
    }

    // Later: rewind to that message
    client.rewindFiles(messageId);
}
```

### getMcpStatus()

获取 MCP 服务器连接的状态。

```java
Map<String, Object> status = client.getMcpStatus();
System.out.println("MCP servers: " + status);
```

### getServerInfo()

获取服务端初始化信息。

```java
Map<String, Object> info = client.getServerInfo();
System.out.println("CLI version: " + info.get("version"));
```

## 会话管理

### 默认会话

默认情况下，所有消息都使用 "default" 会话。

```java
client.sendMessage("Hello");  // Uses "default" session
```

### 多个会话

向不同的会话发送消息。

```java
// Session 1
client.sendMessage("Analyze code.java", "session-1");

// Session 2
client.sendMessage("Write tests", "session-2");

// Receive from all sessions
for (var msg : client.receiveMessages()) {
    // Process messages from any session
}
```

### 恢复之前的会话

```java
// First conversation
var options1 = ClaudeAgentOptions.builder()
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
}

// Later: resume with context
var options2 = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more");
    // Has context from previous session
}
```

### 分叉会话

分叉会从既有会话创建一个新会话。

```java
var options = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .forkSession(true)  // Fork instead of continue
    .build();
```

**区别**：
- `resume(id)` —— 继续同一个会话
- `resume(id) + forkSession(true)` —— 以相同上下文创建新会话

## 线程安全

`ClaudeSDKClient` 是**部分线程安全的**：

### 线程安全的操作

```java
// ✅ Safe: Multiple threads can send
Thread t1 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 1"));
Thread t2 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 2"));

// ✅ Safe: Control methods
client.interrupt();
client.setModel("claude-sonnet-4-5");
client.setPermissionMode(PermissionMode.ACCEPT_ALL);

// ✅ Safe: connect() is synchronized
client.connect();  // Only one connection established
```

### 共享状态

```java
// ⚠️ Warning: Multiple iterators share the queue
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();

// Messages distributed across both iterators!
// Typically use only one iterator per client
```

### 最佳实践

```java
// ✅ Good: One receive loop per client
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Dedicated receive thread
    Thread.ofVirtual().start(() -> {
        for (var msg : client.receiveMessages()) {
            processMessage(msg);
        }
    });

    // Main thread sends
    client.sendMessage("Question 1");
    client.sendMessage("Question 2");
}
```

## 资源管理

### AutoCloseable

请始终使用 try-with-resources：

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
}  // Automatically cleaned up
```

### 手动清理

如果不使用 try-with-resources：

```java
ClaudeSDKClient client = ClaudeSDK.createClient();
try {
    client.connect();
    // Use client
} finally {
    client.close();  // Important!
}
```

### 会被清理的资源

在 `close()` 时，客户端会清理：
- QueryHandler 与线程池
- 流式执行器
- 传输层与 CLI 子进程
- 消息队列与迭代器

## 示例

### 示例 1：交互式聊天

```java
import java.util.Scanner;

public class Chat {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options);
             var scanner = new Scanner(System.in)) {

            client.connect();
            System.out.println("Chat started! Type 'exit' to quit.");

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                client.sendMessage(input);

                System.out.print("Claude: ");
                for (var msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.print(assistant.getTextContent());
                    }
                }
                System.out.println();
            }
        }
    }
}
```

### 示例 2：中断长时间操作

```java
import java.util.concurrent.TimeUnit;

public class InterruptExample {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start long operation in background
            Thread.ofVirtual().start(() -> {
                client.sendMessage("Analyze all files in this large codebase");
                for (var msg : client.receiveResponse()) {
                    System.out.println(msg);
                }
            });

            // Wait 5 seconds then interrupt
            TimeUnit.SECONDS.sleep(5);
            System.out.println("Interrupting...");
            client.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 示例 3：动态切换模型

```java
public class ModelSwitching {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Simple question with Haiku
            client.setModel("claude-haiku-4-5");
            client.sendMessage("What is 2+2?");
            processResponse(client.receiveResponse());

            // Complex question with Opus
            client.setModel("claude-opus-4-6");
            client.sendMessage("Explain quantum entanglement");
            processResponse(client.receiveResponse());

            // Back to Sonnet for balanced tasks
            client.setModel("claude-sonnet-4-5");
            client.sendMessage("Write a Java function");
            processResponse(client.receiveResponse());
        }
    }
}
```

### 示例 4：多会话管理

```java
public class MultiSession {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start multiple tasks in different sessions
            client.sendMessage("Review code.java for bugs", "review");
            client.sendMessage("Write tests for util.java", "testing");
            client.sendMessage("Document api.java", "docs");

            // Process responses from all sessions
            for (var msg : client.receiveMessages()) {
                switch (msg) {
                    case AssistantMessage a ->
                        System.out.println("[Session] " + a.getTextContent());
                    case ResultMessage r ->
                        System.out.println("[Done] Cost: $" + r.totalCostUsd());
                    default -> {}
                }

                // Stop when all three sessions complete
                if (allSessionsComplete()) {
                    break;
                }
            }
        }
    }
}
```

### 示例 5：文件检查点

```java
public class Checkpointing {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .checkpointFiles(true)
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String checkpointId = null;

            // Create a file and save checkpoint
            client.sendMessage("Create test.txt with 'Hello'");
            for (var msg : client.receiveResponse()) {
                if (msg instanceof UserMessage user) {
                    checkpointId = user.id();
                }
            }

            // Modify the file
            client.sendMessage("Append 'World' to test.txt");
            for (var msg : client.receiveResponse()) {}

            // Rewind to original state
            if (checkpointId != null) {
                client.rewindFiles(checkpointId);
                System.out.println("Rewound to checkpoint");
            }
        }
    }
}
```

## 最佳实践

### 1. 使用 try-with-resources

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    client.connect();
}

// ❌ Bad: Resource leak
var client = ClaudeSDK.createClient();
client.connect();
// Forgot to close!
```

### 2. 处理连接错误

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    try {
        client.connect();
    } catch (CLIConnectionException e) {
        System.err.println("Failed to connect: " + e.getMessage());
        return;
    }
    // Use client
}
```

### 3. 请求/响应场景请用 receiveResponse()

```java
// ✅ Good: Clean request/response
client.sendMessage("Question");
for (var msg : client.receiveResponse()) {
    // Processes until ResultMessage
}

// ❌ Bad: Manual ResultMessage checking
for (var msg : client.receiveMessages()) {
    if (msg instanceof ResultMessage) break;
}
```

### 4. 不要创建多个接收迭代器

```java
// ✅ Good: Single iterator
Iterator<Message> messages = client.receiveMessages();

// ❌ Bad: Messages split across iterators
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();
```

### 5. 设置合适的上限

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(50)  // Long conversation
    .maxBudgetUsd(5.0)
    .build();

// ❌ Bad: No limits in interactive session
var client = ClaudeSDK.createClient();  // Could be expensive!
```

### 6. 处理所有消息类型

```java
// ✅ Good: Exhaustive pattern matching
for (var msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage u -> handleUser(u);
        case AssistantMessage a -> handleAssistant(a);
        case ResultMessage r -> handleResult(r);
        case SystemMessage s -> handleSystem(s);
        case StreamEvent e -> handleStream(e);
    }
}
```

### 7. 恰当地使用控制方法

```java
// ✅ Good: Switch models based on task complexity
if (isComplexTask) {
    client.setModel("claude-opus-4-6");
}

client.sendMessage(task);

// ✅ Good: Interrupt on timeout
CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
    .execute(() -> client.interrupt());
```

## 另见

- [简单查询](./feature-simple-queries.md) —— 用于一次性查询
- [配置选项](./feature-configuration-options.md) —— 全部 ClaudeAgentOptions
- [消息类型](./feature-message-types.md) —— 理解消息
- [ClaudeSDKClient API 参考](./api-claude-sdk-client.md) —— 完整的 API 文档
