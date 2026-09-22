# MCP 服务器（Model Context Protocol）

MCP（Model Context Protocol）让你可以创建 Claude 在会话中能够使用的自定义工具。SDK 同时支持进程内的 SDK 服务器和外部的 stdio/SSE/HTTP 服务器。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-mcp-servers.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [SDK MCP 服务器与外部服务器](#sdk-mcp-服务器与外部服务器)
- [创建 SDK MCP 服务器](#创建-sdk-mcp-服务器)
- [使用 @Tool 注解](#使用-tool-注解)
- [工具标题与注解](#工具标题与注解)
- [以编程方式创建工具](#以编程方式创建工具)
- [工具 schema](#工具-schema)
- [工具执行](#工具执行)
- [协议细节](#协议细节)
- [自定义 MCP 处理器](#自定义-mcp-处理器)
- [外部 MCP 服务器](#外部-mcp-服务器)
- [MCP 服务器状态](#mcp-服务器状态)
- [示例](#示例)
- [最佳实践](#最佳实践)

## 概览

MCP（Model Context Protocol）提供了一种标准化的方式来定义 Claude 在会话中可以调用的自定义工具。SDK 支持：

1. **SDK MCP 服务器**（进程内）—— 直接运行在你的应用中
2. **外部 MCP 服务器** —— 作为独立进程运行（stdio/SSE/HTTP）

**主要优势：**
- 用自定义功能扩展 Claude 的能力
- 可以访问你应用的状态与 API
- 类型安全的工具定义
- 自动生成 schema
- 基于 CompletableFuture 的异步执行

## SDK MCP 服务器与外部服务器

### SDK MCP 服务器（进程内）

**优点：**
- ✅ **性能更好**：没有 IPC 开销
- ✅ **部署更简单**：单一进程
- ✅ **更易调试**：同一进程、同一调试器
- ✅ **直接访问**：可直接访问应用状态
- ✅ **类型安全**：借助 Java 类型系统
- ✅ **无需序列化**：直接方法调用

**适用场景：**
- 应用专属的工具
- 数据库访问
- 业务逻辑
- 内部 API
- 测试与原型验证

### 外部 MCP 服务器

**优点：**
- ✅ **语言无关**：可用任何语言编写
- ✅ **隔离性**：独立的进程空间
- ✅ **可复用**：可在多个应用间共享
- ✅ **安全性**：进程沙箱

**适用场景：**
- 第三方工具
- 特定语言的库（Node.js、Python）
- 共享的工具服务器
- 遗留系统

## 创建 SDK MCP 服务器

创建 SDK MCP 服务器有三种方式：

1. 使用 `@Tool` 注解（声明式）
2. 使用 `SdkMcpTool.create()`（编程式）
3. 使用 `SdkMcpServer.create()`（手动）

### 快速上手

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import java.util.concurrent.CompletableFuture;

public class MyTools {
    @Tool(name = "greet", description = "Greet a user")
    public CompletableFuture<ToolResult> greet(String name) {
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
}

// Create server from annotated class
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

// Use in options
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__greet"))
    .build();
```

## 使用 @Tool 注解

`@Tool` 注解提供了一种声明式的工具定义方式。

### 基本注解

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

public class Calculator {

    @Tool(name = "add", description = "Add two numbers")
    public CompletableFuture<ToolResult> add(double a, double b) {
        double result = a + b;
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + result)
        );
    }

    @Tool(name = "multiply", description = "Multiply two numbers")
    public CompletableFuture<ToolResult> multiply(Map<String, Object> args) {
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + (a * b))
        );
    }
}
```

## 工具标题与注解

### 工具标题

使用 `title` 属性提供一个区别于技术性工具名的友好显示名称：

```java
@Tool(
    name = "fetch_user_data",
    title = "User Data Fetcher",
    description = "Fetch user data from the database"
)
public CompletableFuture<ToolResult> fetchUserData(String userId) {
    // ...
}
```

标题会同时出现在工具的顶层（MCP 2025-06-18 规定的位置）和 `annotations` 内部（更早的修订版查找的位置）—— 早于顶层字段的客户端会剥掉它不认识的内容。无论工具是否声明了 annotations，标题都会出现在 `tools/list` 中。

### 工具注解（语义提示）

使用 `annotations` 属性为工具附加行为提示。实现 `ToolAnnotations` 接口：

```java
import in.vidyalai.claude.sdk.mcp.ToolAnnotations;

public class ReadOnlyHints implements ToolAnnotations {
    @Override
    public Boolean readOnlyHint() { return true; }
}

@Tool(
    name = "read_file",
    title = "File Reader",
    description = "Read the contents of a file",
    annotations = ReadOnlyHints.class
)
public CompletableFuture<ToolResult> readFile(String path) {
    // ...
}
```

可用的注解提示：

| 提示 | 说明 |
|------|-------------|
| `readOnlyHint` | 工具只读取数据，不修改状态 |
| `destructiveHint` | 工具执行不可逆的操作 |
| `idempotentHint` | 相同输入的重复调用产生相同结果 |
| `openWorldHint` | 工具查询外部系统，结果没有上界 |
| `maxResultSizeChars` | CLI 把结果溢写到临时文件之前，结果的最大字符数 |

### maxResultSizeChars（Anthropic 特有的提示）

`maxResultSizeChars` 注解控制 CLI 的第二层工具结果溢写阈值。默认情况下，CLI 会把超过约 50K 字符的工具结果溢写到临时文件。设置该注解可以为某个特定工具调高（或调低）这个阈值。

由于 MCP SDK 的 Zod schema 会剥掉未知的注解字段，`maxResultSizeChars` 通过 `_meta` 以带命名空间的键 `anthropic/maxResultSizeChars` 在 `tools/list` 的 JSONRPC 响应中转发。

```java
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .maxResultSizeChars(200_000)  // Allow up to 200K chars
    .build();

SdkMcpTool<Map<String, Object>> bigResultTool = SdkMcpTool.builder("large_query", "Query returning large results")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text(runLargeQuery((String) args.get("query")))
    ))
    .annotations(hints)
    .build();
```

### 方法签名

被注解的方法可以有两种签名：

#### 1. 类型化参数（推荐）

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(String firstName, String lastName) {
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + firstName + " " + lastName + "!")
    );
}
```

**要求：**
- 编译时加上 `-parameters` 标志以保留参数名
- 参数会自动映射为 JSON Schema
- 类型映射：
  - `String` → `"string"`
  - `int`、`Integer`、`long`、`Long` → `"integer"`
  - `double`、`Double`、`float`、`Float` → `"number"`
  - `boolean`、`Boolean` → `"boolean"`
  - `Map<String, Object>` → `"object"`

#### 2. Map 参数

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
    String name = (String) args.get("name");
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + name + "!")
    );
}
```

**适用于：**
- 你希望手动提取参数
- schema 比较复杂
- 你需要可选参数

### 自动生成 schema

使用类型化参数时，SDK 会自动生成 JSON Schema：

```java
@Tool(name = "search", description = "Search for items")
public CompletableFuture<ToolResult> search(String query, int limit) {
    // Implementation
}
```

生成的 schema：
```json
{
    "type": "object",
    "properties": {
        "query": {
            "type": "string"
        },
        "limit": {
            "type": "integer"
        }
    },
    "required": ["query", "limit"]
}
```

### 显式 schema

对于复杂的 schema，请提供显式的 JSON：

```java
@Tool(
    name = "search",
    description = "Search for items",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results",
                    "default": 10,
                    "minimum": 1,
                    "maximum": 100
                },
                "filters": {
                    "type": "object",
                    "properties": {
                        "category": {"type": "string"},
                        "minPrice": {"type": "number"}
                    }
                }
            },
            "required": ["query"]
        }
        """
)
public CompletableFuture<ToolResult> search(Map<String, Object> args) {
    // Implementation
}
```

### 从注解创建服务器

```java
// Create server from annotated instance
Calculator calculator = new Calculator();
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    calculator
);

// Or with version
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    "1.0.0",
    calculator
);
```

## 以编程方式创建工具

若需要动态创建工具，请使用 `SdkMcpTool.create()` 或构建器：

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import java.util.concurrent.CompletableFuture;

// Simple creation
SdkMcpTool<Map<String, Object>> uppercaseTool = SdkMcpTool.create(
    "uppercase",                           // Tool name
    "Convert text to uppercase",           // Description
    Map.of(                                // JSON Schema
        "type", "object",
        "properties", Map.of(
            "text", Map.of(
                "type", "string",
                "description", "The text to convert"
            )
        ),
        "required", List.of("text")
    ),
    args -> {                              // Handler function
        String text = (String) args.get("text");
        return CompletableFuture.completedFuture(
            ToolResult.text(text.toUpperCase())
        );
    }
);

// With title and annotations using builder
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .idempotentHint(true)
    .build();

SdkMcpTool<Map<String, Object>> searchTool = SdkMcpTool.builder("search", "Search records")
    .title("Record Search")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text("Results for: " + args.get("query"))
    ))
    .annotations(hints)
    .build();
```

### 从工具创建服务器

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

// Create multiple tools
List<SdkMcpTool<?>> tools = List.of(
    uppercaseTool,
    lowercaseTool,
    reverseTool
);

// Create server
SdkMcpServer server = SdkMcpServer.create(
    "text-tools",  // Server name
    "1.0.0",       // Version
    tools          // Tool list
);

// Get config for options
McpSdkServerConfig config = server.toConfig();
```

## 工具 schema

### JSON Schema 格式

工具的 `inputSchema` 就是 JSON Schema。参数会在处理函数运行前依据它进行校验（参见[参数校验](#参数校验)）。方言取自 schema 中的 `$schema` 关键字；若没有该关键字，则假定为 Draft 2020-12 —— 也就是 MCP 规范所依据的方言。Draft 4、6、7、2019-09 和 2020-12 都能被识别。

```java
Map<String, Object> schema = Map.of(
    "type", "object",
    "properties", Map.of(
        "name", Map.of(
            "type", "string",
            "description", "User's name",
            "minLength", 1
        ),
        "age", Map.of(
            "type", "integer",
            "description", "User's age",
            "minimum", 0,
            "maximum", 150
        ),
        "email", Map.of(
            "type", "string",
            "format", "email"
        )
    ),
    "required", List.of("name", "email")
);
```

### 支持的类型

- `string` —— 文本值
- `integer` —— 整数
- `number` —— 浮点数
- `boolean` —— true/false
- `object` —— 嵌套对象
- `array` —— 值的列表
- `null` —— 空值

### 约束

```java
Map.of(
    // String constraints
    "minLength", 1,
    "maxLength", 100,
    "pattern", "^[A-Z][a-z]+$",
    "format", "email",  // email, uri, date-time, etc.

    // Number constraints
    "minimum", 0,
    "maximum", 100,
    "exclusiveMinimum", true,
    "multipleOf", 5,

    // Array constraints
    "minItems", 1,
    "maxItems", 10,
    "uniqueItems", true,

    // Enum values
    "enum", List.of("red", "green", "blue")
);
```

## 工具执行

### ToolResult

工具必须返回 `ToolResult`（包装在 CompletableFuture 中）：

```java
import in.vidyalai.claude.sdk.mcp.ToolResult;

// Text result
ToolResult.text("Hello, world!");

// JSON result — serialized into a single text block
ToolResult.json(Map.of("status", "success", "data", data));

// Image result (Base64)
ToolResult.image(base64Data, "image/png");

// Several content blocks
ToolResult.builder()
    .addText("Result:")
    .addJson(data)
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build();

// From raw MCP content blocks, normalized (see below)
ToolResult.ofContent(List.of(
    Map.of("type", "text", "text", "Result:"),
    Map.of("type", "resource_link", "name", "Docs", "uri", "https://example.com")
));

// Error result
ToolResult.error("Failed to process request");
```

#### 内容块

MCP 定义的内容类型比 CLI 能渲染的更多，因此 CLI 无法展示的那些会被折叠成文本 —— 这与 Python SDK 的转换方式一致：

| 块 | 变成 |
|---|---|
| `text` | 它本身 |
| `image` | 它本身 |
| `resource_link` | 文本：名称、URI 和描述各占一行，空白项跳过（全部缺失时为 `Resource link`） |
| 携带 `text` 的 `resource` | 那段文本 |
| 携带二进制数据的 `resource` | 丢弃，并以 `WARNING` 记录 |
| 其他任何内容 | 丢弃，并以 `WARNING` 记录 |

`addResourceLink(...)` 和 `addResource(...)` 应用同样的规则，因此处理函数可以逐块构建结果，而无需了解这些细节。

### 异步执行

工具使用 CompletableFuture 异步执行：

```java
@Tool(name = "fetch_data", description = "Fetch data from API")
public CompletableFuture<ToolResult> fetchData(String url) {
    // Async HTTP request
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()))
        .exceptionally(e -> ToolResult.error(e.getMessage()));
}
```

### 错误处理

若要报告一个模型应当看到的失败，请返回 `ToolResult.error(...)`，它会产生一个带 `isError: true` 的结果：

```java
@Tool(name = "divide", description = "Divide two numbers")
public CompletableFuture<ToolResult> divide(double a, double b) {
    if (b == 0) {
        return CompletableFuture.completedFuture(
            ToolResult.error("Cannot divide by zero")
        );
    }

    return CompletableFuture.completedFuture(
        ToolResult.text("Result: " + (a / b))
    );
}
```

你不必自己捕获一切：抛出异常的处理函数，或者以异常完成的 `CompletableFuture`，都会以同样的方式被报告（见下文的[失败语义](#失败语义)）。

### 参数校验

在处理函数运行之前，`tools/call` 中的参数会依据该工具声明的 `inputSchema` 进行校验。这是对 MCP 服务器的要求 —— *“服务器**必须**校验所有工具输入”* —— 也意味着处理函数只会看到符合它所公布契约的参数。

不匹配的调用会以 `isError: true` 的工具结果返回，文本以 `Input validation error:` 开头，并且**处理函数不会被调用**：

```
{"count": 21}            -> handler runs, returns its result
{}                       -> Input validation error: required property 'count' not found
{"count": "twenty-one"}  -> Input validation error: /count string found, integer expected
```

有两个可以依赖的推论：

- 有副作用的工具不会在参数（它从未同意接受的参数）失败之前就完成一半的变更。
- 模型收到的是一句指明违规属性的话，它可以据此行动，而不是处理函数在读取缺失或类型错误的值时恰好抛出的任何异常。

没有 schema 或 schema 为空的工具不会被校验 —— 没有可比对的东西。

#### 不是合法 JSON Schema 的 schema

校验采用**失败即关闭**的策略，与 Python SDK 一致。构建服务器时，每个 `inputSchema` 都会依据其方言的元 schema 进行检查；未通过的工具会以 `WARNING` 记录，之后对它的每次调用都会返回 `isError` 且不运行处理函数：

```
{"type": "object", "properties": "not-an-object"}
    -> Tool 'x' has an inputSchema this server cannot use, so it cannot be
       called: /properties string found, object expected
```

这件事比看上去更重要。校验器会愉快地接受一个畸形的 schema，然后依据它错误地校验：`{"type": "bogus"}` 能编译通过却不匹配任何内容，于是每次调用都会失败并归咎于调用方的参数而非真正的缺陷；而 `"properties": "a string"` 会被直接忽略，让每次调用都不经检查地放行。处理函数都不该在这两种状态下运行。

这段文本刻意**不以** `Input validation error:` 开头。那个前缀告诉模型是它的参数有问题；而损坏的 schema 是服务器的缺陷，模型绕不过去，若贴错标签会招致无休止的重试。未知关键字仍然合法 —— 带有 `x-vendor` 扩展的 schema 可以正常通过校验。

### 失败语义

各类失败如何传达给调用方：

| 情形 | 响应 | 模型看到什么 |
|---|---|---|
| 处理函数返回 `ToolResult.error(msg)` | 结果，`isError: true` | `msg` |
| 处理函数抛出异常，或其 future 失败 | 结果，`isError: true` | 异常消息；消息为 null/空白时为其类名 |
| 参数与 `inputSchema` 不匹配 | 结果，`isError: true` | `Input validation error: …`（不运行处理函数） |
| `inputSchema` 不是合法的 JSON Schema | 结果，`isError: true` | `… inputSchema this server cannot use …`（不运行处理函数） |
| 工具名未注册 | 结果，`isError: true` | `Tool '<name>' not found` |
| 调用被取消 | JSON-RPC 错误 `-32800` | 无；CLI 已经放弃等待 |
| 方法不是本服务器实现的 | JSON-RPC 错误 `-32601` | 无；模型从不发出这些 |
| `params` 缺失或格式错误 | JSON-RPC 错误 `-32602` | 无；同上 |

*工具调用*可能遇到的一切都属于**工具执行错误**：调用被处理了，并产生了一个恰好描述失败的结果，因此这段文本作为模型可以阅读并据此调整的输出到达它那里。而 JSON-RPC 错误表示请求根本无法被处理，模型永远看不到 —— 这也是为什么未知工具同样以结果的形式上报，与 Python SDK 保持一致。这些语义是 SDK 自身的约定，因此工具在两者上的行为完全相同。

### 取消正在运行的工具

CLI 对 MCP 工具调用有自己的超时（`MCP_TOOL_TIMEOUT`）。超时触发时，CLI 停止等待并发送 MCP 的 `notifications/cancelled`；SDK 以 `-32800` 回应那个挂起的调用，并丢弃处理函数最终返回的内容。

除非处理函数自己去查看，否则它会继续运行。`CompletableFuture` 无法从外部中断 —— `cancel(true)` 只是完成 future，工作本身照旧 —— 因此任何耗时较长、或带有副作用的工具，都应当在参数之外再接收一个 `ToolCallContext`：

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
        "crawl", "Fetch every page under a URL", schema,
        (args, context) -> CompletableFuture.supplyAsync(() -> {
            List<String> pages = new ArrayList<>();
            for (String url : urlsFrom(args)) {
                if (context.isCancelled()) {
                    break;              // nobody is waiting for this any more
                }
                pages.add(fetch(url));
            }
            return ToolResult.text(String.join("\n", pages));
        }));
```

`context.onCancel(runnable)` 适用于无法轮询的工作 —— 阻塞读取、调用另一个服务 —— 它给你一个关闭资源的落点；若调用已被取消，它会立即执行。`context.throwIfCancelled()` 则是给更愿意直接退栈的处理函数用的检查点形式。

只接收参数的处理函数照旧工作；它们只是无法观察到取消。用 `@Tool` 注解的方法可以在签名中的任意位置声明一个 `ToolCallContext` 参数 —— 它会被注入，并且不会出现在工具公布的 schema 中。

断开连接的效果相同：关闭客户端会放弃仍在进行中的调用，因此关机不会被某个无法中断的工具拖住。

处理函数的失败还会在本地以 `WARNING` 连同堆栈记录，这样崩溃的工具可以被调试，而不必只靠模型的会话记录。

### 长时间运行的操作

```java
@Tool(name = "process_large_file", description = "Process a large file")
public CompletableFuture<ToolResult> processFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Long-running operation
            byte[] data = Files.readAllBytes(Path.of(path));
            String result = processData(data);
            return ToolResult.text("Processed: " + result);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    });
}
```

## 协议细节

### 协议版本

服务器公布 `2025-06-18` 和 `2024-11-05`，新的在前。在 `initialize` 时，如果客户端请求的版本它支持，就回显该版本；否则回应它支持的最新版本 —— 这正是规范规定的握手方式。

`2025-03-26` 被刻意排除。该修订版把 JSON-RPC 批处理列为*接收*端必须支持的能力，而一个批次是顶层数组，承载这些消息的控制请求把它们定型为 map，因而无法表达。宣称一个 SDK 无法兑现其唯一必要变更的版本，等于给客户端一个它会据以行动的承诺。

### 方法

已实现 `initialize`、`ping`、`tools/list` 和 `tools/call`。其他一律回应 `-32601`，这是正确的而非缺失：服务器只公布 `tools` 能力，因此符合规范的客户端绝不会请求 resources、prompts 或 completions。（已对 CLI 验证：只声明 `tools` 的服务器不会收到 `resources/list` 或 `prompts/list`。）

### 通知

JSON-RPC 通知 —— 有 `method` 但没有 `id` 的消息 —— 绝不会收到响应，这是 JSON-RPC 的要求。`notifications/initialized` 和 `notifications/cancelled` 会被处理；其他一律以 `FINE` 记录并丢弃。承载该通知的*控制请求*仍会被确认，回应 `{"jsonrpc": "2.0", "result": {}}`，否则 CLI 会一直等下去。

完全没有 `method` 的消息是 JSON-RPC 响应，或者就是垃圾。SDK 不向 CLI 发送任何请求，因此这样到达的内容都不归它匹配：它会被忽略而不是回应。

## 自定义 MCP 处理器

`McpSdkServerConfig` 持有的是一个 `McpMessageHandler`，而不是特指 `SdkMcpServer`。你可以直接实现该接口，来提供 `SdkMcpServer` 未覆盖的 MCP 部分 —— resources、prompts、completions —— 或者适配第三方的 MCP 库：

```java
public class MyMcpServer implements McpMessageHandler {

    @Override
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        // Return the JSON-RPC response for a request, or null for a
        // notification, which must never be answered.
        ...
    }

    @Override
    public void close() {
        // Optional: the connection using this handler is going away.
    }
}

var options = ClaudeAgentOptions.builder()
        .mcpServers(Map.of("mine", new McpSdkServerConfig("mine", new MyMcpServer())))
        .build();
```

CLI 发送什么取决于 `initialize` 返回的 `capabilities`，因此公布了 resources 的处理器就会被请求 resources。

`close()` 的含义是“使用你的这条连接要消失了”，而不是“关停”：同一个处理器可以注册给多个客户端，因此它必须是幂等的，并在之后仍然可用。出于同样的原因，请为每条连接注册一个 `SdkMcpServer` —— 两条活动连接共用一个服务器可能发出相同的 JSON-RPC id，此时第二个调用会被以 `-32603` 拒绝，而不是冒着响应送错调用方的风险。

## 外部 MCP 服务器

### Stdio 服务器

```java
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;

McpStdioServerConfig server = new McpStdioServerConfig(
    "node",                              // Command
    List.of("path/to/server.js"),        // Arguments
    Map.of("NODE_ENV", "production")     // Environment variables
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("external", server))
    .build();
```

### SSE 服务器

```java
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;

McpSseServerConfig server = new McpSseServerConfig(
    "http://localhost:8080/sse"  // SSE endpoint URL
);
```

### HTTP 服务器

```java
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;

McpHttpServerConfig server = new McpHttpServerConfig(
    "http://localhost:8080"  // Base URL
);
```

### 混合使用

你可以同时使用 SDK 服务器和外部服务器：

```java
// SDK server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "app-tools",
    new MyTools()
);

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("external-server.js"),
    Map.of()
);

// Configure both
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "app", sdkServer,
        "external", externalServer
    ))
    .allowedTools(List.of(
        "mcp__app__my_tool",
        "mcp__external__their_tool"
    ))
    .build();
```

### 严格的 MCP 配置

默认情况下，除了你通过 `mcpServers(...)` 传入的服务器之外，CLI 还会从项目的 `.mcp.json`、用户／全局设置以及各个插件中加载 MCP 服务器。设置 `strictMcpConfig(true)` 可以忽略除你传入之外的一切：

```java
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("app", sdkServer))
    .strictMcpConfig(true)   // ignore project / user / plugin MCP configs
    .build();
```

对应 CLI 的 `--strict-mcp-config` 标志。当你想精确掌控哪些 MCP 服务器可达时，这对可复现的部署或测试隔离很有用。

## 示例

### 示例 1：计算器

```java
public class Calculator {

    @Tool(name = "calculate", description = "Perform calculations")
    public CompletableFuture<ToolResult> calculate(
            double a, double b, String operation) {

        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> {
                if (b == 0) {
                    return CompletableFuture.completedFuture(
                        ToolResult.error("Cannot divide by zero")
                    );
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException(
                "Unknown operation: " + operation
            );
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(a + " " + operation + " " + b + " = " + result)
        );
    }
}

// Usage
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    new Calculator()
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", server))
    .allowedTools(List.of("mcp__calc__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Calculate 15 * 7, then 100 / 4");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 示例 2：数据库访问

```java
public class DatabaseTools {
    private final DataSource dataSource;

    public DatabaseTools(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "query_users", description = "Query users from database")
    public CompletableFuture<ToolResult> queryUsers(String filter) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE name LIKE ?")) {

                stmt.setString(1, "%" + filter + "%");
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(Map.of(
                        "id", rs.getInt("id"),
                        "name", rs.getString("name"),
                        "email", rs.getString("email")
                    ));
                }

                return ToolResult.json(Map.of(
                    "count", users.size(),
                    "users", users
                ));

            } catch (SQLException e) {
                return ToolResult.error("Database error: " + e.getMessage());
            }
        });
    }
}
```

### 示例 3：集成 API

```java
public class WeatherTools {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public WeatherTools(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "get_weather", description = "Get current weather")
    public CompletableFuture<ToolResult> getWeather(String city) {
        String url = "https://api.weather.com/weather?city=" + city +
                     "&key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                // Parse JSON response
                Map<String, Object> data = parseJson(response.body());
                return ToolResult.json(data);
            })
            .exceptionally(e -> ToolResult.error(
                "Failed to fetch weather: " + e.getMessage()
            ));
    }
}
```

## 最佳实践

### 1. 使用合适的返回类型

```java
// ✅ Good: Specific result types
ToolResult.text("Simple text response");
ToolResult.json(Map.of("key", "value"));
ToolResult.error("Error message");

// ❌ Bad: Always using text for structured data
ToolResult.text("{\"key\":\"value\"}");  // Should use json()
```

### 2. 优雅地处理错误

```java
// ✅ Good: Proper error handling
@Tool(name = "read_file", description = "Read a file")
public CompletableFuture<ToolResult> readFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            String content = Files.readString(Path.of(path));
            return ToolResult.text(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    });
}

// ❌ Bad: Throwing exceptions
public CompletableFuture<ToolResult> readFile(String path) {
    String content = Files.readString(Path.of(path));  // Throws!
    return CompletableFuture.completedFuture(ToolResult.text(content));
}
```

### 3. 提供好的描述

```java
// ✅ Good: Descriptive and clear
@Tool(
    name = "search_products",
    description = "Search for products by name, category, or price range. " +
                  "Returns a list of matching products with details."
)

// ❌ Bad: Vague description
@Tool(name = "search", description = "Search")
```

### 4. 复杂输入请使用显式 schema

SDK 依据声明的 schema 来校验参数，因此它对输入描述得越精确，处理函数能默认成立的前提就越多 —— 模型调用出错时得到的消息也越有用。没有 schema 的工具完全不会被校验。

```java
// ✅ Good: Explicit schema with validation
@Tool(
    name = "create_user",
    description = "Create a new user",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "email": {"type": "string", "format": "email"},
                "age": {"type": "integer", "minimum": 18}
            },
            "required": ["email"]
        }
        """
)

// ❌ Bad: No validation
@Tool(name = "create_user", description = "Create user")
public CompletableFuture<ToolResult> createUser(Map<String, Object> args)
```

### 5. 让工具保持聚焦

```java
// ✅ Good: Single responsibility
@Tool(name = "add_numbers", description = "Add two numbers")
@Tool(name = "multiply_numbers", description = "Multiply two numbers")

// ❌ Bad: Too much in one tool
@Tool(name = "math", description = "Do any math operation")
```

### 6. I/O 操作使用异步

```java
// ✅ Good: Async I/O
@Tool(name = "fetch", description = "Fetch URL")
public CompletableFuture<ToolResult> fetch(String url) {
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()));
}

// ❌ Bad: Blocking I/O
public CompletableFuture<ToolResult> fetch(String url) {
    String result = blockingHttpCall(url);  // Blocks!
    return CompletableFuture.completedFuture(ToolResult.text(result));
}
```

### 7. 配置工具权限

```java
// ✅ Good: Explicitly allow tools
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .allowedTools(List.of(
        "mcp__calc__add",
        "mcp__calc__subtract"
    ))
    .build();

// ❌ Bad: Allowing all tools (security risk)
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .build();  // All tools allowed!
```

## MCP 服务器状态

`ClaudeSDKClient.getMcpStatus()` 返回一个 `McpStatusResponse`，包含所有已配置 MCP 服务器的当前连接状态。

### McpStatusResponse

```java
record McpStatusResponse(
    List<McpServerStatus> mcpServers   // list of server status entries
)
```

### McpServerStatus

```java
record McpServerStatus(
    String name,                               // server name as configured
    McpServerConnectionStatus status,          // connection state
    @Nullable McpServerInfo serverInfo,        // info from MCP handshake (when connected)
    @Nullable String error,                    // error message (when status = FAILED)
    @Nullable McpServerStatusConfig config,    // server configuration
    @Nullable String scope,                    // config scope (project, user, local)
    @Nullable List<McpToolInfo> tools          // available tools (when connected)
)
```

### McpServerConnectionStatus

```java
enum McpServerConnectionStatus {
    CONNECTED,    // server is connected and ready
    FAILED,       // connection attempt failed
    NEEDS_AUTH,   // server requires authentication
    PENDING,      // connection in progress
    DISABLED      // server is disabled
}
```

### McpServerInfo

```java
record McpServerInfo(
    String name,      // server name from MCP handshake
    String version    // server version from MCP handshake
)
```

### McpToolInfo

```java
record McpToolInfo(
    String name,                               // tool name
    @Nullable String description,              // tool description
    @Nullable McpToolAnnotations annotations   // behavioral hints
)
```

### McpServerStatusConfig（密封接口）

表示状态响应中的服务器配置。它是多态的 —— 请使用模式匹配：

```java
switch (server.config()) {
    case McpStdioServerConfig c -> System.out.println("stdio: " + c.command());
    case McpSseServerConfig c -> System.out.println("sse: " + c.url());
    case McpHttpServerConfig c -> System.out.println("http: " + c.url());
    case McpSdkServerConfigStatus c -> System.out.println("sdk: " + c.name());
    case McpClaudeAIProxyServerConfig c -> System.out.println("proxy: " + c.url());
    case null -> {}
}
```

### 示例：检查 MCP 状态

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    McpStatusResponse status = client.getMcpStatus();
    for (McpServerStatus server : status.mcpServers()) {
        System.out.printf("[%s] %s%n", server.status(), server.name());
        if (server.status() == McpServerConnectionStatus.CONNECTED) {
            if (server.tools() != null) {
                server.tools().forEach(t -> System.out.println("  - " + t.name()));
            }
        } else if (server.status() == McpServerConnectionStatus.FAILED) {
            System.err.println("  Error: " + server.error());
        }
    }
}
```

## 另见

- [配置选项](./feature-configuration-options.md) —— mcpServers 与 tools 选项
- [工具使用示例](../../examples/src/main/java/examples/McpServer.java) —— 完整示例
- [自动生成 schema 示例](../../examples/src/main/java/examples/AutoSchemaGeneration.java)
- [MCP 规范](https://spec.modelcontextprotocol.io/) —— 官方 MCP 协议文档
